package com.tvremote.app;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * WebSocket client صغير (RFC 6455) من غير مكتبات — كفاية لتليفزيونات LG (webOS):
 * رسايل نصية، ping/pong، و ws:// أو wss:// (شهادة التليفزيون موقّعة من نفسه فبنقبلها).
 */
final class Ws {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;

    private Ws(Socket socket, InputStream in, OutputStream out) {
        this.socket = socket;
        this.in = in;
        this.out = out;
    }

    static Ws connect(String uri, int timeoutMs) throws IOException {
        URI u = URI.create(uri);
        boolean tls = "wss".equals(u.getScheme());
        String host = u.getHost();
        int port = u.getPort() > 0 ? u.getPort() : (tls ? 443 : 80);
        String path = (u.getRawPath() == null || u.getRawPath().isEmpty()) ? "/" : u.getRawPath();
        if (u.getRawQuery() != null) path += "?" + u.getRawQuery();

        Socket raw = new Socket();
        Socket s = raw;
        try {
            raw.connect(new InetSocketAddress(host, port), timeoutMs);
            raw.setTcpNoDelay(true);
            raw.setSoTimeout(timeoutMs);
            if (tls) {
                SSLContext ctx = SSLContext.getInstance("TLS");
                ctx.init(null, new TrustManager[]{new X509TrustManager() {
                    public void checkClientTrusted(X509Certificate[] c, String a) { }
                    public void checkServerTrusted(X509Certificate[] c, String a) { }
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                }}, RANDOM);
                SSLSocket ssl = (SSLSocket) ctx.getSocketFactory().createSocket(raw, host, port, true);
                ssl.setUseClientMode(true);
                ssl.startHandshake();
                s = ssl;
            }
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            byte[] key = new byte[16];
            RANDOM.nextBytes(key);
            String req = "GET " + path + " HTTP/1.1\r\n"
                    + "Host: " + host + ":" + port + "\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: " + base64(key) + "\r\n"
                    + "Sec-WebSocket-Version: 13\r\n\r\n";
            out.write(req.getBytes(Proto.UTF8));
            out.flush();
            String head = readHead(in);
            String status = head.split("\r\n", 2)[0];
            if (!status.contains(" 101")) throw new IOException("WebSocket: " + status);
            return new Ws(s, in, out);
        } catch (Exception e) {
            try { s.close(); } catch (IOException ignored) { }
            try { raw.close(); } catch (IOException ignored) { }
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException(e.toString(), e);
        }
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int last4 = 0;
        while (last4 != 0x0D0A0D0A) { // لحد "\r\n\r\n"
            int c = in.read();
            if (c < 0) throw new EOFException("WebSocket: connection closed");
            b.write(c);
            if (b.size() > 16384) throw new IOException("WebSocket: header too big");
            last4 = (last4 << 8) | c;
        }
        return b.toString("ISO-8859-1");
    }

    void setTimeout(int ms) throws IOException {
        socket.setSoTimeout(ms);
    }

    void sendText(String text) throws IOException {
        send(0x1, text.getBytes(Proto.UTF8));
    }

    void ping() throws IOException {
        send(0x9, new byte[0]);
    }

    private void send(int opcode, byte[] payload) throws IOException {
        ByteArrayOutputStream f = new ByteArrayOutputStream(payload.length + 14);
        f.write(0x80 | opcode);
        int n = payload.length;
        if (n < 126) {
            f.write(0x80 | n);
        } else if (n < 65536) {
            f.write(0x80 | 126);
            f.write(n >>> 8);
            f.write(n);
        } else {
            f.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) f.write(i >= 4 ? 0 : (n >>> (8 * i)));
        }
        byte[] mask = new byte[4];
        RANDOM.nextBytes(mask);
        f.write(mask, 0, 4);
        for (int i = 0; i < n; i++) f.write(payload[i] ^ mask[i & 3]);
        synchronized (out) {
            out.write(f.toByteArray());
            out.flush();
        }
    }

    /** بيستنى رسالة نصية كاملة (وبيرد على ping لوحده). */
    String readText() throws IOException {
        ByteArrayOutputStream msg = new ByteArrayOutputStream();
        while (true) {
            int b0 = readByte();
            int b1 = readByte();
            boolean fin = (b0 & 0x80) != 0;
            int op = b0 & 0x0F;
            long len = b1 & 0x7F;
            if (len == 126) len = (readByte() << 8) | readByte();
            else if (len == 127) {
                len = 0;
                for (int i = 0; i < 8; i++) len = (len << 8) | readByte();
            }
            if (len > 8 << 20) throw new IOException("WebSocket: frame too big");
            byte[] mask = null;
            if ((b1 & 0x80) != 0) {
                mask = new byte[4];
                readFully(mask);
            }
            byte[] data = new byte[(int) len];
            readFully(data);
            if (mask != null) for (int i = 0; i < data.length; i++) data[i] ^= mask[i & 3];

            if (op == 0x8) throw new EOFException("WebSocket closed by TV");
            if (op == 0x9) { send(0xA, data); continue; }
            if (op == 0xA) continue;
            msg.write(data, 0, data.length);
            if (fin) return msg.toString("UTF-8");
        }
    }

    private int readByte() throws IOException {
        int c = in.read();
        if (c < 0) throw new EOFException("WebSocket: connection closed");
        return c;
    }

    private void readFully(byte[] b) throws IOException {
        int off = 0;
        while (off < b.length) {
            int n = in.read(b, off, b.length - off);
            if (n < 0) throw new EOFException("WebSocket: connection closed");
            off += n;
        }
    }

    void close() {
        try { send(0x8, new byte[0]); } catch (IOException ignored) { }
        try { socket.close(); } catch (IOException ignored) { }
    }

    private static final char[] B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    static String base64(byte[] d) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < d.length; i += 3) {
            int b = (d[i] & 0xFF) << 16 | (i + 1 < d.length ? (d[i + 1] & 0xFF) << 8 : 0) | (i + 2 < d.length ? d[i + 2] & 0xFF : 0);
            sb.append(B64[b >> 18 & 63]).append(B64[b >> 12 & 63])
              .append(i + 1 < d.length ? B64[b >> 6 & 63] : '=')
              .append(i + 2 < d.length ? B64[b & 63] : '=');
        }
        return sb.toString();
    }
}
