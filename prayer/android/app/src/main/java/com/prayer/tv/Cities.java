package com.prayer.tv;

/** المدن: الاسم، خط العرض، خط الطول، المنطقة الزمنية، طريقة الحساب الافتراضية */
final class Cities {
    static final class City {
        final String name, tz;
        final double lat, lng;
        final int method;

        City(String name, double lat, double lng, String tz, int method) {
            this.name = name;
            this.lat = lat;
            this.lng = lng;
            this.tz = tz;
            this.method = method;
        }
    }

    private static final String EG = "Africa/Cairo";

    static final City[] ALL = {
            // مصر — الهيئة المصرية
            new City("مصر — القاهرة", 30.0444, 31.2357, EG, 0),
            new City("مصر — الجيزة", 30.0131, 31.2089, EG, 0),
            new City("مصر — الإسكندرية", 31.2001, 29.9187, EG, 0),
            new City("مصر — بورسعيد", 31.2653, 32.3019, EG, 0),
            new City("مصر — السويس", 29.9668, 32.5498, EG, 0),
            new City("مصر — الإسماعيلية", 30.5965, 32.2715, EG, 0),
            new City("مصر — المنصورة", 31.0409, 31.3785, EG, 0),
            new City("مصر — طنطا", 30.7865, 31.0004, EG, 0),
            new City("مصر — الزقازيق", 30.5877, 31.5020, EG, 0),
            new City("مصر — دمنهور", 31.0341, 30.4682, EG, 0),
            new City("مصر — كفر الشيخ", 31.1107, 30.9388, EG, 0),
            new City("مصر — دمياط", 31.4175, 31.8144, EG, 0),
            new City("مصر — شبين الكوم", 30.5586, 31.0110, EG, 0),
            new City("مصر — بنها", 30.4660, 31.1848, EG, 0),
            new City("مصر — الفيوم", 29.3084, 30.8428, EG, 0),
            new City("مصر — بني سويف", 29.0661, 31.0994, EG, 0),
            new City("مصر — المنيا", 28.1099, 30.7503, EG, 0),
            new City("مصر — أسيوط", 27.1783, 31.1859, EG, 0),
            new City("مصر — سوهاج", 26.5591, 31.6957, EG, 0),
            new City("مصر — قنا", 26.1551, 32.7160, EG, 0),
            new City("مصر — الأقصر", 25.6872, 32.6396, EG, 0),
            new City("مصر — أسوان", 24.0889, 32.8998, EG, 0),
            new City("مصر — الغردقة", 27.2579, 33.8116, EG, 0),
            new City("مصر — شرم الشيخ", 27.9158, 34.3300, EG, 0),
            new City("مصر — مرسى مطروح", 31.3543, 27.2373, EG, 0),
            new City("مصر — العريش", 31.1316, 33.7984, EG, 0),
            new City("مصر — الخارجة", 25.4390, 30.5586, EG, 0),
            // السعودية — أم القرى
            new City("السعودية — مكة المكرمة", 21.4225, 39.8262, "Asia/Riyadh", 2),
            new City("السعودية — المدينة المنورة", 24.4686, 39.6142, "Asia/Riyadh", 2),
            new City("السعودية — الرياض", 24.7136, 46.6753, "Asia/Riyadh", 2),
            new City("السعودية — جدة", 21.4858, 39.1925, "Asia/Riyadh", 2),
            new City("السعودية — الدمام", 26.4207, 50.0888, "Asia/Riyadh", 2),
            // الخليج
            new City("الإمارات — دبي", 25.2048, 55.2708, "Asia/Dubai", 5),
            new City("الإمارات — أبوظبي", 24.4539, 54.3773, "Asia/Dubai", 5),
            new City("الكويت — الكويت", 29.3759, 47.9774, "Asia/Kuwait", 6),
            new City("قطر — الدوحة", 25.2854, 51.5310, "Asia/Qatar", 7),
            new City("البحرين — المنامة", 26.2285, 50.5860, "Asia/Bahrain", 2),
            new City("عُمان — مسقط", 23.5880, 58.3829, "Asia/Muscat", 1),
            new City("اليمن — صنعاء", 15.3694, 44.1910, "Asia/Aden", 1),
            // الشام والعراق
            new City("الأردن — عمّان", 31.9454, 35.9284, "Asia/Amman", 3),
            new City("فلسطين — القدس", 31.7683, 35.2137, "Asia/Hebron", 0),
            new City("فلسطين — غزة", 31.5017, 34.4668, "Asia/Gaza", 0),
            new City("لبنان — بيروت", 33.8938, 35.5018, "Asia/Beirut", 1),
            new City("سوريا — دمشق", 33.5138, 36.2765, "Asia/Damascus", 1),
            new City("العراق — بغداد", 33.3152, 44.3661, "Asia/Baghdad", 1),
            // أفريقيا
            new City("السودان — الخرطوم", 15.5007, 32.5599, "Africa/Khartoum", 0),
            new City("ليبيا — طرابلس", 32.8872, 13.1913, "Africa/Tripoli", 0),
            new City("تونس — تونس", 36.8065, 10.1815, "Africa/Tunis", 10),
            new City("الجزائر — الجزائر", 36.7538, 3.0588, "Africa/Algiers", 8),
            new City("المغرب — الدار البيضاء", 33.5731, -7.5898, "Africa/Casablanca", 9),
            new City("المغرب — الرباط", 34.0209, -6.8416, "Africa/Casablanca", 9),
    };

    static String[] names() {
        String[] n = new String[ALL.length];
        for (int i = 0; i < ALL.length; i++) n[i] = ALL[i].name;
        return n;
    }

    private Cities() {}
}
