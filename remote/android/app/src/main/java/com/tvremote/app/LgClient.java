package com.tvremote.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * التحكم في تليفزيونات LG (webOS) — نفس طريقة تطبيق LG ThinQ:
 *   WebSocket على بورت 3001 (wss) أو 3000 (ws)، وأول مرة التليفزيون بيسأل "تسمح للجهاز ده؟".
 *   الزراير بتتبعت على WebSocket تاني (pointer input socket)، والكتابة وفتح التطبيقات بـ ssap://.
 */
public class LgClient implements Remote {

    public interface Events {
        /** التليفزيون بيعرض رسالة "تسمح؟" — دوس OK بالريموت بتاع التليفزيون. */
        void onLgPrompt();
        /** المفتاح اللي التليفزيون اداهولنا عشان مايسألش تاني. */
        void onLgKey(String clientKey);
        void onLgMac(String mac);
        void onLgApps(JSONArray apps);
        /** مصادر الدخل (HDMI...) اللي في التليفزيون. */
        void onLgInputs(JSONArray inputs);
        /** سطر في سجل التشخيص (بيظهر في الإعدادات). */
        void onLgLog(String line);
        /** التليفزيون ده بيرفض المانيفست الموقّع — نفتكرها حتى بعد ما التطبيق يتقفل. */
        void onLgUnsigned();
    }

    /** التليفزيون رفض أو محدش وافق. */
    public static class RejectedException extends IOException {
        RejectedException(String m) {
            super(m);
        }
    }

    static int portWss = 3001;
    /** التليفزيونات الجديدة بترفض المانيفست الموقّع ("blacklisted certificate") — بنفتكر ده عشان مانضيعش وقت. */
    static volatile boolean signedRejected;
    /** تليفزيونات webOS القديمة مافيهاش wss — بنفتكر عشان نبدأ بـ ws على طول. */
    static volatile boolean preferWs;
    static int portWs = 3000;

    // زراير الريموت (أرقام KeyEvent بتاعة أندرويد) → أسماء زراير webOS
    private static final Map<Integer, String> BUTTONS = new HashMap<>();

    static {
        BUTTONS.put(3, "HOME");
        BUTTONS.put(4, "BACK");
        BUTTONS.put(19, "UP");
        BUTTONS.put(20, "DOWN");
        BUTTONS.put(21, "LEFT");
        BUTTONS.put(22, "RIGHT");
        BUTTONS.put(23, "ENTER");
        BUTTONS.put(24, "VOLUMEUP");
        BUTTONS.put(25, "VOLUMEDOWN");
        BUTTONS.put(91, "MUTE");
        BUTTONS.put(164, "MUTE");
        BUTTONS.put(166, "CHANNELUP");
        BUTTONS.put(167, "CHANNELDOWN");
        BUTTONS.put(82, "MENU");
        BUTTONS.put(176, "MENU");
        BUTTONS.put(126, "PLAY");
        BUTTONS.put(127, "PAUSE");
        BUTTONS.put(86, "STOP");
        BUTTONS.put(89, "REWIND");
        BUTTONS.put(90, "FASTFORWARD");
        BUTTONS.put(88, "REWIND");
        BUTTONS.put(87, "FASTFORWARD");
        BUTTONS.put(165, "INFO");
        BUTTONS.put(172, "GUIDE");
        BUTTONS.put(178, "INPUT_HUB");
        BUTTONS.put(183, "RED");
        BUTTONS.put(184, "GREEN");
        BUTTONS.put(185, "YELLOW");
        BUTTONS.put(186, "BLUE");
        BUTTONS.put(111, "EXIT");
        for (int i = 0; i <= 9; i++) BUTTONS.put(7 + i, String.valueOf(i));
    }

    private final TvClient.Listener listener;
    private final Events events;
    private volatile String clientKey;

