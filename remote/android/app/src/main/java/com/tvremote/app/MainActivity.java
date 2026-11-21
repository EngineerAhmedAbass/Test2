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
public class MainActivity extends Activity implements TvClient.Listener, LgClient.Events {

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

    private TvClient tv;                 // Android TV / Google TV (شاومي)
    private volatile Remote remote;      // التليفزيون اللي متوصلين بيه دلوقتي (TvClient أو LgClient)
    private volatile String kind = "";   // "atv" أو "lg"
    private volatile String host;
    private volatile String hostName = "";
    private volatile int connectGen;
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
                    ClientIdentity id = ClientIdentity.load();
                    String name = (Build.MANUFACTURER + " " + Build.MODEL).trim();
                    tv = new TvClient(id.keyManagers, id.cert, name, MainActivity.this);
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
            return tv != null;
        }

        @JavascriptInterface
        public void discover() {
            startDiscovery();
        }

        @JavascriptInterface
        public void connect(String h, String name, String k) {
            startConnect(h.trim(), name == null || name.isEmpty() ? h.trim() : name, k == null ? "" : k);
        }

        @JavascriptInterface
        public void disconnect() {
            connectGen++;
            host = null;
            final Remote c = remote;
            if (c != null) worker.execute(new Runnable() { public void run() { c.disconnect(); } });
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
            final TvClient c = tv;
            if (h == null || h.isEmpty() || c == null) return;
            connectGen++; // وقف محاولات الاتصال لحد ما الاقتران يخلص
            Remote old = remote;
            if (old != null && old != c) old.disconnect();
            remote = c;
            kind = "atv";
            host = h;
            hostName = name == null || name.isEmpty() ? h : name;
            if (c.isConnected()) c.disconnect();
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
            final TvClient c = tv;
            if (c == null) return;
            worker.execute(new Runnable() {
                public void run() {
                    try {
                        c.finishPairing(code);
                        emit(obj("type", "paired"));
                        String h = host;
                        if (h != null) startConnect(h, hostName, "atv");
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
            final TvClient c = tv;
            if (c != null) worker.execute(new Runnable() { public void run() { c.cancelPairing(); } });
        }

        @JavascriptInterface
        public void key(final int code) {
            keyDir(code, TvClient.SHORT);
        }

        @JavascriptInterface
        public void keyDir(final int code, final int dir) {
            Remote r = remote;
            if (code == 26 && "lg".equals(kind) && (r == null || !r.isConnected())) {
                wakeLg(); // LG مقفول: نشغله بالواي فاي
                return;
            }
            send(new IoTask() { public void run(Remote c) throws IOException { c.sendKey(code, dir); } });
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
                        ClientIdentity id = ClientIdentity.load();
                        tv = new TvClient(id.keyManagers, id.cert, (Build.MANUFACTURER + " " + Build.MODEL).trim(), MainActivity.this);
                    } catch (Exception ignored) { }
                }
            });
        }
    }

    private interface IoTask {
        void run(Remote c) throws IOException;
    }

    private void send(final IoTask task) {
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

    // ------------------------------------------------------- Connection loop

    private void startConnect(final String h, final String name, final String wantKind) {
        final int gen = ++connectGen;
        host = h;
        hostName = name;
        Remote old = remote;
        if (old != null && old.isConnected()) old.disconnect();
        worker.execute(new Runnable() {
            public void run() {
                // لو مش عارفين نوع التليفزيون (IP مكتوب بالإيد) بنشوف البورتات المفتوحة
                String k = wantKind;
                if (k.isEmpty()) k = savedKind(h);
                if (k.isEmpty()) {
                    emit(obj("type", "status", "state", "connecting", "host", h, "name", name));
                    k = probe(h);
                }
                if (gen != connectGen) return;
                if (k.isEmpty()) {
                    emit(obj("type", "status", "state", "offline", "host", h, "name", name, "msg", "مش لاقي تليفزيون على " + h));
                    k = "atv";
                }
                final Remote c;
                if ("lg".equals(k)) {
                    c = new LgClient(prefs.getString("lgkey:" + h, null), MainActivity.this, MainActivity.this);
                } else {
                    c = tv;
                    if (c == null) {
                        emit(obj("type", "error", "msg", "استنى ثانية والبرنامج بيجهز…"));
                        return;
                    }
                }
                kind = k;
                remote = c;
                remember(h, name, k);
                emit(obj("type", "kind", "kind", k));
                int fails = 0;
                while (gen == connectGen) {
                    if (!resumed) { sleep(500); continue; }
                    emit(obj("type", "status", "state", "connecting", "host", h, "name", name));
                    long t0 = System.currentTimeMillis();
                    try {
                        c.runRemote(h);
                    } catch (TvClient.NeedsPairingException e) {
                        if (gen == connectGen) emit(obj("type", "needPair", "host", h, "name", name));
                        return;
                    } catch (LgClient.RejectedException e) {
                        if (gen == connectGen) emit(obj("type", "lgRejected", "msg", e.getMessage()));
                        return;
                    } catch (IOException e) {
                        if (gen != connectGen) return;
                        if (System.currentTimeMillis() - t0 > 20000) fails = 0;
                        fails++;
                        emit(obj("type", "status", "state", "offline", "host", h, "name", name, "msg", msg(e)));
                    }
                    if (System.currentTimeMillis() < wakeUntil) {
                        fails = 0;
                        sleep(1500);
                    } else {
                        sleep(Math.min(8000, 700L * fails));
                    }
                }
            }
        });
    }

    /** بيشوف التليفزيون نوعه إيه من البورتات: 6466 = Android TV، 3000/3001 = LG. */
    private static String probe(String h) {
        if (portOpen(h, TvClient.REMOTE_PORT, 1500)) return "atv";
        if (portOpen(h, LgClient.portWss, 1500) || portOpen(h, LgClient.portWs, 1500)) return "lg";
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
        wakeUntil = System.currentTimeMillis() + 60000;
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

    // ------------------------------------------------------ TvClient.Listener

    @Override
    public void onConnected(String vendor, String model) {
        emit(obj("type", "status", "state", "connected", "host", host, "name", hostName, "vendor", vendor, "model", model, "kind", kind));
    }

    // ------------------------------------------------------- LgClient.Events

    @Override
    public void onLgPrompt() {
        emit(obj("type", "lgPrompt"));
    }

    @Override
    public void onLgKey(String clientKey) {
        String h = host;
        if (h != null) prefs.edit().putString("lgkey:" + h, clientKey).apply();
        emit(obj("type", "paired"));
    }

    @Override
    public void onLgMac(String mac) {
        String h = host;
        if (h == null) return;
        // لو المستخدم كاتب MAC بإيده بنضيفه للي التليفزيون اداهولنا
        String old = prefs.getString("lgmac:" + h, "");
        java.util.LinkedHashSet<String> all = new java.util.LinkedHashSet<>();
        for (String m : (mac + "," + old).split(",")) if (!m.trim().isEmpty()) all.add(m.trim().toLowerCase(java.util.Locale.US));
        StringBuilder sb = new StringBuilder();
        for (String m : all) { if (sb.length() > 0) sb.append(','); sb.append(m); }
        prefs.edit().putString("lgmac:" + h, sb.toString()).apply();
    }

    private final java.util.LinkedList<String> diag = new java.util.LinkedList<>();

    @Override
    public void onLgLog(String line) {
        String t = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(new java.util.Date());
        synchronized (diag) {
            diag.add(t + "  " + line);
            while (diag.size() > 80) diag.removeFirst();
        }
    }

    @Override
    public void onLgInputs(JSONArray inputs) {
        try {
            emit(new JSONObject().put("type", "inputs").put("list", inputs));
        } catch (Exception ignored) { }
    }

    @Override
    public void onLgApps(JSONArray apps) {
        try {
            emit(new JSONObject().put("type", "apps").put("list", apps));
        } catch (Exception ignored) { }
    }

    @Override
    public void onDisconnected(String reason) {
        emit(obj("type", "status", "state", "offline", "host", host, "name", hostName, "msg", reason));
    }

    @Override
    public void onPower(boolean on) {
        emit(obj("type", "power", "on", on ? "1" : ""));
    }

    @Override
    public void onVolume(int level, int max, boolean muted) {
        emit(obj("type", "volume", "level", String.valueOf(level), "max", String.valueOf(max), "muted", muted ? "1" : ""));
    }

    @Override
    public void onApp(String pkg) {
        emit(obj("type", "app", "pkg", pkg));
    }

    // -------------------------------------------------------------- Discovery

    private void startDiscovery() {
        found.clear();
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
        worker.execute(new Runnable() {
            public void run() {
                sleep(9000);
                stopDiscovery();
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
                                        : (portOpen(ip, LgClient.portWs, 400) || portOpen(ip, LgClient.portWss, 400)) ? "lg" : null;
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
        // بنقفل الاتصال وإحنا في الخلفية عشان البطارية؛ بيرجع لوحده لما تفتح التطبيق
        Remote c = remote;
        if (c != null && c.isConnected()) c.disconnect();
    }

    @Override
    protected void onDestroy() {
        connectGen++;
        stopDiscovery();
        Remote c = remote;
        if (c != null) c.disconnect();
        sender.shutdownNow();
        worker.shutdownNow();
        super.onDestroy();
    }
}
