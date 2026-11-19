package com.tvremote.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
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

import java.io.IOException;
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
public class MainActivity extends Activity implements TvClient.Listener {

    private static final String SERVICE = "_androidtvremote2._tcp.";

    private WebView web;
    private SharedPreferences prefs;
    private final ExecutorService sender = Executors.newSingleThreadExecutor();
    private final ExecutorService worker = Executors.newCachedThreadPool();

    private TvClient tv;
    private volatile String host;
    private volatile String hostName = "";
    private volatile int connectGen;
    private volatile boolean resumed;

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
        public boolean isReady() {
            return tv != null;
        }

        @JavascriptInterface
        public void discover() {
            startDiscovery();
        }

        @JavascriptInterface
        public void connect(String h, String name) {
            startConnect(h.trim(), name == null || name.isEmpty() ? h.trim() : name);
        }

        @JavascriptInterface
        public void disconnect() {
            connectGen++;
            host = null;
            final TvClient c = tv;
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
                        if (h != null) startConnect(h, hostName);
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
            send(new IoTask() { public void run(TvClient c) throws IOException { c.sendKey(code, dir); } });
        }

        @JavascriptInterface
        public void text(final String t) {
            send(new IoTask() { public void run(TvClient c) throws IOException { c.sendText(t); } });
        }

        @JavascriptInterface
        public void launch(final String link) {
            send(new IoTask() { public void run(TvClient c) throws IOException { c.launch(link); } });
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
        void run(TvClient c) throws IOException;
    }

    private void send(final IoTask task) {
        final TvClient c = tv;
        if (c == null || !c.isConnected()) {
            emit(obj("type", "notConnected"));
            return;
        }
        sender.execute(new Runnable() {
            public void run() {
                try {
                    task.run(c);
                } catch (IOException e) {
                    c.disconnect(); // الـ loop هيعيد الاتصال
                }
            }
        });
    }

    // ------------------------------------------------------- Connection loop

    private void startConnect(final String h, final String name) {
        final TvClient c = tv;
        if (c == null) {
            emit(obj("type", "error", "msg", "استنى ثانية والبرنامج بيجهز…"));
            return;
        }
        final int gen = ++connectGen;
        String old = host;
        host = h;
        hostName = name;
        remember(h, name);
        if (old != null && c.isConnected()) c.disconnect();
        worker.execute(new Runnable() {
            public void run() {
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
                    } catch (IOException e) {
                        if (gen != connectGen) return;
                        if (System.currentTimeMillis() - t0 > 20000) fails = 0;
                        fails++;
                        emit(obj("type", "status", "state", "offline", "host", h, "name", name, "msg", msg(e)));
                    }
                    sleep(Math.min(8000, 700L * fails));
                }
            }
        });
    }

    private void remember(String h, String name) {
        try {
            JSONArray arr = new JSONArray(prefs.getString("devices", "[]"));
            JSONArray out = new JSONArray();
            out.put(new JSONObject().put("host", h).put("name", name));
            for (int i = 0; i < arr.length() && out.length() < 8; i++) {
                if (!h.equals(arr.getJSONObject(i).optString("host"))) out.put(arr.getJSONObject(i));
            }
            prefs.edit().putString("devices", out.toString()).putString("last", h).apply();
        } catch (Exception ignored) { }
    }

    // ------------------------------------------------------ TvClient.Listener

    @Override
    public void onConnected(String vendor, String model) {
        emit(obj("type", "status", "state", "connected", "host", host, "name", hostName, "vendor", vendor, "model", model));
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
                    if (a != null) foundTv(a.getHostAddress(), s.getServiceName());
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

    private void foundTv(String ip, String name) {
        if (ip == null || ip.contains(":")) return; // IPv4 بس
        boolean isNew = found.add(ip + "|" + name);
        if (isNew) emit(obj("type", "found", "host", ip, "name", name));
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
                                Socket s = new Socket();
                                try {
                                    s.connect(new InetSocketAddress(ip, TvClient.REMOTE_PORT), 600);
                                    boolean known = false;
                                    synchronized (found) {
                                        for (String f : found) if (f.startsWith(ip + "|")) known = true;
                                    }
                                    if (!known) foundTv(ip, "");
                                } catch (IOException ignored) {
                                } finally {
                                    try { s.close(); } catch (IOException ignored) { }
                                }
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

    // زراير الصوت في الموبايل بتتحكم في صوت التليفزيون
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int k = event.getKeyCode();
        TvClient c = tv;
        if ((k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_DOWN) && c != null && c.isConnected()) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                final int code = k;
                send(new IoTask() { public void run(TvClient t) throws IOException { t.sendKey(code, TvClient.SHORT); } });
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
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        // بنقفل الاتصال وإحنا في الخلفية عشان البطارية؛ بيرجع لوحده لما تفتح التطبيق
        TvClient c = tv;
        if (c != null && c.isConnected()) c.disconnect();
    }

    @Override
    protected void onDestroy() {
        connectGen++;
        stopDiscovery();
        TvClient c = tv;
        if (c != null) c.disconnect();
        sender.shutdownNow();
        worker.shutdownNow();
        super.onDestroy();
    }
}
