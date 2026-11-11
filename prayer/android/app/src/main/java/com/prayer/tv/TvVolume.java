package com.prayer.tv;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * رفع صوت التليفزيون نفسه وقت الأذان.
 *
 * البوكس (زي Mi Box) صوته ثابت على HDMI، والتليفزيون هو اللي بيتحكم في العلو.
 * لما تطبيق يطلب رفع الصوت على جهاز زي ده، أندرويد (10 وأحدث) بيبعت أمر رفع الصوت
 * للتليفزيون عن طريق HDMI-CEC، زي ما ريموت البوكس بيعمل. بنرفع كام درجة قبل الأذان
 * وبنرجّعهم بعده.
 */
final class TvVolume {
    private static final ExecutorService QUEUE = Executors.newSingleThreadExecutor();
    /** عدد الدرجات اللي رفعناها ولسه مارجعناهاش */
    private static int raised = 0;

    /** بوكس متوصل HDMI ومفيهوش سماعة جواه (التليفزيون بيتحكم في الصوت) */
    static boolean isHdmiBox(Context c) {
        if (Build.VERSION.SDK_INT < 23) return false;
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return false;
        boolean hdmi = false, speaker = false;
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            int t = d.getType();
            if (t == AudioDeviceInfo.TYPE_HDMI || t == AudioDeviceInfo.TYPE_HDMI_ARC) hdmi = true;
            if (t == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) speaker = true;
        }
        return hdmi && !speaker;
    }

    static synchronized void raise(Context c, int steps) {
        if (raised > 0 || steps <= 0 || !isHdmiBox(c)) return;
        raised = steps;
        send(c.getApplicationContext(), AudioManager.ADJUST_RAISE, steps);
    }

    static synchronized void restore(Context c) {
        int n = raised;
        raised = 0;
        if (n > 0) send(c.getApplicationContext(), AudioManager.ADJUST_LOWER, n);
    }

    private static void send(Context c, int direction, int steps) {
        QUEUE.execute(() -> {
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            for (int i = 0; i < steps; i++) {
                try {
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0);
                    // التليفزيون محتاج وقت بين كل أمر والتاني
                    Thread.sleep(180);
                } catch (Exception ignored) {
                }
            }
        });
    }

    private TvVolume() {}
}
