package com.prayer.tv;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.media.audiofx.LoudnessEnhancer;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/** بيشغل صوت الأذان في الخلفية (حتى لو شغال تطبيق تاني على التليفزيون) */
public class AdhanService extends Service implements AudioManager.OnAudioFocusChangeListener {
    static final String ACTION_STOP = "com.prayer.tv.STOP";
    private static final String CHANNEL = "adhan";
    private static final int NOTIF_ID = 7;

    /** الصلاة اللي بيتأذن لها دلوقتي (-1 = مفيش) */
    static volatile int playing = -1;

    private MediaPlayer mp;
    private PowerManager.WakeLock wake;
    private AudioFocusRequest focusReq;
    private int savedVolume = -1;
    private LoudnessEnhancer booster;

    static void start(Context c, int prayer) {
        Intent i = new Intent(c, AdhanService.class).putExtra("p", prayer);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    static void stop(Context c) {
        try {
            c.startService(new Intent(c, AdhanService.class).setAction(ACTION_STOP));
        } catch (Exception e) {
            c.stopService(new Intent(c, AdhanService.class));
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        int p = intent.getIntExtra("p", PrayTimes.DHUHR);
        Notification n = notification(p);
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIF_ID, n);
        }
        releasePlayer();
        playing = p;
        wakeUp();
        if (!play(p)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (Prefs.showScreen(this)) {
            try {
                startActivity(new Intent(this, AdhanActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        .putExtra("p", p));
            } catch (Exception ignored) {
                // بعض الأجهزة بتمنع فتح شاشة من الخلفية — الصوت شغال برضه
            }
        }
        AdhanActivity.refresh();
        return START_NOT_STICKY;
    }

    private boolean play(int p) {
        mp = new MediaPlayer();
        try {
            int res = getResources().getIdentifier(Prefs.voiceFile(this, p), "raw", getPackageName());
            if (res == 0) res = getResources().getIdentifier("adhan", "raw", getPackageName());
            if (res != 0) {
                AssetFileDescriptor afd = getResources().openRawResourceFd(res);
                mp.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
                afd.close();
            } else {
                // مفيش ملف أذان جوه التطبيق: نستخدم صوت المنبه بتاع الجهاز
                Uri u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
                if (u == null) u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
                if (u == null) return false;
                mp.setDataSource(this, u);
            }
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            mp.setOnCompletionListener(m -> stopSelf());
            mp.setOnErrorListener((m, w, e) -> {
                stopSelf();
                return true;
            });
            mp.prepare();
            requestFocus();
            applyVolume();
            TvVolume.raise(this, Prefs.TV_STEPS[Prefs.tvSteps(this)]);
            mp.start();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * صوت الأذان:
     * - لو صوت الجهاز بيتغير (زي MiTV Box): بنحطه على الرقم اللي المستخدم اختاره (1..15)
     *   وبنرجّعه زي ما كان بعد الأذان.
     * - لو صوت الجهاز ثابت (بوكس HDMI قديم): بنعلّي الصوت نفسه بـ LoudnessEnhancer.
     */
    private void applyVolume() {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am == null) return;
        boolean fixed = Build.VERSION.SDK_INT >= 21 && am.isVolumeFixed();
        if (!fixed) {
            int v = Prefs.adhanVolume(this, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            if (v <= 0) return; // "زي صوت الجهاز"
            try {
                // أول مرة بس في الأذان ده: لو المستخدم بيجرب كذا مستوى ورا بعض نرجع للأصلي
                if (savedVolume < 0) savedVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0);
            } catch (Exception e) {
                savedVolume = -1;
            }
            return;
        }
        int level = Prefs.loud(this);
        if (level <= 0) return;
        try {
            booster = new LoudnessEnhancer(mp.getAudioSessionId());
            booster.setTargetGain(Prefs.LOUD_BOOST_MB[level]);
            booster.setEnabled(true);
        } catch (Exception e) {
            booster = null;
        }
    }

    private void requestFocus() {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am == null) return;
        // ده بيخلي التطبيقات التانية (يوتيوب، IPTV...) توقف أو توطي صوتها وقت الأذان
        if (Build.VERSION.SDK_INT >= 26) {
            focusReq = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setOnAudioFocusChangeListener(this)
                    .build();
            am.requestAudioFocus(focusReq);
        } else {
            am.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
        }
    }

    @Override
    public void onAudioFocusChange(int focusChange) {
        // الأذان يكمل حتى لو تطبيق تاني طلب الصوت
    }

    @SuppressWarnings("deprecation")
    private void wakeUp() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wake = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                    | PowerManager.ACQUIRE_CAUSES_WAKEUP, "prayer:adhan");
            wake.acquire(10 * 60000L);
        } catch (Exception ignored) {
        }
    }

    private Notification notification(int p) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent stop = PendingIntent.getService(this, 3,
                new Intent(this, AdhanService.class).setAction(ACTION_STOP), fl);
        PendingIntent open = PendingIntent.getActivity(this, 4,
                new Intent(this, AdhanActivity.class).putExtra("p", p), fl);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL, "الأذان", NotificationManager.IMPORTANCE_HIGH);
                ch.setSound(null, null);
                nm.createNotificationChannel(ch);
            }
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this).setPriority(Notification.PRIORITY_MAX);
        }
        return b.setSmallIcon(R.drawable.ic_notif)
                .setContentTitle("أذان " + PrayTimes.NAMES[p])
                .setContentText("حان الآن موعد أذان " + PrayTimes.NAMES[p] + " — " + Prefs.city(this).name)
                .setCategory(Notification.CATEGORY_ALARM)
                .setOngoing(true)
                .setContentIntent(open)
                .setFullScreenIntent(open, true)
                .addAction(new Notification.Action.Builder(0, "إيقاف", stop).build())
                .build();
    }

    private void releasePlayer() {
        if (booster != null) {
            try {
                booster.release();
            } catch (Exception ignored) {
            }
            booster = null;
        }
        if (mp != null) {
            try {
                mp.stop();
            } catch (Exception ignored) {
            }
            mp.release();
            mp = null;
        }
    }

    @Override
    public void onDestroy() {
        releasePlayer();
        playing = -1;
        TvVolume.restore(this);
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am != null) {
            if (savedVolume >= 0) {
                try {
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, savedVolume, 0);
                } catch (Exception ignored) {
                }
            }
            if (Build.VERSION.SDK_INT >= 26 && focusReq != null) am.abandonAudioFocusRequest(focusReq);
            else am.abandonAudioFocus(this);
        }
        if (wake != null && wake.isHeld()) wake.release();
        AdhanActivity.closeIfOpen();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
