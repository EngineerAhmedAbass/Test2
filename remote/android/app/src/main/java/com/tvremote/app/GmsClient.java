package com.tvremote.app;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * ريسيفرات الدش اللي بتشتغل مع تطبيق G-MScreen (تايجر، ستارسات، ميدياستار، جيانت وغيرهم):
 *   - الريسيفر بيعلن عن نفسه برسالة UDP على بورت 25860 (108 بايت متشفرة بـ XOR).
 *   - التحكم على TCP بورت 20000: كل رسالة = "Start" + الطول (7 أرقام) + "End" + XML أو JSON.
 *   - الزرار: request="1040" و KeyValue = كود الزرار عند الريسيفر.
 * مبني على تحليل تطبيق G-MScreen الرسمي (mktvsmart.screen).
 */
public class GmsClient implements Remote {

    public static final int PORT = 20000;
    public static final int BROADCAST_PORT = 25860;
    static final int INFO_LEN = 108;
    static final String MAGIC = "39WwijOog54a";

    static final int REQ_LOGIN = 998;
    static final int REQ_KEEP_ALIVE = 26;
    static final int DO_REMOTE = 1040;
    static final int DO_INPUT_KEYCODE = 1059;

    public interface Events {
        void onRcvLog(String line);
    }

    /** بيانات الريسيفر من رسالة الإعلان أو الـ login. */
    public static class Info {
        public String model = "";
        public int platform;
        public boolean full;
        public boolean json;
        public String ip = "";
    }

    // زراير أندرويد → كود الزرار في الريسيفر (الجدول العادي)
    private static final Map<Integer, Integer> KEYS = new HashMap<>();
    // منصة Trident (platform 30) ليها أكواد مختلفة
    private static final Map<Integer, Integer> KEYS_30 = new HashMap<>();

    static {
        KEYS.put(19, 1);    // فوق
        KEYS.put(20, 2);    // تحت
        KEYS.put(21, 3);    // شمال
        KEYS.put(22, 4);    // يمين
        KEYS.put(23, 5);    // OK
        KEYS.put(82, 6);    // القائمة
        KEYS.put(176, 6);
        KEYS.put(4, 7);     // رجوع = EXIT
        KEYS.put(111, 7);
        KEYS.put(3, 7);     // الرئيسية = EXIT (يرجّع للمشاهدة)
        KEYS.put(183, 8);   // ألوان
        KEYS.put(184, 9);
        KEYS.put(185, 10);
        KEYS.put(186, 11);
        for (int i = 0; i <= 9; i++) KEYS.put(7 + i, 12 + i); // أرقام
        KEYS.put(164, 23);  // كتم
        KEYS.put(91, 23);
        KEYS.put(24, 35);   // صوت +
        KEYS.put(25, 36);   // صوت -
        KEYS.put(166, 1);   // القناة الجاية = فوق (وانت بتتفرج)
        KEYS.put(167, 2);   // القناة اللي فاتت = تحت
        KEYS.put(26, 42);   // تشغيل/Standby
        KEYS.put(165, 57);  // Info
        KEYS.put(172, 32);  // EPG
        KEYS.put(85, 55);   // إيقاف مؤقت (Timeshift)
        KEYS.put(126, 61);
        KEYS.put(127, 63);
        KEYS.put(86, 62);
        KEYS.put(89, 59);   // ترجيع
        KEYS.put(90, 60);   // تقديم
        KEYS.put(88, 64);
        KEYS.put(87, 65);

        KEYS_30.put(26, 44);
        KEYS_30.put(82, 20);
        KEYS_30.put(176, 20);
        KEYS_30.put(4, 48);
        KEYS_30.put(23, 27);
        KEYS_30.put(19, 24);
        KEYS_30.put(20, 25);
        KEYS_30.put(21, 22);
        KEYS_30.put(22, 23);
        KEYS_30.put(24, 56);
        KEYS_30.put(25, 57);
        KEYS_30.put(164, 12);
        KEYS_30.put(91, 12);
        KEYS_30.put(166, 90);
        KEYS_30.put(167, 89);
        KEYS_30.put(172, 29);
        KEYS_30.put(111, 21);
        KEYS_30.put(3, 21);
    }

    private final TvClient.Listener listener;
    private final Events events;
    private volatile Socket socket;
    private volatile OutputStream out;
    private volatile Info info;
    private volatile boolean connected;

    public GmsClient(TvClient.Listener listener, Events events) {
        this.listener = listener;
        this.events = events;
    }

    // ------------------------------------------------------------ Protocol

