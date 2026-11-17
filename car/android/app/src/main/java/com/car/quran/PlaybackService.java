package com.car.quran;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Bundle;
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
import androidx.media3.session.LibraryResult;
import androidx.media3.session.MediaLibraryService;
import androidx.media3.session.MediaSession;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * الخدمة اللي بتشغّل الصوت. Android Auto بيكلمها مباشرة عشان يعرض القوايم
 * على شاشة العربية، وتطبيق الموبايل بيكلمها برضه.
 */
public class PlaybackService extends MediaLibraryService {

    private static final int MAX_RETRIES = 4;

    private MediaLibrarySession session;
    private ExoPlayer player;
    private Catalog catalog;
    private ListeningExecutorService bg;
    private final Handler main = new Handler(Looper.getMainLooper());
    private int retries;

    @Override
    public void onCreate() {
        super.onCreate();
        catalog = new Catalog(this);
        bg = MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(2));

        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(Catalog.UA)
                .setAllowCrossProtocolRedirects(true) // إذاعات كتير بتحوّل من http لـ https
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(20000);

        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(this)
                        .setDataSourceFactory(new DefaultDataSource.Factory(this, http)))
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(), /* handleAudioFocus= */ true)
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .build();
        player.addListener(new PlayerEvents());

        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        session = new MediaLibrarySession.Builder(this, player, new LibraryCallback())
                .setSessionActivity(pi)
                .build();

        // نجهّز القوايم في الخلفية عشان تفتح بسرعة في العربية
        bg.execute(() -> {
            catalog.children(Catalog.FAMOUS);
            catalog.children(Catalog.QURAN_RADIO);
        });
    }

    @Nullable
    @Override
    public MediaLibrarySession onGetSession(@NonNull MediaSession.ControllerInfo controllerInfo) {
        return session;
    }

    @Override
    public void onTaskRemoved(@Nullable Intent rootIntent) {
        saveState();
        if (!player.getPlayWhenReady() || player.getMediaItemCount() == 0) stopSelf();
    }

    @Override
    public void onDestroy() {
        saveState();
        main.removeCallbacksAndMessages(null);
        if (session != null) {
            session.getPlayer().release();
            session.release();
            session = null;
        }
        bg.shutdown();
        super.onDestroy();
    }

    private void saveState() {
        MediaItem cur = player.getCurrentMediaItem();
        if (cur != null) catalog.saveLast(cur.mediaId, player.getCurrentPosition());
    }

    private boolean isLive() {
        MediaItem cur = player.getCurrentMediaItem();
        return cur != null && cur.mediaId.startsWith("st|");
    }

    private final class PlayerEvents implements Player.Listener {
        @Override
        public void onIsPlayingChanged(boolean isPlaying) {
            if (isPlaying) retries = 0;
            saveState();
        }

        @Override
        public void onMediaItemTransition(@Nullable MediaItem mediaItem, int reason) {
            if (mediaItem != null) catalog.saveLast(mediaItem.mediaId, 0);
        }

        @Override
        public void onPlayerError(@NonNull PlaybackException error) {
            // النت في العربية بيقطع (أنفاق، كباري)… نحاول تاني كذا مرة
            if (retries < MAX_RETRIES) {
                retries++;
                main.postDelayed(() -> {
                    if (player.getMediaItemCount() == 0) return;
                    if (isLive()) player.seekToDefaultPosition();
                    player.prepare();
                    player.play();
                }, 2000L * retries);
            } else if (!isLive() && player.hasNextMediaItem()) {
                retries = 0;
                player.seekToNextMediaItem();
                player.prepare();
                player.play();
            }
        }
    }

    private final class LibraryCallback implements MediaLibrarySession.Callback {

        private Bundle styleExtras() {
            Bundle b = new Bundle();
            // 1 = لستة (أوضح للأسامي العربي من الشبكة)
            b.putInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT", 1);
            b.putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT", 1);
            return b;
        }

        @NonNull
        @Override
        public ListenableFuture<LibraryResult<MediaItem>> onGetLibraryRoot(
                @NonNull MediaLibrarySession s, @NonNull MediaSession.ControllerInfo browser,
                @Nullable LibraryParams params) {
            if (params != null && params.isRecent) {
                LibraryParams out = new LibraryParams.Builder()
                        .setRecent(true).setExtras(styleExtras()).build();
                return Futures.immediateFuture(LibraryResult.ofItem(
                        Catalog.folder(Catalog.RECENT, "آخر تشغيل", null,
                                androidx.media3.common.MediaMetadata.MEDIA_TYPE_FOLDER_MIXED), out));
            }
            LibraryParams out = new LibraryParams.Builder().setExtras(styleExtras()).build();
            return Futures.immediateFuture(LibraryResult.ofItem(
                    catalogRoot(), out));
        }

        private MediaItem catalogRoot() {
            return Catalog.folder(Catalog.ROOT, "قرآن وراديو", null,
                    androidx.media3.common.MediaMetadata.MEDIA_TYPE_FOLDER_MIXED);
        }

        @NonNull
        @Override
        public ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> onGetChildren(
                @NonNull MediaLibrarySession s, @NonNull MediaSession.ControllerInfo browser,
                @NonNull String parentId, int page, int pageSize, @Nullable LibraryParams params) {
            return bg.submit(() -> childrenPage(parentId, page, pageSize, params));
        }

        private LibraryResult<ImmutableList<MediaItem>> childrenPage(
                String parentId, int page, int pageSize, @Nullable LibraryParams params) {
            List<MediaItem> all = catalog.children(parentId);
            if (all == null) return LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE);
            if (pageSize <= 0 || pageSize >= all.size()) {
                return LibraryResult.ofItemList(page <= 0 ? all : new ArrayList<MediaItem>(), params);
            }
            long from = (long) Math.max(page, 0) * pageSize;
            if (from >= all.size()) return LibraryResult.ofItemList(new ArrayList<MediaItem>(), params);
            int to = (int) Math.min(all.size(), from + pageSize);
            return LibraryResult.ofItemList(all.subList((int) from, to), params);
        }

        @NonNull
        @Override
        public ListenableFuture<LibraryResult<MediaItem>> onGetItem(
                @NonNull MediaLibrarySession s, @NonNull MediaSession.ControllerInfo browser,
                @NonNull String mediaId) {
            return bg.submit(() -> oneItem(mediaId));
        }

        private LibraryResult<MediaItem> oneItem(String mediaId) {
            MediaItem item = catalog.item(mediaId);
            if (item == null) return LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE);
            return LibraryResult.ofItem(item, null);
        }

        @NonNull
        @Override
        public ListenableFuture<List<MediaItem>> onAddMediaItems(
                @NonNull MediaSession s, @NonNull MediaSession.ControllerInfo controller,
                @NonNull List<MediaItem> mediaItems) {
            return bg.submit(() -> resolveAll(mediaItems));
        }

        private List<MediaItem> resolveAll(List<MediaItem> mediaItems) {
            List<MediaItem> out = new ArrayList<>();
            for (MediaItem m : mediaItems) {
                MediaItem r = catalog.item(idOf(m));
                if (r != null && r.localConfiguration != null) out.add(r);
            }
            return out;
        }

        @NonNull
        @Override
        public ListenableFuture<MediaSession.MediaItemsWithStartPosition> onSetMediaItems(
                @NonNull MediaSession s, @NonNull MediaSession.ControllerInfo controller,
                @NonNull List<MediaItem> mediaItems, int startIndex, long startPositionMs) {
            return bg.submit(() -> {
                // اختيار سورة واحدة = نحط كل سور القارئ عشان يكمّل اللي بعدها
                if (mediaItems.size() == 1) {
                    Catalog.Playlist p = catalog.playlistFor(idOf(mediaItems.get(0)));
                    if (p != null) {
                        return new MediaSession.MediaItemsWithStartPosition(
                                p.items, p.start, startPositionMs);
                    }
                }
                List<MediaItem> out = resolveAll(mediaItems);
                if (out.isEmpty()) throw new IllegalArgumentException("nothing to play");
                int start = startIndex >= 0 && startIndex < out.size() ? startIndex : 0;
                return new MediaSession.MediaItemsWithStartPosition(out, start, startPositionMs);
            });
        }

        @NonNull
        @Override
        public ListenableFuture<MediaSession.MediaItemsWithStartPosition> onPlaybackResumption(
                @NonNull MediaSession s, @NonNull MediaSession.ControllerInfo controller) {
            return bg.submit(() -> {
                String id = catalog.lastId();
                Catalog.Playlist p = id == null ? null : catalog.playlistFor(id);
                if (p == null) p = catalog.playlistFor("st|" + firstQuranRadio());
                if (p == null) throw new IllegalStateException("nothing to resume");
                long pos = id != null && id.startsWith("s|") ? catalog.lastPosition() : C.TIME_UNSET;
                return new MediaSession.MediaItemsWithStartPosition(p.items, p.start, pos);
            });
        }

        private String firstQuranRadio() {
            List<MediaItem> l = catalog.children(Catalog.QURAN_RADIO);
            return l == null || l.isEmpty() ? "" : l.get(0).mediaId.substring(3);
        }

        /** لو جاي من بحث بالصوت ("شغّل سورة يس") نحوّله لمعرّف. */
        private String idOf(MediaItem m) {
            if (m.mediaId != null && !m.mediaId.isEmpty()) return m.mediaId;
            CharSequence q = m.requestMetadata.searchQuery;
            String id = catalog.search(q == null ? "" : q.toString());
            return id == null ? "" : id;
        }
    }
}
