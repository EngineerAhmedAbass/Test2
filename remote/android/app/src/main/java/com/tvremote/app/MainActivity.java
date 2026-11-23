package com.tvremote.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.Settings;
import android.view.View;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Vibrator;
import android.view.KeyEvent;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * ريموت للتليفزيون: الواجهة HTML (من remote/public) جوه WebView،
 * والاتصال بالتليفزيون من هنا (TvClient) عن طريق الواي فاي.
 */
public class MainActivity extends Activity {

    private static final String SERVICE = "_androidtvremote2._tcp.";
    // آخر نسخة بتترفع هنا من GitHub Actions (remote-android.yml)
    private static final String RELEASE = "https://github.com/EngineerAhmedAbass/Test2/releases/download/remote-latest/";

    private volatile String updateApk;
    private volatile int updateCode;
    private volatile boolean downloading;
    private Uri pendingInstall;
    private long lastCheck;

    private WebView web;
    private SharedPreferences prefs;
    private final ExecutorService sender = Executors.newSingleThreadExecutor();
    private final ExecutorService worker = Executors.newCachedThreadPool();

    // للإشعار (RemoteService)
    static volatile MainActivity live;
    static volatile long lastUse;
    static volatile String nName = "";
    static volatile String nState = "";
    static volatile int nVolume = -1;
    static volatile boolean nMuted;

    // مفتاح الأمان بتاع Android TV (واحد لكل الأجهزة)
    private volatile ClientIdentity ident;
    private String phoneName = "";

    // كل الأجهزة اللي في التابات (لحد 4)، والجهاز اللي تابه مفتوح
    static final int MAX_TABS = 4;
    private final java.util.LinkedHashMap<String, Dev> devs = new java.util.LinkedHashMap<>();
    private volatile Dev active;
    private volatile Dev pairing;

    // نسخة من بيانات الجهاز المفتوح (باقي الكود بيستخدمها)
    private volatile Remote remote;
    private volatile String kind = "";   // "atv" أو "lg" أو "rcv" (ريسيفر دش)
    private volatile String host;
    private volatile String hostName = "";
    private volatile boolean resumed;
    private volatile long wakeUntil;   // بعد أمر التشغيل بنحاول نوصل بسرعة لحد ما التليفزيون يقوم

    private NsdManager nsd;
    private NsdManager.DiscoveryListener discovery;
    private WifiManager.MulticastLock multicast;
    private final Set<String> found = Collections.synchronizedSet(new HashSet<String>());
    private final ArrayDeque<NsdServiceInfo> resolveQueue = new ArrayDeque<>();
    private boolean resolving;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("remote", MODE_PRIVATE);
        live = this;
        LgClient.signedRejected = prefs.getBoolean("lgUnsigned", false);

