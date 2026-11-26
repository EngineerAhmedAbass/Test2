package com.tvremote.app;

import org.json.JSONArray;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** بيجرب LgClient ضد fake_lg.py: موافقة على التليفزيون، زراير، كتابة، تطبيقات، ماوس، ومفتاح محفوظ. */
public class LgTest {
    static volatile String key;
    static volatile int prompts;

    public static void main(String[] args) throws Exception {
        final CountDownLatch connected = new CountDownLatch(1);
        final CountDownLatch status = new CountDownLatch(3);
        TvClient.Listener l = new TvClient.Listener() {
            public void onConnected(String vendor, String model) { System.out.println("connected " + vendor + " " + model); connected.countDown(); }
            public void onDisconnected(String reason) { System.out.println("disconnected"); }
            public void onPower(boolean on) { System.out.println("power " + on); }
            public void onVolume(int level, int max, boolean muted) { System.out.println("volume " + level + "/" + max + " " + muted); status.countDown(); }
            public void onApp(String pkg) { System.out.println("app " + pkg); status.countDown(); }
        };
        LgClient.Events ev = new LgClient.Events() {
            public void onLgPrompt() { prompts++; System.out.println("prompt on TV"); }
            public void onLgKey(String k) { key = k; System.out.println("key " + k); }
            public void onLgMac(String mac) { System.out.println("mac " + mac); }
            public void onLgInputs(JSONArray in) { System.out.println("inputs " + in.length() + " " + in.optJSONObject(1).optString("label") + " connected=" + in.optJSONObject(1).optBoolean("connected")); }
            public void onLgLog(String line) { System.out.println("log: " + line); }
            public void onLgChannel(String n, String name) { System.out.println("channel " + n + " " + name); }
            public void onLgUnsigned() { System.out.println("unsigned remembered"); }
            public void onLgApps(JSONArray apps) { System.out.println("apps " + apps.length() + " " + apps.optJSONObject(0).optString("title")); status.countDown(); }
        };

        final LgClient tv = new LgClient(null, l, ev);
        Thread t = new Thread(new Runnable() {
            public void run() {
                try { tv.runRemote("127.0.0.1"); } catch (Exception e) { System.out.println("ended: " + e); }
            }
        });
        t.start();
        if (!connected.await(15, TimeUnit.SECONDS)) throw new AssertionError("not connected");
        if (!status.await(5, TimeUnit.SECONDS)) throw new AssertionError("no status");
        if (prompts != 1 || key == null) throw new AssertionError("pairing flow");
        try { tv.sendKey(19, TvClient.SHORT); } catch (java.io.IOException e) { System.out.println("arrow failed (expected without pointer): " + e.getMessage()); }
        if (!tv.isConnected()) throw new AssertionError("dropped connection");
        tv.sendKey(24, TvClient.SHORT);
        tv.sendKey(164, TvClient.SHORT);
        if (System.getProperty("nopointer") != null) { tv.disconnect(); t.join(5000); System.out.println("DONE"); return; }
        tv.sendKey(23, TvClient.START_LONG);
        tv.sendKey(23, TvClient.END_LONG);
        tv.sendKey(24, TvClient.SHORT);
        tv.sendKey(85, TvClient.SHORT);
        tv.sendKey(85, TvClient.SHORT);
        tv.sendKey(12, TvClient.SHORT);
        tv.sendText("محمد صلاح");
        tv.sendKey(67, TvClient.SHORT);
        tv.sendKey(66, TvClient.SHORT);
        tv.launch("lg:netflix");
        tv.launch("https://youtu.be/dQw4w9WgXcQ?si=abc");
        tv.launch("https://www.shahid.net/ar");
        tv.switchInput("HDMI_2");
        tv.setVolume(23);
        tv.move(12, -5);
        tv.click();
        tv.sendKey(26, TvClient.SHORT);
        Thread.sleep(600);
        tv.disconnect();
        t.join(5000);

        // تاني مرة بالمفتاح المحفوظ: مفيش سؤال على التليفزيون
        prompts = 0;
        final CountDownLatch c2 = new CountDownLatch(1);
        TvClient.Listener l2 = new TvClient.Listener() {
            public void onConnected(String v, String m) { c2.countDown(); }
            public void onDisconnected(String r) { }
            public void onPower(boolean on) { }
            public void onVolume(int a, int b, boolean c) { }
            public void onApp(String p) { }
        };
        final LgClient tv2 = new LgClient(key, l2, ev);
        final long t0 = System.currentTimeMillis();
        Thread t2 = new Thread(new Runnable() {
            public void run() {
                try { tv2.runRemote("127.0.0.1"); } catch (Exception e) { System.out.println("ended2: " + e); }
            }
        });
        t2.start();
        if (!c2.await(10, TimeUnit.SECONDS)) throw new AssertionError("reconnect failed");
        System.out.println("TIME to connected with saved key: " + (System.currentTimeMillis() - t0) + " ms");
        if (prompts != 0) throw new AssertionError("asked again");
        System.out.println("reconnect with saved key OK");
        tv2.disconnect();
        t2.join(5000);
        System.out.println("DONE");
    }
}
