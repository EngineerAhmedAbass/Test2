package com.shella.alwan;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.webkit.JavascriptInterface;

/** Functions the game page can call as window.Android.*. */
final class Bridge {
    private final Activity activity;

    Bridge(Activity activity) {
        this.activity = activity;
    }

    @JavascriptInterface
    public void share(String text) {
        activity.runOnUiThread(new ShareTask(activity, text));
    }

    @JavascriptInterface
    public int versionCode() {
        try {
            return activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    /** Opens a link (the new APK) in the phone's browser, which downloads it. */
    @JavascriptInterface
    public void openUrl(String url) {
        if (url == null || !url.startsWith("https://github.com/")) return;
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            activity.startActivity(i);
        } catch (Exception ignored) {
        }
    }

    @JavascriptInterface
    public void vibrate(int ms) {
        Vibrator v = (Vibrator) activity.getSystemService(Context.VIBRATOR_SERVICE);
        if (v == null) return;
        if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
        else v.vibrate(ms);
    }

    private static final class ShareTask implements Runnable {
        private final Activity activity;
        private final String text;

        ShareTask(Activity activity, String text) {
            this.activity = activity;
            this.text = text;
        }

        @Override
        public void run() {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_TEXT, text);
            activity.startActivity(Intent.createChooser(i, "\u0634\u0627\u0631\u0643 \u0627\u0644\u0643\u0648\u062f"));
        }
    }
}
