package com.prayer.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * زرار التحديث: بيشوف آخر نسخة على GitHub، وينزلها ويسطبها فوق القديمة.
 * الـ workflow بيرفع version.json جنب الـ APK فيه رقم آخر نسخة.
 */
final class Updater {
    private static final String BASE =
            "https://github.com/EngineerAhmedAbass/Test2/releases/download/prayer-latest/";
    static final String VERSION_URL = BASE + "version.json";
    static final String APK_URL = BASE + "Prayer-TV.apk";

    /** آخر نسخة عرفناها من الفحص (0 = لسه) */
    static volatile int latestKnown = 0;

    interface Listener {
        void onLatest(int latest);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    @SuppressWarnings("deprecation")
    static int currentVersion(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? (int) pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    /** فحص في الخلفية من غير ما يزعج (بيتعمل مرة كل يوم لما تفتح البرنامج) */
    static void checkQuietly(Context c, Listener l) {
        new Thread(() -> {
            try {
                int latest = fetchLatest();
                latestKnown = latest;
                MAIN.post(() -> l.onLatest(latest));
            } catch (Exception ignored) {
            }
        }).start();
    }

    /** لما المستخدم يدوس على زرار التحديث */
    @SuppressWarnings("deprecation")
    static void checkAndInstall(Activity a) {
        ProgressDialog pd = ProgressDialog.show(a, null, "بيدوّر على تحديث...", true, false);
        new Thread(() -> {
            try {
                int latest = fetchLatest();
                latestKnown = latest;
                MAIN.post(() -> {
                    pd.dismiss();
                    int cur = currentVersion(a);
                    if (latest <= cur) {
                        Toast.makeText(a, "عندك آخر نسخة (نسخة " + cur + ")", Toast.LENGTH_LONG).show();
                        return;
                    }
                    dialog(a).setTitle("فيه نسخة جديدة")
                            .setMessage("النسخة " + latest + " موجودة (عندك نسخة " + cur + ").\n"
                                    + "تنزلها وتسطبها دلوقتي؟ إعداداتك هتفضل زي ما هي.")
                            .setPositiveButton("نزّل", (d, w) -> startDownload(a))
                            .setNegativeButton("بعدين", null)
                            .show();
                });
            } catch (Exception e) {
                MAIN.post(() -> {
                    pd.dismiss();
                    Toast.makeText(a, "مش قادر يوصل للنت. اتأكد إن التليفزيون متوصل بالإنترنت وجرب تاني.",
                            Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private static AlertDialog.Builder dialog(Activity a) {
        return new AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert);
    }

    private static void startDownload(Activity a) {
        // أندرويد 8 وأحدث: لازم تسمح للتطبيق إنه يسطب تطبيقات
        if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
            dialog(a).setTitle("محتاج إذن واحد بس")
                    .setMessage("عشان البرنامج يقدر يسطب التحديث، فعّل \"السماح من هذا المصدر\" لـ \"مواقيت الصلاة\" "
                            + "في الصفحة اللي هتفتح، وارجع دوس تحديث تاني.")
                    .setPositiveButton("افتح الإعدادات", (d, w) -> {
                        try {
                            a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + a.getPackageName())));
                        } catch (Exception e) {
                            try {
                                a.startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
                            } catch (Exception ignored) {
                                Toast.makeText(a, "افتح الإعدادات ← الأمان ← مصادر غير معروفة وفعّل مواقيت الصلاة",
                                        Toast.LENGTH_LONG).show();
                            }
                        }
                    })
                    .setNegativeButton("إلغاء", null)
                    .show();
            return;
        }

        @SuppressWarnings("deprecation")
        ProgressDialog pd = new ProgressDialog(a);
        pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        pd.setMessage("بينزّل التحديث...");
        pd.setMax(100);
        pd.setCancelable(false);
        pd.show();

        new Thread(() -> {
            PackageInstaller.Session session = null;
            try {
                HttpURLConnection con = open(APK_URL);
                long total = con.getContentLengthLong();
                PackageInstaller pi = a.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams params =
                        new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(a.getPackageName());
                if (total > 0) params.setSize(total);
                int id = pi.createSession(params);
                session = pi.openSession(id);

                try (InputStream in = con.getInputStream();
                     OutputStream out = session.openWrite("update.apk", 0, total > 0 ? total : -1)) {
                    byte[] buf = new byte[64 * 1024];
                    long done = 0;
                    int n, lastPct = -1;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        done += n;
                        if (total > 0) {
                            int pct = (int) (done * 100 / total);
                            if (pct != lastPct) {
                                lastPct = pct;
                                MAIN.post(() -> pd.setProgress(pct));
                            }
                        }
                    }
                    session.fsync(out);
                }

                int fl = PendingIntent.FLAG_UPDATE_CURRENT
                        | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                PendingIntent result = PendingIntent.getBroadcast(a, 9,
                        new Intent(a, InstallResult.class), fl);
                session.commit(result.getIntentSender());
                session.close();
                MAIN.post(pd::dismiss);
            } catch (Exception e) {
                if (session != null) session.abandon();
                MAIN.post(() -> {
                    pd.dismiss();
                    Toast.makeText(a, "التحميل وقف. جرب تاني.", Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private static int fetchLatest() throws Exception {
        HttpURLConnection con = open(VERSION_URL + "?t=" + System.currentTimeMillis());
        try (InputStream in = con.getInputStream()) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return new JSONObject(bo.toString("UTF-8")).getInt("versionCode");
        }
    }

    /** بيفتح الرابط وبيمشي ورا التحويلات (GitHub بيحوّل لسيرفر تاني) */
    private static HttpURLConnection open(String url) throws Exception {
        for (int i = 0; i < 6; i++) {
            HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
            con.setConnectTimeout(15000);
            con.setReadTimeout(30000);
            con.setInstanceFollowRedirects(false);
            con.setRequestProperty("User-Agent", "PrayerTV");
            int code = con.getResponseCode();
            if (code >= 300 && code < 400) {
                url = new URL(new URL(url), con.getHeaderField("Location")).toString();
                con.disconnect();
                continue;
            }
            if (code != 200) throw new Exception("HTTP " + code);
            return con;
        }
        throw new Exception("too many redirects");
    }

    /** نتيجة التسطيب: لو محتاج موافقة المستخدم نفتحله شاشة التأكيد */
    public static class InstallResult extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent intent) {
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try {
                        c.startActivity(confirm);
                    } catch (Exception ignored) {
                    }
                }
            } else if (status != PackageInstaller.STATUS_SUCCESS) {
                String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                Toast.makeText(c, "التحديث ماتسطبش" + (msg != null ? ": " + msg : ""), Toast.LENGTH_LONG).show();
            }
        }
    }

    private Updater() {}
}
