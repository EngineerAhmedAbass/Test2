package com.prayer.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.widget.Toast;
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
    private final TextView[] nameViews = new TextView[6];
    private final TextView[] bells = new TextView[6];
    private Button testBtn, laterBtn, overlayBtn, updateBtn;
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
        // أول مرة: نشرح ليه محتاجين إذن الظهور فوق التطبيقات
        if (!canOverlay() && !Prefs.overlayAsked(this)) {
            Prefs.setOverlayAsked(this);
            handler.postDelayed(this::overlayDialog, 800);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        settingsChanged();
        handler.post(tick);
        // نشوف لو فيه تحديث، مرة كل يوم
        long now = System.currentTimeMillis();
        if (now - Prefs.lastUpdateCheck(this) > 24 * 3600000L) {
            Prefs.setLastUpdateCheck(this, now);
            Updater.checkQuietly(this, latest -> update());
        }
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
        int pad = Ui.dp(c, wide ? 24 : 16);
        col.setPadding(pad, pad, pad, Ui.dp(c, 12));

        clock = Ui.text(c, "", wide ? 62 : 56, Ui.GOLD, true);
        col.addView(clock);
        dates = Ui.text(c, "", wide ? 19 : 16, Ui.TEXT, false);
        col.addView(dates);
        city = Ui.text(c, "", wide ? 15 : 15, Ui.MUTED, false);
        col.addView(city);

        next = Ui.text(c, "", wide ? 21 : 20, Ui.TEXT, true);
        next.setBackground(Ui.pill(c));
        int ph = Ui.dp(c, 22), pv = Ui.dp(c, 6);
        next.setPadding(ph, pv, ph, pv);
        LinearLayout.LayoutParams nlp = wrap();
        nlp.topMargin = Ui.dp(c, 12);
        col.addView(next, nlp);

        // مساحة فاضية عشان الكعبة اللي في الصورة تبان
        col.addView(new View(c), new LinearLayout.LayoutParams(1, 0, 1f));

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
            int cp = Ui.dp(c, wide ? 8 : 14);
            card.setPadding(cp, cp, cp, cp);
            card.setOnClickListener(v -> prayerDialog(p));

            TextView name = Ui.text(c, PrayTimes.NAMES[i], wide ? 18 : 18, Ui.TEXT, false);
            nameViews[i] = name;
            timeViews[i] = Ui.text(c, "", wide ? 25 : 22, Ui.TEXT, true);
            bells[i] = Ui.text(c, "", wide ? 13 : 16, Ui.MUTED, false);
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
                    ? new LinearLayout.LayoutParams(Ui.dp(c, 132), -2)
                    : new LinearLayout.LayoutParams(-1, -2);
            int m = Ui.dp(c, wide ? 4 : 5);
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

        // على التليفزيون الشرح ده بيظهر في الإعدادات بدل ما ياخد مكان من الصورة
        if (!wide) {
            col.addView(Ui.text(c, "اضغط على أي صلاة عشان تقفل أذانها أو تظبط ميعادها بالدقيقة",
                    13, Ui.MUTED, false), wrap());
        }

        // أزرار الإعدادات
        LinearLayout btns = new LinearLayout(c);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.CENTER);
        btns.addView(button("المدينة", v -> cityDialog()));
        btns.addView(button("الإعدادات", v -> settingsDialog()));
        testBtn = button("تجربة الأذان", v -> testAdhan());
        btns.addView(testBtn);
        laterBtn = button("جرّب بعد دقيقة", v -> testLater());
        btns.addView(laterBtn);
        overlayBtn = button("", v -> overlayDialog());
        btns.addView(overlayBtn);
        updateBtn = button("تحديث البرنامج", v -> Updater.checkAndInstall(this));
        btns.addView(updateBtn);
        HorizontalScrollView bs = new HorizontalScrollView(c);
        bs.setHorizontalScrollBarEnabled(false);
        bs.addView(btns);
        LinearLayout.LayoutParams blp = wrap();
        blp.topMargin = Ui.dp(c, 8);
        col.addView(bs, blp);

        ScrollView sv = new ScrollView(c);
        sv.setFillViewport(true);
        sv.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        sv.addView(col);
        return Ui.withHaramBackground(c, sv);
    }

    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private Button button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setTextColor(Ui.TEXT);
        b.setBackground(Ui.focusBg(this, Ui.CARD));
        int h = Ui.dp(this, 16), v = Ui.dp(this, 8);
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
            city.setText(Prefs.city(this).name + "  —  " + PrayTimes.METHOD_NAMES[Prefs.method(this)]);
            for (int i = 0; i < 6; i++) {
                timeViews[i].setText(Ui.time(today[i], tz, false));
                int off = Prefs.offset(this, i);
                String bell = i == PrayTimes.SUNRISE ? "" : (Prefs.enabled(this, i) ? "" : "بدون أذان");
                if (off != 0) bell += (bell.isEmpty() ? "" : " ") + (off > 0 ? "+" : "") + off + "د";
                bells[i].setText(bell);
            }
        }

        clock.setText(Ui.time(now, tz, true));
        long[] n = Prefs.next(this, now, false);
        int nextP = n == null ? -1 : (int) n[0];
        if (AdhanService.playing >= 0) {
            next.setText("حان الآن موعد أذان " + PrayTimes.NAMES[AdhanService.playing]);
            next.setTextColor(Ui.GOLD);
        } else if (n != null) {
            next.setText("باقي على أذان " + PrayTimes.NAMES[nextP] + "   " + Ui.duration(n[1] - now));
            next.setTextColor(Ui.TEXT);
        }
        // الكارت الدهبي = الصلاة الجاية (لو هي النهارده)
        int hl = n != null && today != null && today[nextP] == n[1] ? nextP : -1;
        if (hl != highlighted || cards[0].getBackground() == null) {
            highlighted = hl;
            for (int i = 0; i < 6; i++) {
                boolean on = i == hl;
                cards[i].setBackground(Ui.focusBg(this, on ? Ui.NEXT : Ui.CARD));
                int fg = on ? Ui.NEXT_TEXT : Ui.TEXT;
                nameViews[i].setTextColor(fg);
                timeViews[i].setTextColor(fg);
                bells[i].setTextColor(on ? Ui.NEXT_TEXT : Ui.MUTED);
                // الظل مش محتاجينه على الكارت الدهبي
                float r = on ? 0 : 6;
                nameViews[i].setShadowLayer(r, 0, 2, 0xAA000000);
                timeViews[i].setShadowLayer(r, 0, 2, 0xAA000000);
                bells[i].setShadowLayer(r, 0, 2, 0xAA000000);
            }
        }
        testBtn.setText(AdhanService.playing >= 0 ? "إيقاف الأذان" : "تجربة الأذان");
        long testAt = Prefs.testAt(this);
        laterBtn.setText(testAt > now ? "الأذان التجريبي بعد " + Ui.duration(testAt - now).substring(2) + " (إلغاء)"
                : "جرّب بعد دقيقة");
        updateBtn.setText(Updater.latestKnown > Updater.currentVersion(this)
                ? "تحديث متاح: نسخة " + Updater.latestKnown : "تحديث البرنامج");
        overlayBtn.setText(canOverlay() ? "شاشة الأذان فوق البرامج: مفعّلة" : "شاشة الأذان فوق البرامج: مش مفعّلة");
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

    /** أذان تجريبي بعد دقيقة: اقفل التطبيق وروح لأي برنامج وشوف هيشتغل ولا لأ */
    private void testLater() {
        if (Prefs.testAt(this) > System.currentTimeMillis()) {
            AlarmReceiver.cancelTest(this);
            Toast.makeText(this, "اتلغى الأذان التجريبي", Toast.LENGTH_SHORT).show();
        } else {
            AlarmReceiver.scheduleTest(this, System.currentTimeMillis() + 60000L);
            Toast.makeText(this, "روح افتح يوتيوب أو أي برنامج تاني — الأذان هيشتغل بعد دقيقة", Toast.LENGTH_LONG).show();
        }
        update();
    }

    private boolean canOverlay() {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this);
    }

    private void overlayDialog() {
        if (canOverlay()) {
            dialog("شاشة الأذان فوق البرامج")
                    .setMessage("مسموح. شاشة \"حان الآن موعد الأذان\" هتظهر حتى لو أنت في برنامج تاني.")
                    .setPositiveButton("تمام", null).show();
            return;
        }
        dialog("اسمح بظهور شاشة الأذان فوق البرامج")
                .setMessage("عشان شاشة الأذان (وزرار الإيقاف) تظهر وأنت بتتفرج على برنامج تاني، "
                        + "لازم تسمح للتطبيق بـ \"الظهور فوق التطبيقات الأخرى\".\n\n"
                        + "الصوت هيشتغل في كل الأحوال حتى من غير الإذن ده.\n\n"
                        + "دوس \"افتح الإعدادات\" وفعّل \"مواقيت الصلاة\".")
                .setPositiveButton("افتح الإعدادات", (d, w) -> openOverlaySettings())
                .setNegativeButton("بعدين", null)
                .show();
    }

    private void openOverlaySettings() {
        Intent[] tries = {
                new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())),
                new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
                new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())),
        };
        for (Intent i : tries) {
            try {
                startActivity(i);
                return;
            } catch (Exception ignored) {
            }
        }
        dialog("الجهاز مش فيه الإعداد ده")
                .setMessage("بعض أجهزة التليفزيون مخبية الإعداد ده. تقدر تفعله من الكمبيوتر بأمر adb:\n\n"
                        + "adb shell appops set " + getPackageName() + " SYSTEM_ALERT_WINDOW allow\n\n"
                        + "ومن غيره الأذان هيشتغل بالصوت عادي.")
                .setPositiveButton("تمام", null).show();
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
        int fv = Prefs.fajrVoice(this);
        String[] items = {
                "المؤذن: " + Prefs.VOICE_NAMES[Prefs.voice(this)],
                "مؤذن الفجر: " + (fv < 0 ? "زي باقي الصلوات" : Prefs.VOICE_NAMES[fv]),
                "طريقة الحساب: " + PrayTimes.METHOD_NAMES[Prefs.method(this)],
                "العصر: " + (Prefs.hanafi(this) ? "الحنفي" : "الجمهور (شافعي/مالكي/حنبلي)"),
                "مستوى صوت الأذان: " + volumeName(),
                "شاشة الأذان: " + (Prefs.showScreen(this) ? "بتظهر" : "صوت بس"),
                "تعديل التاريخ الهجري: " + Prefs.hijriAdjust(this) + " يوم",
        };
        dialog("الإعدادات").setItems(items, (d, w) -> {
            if (w == 0) {
                dialog("اختار المؤذن (هيشتغل للتجربة)").setSingleChoiceItems(java.util.Arrays.copyOf(Prefs.VOICE_NAMES, Prefs.MAIN_VOICES), Prefs.voice(this), (d2, m) -> {
                    Prefs.setVoice(this, m);
                    d2.dismiss();
                    AdhanService.start(this, PrayTimes.DHUHR);
                    handler.postDelayed(this::update, 300);
                }).show();
                return;
            }
            if (w == 1) {
                String[] opts = new String[Prefs.VOICE_NAMES.length + 1];
                opts[0] = "زي باقي الصلوات";
                System.arraycopy(Prefs.VOICE_NAMES, 0, opts, 1, Prefs.VOICE_NAMES.length);
                dialog("مؤذن الفجر (هيشتغل للتجربة)").setSingleChoiceItems(opts, Prefs.fajrVoice(this) + 1, (d2, m) -> {
                    Prefs.setFajrVoice(this, m - 1);
                    d2.dismiss();
                    AdhanService.start(this, PrayTimes.FAJR);
                    handler.postDelayed(this::update, 300);
                }).show();
                return;
            }
            switch (w - 2) {
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
                    dialog("مستوى صوت الأذان").setSingleChoiceItems(Prefs.VOLUME_NAMES, cur, (d2, m) -> {
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
        String toggle = Prefs.enabled(this, p) ? "اقفل أذان " + PrayTimes.NAMES[p] : "شغّل أذان " + PrayTimes.NAMES[p];
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
