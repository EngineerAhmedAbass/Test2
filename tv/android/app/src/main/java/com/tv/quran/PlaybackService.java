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

    @Override
    public void onCreate() {
        super.onCreate();
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
                if (isPlaying) retries = 0;
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_ENDED) {
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
            android.content.SharedPreferences prefs = getSharedPreferences("player", MODE_PRIVATE);
            if (player.getMediaItemCount() == 0) {
                player.setMediaItem(baqarah(savedReciter(prefs)), 0);
            } else {
                player.seekTo(0);
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
        main.removeCallbacksAndMessages(null);
        if (session != null) {
            player.release();
            session.release();
            session = null;
        }
        super.onDestroy();
    }
}
