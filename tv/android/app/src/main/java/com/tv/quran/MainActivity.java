package com.tv.quran;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.List;
import java.util.Locale;

/**
 * أول ما يفتح بيشغّل سورة البقرة لوحده.
 * الريموت: OK = إيقاف/تشغيل، يمين/شمال = تقديم/ترجيع 30 ثانية، فوق/تحت = تغيير القارئ.
 */
public class MainActivity extends Activity {

    private static final int BG = 0xFF0B1512, GOLD = 0xFFD4AF37, TEXT = 0xFFF2F2EE,
            MUTED = 0xFF8E9C96;
    private static final long SEEK_MS = 30_000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    private ListenableFuture<MediaController> controllerFuture;
    private MediaController controller;

    private List<Reciters.Reciter> reciters = Reciters.builtin();
    private int current;          // القارئ اللي شغّال

    private TextView reciterView, statusView, posView, durView, autoView;
    private ProgressBar bar;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            handler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("player", MODE_PRIVATE);
        buildUi();
        WatchService.start(this); // عشان يفتح لوحده بعد كده مع التليفزيون

        // القارئ المحفوظ من آخر مرة
        String saved = prefs.getString("server", null);
        if (saved != null) {
            boolean found = false;
            for (int i = 0; i < reciters.size(); i++) {
                if (reciters.get(i).server.equals(saved)) {
                    current = i;
                    found = true;
                }
            }
            if (!found) {
                reciters.add(0, new Reciters.Reciter(prefs.getString("name", ""),
                        prefs.getString("moshaf", ""), saved));
                current = 0;
            }
        }
        showReciter();

