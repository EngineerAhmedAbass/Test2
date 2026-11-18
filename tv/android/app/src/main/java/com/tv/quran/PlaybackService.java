package com.tv.quran;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.List;

/** بيشغّل الصوت حتى لو التليفزيون دخل على شاشة التوقف أو فتحت تطبيق تاني. */
public class PlaybackService extends MediaSessionService {

    static final String ACTION_AUTOPLAY = "com.tv.quran.AUTOPLAY";
    private static final int MAX_RETRIES = 12; // ~دقيقتين (النت بياخد وقت لما التليفزيون يفتح)

    private MediaSession session;
    private ExoPlayer player;
    private final Handler main = new Handler(Looper.getMainLooper());
    private int retries;
    private android.content.SharedPreferences prefs;

    /** كل 10 ثواني بنحفظ وصلنا لفين، عشان لو التليفزيون اتقفل فجأة نكمّل من نفس المكان. */
    private final Runnable saver = new Runnable() {
        @Override
        public void run() {
            saveNow();
            main.postDelayed(this, 10_000);
        }
    };

    private void saveNow() {
        int st = player.getPlaybackState();
        if (player.getMediaItemCount() == 0 || st == Player.STATE_IDLE || st == Player.STATE_ENDED) return;
        String server = prefs.getString("server", null);
        if (server != null) saveProgress(prefs, server, player.getCurrentPosition());
    }

    // ───────── التكملة من نفس المكان (في نفس اليوم ومن 8 لـ 1 بس) ─────────

    static int today() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        return c.get(java.util.Calendar.YEAR) * 10000 + (c.get(java.util.Calendar.MONTH) + 1) * 100
                + c.get(java.util.Calendar.DAY_OF_MONTH);
    }

    static void saveProgress(android.content.SharedPreferences p, String server, long pos) {
        p.edit().putLong("pos", Math.max(0, pos)).putInt("pos_day", today())
                .putString("pos_server", server).apply();
    }

    /** منين نبدأ: من المكان اللي وقفنا عنده النهارده لو إحنا في الفترة، وإلا من الأول. */
    static long resumePosition(android.content.Context c, android.content.SharedPreferences p, String server) {
        if (!AutoStart.inWindow(c)) return 0;
        if (p.getInt("pos_day", 0) != today() || !server.equals(p.getString("pos_server", ""))) return 0;
        return Math.max(0, p.getLong("pos", 0));
    }

    /** السورة خلصت النهارده؟ (يبقى مفيش فتح تلقائي تاني لحد بكرة) */
    static boolean doneToday(android.content.SharedPreferences p) {
        return p.getInt("done_day", 0) == today();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("player", MODE_PRIVATE);
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(Reciters.UA)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(20000);

        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(this)
                        .setDataSourceFactory(new DefaultDataSource.Factory(this, http)))
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(), /* handleAudioFocus= */ true)
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .build();
        // مفيش إعادة: لما السورة تخلص الخدمة بتقفل والبرنامج كمان
        player.setRepeatMode(Player.REPEAT_MODE_OFF);
        player.addListener(new Player.Listener() {
            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                main.removeCallbacks(saver);
                if (isPlaying) {
                    retries = 0;
                    main.postDelayed(saver, 10_000);
                } else {
                    saveNow();
                }
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_ENDED) {
                    // خلاص النهارده: المرة الجاية تبدأ من الأول، ومفيش فتح تلقائي لحد بكرة
                    prefs.edit().putInt("done_day", today()).putLong("pos", 0).apply();
                    // نسيب الشاشة تلحق تقفل الأول، وبعدين نقفل الخدمة
                    main.postDelayed(() -> {
                        if (player.getPlaybackState() != Player.STATE_ENDED) return;
                        player.clearMediaItems();
                        stopSelf();
                    }, 1500);
                }
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                // النت فصل؟ نحاول تاني بعد شوية
                if (retries >= MAX_RETRIES) return;
                retries++;
                main.postDelayed(() -> {
                    if (player.getMediaItemCount() == 0) return;
                    player.prepare();
                    player.play();
                }, Math.min(15000L, 3000L * retries));
            }
        });

        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        session = new MediaSession.Builder(this, player)
                .setSessionActivity(pi)
                .setCallback(new MediaSession.Callback() {
                    @NonNull
                    @Override
                    public ListenableFuture<List<MediaItem>> onAddMediaItems(
                            @NonNull MediaSession s, @NonNull MediaSession.ControllerInfo c,
                            @NonNull List<MediaItem> items) {
                        // الرابط نفسه محطوط في mediaId عشان يوصل سليم للخدمة
                        List<MediaItem> out = new ArrayList<>();
                        for (MediaItem m : items) {
                            out.add(m.localConfiguration != null ? m
                                    : m.buildUpon().setUri(m.mediaId).build());
                        }
                        return Futures.immediateFuture(out);
                    }
                })
                .build();
    }

    /** عنصر سورة البقرة لقارئ معيّن (الرابط في mediaId عشان يوصل سليم للخدمة). */
    static MediaItem baqarah(Reciters.Reciter r) {
        return new MediaItem.Builder()
                .setMediaId(r.baqarahUrl())
                .setUri(r.baqarahUrl())
                .setMediaMetadata(new androidx.media3.common.MediaMetadata.Builder()
                        .setTitle("سورة البقرة")
                        .setArtist(r.label())
                        .build())
                .build();
    }

    /** القارئ المحفوظ من آخر مرة (أو الافتراضي). */
    static Reciters.Reciter savedReciter(android.content.SharedPreferences prefs) {
        String server = prefs.getString("server", null);
        if (server == null) return Reciters.builtin().get(0);
        return new Reciters.Reciter(prefs.getString("name", ""), prefs.getString("moshaf", ""), server);
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        int r = super.onStartCommand(intent, flags, startId);
        if (intent != null && ACTION_AUTOPLAY.equals(intent.getAction()) && !player.getPlayWhenReady()) {
            Reciters.Reciter r = savedReciter(prefs);
            long start = resumePosition(this, prefs, r.server);
            if (player.getMediaItemCount() == 0) {
                player.setMediaItem(baqarah(r), start);
            } else {
                player.seekTo(start);
            }
            player.setRepeatMode(Player.REPEAT_MODE_OFF);
            player.prepare();
            player.play();
        }
        return r;
    }

    @Nullable
    @Override
    public MediaSession onGetSession(@NonNull MediaSession.ControllerInfo controllerInfo) {
        return session;
    }

    @Override
    public void onTaskRemoved(@Nullable Intent rootIntent) {
        if (!player.getPlayWhenReady() || player.getMediaItemCount() == 0) stopSelf();
    }

    @Override
    public void onDestroy() {
        saveNow();
        main.removeCallbacksAndMessages(null);
        if (session != null) {
            player.release();
            session.release();
            session = null;
        }
        super.onDestroy();
    }
}
