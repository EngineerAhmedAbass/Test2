package com.tvremote.app;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.Locale;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * الاتصال بأجهزة Android TV / Google TV (زي Mi Box و Mi TV Stick وتليفزيونات شاومي)
 * ببروتوكول "Android TV Remote v2" — نفس اللي بيستخدمه تطبيق Google TV:
 *   - بورت 6467: الاقتران (التليفزيون بيعرض كود من 6 حروف ونكتبه في الموبايل).
 *   - بورت 6466: الريموت نفسه (زراير، كتابة، فتح تطبيقات، الصوت).
 * الكلاس ده Java عادي من غير أي حاجة من أندرويد، عشان يتجرب على الكمبيوتر كمان.
 */
public class TvClient implements Remote {

    public static final int REMOTE_PORT = 6466;
    public static final int PAIRING_PORT = 6467;

    // RemoteDirection
    public static final int SHORT = 3;
    public static final int START_LONG = 1;
    public static final int END_LONG = 2;

    // الخصائص اللي بنطلبها: PING | KEY | IME | POWER | VOLUME | APP_LINK
    static final int FEATURES = 1 | 2 | 4 | 32 | 64 | 512;

    public interface Listener {
        void onConnected(String vendor, String model);
        void onDisconnected(String reason);
        void onPower(boolean on);
        void onVolume(int level, int max, boolean muted);
        void onApp(String pkg);
    }

    /** الجهاز رفض شهادتنا: لازم نعمل اقتران الأول. */
    public static class NeedsPairingException extends IOException {
        NeedsPairingException(String m, Throwable cause) {
            super(m, cause);
        }
    }

    /** الكود اللي اتكتب غلط. */
    public static class WrongCodeException extends IOException {
        WrongCodeException(String m) {
            super(m);
        }
    }

    /** الاتصال وصل للتليفزيون بس الـ TLS اترفض. */
    static class HandshakeException extends IOException {
        HandshakeException(Throwable cause) {
            super("TLS handshake: " + cause, cause);
        }
    }

    private final KeyManager[] keyManagers;
    private final X509Certificate clientCert;
    private final String clientName;
    private final Listener listener;

    private volatile SSLSocket remoteSocket;
    private volatile OutputStream remoteOut;
    private SSLSocket pairingSocket;

    private volatile int imeCounter;
    private volatile int imeFieldCounter;
    private boolean hasImeCounter;
    private boolean hasImeFieldCounter;

    public TvClient(KeyManager[] keyManagers, X509Certificate clientCert, String clientName, Listener listener) {
        this.keyManagers = keyManagers;
        this.clientCert = clientCert;
        this.clientName = clientName;
        this.listener = listener;
    }

    // ----------------------------------------------------------------- TLS

