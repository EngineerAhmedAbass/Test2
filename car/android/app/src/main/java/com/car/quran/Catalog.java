package com.car.quran;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;

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
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * كل المحتوى: القرّاء والسور والإذاعات، ومعرّفات (mediaId) كل عنصر.
 *
 * المعرّفات:
 *   root, famous, all, qradio, egradio, recent  مجلدات
 *   rc|<رقم القارئ>                            قارئ (فيه أكتر من رواية)
 *   ms|<رابط السيرفر>                          رواية واحدة = لستة السور
 *   s|<رقم السورة>|<رابط السيرفر>               سورة (تتشغل)
 *   st|<رابط البث>                              إذاعة (تتشغل)
 *
 * كل الدوال اللي بتحمّل من النت بتتنادي من خيط في الخلفية بس.
 */
final class Catalog {

    static final String ROOT = "root";
    static final String RECENT = "recent";
    static final String FAMOUS = "famous";
    static final String ALL = "all";
    static final String QURAN_RADIO = "qradio";
    static final String EG_RADIO = "egradio";

    static final String[] SURAHS = {
            "الفاتحة", "البقرة", "آل عمران", "النساء", "المائدة", "الأنعام", "الأعراف", "الأنفال",
            "التوبة", "يونس", "هود", "يوسف", "الرعد", "إبراهيم", "الحجر", "النحل", "الإسراء",
            "الكهف", "مريم", "طه", "الأنبياء", "الحج", "المؤمنون", "النور", "الفرقان", "الشعراء",
            "النمل", "القصص", "العنكبوت", "الروم", "لقمان", "السجدة", "الأحزاب", "سبأ", "فاطر",
            "يس", "الصافات", "ص", "الزمر", "غافر", "فصلت", "الشورى", "الزخرف", "الدخان",
            "الجاثية", "الأحقاف", "محمد", "الفتح", "الحجرات", "ق", "الذاريات", "الطور", "النجم",
            "القمر", "الرحمن", "الواقعة", "الحديد", "المجادلة", "الحشر", "الممتحنة", "الصف",
            "الجمعة", "المنافقون", "التغابن", "الطلاق", "التحريم", "الملك", "القلم", "الحاقة",
            "المعارج", "نوح", "الجن", "المزمل", "المدثر", "القيامة", "الإنسان", "المرسلات",
            "النبأ", "النازعات", "عبس", "التكوير", "الانفطار", "المطففين", "الانشقاق", "البروج",
            "الطارق", "الأعلى", "الغاشية", "الفجر", "البلد", "الشمس", "الليل", "الضحى", "الشرح",
            "التين", "العلق", "القدر", "البينة", "الزلزلة", "العاديات", "القارعة", "التكاثر",
            "العصر", "الهمزة", "الفيل", "قريش", "الماعون", "الكوثر", "الكافرون", "النصر",
            "المسد", "الإخلاص", "الفلق", "الناس"
    };

    /** القرّاء اللي بيظهروا في "المشهورين" بالترتيب ده. */
    private static final String[] FAMOUS_NAMES = {
            "عبدالباسط عبدالصمد", "محمد صديق المنشاوي", "محمود خليل الحصري", "مصطفى إسماعيل",
            "محمد محمود الطبلاوي", "مشاري العفاسي", "ماهر المعيقلي", "عبدالرحمن السديس",
            "سعود الشريم", "ياسر الدوسري", "سعد الغامدي", "أحمد بن علي العجمي", "ناصر القطامي",
            "إسلام صبحي", "محمد أيوب", "فارس عباد", "إدريس أبكر", "محمد جبريل",
            "عبدالله الجهني", "أبو بكر الشاطري", "خالد الجليل", "بندر بليلة"
    };

