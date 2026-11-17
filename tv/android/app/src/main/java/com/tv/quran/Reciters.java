package com.tv.quran;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * القرّاء اللي ممكن تشغّل بيهم سورة البقرة.
 * بنجيب الروابط من mp3quran.net، ولو مفيش نت بنستعمل آخر نسخة محفوظة أو لستة ثابتة.
 * load() بتتنادي من خيط في الخلفية بس.
 */
final class Reciters {

    static final String UA = "QuranTV/1.0 (Android)";

    static final class Reciter {
        final String name, moshaf, server;

        Reciter(String name, String moshaf, String server) {
            this.name = name;
            this.moshaf = moshaf;
            this.server = server;
        }

        String baqarahUrl() {
            return server + "002.mp3";
        }

        String label() {
            return moshaf == null || moshaf.isEmpty() ? name : name + " — " + moshaf;
        }
    }

    /** بالترتيب ده بيظهروا لما تقلّب بالريموت، وأولهم هو الافتراضي. */
    private static final String[] FAMOUS = {
            "عبدالباسط عبدالصمد", "محمد صديق المنشاوي", "محمود خليل الحصري", "مصطفى إسماعيل",
            "محمد محمود الطبلاوي", "مشاري العفاسي", "ماهر المعيقلي", "سعد الغامدي",
            "عبدالرحمن السديس", "سعود الشريم", "ياسر الدوسري", "أحمد بن علي العجمي",
            "ناصر القطامي", "إسلام صبحي", "فارس عباد", "إدريس أبكر", "محمد أيوب"
    };

    private static final String[][] BUILTIN = {
            {"عبدالباسط عبدالصمد", "https://server7.mp3quran.net/basit/"},
            {"محمد صديق المنشاوي", "https://server10.mp3quran.net/minsh/"},
            {"محمود خليل الحصري", "https://server13.mp3quran.net/husr/"},
            {"مشاري العفاسي", "https://server8.mp3quran.net/afs/"},
            {"ماهر المعيقلي", "https://server12.mp3quran.net/maher/"},
            {"سعد الغامدي", "https://server7.mp3quran.net/s_gmd/"},
            {"عبدالرحمن السديس", "https://server11.mp3quran.net/sds/"},
            {"ياسر الدوسري", "https://server11.mp3quran.net/yasser/"},
    };

    private static final String API = "https://mp3quran.net/api/v3/reciters?language=ar";
    private static final long CACHE_MS = 7L * 24 * 60 * 60 * 1000;

    static List<Reciter> builtin() {
        List<Reciter> l = new ArrayList<>();
        for (String[] b : BUILTIN) l.add(new Reciter(b[0], "مرتل", b[1]));
        return l;
    }

    static List<Reciter> load(Context ctx) {
        File f = new File(ctx.getFilesDir(), "reciters.json");
        String json = null;
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < CACHE_MS) json = read(f);
        if (json == null) {
            json = httpGet(API);
            if (json != null) write(f, json);
            else if (f.exists()) json = read(f);
        }
        if (json != null) {
            try {
                List<Reciter> l = parse(json);
                if (!l.isEmpty()) return l;
            } catch (Exception ignored) {
            }
        }
        return builtin();
    }

    private static List<Reciter> parse(String json) throws Exception {
        JSONArray arr = new JSONObject(json).getJSONArray("reciters");
        // اسم القارئ (بعد التوحيد) ← كل مصاحفه اللي فيها البقرة
        Map<String, List<Reciter>> byName = new LinkedHashMap<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            String name = o.optString("name").trim();
            JSONArray ms = o.optJSONArray("moshaf");
            if (name.isEmpty() || ms == null) continue;
            List<Reciter> list = new ArrayList<>();
            for (int j = 0; j < ms.length(); j++) {
                JSONObject m = ms.getJSONObject(j);
                String server = m.optString("server");
                if (!server.startsWith("http") || !hasBaqarah(m.optString("surah_list"))) continue;
                if (!server.endsWith("/")) server += "/";
                String mn = m.optString("name").trim();
                if (!mn.contains("حفص")) continue; // الرواية المعتادة في مصر
                list.add(new Reciter(name, shortMoshaf(mn), server));
            }
            if (list.isEmpty()) continue;
            // المرتل الأول وبعدين المجود
            Collections.sort(list, (a, b) -> Integer.compare(rank(a.moshaf), rank(b.moshaf)));
            byName.put(" " + norm(name) + " ", list);
        }

        List<Reciter> out = new ArrayList<>();
        for (String want : FAMOUS) {
            String[] words = norm(want).split(" ");
            for (Map.Entry<String, List<Reciter>> e : byName.entrySet()) {
                boolean all = true;
                for (String w : words) if (!e.getKey().contains(" " + w + " ")) all = false;
                if (all) {
                    for (Reciter r : e.getValue()) if (!contains(out, r)) out.add(r);
                }
            }
        }
        return out;
    }

    private static boolean contains(List<Reciter> l, Reciter r) {
        for (Reciter x : l) if (x.server.equals(r.server)) return true;
        return false;
    }

    private static int rank(String moshaf) {
        if (moshaf.contains("مرتل")) return 0;
        if (moshaf.contains("مجود")) return 1;
        return 2;
    }

    private static String shortMoshaf(String n) {
        if (n.contains("مرتل")) return "مرتل";
        if (n.contains("مجود")) return "مجود";
        if (n.contains("معلم")) return "المصحف المعلم";
        return n.replace("حفص عن عاصم", "").replace("-", "").trim();
    }

    private static boolean hasBaqarah(String list) {
        for (String p : list.split(",")) if (p.trim().equals("2")) return true;
        return false;
    }

    static String norm(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toLowerCase(Locale.ROOT).toCharArray()) {
            if (c >= 'ً' && c <= 'ٟ' || c == 'ـ' || c == 'ٰ') continue;
            if (c == 'أ' || c == 'إ' || c == 'آ') c = 'ا';
            else if (c == 'ة') c = 'ه';
            else if (c == 'ى') c = 'ي';
            else if (!Character.isLetterOrDigit(c)) c = ' ';
            b.append(c);
        }
        return b.toString().replace("عبد ال", "عبدال").replaceAll("\\s+", " ").trim();
    }

    private static String httpGet(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", UA);
            if (c.getResponseCode() != 200) return null;
            return readAll(c.getInputStream());
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String read(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            return readAll(in);
        } catch (Exception e) {
            return null;
        }
    }

    private static void write(File f, String s) {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(f);
    }

    private static String readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }
}