    private SSLSocket open(String host, int port, int timeoutMs) throws IOException {
        SSLContext ctx;
        try {
            ctx = SSLContext.getInstance("TLS");
            // التليفزيون بيستخدم شهادة موقّعة من نفسه، فبنقبلها (الأمان بييجي من الاقتران بالكود)
            TrustManager[] trustAll = {new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] c, String a) { }
                public void checkServerTrusted(X509Certificate[] c, String a) { }
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }};
            ctx.init(keyManagers, trustAll, new SecureRandom());
        } catch (Exception e) {
            throw new IOException("TLS: " + e.getMessage(), e);
        }
        Socket raw = new Socket();
        try {
            raw.connect(new InetSocketAddress(host, port), timeoutMs);
        } catch (IOException e) {
            try { raw.close(); } catch (IOException ignored) { }
            throw new IOException("مش لاقي التليفزيون على " + host, e);
        }
        try {
            raw.setTcpNoDelay(true);
            SSLSocket s = (SSLSocket) ctx.getSocketFactory().createSocket(raw, host, port, true);
            s.setUseClientMode(true);
            s.setSoTimeout(timeoutMs);
            s.startHandshake();
            return s;
        } catch (IOException e) {
            try { raw.close(); } catch (IOException ignored) { }
            // التليفزيون رفض الشهادة: بتظهر أحيانًا SSLException وأحيانًا قفل الاتصال (broken pipe / reset)
            throw new HandshakeException(e);
        }
    }

    private static Proto.Writer outer() {
        return new Proto.Writer().varint(1, 2).varint(2, 200); // protocol_version=2, status=OK
    }

    private static Proto.Writer encoding() {
        return new Proto.Writer().varint(1, 3).varint(2, 6); // HEXADECIMAL, 6 حروف
    }

    // ------------------------------------------------------------- Pairing

    /** بيبدأ الاقتران؛ لما يرجع التليفزيون بيكون عارض الكود. */
    public synchronized void startPairing(String host) throws IOException {
        cancelPairing();
        SSLSocket s = open(host, PAIRING_PORT, 8000);
        pairingSocket = s;
        try {
            s.setSoTimeout(15000);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            Proto.send(out, outer().msg(10, new Proto.Writer().string(1, "atvremote").string(2, clientName)));
            while (true) {
                Proto.Msg m = readPairing(in);
                if (m.has(11)) { // pairing_request_ack
                    Proto.send(out, outer().msg(20, new Proto.Writer().msg(1, encoding()).varint(3, 1)));
                } else if (m.has(20)) { // options
                    Proto.send(out, outer().msg(30, new Proto.Writer().msg(1, encoding()).varint(2, 1)));
                } else if (m.has(31)) { // configuration_ack → الكود ظاهر على الشاشة
                    s.setSoTimeout(0);
                    return;
                } else {
                    throw new IOException("رد غير متوقع من التليفزيون");
                }
            }
        } catch (IOException e) {
            cancelPairing();
            throw e;
        }
    }

    /** بيبعت الكود اللي ظاهر على الشاشة. */
    public synchronized void finishPairing(String code) throws IOException {
        SSLSocket s = pairingSocket;
        if (s == null) throw new IOException("ابدأ الاقتران الأول");
        code = code.trim().toUpperCase(Locale.US);
        if (!code.matches("[0-9A-F]{6}")) throw new WrongCodeException("الكود لازم يكون 6 حروف/أرقام");
        Certificate[] peer = s.getSession().getPeerCertificates();
        byte[] secret = pairingSecret(
                (RSAPublicKey) clientCert.getPublicKey(),
                (RSAPublicKey) peer[0].getPublicKey(),
                code);
        if ((secret[0] & 0xFF) != Integer.parseInt(code.substring(0, 2), 16)) {
            throw new WrongCodeException("الكود غلط، اتأكد منه");
        }
        try {
            s.setSoTimeout(15000);
            Proto.send(s.getOutputStream(), outer().msg(40, new Proto.Writer().bytes(1, secret)));
            Proto.Msg m = readPairing(s.getInputStream());
            if (!m.has(41)) throw new IOException("التليفزيون مأكدش الاقتران");
        } finally {
            cancelPairing();
        }
    }

    public synchronized void cancelPairing() {
        if (pairingSocket != null) {
            try { pairingSocket.close(); } catch (IOException ignored) { }
            pairingSocket = null;
        }
    }

    private static Proto.Msg readPairing(InputStream in) throws IOException {
        Proto.Msg m = Proto.Msg.parse(Proto.receive(in));
        long status = m.num(2);
        if (status != 200) {
            if (status == 402) throw new WrongCodeException("الكود غلط، اتأكد منه");
            throw new IOException("التليفزيون رفض الاقتران (" + status + ")");
        }
        return m;
    }

    static byte[] pairingSecret(RSAPublicKey client, RSAPublicKey server, String code) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(unsigned(client.getModulus()));
            sha.update(unsigned(client.getPublicExponent()));
            sha.update(unsigned(server.getModulus()));
            sha.update(unsigned(server.getPublicExponent()));
            sha.update(hex(code.substring(2)));
            return sha.digest();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] unsigned(BigInteger v) {
        byte[] b = v.toByteArray();
        if (b.length > 1 && b[0] == 0) {
            byte[] t = new byte[b.length - 1];
            System.arraycopy(b, 1, t, 0, t.length);
            return t;
        }
        return b;
    }

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return out;
    }

    // -------------------------------------------------------------- Remote

    /**
     * بيوصل بالريموت ويفضل يقرا الرسايل لحد ما الاتصال يقفل (الدالة دي بتفضل شغالة — شغّلها في Thread).
     * لو الجهاز محتاج اقتران بترمي NeedsPairingException قبل ما توصل.
     */
    @Override
    public void runRemote(String host) throws IOException {
        SSLSocket s;
        try {
            s = open(host, REMOTE_PORT, 6000);
        } catch (HandshakeException e) {
            throw new NeedsPairingException("محتاج اقتران", e);
        }
        InputStream in = s.getInputStream();
        OutputStream out = s.getOutputStream();
        boolean configured = false;
        int active = FEATURES;
        String reason = "انقطع الاتصال";
        try {
            // التليفزيون بيبعت ping كل 5 ثواني؛ لو مفيش حاجة 16 ثانية يبقى الاتصال واقع
            s.setSoTimeout(16000);
            while (true) {
                byte[] raw;
                try {
                    raw = Proto.receive(in);
                } catch (SocketTimeoutException e) {
                    throw e;
                } catch (IOException e) {
                    // في TLS 1.3 رفض الشهادة بيوصل بعد الـ handshake، أول ما نقرا
                    if (!configured) throw new NeedsPairingException("محتاج اقتران", e);
                    throw e;
                }
                Proto.Msg m = Proto.Msg.parse(raw);
                if (m.has(1)) { // remote_configure
                    Proto.Msg cfg = m.sub(1);
                    Proto.Msg info = cfg.sub(2);
                    active = FEATURES & (int) cfg.num(1);
                    Proto.send(out, new Proto.Writer().msg(1, new Proto.Writer()
                            .varint(1, active)
                            .msg(2, new Proto.Writer().varint(3, 1).string(4, "1")
                                    .string(5, "atvremote").string(6, "1.0.0"))));
                    if (!configured) {
                        configured = true;
                        remoteSocket = s;
                        remoteOut = out;
                        listener.onConnected(info.str(2), info.str(1));
                    }
                } else if (m.has(2)) { // remote_set_active
                    Proto.send(out, new Proto.Writer().msg(2, new Proto.Writer().varint(1, active)));
                } else if (m.has(8)) { // ping
                    Proto.send(out, new Proto.Writer().msg(9, new Proto.Writer().varint(1, m.sub(8).num(1))));
                } else if (m.has(20)) { // remote_ime_key_inject: التطبيق اللي مفتوح
                    Proto.Msg inj = m.sub(20);
                    Proto.Msg app = inj.sub(1);
                    imeCounter = (int) app.num(1);
                    imeFieldCounter = (int) inj.sub(2).num(1);
                    hasImeCounter = imeCounter != 0;
                    hasImeFieldCounter = imeFieldCounter != 0;
                    String pkg = app.str(12);
                    if (!pkg.isEmpty()) listener.onApp(pkg);
                } else if (m.has(22)) { // remote_ime_show_request
                    Proto.Msg req = m.sub(22);
                    if (req.has(2)) {
                        imeFieldCounter = (int) req.sub(2).num(1);
                        hasImeFieldCounter = true;
                    }
                } else if (m.has(21)) { // remote_ime_batch_edit
                    Proto.Msg be = m.sub(21);
                    if (!hasImeCounter) imeCounter = (int) be.num(1);
                    if (!hasImeFieldCounter) imeFieldCounter = (int) be.num(2);
                } else if (m.has(50)) { // الصوت
                    Proto.Msg v = m.sub(50);
                    listener.onVolume((int) v.num(7), (int) v.num(6), v.num(8) != 0);
                } else if (m.has(40)) { // remote_start: التليفزيون شغال ولا Standby
                    listener.onPower(m.sub(40).num(1) != 0);
                }
            }
        } catch (SocketTimeoutException e) {
            reason = "التليفزيون مش بيرد";
            throw e;
        } finally {
            boolean wasConnected = remoteSocket == s;
            if (wasConnected) {
                remoteSocket = null;
                remoteOut = null;
            }
            try { s.close(); } catch (IOException ignored) { }
            if (wasConnected) listener.onDisconnected(reason);
        }
    }

    @Override
    public boolean isConnected() {
        return remoteOut != null;
    }

    @Override
    public void disconnect() {
        SSLSocket s = remoteSocket;
        if (s != null) {
            try { s.close(); } catch (IOException ignored) { }
        }
        cancelPairing();
    }

    private void sendRemote(Proto.Writer msg) throws IOException {
        OutputStream out = remoteOut;
        if (out == null) throw new IOException("مش متوصل");
        Proto.send(out, msg);
    }

    /** keyCode = أرقام KeyEvent بتاعة أندرويد (مثلًا 19 فوق، 23 OK، 4 رجوع، 3 هوم). */
    @Override
    public void sendKey(int keyCode, int direction) throws IOException {
        sendRemote(new Proto.Writer().msg(10, new Proto.Writer().varint(1, keyCode).varint(2, direction)));
    }

    /** بيكتب نص في الخانة اللي عليها الفوكس في التليفزيون. */
    @Override
    public void sendText(String text) throws IOException {
        if (text == null || text.isEmpty()) return;
        int len = text.length();
        Proto.Writer obj = new Proto.Writer().varint(1, len).varint(2, len).string(3, text);
        Proto.Writer edit = new Proto.Writer().varint(1, 1).msg(2, obj);
        sendRemote(new Proto.Writer().msg(21, new Proto.Writer()
                .varint(1, imeCounter).varint(2, imeFieldCounter).msg(3, edit)));
    }

    /** بيفتح تطبيق برابط (مثلًا https://www.youtube.com أو market://launch?id=com.netflix.ninja). */
    @Override
    public void launch(String link) throws IOException {
        sendRemote(new Proto.Writer().msg(90, new Proto.Writer().string(1, link)));
    }
}
