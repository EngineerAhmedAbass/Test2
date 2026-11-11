package com.prayer.tv;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** ألوان وأدوات صغيرة للواجهة */
final class Ui {
    static final int BG = 0xFF060E1E;
    /** كروت شفافة فوق صورة الحرم */
    static final int CARD = 0xC70A162E;
    static final int GOLD = 0xFFE2C275;
    /** الصلاة الجاية: كارت دهبي وكلام كحلي */
    static final int NEXT = GOLD;
    static final int NEXT_TEXT = 0xFF0B1730;
    static final int TEXT = 0xFFF3EEDF;
    static final int MUTED = 0xFFC3CAD8;

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
        // ظل خفيف عشان الكلام يبان فوق الصورة
        t.setShadowLayer(6, 0, 2, 0xAA000000);
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
        // على الأزرار الدهبي الإطار أبيض عشان يبان
        int ring = fill == GOLD ? Color.WHITE : GOLD;
        s.addState(new int[]{android.R.attr.state_focused}, rect(c, fill, ring));
        s.addState(new int[]{android.R.attr.state_pressed}, rect(c, fill, ring));
        s.addState(new int[]{}, rect(c, fill, Color.TRANSPARENT));
        return s;
    }

    /** صورة الحرم كخلفية + تغميق عشان الكلام يبان، والمحتوى فوقهم */
    static View withHaramBackground(Context c, View content) {
        return withHaramBackground(c, content, false);
    }

    /** dim = تغميق أكتر (لشاشة الإعدادات) */
    static View withHaramBackground(Context c, View content, boolean dim) {
        FrameLayout f = new FrameLayout(c);
        f.setBackgroundColor(BG);
        ImageView img = new ImageView(c);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setImageResource(R.drawable.bg_haram);
        f.addView(img, new FrameLayout.LayoutParams(-1, -1));
        View shade = new View(c);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                dim ? new int[]{0xE6040A17, 0xD9040A17, 0xEB040A17}
                        : new int[]{0xA6040A17, 0x4D040A17, 0x40040A17, 0xD9040A17}));
        f.addView(shade, new FrameLayout.LayoutParams(-1, -1));
        f.addView(content, new FrameLayout.LayoutParams(-1, -1));
        return f;
    }

    private static GradientDrawable round(Context c, int fill, int stroke, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(c, radius));
        if (stroke != 0) g.setStroke(dp(c, 2), stroke);
        return g;
    }

    /** صف في الإعدادات: شفاف، ولما الريموت يقف عليه يبقى دهبي فاتح بإطار دهبي */
    static Drawable rowBg(Context c) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, round(c, 0x33E2C275, GOLD, 16));
        s.addState(new int[]{android.R.attr.state_pressed}, round(c, 0x33E2C275, GOLD, 16));
        s.addState(new int[]{android.R.attr.state_activated}, round(c, 0x1FFFFFFF, 0, 16));
        s.addState(new int[]{}, round(c, 0x00000000, 0, 16));
        return s;
    }

    /** لوحة الاختيارات */
    static Drawable panel(Context c) {
        return round(c, 0x8C0A162E, 0x26FFFFFF, 24);
    }

    /** دايرة الاختيار: مليانة دهبي لو مختارة */
    static Drawable radio(Context c, boolean on) {
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(0x00000000);
        ring.setStroke(dp(c, 2), on ? GOLD : MUTED);
        if (!on) return ring;
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(GOLD);
        android.graphics.drawable.LayerDrawable l =
                new android.graphics.drawable.LayerDrawable(new Drawable[]{ring, dot});
        int in = dp(c, 5);
        l.setLayerInset(1, in, in, in, in);
        return l;
    }

    /** مفتاح تشغيل/إيقاف */
    static Drawable switchTrack(Context c, boolean on) {
        return round(c, on ? GOLD : 0x40FFFFFF, 0, 999);
    }

    static Drawable switchKnob(Context c, boolean on) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(on ? NEXT_TEXT : TEXT);
        return g;
    }

    /** خلفية مدورة شفافة دهبي (للعد التنازلي) */
    static Drawable pill(Context c) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x24E2C275);
        g.setCornerRadius(dp(c, 999));
        g.setStroke(dp(c, 1), 0x73E2C275);
        return g;
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