    private volatile Ws main;
    private volatile Ws input;
    private volatile String inputPath;
    private volatile boolean connected;
    private volatile boolean playing = true;
    private volatile boolean screenOn = true;
    private volatile boolean muted;
    private volatile String host;
    private final Map<String, JSONObject[]> pending = new HashMap<>();
    private int nextId = 1;
    private final ArrayDeque<JSONObject> early = new ArrayDeque<>();

    public LgClient(String clientKey, TvClient.Listener listener, Events events) {
        this.clientKey = clientKey == null || clientKey.isEmpty() ? null : clientKey;
        this.listener = listener;
        this.events = events;
    }

    // ------------------------------------------------------------ Connect

    @Override
    public void runRemote(String host) throws IOException {
        this.host = host;
        inputPath = null; // عنوان socket الزراير بيتغير كل مرة
        long t0 = System.currentTimeMillis();
        String first = preferWs ? "ws://" + host + ":" + portWs : "wss://" + host + ":" + portWss;
        String second = preferWs ? "wss://" + host + ":" + portWss : "ws://" + host + ":" + portWs;
        Ws ws;
        try {
            ws = Ws.connect(first, 5000);
            log("main: " + first + " OK");
        } catch (IOException e) {
            log("main: " + first + " failed: " + e);
            try {
                ws = Ws.connect(second, 5000);
            } catch (IOException e2) {
                log("main: " + second + " failed: " + e2);
                throw e2;
            }
            preferWs = second.startsWith("ws:");
            log("main: " + second + " OK");
        }
        main = ws;
        early.clear();
        String model = "";
        try {
            ws.setTimeout(10000);
            log("tls+websocket: " + (System.currentTimeMillis() - t0) + " ms");
            // بنبعت كله مرة واحدة من غير ما نستنى رد: بعض التليفزيونات مابتردش على hello/systemInfo
            // قبل التسجيل، وكنا بنستنى 10 ثواني على الفاضي
            send(ws, msg("hello", "hello", null, new JSONObject()));
            send(ws, msg("request", "sys0", "ssap://system/getSystemInfo", new JSONObject()));
            register(ws);
            log("registered: " + (System.currentTimeMillis() - t0) + " ms");
            boolean gotHello = false, gotSys = false;
            for (JSONObject e : early) {
                String eid = e.optString("id");
                if ("hello".equals(eid)) gotHello = true;
                if ("sys0".equals(eid)) {
                    gotSys = true;
                    JSONObject p = e.optJSONObject("payload");
                    if (p != null && !p.optString("modelName", "").isEmpty()) model = p.optString("modelName");
                }
            }
            log("replies before register: hello=" + gotHello + " systemInfo=" + gotSys);

            send(ws, msg("subscribe", "sub_apps", "ssap://com.webos.applicationManager/listLaunchPoints", new JSONObject()));
            send(ws, msg("subscribe", "sub_inputs", "ssap://tv/getExternalInputList", new JSONObject()));
            send(ws, msg("subscribe", "sub_vol", "ssap://audio/getVolume", new JSONObject()));
            send(ws, msg("subscribe", "sub_app", "ssap://com.webos.applicationManager/getForegroundAppInfo", new JSONObject()));
            send(ws, msg("subscribe", "sub_power", "ssap://com.webos.service.tvpower/power/getPowerState", new JSONObject()));

            connected = true;
            listener.onConnected("LG", model.isEmpty() ? "webOS TV" : model);
            log("connected in " + (System.currentTimeMillis() - t0) + " ms");
            final long t0Final = t0;

            // الحاجات اللي مش لازمة عشان نقول "متوصل" بتتعمل في الخلفية
            final Ws mainWs = ws;
            final boolean needModel = model.isEmpty();
            Thread setup = new Thread(new Runnable() {
                public void run() {
                    if (main != mainWs) return;
                    openInput(); // socket الزراير (أول زرار بيستناه لو لسه بيفتح)
                    log("buttons ready: " + (System.currentTimeMillis() - t0Final) + " ms");
                    if (needModel) fetchModel();
                    fetchMac();
                }
            }, "lg-setup");
            setup.setDaemon(true);
            setup.start();

            // مفيش ping من التليفزيون، فبنبعت إحنا كل 15 ثانية
            final Ws pinged = ws;
            Thread hb = new Thread(new Runnable() {
                public void run() {
                    while (main == pinged) {
                        try {
                            Thread.sleep(15000);
                            if (main == pinged) pinged.ping();
                        } catch (Exception e) {
                            return;
                        }
                    }
                }
            }, "lg-heartbeat");
            hb.setDaemon(true);
            hb.start();

            ws.setTimeout(45000);
            while (!early.isEmpty()) handle(early.poll());
            while (true) handle(new JSONObject(ws.readText()));
        } catch (JSONException e) {
            throw new IOException(L.t("رد غريب من التليفزيون"), e);
        } catch (SocketTimeoutException e) {
            throw new IOException(L.t("التليفزيون مش بيرد"), e);
        } finally {
            boolean was = connected;
            connected = false;
            main = null;
            ws.close();
            Ws in = input;
            input = null;
            if (in != null) in.close();
            if (was) listener.onDisconnected(L.t("انقطع الاتصال"));
        }
    }

