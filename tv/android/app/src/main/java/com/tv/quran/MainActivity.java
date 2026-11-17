package com.tv.quran;

import android.app.Activity;
import android.content.ComponentName;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
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
    private int pending = -1;     // القارئ اللي بيتقلّب عليه بالريموت (لسه متشغّلش)

    private TextView reciterView, statusView, posView, durView;
    private ProgressBar bar;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            handler.postDelayed(this, 500);
        }
    };

    private final Runnable applyPending = () -> {
        if (pending >= 0 && pending != current) {
            current = pending;
            play(reciters.get(current), 0);
        }
        pending = -1;
        showReciter();
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("player", MODE_PRIVATE);
        buildUi();

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
        pending = -1;
        showReciter();
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
        MediaItem item = new MediaItem.Builder()
                .setMediaId(r.baqarahUrl())
                .setUri(r.baqarahUrl())
                .setMediaMetadata(new MediaMetadata.Builder()
                        .setTitle("سورة البقرة")
                        .setArtist(r.label())
                        .build())
                .build();
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
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
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
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                stepReciter(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                stepReciter(-1);
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /** رجوع = يقفل الصوت ويخرج. (زرار Home بيسيب الصوت شغّال.) */
    @Override
    public void onBackPressed() {
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

    private void stepReciter(int dir) {
        int n = reciters.size();
        if (n == 0) return;
        int from = pending >= 0 ? pending : current;
        pending = ((from + dir) % n + n) % n;
        showReciter();
        // بنستنى ثانية عشان لو بتقلّب بسرعة ميحمّلش كل قارئ
        handler.removeCallbacks(applyPending);
        handler.postDelayed(applyPending, 1200);
    }

    // ───────────────────────── الشاشة ─────────────────────────

    private void showReciter() {
        int i = pending >= 0 ? pending : current;
        Reciters.Reciter r = reciters.get(Math.min(i, reciters.size() - 1));
        reciterView.setText(r.label());
        reciterView.setTextColor(pending >= 0 ? GOLD : TEXT);
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
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(BG);
        root.setPadding(dp(48), dp(27), dp(48), dp(27)); // هامش أمان لشاشات التليفزيون

        TextView basmala = text(22, MUTED, false);
        basmala.setText("بسم الله الرحمن الرحيم");
        root.addView(basmala);

        TextView title = text(64, GOLD, true);
        title.setText("سورة البقرة");
        title.setPadding(0, dp(8), 0, dp(4));
        root.addView(title);

        reciterView = text(28, TEXT, false);
        root.addView(reciterView);

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
        hint.setText("OK: إيقاف / تشغيل     ◀ ▶: ترجيع / تقديم 30 ثانية     ▲ ▼: تغيير القارئ     رجوع: خروج");
        hint.setPadding(0, dp(36), 0, 0);
        root.addView(hint);

        setContentView(root);
    }
}
