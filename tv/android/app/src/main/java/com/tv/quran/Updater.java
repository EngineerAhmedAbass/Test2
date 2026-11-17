package com.tv.quran;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * التحديث التلقائي: بيشوف آخر نسخة على GitHub، ولو أجدد بينزّلها ويطلب التثبيت.
 * (version.json و Quran-TV.apk بيترفعوا مع كل نسخة جديدة.)
 */
final class Updater {

    private static final String BASE =
            "https://github.com/EngineerAhmedAbass/Test2/releases/download/tv-latest/";
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    interface Listener {
        /** رسالة تتعرض للمستخدم. sticky = تفضل ظاهرة لحد رسالة تانية. */
        void onMessage(String text, boolean sticky);
    }

    private Updater() {
    }

    static long installedVersion(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            //noinspection deprecation
            return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    static void check(Activity activity, boolean manual, Listener l) {
        if (!RUNNING.compareAndSet(false, true)) {
            if (manual) l.onMessage("بيدوّر على تحديث دلوقتي…", false);
            return;
        }
        final Context ctx = activity.getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                run(ctx, manual, (t, s) -> main.post(() -> l.onMessage(t, s)));
            } finally {
                RUNNING.set(false);
            }
        }).start();
    }

    private static void run(Context ctx, boolean manual, Listener l) {
        if (manual) l.onMessage("بيدوّر على تحديث…", true);
        long remote = -1;
        String json = get(BASE + "version.json?t=" + System.currentTimeMillis());
        if (json != null) {
            try {
                remote = new JSONObject(json.trim()).getLong("versionCode");
            } catch (Exception ignored) {
            }
        }
        long local = installedVersion(ctx);
        if (remote < 0) {
            if (manual) l.onMessage("مقدرتش أوصل للتحديثات… اتأكد من النت", false);
            return;
        }
        if (remote <= local) {
            if (manual) l.onMessage("✓ عندك آخر نسخة (" + local + ")", false);
            return;
        }

        l.onMessage("⬇ في نسخة جديدة (" + remote + ")… بيتم التحميل", true);
        File apk = new File(ctx.getCacheDir(), "update.apk");
        if (!download(BASE + "Quran-TV.apk", apk, l)) {
            l.onMessage("التحميل وقف… هيحاول تاني المرة الجاية", false);
            return;
        }
        l.onMessage("التحديث جاهز… اضغط \"تثبيت\"", false);
        try {
            install(ctx, apk);
        } catch (Exception e) {
            l.onMessage("مقدرش يثبّت التحديث: " + e.getMessage(), false);
        }
    }

    private static void install(Context ctx, File apk) throws Exception {
        PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        int id = pi.createSession(params);
        PackageInstaller.Session session = pi.openSession(id);
        try {
            try (InputStream in = new FileInputStream(apk);
                 OutputStream out = session.openWrite("app.apk", 0, apk.length())) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                session.fsync(out);
            }
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
            PendingIntent done = PendingIntent.getBroadcast(ctx, id,
                    new Intent(ctx, InstallReceiver.class), flags);
            session.commit(done.getIntentSender());
        } finally {
            session.close();
        }
    }

    /** النظام بيرجع هنا: يا إما محتاج موافقة المستخدم، يا إما خلص. */
    public static final class InstallReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                //noinspection deprecation
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try {
                        context.startActivity(confirm);
                    } catch (Exception ignored) {
                    }
                }
            }
        }
    }

    private static boolean download(String url, File to, Listener l) {
        HttpURLConnection c = null;
        File tmp = new File(to.getPath() + ".part");
        try {
            c = open(url);
            if (c.getResponseCode() != 200) return false;
            long total = c.getContentLength();
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[65536];
                long got = 0;
                int lastPct = -1, n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    got += n;
                    if (total > 0) {
                        int pct = (int) (got * 100 / total);
                        if (pct / 10 != lastPct / 10) {
                            lastPct = pct;
                            l.onMessage("⬇ بيتم تحميل التحديث… " + pct + "%", true);
                        }
                    }
                }
            }
            return tmp.renameTo(to) || (to.delete() && tmp.renameTo(to));
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String get(String url) {
        HttpURLConnection c = null;
        try {
            c = open(url);
            if (c.getResponseCode() != 200) return null;
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            }
            return new String(b.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setUseCaches(false);
        c.setRequestProperty("User-Agent", Reciters.UA);
        return c;
    }
}
