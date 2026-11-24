package com.tvremote.app;

import java.util.HashMap;
import java.util.Map;

/**
 * لغة التطبيق: العربي هو الأصل، والإنجليزي بييجي من public/i18n.js (نفس ملف الواجهة).
 * L.t("الكلام العربي") بترجع الترجمة لو اللغة إنجليزي، والمسافات اللي على الأطراف بتفضل زي ما هي.
 */
public final class L {
    static volatile String lang = "ar";
    static final Map<String, String> EN = new HashMap<>();

    private L() { }

    public static String t(String ar) {
        if (ar == null || !"en".equals(lang)) return ar;
        int a = 0, b = ar.length();
        while (a < b && Character.isWhitespace(ar.charAt(a))) a++;
        while (b > a && Character.isWhitespace(ar.charAt(b - 1))) b--;
        String key = ar.substring(a, b).replaceAll("\\s+", " ");
        String en = EN.get(key);
        return en == null ? ar : ar.substring(0, a) + en + ar.substring(b);
    }
}
