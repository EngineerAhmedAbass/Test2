package com.tvremote.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Vibrator;
import android.widget.RemoteViews;

/**
 * الريموت في الإشعارات لما تخرج من التطبيق وانت متوصل:
 *   - إشعار ثابت فيه ريموت مصغّر (مقفول: سطر زراير، مفتوح: أسهم و OK وصوت وقنوات).
 *   - إشعار بشكل مشغّل الميديا (بيظهر كمان على شاشة القفل).
 * الاتصال نفسه في MainActivity؛ الخدمة دي بتخلي التطبيق شغال في الخلفية وبتبعت الزراير.
 * بيقفل لوحده بعد ساعة من غير استخدام.
 */
public class RemoteService extends Service {

    static final String CHANNEL = "remote";
    static final int ID_REMOTE = 1;
    static final int ID_MEDIA = 2;
    static final long IDLE_MS = 60 * 60 * 1000L; // ساعة

    static final String ACTION_SHOW = "com.tvremote.app.SHOW";
    static final String ACTION_KEY = "com.tvremote.app.KEY";
    static final String ACTION_CLOSE = "com.tvremote.app.CLOSE";
    static final String ACTION_REFRESH = "com.tvremote.app.REFRESH";

    static volatile boolean running;

    private MediaSession session;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable idleCheck = new Runnable() {
        public void run() {
            if (System.currentTimeMillis() - MainActivity.lastUse > IDLE_MS) {
                running = false; // عشان الـ loop مايرجعش يوصل
                MainActivity a = MainActivity.live;
                if (a != null) a.externalClose("idle");
                stopSelf();
                return;
            }
            handler.postDelayed(this, 60 * 1000L);
        }
    };

    // ------------------------------------------------------------- Static