    /** لو النت مش شغال أول مرة ومفيش نسخة محفوظة. */
    private static final String[][] BUILTIN_RECITERS = {
            {"عبدالباسط عبدالصمد", "https://server7.mp3quran.net/basit/"},
            {"محمد صديق المنشاوي", "https://server10.mp3quran.net/minsh/"},
            {"محمود خليل الحصري", "https://server13.mp3quran.net/husr/"},
            {"مشاري العفاسي", "https://server8.mp3quran.net/afs/"},
            {"ماهر المعيقلي", "https://server12.mp3quran.net/maher/"},
            {"عبدالرحمن السديس", "https://server11.mp3quran.net/sds/"},
            {"سعود الشريم", "https://server7.mp3quran.net/shur/"},
            {"ياسر الدوسري", "https://server11.mp3quran.net/yasser/"},
            {"سعد الغامدي", "https://server7.mp3quran.net/s_gmd/"},
            {"أحمد بن علي العجمي", "https://server10.mp3quran.net/ajm/"},
            {"ناصر القطامي", "https://server6.mp3quran.net/qtm/"},
    };

    /** إذاعات القرآن الكريم الثابتة (أول حاجة في اللستة). */
    private static final String[][] BUILTIN_QURAN_RADIOS = {
            {"إذاعة القرآن الكريم من القاهرة", "https://stream.radiojar.com/8s5u5tpdtwzuv"},
    };

    private static final String RECITERS_API = "https://mp3quran.net/api/v3/reciters?language=ar";
    private static final String RADIOS_API = "https://mp3quran.net/api/v3/radios?language=ar";
    private static final String[] RADIO_BROWSER_HOSTS = {
            "https://de1.api.radio-browser.info", "https://nl1.api.radio-browser.info",
            "https://at1.api.radio-browser.info"
    };
    private static final String EG_PATH =
            "/json/stations/search?countrycode=EG&hidebroken=true&order=clickcount&reverse=true&limit=100";

    private static final long CACHE_MS = 3L * 24 * 60 * 60 * 1000; // 3 أيام
    static final String UA = "CarQuranRadio/1.0 (Android)";

    static final class Moshaf {
        final String name, server;
        final int[] surahs;
        Reciter reciter;

        Moshaf(String name, String server, int[] surahs) {
            this.name = name;
            this.server = server;
            this.surahs = surahs;
        }
    }

    static final class Reciter {
        final String id, name;
        final List<Moshaf> moshafs = new ArrayList<>();

