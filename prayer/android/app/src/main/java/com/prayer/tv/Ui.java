package com.prayer.tv;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** ألوان وأدوات صغيرة للواجهة */
final class Ui {
    static final int BG = 0xFF0B1220;
    static final int CARD = 0xFF151F33;
    static final int NEXT = 0xFF14532D;
    static final int GOLD = 0xFFE8C66A;
    static final int TEXT = 0xFFF1F5F9;
    static final int MUTED = 0xFF94A3B8;

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private static GradientDrawable rect(Context c, int fill, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(c, 14));
        if (stroke != 0) g.setStroke(dp(c, 3), stroke);
        return g;
    }

    /** خلفية بتظهر إطار دهبي لما الريموت يقف عليها */
    static Drawable focusBg(Context c, int fill) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, rect(c, fill, GOLD));
        s.addState(new int[]{android.R.attr.state_pressed}, rect(c, fill, GOLD));
        s.addState(new int[]{}, rect(c, fill, Color.TRANSPARENT));
        return s;
    }

    /** "4:45 م" بأرقام عادية */
    static String time(long ms, TimeZone tz, boolean seconds) {
        SimpleDateFormat f = new SimpleDateFormat(seconds ? "h:mm:ss" : "h:mm", Locale.US);
        f.setTimeZone(tz);
        SimpleDateFormat a = new SimpleDateFormat("a", Locale.US);
        a.setTimeZone(tz);
        return f.format(new Date(ms)) + ("AM".equals(a.format(new Date(ms))) ? " ص" : " م");
    }

    static String duration(long ms) {
        long s = Math.max(0, ms / 1000);
        return String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
    }

    private Ui() {}
}