    private void register(Ws ws) throws IOException, JSONException {
        boolean signed = !signedRejected;
        for (int attempt = 0; attempt < 2; attempt++) {
            JSONObject res = call(ws, registration(signed, "register_" + attempt));
            if (res == null) throw new IOException(L.t("مفيش رد على التسجيل"));
            log("register (" + (signed ? "signed" : "unsigned") + "): " + res.optString("type") + " " + res.optString("error"));
            if ("error".equals(res.optString("type")) && attempt == 0) {
                if (signed) {
                    signedRejected = true; // الـ firmware الجديد بيرفض المانيفست الموقّع
                    events.onLgUnsigned();
                }
                signed = !signed; // نجرب الطريقة التانية
                continue;
            }
            if ("response".equals(res.optString("type"))) {
                // التليفزيون بيعرض "تسمح؟" — بنستنى لحد دقيقة
                events.onLgPrompt();
                ws.setTimeout(60000);
                try {
                    while (true) {
                        res = new JSONObject(ws.readText());
                        String type = res.optString("type");
                        if ("registered".equals(type) || "error".equals(type)) break;
                    }
                } catch (SocketTimeoutException e) {
                    throw new RejectedException(L.t("محدش وافق على التليفزيون"));
                }
                ws.setTimeout(10000);
            }
            if ("error".equals(res.optString("type"))) {
                throw new RejectedException(L.t("التليفزيون رفض الاتصال"));
            }
            if ("registered".equals(res.optString("type"))) {
                JSONObject p = res.optJSONObject("payload");
                String key = p == null ? null : p.optString("client-key", null);
                if (key != null && !key.isEmpty() && !key.equals(clientKey)) {
                    clientKey = key;
                    events.onLgKey(key);
                }
                return;
            }
        }
        throw new RejectedException(L.t("التليفزيون رفض الاتصال"));
    }

