package com.car.quran;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.session.LibraryResult;
import androidx.media3.session.MediaBrowser;
import androidx.media3.session.SessionToken;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/** شاشة الموبايل: نفس القوايم اللي بتظهر في العربية + شريط تشغيل تحت. */
public class MainActivity extends Activity {

    private static final int BG = 0xFF0B1512, CARD = 0xFF13211C, GOLD = 0xFFD4AF37,
            TEXT = 0xFFF2F2EE, MUTED = 0xFF9AA8A2;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Executor mainExec = handler::post;

    private ListenableFuture<MediaBrowser> browserFuture;
    private MediaBrowser browser;

    /** مسار التصفح: معرّف + عنوان لكل مستوى. */
    private final ArrayList<String[]> stack = new ArrayList<>();
    private final List<MediaItem> items = new ArrayList<>();
    private int loadToken;

    private TextView title, back, status, nowTitle, nowSub, playBtn;
    private ItemsAdapter adapter;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        stack.add(new String[]{Catalog.ROOT, getString(R.string.app_name)});
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        SessionToken token = new SessionToken(this, new ComponentName(this, PlaybackService.class));
        final ListenableFuture<MediaBrowser> future = new MediaBrowser.Builder(this, token).buildAsync();
        browserFuture = future;
        future.addListener(() -> {
            if (future != browserFuture) return; // الشاشة اتقفلت قبل ما يوصل
            try {
                browser = future.get();
            } catch (Exception e) {
                status.setText("حصلت مشكلة في تشغيل الخدمة");
                return;
            }
            browser.addListener(new Player.Listener() {
                @Override
                public void onEvents(Player player, Player.Events events) {
                    updateNowPlaying();
                }
            });
            updateNowPlaying();
            load();
        }, mainExec);
    }

    @Override
    protected void onStop() {
        if (browserFuture != null) MediaBrowser.releaseFuture(browserFuture);
        browser = null;
        browserFuture = null;
        super.onStop();
    }

    @Override
    public void onBackPressed() {
        if (stack.size() > 1) {
            stack.remove(stack.size() - 1);
            load();
        } else {
            super.onBackPressed();
        }
    }

    // ───────────────────────── تحميل القوايم ─────────────────────────

    private void load() {
        String[] top = stack.get(stack.size() - 1);
        title.setText(top[1]);
        back.setVisibility(stack.size() > 1 ? View.VISIBLE : View.INVISIBLE);
        items.clear();
        adapter.notifyDataSetChanged();
        status.setText("جاري التحميل…");
        status.setVisibility(View.VISIBLE);
        if (browser == null) return;

        final int token = ++loadToken;
        ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> f =
                browser.getChildren(top[0], 0, Integer.MAX_VALUE, null);
        f.addListener(() -> {
            if (token != loadToken) return;
            LibraryResult<ImmutableList<MediaItem>> r;
            try {
                r = f.get();
            } catch (Exception e) {
                r = null;
            }
            if (r == null || r.resultCode != LibraryResult.RESULT_SUCCESS || r.value == null) {
                status.setText("مفيش نت؟ اتأكد من الاتصال وارجع تاني");
                return;
            }
            items.addAll(r.value);
            adapter.notifyDataSetChanged();
            status.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
            status.setText("القايمة فاضية");
        }, mainExec);
    }

    private void onItemClick(MediaItem item) {
        if (Boolean.TRUE.equals(item.mediaMetadata.isBrowsable)) {
            stack.add(new String[]{item.mediaId, String.valueOf(item.mediaMetadata.title)});
            load();
        } else if (browser != null) {
            browser.setMediaItem(item);
            browser.prepare();
            browser.play();
        }
    }

    private void updateNowPlaying() {
        if (browser == null) return;
        MediaItem cur = browser.getCurrentMediaItem();
        if (cur == null) {
            nowTitle.setText("اختار سورة أو إذاعة");
            nowSub.setText("");
        } else {
            MediaMetadata md = browser.getMediaMetadata();
            CharSequence t = md.displayTitle != null ? md.displayTitle : md.title;
            if (t == null) t = cur.mediaMetadata.title;
            nowTitle.setText(t);
            CharSequence a = md.artist != null ? md.artist : cur.mediaMetadata.artist;
            int st = browser.getPlaybackState();
            nowSub.setText(st == Player.STATE_BUFFERING ? "جاري التحميل…" : a == null ? "" : a);
        }
        playBtn.setText(browser.isPlaying() || browser.getPlayWhenReady() ? "⏸" : "▶");
    }

    // ───────────────────────── الواجهة ─────────────────────────

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    private TextView text(float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextDirection(View.TEXT_DIRECTION_RTL);
        return t;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        // الشريط اللي فوق
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), dp(14), dp(8), dp(14));
        title = text(22, GOLD, true);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        back = text(17, TEXT, false);
        back.setText("رجوع ←");
        back.setPadding(dp(14), dp(8), dp(14), dp(8));
        back.setOnClickListener(v -> onBackPressed());
        bar.addView(back);
        root.addView(bar);

        status = text(16, MUTED, false);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(16), dp(32), dp(16), dp(32));
        root.addView(status);

        ListView list = new ListView(this);
        list.setDivider(null);
        list.setPadding(dp(10), 0, dp(10), dp(10));
        list.setClipToPadding(false);
        adapter = new ItemsAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> onItemClick(items.get(pos)));
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // شريط التشغيل اللي تحت
        LinearLayout player = new LinearLayout(this);
        player.setGravity(Gravity.CENTER_VERTICAL);
        player.setPadding(dp(16), dp(10), dp(8), dp(10));
        player.setBackgroundColor(CARD);
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        nowTitle = text(17, TEXT, true);
        nowTitle.setSingleLine(true);
        nowTitle.setEllipsize(TextUtils.TruncateAt.END);
        nowTitle.setText("اختار سورة أو إذاعة");
        nowSub = text(14, MUTED, false);
        nowSub.setSingleLine(true);
        info.addView(nowTitle);
        info.addView(nowSub);
        player.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        // في الاتجاه العربي: "السابق" على اليمين و"التالي" على الشمال
        player.addView(control("⏮", v -> {
            if (browser != null) browser.seekToPrevious();
        }));
        playBtn = control("▶", v -> {
            if (browser == null) return;
            if (browser.isPlaying() || browser.getPlayWhenReady()) browser.pause();
            else {
                if (browser.getPlaybackState() == Player.STATE_IDLE) browser.prepare();
                browser.play();
            }
        });
        playBtn.setTextColor(GOLD);
        player.addView(playBtn);
        player.addView(control("⏭", v -> {
            if (browser != null) browser.seekToNext();
        }));
        root.addView(player);

        setContentView(root);
    }

    private TextView control(String label, View.OnClickListener l) {
        TextView t = text(26, TEXT, false);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setMinWidth(dp(52));
        t.setPadding(dp(6), dp(4), dp(6), dp(4));
        t.setOnClickListener(l);
        return t;
    }

    private final class ItemsAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int i) {
            return items.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int i, View convert, ViewGroup parent) {
            LinearLayout row;
            TextView t1, t2;
            if (convert == null) {
                row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(dp(16), dp(14), dp(16), dp(14));
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(CARD);
                bg.setCornerRadius(dp(12));
                row.setBackground(bg);
                t1 = text(18, TEXT, true);
                t2 = text(14, MUTED, false);
                row.addView(t1);
                row.addView(t2);
                // مسافة بين الكروت
                LinearLayout wrap = new LinearLayout(MainActivity.this);
                wrap.setPadding(0, dp(4), 0, dp(4));
                wrap.addView(row, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                wrap.setTag(new TextView[]{t1, t2});
                convert = wrap;
            }
            TextView[] tv = (TextView[]) convert.getTag();
            t1 = tv[0];
            t2 = tv[1];
            MediaItem it = items.get(i);
            boolean folder = Boolean.TRUE.equals(it.mediaMetadata.isBrowsable);
            t1.setText(it.mediaMetadata.title);
            t1.setTextColor(folder ? TEXT : GOLD);
            CharSequence sub = it.mediaMetadata.subtitle;
            t2.setText(sub == null ? "" : sub);
            t2.setVisibility(TextUtils.isEmpty(sub) ? View.GONE : View.VISIBLE);
            return convert;
        }
    }
}