        Reciter(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    static final class Station {
        final String name, url, art;

        Station(String name, String url, String art) {
            this.name = name;
            this.url = url;
            this.art = art;
        }
    }

    private final Context ctx;
    private final SharedPreferences prefs;

    private List<Reciter> reciters;
    private List<Reciter> famous;
    private final Map<String, Reciter> reciterById = new HashMap<>();
    private final Map<String, Moshaf> moshafByServer = new HashMap<>();
    private List<Station> quranRadios;
    private List<Station> egRadios;
    private final Map<String, Station> stationByUrl = new HashMap<>();

    Catalog(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.prefs = ctx.getSharedPreferences("player", Context.MODE_PRIVATE);
    }

    // ───────────────────────── تصفّح ─────────────────────────

    /** العناصر اللي جوه مجلد، أو null لو المعرّف مش معروف. */
    synchronized List<MediaItem> children(String parentId) {
        List<MediaItem> out = new ArrayList<>();
        switch (parentId) {
            case ROOT:
                out.add(folder(FAMOUS, "القرّاء المشهورين", "عبد الباسط، المنشاوي، الحصري…",
                        MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS));
                out.add(folder(QURAN_RADIO, "إذاعات القرآن", "بث مباشر",
                        MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS));
                out.add(folder(EG_RADIO, "إذاعات مصرية", "نجوم، نايل، ميجا…",
                        MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS));
                out.add(folder(ALL, "كل القرّاء", "من أ إلى ي",
                        MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS));
                return out;
            case RECENT: {
                MediaItem last = lastPlayed();
                if (last != null) out.add(last);
                return out;
            }
            case FAMOUS:
                loadReciters();
                for (Reciter r : famous) out.add(reciterItem(r));
                return out;
            case ALL:
                loadReciters();
                for (Reciter r : reciters) out.add(reciterItem(r));
                return out;
            case QURAN_RADIO:
                loadRadios();
                for (Station s : quranRadios) out.add(stationItem(s));
                return out;
            case EG_RADIO:
                loadRadios();
                for (Station s : egRadios) out.add(stationItem(s));
                return out;
        }
        if (parentId.startsWith("rc|")) {
            loadReciters();
            Reciter r = reciterById.get(parentId.substring(3));
            if (r == null) return null;
            if (r.moshafs.size() == 1) return surahItems(r.moshafs.get(0));
            for (Moshaf m : r.moshafs) out.add(moshafItem(m));
            return out;
        }
        if (parentId.startsWith("ms|")) {
            loadReciters();
            Moshaf m = moshafByServer.get(parentId.substring(3));
            return m == null ? null : surahItems(m);
        }
        return null;
    }

    /** عنصر واحد كامل (برابط التشغيل لو بيتشغل). */
    synchronized MediaItem item(String id) {
        if (id == null) return null;
        if (id.equals(ROOT)) return folder(ROOT, "قرآن وراديو", null, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED);
        if (id.startsWith("s|")) {
            String[] p = id.split("\\|", 3);
            if (p.length < 3) return null;
            int n;
            try {
                n = Integer.parseInt(p[1]);
            } catch (NumberFormatException e) {
                return null;
            }
            if (n < 1 || n > 114) return null;
            loadReciters();
            return surahItem(n, p[2], moshafByServer.get(p[2]));
        }
        if (id.startsWith("st|")) {
            loadRadios();
            String url = id.substring(3);
            Station s = stationByUrl.get(url);
            return stationItem(s != null ? s : new Station("إذاعة", url, null));
        }
        if (id.startsWith("rc|") || id.startsWith("ms|")) {
            loadReciters();
            if (id.startsWith("rc|")) {
                Reciter r = reciterById.get(id.substring(3));
                return r == null ? null : reciterItem(r);
            }
            Moshaf m = moshafByServer.get(id.substring(3));
            return m == null ? null : moshafItem(m);
        }
        for (MediaItem f : children(ROOT)) if (f.mediaId.equals(id)) return f;
        return null;
    }

    /** لو العنصر سورة: كل سور نفس القارئ عشان يكمّل لوحده للسورة اللي بعدها. */
    synchronized Playlist playlistFor(String id) {
        MediaItem one = item(id);
        if (one == null || one.localConfiguration == null) return null;
        if (!id.startsWith("s|")) return new Playlist(Collections.singletonList(one), 0);
        String[] p = id.split("\\|", 3);
        int n = Integer.parseInt(p[1]);
        Moshaf m = moshafByServer.get(p[2]);
        int[] list = m != null ? m.surahs : allSurahs();
        List<MediaItem> items = new ArrayList<>();
        int start = 0;
        for (int s : list) {
            if (s == n) start = items.size();
            items.add(surahItem(s, p[2], m));
        }
        if (items.isEmpty()) return new Playlist(Collections.singletonList(one), 0);
        return new Playlist(items, start);
    }

    static final class Playlist {
        final List<MediaItem> items;
        final int start;

        Playlist(List<MediaItem> items, int start) {
            this.items = items;
            this.start = start;
        }
    }

    // ───────────────────────── بحث بالصوت ─────────────────────────

    /** "شغّل سورة الكهف" / "شغّل الحصري" / "شغّل نجوم إف إم". */
    synchronized String search(String query) {
        String q = norm(query).replace("سوره ", " ").replace("اذاعه ", " ").trim();
        if (q.isEmpty()) return lastId();

        // سورة؟ بصوت آخر قارئ اتسمع (أو أول قارئ مشهور)
        int surah = 0;
        Set<String> tokens = new HashSet<>();
        for (String t : q.split("\\s+")) tokens.add(t);
        for (int i = 0; i < SURAHS.length; i++) {
            String n = norm(SURAHS[i]);
            boolean hit = n.contains(" ") ? q.contains(n)
                    : tokens.contains(n) || tokens.contains("ال" + n)
                    || (n.startsWith("ال") && tokens.contains(n.substring(2)));
            if (hit && (surah == 0 || n.length() > norm(SURAHS[surah - 1]).length())) surah = i + 1;
        }

        loadReciters();
        Reciter reciter = null;
        for (Reciter r : reciters) {
            String rn = norm(r.name);
            for (String part : rn.split("\\s+")) {
                if (part.length() >= 4 && q.contains(part)) {
                    reciter = r;
                    break;
                }
            }
            if (reciter != null) break;
        }

        if (surah > 0 || reciter != null) {
            String server;
            if (reciter != null) server = reciter.moshafs.get(0).server;
            else server = lastServer();
            return "s|" + Math.max(surah, 1) + "|" + server;
        }

        loadRadios();
        for (List<Station> list : java.util.Arrays.asList(quranRadios, egRadios)) {
            for (Station s : list) {
                String sn = norm(s.name);
                if (sn.contains(q) || q.contains(sn)) return "st|" + s.url;
            }
        }
        for (List<Station> list : java.util.Arrays.asList(quranRadios, egRadios)) {
            for (Station s : list) {
                String sn = norm(s.name);
                for (String t : tokens) if (t.length() >= 3 && sn.contains(t)) return "st|" + s.url;
            }
        }
        return null;
    }

    static String norm(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (char c : s.toLowerCase(Locale.ROOT).toCharArray()) {
            if (c >= 'ً' && c <= 'ٟ' || c == 'ـ' || c == 'ٰ') continue; // تشكيل
            if (c == 'أ' || c == 'إ' || c == 'آ' || c == 'ٱ') c = 'ا';
            else if (c == 'ة') c = 'ه';
            else if (c == 'ى') c = 'ي';
            else if (!Character.isLetterOrDigit(c)) c = ' ';
            b.append(c);
        }
        return b.toString().replace("عبد ال", "عبدال").replaceAll("\\s+", " ").trim();
    }

    // ───────────────────────── آخر حاجة اتسمعت ─────────────────────────

    void saveLast(String id, long positionMs) {
        if (id == null || id.isEmpty()) return;
        SharedPreferences.Editor e = prefs.edit().putString("last_id", id)
                .putLong("last_pos", id.startsWith("s|") ? Math.max(0, positionMs) : 0);
        if (id.startsWith("s|")) e.putString("last_server", id.split("\\|", 3)[2]);
        e.apply();
    }

    String lastId() {
        return prefs.getString("last_id", null);
    }

    long lastPosition() {
        return prefs.getLong("last_pos", 0);
    }

    private String lastServer() {
        String s = prefs.getString("last_server", null);
        if (s != null) return s;
        if (!famous.isEmpty()) return famous.get(0).moshafs.get(0).server;
        return BUILTIN_RECITERS[0][1];
    }

    private MediaItem lastPlayed() {
        String id = lastId();
        return id == null ? null : item(id);
    }

    // ───────────────────────── بناء العناصر ─────────────────────────

    static MediaItem folder(String id, String title, String subtitle, int type) {
        return new MediaItem.Builder()
                .setMediaId(id)
                .setMediaMetadata(new MediaMetadata.Builder()
                        .setTitle(title)
                        .setSubtitle(subtitle)
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setMediaType(type)
                        .build())
                .build();
    }

    private static MediaItem reciterItem(Reciter r) {
        String sub = r.moshafs.size() == 1 ? r.moshafs.get(0).name : r.moshafs.size() + " روايات";
        return folder("rc|" + r.id, r.name, sub, MediaMetadata.MEDIA_TYPE_ARTIST);
    }

    private static MediaItem moshafItem(Moshaf m) {
        String who = m.reciter != null ? m.reciter.name : null;
        return folder("ms|" + m.server, m.name, who, MediaMetadata.MEDIA_TYPE_ALBUM);
    }

    private List<MediaItem> surahItems(Moshaf m) {
        List<MediaItem> out = new ArrayList<>();
        for (int s : m.surahs) out.add(surahItem(s, m.server, m));
        return out;
    }

    private MediaItem surahItem(int n, String server, Moshaf m) {
        String reciter = m != null && m.reciter != null ? m.reciter.name : builtinName(server);
        String url = server + String.format(Locale.ROOT, "%03d.mp3", n);
        return new MediaItem.Builder()
                .setMediaId("s|" + n + "|" + server)
                .setUri(url)
                .setMediaMetadata(new MediaMetadata.Builder()
                        .setTitle(n + ". سورة " + SURAHS[n - 1])
                        .setDisplayTitle("سورة " + SURAHS[n - 1])
                        .setArtist(reciter)
                        .setSubtitle(reciter)
                        .setAlbumTitle(m != null ? m.name : null)
                        .setTrackNumber(n)
                        .setTotalTrackCount(114)
                        .setIsBrowsable(false)
                        .setIsPlayable(true)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                        .build())
                .build();
    }

    private static MediaItem stationItem(Station s) {
        MediaMetadata.Builder md = new MediaMetadata.Builder()
                .setTitle(s.name)
                .setDisplayTitle(s.name)
                .setArtist("بث مباشر")
                .setSubtitle("بث مباشر")
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION);
        if (s.art != null && s.art.startsWith("http")) md.setArtworkUri(Uri.parse(s.art));
        return new MediaItem.Builder()
                .setMediaId("st|" + s.url)
                .setUri(s.url)
                .setLiveConfiguration(new MediaItem.LiveConfiguration.Builder().build())
                .setMediaMetadata(md.build())
                .build();
    }