    private void handle(JSONObject m) {
        String id = m.optString("id");
        synchronized (pending) {
            JSONObject[] slot = pending.get(id);
            if (slot != null) {
                slot[0] = m;
                pending.notifyAll();
                return;
            }
        }
        if ("error".equals(m.optString("type"))) log("TV error (" + id + "): " + m.optString("error"));
        JSONObject p = m.optJSONObject("payload");
        if (p == null) return;
        if ("sub_vol".equals(id)) {
            JSONObject v = p.optJSONObject("volumeStatus") != null ? p.optJSONObject("volumeStatus") : p;
            if (v.has("volume")) {
                muted = v.optBoolean("muteStatus", v.optBoolean("muted", false));
                listener.onVolume(v.optInt("volume"), v.optInt("maxVolume", 100), muted);
            }
        } else if ("sub_app".equals(id)) {
            String app = p.optString("appId", "");
            if (!app.isEmpty()) listener.onApp(app);
        } else if ("sub_power".equals(id)) {
            String state = p.optString("state", "");
            if (!state.isEmpty()) {
                screenOn = "Active".equals(state);
                listener.onPower(screenOn);
            }
        } else if ("sub_inputs".equals(id)) {
            JSONArray dev = p.optJSONArray("devices");
            if (dev != null) {
                JSONArray out = new JSONArray();
                for (int i = 0; i < dev.length(); i++) {
                    JSONObject d = dev.optJSONObject(i);
                    if (d == null) continue;
                    try {
                        out.put(new JSONObject()
                                .put("id", d.optString("id"))
                                .put("label", d.optString("label", d.optString("id")))
                                .put("appId", d.optString("appId"))
                                .put("connected", d.optBoolean("connected", false)));
                    } catch (JSONException ignored) { }
                }
                events.onLgInputs(out);
            } else {
                log("inputs: " + m.toString());
            }
        } else if ("sub_apps".equals(id)) {
            JSONArray lp = p.optJSONArray("launchPoints");
            if (lp != null) {
                JSONArray out = new JSONArray();
                for (int i = 0; i < lp.length(); i++) {
                    JSONObject a = lp.optJSONObject(i);
                    if (a == null) continue;
                    try {
                        out.put(new JSONObject()
                                .put("id", a.optString("id"))
                                .put("title", a.optString("title"))
                                .put("icon", a.optString("icon", a.optString("largeIcon", ""))));
                    } catch (JSONException ignored) { }
                }
                events.onLgApps(out);
            }
        }
    }

    // ------------------------------------------------------- Messaging

