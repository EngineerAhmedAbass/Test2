package com.prayer.tv;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * بيستقبل منبه الأذان، وكمان تشغيل الجهاز وتغيير الساعة عشان يعيد جدولة الأذان الجاي.
 */
public class AlarmReceiver extends BroadcastReceiver {
    static final String ACTION_ADHAN = "com.prayer.tv.ADHAN";
    /** لو الجهاز صحي متأخر أكتر من كده (كان مقفول مثلًا) مش هنأذن متأخر */
    private static final long MAX_LATE = 10 * 60000L;

    @Override
    public void onReceive(Context c, Intent intent) {
        long now = System.currentTimeMillis();
        if (ACTION_ADHAN.equals(intent.getAction())) {
            int p = intent.getIntExtra("p", -1);
            long t = intent.getLongExtra("t", now);
            if (p >= 0 && now - t < MAX_LATE && Prefs.enabled(c, p)) {
                AdhanService.start(c, p);
            }
            schedule(c, Math.max(now, t) + 1000);
        } else {
            schedule(c, now);
        }
    }

    private static int flags() {
        return PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
    }

    /** يجدول منبه للأذان الجاي بعد after */
    static void schedule(Context c, long after) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        long[] next = Prefs.next(c, after, true);
        Intent i = new Intent(c, AlarmReceiver.class).setAction(ACTION_ADHAN);
        if (next == null) {
            am.cancel(PendingIntent.getBroadcast(c, 1, i, flags()));
            return;
        }
        i.putExtra("p", (int) next[0]).putExtra("t", next[1]);
        PendingIntent pi = PendingIntent.getBroadcast(c, 1, i, flags());
        try {
            PendingIntent show = PendingIntent.getActivity(c, 2, new Intent(c, MainActivity.class), flags());
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(next[1], show), pi);
        } catch (Exception e) {
            // لو الجهاز مش سامح بالمنبهات الدقيقة
            if (Build.VERSION.SDK_INT >= 23) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next[1], pi);
            else am.set(AlarmManager.RTC_WAKEUP, next[1], pi);
        }
    }
}
