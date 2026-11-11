package com.prayer.tv;

/**
 * حساب مواقيت الصلاة فلكيًا (بدون إنترنت).
 * مبني على الخوارزمية المعروفة بتاعة PrayTimes.org.
 */
public final class PrayTimes {
    public static final int FAJR = 0, SUNRISE = 1, DHUHR = 2, ASR = 3, MAGHRIB = 4, ISHA = 5;
    public static final String[] NAMES = {"الفجر", "الشروق", "الظهر", "العصر", "المغرب", "العشاء"};

    /** اسم الطريقة، زاوية الفجر، زاوية العشاء (أو دقائق بعد المغرب لو isMinutes = 1) */
    public static final String[] METHOD_NAMES = {
            "الهيئة المصرية العامة للمساحة",
            "رابطة العالم الإسلامي",
            "أم القرى (مكة)",
            "جامعة العلوم الإسلامية بكراتشي",
            "أمريكا الشمالية (ISNA)",
            "الإمارات (دبي)",
            "الكويت",
            "قطر",
            "الجزائر",
            "المغرب",
            "تونس",
    };
    static final double[][] METHODS = {
            {19.5, 17.5, 0},
            {18, 17, 0},
            {18.5, 90, 1},
            {18, 18, 0},
            {15, 15, 0},
            {18.2, 18.2, 0},
            {18, 17.5, 0},
            {18, 90, 1},
            {18, 17, 0},
            {19, 17, 0},
            {18, 18, 0},
    };

    private final double lat, lng, jDate;

    private PrayTimes(double lat, double lng, double jDate) {
        this.lat = lat;
        this.lng = lng;
        this.jDate = jDate;
    }

    /**
     * بيرجع 6 مواعيد بالـ millis (من 1970 UTC) لليوم ده في المدينة دي.
     * y/m/d هو التاريخ المحلي في المدينة (m من 1 لـ 12).
     */
    public static long[] compute(int y, int m, int d, double lat, double lng, int method, boolean hanafi) {
        if (method < 0 || method >= METHODS.length) method = 0;
        double[] md = METHODS[method];
        PrayTimes p = new PrayTimes(lat, lng, julian(y, m, d) - lng / (15 * 24.0));

        // fajr, sunrise, dhuhr, asr, sunset, isha
        double[] t = {5, 6, 12, 13, 18, 18};
        for (int iter = 0; iter < 2; iter++) {
            double[] dp = new double[6];
            for (int i = 0; i < 6; i++) dp[i] = t[i] / 24.0;
            t[0] = p.sunAngleTime(md[0], dp[0], true);
            t[1] = p.sunAngleTime(0.833, dp[1], true);
            t[2] = p.midDay(dp[2]);
            t[3] = p.asrTime(hanafi ? 2 : 1, dp[3]);
            t[4] = p.sunAngleTime(0.833, dp[4], false);
            t[5] = md[2] == 1 ? t[4] : p.sunAngleTime(md[1], dp[5], false);
        }

        // من الوقت الشمسي المحلي لـ UTC
        for (int i = 0; i < 6; i++) t[i] -= lng / 15.0;
        if (md[2] == 1) t[5] = t[4] + md[1] / 60.0;

        // خطوط العرض العالية: لو الفجر أو العشاء مش موجودين نستخدم جزء من الليل
        double night = t[1] + 24 - t[4];
        double fajrMax = t[1] - md[0] / 60.0 * night;
        if (Double.isNaN(t[0]) || t[0] < fajrMax) t[0] = fajrMax;
        if (md[2] != 1) {
            double ishaMax = t[4] + md[1] / 60.0 * night;
            if (Double.isNaN(t[5]) || t[5] > ishaMax) t[5] = ishaMax;
        }

        long utcMidnight = utcMidnight(y, m, d);
        // الشروق=1، المغرب = الغروب
        double[] out = {t[0], t[1], t[2], t[3], t[4], t[5]};
        long[] res = new long[6];
        for (int i = 0; i < 6; i++) {
            long ms = utcMidnight + Math.round(out[i] * 3600000.0);
            res[i] = Math.round(ms / 60000.0) * 60000L; // أقرب دقيقة
        }
        return res;
    }

