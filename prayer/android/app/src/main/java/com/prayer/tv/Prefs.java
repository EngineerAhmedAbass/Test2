package com.prayer.tv;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Calendar;
import java.util.TimeZone;

/** الإعدادات + حساب مواعيد أي يوم بالإعدادات دي */
final class Prefs {
    /** مستويات صوت الأذان (0 = زي صوت الجهاز) */
    static final int[] VOLUMES = {0, 30, 50, 70, 100};
    static final String[] VOLUME_NAMES = {"زي صوت التليفزيون", "30٪", "50٪", "70٪", "100٪"};

    /** أصوات الأذان اللي جوه التطبيق (res/raw) */
    static final String[] VOICE_NAMES = {"الشيخ عمر هشام العربي", "الشيخ محمد مروان قصاص (المدينة)",
            "الشيخ إسلام صبحي (أذان الفجر)"};
    static final String[] VOICE_FILES = {"adhan", "adhan_qassas", "adhan_fajr_sobhi"};
    /** مؤذن الفجر الافتراضي: إسلام صبحي (أذان فيه "الصلاة خير من النوم") */
    private static final int DEFAULT_FAJR_VOICE = 2;
    /** أول كام صوت ينفعوا لباقي الصلوات (أذان الفجر فيه "الصلاة خير من النوم") */
    static final int MAIN_VOICES = 2;

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("prayer", Context.MODE_PRIVATE);
    }

    static int cityIndex(Context c) {
        int i = sp(c).getInt("city", 0);
        return i >= 0 && i < Cities.ALL.length ? i : 0;
    }

    static Cities.City city(Context c) {
        return Cities.ALL[cityIndex(c)];
    }

    static void setCity(Context c, int i) {
        // طريقة الحساب ترجع للافتراضي بتاع البلد الجديدة
        sp(c).edit().putInt("city", i).remove("method").commit();
    }

    static int method(Context c) {
        int m = sp(c).getInt("method", -1);
        return m >= 0 && m < PrayTimes.METHODS.length ? m : city(c).method;
    }

    static void setMethod(Context c, int m) { sp(c).edit().putInt("method", m).commit(); }

    static boolean hanafi(Context c) { return sp(c).getBoolean("hanafi", false); }

    static void setHanafi(Context c, boolean v) { sp(c).edit().putBoolean("hanafi", v).commit(); }

    static boolean enabled(Context c, int p) {
        return p != PrayTimes.SUNRISE && sp(c).getBoolean("on" + p, true);
    }

    static void setEnabled(Context c, int p, boolean v) { sp(c).edit().putBoolean("on" + p, v).commit(); }

    /** تعديل يدوي بالدقايق لكل صلاة عشان تظبطها على مسجد منطقتك */
    static int offset(Context c, int p) { return sp(c).getInt("off" + p, 0); }

    static void setOffset(Context c, int p, int v) { sp(c).edit().putInt("off" + p, v).commit(); }

    static int volume(Context c) { return sp(c).getInt("volume", 0); }

    static void setVolume(Context c, int v) { sp(c).edit().putInt("volume", v).commit(); }

    static int voice(Context c) {
        int v = sp(c).getInt("voice", 0);
        return v >= 0 && v < MAIN_VOICES ? v : 0;
    }

    static void setVoice(Context c, int v) { sp(c).edit().putInt("voice", v).commit(); }

    /** -1 = زي باقي الصلوات */
    static int fajrVoice(Context c) {
        int v = sp(c).getInt("fajrVoice", DEFAULT_FAJR_VOICE);
        return v >= -1 && v < VOICE_FILES.length ? v : -1;
    }

    static void setFajrVoice(Context c, int v) { sp(c).edit().putInt("fajrVoice", v).commit(); }

    /** اسم ملف الأذان للصلاة دي */
    static String voiceFile(Context c, int prayer) {
        int v = prayer == PrayTimes.FAJR && fajrVoice(c) >= 0 ? fajrVoice(c) : voice(c);
        return VOICE_FILES[v];
    }

    static boolean showScreen(Context c) { return sp(c).getBoolean("screen", true); }

    static void setShowScreen(Context c, boolean v) { sp(c).edit().putBoolean("screen", v).commit(); }

    static int hijriAdjust(Context c) { return sp(c).getInt("hijri", 0); }

    static void setHijriAdjust(Context c, int v) { sp(c).edit().putInt("hijri", v).commit(); }

    static TimeZone zone(Context c) { return TimeZone.getTimeZone(city(c).tz); }

    /** مواعيد اليوم (dayOffset = 0 النهارده، 1 بكرة...) بتوقيت المدينة */
    static long[] times(Context c, int dayOffset) {
        Cities.City city = city(c);
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone(city.tz));
        cal.add(Calendar.DAY_OF_MONTH, dayOffset);
        long[] t = PrayTimes.compute(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1,
                cal.get(Calendar.DAY_OF_MONTH), city.lat, city.lng, method(c), hanafi(c));
        for (int i = 0; i < t.length; i++) t[i] += offset(c, i) * 60000L;
        return t;
    }

    /** {رقم الصلاة، الميعاد} لأول صلاة (من غير الشروق) بعد after */
    static long[] next(Context c, long after, boolean enabledOnly) {
        for (int day = -1; day <= 2; day++) {
            long[] t = times(c, day);
            for (int p = 0; p < 6; p++) {
                if (p == PrayTimes.SUNRISE) continue;
                if (enabledOnly && !enabled(c, p)) continue;
                if (t[p] > after) return new long[]{p, t[p]};
            }
        }
        return null;
    }

    private Prefs() {}
}