    private static int[] allSurahs() {
        int[] a = new int[114];
        for (int i = 0; i < 114; i++) a[i] = i + 1;
        return a;
    }

    private static String builtinName(String server) {
        for (String[] r : BUILTIN_RECITERS) if (r[1].equals(server)) return r[0];
        return "";
    }

    // ───────────────────────── تحميل القرّاء ─────────────────────────

    private void loadReciters() {
        if (reciters != null) return;
        List<Reciter> list = null;
        String json = cachedOrFetch("reciters.json", new String[]{RECITERS_API});
        if (json != null) {
            try {
                list = parseReciters(json);
            } catch (Exception ignored) {
            }
        }
        if (list == null || list.isEmpty()) {
            list = new ArrayList<>();
            int i = 0;
            for (String[] b : BUILTIN_RECITERS) {
                Reciter r = new Reciter("b" + (i++), b[0]);
                Moshaf m = new Moshaf("حفص عن عاصم - مرتل", b[1], allSurahs());
                m.reciter = r;
                r.moshafs.add(m);
                list.add(r);
            }
        }
        final Collator collator = Collator.getInstance(new Locale("ar"));
        Collections.sort(list, (a, b) -> collator.compare(a.name, b.name));

        reciterById.clear();
        moshafByServer.clear();
        for (Reciter r : list) {
            reciterById.put(r.id, r);
            for (Moshaf m : r.moshafs) moshafByServer.put(m.server, m);
        }

        LinkedHashMap<String, Reciter> fam = new LinkedHashMap<>();
        for (String want : FAMOUS_NAMES) {
            // كل كلمات الاسم لازم تكون موجودة ("مشاري العفاسي" = "مشاري راشد العفاسي")
            String[] words = norm(want).split(" ");
            for (Reciter r : list) {
                String rn = " " + norm(r.name) + " ";
                boolean all = true;
                for (String w : words) if (!rn.contains(" " + w + " ")) all = false;
                if (all) fam.put(r.id, r);
            }
        }
        famous = new ArrayList<>(fam.values());
        if (famous.isEmpty()) famous = list;
        reciters = list;
    }

