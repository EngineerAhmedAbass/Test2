package com.tvremote.app;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** بيجرب GmsClient ضد fake_receiver.py: الاكتشاف، الدخول، الزراير، الكتابة. */
public class RcvTest {
    public static void main(String[] args) throws Exception {
        final String[] found = new String[1];
        GmsClient.listen(3000, new GmsClient.Found() {
            public void onFound(String ip, GmsClient.Info i) {
                if (found[0] == null) System.out.println("found " + ip + " " + i.model + " platform=" + i.platform + " json=" + i.json);
                found[0] = ip;
            }
        });
        if (found[0] == null) throw new AssertionError("not discovered");

        final CountDownLatch c = new CountDownLatch(1);
        final GmsClient rcv = new GmsClient(new TvClient.Listener() {
            public void onConnected(String v, String m) { System.out.println("connected " + m); c.countDown(); }
            public void onDisconnected(String r) { System.out.println("disconnected"); }
            public void onPower(boolean on) { }
            public void onVolume(int a, int b, boolean d) { }
            public void onApp(String p) { }
        }, new GmsClient.Events() {
            public void onRcvLog(String line) { System.out.println("log: " + line); }
        });
        final String[] err = new String[1];
        Thread t = new Thread(new Runnable() {
            public void run() {
                try { rcv.runRemote(found[0]); } catch (Exception e) { err[0] = e.getMessage(); System.out.println("ended: " + e); }
            }
        });
        t.start();
        if (!c.await(5, TimeUnit.SECONDS)) {
            t.join(3000);
            System.out.println("not connected: " + err[0]);
            System.out.println("DONE");
            return;
        }
        rcv.sendKey(19, TvClient.SHORT);   // فوق
        rcv.sendKey(23, TvClient.SHORT);   // OK
        rcv.sendKey(4, TvClient.SHORT);    // رجوع
        rcv.sendKey(24, TvClient.SHORT);   // صوت +
        rcv.sendKey(12, TvClient.SHORT);   // رقم 5
        rcv.sendKey(26, TvClient.SHORT);   // Power
        rcv.sendRaw(33);                   // FAV
        rcv.sendText("Aب");
        Thread.sleep(500);
        rcv.disconnect();
        t.join(3000);
        System.out.println("DONE");
    }
}