    static byte[] frame(String payload) {
        byte[] body = payload.getBytes(Proto.UTF8);
        String len = String.valueOf(body.length);
        StringBuilder sb = new StringBuilder("Start");
        for (int i = len.length(); i < 7; i++) sb.append('0');
        sb.append(len).append("End");
        byte[] head = sb.toString().getBytes(Proto.UTF8);
        byte[] f = new byte[head.length + body.length];
        System.arraycopy(head, 0, f, 0, head.length);
        System.arraycopy(body, 0, f, head.length, body.length);
        return f;
    }

    static String xml(int request, String inner) {
        return "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><Command request=\"" + request + "\">"
                + (inner == null ? "" : inner) + "</Command>";
    }

    private String command(int request, String xmlInner, String jsonInner) {
        Info i = info;
        if (i != null && i.json) return "{\"request\":\"" + request + "\"" + (jsonInner == null ? "" : "," + jsonInner) + "}";
        return xml(request, xmlInner);
    }

    /** فك التشفير (XOR 0x5B + قلب البايتات) — نفس الدالة بتشفّر وبتفك. */
    static void scramble(byte[] b, int len) {
        int half = len / 2;
        for (int i = 0; i < half; i++) {
            int j = len - 1 - i;
            byte t = b[j];
            b[j] = b[i];
            b[i] = t;
            b[i] ^= 0x5B;
            b[j] ^= 0x5B;
        }
        if ((len & 1) == 1) b[len / 2] ^= 0x5B;
    }

    /** بيفك رسالة الـ 108 بايت؛ بيرجع null لو مش رسالة ريسيفر. */
    static Info parseInfo(byte[] data, int len) {
        if (len != INFO_LEN) return null;
        byte[] b = new byte[INFO_LEN];
        System.arraycopy(data, 0, b, 0, INFO_LEN);
        scramble(b, INFO_LEN);
        if (!MAGIC.equals(new String(b, 0, 12, Proto.UTF8))) return null;
        Info i = new Info();
        int end = 20;
        while (end < 52 && b[end] != 0) end++;
        i.model = new String(b, 20, end - 20, Proto.UTF8).trim();
        i.ip = (b[68] & 0xFF) + "." + (b[69] & 0xFF) + "." + (b[70] & 0xFF) + "." + (b[71] & 0xFF);
        i.platform = b[72] & 0xFF;
        int flags = b[84] & 0xFF;
        i.full = (flags & 0x01) != 0;
        i.json = (flags & 0x40) != 0;
        return i;
    }

    // ------------------------------------------------------------- Connect