    static long utcMidnight(int y, int m, int d) {
        // (julian - 2440587.5) أيام من 1970
        return Math.round((julian(y, m, d) - 2440587.5) * 86400000.0);
    }

    static double julian(int y, int m, int d) {
        if (m <= 2) {
            y -= 1;
            m += 12;
        }
        double a = Math.floor(y / 100.0);
        double b = 2 - a + Math.floor(a / 4);
        return Math.floor(365.25 * (y + 4716)) + Math.floor(30.6001 * (m + 1)) + d + b - 1524.5;
    }

    /** [الميل، معادلة الوقت] */
    private double[] sunPosition(double jd) {
        double D = jd - 2451545.0;
        double g = fixAngle(357.529 + 0.98560028 * D);
        double q = fixAngle(280.459 + 0.98564736 * D);
        double L = fixAngle(q + 1.915 * dsin(g) + 0.020 * dsin(2 * g));
        double e = 23.439 - 0.00000036 * D;
        double RA = fixHour(darctan2(dcos(e) * dsin(L), dcos(L)) / 15.0);
        double eqt = q / 15.0 - RA;
        double decl = darcsin(dsin(e) * dsin(L));
        return new double[]{decl, eqt};
    }

    private double midDay(double t) {
        double eqt = sunPosition(jDate + t)[1];
        return fixHour(12 - eqt);
    }

    private double sunAngleTime(double angle, double t, boolean ccw) {
        double decl = sunPosition(jDate + t)[0];
        double noon = midDay(t);
        double tt = darccos((-dsin(angle) - dsin(decl) * dsin(lat)) / (dcos(decl) * dcos(lat))) / 15.0;
        return noon + (ccw ? -tt : tt);
    }

    private double asrTime(double factor, double t) {
        double decl = sunPosition(jDate + t)[0];
        double angle = -darccot(factor + dtan(Math.abs(lat - decl)));
        return sunAngleTime(angle, t, false);
    }

    /* ---------- التاريخ الهجري (الحساب الكويتي التقريبي) ---------- */

    public static final String[] HIJRI_MONTHS = {
            "محرم", "صفر", "ربيع الأول", "ربيع الآخر", "جمادى الأولى", "جمادى الآخرة",
            "رجب", "شعبان", "رمضان", "شوال", "ذو القعدة", "ذو الحجة"};

    /** بيرجع {سنة، شهر (1-12)، يوم} */
    public static int[] hijri(int y, int m, int d, int adjustDays) {
        long jd = (long) Math.floor(julian(y, m, d) + 0.5) + adjustDays;
        long l = jd - 1948440 + 10632;
        long n = (l - 1) / 10631;
        l = l - 10631 * n + 354;
        long j = ((10985 - l) / 5316) * ((50 * l) / 17719) + (l / 5670) * ((43 * l) / 15238);
        l = l - ((30 - j) / 15) * ((17719 * j) / 50) - (j / 16) * ((15238 * j) / 43) + 29;
        long month = (24 * l) / 709;
        long day = l - (709 * month) / 24;
        long year = 30 * n + j - 30;
        return new int[]{(int) year, (int) month, (int) day};
    }

    /* ---------- رياضيات بالدرجات ---------- */
    private static double dsin(double d) { return Math.sin(Math.toRadians(d)); }
    private static double dcos(double d) { return Math.cos(Math.toRadians(d)); }
    private static double dtan(double d) { return Math.tan(Math.toRadians(d)); }
    private static double darcsin(double x) { return Math.toDegrees(Math.asin(x)); }
    private static double darccos(double x) { return Math.toDegrees(Math.acos(x)); }
    private static double darccot(double x) { return Math.toDegrees(Math.atan(1 / x)); }
    private static double darctan2(double y, double x) { return Math.toDegrees(Math.atan2(y, x)); }
    private static double fixAngle(double a) { a = a - 360 * Math.floor(a / 360); return a < 0 ? a + 360 : a; }
    private static double fixHour(double a) { a = a - 24 * Math.floor(a / 24); return a < 0 ? a + 24 : a; }
}