    private static List<Reciter> parseReciters(String json) throws Exception {
        JSONArray arr = new JSONObject(json).getJSONArray("reciters");
        List<Reciter> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            Reciter r = new Reciter(o.optString("id", "x" + i), o.optString("name").trim());
            JSONArray ms = o.optJSONArray("moshaf");
            if (ms == null || r.name.isEmpty()) continue;
            for (int j = 0; j < ms.length(); j++) {
                JSONObject m = ms.getJSONObject(j);
                String server = m.optString("server");
                if (!server.startsWith("http")) continue;
                if (!server.endsWith("/")) server += "/";
                int[] surahs = parseList(m.optString("surah_list"));
                if (surahs.length == 0) continue;
                Moshaf mm = new Moshaf(m.optString("name", "مصحف").trim(), server, surahs);
                mm.reciter = r;
                r.moshafs.add(mm);
            }
            if (r.moshafs.isEmpty()) continue;
            // حفص مرتل الأول، وبعدين الأكمل
            Collections.sort(r.moshafs, (a, b) -> Integer.compare(score(b), score(a)));
            out.add(r);
        }
        return out;
    }

    private static int score(Moshaf m) {
        int s = m.surahs.length;
        if (m.name.contains("حفص")) s += 1000;
        if (m.name.contains("مرتل")) s += 500;
        return s;
    }

