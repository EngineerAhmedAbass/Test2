package com.tv.quran;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * خدمة صغيرة بتفضل صاحية عشان تعرف إمتى شاشة التليفزيون تفتح من الاستاندباي
 * (أغلب التليفزيونات مش بتقفل خالص، فمفيش "إقلاع" جديد كل مرة).
 */
public class WatchService extends Service {

    private static final String CHANNEL = "watch";

    private final BroadcastReceiver screenOn = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            AutoStart.maybeLaunch(context);
        }
    };

    static void start(Context c) {
        Intent i = new Intent(c, WatchService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Notification n;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "التشغيل التلقائي",
                    NotificationManager.IMPORTANCE_MIN));
            n = new Notification.Builder(this, CHANNEL)
                    .setSmallIcon(R.drawable.ic_launcher_fg)
                    .setContentTitle(getString(R.string.app_name))
                    .setContentText("هيشتغل لوحده لما التليفزيون يفتح من 8 الصبح لـ 1 الضهر")
                    .build();
        } else {
            //noinspection deprecation
            n = new Notification.Builder(this)
                    .setSmallIcon(R.drawable.ic_launcher_fg)
                    .setContentTitle(getString(R.string.app_name))
                    .build();
        }
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(1, n);
            }
        } catch (Exception e) {
            stopSelf();
            return;
        }
        registerReceiver(screenOn, new IntentFilter(Intent.ACTION_SCREEN_ON));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        try {
            unregisterReceiver(screenOn);
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
