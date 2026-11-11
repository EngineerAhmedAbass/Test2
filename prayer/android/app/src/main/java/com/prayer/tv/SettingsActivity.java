package com.prayer.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * شاشة الإعدادات بشكل تليفزيون حديث:
 * القايمة على اليمين، والاختيارات بتاعة الصف اللي واقف عليه على الشمال.
 * على الموبايل القايمة بتاخد الشاشة كلها، والاختيارات بتفتح مكانها.
 */
public class SettingsActivity extends Activity {
    static final String EXTRA_PRAYER = "prayer";

    private interface Val { String get(); }
    private interface Detail { void build(); }
    private interface Pick { void pick(int i); }

    private static final class Row {
        final String title, hint;
        final Val value;
        final Detail detail;
        LinearLayout view;
        TextView valueView;

        Row(String title, String hint, Val value, Detail detail) {
            this.title = title;
            this.hint = hint;
            this.value = value;
            this.detail = detail;
        }
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Row> rows = new ArrayList<>();
    private final List<View> options = new ArrayList<>();
    private boolean wide;
    private LinearLayout list, detailBox, detailPane;
    private ScrollView listScroll, detailScroll;
    private TextView detailTitle, detailHint;
    private Row current;
    private Row[] prayerRows = new Row[6];

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refreshValues();
            handler.postDelayed(this, 1000);
        }
    };

    static Intent forPrayer(Context c, int p) {
        return new Intent(c, SettingsActivity.class).putExtra(EXTRA_PRAYER, p);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        wide = getResources().getConfiguration().screenWidthDp >= 600;
        buildRows();
        setContentView(Ui.withHaramBackground(this, build(), true));
        int p = getIntent().getIntExtra(EXTRA_PRAYER, -1);
        Row first = p >= 0 && p < 6 ? prayerRows[p] : rows.get(0);
        if (wide) {
            showDetail(first);
            first.view.requestFocus();
            if (p >= 0) focusOption(0);
        } else if (p >= 0) {
            openNarrow(first);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(tick);
        if (current != null) rebuildDetail(-1); // لو رجع من صفحة إعدادات النظام
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(tick);
        super.onPause();
        // أي تغيير في المواعيد لازم يتجدول
        AlarmReceiver.schedule(this, System.currentTimeMillis());
    }

    /* ------------------------------ الصفوف ------------------------------ */

    private void buildRows() {
        rows.add(section("الموقع والحساب"));
        rows.add(new Row("المدينة", "المواعيد بتتحسب على حسب مكان المدينة",
                () -> Prefs.city(this).name,
                () -> choice(Cities.names(), Prefs.cityIndex(this), i -> Prefs.setCity(this, i))));
        rows.add(new Row("طريقة الحساب", "بتتظبط لوحدها لما تغير البلد",
                () -> PrayTimes.METHOD_NAMES[Prefs.method(this)],
                () -> choice(PrayTimes.METHOD_NAMES, Prefs.method(this), i -> Prefs.setMethod(this, i))));
        String[] asr = {"الجمهور (شافعي ومالكي وحنبلي)", "الحنفي"};
        rows.add(new Row("ميعاد العصر", null,
                () -> asr[Prefs.hanafi(this) ? 1 : 0],
                () -> choice(asr, Prefs.hanafi(this) ? 1 : 0, i -> Prefs.setHanafi(this, i == 1))));
        String[] hijri = {"قبلها بيومين", "قبلها بيوم", "زي ما هو", "بعدها بيوم", "بعدها بيومين"};
        rows.add(new Row("التاريخ الهجري", "لو التاريخ مختلف عن بلدك بيوم ظبطه من هنا",
                () -> hijri[Prefs.hijriAdjust(this) + 2],
                () -> choice(hijri, Prefs.hijriAdjust(this) + 2, i -> Prefs.setHijriAdjust(this, i - 2))));

        rows.add(section("الأذان"));
        String[] main = Arrays.copyOf(Prefs.VOICE_NAMES, Prefs.MAIN_VOICES);
        rows.add(new Row("المؤذن", "لما تختار صوت هيشتغل علطول عشان تسمعه",
                () -> Prefs.VOICE_NAMES[Prefs.voice(this)],
                () -> choice(main, Prefs.voice(this), i -> {
                    Prefs.setVoice(this, i);
                    AdhanService.start(this, PrayTimes.DHUHR);
                })));
        String[] fajr = new String[Prefs.VOICE_NAMES.length + 1];
        fajr[0] = "زي باقي الصلوات";
        System.arraycopy(Prefs.VOICE_NAMES, 0, fajr, 1, Prefs.VOICE_NAMES.length);
        rows.add(new Row("مؤذن الفجر", "أذان الفجر فيه \"الصلاة خير من النوم\"",
                () -> fajr[Prefs.fajrVoice(this) + 1],
                () -> choice(fajr, Prefs.fajrVoice(this) + 1, i -> {
                    Prefs.setFajrVoice(this, i - 1);
                    AdhanService.start(this, PrayTimes.FAJR);
                })));
        rows.add(new Row("مستوى الصوت", "بيرجع الصوت زي ما كان بعد الأذان",
                () -> Prefs.VOLUME_NAMES[volumeIndex()],
                () -> choice(Prefs.VOLUME_NAMES, volumeIndex(), i -> Prefs.setVolume(this, Prefs.VOLUMES[i]))));
        String[] screen = {"تظهر شاشة الأذان", "صوت بس"};
        rows.add(new Row("شاشة الأذان", null,
                () -> screen[Prefs.showScreen(this) ? 0 : 1],
                () -> choice(screen, Prefs.showScreen(this) ? 0 : 1, i -> Prefs.setShowScreen(this, i == 0))));

        rows.add(section("الصلوات"));
        for (int p = 0; p < 6; p++) {
            final int fp = p;
            prayerRows[p] = new Row(PrayTimes.NAMES[p],
                    "قدّم أو أخّر الميعاد بالدقيقة عشان يطابق مسجد منطقتك",
                    () -> prayerValue(fp), () -> prayerDetail(fp));
            rows.add(prayerRows[p]);
        }

        rows.add(section("عام"));
        rows.add(new Row("جرّب الأذان", "دوس جرّب بعد دقيقة، وروح افتح يوتيوب أو أي برنامج، واستنى",
                () -> {
                    long left = Prefs.testAt(this) - System.currentTimeMillis();
                    return left > 0 ? "أذان تجريبي بعد " + Ui.duration(left).substring(2) : "اسمعه دلوقتي أو بعد دقيقة";
                },
                this::testDetail));
        rows.add(new Row("شاشة الأذان فوق البرامج", null,
                () -> canOverlay() ? "مفعّلة" : "مش مفعّلة",
                this::overlayDetail));
        rows.add(new Row("تحديث البرنامج", null,
                () -> "نسخة " + Updater.currentVersion(this)
                        + (Updater.latestKnown > Updater.currentVersion(this) ? "  •  فيه نسخة جديدة" : ""),
                this::updateDetail));
    }

    /** عنوان قسم (مش صف) */
    private Row section(String title) {
        return new Row(title, null, null, null);
    }

    private int volumeIndex() {
        for (int i = 0; i < Prefs.VOLUMES.length; i++) if (Prefs.VOLUMES[i] == Prefs.volume(this)) return i;
        return 0;
    }

    private String prayerValue(int p) {
        long t = Prefs.times(this, 0)[p];
        String v = Ui.time(t, Prefs.zone(this), false);
        if (p != PrayTimes.SUNRISE) v += Prefs.enabled(this, p) ? "  •  بأذان" : "  •  بدون أذان";
        int off = Prefs.offset(this, p);
        if (off != 0) v += "  •  " + (off > 0 ? "+" : "") + off + " دقيقة";
        return v;
    }

    /* ------------------------------ الواجهة ------------------------------ */

    private View build() {
        int pad = Ui.dp(this, wide ? 40 : 16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setPadding(pad, Ui.dp(this, wide ? 32 : 20), pad, Ui.dp(this, wide ? 32 : 16));

        // القايمة
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        TextView h = label("الإعدادات", wide ? 30 : 26, Ui.GOLD, true);
        left.addView(h);
        left.addView(label(wide ? "اتحرك بالأسهم، ودوس OK عشان تختار" : "اختار أي حاجة عشان تغيرها",
                14, Ui.MUTED, false));
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 24));
        for (Row r : rows) list.addView(r.detail == null ? sectionView(r.title) : rowView(r));
        listScroll = new ScrollView(this);
        listScroll.setVerticalScrollBarEnabled(false);
        listScroll.addView(list);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, 0, 1f);
        slp.topMargin = Ui.dp(this, 12);
        left.addView(listScroll, slp);
        root.addView(left, new LinearLayout.LayoutParams(0, -1, wide ? 0.42f : 1f));

        // لوحة الاختيارات
        detailPane = new LinearLayout(this);
        detailPane.setOrientation(LinearLayout.VERTICAL);
        detailPane.setBackground(Ui.panel(this));
        int dp = Ui.dp(this, wide ? 28 : 18);
        detailPane.setPadding(dp, dp, dp, dp);
        detailTitle = label("", wide ? 26 : 22, Ui.TEXT, true);
        detailPane.addView(detailTitle);
        detailHint = label("", 14, Ui.MUTED, false);
        detailPane.addView(detailHint);
        detailBox = new LinearLayout(this);
        detailBox.setOrientation(LinearLayout.VERTICAL);
        detailBox.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 8));
        detailScroll = new ScrollView(this);
        detailScroll.setVerticalScrollBarEnabled(false);
        detailScroll.addView(detailBox);
        detailPane.addView(detailScroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(0, -1, wide ? 0.58f : 1f);
        dlp.setMarginStart(Ui.dp(this, wide ? 32 : 0));
        root.addView(detailPane, dlp);
        if (!wide) detailPane.setVisibility(View.GONE);
        return root;
    }

    private TextView label(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.START);
        t.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private View sectionView(String title) {
        TextView t = label(title, 13, Ui.GOLD, true);
        t.setLetterSpacing(0.05f);
        t.setAlpha(0.85f);
        t.setPadding(Ui.dp(this, 18), Ui.dp(this, 18), Ui.dp(this, 18), Ui.dp(this, 6));
        return t;
    }

    private View rowView(Row r) {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.HORIZONTAL);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setFocusable(true);
        v.setClickable(true);
        v.setBackground(Ui.rowBg(this));
        int ph = Ui.dp(this, 18), pv = Ui.dp(this, 11);
        v.setPadding(ph, pv, ph, pv);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(label(r.title, 17, Ui.TEXT, false));
        r.valueView = label(r.value.get(), 13.5f, Ui.MUTED, false);
        r.valueView.setSingleLine(true);
        r.valueView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(r.valueView);
        v.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        // سهم ناحية لوحة الاختيارات
        v.addView(label("‹", 24, Ui.MUTED, false));

        v.setOnFocusChangeListener((view, has) -> {
            if (has && wide && current != r) showDetail(r);
        });
        v.setOnClickListener(view -> {
            if (wide) {
                if (current != r) showDetail(r);
                focusOption(selectedOption());
            } else {
                openNarrow(r);
            }
        });
        r.view = v;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = Ui.dp(this, 4);
        v.setLayoutParams(lp);
        return v;
    }

    private void refreshValues() {
        for (Row r : rows) if (r.valueView != null) r.valueView.setText(r.value.get());
    }

    /* ------------------------------ لوحة الاختيارات ------------------------------ */

    private void showDetail(Row r) {
        if (current != null && current.view != null) current.view.setActivated(false);
        current = r;
        r.view.setActivated(true);
        rebuildDetail(-1);
        detailScroll.scrollTo(0, 0);
    }

    /** focus = رقم الاختيار اللي نرجعله بعد ما نبني من جديد (-1 = ولا حاجة) */
    private void rebuildDetail(int focus) {
        if (current == null) return;
        detailTitle.setText(current.title);
        detailHint.setText(current.hint == null ? "" : current.hint);
        detailHint.setVisibility(current.hint == null ? View.GONE : View.VISIBLE);
        detailBox.removeAllViews();
        options.clear();
        current.detail.build();
        refreshValues();
        if (focus >= 0) focusOption(focus);
    }

    private void focusOption(int i) {
        if (options.isEmpty()) return;
        View v = options.get(Math.max(0, Math.min(i, options.size() - 1)));
        v.requestFocus();
    }

    private int selectedOption() {
        for (int i = 0; i < options.size(); i++) if (Boolean.TRUE.equals(options.get(i).getTag())) return i;
        return 0;
    }

    private void openNarrow(Row r) {
        showDetail(r);
        listScroll.setVisibility(View.GONE);
        ((View) listScroll.getParent()).setVisibility(View.GONE);
        detailPane.setVisibility(View.VISIBLE);
        focusOption(selectedOption());
    }

    private boolean closeNarrow() {
        if (wide || detailPane.getVisibility() != View.VISIBLE) return false;
        detailPane.setVisibility(View.GONE);
        ((View) listScroll.getParent()).setVisibility(View.VISIBLE);
        listScroll.setVisibility(View.VISIBLE);
        if (current != null) current.view.requestFocus();
        return true;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (closeNarrow()) return true;
            // لو واقف في الاختيارات، رجوع يرجعك للقايمة
            View f = getCurrentFocus();
            if (wide && f != null && current != null && isInside(f, detailPane)) {
                current.view.requestFocus();
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    private static boolean isInside(View v, View parent) {
        for (Object p = v; p instanceof View; p = ((View) p).getParent()) if (p == parent) return true;
        return false;
    }

    /** اختيار واحد من لستة: دايرة دهبي جنب اللي مختار */
    private void choice(String[] items, int selected, Pick onPick) {
        for (int i = 0; i < items.length; i++) {
            final int fi = i;
            LinearLayout o = optionView(items[i], null);
            View dot = new View(this);
            dot.setBackground(Ui.radio(this, i == selected));
            int s = Ui.dp(this, 22);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(s, s);
            lp.setMarginEnd(Ui.dp(this, 14));
            o.addView(dot, 0, lp);
            o.setTag(i == selected);
            o.setOnClickListener(v -> {
                onPick.pick(fi);
                rebuildDetail(fi);
            });
            addOption(o);
        }
    }

    private LinearLayout optionView(String text, String sub) {
        LinearLayout o = new LinearLayout(this);
        o.setOrientation(LinearLayout.HORIZONTAL);
        o.setGravity(Gravity.CENTER_VERTICAL);
        o.setFocusable(true);
        o.setClickable(true);
        o.setBackground(Ui.rowBg(this));
        int ph = Ui.dp(this, 16), pv = Ui.dp(this, 12);
        o.setPadding(ph, pv, ph, pv);
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.addView(label(text, 17, Ui.TEXT, false));
        if (sub != null) t.addView(label(sub, 13, Ui.MUTED, false));
        o.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        return o;
    }

    private void addOption(View o) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = Ui.dp(this, 4);
        detailBox.addView(o, lp);
        options.add(o);
    }

    /** زرار دهبي كبير */
    private void actionButton(String text, View.OnClickListener l) {
        TextView b = label(text, 17, Ui.NEXT_TEXT, true);
        b.setGravity(Gravity.CENTER);
        b.setFocusable(true);
        b.setClickable(true);
        b.setBackground(Ui.focusBg(this, Ui.GOLD));
        int ph = Ui.dp(this, 24), pv = Ui.dp(this, 12);
        b.setPadding(ph, pv, ph, pv);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = Ui.dp(this, 8);
        detailBox.addView(b, lp);
        options.add(b);
    }

    private void paragraph(String text) {
        TextView t = label(text, 16, Ui.TEXT, false);
        t.setLineSpacing(0, 1.25f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = Ui.dp(this, 10);
        detailBox.addView(t, lp);
    }

    /* ------------------------------ تفاصيل كل صف ------------------------------ */

    private void prayerDetail(int p) {
        TextView big = label(Ui.time(Prefs.times(this, 0)[p], Prefs.zone(this), false), 44, Ui.GOLD, true);
        detailBox.addView(big);
        int off = Prefs.offset(this, p);
        detailBox.addView(label(off == 0 ? "الميعاد المحسوب بالظبط"
                : "متعدّل " + (off > 0 ? "+" : "") + off + " دقيقة عن المحسوب", 14, Ui.MUTED, false));
        View gap = new View(this);
        detailBox.addView(gap, new LinearLayout.LayoutParams(1, Ui.dp(this, 14)));

        if (p != PrayTimes.SUNRISE) {
            boolean on = Prefs.enabled(this, p);
            LinearLayout o = optionView("أذان " + PrayTimes.NAMES[p], on ? "شغال" : "مقفول");
            FrameLayout track = new FrameLayout(this);
            track.setBackground(Ui.switchTrack(this, on));
            View knob = new View(this);
            knob.setBackground(Ui.switchKnob(this, on));
            int k = Ui.dp(this, 20);
            FrameLayout.LayoutParams kp = new FrameLayout.LayoutParams(k, k,
                    Gravity.CENTER_VERTICAL | (on ? Gravity.START : Gravity.END));
            int m = Ui.dp(this, 3);
            kp.setMargins(m, m, m, m);
            track.addView(knob, kp);
            o.addView(track, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 26)));
            o.setTag(true);
            o.setOnClickListener(v -> {
                Prefs.setEnabled(this, p, !Prefs.enabled(this, p));
                rebuildDetail(0);
            });
            addOption(o);
        }
        int base = options.size();
        LinearLayout later = optionView("أخّر دقيقة", "+1");
        later.setOnClickListener(v -> {
            Prefs.setOffset(this, p, Prefs.offset(this, p) + 1);
            rebuildDetail(base);
        });
        addOption(later);
        LinearLayout earlier = optionView("قدّم دقيقة", "-1");
        earlier.setOnClickListener(v -> {
            Prefs.setOffset(this, p, Prefs.offset(this, p) - 1);
            rebuildDetail(base + 1);
        });
        addOption(earlier);
        if (off != 0) {
            LinearLayout reset = optionView("رجّع الميعاد المحسوب", null);
            reset.setOnClickListener(v -> {
                Prefs.setOffset(this, p, 0);
                rebuildDetail(base);
            });
            addOption(reset);
        }
    }

    private void testDetail() {
        paragraph("التجربة دي بتتأكد إن الأذان هيشتغل وأنت بتتفرج على أي حاجة تانية.");
        boolean waiting = Prefs.testAt(this) > System.currentTimeMillis();
        actionButton(waiting ? "إلغاء الأذان التجريبي" : "جرّب بعد دقيقة", v -> {
            if (Prefs.testAt(this) > System.currentTimeMillis()) {
                AlarmReceiver.cancelTest(this);
            } else {
                AlarmReceiver.scheduleTest(this, System.currentTimeMillis() + 60000L);
            }
            rebuildDetail(0);
        });
        actionButton(AdhanService.playing >= 0 ? "إيقاف الأذان" : "اسمع الأذان دلوقتي", v -> {
            if (AdhanService.playing >= 0) AdhanService.stop(this);
            else AdhanService.start(this, PrayTimes.DHUHR);
            handler.postDelayed(() -> rebuildDetail(1), 400);
        });
    }

    private boolean canOverlay() {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this);
    }

    private void overlayDetail() {
        if (canOverlay()) {
            paragraph("مفعّلة. شاشة \"حان الآن موعد الأذان\" وزرار الإيقاف هيظهروا حتى لو أنت في برنامج تاني.");
            return;
        }
        paragraph("من غير الإذن ده الأذان بيشتغل بالصوت بس، والشاشة اللي فيها زرار الإيقاف ممكن ماتظهرش "
                + "وأنت في برنامج تاني.\n\nدوس الزرار وفعّل \"مواقيت الصلاة\" في الصفحة اللي هتفتح.");
        actionButton("افتح الإعدادات", v -> openOverlaySettings(this));
    }

    private void updateDetail() {
        int cur = Updater.currentVersion(this);
        paragraph("عندك نسخة " + cur + "."
                + (Updater.latestKnown > cur ? "\nفيه نسخة جديدة: " + Updater.latestKnown + "." : "")
                + "\n\nالتحديث بينزل ويتسطب فوق القديم، وإعداداتك بتفضل زي ما هي.");
        actionButton(Updater.latestKnown > cur ? "نزّل التحديث" : "دوّر على تحديث",
                v -> Updater.checkAndInstall(this));
    }

    /** بيفتح صفحة "الظهور فوق التطبيقات" (أو يشرح بديلها لو الجهاز مخبيها) */
    static void openOverlaySettings(Activity a) {
        Intent[] tries = {
                new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + a.getPackageName())),
                new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
                new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.getPackageName())),
        };
        for (Intent i : tries) {
            try {
                a.startActivity(i);
                return;
            } catch (Exception ignored) {
            }
        }
        new AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("الجهاز مش فيه الإعداد ده")
                .setMessage("بعض أجهزة التليفزيون مخبية الإعداد ده. تقدر تفعله من الكمبيوتر بأمر adb:\n\n"
                        + "adb shell appops set " + a.getPackageName() + " SYSTEM_ALERT_WINDOW allow\n\n"
                        + "ومن غيره الأذان هيشتغل بالصوت عادي.")
                .setPositiveButton("تمام", null).show();
    }
}
