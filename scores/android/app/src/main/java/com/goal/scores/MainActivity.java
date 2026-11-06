package com.goal.scores;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.ValueCallback;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

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

/**
 * GOAL: WebView بيعرض واجهة النتايج (من scores/public)،
 * وطلبات /api/football/* بتتبعت من هنا لـ API-Football بالمفتاح اللي المستخدم حطه.
 */
public class MainActivity extends Activity {

    static final String HOST = "app.goal.local";
    static final String ORIGIN = "https://" + HOST + "/";
    static final String API_BASE = "https://v3.football.api-sports.io/";
    static final Charset UTF8 = Charset.forName("UTF-8");
    static final String[] PASS_HEADERS = {
            "x-ratelimit-requests-limit", "x-ratelimit-requests-remaining", "x-ratelimit-limit", "x-ratelimit-remaining"
    };

    private WebView web;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        web.setBackgroundColor(Color.rgb(10, 14, 19));
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setAllowFileAccess(false);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (!HOST.equals(u.getHost())) return null;
                String path = u.getPath() == null ? "/" : u.getPath();
                try {
                    if (path.startsWith("/api/football/")) return football(u, path.substring("/api/football/".length()));
                    return asset(path);
                } catch (Exception e) {
                    return json(502, "{\"errors\":{\"network\":" + jsonStr("تعذر الاتصال بـ API-Football: " + e.getMessage()) + "},\"response\":[]}");
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (HOST.equals(u.getHost())) return false;
                // الروابط الخارجية (زي صفحة التسجيل في API-Football) تفتح في المتصفح
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }
        });

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(ORIGIN + "index.html");
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onPause() { super.onPause(); web.onPause(); }

    @Override
    protected void onResume() { super.onResume(); web.onResume(); }

    @Override
    protected void onDestroy() { web.destroy(); super.onDestroy(); }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("(window.__back && window.__back()) ? 'yes' : 'no'", new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String v) {
                if (v == null || !v.contains("yes")) moveTaskToBack(true);
            }
        });
    }

    // ---------- الطلبات ----------

    private WebResourceResponse football(Uri u, String endpoint) throws IOException {
        if (!endpoint.matches("[a-zA-Z/]+")) return json(400, "{\"errors\":{\"request\":\"bad endpoint\"},\"response\":[]}");
        String key = "";
        StringBuilder q = new StringBuilder();
        for (String k : u.getQueryParameterNames()) {
            String v = u.getQueryParameter(k);
            if (k.equals("_k")) { key = v == null ? "" : v; continue; }
            q.append(q.length() == 0 ? "?" : "&").append(enc(k)).append('=').append(enc(v == null ? "" : v));
        }
        HttpURLConnection c = (HttpURLConnection) new URL(API_BASE + endpoint + q).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("x-apisports-key", key);
        c.setRequestProperty("Accept", "application/json");
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        byte[] body = in == null ? new byte[0] : readAll(in);
        Map<String, String> h = baseHeaders();
        for (String name : PASS_HEADERS) {
            String v = c.getHeaderField(name);
            if (v != null) h.put(name, v);
        }
        c.disconnect();
        return new WebResourceResponse("application/json", "utf-8", code, reason(code), h, new ByteArrayInputStream(body));
    }

    private WebResourceResponse asset(String path) {
        if (path.equals("/") || path.isEmpty()) path = "/index.html";
        String name = path.substring(1);
        if (name.contains("..")) return json(403, "{}");
        try {
            return new WebResourceResponse(mime(name), null, 200, "OK", baseHeaders(), getAssets().open(name));
        } catch (IOException e) {
            return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", baseHeaders(), new ByteArrayInputStream(new byte[0]));
        }
    }

    // ---------- أدوات ----------

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        try { while ((n = in.read(buf)) > 0) out.write(buf, 0, n); } finally { in.close(); }
        return out.toByteArray();
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

    static Map<String, String> baseHeaders() {
        Map<String, String> h = new HashMap<>();
        h.put("Cache-Control", "no-store");
        return h;
    }

    static WebResourceResponse json(int code, String body) {
        return new WebResourceResponse("application/json", "utf-8", code, reason(code), baseHeaders(), new ByteArrayInputStream(body.getBytes(UTF8)));
    }

    static String reason(int code) {
        if (code == 200) return "OK";
        if (code == 400) return "Bad Request";
        if (code == 401) return "Unauthorized";
        if (code == 403) return "Forbidden";
        if (code == 404) return "Not Found";
        if (code == 429) return "Too Many Requests";
        if (code == 502) return "Bad Gateway";
        return code < 400 ? "OK" : "Error";
    }

    static String mime(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".html")) return "text/html";
        if (n.endsWith(".js")) return "application/javascript";
        if (n.endsWith(".css")) return "text/css";
        if (n.endsWith(".svg")) return "image/svg+xml";
        if (n.endsWith(".png")) return "image/png";
        return "application/octet-stream";
    }
}