        web = new WebView(this);
        web.setBackgroundColor(Color.rgb(11, 13, 18));
        web.setOverScrollMode(WebView.OVER_SCROLL_NEVER);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(), "TV");
        web.loadUrl("file:///android_asset/index.html");

        worker.execute(new Runnable() {
            public void run() {
                try {
                    phoneName = (Build.MANUFACTURER + " " + Build.MODEL).trim();
                    ident = ClientIdentity.load();
                    kick(); // أي جهاز Android TV كان مستني المفتاح
                    emit(obj("type", "ready"));
                } catch (Exception e) {
                    emit(obj("type", "error", "msg", "مشكلة في تجهيز مفتاح الأمان: " + e));
                }
            }
        });
    }

    // ------------------------------------------------------------ JS → Java

    private class Bridge {
        @JavascriptInterface
        public String saved() {
            try {
                return prefs.getString("devices", "[]");
            } catch (Exception e) {
                return "[]";
            }
        }

        @JavascriptInterface
        public String lastHost() {
            return prefs.getString("last", "");
        }

        @JavascriptInterface
        public String appVersion() {
            return versionName() + " (" + versionCode() + ")";
        }

        @JavascriptInterface
        public void checkUpdate(boolean manual) {
            MainActivity.this.checkUpdate(manual);
        }

        @JavascriptInterface
        public void startUpdate() {
            MainActivity.this.startUpdate();
        }

        @JavascriptInterface
        public void setBars(final String hex, final boolean light) {
            runOnUiThread(new Runnable() {
                public void run() {
                    try {
                        int c = Color.parseColor(hex);
                        getWindow().setStatusBarColor(c);
                        getWindow().setNavigationBarColor(c);
                        web.setBackgroundColor(c);
                        View d = getWindow().getDecorView();
                        int f = d.getSystemUiVisibility();
                        int lightFlags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                                | (Build.VERSION.SDK_INT >= 26 ? View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0);
                        d.setSystemUiVisibility(light ? (f | lightFlags) : (f & ~lightFlags));
                    } catch (Exception ignored) { }
                }
            });
        }

        @JavascriptInterface
        public boolean isReady() {
            return ident != null;
        }

        @JavascriptInterface
        public void discover() {
            startDiscovery();
        }

        @JavascriptInterface
        public void connect(String h, String name, String k) {
            startConnect(h.trim(), name == null || name.isEmpty() ? h.trim() : name, k == null ? "" : k, true);
        }

        /** يضيف جهاز للتابات ويوصل بيه في الخلفية من غير ما يفتح تابه. */
        @JavascriptInterface
        public void addTab(String h, String name, String k) {
            startConnect(h.trim(), name == null || name.isEmpty() ? h.trim() : name, k == null ? "" : k, false);
        }

        /** يفتح تاب جهاز موجود. */
        @JavascriptInterface
        public void activate(String h) {
            Dev d;
            synchronized (devs) { d = devs.get(h); }
            if (d != null) setActive(d);
        }

        /** يشيل جهاز من التابات ويقفل الاتصال بيه. */
        @JavascriptInterface
        public void removeDevice(String h) {
            Dev d;
            synchronized (devs) { d = devs.remove(h); }
            if (d == null) return;
            d.stop();
            saveTabs();
            if (active == d) {
                Dev next;
                synchronized (devs) { next = devs.isEmpty() ? null : devs.values().iterator().next(); }
                if (next != null) setActive(next);
                else clearActive();
            }
            emitTabs();
        }

        @JavascriptInterface
        public String tabs() {
            return prefs.getString("tabs", "[]");
        }

        /** القديمة: تقفل الجهاز المفتوح وتشيله من التابات. */
        @JavascriptInterface
        public void disconnect() {
            Dev d = active;
            if (d != null) removeDevice(d.host);
        }

        @JavascriptInterface
        public void forget(String h) {
            try {
                JSONArray arr = new JSONArray(prefs.getString("devices", "[]"));
                JSONArray out = new JSONArray();
                for (int i = 0; i < arr.length(); i++) {
                    if (!h.equals(arr.getJSONObject(i).optString("host"))) out.put(arr.getJSONObject(i));
                }
                prefs.edit().putString("devices", out.toString()).apply();
                if (h.equals(prefs.getString("last", ""))) prefs.edit().remove("last").apply();
            } catch (Exception ignored) { }
        }

        @JavascriptInterface
        public void pairStart(final String h, String name) {
            if (h == null || h.isEmpty() || ident == null) return;
            final Dev d = devFor(h, name == null || name.isEmpty() ? h : name);
            if (d == null) return;
            d.stop(); // وقف محاولات الاتصال لحد ما الاقتران يخلص
            d.kind = "atv";
            final TvClient c = d.newTvClient();
            pairing = d;
            setActive(d);
            worker.execute(new Runnable() {
                public void run() {
                    try {
                        c.startPairing(h);
                        emit(obj("type", "pairCode"));
                    } catch (IOException e) {
                        emit(obj("type", "pairError", "msg", "معرفناش نبدأ الاقتران: " + msg(e)));
                    }
                }
            });
        }

        @JavascriptInterface
        public void pairFinish(final String code) {
            final Dev d = pairing;
            final TvClient c = d == null || !(d.client instanceof TvClient) ? null : (TvClient) d.client;
            if (c == null) return;
            worker.execute(new Runnable() {
                public void run() {
                    try {
                        c.finishPairing(code);
                        emit(obj("type", "paired"));
                        d.start("atv");
                    } catch (TvClient.WrongCodeException e) {
                        emit(obj("type", "pairError", "msg", e.getMessage(), "retry", "1"));
                    } catch (IOException e) {
                        emit(obj("type", "pairError", "msg", "الاقتران فشل: " + msg(e)));
                    }
                }
            });
        }

        @JavascriptInterface
        public void pairCancel() {
            final Dev d = pairing;
            final TvClient c = d == null || !(d.client instanceof TvClient) ? null : (TvClient) d.client;
            if (c != null) worker.execute(new Runnable() { public void run() { c.cancelPairing(); } });
        }

        @JavascriptInterface
        public void key(final int code) {
            keyDir(code, TvClient.SHORT);
        }

        @JavascriptInterface
        public void keyDir(final int code, final int dir) {
            keyDirect(code, dir);
        }

        @JavascriptInterface
        public boolean getNotif() {
            return prefs.getBoolean("notif", true);
        }

        @JavascriptInterface
        public void setNotif(boolean on) {
            prefs.edit().putBoolean("notif", on).apply();
            if (on) askNotifPermission(true);
        }

        /** الماوس في تليفزيونات LG. */
        @JavascriptInterface
        public void pointer(final int dx, final int dy) {
            send(new IoTask() { public void run(Remote c) throws IOException { if (c instanceof LgClient) ((LgClient) c).move(dx, dy); } });
        }

        /** LG: id زي HDMI_1. */
        @JavascriptInterface
        public void switchInput(final String id) {
            send(new IoTask() { public void run(Remote c) throws IOException { if (c instanceof LgClient) ((LgClient) c).switchInput(id); } });
        }

        @JavascriptInterface
        public void click() {
            send(new IoTask() { public void run(Remote c) throws IOException { if (c instanceof LgClient) ((LgClient) c).click(); } });
        }

        @JavascriptInterface
        public void scroll(final int dx, final int dy) {
            send(new IoTask() { public void run(Remote c) throws IOException { if (c instanceof LgClient) ((LgClient) c).scroll(dx, dy); } });
        }

        @JavascriptInterface
        public String getMac() {
            String h = host;
            return h == null ? "" : prefs.getString("lgmac:" + h, "");
        }

        /** MAC مكتوب بالإيد (من إعدادات الشبكة في التليفزيون). */
        @JavascriptInterface
        public boolean setMac(String macs) {
            String h = host;
            if (h == null) return false;
            StringBuilder sb = new StringBuilder();
            for (String m : macs.split("[,\\s]+")) {
                m = m.trim().replace('-', ':').toLowerCase(java.util.Locale.US);
                if (m.isEmpty()) continue;
                if (!m.matches("([0-9a-f]{2}:){5}[0-9a-f]{2}")) return false;
                if (sb.length() > 0) sb.append(',');
                sb.append(m);
            }
            prefs.edit().putString("lgmac:" + h, sb.toString()).apply();
            onLgLog("MAC set by user: " + sb);
            return true;
        }

        @JavascriptInterface
        public String diagnostics() {
            StringBuilder sb = new StringBuilder();
            sb.append("app ").append(versionName()).append(" • ").append(kind).append(" • ").append(host).append('\n');
            for (Dev d : devList()) sb.append("tab: ").append(d.kind).append(' ').append(d.host).append(' ').append(d.state).append(d == active ? " (active)" : "").append('\n');
            if ("lg".equals(kind) && host != null) sb.append("saved MAC: ").append(prefs.getString("lgmac:" + host, "none")).append('\n');
            synchronized (diag) {
                for (String l : diag) sb.append(l).append('\n');
            }
            return sb.toString();
        }

        @JavascriptInterface
        public void share(String text) {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_TEXT, text);
            try { startActivity(Intent.createChooser(i, "ابعت السجل")); } catch (Exception ignored) { }
        }

        /** زرار ريسيفر بالكود بتاعه مباشرة (FAV، TV/Radio...). */
        @JavascriptInterface
        public void rawKey(final int stbKey) {
            send(new IoTask() { public void run(Remote c) throws IOException { if (c instanceof GmsClient) ((GmsClient) c).sendRaw(stbKey); } });
        }

        @JavascriptInterface
        public String kind() {
            return kind;
        }

        @JavascriptInterface
        public void text(final String t) {
            send(new IoTask() { public void run(Remote c) throws IOException { c.sendText(t); } });
        }

        @JavascriptInterface
        public void launch(final String link) {
            send(new IoTask() { public void run(Remote c) throws IOException { c.launch(link); } });
        }

        @JavascriptInterface
        public void vibrate(int ms) {
            try {
                Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                if (v != null && prefs.getBoolean("vibrate", true)) v.vibrate(ms);
            } catch (Exception ignored) { }
        }

        @JavascriptInterface
        public void setVibrate(boolean on) {
            prefs.edit().putBoolean("vibrate", on).apply();
        }

        @JavascriptInterface
        public boolean getVibrate() {
            return prefs.getBoolean("vibrate", true);
        }

        @JavascriptInterface
        public void resetIdentity() {
            ClientIdentity.reset();
            worker.execute(new Runnable() {
                public void run() {
                    try {
                        ident = ClientIdentity.load();
                        // أجهزة Android TV محتاجة اقتران من جديد بالمفتاح الجديد
                        for (Dev d : devList()) if ("atv".equals(d.kind)) { d.stop(); d.client = null; d.start("atv"); }
                    } catch (Exception ignored) { }
                }
            });
        }
    }

    void keyDirect(final int code, final int dir) {
        Remote r = remote;
        if (code == 26 && "lg".equals(kind) && (r == null || !r.isConnected())) {
            wakeLg(); // LG مقفول: نشغله بالواي فاي
            return;
        }
        send(new IoTask() { public void run(Remote c) throws IOException { c.sendKey(code, dir); } });
    }

    /** زرار من الإشعار. */
    void external(final int code) {
        lastUse = System.currentTimeMillis();
        runOnUiThread(new Runnable() { public void run() { keyDirect(code, TvClient.SHORT); } });
    }

    /** زرار ✕ في الإشعار أو عدّت ساعة من غير استخدام: نقفل الاتصال لحد ما تفتح التطبيق. */
    void externalClose(String why) {
        onLgLog("notification closed (" + why + ")");
        if (resumed) return;
        for (Dev d : devList()) {
            final Remote c = d.client;
            if (c != null) worker.execute(new Runnable() { public void run() { c.disconnect(); } });
        }
    }

    /** الإشعار محتاج إذن في أندرويد 13+. */
    private void askNotifPermission(boolean force) {
        if (Build.VERSION.SDK_INT < 33) return;
        if (checkSelfPermission("android.permission.POST_NOTIFICATIONS") == android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        if (!force && prefs.getBoolean("notifAsked", false)) return;
        prefs.edit().putBoolean("notifAsked", true).apply();
        final Activity a = this;
        runOnUiThread(new Runnable() {
            public void run() {
                try { a.requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 7); } catch (Exception ignored) { }
            }
        });
    }

    private boolean notifAllowed() {
        if (!prefs.getBoolean("notif", true)) return false;
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) return false;
        return true;
    }

    private interface IoTask {
        void run(Remote c) throws IOException;
    }

    private void send(final IoTask task) {
        lastUse = System.currentTimeMillis();
        final Remote c = remote;
        if (c == null || !c.isConnected()) {
            emit(obj("type", "notConnected"));
            return;
        }
        sender.execute(new Runnable() {
            public void run() {
                try {
                    task.run(c);
                } catch (IOException e) {
                    if (c instanceof LgClient) {
                        // في LG الاتصال الأساسي سليم؛ بس نقول للمستخدم إيه اللي حصل
                        onLgLog("key failed: " + e);
                        emit(obj("type", "error", "msg", msg(e)));
                    } else {
                        c.disconnect(); // الـ loop هيعيد الاتصال
                    }
                }
            }
        });
    }

    // ------------------------------------------------------- Devices (tabs)

    private java.util.List<Dev> devList() {
        synchronized (devs) {
            return new java.util.ArrayList<>(devs.values());
        }
    }

    /** الجهاز ده لو موجود، أو جهاز جديد في التابات (null لو التابات مليانة). */
    private Dev devFor(String h, String name) {
        synchronized (devs) {
            Dev d = devs.get(h);
            if (d != null) {
                d.name = name;
                return d;
            }
            if (devs.size() >= MAX_TABS) return null;
            d = new Dev(h, name);
            devs.put(h, d);
        }
        saveTabs();
        return devs.get(h);
    }

    private void startConnect(final String h, final String name, final String wantKind, boolean activate) {
        Dev d = devFor(h, name);
        if (d == null) {
            emit(obj("type", "error", "msg", "التابات مليانة (" + MAX_TABS + " أجهزة) — شيل جهاز بضغطة طويلة على تابه"));
            emit(obj("type", "tabsFull"));
            return;
        }
        if (activate) setActive(d);
        d.start(wantKind);
        emitTabs();
    }

    private void setActive(Dev d) {
        active = d;
        host = d.host;
        hostName = d.name;
        kind = d.kind;
        remote = d.client;
        nName = d.name;
        nState = d.state;
        nVolume = d.volume;
        nMuted = d.muted;
        prefs.edit().putString("last", d.host).apply();
        d.replay(); // ابعت للواجهة كل حالة الجهاز ده
        emitTabs();
        RemoteService.refresh(this);
    }

    private void clearActive() {
        active = null;
        host = null;
        hostName = "";
        kind = "";
        remote = null;
        nState = "offline";
        nVolume = -1;
        RemoteService.refresh(this);
    }

    private void saveTabs() {
        try {
            JSONArray arr = new JSONArray();
            for (Dev d : devList()) arr.put(new JSONObject().put("host", d.host).put("name", d.name).put("kind", d.kind));
            prefs.edit().putString("tabs", arr.toString()).apply();
        } catch (Exception ignored) { }
    }

    private void emitTabs() {
        try {
            JSONArray arr = new JSONArray();
            Dev a = active;
            for (Dev d : devList()) {
                arr.put(new JSONObject().put("host", d.host).put("name", d.name).put("kind", d.kind)
                        .put("state", d.state).put("volume", d.volume).put("active", d == a));
            }
            emit(new JSONObject().put("type", "tabs").put("list", arr));
        } catch (Exception ignored) { }
    }

    /**
     * جهاز واحد في التابات: اتصاله، والـ loop اللي بيعيد الاتصال، وآخر حالة (صوت، تطبيق، مصادر...).
     * كل جهاز ليه اتصاله المستقل، فالتنقل بين التابات بيبقى فوري.
     */
    final class Dev implements TvClient.Listener, LgClient.Events, GmsClient.Events {
        final String host;
        volatile String name;
        volatile String kind = "";
        volatile Remote client;
        volatile String state = "connecting";
        volatile int volume = -1;
        volatile int volMax;
        volatile boolean muted;
        volatile String app = "";
        volatile Boolean power;
        volatile JSONArray apps;
        volatile JSONArray inputs;
        volatile String vendor = "";
        volatile String model = "";
        private volatile boolean looping;
        private volatile boolean stopped;
        private volatile String wantKind = "";

        Dev(String host, String name) {
            this.host = host;
            this.name = name;
        }

        boolean isActive() {
            return active == this;
        }

        TvClient newTvClient() {
            TvClient c = new TvClient(ident.keyManagers, ident.cert, phoneName, this);
            client = c;
            if (isActive()) remote = c;
            return c;
        }

        void start(String want) {
            if (want != null && !want.isEmpty()) wantKind = want;
            stopped = false;
            if (looping) {
                kick(); // شغال: خليه يحاول دلوقتي
                return;
            }
            looping = true;
            worker.execute(new Runnable() {
                public void run() {
                    try {
                        loop();
                    } finally {
                        looping = false;
                    }
                }
            });
        }

        void stop() {
            stopped = true;
            kick();
            final Remote c = client;
            if (c != null) worker.execute(new Runnable() { public void run() { c.disconnect(); } });
        }

        private void setState(String st, String msg) {
            state = st;
            if (isActive()) {
                nState = st;
                if (!"connected".equals(st)) nVolume = -1;
                RemoteService.refresh(MainActivity.this);
            }
            emit(obj("type", "status", "state", st, "host", host, "name", name, "vendor", vendor, "model", model, "kind", kind, "msg", msg));
            emitTabs();
        }

        private void log(String line) {
            String n = name.length() > 14 ? name.substring(0, 14) : name;
            onLgLog("[" + n + "] " + line);
        }

        private void loop() {
            // لو مش عارفين نوع التليفزيون (IP مكتوب بالإيد) بنشوف البورتات المفتوحة
            String k = wantKind;
            if (k.isEmpty()) k = kind;
            if (k.isEmpty()) k = savedKind(host);
            if (k.isEmpty()) {
                setState("connecting", "");
                k = probe(host);
                log("probe " + host + ": " + (k.isEmpty() ? "no TV/receiver ports open (6466, 3000/3001, 20000)" : k));
            }
            if (stopped) return;
            if (k.isEmpty()) {
                setState("offline", "مش لاقي تليفزيون على " + host);
                k = "atv";
            }
            Remote c = client;
            boolean sameKind = k.equals(kind);
            kind = k;
            if (c == null || !sameKind) {
                if ("lg".equals(k)) {
                    c = new LgClient(prefs.getString("lgkey:" + host, null), this, this);
                } else if ("rcv".equals(k)) {
                    c = new GmsClient(this, this);
                } else {
                    while (ident == null && !stopped) pause(500); // مستنيين مفتاح الأمان
                    if (stopped) return;
                    c = new TvClient(ident.keyManagers, ident.cert, phoneName, this);
                }
                client = c;
            }
            remember(host, name, k);
            saveTabs();
            if (isActive()) {
                kind = k;
                MainActivity.this.kind = k;
                remote = c;
                nName = name;
                emit(obj("type", "kind", "kind", k, "host", host));
            }
            int fails = 0;
            while (!stopped) {
                if (!resumed && !RemoteService.running) { pause(30000); continue; }
                setState("connecting", "");
                long t0 = System.currentTimeMillis();
                try {
                    c.runRemote(host);
                } catch (TvClient.NeedsPairingException e) {
                    if (!stopped) {
                        setState("pair", "");
                        emit(obj("type", "needPair", "host", host, "name", name));
                    }
                    return;
                } catch (LgClient.RejectedException e) {
                    if (!stopped) {
                        setState("rejected", e.getMessage());
                        emit(obj("type", "lgRejected", "msg", e.getMessage(), "host", host));
                    }
                    return;
                } catch (IOException e) {
                    if (stopped) return;
                    if (System.currentTimeMillis() - t0 > 20000) fails = 0;
                    if (!resumed && !RemoteService.running) fails = 0; // اتقفل عشان خرجنا من التطبيق، مش عطل
                    else fails++;
                    setState("offline", msg(e));
                }
                if (System.currentTimeMillis() < wakeUntil) {
                    fails = 0;
                    pause(1000); // بعد أمر التشغيل: بنحاول كل ثانية لحد ما واي فاي التليفزيون يرجع
                } else {
                    pause(Math.min(8000, 700L * fails));
                }
            }
        }

        /** لما تابه يتفتح: ابعت للواجهة كل حالته. */
        void replay() {
            emit(obj("type", "kind", "kind", kind, "host", host));
            emit(obj("type", "status", "state", state, "host", host, "name", name, "vendor", vendor, "model", model, "kind", kind));
            if (volume >= 0) emit(obj("type", "volume", "level", String.valueOf(volume), "max", String.valueOf(volMax), "muted", muted ? "1" : "", "host", host));
            emit(obj("type", "app", "pkg", app, "host", host));
            if (power != null) emit(obj("type", "power", "on", power ? "1" : "", "host", host));
            try {
                emit(new JSONObject().put("type", "apps").put("list", apps == null ? new JSONArray() : apps).put("host", host));
                emit(new JSONObject().put("type", "inputs").put("list", inputs == null ? new JSONArray() : inputs).put("host", host));
            } catch (Exception ignored) { }
            if ("pair".equals(state)) emit(obj("type", "needPair", "host", host, "name", name));
        }

        // ---- TvClient.Listener
        @Override
        public void onConnected(String vendor, String model) {
            this.vendor = vendor;
            this.model = model;
            setState("connected", "");
            if (isActive()) {
                nName = name;
                askNotifPermission(false); // أول مرة بس
            }
        }

        @Override
        public void onDisconnected(String reason) {
            volume = -1;
            setState("offline", reason);
        }

        @Override
        public void onPower(boolean on) {
            power = on;
            emit(obj("type", "power", "on", on ? "1" : "", "host", host));
        }

        @Override
        public void onVolume(int level, int max, boolean m) {
            boolean changed = level != volume || m != muted;
            volume = level;
            volMax = max;
            muted = m;
            emit(obj("type", "volume", "level", String.valueOf(level), "max", String.valueOf(max), "muted", m ? "1" : "", "host", host));
            if (changed && isActive()) {
                nVolume = level;
                nMuted = m;
                RemoteService.refresh(MainActivity.this);
            }
        }

        @Override
        public void onApp(String pkg) {
            app = pkg;
            emit(obj("type", "app", "pkg", pkg, "host", host));
        }

        // ---- LgClient.Events
        @Override
        public void onLgPrompt() {
            emit(obj("type", "lgPrompt", "host", host, "name", name));
        }

        @Override
        public void onLgKey(String clientKey) {
            prefs.edit().putString("lgkey:" + host, clientKey).apply();
            emit(obj("type", "paired", "host", host));
        }

        @Override
        public void onLgMac(String mac) {
            // لو المستخدم كاتب MAC بإيده بنضيفه للي التليفزيون اداهولنا
            String old = prefs.getString("lgmac:" + host, "");
            java.util.LinkedHashSet<String> all = new java.util.LinkedHashSet<>();
            for (String m : (mac + "," + old).split(",")) if (!m.trim().isEmpty()) all.add(m.trim().toLowerCase(java.util.Locale.US));
            StringBuilder sb = new StringBuilder();
            for (String m : all) { if (sb.length() > 0) sb.append(','); sb.append(m); }
            prefs.edit().putString("lgmac:" + host, sb.toString()).apply();
        }

        @Override
        public void onLgLog(String line) {
            log(line);
        }

        @Override
        public void onRcvLog(String line) {
            log(line);
        }

        @Override
        public void onLgUnsigned() {
            prefs.edit().putBoolean("lgUnsigned", true).apply();
        }

        @Override
        public void onLgInputs(JSONArray list) {
            inputs = list;
            try {
                emit(new JSONObject().put("type", "inputs").put("list", list).put("host", host));
            } catch (Exception ignored) { }
        }

        @Override
        public void onLgApps(JSONArray list) {
            apps = list;
            try {
                emit(new JSONObject().put("type", "apps").put("list", list).put("host", host));
            } catch (Exception ignored) { }
        }
    }

    private final Object loopLock = new Object();

    /** انتظار بين محاولات الاتصال، وبيخلص بدري لو رجعنا للتطبيق أو دسنا تشغيل. */
    private void pause(long ms) {
        if (ms <= 0) return;
        synchronized (loopLock) {
            try { loopLock.wait(ms); } catch (InterruptedException ignored) { }
        }
    }

    private void kick() {
        synchronized (loopLock) {
            loopLock.notifyAll();
        }
    }

    /** بيشوف التليفزيون نوعه إيه من البورتات: 6466 = Android TV، 3000/3001 = LG. */
    private static String probe(String h) {
        if (portOpen(h, TvClient.REMOTE_PORT, 1500)) return "atv";
        if (portOpen(h, LgClient.portWss, 1500) || portOpen(h, LgClient.portWs, 1500)) return "lg";
        if (portOpen(h, GmsClient.PORT, 1500)) return "rcv";
        return "";
    }

    private static boolean portOpen(String h, int port, int timeout) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(h, port), timeout);
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            try { s.close(); } catch (IOException ignored) { }
        }
    }

    private String savedKind(String h) {
        try {
            JSONArray arr = new JSONArray(prefs.getString("devices", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                if (h.equals(arr.getJSONObject(i).optString("host"))) return arr.getJSONObject(i).optString("kind", "");
            }
        } catch (Exception ignored) { }
        return "";
    }

    private void wakeLg() {
        final String h = host;
        final String macs = h == null ? null : prefs.getString("lgmac:" + h, null);
        if (macs == null || macs.isEmpty()) {
            onLgLog("wake: no MAC saved for " + h);
            emit(obj("type", "needMac"));
            return;
        }
        wakeUntil = System.currentTimeMillis() + 120000; // واي فاي التليفزيون ممكن ياخد دقيقة ونص عشان يرجع
        kick();
        emit(obj("type", "status", "state", "connecting", "host", h, "name", hostName));
        worker.execute(new Runnable() {
            public void run() {
                int ok = 0;
                // بنكرر الأمر كام مرة: كارت الواي فاي وهو نايم ممكن يفوّت أول رسالة
                for (int round = 0; round < 4; round++) {
                    for (String mac : macs.split(",")) {
                        try {
                            String via = LgClient.wake(mac.trim(), h);
                            if (round == 0) onLgLog("wake sent to " + mac.trim() + " from " + via);
                            ok++;
                        } catch (IOException e) {
                            if (round == 0) onLgLog("wake failed " + mac + ": " + e);
                        }
                    }
                    if (round == 0) emit(obj("type", "error", "msg", ok > 0 ? "بنشغّل التليفزيون… بياخد حوالي 20 ثانية" : "معرفناش نبعت أمر التشغيل"));
                    sleep(1500);
                }
            }
        });
    }

    private void remember(String h, String name, String k) {
        try {
            JSONArray arr = new JSONArray(prefs.getString("devices", "[]"));
            JSONArray out = new JSONArray();
            out.put(new JSONObject().put("host", h).put("name", name).put("kind", k));
            for (int i = 0; i < arr.length() && out.length() < 8; i++) {
                if (!h.equals(arr.getJSONObject(i).optString("host"))) out.put(arr.getJSONObject(i));
            }
            prefs.edit().putString("devices", out.toString()).putString("last", h).apply();
        } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------ Diagnostics

    private final java.util.LinkedList<String> diag = new java.util.LinkedList<>();

    void onLgLog(String line) {
        String t = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(new java.util.Date());
        synchronized (diag) {
            diag.add(t + "  " + line);
            while (diag.size() > 120) diag.removeFirst();
        }
    }

    // -------------------------------------------------------------- Discovery

    private void startDiscovery() {
        found.clear();
        InetAddress me = null;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        me = a;
                        onLgLog("search: phone " + ni.getName() + " " + a.getHostAddress());
                    }
                }
            }
        } catch (Exception ignored) { }
        if (me == null) onLgLog("search: phone has no Wi-Fi address!");
        stopDiscovery();
        try {
            WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                multicast = wifi.createMulticastLock("tvremote");
                multicast.setReferenceCounted(false);
                multicast.acquire();
            }
        } catch (Exception ignored) { }

        try {
            nsd = (NsdManager) getSystemService(Context.NSD_SERVICE);
            discovery = new NsdManager.DiscoveryListener() {
                public void onStartDiscoveryFailed(String t, int e) { }
                public void onStopDiscoveryFailed(String t, int e) { }
                public void onDiscoveryStarted(String t) { }
                public void onDiscoveryStopped(String t) { }
                public void onServiceLost(NsdServiceInfo s) { }
                public void onServiceFound(NsdServiceInfo s) {
                    synchronized (resolveQueue) {
                        resolveQueue.add(s);
                    }
                    resolveNext();
                }
            };
            nsd.discoverServices(SERVICE, NsdManager.PROTOCOL_DNS_SD, discovery);
        } catch (Exception e) {
            discovery = null;
        }

        // احتياطي: لو الراوتر بيمنع mDNS بندوّر على بورت 6466 في الشبكة كلها
        worker.execute(new Runnable() {
            public void run() {
                scanSubnet();
            }
        });
        worker.execute(new Runnable() {
            public void run() {
                ssdpLg();
            }
        });
        // ريسيفرات الدش (تايجر وغيره) بتعلن عن نفسها على UDP 25860
        worker.execute(new Runnable() {
            public void run() {
                GmsClient.listen(8000, new GmsClient.Found() {
                    public void onFound(String ip, GmsClient.Info info) {
                        foundTv(ip, info.model.isEmpty() ? "ريسيفر" : info.model, "rcv");
                    }

                    public void onNote(String line) {
                        onLgLog("search: " + line);
                    }
                });
            }
        });
        worker.execute(new Runnable() {
            public void run() {
                sleep(9000);
                stopDiscovery();
                onLgLog("search: done, found " + found.size());
                emit(obj("type", "scanDone"));
            }
        });
    }

    @SuppressWarnings("deprecation")
    private void resolveNext() {
        final NsdServiceInfo next;
        synchronized (resolveQueue) {
            if (resolving || resolveQueue.isEmpty() || nsd == null) return;
            resolving = true;
            next = resolveQueue.poll();
        }
        try {
            nsd.resolveService(next, new NsdManager.ResolveListener() {
                public void onResolveFailed(NsdServiceInfo s, int e) {
                    done();
                }

                public void onServiceResolved(NsdServiceInfo s) {
                    InetAddress a = s.getHost();
                    if (a != null) foundTv(a.getHostAddress(), s.getServiceName(), "atv");
                    done();
                }

                private void done() {
                    synchronized (resolveQueue) {
                        resolving = false;
                    }
                    resolveNext();
                }
            });
        } catch (Exception e) {
            synchronized (resolveQueue) {
                resolving = false;
            }
        }
    }

    private void foundTv(String ip, String name, String k) {
        if (ip == null || ip.contains(":")) return; // IPv4 بس
        boolean isNew = found.add(ip + "|" + name);
        if (isNew) onLgLog("search: found " + k + " " + ip + " " + name);
        if (isNew) emit(obj("type", "found", "host", ip, "name", name, "kind", k));
    }

    /** تليفزيونات LG بترد على SSDP (UPnP) بالخدمة دي. */
    private void ssdpLg() {
        java.net.DatagramSocket s = null;
        try {
            String req = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\n"
                    + "ST: urn:lge-com:service:webos-second-screen:1\r\n\r\n";
            byte[] b = req.getBytes("UTF-8");
            s = new java.net.DatagramSocket();
            s.setSoTimeout(1000);
            InetAddress group = InetAddress.getByName("239.255.255.250");
            long end = System.currentTimeMillis() + 7000;
            int sent = 0;
            while (System.currentTimeMillis() < end) {
                if (sent < 3) { s.send(new java.net.DatagramPacket(b, b.length, group, 1900)); sent++; }
                byte[] buf = new byte[2048];
                java.net.DatagramPacket p = new java.net.DatagramPacket(buf, buf.length);
                try {
                    s.receive(p);
                } catch (java.net.SocketTimeoutException e) {
                    continue;
                }
                String res = new String(p.getData(), 0, p.getLength(), "UTF-8");
                if (!res.toLowerCase(java.util.Locale.US).contains("webos")) continue;
                final String ip = p.getAddress().getHostAddress();
                String loc = header(res, "location");
                String name = "LG TV";
                if (loc != null) {
                    try {
                        java.util.regex.Matcher m = java.util.regex.Pattern.compile("<friendlyName>([^<]+)</friendlyName>").matcher(httpGet(loc));
                        if (m.find()) name = m.group(1).replace("&amp;", "&").replace("[LG]", "LG").replace("webOS TV", "").replaceAll("\\s+", " ").trim();
                    } catch (Exception ignored) { }
                }
                foundTv(ip, name, "lg");
            }
        } catch (Exception ignored) {
        } finally {
            if (s != null) s.close();
        }
    }

    private static String header(String res, String name) {
        for (String line : res.split("\r\n")) {
            int i = line.indexOf(':');
            if (i > 0 && line.substring(0, i).trim().equalsIgnoreCase(name)) return line.substring(i + 1).trim();
        }
        return null;
    }

    private void scanSubnet() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress a = ia.getAddress();
                    if (!(a instanceof Inet4Address) || !a.isSiteLocalAddress()) continue;
                    byte[] b = a.getAddress();
                    final String prefix = (b[0] & 0xFF) + "." + (b[1] & 0xFF) + "." + (b[2] & 0xFF) + ".";
                    final int self = b[3] & 0xFF;
                    ExecutorService pool = Executors.newFixedThreadPool(48);
                    for (int i = 1; i < 255; i++) {
                        if (i == self) continue;
                        final String ip = prefix + i;
                        pool.execute(new Runnable() {
                            public void run() {
                                String k = portOpen(ip, TvClient.REMOTE_PORT, 500) ? "atv"
                                        : (portOpen(ip, LgClient.portWs, 400) || portOpen(ip, LgClient.portWss, 400)) ? "lg"
                                        : portOpen(ip, GmsClient.PORT, 400) ? "rcv" : null;
                                if (k == null) return;
                                sleep(1500); // نستنى mDNS/SSDP يجيبوا الاسم الحقيقي
                                boolean known = false;
                                synchronized (found) {
                                    for (String f : found) if (f.startsWith(ip + "|")) known = true;
                                }
                                if (!known) foundTv(ip, "", k);
                            }
                        });
                    }
                    pool.shutdown();
                    pool.awaitTermination(8, TimeUnit.SECONDS);
                }
            }
        } catch (Exception ignored) { }
    }

    private void stopDiscovery() {
        try {
            if (nsd != null && discovery != null) nsd.stopServiceDiscovery(discovery);
        } catch (Exception ignored) { }
        discovery = null;
        try {
            if (multicast != null && multicast.isHeld()) multicast.release();
        } catch (Exception ignored) { }
    }

    // ---------------------------------------------------------------- helpers

    private void emit(final JSONObject o) {
        runOnUiThread(new Runnable() {
            public void run() {
                if (web != null) web.evaluateJavascript("window.onTv&&window.onTv(" + o.toString() + ")", null);
            }
        });
    }

    private static JSONObject obj(String... kv) {
        JSONObject o = new JSONObject();
        try {
            for (int i = 0; i + 1 < kv.length; i += 2) o.put(kv[i], kv[i + 1] == null ? "" : kv[i + 1]);
        } catch (Exception ignored) { }
        return o;
    }

    private static String msg(Exception e) {
        Throwable t = e;
        while (t.getCause() != null && (t.getMessage() == null || t instanceof TvClient.HandshakeException)) t = t.getCause();
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }

    // ---------------------------------------------------------------- Update

    private int versionCode() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    /** بيقرا version.json من آخر release على GitHub ويقارنه بالنسخة المتسطبة. */
    private void checkUpdate(final boolean manual) {
        if (!manual && System.currentTimeMillis() - lastCheck < 60 * 1000) return;
        lastCheck = System.currentTimeMillis();
        worker.execute(new Runnable() {
            public void run() {
                try {
                    JSONObject v = new JSONObject(httpGet(RELEASE + "version.json?t=" + System.currentTimeMillis()));
                    int latest = v.getInt("versionCode");
                    int current = versionCode();
                    if (latest > current) {
                        updateCode = latest;
                        updateApk = v.optString("apk", RELEASE + "TV-Remote.apk");
                        emit(obj("type", "update", "current", versionName(), "latest", v.optString("versionName", String.valueOf(latest))));
                    } else {
                        emit(obj("type", "noUpdate", "manual", manual ? "1" : ""));
                    }
                } catch (Exception e) {
                    if (manual) emit(obj("type", "error", "msg", "معرفناش نشوف التحديثات — اتأكد من النت"));
                }
            }
        });
    }

    private static String httpGet(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setInstanceFollowRedirects(true);
        c.setUseCaches(false);
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    /** بينزّل الـ APK الجديد ويفتح شاشة التثبيت. */
    private void startUpdate() {
        final String apk = updateApk;
        if (apk == null || downloading) return;
        downloading = true;
        worker.execute(new Runnable() {
            public void run() {
                try {
                    DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                    File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    if (dir != null) {
                        File[] old = dir.listFiles();
                        if (old != null) for (File f : old) if (f.getName().endsWith(".apk")) f.delete();
                    }
                    String name = "TV-Remote-" + updateCode + ".apk";
                    DownloadManager.Request r = new DownloadManager.Request(Uri.parse(apk))
                            .setTitle("ريموت TV — تحديث")
                            .setMimeType("application/vnd.android.package-archive")
                            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                            .setDestinationInExternalFilesDir(MainActivity.this, Environment.DIRECTORY_DOWNLOADS, name);
                    long id = dm.enqueue(r);
                    while (true) {
                        sleep(400);
                        Cursor cur = dm.query(new DownloadManager.Query().setFilterById(id));
                        if (cur == null || !cur.moveToFirst()) throw new IOException("التحميل اتلغى");
                        int status = cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                        long done = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                        long total = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                        cur.close();
                        if (status == DownloadManager.STATUS_SUCCESSFUL) break;
                        if (status == DownloadManager.STATUS_FAILED) throw new IOException("التحميل فشل");
                        if (total > 0) emit(obj("type", "updateProgress", "pct", String.valueOf(done * 99 / total)));
                    }
                    emit(obj("type", "updateProgress", "pct", "100"));
                    final Uri uri = dm.getUriForDownloadedFile(id);
                    runOnUiThread(new Runnable() { public void run() { install(uri); } });
                } catch (Exception e) {
                    emit(obj("type", "updateError", "msg", msg(e) + " — هنفتحلك صفحة التحميل"));
                    sleep(1500);
                    runOnUiThread(new Runnable() {
                        public void run() {
                            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(apk))); } catch (Exception ignored) { }
                        }
                    });
                } finally {
                    downloading = false;
                }
            }
        });
    }

    private void install(Uri uri) {
        if (uri == null) return;
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            // أول مرة: لازم تسمح للتطبيق إنه يسطّب تحديثاته
            pendingInstall = uri;
            emit(obj("type", "error", "msg", "فعّل \"السماح من المصدر ده\" وارجع للتطبيق"));
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            } catch (Exception ignored) { }
            return;
        }
        pendingInstall = null;
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            emit(obj("type", "updateError", "msg", "معرفناش نفتح التثبيت: " + msg(e)));
        }
    }

    // زراير الصوت في الموبايل بتتحكم في صوت التليفزيون
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int k = event.getKeyCode();
        Remote c = remote;
        if ((k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_DOWN) && c != null && c.isConnected()) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                final int code = k;
                send(new IoTask() { public void run(Remote t) throws IOException { t.sendKey(code, TvClient.SHORT); } });
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.onBack?window.onBack():false", new android.webkit.ValueCallback<String>() {
            public void onReceiveValue(String handled) {
                if (!"true".equals(handled)) MainActivity.super.onBackPressed();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        handler.removeCallbacks(idleDisconnect);
        RemoteService.stop(this); // التطبيق مفتوح: مش محتاجين الإشعار
        kick(); // لو الاتصال كان مقفول، نحاول على طول من غير انتظار
        emit(obj("type", "resume"));
        if (pendingInstall != null && (Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls())) {
            install(pendingInstall);
        } else if (lastCheck != 0) {
            checkUpdate(false); // لما ترجع للتطبيق (بحد أقصى مرة في الدقيقة)
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        handler.removeCallbacks(idleDisconnect);
        // لو متوصل: الريموت يفضل في الإشعارات (وبيقفل لوحده بعد ساعة من غير استخدام)
        Remote r = remote;
        if (host != null && r != null && r.isConnected() && notifAllowed()) {
            try {
                lastUse = System.currentTimeMillis();
                RemoteService.start(this);
                return;
            } catch (Exception e) {
                onLgLog("notification failed: " + e);
            }
        }
        // غير كده: بنسيب الاتصال مفتوح دقيقة لو رجعت بسرعة، وبعدها نقفله عشان البطارية
        handler.postDelayed(idleDisconnect, 60000);
    }

    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable idleDisconnect = new Runnable() {
        public void run() {
            if (resumed || RemoteService.running) return;
            for (Dev d : devList()) {
                final Remote c = d.client;
                if (c != null && c.isConnected()) worker.execute(new Runnable() { public void run() { c.disconnect(); } });
            }
        }
    };

    @Override
    protected void onDestroy() {
        for (Dev d : devList()) d.stop();
        kick();
        handler.removeCallbacks(idleDisconnect);
        RemoteService.stop(this);
        if (live == this) live = null;
        stopDiscovery();
        sender.shutdownNow();
        worker.shutdownNow();
        super.onDestroy();
    }
}
