package com.fastread.reader;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.util.Base64;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Comparator;

public class MainActivity extends Activity {
    private WebView webView;
    private final RenderStats renderStats = new RenderStats();
    private ViewTreeObserver.OnDrawListener drawListener;
    private final EinkHooks einkHooks = new EinkHooks();
    private Uri pendingUri = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Fullscreen
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                             WindowManager.LayoutParams.FLAG_FULLSCREEN);

        hideSystemUI();

        webView = new WebView(this);
        setContentView(webView);
        drawListener = new ViewTreeObserver.OnDrawListener() {
            @Override public void onDraw() { renderStats.recordDraw(); }
        };
        webView.getViewTreeObserver().addOnDrawListener(drawListener);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setAllowContentAccess(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (pendingUri != null) {
                    loadBookFromUri(pendingUri);
                    pendingUri = null;
                }
            }
        });

        webView.addJavascriptInterface(new NativeBridge(), "AndroidBridge");

        Intent intent = getIntent();
        if (intent != null && intent.getData() != null) {
            pendingUri = intent.getData();
        }

        webView.loadUrl("file:///android_asset/index.html");
    }

    private void hideSystemUI() {
        if (android.os.Build.VERSION.SDK_INT >= 19) {
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUI();
    }

    @Override
    protected void onPause() {
        if (webView != null) {
            renderStats.endReading();
            webView.setKeepScreenOn(false);
            webView.evaluateJavascript("if(window.FastRead){window.FastRead.pause();}", null);
            webView.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            renderStats.endReading();
            webView.setKeepScreenOn(false);
            webView.stopLoading();
            webView.removeJavascriptInterface("AndroidBridge");
            if (drawListener != null && webView.getViewTreeObserver().isAlive()) {
                webView.getViewTreeObserver().removeOnDrawListener(drawListener);
            }
            setContentView(new View(this));
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    // Observe existing view-tree draws without replacing WebView or changing
    // invalidation, layers, EPD mode or scheduling. NOT a panel-frame counter.
    private static final class RenderStats {
        private volatile boolean reading;
        private volatile long drawCount;
        private volatile long lastDrawNanos;
        private volatile long maxGapNanos;
        private volatile long startedMs;
        private volatile long elapsedMs;

        void beginReading() {
            if (reading) return;
            drawCount = 0;
            lastDrawNanos = 0;
            maxGapNanos = 0;
            elapsedMs = 0;
            startedMs = SystemClock.elapsedRealtime();
            reading = true;
        }

        void endReading() {
            if (reading) elapsedMs = SystemClock.elapsedRealtime() - startedMs;
            reading = false;
        }

        void recordDraw() {
            if (!reading) return;
            long now = System.nanoTime();
            if (lastDrawNanos != 0 && now - lastDrawNanos > maxGapNanos) maxGapNanos = now - lastDrawNanos;
            lastDrawNanos = now;
            drawCount++;
        }

        String renderStats() {
            try {
                JSONObject stats = new JSONObject();
                stats.put("drawCount", drawCount);
                stats.put("maxGapMs", maxGapNanos / 1000000.0);
                stats.put("elapsedMs", reading ? SystemClock.elapsedRealtime() - startedMs : elapsedMs);
                stats.put("device", android.os.Build.MODEL);
                stats.put("sdk", android.os.Build.VERSION.SDK_INT);
                return stats.toString();
            } catch (Exception e) { return "{}"; }
        }
    }

    // Capability detection only. Never guess numeric waveform flags, alter
    // global refresh mode or issue a vendor command during ordinary reading.
    private static final class EinkHooks {
        private Method gcOnce;
        private Method sdkInvalidate;
        private Object sdkGc;
        private String api = "unavailable";

        EinkHooks() {
            try {
                gcOnce = View.class.getMethod("applyGCOnce");
                api = "View.applyGCOnce";
            } catch (Throwable ignored) {}
            if (gcOnce == null) {
                try {
                    Class<?> controller = Class.forName("com.onyx.android.sdk.api.device.epd.EpdController", false, MainActivity.class.getClassLoader());
                    Class<?> mode = Class.forName("com.onyx.android.sdk.api.device.epd.UpdateMode");
                    Method invalidate = controller.getMethod("invalidate", View.class, mode);
                    Object gc = mode.getField("GC").get(null);
                    if (Modifier.isStatic(invalidate.getModifiers()) && gc != null) {
                        sdkInvalidate = invalidate;
                        sdkGc = gc;
                        api = "EpdController.invalidate(GC)";
                    }
                } catch (Throwable ignored) {}
            }
        }

        boolean supported() { return gcOnce != null || sdkInvalidate != null; }

        String capabilities() {
            try {
                JSONObject result = new JSONObject();
                result.put("fullRefresh", supported());
                result.put("api", api);
                result.put("model", android.os.Build.MODEL);
                result.put("firmware", android.os.Build.DISPLAY);
                return result.toString();
            } catch (Exception e) { return "{}"; }
        }

        boolean fullRefresh(WebView view) {
            try {
                if (gcOnce != null) {
                    gcOnce.invoke(Modifier.isStatic(gcOnce.getModifiers()) ? null : view);
                    view.invalidate();
                    return true;
                }
                if (sdkInvalidate != null) {
                    sdkInvalidate.invoke(null, view, sdkGc);
                    return true;
                }
            } catch (Throwable t) { android.util.Log.w("FastRead", "Manual EPD refresh failed", t); }
            return false;
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.getData() != null) {
            loadBookFromUri(intent.getData());
        }
    }

    private void loadBookFromUri(Uri uri) {
        try {
            String name = uri.getLastPathSegment();
            if (name == null || name.isEmpty()) name = "Book";

            String path = null;
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                path = uri.getPath();
            } else {
                File cacheFile = new File(getCacheDir(), "opened_book");
                InputStream in = getContentResolver().openInputStream(uri);
                if (in != null) {
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(cacheFile);
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
                    in.close();
                    fos.close();
                    path = cacheFile.getAbsolutePath();
                }
            }

            if (path != null) {
                final String safeName = name.replace("'", "\\'");
                final String safePath = path.replace("'", "\\'").replace("\\", "/");
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        webView.loadUrl("javascript:if(window.openBookFromPath){window.openBookFromPath('" 
                            + safeName + "','" + safePath + "');}");
                    }
                });
            }
        } catch (Throwable t) {
            android.util.Log.e("FastRead", "loadBookFromUri failed", t);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Forward BOOX hardware buttons to the webview
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == 92 || keyCode == KeyEvent.KEYCODE_PAGE_UP) {
            webView.loadUrl("javascript:window.dispatchEvent(new KeyboardEvent('keydown', {keyCode: 24, bubbles: true}))");
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == 93 || keyCode == KeyEvent.KEYCODE_PAGE_DOWN) {
            webView.loadUrl("javascript:window.dispatchEvent(new KeyboardEvent('keydown', {keyCode: 25, bubbles: true}))");
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    public class NativeBridge {
        @JavascriptInterface
        public String getEinkCapabilities() { return einkHooks.capabilities(); }

        @JavascriptInterface
        public void requestFullRefresh() {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    if (webView == null) return;
                    boolean sent = einkHooks.fullRefresh(webView);
                    webView.evaluateJavascript("if(window.FastRead){window.FastRead.onScreenRefresh(" + sent + ");}", null);
                }
            });
        }

        @JavascriptInterface
        public void setReading(final boolean active) {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    if (webView == null) return;
                    if (active && hasWindowFocus()) {
                        renderStats.beginReading();
                        webView.setKeepScreenOn(true);
                    } else {
                        renderStats.endReading();
                        webView.setKeepScreenOn(false);
                    }
                }
            });
        }

        @JavascriptInterface
        public String getRenderStats() {
            return renderStats.renderStats();
        }

        @JavascriptInterface
        public String getRootPath() {
            return Environment.getExternalStorageDirectory().getAbsolutePath();
        }

        @JavascriptInterface
        public String listDirectory(String dirPath) {
            try {
                if (dirPath == null || dirPath.isEmpty()) {
                    dirPath = Environment.getExternalStorageDirectory().getAbsolutePath();
                }
                File dir = new File(dirPath);
                if (!dir.exists() || !dir.isDirectory()) {
                    dir = Environment.getExternalStorageDirectory();
                }
                File[] files = dir.listFiles();
                JSONArray arr = new JSONArray();
                if (files != null) {
                    Arrays.sort(files, new Comparator<File>() {
                        @Override
                        public int compare(File f1, File f2) {
                            if (f1.isDirectory() && !f2.isDirectory()) return -1;
                            if (!f1.isDirectory() && f2.isDirectory()) return 1;
                            return f1.getName().compareToIgnoreCase(f2.getName());
                        }
                    });

                    for (int i = 0; i < files.length; i++) {
                        File f = files[i];
                        String name = f.getName();
                        if (name.startsWith(".")) continue;
                        boolean isDir = f.isDirectory();
                        String lower = name.toLowerCase();
                        if (isDir || lower.endsWith(".epub") || lower.endsWith(".fb2") 
                            || lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".json")) {
                            JSONObject obj = new JSONObject();
                            obj.put("name", name);
                            obj.put("path", f.getAbsolutePath());
                            obj.put("isDir", isDir);
                            obj.put("size", isDir ? 0 : f.length());
                            arr.put(obj);
                        }
                    }
                }
                return arr.toString();
            } catch (Exception e) {
                return "[]";
            }
        }

        @JavascriptInterface
        public String readFileBase64(String filePath) {
            try {
                if (filePath == null || filePath.isEmpty()) {
                    return "ERROR: Путь к файлу пустой";
                }
                File f = new File(filePath);
                if (!f.exists() || !f.isFile()) {
                    return "ERROR: Файл не найден на диске: " + filePath;
                }
                if (!f.canRead()) {
                    return "ERROR: Нет прав на чтение файла: " + filePath;
                }
                long len = f.length();
                if (len > 35 * 1024 * 1024) {
                    return "ERROR: Файл слишком большой (" + (len / 1024 / 1024) + " MB). Лимит 35 MB.";
                }
                FileInputStream fis = new FileInputStream(f);
                ByteArrayOutputStream baos = new ByteArrayOutputStream((int) Math.min(len, 2 * 1024 * 1024));
                byte[] buf = new byte[16384];
                int n;
                while ((n = fis.read(buf)) > 0) baos.write(buf, 0, n);
                fis.close();
                return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
            } catch (OutOfMemoryError oom) {
                return "ERROR: Недостаточно оперативной памяти для открытия книги такого размера (" + oom.getMessage() + ")";
            } catch (Throwable t) {
                return "ERROR: Ошибка чтения файла: " + t.getMessage();
            }
        }
    }
}