        new Thread(() -> {
            final List<Reciters.Reciter> list = Reciters.load(getApplicationContext());
            handler.post(() -> onRecitersLoaded(list));
        }).start();
    }

    private void onRecitersLoaded(List<Reciters.Reciter> list) {
        String server = reciters.get(current).server;
        int idx = -1;
        for (int i = 0; i < list.size(); i++) if (list.get(i).server.equals(server)) idx = i;
        if (idx < 0) {
            list.add(0, reciters.get(current));
            idx = 0;
        }
        reciters = list;
        current = idx;
        showReciter();
        if (pickerOpen) fillRows();
    }

    @Override
    protected void onStart() {
        super.onStart();
        SessionToken token = new SessionToken(this, new ComponentName(this, PlaybackService.class));
        final ListenableFuture<MediaController> future = new MediaController.Builder(this, token).buildAsync();
        controllerFuture = future;
        future.addListener(() -> {
            if (future != controllerFuture) return;
            try {
                controller = future.get();
            } catch (Exception e) {
                statusView.setText("حصلت مشكلة… اقفل البرنامج وافتحه تاني");
                return;
            }
            controller.addListener(new Player.Listener() {
                @Override
                public void onEvents(Player player, Player.Events events) {
                    updateStatus();
                }
            });
            // لو مفيش حاجة شغّالة: نبدأ البقرة على طول
            if (controller.getMediaItemCount() == 0) {
                play(reciters.get(current), prefs.getLong("pos", 0));
            } else if (!controller.getPlayWhenReady()) {
                controller.play();
            }
            updateStatus();
        }, handler::post);
        handler.post(tick);
        updateAutoHint();
    }

    @Override
    protected void onStop() {
        handler.removeCallbacks(tick);
        savePosition();
        if (controllerFuture != null) MediaController.releaseFuture(controllerFuture);
        controllerFuture = null;
        controller = null;
        super.onStop();
    }

    private void play(Reciters.Reciter r, long positionMs) {
        prefs.edit().putString("server", r.server).putString("name", r.name)
                .putString("moshaf", r.moshaf).putLong("pos", positionMs).apply();
        if (controller == null) return;
        MediaItem item = PlaybackService.baqarah(r);
        controller.setMediaItem(item, Math.max(0, positionMs));
        controller.setRepeatMode(Player.REPEAT_MODE_ONE);
        controller.prepare();
        controller.play();
    }

    private void savePosition() {
        if (controller != null && controller.getMediaItemCount() > 0) {
            prefs.edit().putLong("pos", controller.getCurrentPosition()).apply();
        }
    }

    // ───────────────────────── الريموت ─────────────────────────

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // القايمة مفتوحة: الأسهم وOK بتتحرك جوّاها عادي
        if (pickerOpen) return super.onKeyDown(keyCode, event);
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // ضغطة عادية = إيقاف/تشغيل (في onKeyUp)، ضغطة طويلة = إعدادات الفتح التلقائي
                event.startTracking();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (event.getRepeatCount() == 0) togglePlay();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                if (controller != null) controller.play();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (controller != null) controller.pause();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                seekBy(SEEK_MS);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                seekBy(-SEEK_MS);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_MENU:
                if (event.getRepeatCount() == 0) openPicker();
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (pickerOpen) return super.onKeyUp(keyCode, event);
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (event.isTracking() && !event.isCanceled()) togglePlay();
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        if (pickerOpen) return super.onKeyLongPress(keyCode, event);
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            openOverlaySettings();
            return true;
        }
        return super.onKeyLongPress(keyCode, event);
    }

    /** "الظهور فوق التطبيقات": من غيرها أندرويد 10+ مش بيسيب البرنامج يفتح شاشته لوحده. */
    private void openOverlaySettings() {
        if (AutoStart.canOpenScreen(this)) {
            autoView.setText("✓ الفتح التلقائي شغّال (من 8 الصبح لـ 1 الضهر)");
            return;
        }
        String[] tries = {"package:" + getPackageName(), null};
        for (String uri : tries) {
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
                if (uri != null) i.setData(Uri.parse(uri));
                startActivity(i);
                return;
            } catch (Exception ignored) {
            }
        }
        autoView.setText("التليفزيون ده مفيهوش الإعداد ده. من الكمبيوتر:  adb shell appops set "
                + getPackageName() + " SYSTEM_ALERT_WINDOW allow");
    }

    private void updateAutoHint() {
        autoView.setText(AutoStart.canOpenScreen(this)
                ? "✓ بيفتح لوحده مع التليفزيون من 8 الصبح لـ 1 الضهر"
                : "عشان يفتح لوحده مع التليفزيون: دوس على OK ضغطة طويلة واسمح بـ \"الظهور فوق التطبيقات\"");
    }

    /** رجوع = يقفل الصوت ويخرج. (زرار Home بيسيب الصوت شغّال.) */
    @Override
    public void onBackPressed() {
        if (pickerOpen) {
            closePicker();
            return;
        }
        savePosition();
        if (controller != null) controller.stop();
        super.onBackPressed();
    }

    private void togglePlay() {
        if (controller == null) return;
        if (controller.getPlayWhenReady()) {
            controller.pause();
        } else {
            if (controller.getMediaItemCount() == 0) {
                play(reciters.get(current), prefs.getLong("pos", 0));
                return;
            }
            if (controller.getPlaybackState() == Player.STATE_IDLE) controller.prepare();
            controller.play();
        }
    }

    private void seekBy(long ms) {
        if (controller == null || controller.getMediaItemCount() == 0) return;
        long dur = controller.getDuration();
        long to = Math.max(0, controller.getCurrentPosition() + ms);
        if (dur != C.TIME_UNSET && dur > 0) to = Math.min(to, dur - 1000);
        controller.seekTo(to);
        updateProgress();
    }

    // ───────────────────────── الشاشة ─────────────────────────

    private void showReciter() {
        Reciters.Reciter r = reciters.get(Math.min(current, reciters.size() - 1));
        reciterView.setText(r.label() + "   ▾");
    }

    // ───────────────────────── قايمة القرّاء ─────────────────────────

    private static final int PANEL_W = 470;

    private View scrim;
    private LinearLayout panel, rows;
    private ScrollView scroll;
    private boolean pickerOpen;

    private void buildPicker(FrameLayout host) {
        scrim = new View(this);
        scrim.setBackgroundColor(0xB3000000);
        scrim.setVisibility(View.GONE);
        host.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        panel.setPadding(dp(28), dp(32), dp(28), dp(16));
        GradientDrawable pbg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF14251F, 0xFF0D1814});
        float r = dp(28);
        pbg.setCornerRadii(new float[]{r, r, 0, 0, 0, 0, r, r}); // الناحية اللي جوه الشاشة بس
        panel.setBackground(pbg);
        panel.setElevation(dp(24));

        TextView head = text(30, TEXT, true);
        head.setGravity(Gravity.START);
        head.setText("اختار القارئ");
        panel.addView(head);
        TextView sub = text(15, MUTED, false);
        sub.setGravity(Gravity.START);
        sub.setText("▲ ▼ للتنقل   •   OK للاختيار   •   رجوع للإلغاء");
        sub.setPadding(0, dp(4), 0, dp(18));
        panel.addView(sub);

        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(dp(6), dp(6), dp(6), dp(24));
        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows);
        panel.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        panel.setVisibility(View.GONE);
        panel.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        host.addView(panel, new FrameLayout.LayoutParams(dp(PANEL_W),
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.RIGHT));
    }

    private void fillRows() {
        rows.removeAllViews();
        for (int i = 0; i < reciters.size(); i++) rows.addView(reciterRow(i, reciters.get(i), i == current));
    }

    private View reciterRow(final int index, Reciters.Reciter r, boolean playing) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setFocusable(true);
        row.setClickable(true);

        final GradientDrawable normal = new GradientDrawable();
        normal.setCornerRadius(dp(18));
        normal.setColor(playing ? 0xFF182B24 : 0x00000000);
        final GradientDrawable focused = new GradientDrawable();
        focused.setCornerRadius(dp(18));
        focused.setColor(0xFF223A30);
        focused.setStroke(dp(2), GOLD);
        row.setBackground(normal);

        // دايرة فيها أول حرف من اسم القارئ
        TextView avatar = text(22, playing ? BG : GOLD, true);
        String name = r.name.trim();
        avatar.setText(name.isEmpty() ? "؟" : name.substring(0, 1));
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(playing ? GOLD : 0xFF1F3329);
        avatar.setBackground(circle);
        row.addView(avatar, new LinearLayout.LayoutParams(dp(52), dp(52)));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(16), 0, dp(10), 0);
        TextView t1 = text(21, TEXT, true);
        t1.setGravity(Gravity.START);
        t1.setSingleLine(true);
        t1.setEllipsize(TextUtils.TruncateAt.END);
        t1.setText(name);
        texts.addView(t1);
        if (r.moshaf != null && !r.moshaf.isEmpty()) {
            TextView t2 = text(15, MUTED, false);
            t2.setGravity(Gravity.START);
            t2.setText(r.moshaf);
            texts.addView(t2);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        if (playing) {
            TextView badge = text(13, GOLD, true);
            badge.setText("يُتلى الآن");
            badge.setPadding(dp(10), dp(4), dp(10), dp(4));
            GradientDrawable pill = new GradientDrawable();
            pill.setCornerRadius(dp(20));
            pill.setStroke(dp(1), GOLD);
            badge.setBackground(pill);
            row.addView(badge);
        }

        row.setOnFocusChangeListener((v, has) -> {
            v.setBackground(has ? focused : normal);
            v.animate().scaleX(has ? 1.03f : 1f).scaleY(has ? 1.03f : 1f).setDuration(140).start();
        });
        row.setOnClickListener(v -> chooseReciter(index));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        row.setLayoutParams(lp);
        return row;
    }

    private void openPicker() {
        if (pickerOpen) return;
        pickerOpen = true;
        fillRows();
        panel.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        scrim.animate().cancel();
        panel.animate().cancel();
        scrim.setAlpha(0f);
        scrim.setVisibility(View.VISIBLE);
        scrim.animate().alpha(1f).setDuration(220).start();
        panel.setVisibility(View.VISIBLE);
        panel.setTranslationX(dp(PANEL_W));
        panel.animate().translationX(0).setDuration(260)
                .setInterpolator(new DecelerateInterpolator(2f)).start();

        final View cur = rows.getChildAt(Math.min(current, rows.getChildCount() - 1));
        if (cur != null) {
            cur.requestFocus();
            scroll.post(() -> scroll.scrollTo(0, Math.max(0, cur.getTop() - dp(140))));
        }
    }

    private void closePicker() {
        if (!pickerOpen) return;
        pickerOpen = false;
        panel.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        scrim.animate().alpha(0f).setDuration(200)
                .withEndAction(() -> scrim.setVisibility(View.GONE)).start();
        panel.animate().translationX(dp(PANEL_W)).setDuration(200)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> panel.setVisibility(View.GONE)).start();
    }

    private void chooseReciter(int index) {
        if (!pickerOpen) return;
        if (index != current) {
            current = index;
            play(reciters.get(current), 0);
        } else if (controller != null && !controller.getPlayWhenReady()) {
            togglePlay();
        }
        showReciter();
        closePicker();
    }

    private void updateStatus() {
        if (controller == null) return;
        String s;
        if (controller.getPlayerError() != null) s = "مفيش نت… هيحاول تاني لوحده";
        else if (controller.getPlaybackState() == Player.STATE_BUFFERING) s = "جاري التحميل…";
        else if (!controller.getPlayWhenReady()) s = "⏸ متوقف — اضغط OK للتشغيل";
        else s = "▶ شغّال";
        statusView.setText(s);
        updateProgress();
    }

    private void updateProgress() {
        if (controller == null || controller.getMediaItemCount() == 0) return;
        long pos = controller.getCurrentPosition();
        long dur = controller.getDuration();
        posView.setText(fmt(pos));
        if (dur != C.TIME_UNSET && dur > 0) {
            durView.setText(fmt(dur));
            bar.setProgress((int) (1000 * pos / dur));
        } else {
            durView.setText("--:--");
            bar.setProgress(0);
        }
    }

    private static String fmt(long ms) {
        long s = ms / 1000;
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec)
                : String.format(Locale.ROOT, "%02d:%02d", m, sec);
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    private TextView text(float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private void buildUi() {
        FrameLayout host = new FrameLayout(this);
        host.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(48), dp(27), dp(48), dp(27)); // هامش أمان لشاشات التليفزيون

        TextView basmala = text(22, MUTED, false);
        basmala.setText("بسم الله الرحمن الرحيم");
        root.addView(basmala);

        TextView title = text(64, GOLD, true);
        title.setText("سورة البقرة");
        title.setPadding(0, dp(8), 0, dp(4));
        root.addView(title);

        // اسم القارئ في "كبسولة" — بتفتح القايمة بـ ▲ أو ▼
        reciterView = text(24, TEXT, false);
        reciterView.setPadding(dp(26), dp(10), dp(26), dp(10));
        GradientDrawable chip = new GradientDrawable();
        chip.setCornerRadius(dp(30));
        chip.setColor(0xFF13231D);
        chip.setStroke(dp(1), 0xFF2E4A3E);
        reciterView.setBackground(chip);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.topMargin = dp(10);
        root.addView(reciterView, cp);

        statusView = text(20, MUTED, false);
        statusView.setPadding(0, dp(16), 0, dp(20));
        statusView.setText("جاري التحميل…");
        root.addView(statusView);

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setProgressTintList(ColorStateList.valueOf(GOLD));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(0xFF22332D));
        bar.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(560), dp(10));
        root.addView(bar, bp);

        LinearLayout times = new LinearLayout(this);
        times.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        posView = text(18, MUTED, false);
        durView = text(18, MUTED, false);
        posView.setGravity(Gravity.START);
        durView.setGravity(Gravity.END);
        times.addView(posView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        times.addView(durView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(times, new LinearLayout.LayoutParams(dp(560), ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView hint = text(16, MUTED, false);
        hint.setText("OK: إيقاف / تشغيل     ◀ ▶: ترجيع / تقديم 30 ثانية     ▲ ▼: اختيار القارئ     رجوع: خروج");
        hint.setPadding(0, dp(36), 0, 0);
        root.addView(hint);

        autoView = text(15, MUTED, false);
        autoView.setPadding(0, dp(10), 0, 0);
        root.addView(autoView);

        host.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        buildPicker(host);
        setContentView(host);
    }
}