    private JSONObject msg(String type, String id, String uri, JSONObject payload) throws IOException {
        try {
            JSONObject o = new JSONObject();
            o.put("type", type);
            o.put("id", id != null ? id : "req_" + (nextId++));
            if (uri != null) o.put("uri", uri);
            if (payload != null) o.put("payload", payload);
            return o;
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    private static void send(Ws ws, JSONObject m) throws IOException {
        ws.sendText(m.toString());
    }

    /** بيبعت ويستنى الرد اللي ليه نفس الـ id (الرسايل التانية بتتأجل). */
    private JSONObject call(Ws ws, JSONObject m) throws IOException, JSONException {
        String id = m.optString("id");
        send(ws, m);
        try {
            for (int i = 0; i < 50; i++) {
                JSONObject r = new JSONObject(ws.readText());
                if (id.equals(r.optString("id"))) return r;
                early.add(r);
            }
        } catch (SocketTimeoutException e) {
            if (id.startsWith("register")) throw e;
            return null; // بعض الأجهزة مابتردش على كل حاجة
        }
        return null;
    }

    private void request(String uri, JSONObject payload) throws IOException {
        Ws ws = main;
        if (ws == null || !connected) throw new IOException(L.t("مش متوصل"));
        send(ws, msg("request", null, uri, payload == null ? new JSONObject() : payload));
    }

    /** اسم الموديل لو ماجاش قبل التسجيل. */
    private void fetchModel() {
        try {
            JSONObject r = requestWait("ssap://system/getSystemInfo", null, 3000);
            String m = r == null || r.optJSONObject("payload") == null ? "" : r.optJSONObject("payload").optString("modelName", "");
            if (!m.isEmpty() && connected) listener.onConnected("LG", m);
        } catch (IOException ignored) { }
    }

    /** عنوان MAC عشان نشغّل التليفزيون وهو مقفول. بنحفظ الاتنين (واي فاي وكابل). */
    private void fetchMac() {
        try {
            JSONObject info = requestWait("ssap://com.webos.service.connectionmanager/getinfo", null, 6000);
            log("network info: " + (info == null ? "none" : info.optString("type") + " " + info.optString("error")));
            if (info == null || info.optJSONObject("payload") == null) return;
            JSONObject p = info.optJSONObject("payload");
            StringBuilder macs = new StringBuilder();
            String[] keys = {"wifiInfo", "wiredInfo"};
            for (String k : keys) {
                JSONObject o = p.optJSONObject(k);
                String mac = o == null ? "" : o.optString("macAddress", "");
                if (mac.matches("([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}")) {
                    if (macs.length() > 0) macs.append(',');
                    macs.append(mac.toLowerCase(java.util.Locale.US));
                }
            }
            log("MAC: " + (macs.length() == 0 ? "not provided" : macs));
            if (macs.length() > 0) events.onLgMac(macs.toString());
        } catch (IOException e) {
            log("network info failed: " + e);
        }
    }

    /** طلب وهو متوصل: بيستنى الرد من الـ loop الأساسي. */
    private JSONObject requestWait(String uri, JSONObject payload, int timeoutMs) throws IOException {
        Ws ws = main;
        if (ws == null || !connected) throw new IOException(L.t("مش متوصل"));
        JSONObject m = msg("request", null, uri, payload == null ? new JSONObject() : payload);
        String id = m.optString("id");
        JSONObject[] slot = new JSONObject[1];
        synchronized (pending) {
            pending.put(id, slot);
        }
        try {
            send(ws, m);
            long end = System.currentTimeMillis() + timeoutMs;
            synchronized (pending) {
                while (slot[0] == null) {
                    long left = end - System.currentTimeMillis();
                    if (left <= 0) return null;
                    try { pending.wait(left); } catch (InterruptedException e) { return null; }
                }
            }
            return slot[0];
        } finally {
            synchronized (pending) {
                pending.remove(id);
            }
        }
    }

    /** بيفتح socket الزراير. بنجرب العنوان اللي التليفزيون اداه، وكمان wss:3001 / ws:3000 (تليفزيونات webOS الجديدة بتقفل 3000). */
    private synchronized void openInput() {
        if (input != null) return;
        String path = inputPath;
        if (path == null && connected) {
            try {
                JSONObject r = requestWait("ssap://com.webos.service.networkinput/getPointerInputSocket", null, 5000);
                log("pointer socket reply (retry): " + (r == null ? "none" : r.toString()));
                if (r != null && r.optJSONObject("payload") != null) path = r.optJSONObject("payload").optString("socketPath", null);
                inputPath = path;
            } catch (IOException e) {
                log("pointer socket request failed: " + e);
            }
        }
        if (path == null || path.isEmpty()) return;
        java.util.LinkedHashSet<String> tries = new java.util.LinkedHashSet<>();
        tries.add(path);
        String rest = path.replaceFirst("^wss?://[^/]+", "");
        if (host != null) {
            tries.add("wss://" + host + ":" + portWss + rest);
            tries.add("ws://" + host + ":" + portWs + rest);
        }
        for (String url : tries) {
            try {
                final Ws in = Ws.connect(url, 5000);
                in.setTimeout(0);
                input = in;
                inputPath = url;
                log("buttons socket OK: " + url);
                Thread t = new Thread(new Runnable() {
                    public void run() {
                        try {
                            while (true) in.readText();
                        } catch (IOException e) {
                            if (input == in) input = null;
                        }
                    }
                }, "lg-input");
                t.setDaemon(true);
                t.start();
                return;
            } catch (IOException e) {
                log("buttons socket failed: " + url + " → " + e);
            }
        }
    }

    private void inputCommand(String text) throws IOException {
        Ws in = input;
        if (in == null) {
            openInput();
            in = input;
        }
        if (in == null) throw new IOException(L.t("زراير الريموت مش متاحة في التليفزيون ده"));
        try {
            in.sendText(text);
        } catch (IOException e) {
            input = null;
            openInput();
            if (input == null) throw e;
            input.sendText(text);
        }
    }

    private void log(String line) {
        try { events.onLgLog(line); } catch (Exception ignored) { }
    }

    // ---------------------------------------------------------- Remote

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public void disconnect() {
        Ws ws = main;
        if (ws != null) ws.close();
    }

    @Override
    public void sendKey(int keyCode, int direction) throws IOException {
        if (direction == TvClient.END_LONG) return; // webOS مافيهوش ضغطة طويلة
        if (!connected) throw new IOException(L.t("مش متوصل"));
        switch (keyCode) {
            case 26:
                if (screenOn) request("ssap://system/turnOff", null);
                else request("ssap://system/turnOn", null);
                return;
            case 85: // تشغيل/إيقاف
                inputCommand(button(playing ? "PAUSE" : "PLAY"));
                playing = !playing;
                return;
            case 66:
                request("ssap://com.webos.service.ime/sendEnterKey", null);
                return;
            case 67:
                try {
                    request("ssap://com.webos.service.ime/deleteCharacters", new JSONObject().put("count", 1));
                } catch (JSONException ignored) { }
                return;
            case 62:
                sendText(" ");
                return;
            case 84:
                return;
            default:
                String b = BUTTONS.get(keyCode);
                if (b == null) return;
                try {
                    inputCommand(button(b));
                } catch (IOException e) {
                    // لو socket الزراير مش شغال، اللي ليه أمر ssap بديل نبعته بيه
                    if (!ssapFallback(b)) throw e;
                }
        }
    }

    private boolean ssapFallback(String b) throws IOException {
        try {
            if ("VOLUMEUP".equals(b)) request("ssap://audio/volumeUp", null);
            else if ("VOLUMEDOWN".equals(b)) request("ssap://audio/volumeDown", null);
            else if ("MUTE".equals(b)) request("ssap://audio/setMute", new JSONObject().put("mute", !muted));
            else if ("CHANNELUP".equals(b)) request("ssap://tv/channelUp", null);
            else if ("CHANNELDOWN".equals(b)) request("ssap://tv/channelDown", null);
            else if ("PLAY".equals(b)) request("ssap://media.controls/play", null);
            else if ("PAUSE".equals(b)) request("ssap://media.controls/pause", null);
            else if ("STOP".equals(b)) request("ssap://media.controls/stop", null);
            else if ("REWIND".equals(b)) request("ssap://media.controls/rewind", null);
            else if ("FASTFORWARD".equals(b)) request("ssap://media.controls/fastForward", null);
            else if ("HOME".equals(b)) request("ssap://system.launcher/launch", new JSONObject().put("id", "com.webos.app.home"));
            else return false;
            return true;
        } catch (JSONException e) {
            return false;
        }
    }

    private static String button(String name) {
        return "type:button\nname:" + name + "\n\n";
    }

    @Override
    public void sendText(String text) throws IOException {
        if (text == null || text.isEmpty()) return;
        try {
            request("ssap://com.webos.service.ime/insertText", new JSONObject().put("text", text).put("replace", false));
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    /** link = "lg:<appId>" أو رابط http يتفتح في متصفح التليفزيون. */
    @Override
    public void launch(String link) throws IOException {
        try {
            if (link.startsWith("lg:")) {
                request("ssap://system.launcher/launch", new JSONObject().put("id", link.substring(3)));
            } else if (link.startsWith("http")) {
                request("ssap://system.launcher/open", new JSONObject().put("target", link));
            } else {
                request("ssap://system.launcher/launch", new JSONObject().put("id", link));
            }
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    /** تغيير المصدر (مثلًا HDMI_1). */
    public void switchInput(String inputId) throws IOException {
        try {
            request("ssap://tv/switchInput", new JSONObject().put("inputId", inputId));
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    /** الماوس: تحريك السهم على الشاشة. */
    public void move(int dx, int dy) throws IOException {
        inputCommand("type:move\ndx:" + dx + "\ndy:" + dy + "\ndown:0\n\n");
    }

    public void click() throws IOException {
        inputCommand("type:click\n\n");
    }

    public void scroll(int dx, int dy) throws IOException {
        inputCommand("type:scroll\ndx:" + dx + "\ndy:" + dy + "\n\n");
    }

    // -------------------------------------------------------- Power on

    /** تشغيل التليفزيون وهو مقفول (Wake-on-LAN) — لازم "التشغيل عن طريق Wi-Fi" يكون مفعّل في التليفزيون. */
    public static String wake(String mac, String host) throws IOException {
        String[] parts = mac.split("[:-]");
        if (parts.length != 6) throw new IOException(L.t("عنوان MAC مش معروف"));
        byte[] m = new byte[6];
        for (int i = 0; i < 6; i++) m[i] = (byte) Integer.parseInt(parts[i], 16);
        byte[] pkt = new byte[102];
        for (int i = 0; i < 6; i++) pkt[i] = (byte) 0xFF;
        for (int i = 6; i < pkt.length; i++) pkt[i] = m[(i - 6) % 6];

        // بنبعت من كارت الواي فاي نفسه (لو الموبايل فاتح داتا، البرودكاست ممكن يروح على الداتا)
        InetAddress local = localAddressFor(host);
        DatagramSocket s = local != null ? new DatagramSocket(new java.net.InetSocketAddress(local, 0)) : new DatagramSocket();
        try {
            s.setBroadcast(true);
            String[] targets = {subnetBroadcast(host), "255.255.255.255", host};
            int[] ports = {9, 7};
            for (String t : targets) {
                if (t == null) continue;
                for (int port : ports) {
                    try {
                        s.send(new DatagramPacket(pkt, pkt.length, InetAddress.getByName(t), port));
                    } catch (IOException ignored) { }
                }
            }
        } finally {
            s.close();
        }
        return local == null ? "default" : local.getHostAddress();
    }

    /** عنوان الموبايل على نفس شبكة التليفزيون (أول 3 أرقام زي بعض). */
    static InetAddress localAddressFor(String host) {
        String[] h = host.split("\\.");
        if (h.length != 4) return null;
        String prefix = h[0] + "." + h[1] + "." + h[2] + ".";
        try {
            for (java.net.NetworkInterface ni : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : java.util.Collections.list(ni.getInetAddresses())) {
                    if (a instanceof java.net.Inet4Address && a.getHostAddress().startsWith(prefix)) return a;
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    private static String subnetBroadcast(String host) {
        String[] p = host.split("\\.");
        return p.length == 4 ? p[0] + "." + p[1] + "." + p[2] + ".255" : null;
    }

    // ---------------------------------------------------- Registration

    JSONObject registration(boolean signed, String id) throws IOException {
        try {
            JSONArray perms = new JSONArray();
            for (String s : PERMISSIONS) perms.put(s);
            JSONObject manifest = new JSONObject()
                    .put("appVersion", "1.1")
                    .put("manifestVersion", 1);
            if (signed) {
                JSONArray sp = new JSONArray();
                for (String s : SIGNED_PERMISSIONS) sp.put(s);
                manifest.put("permissions", perms);
                manifest.put("signatures", new JSONArray().put(new JSONObject().put("signature", SIGNATURE).put("signatureVersion", 1)));
                manifest.put("signed", new JSONObject()
                        .put("appId", "com.lge.test")
                        .put("created", "20140509")
                        .put("localizedAppNames", new JSONObject().put("", "LG Remote App").put("ko-KR", "리모컨 앱").put("zxx-XX", "ЛГ Rэмotэ AПП"))
                        .put("localizedVendorNames", new JSONObject().put("", "LG Electronics"))
                        .put("permissions", sp)
                        .put("serial", "2f930e2d2cfe083771f68e4fe7bb07")
                        .put("vendorId", "com.lge"));
            } else {
                java.util.TreeSet<String> all = new java.util.TreeSet<>();
                java.util.Collections.addAll(all, PERMISSIONS);
                java.util.Collections.addAll(all, SIGNED_PERMISSIONS);
                JSONArray ap = new JSONArray();
                for (String s : all) ap.put(s);
                manifest.put("permissions", ap);
            }
            return new JSONObject()
                    .put("type", "register")
                    .put("id", id)
                    .put("payload", new JSONObject()
                            .put("forcePairing", false)
                            .put("pairingType", "PROMPT")
                            .put("client-key", clientKey == null ? JSONObject.NULL : clientKey)
                            .put("manifest", manifest));
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    private static final String SIGNATURE =
            "eyJhbGdvcml0aG0iOiJSU0EtU0hBMjU2Iiwia2V5SWQiOiJ0ZXN0LXNpZ25pbm"
            + "ctY2VydCIsInNpZ25hdHVyZVZlcnNpb24iOjF9.hrVRgjCwXVvE2OOSpDZ58hR"
            + "+59aFNwYDyjQgKk3auukd7pcegmE2CzPCa0bJ0ZsRAcKkCTJrWo5iDzNhMBWRy"
            + "aMOv5zWSrthlf7G128qvIlpMT0YNY+n/FaOHE73uLrS/g7swl3/qH/BGFG2Hu4"
            + "RlL48eb3lLKqTt2xKHdCs6Cd4RMfJPYnzgvI4BNrFUKsjkcu+WD4OO2A27Pq1n"
            + "50cMchmcaXadJhGrOqH5YmHdOCj5NSHzJYrsW0HPlpuAx/ECMeIZYDh6RMqaFM"
            + "2DXzdKX9NmmyqzJ3o/0lkk/N97gfVRLW5hA29yeAwaCViZNCP8iC9aO0q9fQoj"
            + "oa7NQnAtw==";

    private static final String[] PERMISSIONS = {
            "LAUNCH", "LAUNCH_WEBAPP", "APP_TO_APP", "CLOSE", "TEST_OPEN", "TEST_PROTECTED",
            "CONTROL_AUDIO", "CONTROL_DISPLAY", "CONTROL_INPUT_JOYSTICK", "CONTROL_INPUT_MEDIA_RECORDING",
            "CONTROL_INPUT_MEDIA_PLAYBACK", "CONTROL_INPUT_TV", "CONTROL_POWER", "CONTROL_TV_SCREEN",
            "READ_APP_STATUS", "READ_CURRENT_CHANNEL", "READ_INPUT_DEVICE_LIST", "READ_NETWORK_STATE",
            "READ_RUNNING_APPS", "READ_TV_CHANNEL_LIST", "WRITE_NOTIFICATION_TOAST", "READ_POWER_STATE",
            "READ_COUNTRY_INFO", "CONTROL_INPUT_TEXT", "CONTROL_MOUSE_AND_KEYBOARD", "READ_INSTALLED_APPS",
            "READ_SETTINGS",
    };

    private static final String[] SIGNED_PERMISSIONS = {
            "TEST_SECURE", "CONTROL_INPUT_TEXT", "CONTROL_MOUSE_AND_KEYBOARD", "READ_INSTALLED_APPS",
            "READ_LGE_SDX", "READ_NOTIFICATIONS", "SEARCH", "WRITE_SETTINGS", "WRITE_NOTIFICATION_ALERT",
            "CONTROL_POWER", "READ_CURRENT_CHANNEL", "READ_RUNNING_APPS", "READ_UPDATE_INFO",
            "UPDATE_FROM_REMOTE_APP", "READ_LGE_TV_INPUT_EVENTS", "READ_TV_CURRENT_TIME",
    };
}
