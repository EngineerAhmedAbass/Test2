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
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.media3.common.C;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.List;
import java.util.Locale;

/**
 * أول ما يفتح بيشغّل سورة البقرة لوحده.
 * تحت فيه أزرار: الإعدادات، ترجيع، تشغيل/إيقاف، تقديم، القرّاء (بتتنقل بينها بـ ◀ ▶ وتدوس OK).
 */
public class MainActivity extends Activity {

    private static final int BG = 0xFF0B1512, GOLD = 0xFFD4AF37, GOLD_SOFT = 0xFFE9CF7A,
            TEXT = 0xFFF5F3EA, MUTED = 0xFF9DB0A8;
    private static final long SEEK_MS = 30_000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    private ListenableFuture<MediaController> controllerFuture;
    private MediaController controller;

    private List<Reciters.Reciter> reciters = Reciters.builtin();
    private int current;

    private TextView reciterView, statusView, posView, durView, banner;
    private ProgressBar bar;
    private ImageView playIcon;
    private View playBtn;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            handler.postDelayed(this, 500);
        }
    };
    private final Runnable hideBanner = () -> banner.animate().alpha(0f).setDuration(300)
            .withEndAction(() -> banner.setVisibility(View.GONE)).start();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("player", MODE_PRIVATE);
        buildUi();
        WatchService.start(this); // عشان يفتح لوحده بعد كده مع التليفزيون

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
                reciters.add(0, PlaybackService.savedReciter(prefs));
                current = 0;
            }
        }
        showReciter();

        new Thread(() -> {
            final List<Reciters.Reciter> list = Reciters.load(getApplicationContext());
            handler.post(() -> onRecitersLoaded(list));
        }).start();

        // كل ما يفتح يدوّر على تحديث
        handler.postDelayed(() -> Updater.check(this, false, this::showBanner), 3000);
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
        if (panelMode == PANEL_RECITERS) fillReciters();
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
                    // السورة خلصت ← البرنامج يقفل
                    if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED)
                            && player.getPlaybackState() == Player.STATE_ENDED) {
                        finish();
                    }
                }
            });
            // من 8 لـ 1: يكمّل من المكان اللي وقف عنده النهارده. غير كده: من أول السورة.
            if (controller.getMediaItemCount() == 0) {
                Reciters.Reciter r = reciters.get(current);
                play(r, PlaybackService.resumePosition(this, prefs, r.server));
            } else if (controller.getPlaybackState() == Player.STATE_ENDED) {
                restartFromBeginning();
            } else if (!controller.getPlayWhenReady()) {
                if (PlaybackService.resumePosition(this, prefs, reciters.get(current).server) > 0) {
                    if (controller.getPlaybackState() == Player.STATE_IDLE) controller.prepare();
                    controller.play();
                } else {
                    restartFromBeginning();
                }
            }
            updateStatus();
        }, handler::post);
        handler.post(tick);
        if (panelMode == PANEL_SETTINGS) fillSettings(); // لو راجع من شاشة الإذن
        if (panelMode == PANEL_NONE) playBtn.requestFocus();
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
                .putString("moshaf", r.moshaf).apply();
        if (controller == null) return;
        controller.setMediaItem(PlaybackService.baqarah(r), Math.max(0, positionMs));
        controller.setRepeatMode(Player.REPEAT_MODE_OFF);
        controller.prepare();
        controller.play();
    }

    private void restartFromBeginning() {
        controller.setRepeatMode(Player.REPEAT_MODE_OFF);
        controller.seekTo(0);
        if (controller.getPlaybackState() == Player.STATE_IDLE
                || controller.getPlaybackState() == Player.STATE_ENDED) {
            controller.prepare();
        }
        controller.play();
    }

    private void savePosition() {
        if (controller == null || controller.getMediaItemCount() == 0) return;
        int st = controller.getPlaybackState();
        if (st == Player.STATE_ENDED || st == Player.STATE_IDLE) return;
        PlaybackService.saveProgress(prefs, reciters.get(current).server, controller.getCurrentPosition());
    }

    // ───────────────────────── الريموت ─────────────────────────

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (panelMode != PANEL_NONE) return super.onKeyDown(keyCode, event);
        switch (keyCode) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (event.getRepeatCount() == 0) togglePlay();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                if (controller != null) controller.play();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (controller != null) controller.pause();
                return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                seekBy(SEEK_MS);
                return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                seekBy(-SEEK_MS);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                if (event.getRepeatCount() == 0) openReciters();
                return true;
            case KeyEvent.KEYCODE_MENU:
                if (event.getRepeatCount() == 0) openSettings();
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /** رجوع = يقفل الصوت ويخرج. (زرار Home بيسيب الصوت شغّال.) */
    @Override
    public void onBackPressed() {
        if (panelMode != PANEL_NONE) {
            closePanel();
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
                Reciters.Reciter r = reciters.get(current);
                play(r, PlaybackService.resumePosition(this, prefs, r.server));
                return;
            }
            if (controller.getPlaybackState() == Player.STATE_ENDED) {
                restartFromBeginning();
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

    // ───────────────────────── الإعدادات ─────────────────────────

    /** "الظهور فوق التطبيقات": من غيرها أندرويد 10+ مش بيسيب البرنامج يفتح شاشته لوحده. */
    private void openOverlaySettings() {
        if (AutoStart.canOpenScreen(this)) {
            showBanner("✓ الفتح التلقائي شغّال", false);
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
        showBanner("التليفزيون ده مفيهوش الإعداد ده. من الكمبيوتر:  adb shell appops set "
                + getPackageName() + " SYSTEM_ALERT_WINDOW allow", true);
    }

    private void startAutoOpenTest() {
        AutoStart.startTest(this);
        closePanel();
        showBanner("تمام! البرنامج هيقفل دلوقتي… اقفل التليفزيون بالريموت، استنى 10 ثواني، وافتحه تاني", true);
        handler.postDelayed(() -> {
            savePosition();
            if (controller != null) controller.stop();
            finish();
        }, 6000);
    }

    // ───────────────────────── الشاشة ─────────────────────────

    private void showReciter() {
        Reciters.Reciter r = reciters.get(Math.min(current, reciters.size() - 1));
        reciterView.setText(r.label());
    }

    private void showBanner(String text, boolean sticky) {
        handler.removeCallbacks(hideBanner);
        banner.animate().cancel();
        banner.setText(text);
        if (banner.getVisibility() != View.VISIBLE) {
            banner.setAlpha(0f);
            banner.setVisibility(View.VISIBLE);
        }
        banner.animate().alpha(1f).setDuration(200).start();
        if (!sticky) handler.postDelayed(hideBanner, 5000);
    }

    private void updateStatus() {
        if (controller == null) return;
        String s;
        boolean playing = controller.getPlayWhenReady();
        if (controller.getPlayerError() != null) s = "مفيش نت… هيحاول تاني لوحده";
        else if (controller.getPlaybackState() == Player.STATE_BUFFERING) s = "جاري التحميل…";
        else if (!playing) s = "متوقف";
        else s = "يُتلى الآن";
        statusView.setText(s);
        playIcon.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);

        // طول ما السورة شغّالة: الشاشة تفضل صاحية (مفيش شاشة توقف ولا قفل للتليفزيون)
        int st = controller.getPlaybackState();
        boolean active = playing && st != Player.STATE_ENDED
                && (st != Player.STATE_IDLE || controller.getPlayerError() != null);
        if (active) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
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
        t.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        return t;
    }

    private static GradientDrawable rounded(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    private void buildUi() {
        FrameLayout host = new FrameLayout(this);
        host.setBackgroundColor(BG);
        host.addView(new PatternView(this), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setClipChildren(false);
        root.setPadding(dp(48), dp(24), dp(48), dp(24)); // هامش أمان لشاشات التليفزيون

        TextView basmala = text(20, GOLD_SOFT, false);
        basmala.setText("بِسْمِ اللَّهِ الرَّحْمَٰنِ الرَّحِيمِ");
        basmala.setAlpha(0.85f);
        root.addView(basmala);

        TextView title = text(68, GOLD, true);
        title.setText("سورة البقرة");
        title.setShadowLayer(dp(18), 0, 0, 0x66D4AF37);
        root.addView(title);

        // اسم القارئ + الحالة في كبسولة
        LinearLayout chip = new LinearLayout(this);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(dp(8), dp(6), dp(20), dp(6));
        GradientDrawable chipBg = rounded(0x5913231D, dp(30));
        chipBg.setStroke(dp(1), 0x40D4AF37);
        chip.setBackground(chipBg);
        ImageView person = new ImageView(this);
        person.setImageResource(R.drawable.ic_person);
        person.setImageTintList(ColorStateList.valueOf(BG));
        person.setPadding(dp(7), dp(7), dp(7), dp(7));
        GradientDrawable pc = new GradientDrawable();
        pc.setShape(GradientDrawable.OVAL);
        pc.setColor(GOLD);
        person.setBackground(pc);
        chip.addView(person, new LinearLayout.LayoutParams(dp(36), dp(36)));
        LinearLayout chipText = new LinearLayout(this);
        chipText.setOrientation(LinearLayout.VERTICAL);
        chipText.setPadding(dp(12), 0, 0, 0);
        reciterView = text(20, TEXT, true);
        reciterView.setGravity(Gravity.START);
        statusView = text(14, GOLD_SOFT, false);
        statusView.setGravity(Gravity.START);
        statusView.setText("جاري التحميل…");
        chipText.addView(reciterView);
        chipText.addView(statusView);
        chip.addView(chipText);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.topMargin = dp(14);
        root.addView(chip, cp);

        // شريط التقدّم
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setProgressTintList(ColorStateList.valueOf(GOLD));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(0x33FFFFFF));
        bar.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(600), dp(6));
        bp.topMargin = dp(30);
        root.addView(bar, bp);

        LinearLayout times = new LinearLayout(this);
        times.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        posView = text(15, MUTED, false);
        durView = text(15, MUTED, false);
        posView.setGravity(Gravity.START);
        durView.setGravity(Gravity.END);
        times.addView(posView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        times.addView(durView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(dp(600), ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.topMargin = dp(6);
        root.addView(times, tp);

        // الأزرار (من الشمال لليمين زي أي مشغّل)
        LinearLayout controls = new LinearLayout(this);
        controls.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        controls.setGravity(Gravity.CENTER);
        controls.setClipChildren(false);
        controls.setClipToPadding(false);
        controls.setPadding(0, dp(10), 0, dp(10));
        View settingsBtn = controlButton(R.drawable.ic_settings, "الإعدادات", 58, v -> openSettings());
        View back30 = controlButton(R.drawable.ic_replay, "ترجيع ٣٠ ث", 58, v -> seekBy(-SEEK_MS));
        playBtn = controlButton(R.drawable.ic_play, "تشغيل / إيقاف", 84, v -> togglePlay());
        playIcon = (ImageView) ((ViewGroup) playBtn).getChildAt(0);
        View fwd30 = controlButton(R.drawable.ic_forward, "تقديم ٣٠ ث", 58, v -> seekBy(SEEK_MS));
        View readersBtn = controlButton(R.drawable.ic_person, "القرّاء", 58, v -> openReciters());
        for (View b : new View[]{settingsBtn, back30, playBtn, fwd30, readersBtn}) controls.addView(b);
        LinearLayout.LayoutParams ctl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ctl.topMargin = dp(18);
        root.addView(controls, ctl);

        host.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // رسالة صغيرة فوق (تحديثات، تجربة…)
        banner = text(16, TEXT, false);
        banner.setPadding(dp(22), dp(10), dp(22), dp(10));
        GradientDrawable bb = rounded(0xE6162A22, dp(24));
        bb.setStroke(dp(1), 0x66D4AF37);
        banner.setBackground(bb);
        banner.setMaxWidth(dp(720));
        banner.setVisibility(View.GONE);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        blp.topMargin = dp(22);
        host.addView(banner, blp);

        buildPanel(host);
        setContentView(host);
    }

    /** زرار دايري: أيقونة + كلمة تحت. لما يتعلّم عليه بيبقى دهبي ويكبر. */
    private View controlButton(int icon, String label, int sizeDp, View.OnClickListener click) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setFocusable(true);
        box.setClickable(true);
        box.setClipChildren(false);
        box.setPadding(dp(14), dp(4), dp(14), dp(4));

        final ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        int pad = Math.round(sizeDp * 0.27f);
        iv.setPadding(dp(pad), dp(pad), dp(pad), dp(pad));
        final GradientDrawable idle = new GradientDrawable();
        idle.setShape(GradientDrawable.OVAL);
        idle.setColor(0x1FFFFFFF);
        idle.setStroke(dp(1), 0x33FFFFFF);
        final GradientDrawable focus = new GradientDrawable();
        focus.setShape(GradientDrawable.OVAL);
        focus.setColor(GOLD);
        iv.setBackground(idle);
        iv.setImageTintList(ColorStateList.valueOf(TEXT));
        box.addView(iv, new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));

        final TextView tv = text(13, MUTED, false);
        tv.setText(label);
        tv.setPadding(0, dp(8), 0, 0);
        box.addView(tv);

        box.setOnFocusChangeListener((v, has) -> {
            iv.setBackground(has ? focus : idle);
            iv.setImageTintList(ColorStateList.valueOf(has ? BG : TEXT));
            tv.setTextColor(has ? TEXT : MUTED);
            iv.animate().scaleX(has ? 1.15f : 1f).scaleY(has ? 1.15f : 1f).setDuration(150).start();
        });
        box.setOnClickListener(click);
        return box;
    }

    // ───────────────────────── القايمة الجانبية (القرّاء / الإعدادات) ─────────────────────────

    private static final int PANEL_NONE = 0, PANEL_RECITERS = 1, PANEL_SETTINGS = 2;
    private static final int PANEL_W = 480;

    private View scrim;
    private LinearLayout panel, rows;
    private TextView panelTitle, panelSub;
    private ScrollView scroll;
    private int panelMode = PANEL_NONE;

    private void buildPanel(FrameLayout host) {
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
                new int[]{0xF214251F, 0xF20A1511});
        float r = dp(28);
        pbg.setCornerRadii(new float[]{r, r, 0, 0, 0, 0, r, r});
        panel.setBackground(pbg);
        panel.setElevation(dp(24));

        panelTitle = text(30, TEXT, true);
        panelTitle.setGravity(Gravity.START);
        panel.addView(panelTitle);
        panelSub = text(15, MUTED, false);
        panelSub.setGravity(Gravity.START);
        panelSub.setPadding(0, dp(4), 0, dp(18));
        panel.addView(panelSub);

        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(dp(6), dp(6), dp(6), dp(24));
        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows);
        panel.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        panel.setVisibility(View.GONE);
        panel.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        host.addView(panel, new FrameLayout.LayoutParams(dp(PANEL_W),
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.RIGHT));
    }

    private void openReciters() {
        panelTitle.setText("اختار القارئ");
        panelSub.setText("▲ ▼ للتنقل   •   OK للاختيار   •   رجوع للإلغاء");
        panelMode = PANEL_RECITERS;
        fillReciters();
        showPanel(current);
    }

    private void openSettings() {
        panelTitle.setText("الإعدادات");
        panelSub.setText("النسخة " + Updater.installedVersion(this));
        panelMode = PANEL_SETTINGS;
        fillSettings();
        showPanel(0);
    }

    private void fillReciters() {
        rows.removeAllViews();
        for (int i = 0; i < reciters.size(); i++) {
            final int idx = i;
            Reciters.Reciter r = reciters.get(i);
            boolean playing = i == current;
            TextView avatar = text(22, playing ? BG : GOLD, true);
            String name = r.name.trim();
            avatar.setText(name.isEmpty() ? "؟" : name.substring(0, 1));
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(playing ? GOLD : 0xFF1F3329);
            avatar.setBackground(circle);
            rows.addView(row(avatar, name, r.moshaf, playing ? "يُتلى الآن" : null, playing,
                    () -> chooseReciter(idx)));
        }
    }

    private void fillSettings() {
        rows.removeAllViews();
        boolean auto = AutoStart.canOpenScreen(this);
        rows.addView(row(iconLead(auto ? R.drawable.ic_check : R.drawable.ic_shield, auto),
                "الفتح التلقائي مع التليفزيون",
                auto ? "شغّال: من 8 الصبح لـ 1 الضهر"
                        : "محتاج إذن \"الظهور فوق التطبيقات\"… اضغط هنا",
                null, false, this::openOverlaySettings));
        boolean testing = AutoStart.testActive(this);
        rows.addView(row(iconLead(R.drawable.ic_bolt, testing),
                "جرّب الفتح التلقائي دلوقتي",
                testing ? "وضع التجربة شغّال… اقفل التليفزيون وافتحه"
                        : "يشتغل في أي وقت لمدة 10 دقايق عشان تجرّبه",
                null, false, this::startAutoOpenTest));
        rows.addView(row(iconLead(R.drawable.ic_update, false),
                "البحث عن تحديث",
                "بيدوّر لوحده كل ما البرنامج يفتح",
                null, false, () -> {
                    closePanel();
                    Updater.check(this, true, this::showBanner);
                }));
    }

    private ImageView iconLead(int res, boolean on) {
        ImageView iv = new ImageView(this);
        iv.setImageResource(res);
        iv.setPadding(dp(13), dp(13), dp(13), dp(13));
        iv.setImageTintList(ColorStateList.valueOf(on ? BG : GOLD));
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(on ? GOLD : 0xFF1F3329);
        iv.setBackground(circle);
        return iv;
    }

    private View row(View lead, String title, String sub, String badge, boolean highlighted,
                     final Runnable onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setFocusable(true);
        row.setClickable(true);

        final GradientDrawable normal = rounded(highlighted ? 0xFF182B24 : 0x00000000, dp(18));
        final GradientDrawable focused = rounded(0xFF223A30, dp(18));
        focused.setStroke(dp(2), GOLD);
        row.setBackground(normal);

        row.addView(lead, new LinearLayout.LayoutParams(dp(52), dp(52)));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(16), 0, dp(10), 0);
        TextView t1 = text(20, TEXT, true);
        t1.setGravity(Gravity.START);
        t1.setSingleLine(true);
        t1.setEllipsize(TextUtils.TruncateAt.END);
        t1.setText(title);
        texts.addView(t1);
        if (sub != null && !sub.isEmpty()) {
            TextView t2 = text(14, MUTED, false);
            t2.setGravity(Gravity.START);
            t2.setText(sub);
            texts.addView(t2);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        if (badge != null) {
            TextView b = text(13, GOLD, true);
            b.setText(badge);
            b.setPadding(dp(10), dp(4), dp(10), dp(4));
            GradientDrawable pill = new GradientDrawable();
            pill.setCornerRadius(dp(20));
            pill.setStroke(dp(1), GOLD);
            b.setBackground(pill);
            row.addView(b);
        }

        row.setOnFocusChangeListener((v, has) -> {
            v.setBackground(has ? focused : normal);
            v.animate().scaleX(has ? 1.03f : 1f).scaleY(has ? 1.03f : 1f).setDuration(140).start();
        });
        row.setOnClickListener(v -> {
            if (panelMode != PANEL_NONE) onClick.run();
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        row.setLayoutParams(lp);
        return row;
    }

    private void showPanel(int focusIndex) {
        panel.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        scrim.animate().cancel();
        panel.animate().cancel();
        if (scrim.getVisibility() != View.VISIBLE) {
            scrim.setAlpha(0f);
            scrim.setVisibility(View.VISIBLE);
            panel.setVisibility(View.VISIBLE);
            panel.setTranslationX(dp(PANEL_W));
        }
        scrim.animate().alpha(1f).setDuration(220).start();
        panel.animate().translationX(0).setDuration(260)
                .setInterpolator(new DecelerateInterpolator(2f)).start();

        final View target = rows.getChildAt(Math.max(0, Math.min(focusIndex, rows.getChildCount() - 1)));
        if (target != null) {
            target.requestFocus();
            scroll.post(() -> scroll.scrollTo(0, Math.max(0, target.getTop() - dp(140))));
        }
    }

    private void closePanel() {
        if (panelMode == PANEL_NONE) return;
        panelMode = PANEL_NONE;
        panel.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        scrim.animate().alpha(0f).setDuration(200)
                .withEndAction(() -> scrim.setVisibility(View.GONE)).start();
        panel.animate().translationX(dp(PANEL_W)).setDuration(200)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> panel.setVisibility(View.GONE)).start();
        playBtn.requestFocus();
    }

    private void chooseReciter(int index) {
        if (index != current) {
            current = index;
            play(reciters.get(current), 0);
        } else if (controller != null && !controller.getPlayWhenReady()) {
            togglePlay();
        }
        showReciter();
        closePanel();
    }
}