    private static int[] parseList(String s) {
        List<Integer> l = new ArrayList<>();
        for (String p : s.split(",")) {
            try {
                int n = Integer.parseInt(p.trim());
                if (n >= 1 && n <= 114) l.add(n);
            } catch (NumberFormatException ignored) {
            }
        }
        Collections.sort(l);
        int[] a = new int[l.size()];
        for (int i = 0; i < a.length; i++) a[i] = l.get(i);
        return a;
    }

    // ───────────────────────── تحميل الإذاعات ─────────────────────────

    private void loadRadios() {
        if (quranRadios != null) return;
        List<Station> q = new ArrayList<>();
        for (String[] b : BUILTIN_QURAN_RADIOS) q.add(new Station(b[0], b[1], null));
        String json = cachedOrFetch("radios.json", new String[]{RADIOS_API});
        if (json != null) {
            try {
                JSONArray arr = new JSONObject(json).getJSONArray("radios");
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    String url = o.optString("url");
                    if (url.startsWith("http")) q.add(new Station(o.optString("name").trim(), url, null));
                }
            } catch (Exception ignored) {
            }
        }

        List<Station> eg = new ArrayList<>();
        String[] urls = new String[RADIO_BROWSER_HOSTS.length];
        for (int i = 0; i < urls.length; i++) urls[i] = RADIO_BROWSER_HOSTS[i] + EG_PATH;
        json = cachedOrFetch("egypt.json", urls);
        if (json != null) {
            try {
                JSONArray arr = new JSONArray(json);
                Set<String> seen = new HashSet<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    String name = o.optString("name").trim();
                    String url = o.optString("url_resolved");
                    if (url.isEmpty()) url = o.optString("url");
                    if (name.isEmpty() || !url.startsWith("http") || !seen.add(norm(name))) continue;
                    eg.add(new Station(name, url, o.optString("favicon", null)));
                }
            } catch (Exception ignored) {
            }
        }
        if (eg.isEmpty()) eg.add(q.get(0));

        stationByUrl.clear();
        for (Station s : q) stationByUrl.put(s.url, s);
        for (Station s : eg) stationByUrl.put(s.url, s);
        quranRadios = q;
        egRadios = eg;
    }

    // ───────────────────────── شبكة + كاش ─────────────────────────

    /** نسخة محفوظة لو جديدة، وإلا يحمّل، ولو النت فاصل يرجع للنسخة القديمة. */
    private String cachedOrFetch(String file, String[] urls) {
        File f = new File(ctx.getFilesDir(), file);
        boolean fresh = f.exists() && System.currentTimeMillis() - f.lastModified() < CACHE_MS;
        if (fresh) {
            String s = readFile(f);
            if (s != null) return s;
        }
        for (String url : urls) {
            String s = httpGet(url);
            if (s != null) {
                writeFile(f, s);
                return s;
            }
        }
        return f.exists() ? readFile(f) : null;
    }

    private static String httpGet(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "application/json");
            if (c.getResponseCode() != 200) return null;
            String s = readAll(c.getInputStream());
            return s.trim().isEmpty() ? null : s;
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String readFile(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            return readAll(in);
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeFile(File f, String s) {
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
