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

    private static final int MAX_RETRIES = 5;

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
        // البقرة تتعاد تاني لوحدها لما تخلص
        player.setRepeatMode(Player.REPEAT_MODE_ONE);
        player.addListener(new Player.Listener() {
            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                if (isPlaying) retries = 0;
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
                }, 3000L * retries);
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
