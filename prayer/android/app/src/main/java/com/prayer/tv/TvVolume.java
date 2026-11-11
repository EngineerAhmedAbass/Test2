package com.prayer.tv;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.SystemClock;
import android.view.KeyEvent;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * رفع صوت التليفزيون نفسه وقت الأذان.
 *
 * البوكس (زي Mi Box) صوته ثابت على HDMI، والتليفزيون هو اللي بيتحكم في العلو.
 * أندرويد بيقدر يبعت أمر رفع الصوت للتليفزيون عن طريق HDMI-CEC، بس الطريقة اللي
 * بتشتغل بتختلف من جهاز للتاني، فعندنا كذا طريقة والمستخدم بيجربهم من الإعدادات.
 */
final class TvVolume {
    /** FLAG_FROM_KEY مخفي في أندرويد: بيخلي الطلب يتعامل كأنه من زرار الريموت */
    private static final int FLAG_FROM_KEY = 1 << 12;

    static final String[] METHOD_NAMES = {
            "الطريقة 1 (صوت الميديا)",
            "الطريقة 2 (زي زرار الريموت)",
            "الطريقة 3 (ضغطة زرار صوت)",
            "الطريقة 4 (الصوت العام)",
    };

    private static final ExecutorService QUEUE = Executors.newSingleThreadExecutor();
    /** عدد الدرجات اللي رفعناها ولسه مارجعناهاش */
    private static int raised = 0;
    private static int raisedMethod = 0;

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

    /** معلومات للتشخيص في الإعدادات */
    static String info(Context c) {
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        StringBuilder s = new StringBuilder("أندرويد " + Build.VERSION.RELEASE + " • " + Build.MODEL);
        if (am != null) {
            s.append("\nصوت الجهاز: ").append(am.getStreamVolume(AudioManager.STREAM_MUSIC))
                    .append(" من ").append(am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            if (Build.VERSION.SDK_INT >= 21) s.append(am.isVolumeFixed() ? " (ثابت)" : "");
            if (Build.VERSION.SDK_INT >= 23) {
                s.append("\nالخرج: ");
                for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                    s.append(typeName(d.getType())).append("  ");
                }
            }
        }
        return s.toString();
    }

    private static String typeName(int t) {
        switch (t) {
            case 9: return "HDMI";
            case 10: return "HDMI-ARC";
            case 2: return "سماعة داخلية";
            case 8: return "بلوتوث";
            case 3: case 4: return "سماعة رأس";
            case 11: case 22: return "USB";
            default: return "نوع " + t;
        }
    }

    /** قبل الأذان: لو المستخدم اختار طريقة، نستخدمها؛ وإلا نجرب الأولى على البوكس بس */
    static synchronized void raise(Context c, int steps) {
        if (raised > 0 || steps <= 0) return;
        int m = Prefs.tvMethod(c);
        if (m < 0) {
            if (!isHdmiBox(c)) return;
            m = 0;
        }
        raised = steps;
        raisedMethod = m;
        send(c.getApplicationContext(), m, true, steps);
    }

    static synchronized void restore(Context c) {
        int n = raised;
        raised = 0;
        if (n > 0) send(c.getApplicationContext(), raisedMethod, false, n);
    }

    /** للتجربة من الإعدادات */
    static void test(Context c, int method, boolean up, int steps) {
        send(c.getApplicationContext(), method, up, steps);
    }

    private static void send(Context c, int method, boolean up, int steps) {
        QUEUE.execute(() -> {
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            int dir = up ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER;
            for (int i = 0; i < steps; i++) {
                try {
                    switch (method) {
                        case 1:
                            am.adjustSuggestedStreamVolume(dir, AudioManager.USE_DEFAULT_STREAM_TYPE,
                                    AudioManager.FLAG_SHOW_UI | FLAG_FROM_KEY);
                            break;
                        case 2: {
                            int code = up ? KeyEvent.KEYCODE_VOLUME_UP : KeyEvent.KEYCODE_VOLUME_DOWN;
                            long now = SystemClock.uptimeMillis();
                            am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0));
                            am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0));
                            break;
                        }
                        case 3:
                            am.adjustVolume(dir, AudioManager.FLAG_SHOW_UI);
                            break;
                        default:
                            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI);
                    }
                    // التليفزيون محتاج وقت بين كل أمر والتاني
                    Thread.sleep(200);
                } catch (Exception ignored) {
                }
            }
        });
    }

    private TvVolume() {}
}
