package com.tvremote.app;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Protobuf بسيط جدًا (من غير مكتبات): بيكتب ويقرا الرسايل اللي بروتوكول ريموت Android TV محتاجها.
 * الرسايل على الشبكة: طول الرسالة (varint) وبعده الرسالة نفسها.
 */
final class Proto {
    static final Charset UTF8 = Charset.forName("UTF-8");

    private Proto() { }

    /** بيبني رسالة protobuf. */
    static final class Writer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Writer varint(int field, long value) {
            if (value == 0) return this; // proto3: القيمة الافتراضية مش بتتبعت
            writeVarint(out, ((long) field << 3));
            writeVarint(out, value);
            return this;
        }

        Writer bytes(int field, byte[] value) {
            writeVarint(out, ((long) field << 3) | 2);
            writeVarint(out, value.length);
            out.write(value, 0, value.length);
            return this;
        }

        Writer string(int field, String value) {
            return bytes(field, value.getBytes(UTF8));
        }

        Writer msg(int field, Writer value) {
            return bytes(field, value.toByteArray());
        }

        byte[] toByteArray() {
            return out.toByteArray();
        }
    }

    /** رسالة متقريّة: كل رقم حقل ليه لستة قيم (Long للأرقام، byte[] للنصوص والرسايل). */
    static final class Msg {
        final Map<Integer, List<Object>> fields = new HashMap<>();

        static Msg parse(byte[] data) throws IOException {
            Msg m = new Msg();
            int[] pos = {0};
            while (pos[0] < data.length) {
                long key = readVarint(data, pos);
                int field = (int) (key >>> 3);
                int type = (int) (key & 7);
                Object value;
                switch (type) {
                    case 0:
                        value = readVarint(data, pos);
                        break;
                    case 1:
                        need(data, pos[0], 8);
                        long v64 = 0;
                        for (int i = 7; i >= 0; i--) v64 = (v64 << 8) | (data[pos[0] + i] & 0xFF);
                        pos[0] += 8;
                        value = v64;
                        break;
                    case 2:
                        int len = (int) readVarint(data, pos);
                        need(data, pos[0], len);
                        byte[] b = new byte[len];
                        System.arraycopy(data, pos[0], b, 0, len);
                        pos[0] += len;
                        value = b;
                        break;
                    case 5:
                        need(data, pos[0], 4);
                        long v32 = 0;
                        for (int i = 3; i >= 0; i--) v32 = (v32 << 8) | (data[pos[0] + i] & 0xFF);
                        pos[0] += 4;
                        value = v32;
                        break;
                    default:
                        throw new IOException("protobuf: wire type " + type);
                }
                List<Object> list = m.fields.get(field);
                if (list == null) {
                    list = new ArrayList<>();
                    m.fields.put(field, list);
                }
                list.add(value);
            }
            return m;
        }

        boolean has(int field) {
            return fields.containsKey(field);
        }

        long num(int field) {
            List<Object> l = fields.get(field);
            if (l == null) return 0;
            Object o = l.get(l.size() - 1);
            return o instanceof Long ? (Long) o : 0;
        }

        byte[] raw(int field) {
            List<Object> l = fields.get(field);
            if (l == null) return null;
            Object o = l.get(l.size() - 1);
            return o instanceof byte[] ? (byte[]) o : null;
        }

        String str(int field) {
            byte[] b = raw(field);
            return b == null ? "" : new String(b, UTF8);
        }

        /** رسالة داخلية؛ لو مش موجودة بيرجع رسالة فاضية. */
        Msg sub(int field) throws IOException {
            byte[] b = raw(field);
            return b == null ? new Msg() : parse(b);
        }
    }

    private static void need(byte[] data, int pos, int len) throws IOException {
        if (len < 0 || pos + len > data.length) throw new IOException("protobuf: truncated");
    }

    static void writeVarint(OutputStream out, long value) {
        try {
            while ((value & ~0x7FL) != 0) {
                out.write((int) ((value & 0x7F) | 0x80));
                value >>>= 7;
            }
            out.write((int) value);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    static long readVarint(byte[] data, int[] pos) throws IOException {
        long result = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            if (pos[0] >= data.length) throw new IOException("protobuf: truncated varint");
            int b = data[pos[0]++] & 0xFF;
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return result;
        }
        throw new IOException("protobuf: bad varint");
    }

    /** بيبعت رسالة واحدة على الشبكة (الطول + الرسالة). */
    static void send(OutputStream out, Writer msg) throws IOException {
        byte[] body = msg.toByteArray();
        ByteArrayOutputStream frame = new ByteArrayOutputStream(body.length + 5);
        writeVarint(frame, body.length);
        frame.write(body, 0, body.length);
        synchronized (out) {
            out.write(frame.toByteArray());
            out.flush();
        }
    }

    /** بيستنى رسالة واحدة كاملة من الشبكة. */
    static byte[] receive(InputStream in) throws IOException {
        long len = 0;
        for (int shift = 0; ; shift += 7) {
            int b = in.read();
            if (b < 0) throw new EOFException("connection closed");
            len |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) break;
            if (shift > 28) throw new IOException("bad length");
        }
        if (len > 1 << 20) throw new IOException("message too big");
        byte[] buf = new byte[(int) len];
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) throw new EOFException("connection closed");
            off += n;
        }
        return buf;
    }
}
