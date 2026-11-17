package com.tv.quran;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;

import java.util.Calendar;

/**
 * التشغيل التلقائي: لما التليفزيون يفتح (إقلاع جديد أو صحيان من الاستاندباي)
 * وفي الوقت من 8 الصبح لحد 1 الضهر، يفتح البرنامج ويشغّل البقرة.
 */
public final class AutoStart {

    static final int FROM_HOUR = 8;  // 8:00 الصبح
    static final int TO_HOUR = 13;   // لحد 1:00 الضهر (مش شاملها)

    private AutoStart() {
    }

    static final long TEST_MS = 10 * 60 * 1000; // وضع التجربة: 10 دقايق

    /** وضع التجربة: الفتح التلقائي يشتغل في أي وقت لمدة 10 دقايق. */
    static void startTest(Context c) {
        prefs(c).edit().putLong("test_until", System.currentTimeMillis() + TEST_MS).apply();
    }

    static boolean testActive(Context c) {
        return System.currentTimeMillis() < prefs(c).getLong("test_until", 0);
    }

    private static android.content.SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("player", Context.MODE_PRIVATE);
    }

    static boolean inWindow(Context c) {
        return testActive(c) || inWindow();
    }

    static boolean inWindow() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return h >= FROM_HOUR && h < TO_HOUR;
    }

    /** أندرويد 10+ بيمنع فتح الشاشات من الخلفية إلا لو "الظهور فوق التطبيقات" مسموح. */
    static boolean canOpenScreen(Context c) {
        return Build.VERSION.SDK_INT < 29 || Settings.canDrawOverlays(c);
    }

    static void maybeLaunch(Context c) {
        if (!inWindow(c)) return;
        try {
            c.startActivity(new Intent(c, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        } catch (Exception ignored) {
        }
        if (!canOpenScreen(c)) {
            // مش هتظهر الشاشة، بس على الأقل الصوت يشتغل
            Intent i = new Intent(c, PlaybackService.class).setAction(PlaybackService.ACTION_AUTOPLAY);
            try {
                if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
                else c.startService(i);
            } catch (Exception ignored) {
            }
        }
    }

    /** بيشتغل لما التليفزيون يقلع من الأول، أو بعد تحديث البرنامج. */
    public static final class BootReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String a = intent.getAction();
            if (a == null) return;
            WatchService.start(context);
            if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) maybeLaunch(context);
        }
    }
}
