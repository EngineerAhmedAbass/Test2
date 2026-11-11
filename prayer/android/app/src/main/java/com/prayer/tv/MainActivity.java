package com.prayer.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Calendar;
import java.util.TimeZone;

/** الشاشة الرئيسية: الساعة، التاريخ، مواقيت النهارده، والإعدادات */
public class MainActivity extends Activity {
    private static final String[] DAYS = {"الأحد", "الإثنين", "الثلاثاء", "الأربعاء", "الخميس", "الجمعة", "السبت"};
    private static final String[] MONTHS = {"يناير", "فبراير", "مارس", "أبريل", "مايو", "يونيو",
            "يوليو", "أغسطس", "سبتمبر", "أكتوبر", "نوفمبر", "ديسمبر"};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView clock, dates, city, next;
    private final LinearLayout[] cards = new LinearLayout[6];
    private final TextView[] timeViews = new TextView[6];
    private final TextView[] bells = new TextView[6];
    private Button testBtn;
    private long[] today;
    private int todayKey = -1;
    private int highlighted = -2;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            update();
            handler.postDelayed(this, 1000 - System.currentTimeMillis() % 1000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(build());
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != 0) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        settingsChanged();
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(tick);
        super.onPause();
    }

    /* ------------------------------ الواجهة ------------------------------ */

    private View build() {
        Context c = this;
        boolean wide = getResources().getConfiguration().screenWidthDp >= 600;

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = Ui.dp(c, wide ? 28 : 16);
        col.setPadding(pad, pad, pad, pad);

        clock = Ui.text(c, "", wide ? 80 : 56, Ui.GOLD, true);
        col.addView(clock);
        dates = Ui.text(c, "", wide ? 22 : 16, Ui.TEXT, false);
        col.addView(dates);
        city = Ui.text(c, "", wide ? 18 : 15, Ui.MUTED, false);
        col.addView(city);

        next = Ui.text(c, "", wide ? 28 : 20, Ui.TEXT, true);
        LinearLayout.LayoutParams nlp = wrap();
        nlp.topMargin = Ui.dp(c, 18);
        nlp.bottomMargin = Ui.dp(c, 18);
        col.addView(next, nlp);

        LinearLayout row = new LinearLayout(c);
        row.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        row.setGravity(Gravity.CENTER);
        for (int i = 0; i < 6; i++) {
            final int p = i;
            LinearLayout card = new LinearLayout(c);
            card.setOrientation(wide ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER);
            card.setFocusable(true);
            card.setClickable(true);
            int cp = Ui.dp(c, 14);
            card.setPadding(cp, cp, cp, cp);
            card.setOnClickListener(v -> prayerDialog(p));

            TextView name = Ui.text(c, PrayTimes.NAMES[i], wide ? 22 : 18, Ui.TEXT, false);
            timeViews[i] = Ui.text(c, "", wide ? 30 : 22, Ui.TEXT, true);
            bells[i] = Ui.text(c, "", wide ? 16 : 16, Ui.MUTED, false);
            if (wide) {
                card.addView(name);
                card.addView(timeViews[i]);
                card.addView(bells[i]);
            } else {
                card.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
                card.addView(timeViews[i], new LinearLayout.LayoutParams(0, -2, 1));
                card.addView(bells[i], new LinearLayout.LayoutParams(Ui.dp(c, 40), -2));
            }
            LinearLayout.LayoutParams lp = wide
                    ? new LinearLayout.LayoutParams(Ui.dp(c, 150), -2)
                    : new LinearLayout.LayoutParams(-1, -2);
            int m = Ui.dp(c, 5);
            lp.setMargins(m, m, m, m);
            cards[i] = card;
            row.addView(card, lp);
        }
        if (wide) {
            HorizontalScrollView hs = new HorizontalScrollView(c);
            hs.setHorizontalScrollBarEnabled(false);
            hs.addView(row);
            col.addView(hs, wrap());
        } else {
            col.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }

        col.addView(Ui.text(c, "اضغط على أي صلاة عشان تقفل أذانها أو تظبط ميعادها بالدقيقة",
                14, Ui.MUTED, false), wrap());

        // أزرار الإعدادات
        LinearLayout btns = new LinearLayout(c);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.CENTER);
        btns.addView(button("📍 المدينة", v -> cityDialog()));
        btns.addView(button("⚙️ الإعدادات", v -> settingsDialog()));
        testBtn = button("🔊 تجربة الأذان", v -> testAdhan());
        btns.addView(testBtn);
        HorizontalScrollView bs = new HorizontalScrollView(c);
        bs.setHorizontalScrollBarEnabled(false);
        bs.addView(btns);
        LinearLayout.LayoutParams blp = wrap();
        blp.topMargin = Ui.dp(c, 18);
        col.addView(bs, blp);

        ScrollView sv = new ScrollView(c);
        sv.setFillViewport(true);
        sv.setBackgroundColor(Ui.BG);
        sv.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        sv.addView(col);
        return sv;
    }

    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private Button button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(18);
        b.setTextColor(Ui.TEXT);
        b.setBackground(Ui.focusBg(this, Ui.CARD));
        int h = Ui.dp(this, 18), v = Ui.dp(this, 10);
        b.setPadding(h, v, h, v);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = wrap();
        int m = Ui.dp(this, 6);
        lp.setMargins(m, 0, m, 0);
        b.setLayoutParams(lp);
        return b;
    }

    /* ------------------------------ التحديث ------------------------------ */

    /** بعد أي تغيير في الإعدادات: نحسب من جديد ونجدول الأذان الجاي */
    private void settingsChanged() {
        todayKey = -1;
        AlarmReceiver.schedule(this, System.currentTimeMillis());
        update();
    }

    private void update() {
        long now = System.currentTimeMillis();
        TimeZone tz = Prefs.zone(this);
        Calendar cal = Calendar.getInstance(tz);
        int y = cal.get(Calendar.YEAR), m = cal.get(Calendar.MONTH) + 1, d = cal.get(Calendar.DAY_OF_MONTH);
        int key = y * 400 + m * 32 + d;

        if (key != todayKey) {
            todayKey = key;
            today = Prefs.times(this, 0);
            int[] h = PrayTimes.hijri(y, m, d, Prefs.hijriAdjust(this));
            dates.setText(DAYS[cal.get(Calendar.DAY_OF_WEEK) - 1] + "  " + d + " " + MONTHS[m - 1] + " " + y
                    + "   •   " + h[2] + " " + PrayTimes.HIJRI_MONTHS[(h[1] + 11) % 12] + " " + h[0] + " هـ");
            city.setText("📍 " + Prefs.city(this).name + "  —  " + PrayTimes.METHOD_NAMES[Prefs.method(this)]);
            for (int i = 0; i < 6; i++) {
                timeViews[i].setText(Ui.time(today[i], tz, false));
                int off = Prefs.offset(this, i);
                String bell = i == PrayTimes.SUNRISE ? "" : (Prefs.enabled(this, i) ? "🔔" : "🔕");
                if (off != 0) bell += (bell.isEmpty() ? "" : " ") + (off > 0 ? "+" : "") + off + "د";
                bells[i].setText(bell);
            }
        }

        clock.setText(Ui.time(now, tz, true));
        long[] n = Prefs.next(this, now, false);
        int nextP = n == null ? -1 : (int) n[0];
        if (AdhanService.playing >= 0) {
            next.setText("🕌 حان الآن موعد أذان " + PrayTimes.NAMES[AdhanService.playing]);
            next.setTextColor(Ui.GOLD);
        } else if (n != null) {
            next.setText("باقي على أذان " + PrayTimes.NAMES[nextP] + "   " + Ui.duration(n[1] - now));
            next.setTextColor(Ui.TEXT);
        }
        // الكارت الأخضر = الصلاة الجاية (لو هي النهارده)
        int hl = n != null && today != null && today[nextP] == n[1] ? nextP : -1;
        if (hl != highlighted || cards[0].getBackground() == null) {
            highlighted = hl;
            for (int i = 0; i < 6; i++) cards[i].setBackground(Ui.focusBg(this, i == hl ? Ui.NEXT : Ui.CARD));
        }
        testBtn.setText(AdhanService.playing >= 0 ? "⏹ إيقاف الأذان" : "🔊 تجربة الأذان");
    }

    private void testAdhan() {
        if (AdhanService.playing >= 0) {
            AdhanService.stop(this);
        } else {
            long[] n = Prefs.next(this, System.currentTimeMillis(), false);
            AdhanService.start(this, n == null ? PrayTimes.DHUHR : (int) n[0]);
        }
        handler.postDelayed(this::update, 300);
    }

    /* ------------------------------ القوائم ------------------------------ */

    private AlertDialog.Builder dialog(String title) {
        return new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(title);
    }

    private void cityDialog() {
        dialog("اختار مدينتك")
                .setSingleChoiceItems(Cities.names(), Prefs.cityIndex(this), (d, w) -> {
                    Prefs.setCity(this, w);
                    d.dismiss();
                    settingsChanged();
                })
                .show();
    }

    private void settingsDialog() {
        String[] items = {
                "طريقة الحساب: " + PrayTimes.METHOD_NAMES[Prefs.method(this)],
                "العصر: " + (Prefs.hanafi(this) ? "الحنفي" : "الجمهور (شافعي/مالكي/حنبلي)"),
                "صوت الأذان: " + volumeName(),
                "شاشة الأذان: " + (Prefs.showScreen(this) ? "بتظهر" : "صوت بس"),
                "تعديل التاريخ الهجري: " + Prefs.hijriAdjust(this) + " يوم",
        };
        dialog("الإعدادات").setItems(items, (d, w) -> {
            switch (w) {
                case 0:
                    dialog("طريقة الحساب").setSingleChoiceItems(PrayTimes.METHOD_NAMES, Prefs.method(this), (d2, m) -> {
                        Prefs.setMethod(this, m);
                        d2.dismiss();
                        settingsChanged();
                    }).show();
                    break;
                case 1:
                    dialog("ميعاد العصر").setSingleChoiceItems(
                            new String[]{"الجمهور (شافعي/مالكي/حنبلي)", "الحنفي"},
                            Prefs.hanafi(this) ? 1 : 0, (d2, m) -> {
                                Prefs.setHanafi(this, m == 1);
                                d2.dismiss();
                                settingsChanged();
                            }).show();
                    break;
                case 2:
                    int cur = 0;
                    for (int i = 0; i < Prefs.VOLUMES.length; i++) if (Prefs.VOLUMES[i] == Prefs.volume(this)) cur = i;
                    dialog("صوت الأذان").setSingleChoiceItems(Prefs.VOLUME_NAMES, cur, (d2, m) -> {
                        Prefs.setVolume(this, Prefs.VOLUMES[m]);
                        d2.dismiss();
                    }).show();
                    break;
                case 3:
                    Prefs.setShowScreen(this, !Prefs.showScreen(this));
                    settingsDialog();
                    break;
                case 4:
                    dialog("تعديل التاريخ الهجري").setSingleChoiceItems(
                            new String[]{"-2", "-1", "0", "+1", "+2"},
                            Prefs.hijriAdjust(this) + 2, (d2, m) -> {
                                Prefs.setHijriAdjust(this, m - 2);
                                d2.dismiss();
                                settingsChanged();
                            }).show();
                    break;
            }
        }).show();
    }

    private String volumeName() {
        for (int i = 0; i < Prefs.VOLUMES.length; i++) {
            if (Prefs.VOLUMES[i] == Prefs.volume(this)) return Prefs.VOLUME_NAMES[i];
        }
        return Prefs.VOLUME_NAMES[0];
    }

    private void prayerDialog(int p) {
        int off = Prefs.offset(this, p);
        boolean sunrise = p == PrayTimes.SUNRISE;
        String toggle = Prefs.enabled(this, p) ? "🔕 اقفل أذان " + PrayTimes.NAMES[p] : "🔔 شغّل أذان " + PrayTimes.NAMES[p];
        String[] items = sunrise
                ? new String[]{"أخّر دقيقة (+1)", "قدّم دقيقة (-1)", "رجّع الميعاد المحسوب"}
                : new String[]{toggle, "أخّر دقيقة (+1)", "قدّم دقيقة (-1)", "رجّع الميعاد المحسوب"};
        String title = PrayTimes.NAMES[p] + "  —  " + Ui.time(today[p], Prefs.zone(this), false)
                + (off != 0 ? "  (تعديل " + (off > 0 ? "+" : "") + off + " دقيقة)" : "");
        dialog(title).setItems(items, (d, w) -> {
            int a = sunrise ? w + 1 : w;
            if (a == 0) Prefs.setEnabled(this, p, !Prefs.enabled(this, p));
            else if (a == 1) Prefs.setOffset(this, p, off + 1);
            else if (a == 2) Prefs.setOffset(this, p, off - 1);
            else Prefs.setOffset(this, p, 0);
            settingsChanged();
            if (a == 1 || a == 2) prayerDialog(p); // عشان تقدر تدوس كذا مرة ورا بعض
        }).show();
    }
}
