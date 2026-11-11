package com.prayer.tv;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;

/** شاشة "حان الآن موعد الأذان" وفيها زرار إيقاف */
public class AdhanActivity extends Activity {
    private static WeakReference<AdhanActivity> current = new WeakReference<>(null);
    private TextView title;

    static void closeIfOpen() {
        AdhanActivity a = current.get();
        if (a != null) a.runOnUiThread(a::finish);
    }

    static void refresh() {
        AdhanActivity a = current.get();
        if (a != null) a.runOnUiThread(a::bind);
    }

    @SuppressWarnings("deprecation")
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        int pad = Ui.dp(this, 32);
        root.setPadding(pad, pad, pad, pad);

        title = Ui.text(this, "", 44, Ui.GOLD, true);
        root.addView(title);
        TextView sub = Ui.text(this, "", 22, Ui.TEXT, false);
        sub.setTag("sub");
        root.addView(sub);

        Button stop = new Button(this);
        stop.setText("إيقاف الأذان");
        stop.setTextSize(24);
        stop.setTextColor(Ui.NEXT_TEXT);
        stop.setAllCaps(false);
        stop.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        stop.setBackground(Ui.focusBg(this, Ui.GOLD));
        int h = Ui.dp(this, 18), v = Ui.dp(this, 12);
        stop.setPadding(h * 2, v, h * 2, v);
        stop.setOnClickListener(x -> {
            AdhanService.stop(this);
            finish();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        // الكلام فوق والزرار تحت، والكعبة اللي في الصورة تبان في النص
        root.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1f));
        root.addView(stop, lp);
        setContentView(Ui.withHaramBackground(this, root));
        stop.requestFocus();
        current = new WeakReference<>(this);
        bind();
    }

    private void bind() {
        int p = AdhanService.playing;
        if (p < 0) p = getIntent().getIntExtra("p", PrayTimes.DHUHR);
        title.setText("حان الآن موعد أذان " + PrayTimes.NAMES[p]);
        TextView sub = getWindow().getDecorView().findViewWithTag("sub");
        if (sub != null) {
            sub.setText(Prefs.city(this).name + "  •  "
                    + Ui.time(System.currentTimeMillis(), Prefs.zone(this), false));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // لو الأذان خلص قبل ما الشاشة تفتح
        if (AdhanService.playing < 0) finish();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            AdhanService.stop(this);
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        if (current.get() == this) current = new WeakReference<>(null);
        super.onDestroy();
    }
}
