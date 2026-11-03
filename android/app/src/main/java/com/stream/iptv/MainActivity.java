package com.stream.iptv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * التطبيق عبارة عن WebView بيعرض نفس واجهة البرنامج (من مجلد public)،
 * والطلبات اللي الواجهة بتبعتها لـ /api و /proxy بتتنفذ هنا في الجافا
 * بدل سيرفر Node — عشان التليفون مش محتاج أي سيرفر.
 */
public class MainActivity extends Activity {

    static final String HOST = "app.stream.local";
    static final String ORIGIN = "https://" + HOST + "/";
    static final String USER_AGENT = "VLC/3.0.20 LibVLC/3.0.20";
    static final Charset UTF8 = Charset.forName("UTF-8");

    private WebView web;
    private FrameLayout root;
    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;
    private WebChromeClient chrome;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(6, 6, 6));
        web = new WebView(this);
        web.setBackgroundColor(Color.rgb(6, 6, 6));
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setAllowFileAccess(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (!HOST.equals(u.getHost())) return null;
                try {
                    return handle(u, req.getRequestHeaders());
                } catch (Exception e) {
                    return text(502, "application/json", "{\"error\":" + jsonStr("تعذر الاتصال بالسيرفر: " + e.getMessage()) + "}");
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                return !HOST.equals(req.getUrl().getHost());
            }
        });

        chrome = new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) { callback.onCustomViewHidden(); return; }
                customView = view;
                customCallback = callback;
                root.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                web.setVisibility(View.GONE);
                setFullscreenUi(true);
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            }

            @Override
            public void onHideCustomView() {
                if (customView == null) return;
                root.removeView(customView);
                customView = null;
                web.setVisibility(View.VISIBLE);
                setFullscreenUi(false);
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
                if (customCallback != null) customCallback.onCustomViewHidden();
                customCallback = null;
            }
        };
        web.setWebChromeClient(chrome);

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(ORIGIN + "index.html");
    }

    private void setFullscreenUi(boolean on) {
        View d = getWindow().getDecorView();
        if (on) {
            d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        } else {
            d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onPause() {
        super.onPause();
        web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
    }

    @Override
    protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            onBackPressed();
            return true;
        }
        // أزرار ريموت التليفزيون: القناة اللي بعدها / اللي قبلها
        if (keyCode == KeyEvent.KEYCODE_CHANNEL_UP) { dispatchJsKey("ArrowUp"); return true; }
        if (keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN) { dispatchJsKey("ArrowDown"); return true; }
        return super.onKeyDown(keyCode, event);
    }

    private void dispatchJsKey(String key) {
        web.evaluateJavascript("document.dispatchEvent(new KeyboardEvent('keydown',{key:'" + key + "'}))", null);
    }

    @Override
    public void onBackPressed() {
        if (customView != null) {
            chrome.onHideCustomView();
            return;
        }
        web.evaluateJavascript("(window.__back && window.__back()) ? 'yes' : 'no'", new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String v) {
                if (v == null || !v.contains("yes")) moveTaskToBack(true);
            }
        });
    }

    // =====================================================================
    //                         الطلبات الداخلية
    // =====================================================================

    private WebResourceResponse handle(Uri u, Map<String, String> reqHeaders) throws IOException {
        String path = u.getPath() == null ? "/" : u.getPath();
        if (path.equals("/api/xtream")) return apiXtream(u);
        if (path.equals("/api/m3u")) return apiM3u(u);
        if (path.equals("/proxy")) return proxy(u, reqHeaders);
        return asset(path);
    }

    private WebResourceResponse asset(String path) {
        if (path.equals("/") || path.isEmpty()) path = "/index.html";
        String name = path.substring(1);
        if (name.contains("..")) return text(403, "text/plain", "Forbidden");
        try {
            InputStream in = getAssets().open(name);
            return new WebResourceResponse(mime(name), null, 200, "OK", noCache(), in);
        } catch (IOException e) {
            return text(404, "text/plain", "Not found");
        }
    }

    private WebResourceResponse apiXtream(Uri u) throws IOException {
        String server = u.getQueryParameter("server");
        if (!isHttp(server)) return text(400, "application/json", "{\"error\":\"عنوان السيرفر غير صحيح\"}");
        server = server.replaceAll("/+$", "");
        StringBuilder q = new StringBuilder();
        for (String k : u.getQueryParameterNames()) {
            if (k.equals("server")) continue;
            String v = u.getQueryParameter(k);
            q.append(q.length() == 0 ? "?" : "&").append(enc(k)).append('=').append(enc(v == null ? "" : v));
        }
        Upstream r = open(server + "/player_api.php" + q, null);
        byte[] body = readAll(r.in);
        return new WebResourceResponse("application/json", "utf-8", r.code, reason(r.code), noCache(), new ByteArrayInputStream(body));
    }

    private WebResourceResponse apiM3u(Uri u) throws IOException {
        String url = u.getQueryParameter("url");
        if (!isHttp(url)) return text(400, "application/json", "{\"error\":\"رابط القائمة غير صحيح\"}");
        Upstream r = open(url, null);
        if (r.code >= 400) {
            r.close();
            return text(r.code, "application/json", "{\"error\":\"السيرفر رد بخطأ " + r.code + "\"}");
        }
        return new WebResourceResponse("text/plain", "utf-8", 200, "OK", noCache(), new ByteArrayInputStream(readAll(r.in)));
    }

    private WebResourceResponse proxy(Uri u, Map<String, String> reqHeaders) throws IOException {
        String url = u.getQueryParameter("url");
        if (!isHttp(url)) return text(400, "text/plain", "Bad url");
        String range = null;
        if (reqHeaders != null) {
            for (Map.Entry<String, String> h : reqHeaders.entrySet()) {
                if ("range".equalsIgnoreCase(h.getKey())) range = h.getValue();
            }
        }
        Upstream r = open(url, range);
        String ct = r.contentType == null ? "" : r.contentType.toLowerCase(Locale.ROOT);
        String p = Uri.parse(r.finalUrl).getPath();
        boolean playlist = ct.contains("mpegurl") || (p != null && p.toLowerCase(Locale.ROOT).matches(".*\\.m3u8?$"));

        if (playlist && r.code < 400) {
            String txt = new String(readAll(r.in), UTF8);
            if (txt.trim().startsWith("#EXTM3U")) {
                byte[] out = rewritePlaylist(txt, r.finalUrl).getBytes(UTF8);
                return new WebResourceResponse("application/vnd.apple.mpegurl", "utf-8", 200, "OK", noCache(), new ByteArrayInputStream(out));
            }
            return new WebResourceResponse(ct.isEmpty() ? "text/plain" : ct, "utf-8", r.code, reason(r.code), noCache(), new ByteArrayInputStream(txt.getBytes(UTF8)));
        }

        Map<String, String> h = noCache();
        if (r.contentLength >= 0) h.put("Content-Length", String.valueOf(r.contentLength));
        if (r.contentRange != null) h.put("Content-Range", r.contentRange);
        h.put("Accept-Ranges", "bytes");
        String mime = ct.isEmpty() ? "application/octet-stream" : ct.split(";")[0].trim();
        return new WebResourceResponse(mime, null, r.code, reason(r.code), h, r.in);
    }

    private static final Pattern URI_ATTR = Pattern.compile("URI=\"([^\"]+)\"");

    static String rewritePlaylist(String text, String base) {
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\r?\n")) {
            String t = line.trim();
            if (t.isEmpty()) {
                sb.append(line);
            } else if (t.startsWith("#")) {
                Matcher m = URI_ATTR.matcher(line);
                StringBuffer b = new StringBuffer();
                while (m.find()) m.appendReplacement(b, Matcher.quoteReplacement("URI=\"" + proxify(resolve(base, m.group(1))) + "\""));
                m.appendTail(b);
                sb.append(b);
            } else {
                sb.append(proxify(resolve(base, t)));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    static String resolve(String base, String rel) {
        try { return new URL(new URL(base), rel).toString(); } catch (Exception e) { return rel; }
    }

    static String proxify(String u) { return "/proxy?url=" + enc(u); }

    // ---------- الاتصال بالسيرفر ----------

    static final class Upstream {
        int code;
        String contentType;
        String contentRange;
        long contentLength = -1;
        String finalUrl;
        InputStream in;
        HttpURLConnection conn;

        void close() { try { if (in != null) in.close(); } catch (IOException ignored) { } conn.disconnect(); }
    }

    static Upstream open(String url, String range) throws IOException {
        String current = url;
        for (int i = 0; i < 6; i++) {
            HttpURLConnection c = (HttpURLConnection) new URL(current).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", USER_AGENT);
            c.setRequestProperty("Accept", "*/*");
            c.setRequestProperty("Connection", "keep-alive");
            if (range != null) c.setRequestProperty("Range", range);
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) throw new IOException("Redirect without Location");
                current = resolve(current, loc);
                continue;
            }
            Upstream r = new Upstream();
            r.conn = c;
            r.code = code;
            r.finalUrl = current;
            r.contentType = c.getContentType();
            r.contentRange = c.getHeaderField("Content-Range");
            String len = c.getHeaderField("Content-Length");
            if (len != null) { try { r.contentLength = Long.parseLong(len.trim()); } catch (NumberFormatException ignored) { } }
            r.in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (r.in == null) r.in = new ByteArrayInputStream(new byte[0]);
            return r;
        }
        throw new IOException("Too many redirects");
    }

    // ---------- أدوات ----------

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        try { while ((n = in.read(buf)) > 0) out.write(buf, 0, n); } finally { in.close(); }
        return out.toByteArray();
    }

    static boolean isHttp(String s) {
        if (s == null) return false;
        String l = s.toLowerCase(Locale.ROOT);
        return l.startsWith("http://") || l.startsWith("https://");
    }

    static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8").replace("+", "%20"); } catch (Exception e) { return s; }
    }

    static String jsonStr(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char ch : String.valueOf(s).toCharArray()) {
            if (ch == '"' || ch == '\\') b.append('\\').append(ch);
            else if (ch < 0x20) b.append(String.format(Locale.ROOT, "\\u%04x", (int) ch));
            else b.append(ch);
        }
        return b.append('"').toString();
    }

    static Map<String, String> noCache() {
        Map<String, String> h = new HashMap<>();
        h.put("Cache-Control", "no-cache");
        h.put("Access-Control-Allow-Origin", "*");
        return h;
    }

    static WebResourceResponse text(int code, String mime, String body) {
        return new WebResourceResponse(mime, "utf-8", code, reason(code), noCache(), new ByteArrayInputStream(body.getBytes(UTF8)));
    }

    static String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 206: return "Partial Content";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 416: return "Range Not Satisfiable";
            case 502: return "Bad Gateway";
            default: return code < 400 ? "OK" : "Error";
        }
    }

    static String mime(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".html")) return "text/html";
        if (n.endsWith(".js")) return "application/javascript";
        if (n.endsWith(".css")) return "text/css";
        if (n.endsWith(".svg")) return "image/svg+xml";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".json")) return "application/json";
        if (n.endsWith(".txt")) return "text/plain";
        return "application/octet-stream";
    }
}