    @Override
    public void runRemote(String host) throws IOException {
        long t0 = System.currentTimeMillis();
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, PORT), 5000);
        } catch (IOException e) {
            s.close();
            log("connect " + host + ":" + PORT + " failed: " + e);
            throw new IOException("مش لاقي الريسيفر على " + host, e);
        }
        boolean was = false;
        try {
            s.setTcpNoDelay(true);
            s.setSoTimeout(6000);
            InputStream in = s.getInputStream();
            OutputStream o = s.getOutputStream();
            String login = xml(REQ_LOGIN, "<data>Android " + android() + "</data><uuid>" + UUID.randomUUID() + "</uuid>");
            o.write(frame(login));
            o.flush();
            byte[] raw = new byte[INFO_LEN];
            readFully(in, raw);
            Info i = parseInfo(raw, INFO_LEN);
            if (i == null) throw new IOException("ده مش ريسيفر G-MScreen");
            log("login OK: model=" + i.model + " platform=" + i.platform + " json=" + i.json + " full=" + i.full
                    + " (" + (System.currentTimeMillis() - t0) + " ms)");
            if (i.full) throw new IOException("الريسيفر متوصل بأجهزة كتير — اقفل G-MScreen من الموبايلات التانية");
            info = i;
            socket = s;
            out = o;
            connected = true;
            was = true;
            listener.onConnected("Receiver", i.model.isEmpty() ? "ريسيفر" : i.model);

            // keep-alive كل 20 ثانية؛ لو الكتابة فشلت الاتصال اتقطع
            final Socket ks = s;
            Thread hb = new Thread(new Runnable() {
                public void run() {
                    while (socket == ks) {
                        try {
                            Thread.sleep(20000);
                            if (socket == ks) send(command(REQ_KEEP_ALIVE, null, null));
                        } catch (Exception e) {
                            try { ks.close(); } catch (IOException ignored) { }
                            return;
                        }
                    }
                }
            }, "rcv-keepalive");
            hb.setDaemon(true);
            hb.start();

            // بنقرا ردود الريسيفر ونرميها (GCDH + طول + نوع + حالة + بيانات)
            s.setSoTimeout(0);
            byte[] head = new byte[16];
            while (true) {
                readFully(in, head);
                if (head[0] != 'G' || head[1] != 'C' || head[2] != 'D' || head[3] != 'H') {
                    log("unexpected data from receiver");
                    continue;
                }
                long len = ((head[4] & 0xFFL) << 24) | ((head[5] & 0xFF) << 16) | ((head[6] & 0xFF) << 8) | (head[7] & 0xFF);
                if (len < 0 || len > 32L << 20) throw new IOException("bad length");
                skip(in, len);
            }
        } finally {
            connected = false;
            if (socket == s) {
                socket = null;
                out = null;
            }
            try { s.close(); } catch (IOException ignored) { }
            if (was) listener.onDisconnected("انقطع الاتصال بالريسيفر");
        }
    }

    private static String android() {
        try {
            return (String) Class.forName("android.os.Build").getField("MODEL").get(null);
        } catch (Exception e) {
            return "phone";
        }
    }

    private static void readFully(InputStream in, byte[] b) throws IOException {
        int off = 0;
        while (off < b.length) {
            int n = in.read(b, off, b.length - off);
            if (n < 0) throw new EOFException("الريسيفر قفل الاتصال");
            off += n;
        }
    }

    private static void skip(InputStream in, long n) throws IOException {
        byte[] buf = new byte[4096];
        while (n > 0) {
            int r = in.read(buf, 0, (int) Math.min(buf.length, n));
            if (r < 0) throw new EOFException("الريسيفر قفل الاتصال");
            n -= r;
        }
    }

    private void send(String payload) throws IOException {
        OutputStream o = out;
        if (o == null || !connected) throw new IOException("مش متوصل بالريسيفر");
        byte[] f = frame(payload);
        synchronized (o) {
            o.write(f);
            o.flush();
        }
    }

    private void log(String line) {
        try { events.onRcvLog(line); } catch (Exception ignored) { }
    }

    // -------------------------------------------------------------- Remote

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public void disconnect() {
        Socket s = socket;
        if (s != null) {
            try { s.close(); } catch (IOException ignored) { }
        }
    }

    /** كود زرار الريسيفر مباشرة (لزراير زي FAV و TV/Radio). */
    public void sendRaw(int stbKey) throws IOException {
        if (stbKey <= 0) return;
        send(command(DO_REMOTE, "<KeyValue>" + stbKey + "</KeyValue>", "\"array\":[{\"KeyValue\":\"" + stbKey + "\"}]"));
    }

    @Override
    public void sendKey(int keyCode, int direction) throws IOException {
        if (direction == TvClient.END_LONG) return;
        Info i = info;
        int platform = i == null ? 0 : i.platform;
        if (keyCode == 67) { // مسح في الكيبورد
            sendRaw(10);
            return;
        }
        if (keyCode == 66) { // Enter في الكيبورد
            sendRaw(platform == 20 || platform == 21 || platform == 25 ? 8 : 11);
            return;
        }
        if (keyCode == 62) {
            sendText(" ");
            return;
        }
        Integer k = platform == 30 && KEYS_30.containsKey(keyCode) ? KEYS_30.get(keyCode) : KEYS.get(keyCode);
        if (k != null) sendRaw(k);
    }

    /** الكتابة حرف حرف (لما كيبورد الريسيفر يكون مفتوح على الشاشة). */
    @Override
    public void sendText(String text) throws IOException {
        if (text == null) return;
        Info i = info;
        int p = i == null ? 0 : i.platform;
        if (p == 32 || p == 71 || p == 72 || p == 74) throw new IOException("الكتابة مش مدعومة في الريسيفر ده");
        for (int n = 0; n < text.length(); ) {
            int cp = text.codePointAt(n);
            n += Character.charCount(cp);
            send(command(DO_INPUT_KEYCODE, "<KeyCode>" + cp + "</KeyCode>", "\"KeyCode\":\"" + cp + "\""));
        }
    }

    @Override
    public void launch(String link) throws IOException {
        // الريسيفر مافيهوش تطبيقات
    }

    // ----------------------------------------------------------- Discovery

    public interface Found {
        void onFound(String ip, Info info);
    }

    /** بيسمع إعلانات الريسيفرات على UDP 25860 لمدة معينة. */
    public static void listen(int durationMs, Found cb) {
        DatagramSocket s = null;
        try {
            s = new DatagramSocket(null);
            s.setReuseAddress(true);
            s.setBroadcast(true);
            s.bind(new InetSocketAddress(BROADCAST_PORT));
            s.setSoTimeout(1000);
            long end = System.currentTimeMillis() + durationMs;
            byte[] buf = new byte[2048];
            while (System.currentTimeMillis() < end) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    s.receive(p);
                } catch (SocketTimeoutException e) {
                    continue;
                }
                Info i = parseInfo(p.getData(), p.getLength());
                if (i != null) cb.onFound(p.getAddress().getHostAddress(), i);
            }
        } catch (IOException ignored) {
        } finally {
            if (s != null) s.close();
        }
    }
}