    static void start(Context c) {
        Intent i = new Intent(c, RemoteService.class).setAction(ACTION_SHOW);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    static void stop(Context c) {
        if (!running) return;
        c.stopService(new Intent(c, RemoteService.class));
    }

    static void refresh(Context c) {
        if (!running) return;
        try {
            c.startService(new Intent(c, RemoteService.class).setAction(ACTION_REFRESH));
        } catch (Exception ignored) { }
    }

    // ---------------------------------------------------------- Lifecycle

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "الريموت في الإشعارات", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("تتحكم في التليفزيون من غير ما تفتح التطبيق");
            ch.setShowBadge(false);
            ch.setSound(null, null);
            ch.enableVibration(false);
            nm().createNotificationChannel(ch);
        }
        session = new MediaSession(this, "TvRemote");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { key(85); }
            @Override public void onPause() { key(85); }
            @Override public void onCustomAction(String action, Bundle extras) {
                if ("vol_down".equals(action)) key(25);
                else if ("vol_up".equals(action)) key(24);
                else if ("mute".equals(action)) key(164);
                else if ("power".equals(action)) key(26);
            }
        });
        session.setActive(true);
        handler.postDelayed(idleCheck, 60 * 1000L);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_SHOW : intent.getAction();
        if (!running && !ACTION_SHOW.equals(action) && !ACTION_KEY.equals(action) && !ACTION_REFRESH.equals(action)) return START_NOT_STICKY;
        if (ACTION_KEY.equals(action)) {
            key(intent.getIntExtra("code", 0));
        } else if (ACTION_CLOSE.equals(action)) {
            running = false; // عشان الـ loop مايرجعش يوصل
            MainActivity a = MainActivity.live;
            if (a != null) a.externalClose("user");
            stopSelf();
            return START_NOT_STICKY;
        }
        show();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        handler.removeCallbacks(idleCheck);
        try { nm().cancel(ID_MEDIA); } catch (Exception ignored) { }
        if (session != null) {
            session.setActive(false);
            session.release();
        }
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
        super.onDestroy();
    }

    private NotificationManager nm() {
        return (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    }

    // ------------------------------------------------------------ Keys

    private void key(int code) {
        if (code == 0) return;
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null && getSharedPreferences("remote", MODE_PRIVATE).getBoolean("vibrate", true)) v.vibrate(12);
        } catch (Exception ignored) { }
        MainActivity a = MainActivity.live;
        if (a != null) a.external(code);
    }

    private PendingIntent keyIntent(int code) {
        Intent i = new Intent(this, RemoteService.class).setAction(ACTION_KEY).putExtra("code", code);
        return PendingIntent.getService(this, 1000 + code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private PendingIntent closeIntent() {
        Intent i = new Intent(this, RemoteService.class).setAction(ACTION_CLOSE);
        return PendingIntent.getService(this, 999, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private PendingIntent openIntent() {
        Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 998, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    // ---------------------------------------------------- Notifications

    private void show() {
        String name = MainActivity.nName == null || MainActivity.nName.isEmpty() ? "التليفزيون" : MainActivity.nName;
        String state = MainActivity.nState;
        String status = "connected".equals(state) ? "متوصل" : "connecting".equals(state) ? "بيوصل…" : "مش متوصل";
        String detail = MainActivity.nVolume >= 0
                ? (MainActivity.nMuted ? "مكتوم" : "الصوت " + MainActivity.nVolume) : status;

        // 1) الريموت المصغّر
        RemoteViews small = new RemoteViews(getPackageName(), R.layout.notif_small);
        RemoteViews big = new RemoteViews(getPackageName(), R.layout.notif_big);
        bind(small, R.id.n_power, 26);
        bind(small, R.id.n_vol_down, 25);
        bind(small, R.id.n_vol_up, 24);
        bind(small, R.id.n_mute, 164);
        bind(small, R.id.n_play, 85);
        small.setOnClickPendingIntent(R.id.n_close, closeIntent());

        big.setTextViewText(R.id.n_title, name + "  •  " + detail);
        bind(big, R.id.n_up, 19);
        bind(big, R.id.n_down, 20);
        bind(big, R.id.n_left, 21);
        bind(big, R.id.n_right, 22);
        bind(big, R.id.n_ok, 23);
        bind(big, R.id.n_vol_up, 24);
        bind(big, R.id.n_vol_down, 25);
        bind(big, R.id.n_ch_up, 166);
        bind(big, R.id.n_ch_down, 167);
        bind(big, R.id.n_mute, 164);
        bind(big, R.id.n_play, 85);
        bind(big, R.id.n_back, 4);
        bind(big, R.id.n_home, 3);
        bind(big, R.id.n_menu, 82);
        bind(big, R.id.n_power, 26);
        big.setOnClickPendingIntent(R.id.n_open, openIntent());
        big.setOnClickPendingIntent(R.id.n_close, closeIntent());

        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_n_tv)
                .setContentTitle(name)
                .setContentText(status)
                .setSubText(name + " • " + status)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(openIntent())
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setCustomContentView(small)
                .setCustomBigContentView(big)
                .setStyle(new Notification.DecoratedCustomViewStyle());
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_LOW);
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        Notification n = b.build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID_REMOTE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(ID_REMOTE, n);
        }

        // 2) شكل مشغّل الميديا (والشاشة القفل)
        try {
            showMedia(name, status, detail);
        } catch (Exception ignored) { }
    }

    private void bind(RemoteViews v, int id, int code) {
        v.setOnClickPendingIntent(id, keyIntent(code));
    }

    private void showMedia(String name, String status, String detail) {
        session.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, name)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "ريموت TV • " + detail)
                .build());
        // في أندرويد 13+ زراير مشغّل الميديا بتيجي من هنا
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE)
                .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 0)
                .addCustomAction(new PlaybackState.CustomAction.Builder("vol_down", "صوت -", R.drawable.ic_n_vol_down).build())
                .addCustomAction(new PlaybackState.CustomAction.Builder("vol_up", "صوت +", R.drawable.ic_n_vol_up).build())
                .addCustomAction(new PlaybackState.CustomAction.Builder("mute", "كتم", R.drawable.ic_n_mute).build())
                .addCustomAction(new PlaybackState.CustomAction.Builder("power", "تشغيل", R.drawable.ic_n_power).build())
                .build());

        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_n_tv)
                .setContentTitle(name)
                .setContentText("ريموت TV • " + detail)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(openIntent())
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(action(R.drawable.ic_n_vol_down, "صوت -", 25))
                .addAction(action(R.drawable.ic_n_power, "تشغيل", 26))
                .addAction(action(R.drawable.ic_n_play, "تشغيل/إيقاف", 85))
                .addAction(action(R.drawable.ic_n_mute, "كتم", 164))
                .addAction(action(R.drawable.ic_n_vol_up, "صوت +", 24))
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(session.getSessionToken())
                        .setShowActionsInCompactView(0, 2, 4));
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_LOW);
        nm().notify(ID_MEDIA, b.build());
    }

    private Notification.Action action(int icon, String title, int code) {
        return new Notification.Action.Builder(Icon.createWithResource(this, icon), title, keyIntent(code)).build();
    }
}
