package com.tvremote.app;

import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.KeyManagerFactory;

/** بيجرب TvClient ضد fake_tv.py: اقتران، اتصال، زراير، كتابة، فتح تطبيق. */
public class ClientTest {
    public static void main(String[] args) throws Exception {
        String work = args[0];
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(new FileInputStream(work + "/client.p12"), "testtest".toCharArray());
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, "testtest".toCharArray());
        X509Certificate cert = (X509Certificate) ks.getCertificate("atvremote");

        CountDownLatch connected = new CountDownLatch(1);
        CountDownLatch volume = new CountDownLatch(1);
        CountDownLatch app = new CountDownLatch(1);
        TvClient tv = new TvClient(kmf.getKeyManagers(), cert, "Test Phone", new TvClient.Listener() {
            public void onConnected(String vendor, String model) { System.out.println("connected " + vendor + " " + model); connected.countDown(); }
            public void onDisconnected(String reason) { System.out.println("disconnected " + reason); }
            public void onPower(boolean on) { System.out.println("power " + on); }
            public void onVolume(int level, int max, boolean muted) { System.out.println("volume " + level + "/" + max + " " + muted); volume.countDown(); }
            public void onApp(String pkg) { System.out.println("app " + pkg); app.countDown(); }
        });

        try {
            tv.runRemote("127.0.0.1");
            throw new AssertionError("expected NeedsPairingException");
        } catch (TvClient.NeedsPairingException e) {
            System.out.println("needs pairing OK");
        }

        tv.startPairing("127.0.0.1");
        Thread.sleep(300);
        String code = new String(Files.readAllBytes(Paths.get(work, "code.txt"))).trim();
        System.out.println("code on TV: " + code);
        String wrong = String.format("%02X", (Integer.parseInt(code.substring(0, 2), 16) + 1) & 0xFF) + code.substring(2);
        try {
            tv.finishPairing(wrong);
            throw new AssertionError("expected WrongCodeException");
        } catch (TvClient.WrongCodeException e) {
            System.out.println("wrong code rejected OK");
        }
        tv.finishPairing(code.toLowerCase());
        System.out.println("paired OK");

        Thread t = new Thread(() -> {
            try { tv.runRemote("127.0.0.1"); } catch (Exception e) { System.out.println("remote ended: " + e); }
        });
        t.start();
        if (!connected.await(10, TimeUnit.SECONDS)) throw new AssertionError("not connected");
        if (!volume.await(5, TimeUnit.SECONDS) || !app.await(5, TimeUnit.SECONDS)) throw new AssertionError("no status");
        Thread.sleep(300);
        tv.sendKey(19, TvClient.SHORT);
        tv.sendKey(23, TvClient.SHORT);
        tv.sendKey(24, TvClient.START_LONG);
        tv.sendKey(24, TvClient.END_LONG);
        tv.sendText("مرحبا hello");
        tv.launch("https://www.youtube.com");
        Thread.sleep(800);
        tv.disconnect();
        t.join(5000);
        System.out.println("DONE");
    }
}
