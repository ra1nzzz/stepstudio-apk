package com.stepstudio.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final String START_URL = "https://studio.stepx.com/";
    private static final String RELEASES_API =
            "https://api.github.com/repos/ra1nzzz/stepstudio-apk/releases/latest";
    private static final String REPO_URL = "https://github.com/ra1nzzz/stepstudio-apk";
    private static final String[] INTERNAL_HOSTS = {"stepx.com", "stepfun.com"};

    private static final int REQ_FILE_CHOOSER = 1001;
    private static final int REQ_AUDIO_PERM = 1002;

    private static final String PREFS = "stepstudio";
    private static final String KEY_LAST_CHECK = "last_update_check";
    private static final long CHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L;

    private WebView webView;
    private ProgressBar progressBar;
    private FrameLayout root;
    private TextView versionChip;
    private ValueCallback<Uri[]> pendingFileCallback;
    private PermissionRequest pendingPermissionRequest;
    private boolean pageErrorShown = false;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private long pendingDownloadId = -1;
    private String pendingApkUrl;
    private BroadcastReceiver downloadReceiver;
    private int topInset = 0;
    private int bottomInset = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#0B0B0F"));

        webView = new WebView(this);
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        int barHeight = Math.max(2, (int) (2 * getResources().getDisplayMetrics().density));
        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, barHeight);
        pl.gravity = Gravity.TOP;
        progressBar.setLayoutParams(pl);
        progressBar.setMax(100);
        progressBar.setVisibility(View.GONE);
        root.addView(progressBar);

        versionChip = buildVersionChip();
        root.addView(versionChip);

        setContentView(root);

        applyEdgeToEdge();
        applyInsets();

        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        configureWebView();

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            webView.loadUrl(START_URL);
        }

        maybeAutoCheckUpdate();
    }

    // ---------- 版本入口 ----------

    private TextView buildVersionChip() {
        float d = getResources().getDisplayMetrics().density;
        TextView chip = new TextView(this);
        chip.setText("v" + localVersionName());
        chip.setTextSize(10f);
        chip.setTextColor(Color.parseColor("#C8C8D8"));
        chip.setPadding((int) (9 * d), (int) (4 * d), (int) (9 * d), (int) (4 * d));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#66000000"));
        bg.setCornerRadius(20 * d);
        bg.setStroke(Math.max(1, (int) d), Color.parseColor("#33FFFFFF"));
        chip.setBackground(bg);
        chip.setAlpha(0.6f);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM | Gravity.END;
        lp.rightMargin = (int) (10 * d);
        lp.bottomMargin = (int) (10 * d);
        chip.setLayoutParams(lp);
        chip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAboutDialog();
            }
        });
        return chip;
    }

    private void showAboutDialog() {
        final String menu = "版本  v" + localVersionName() + "（构建 " + localVersionCode() + "）\n"
                + "包名  " + getPackageName() + "\n"
                + "更新源  " + REPO_URL + "\n\n"
                + "沉浸式显示 · 自动检查更新";

        new AlertDialog.Builder(this)
                .setTitle("Step Studio")
                .setMessage(menu)
                .setPositiveButton("检查更新", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        checkUpdate(true);
                    }
                })
                .setNeutralButton("打开仓库", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        try {
                            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL)));
                        } catch (Exception ignored) {
                        }
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private String localVersionName() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName == null ? "1.0.0" : info.versionName;
        } catch (Exception e) {
            return "1.0.0";
        }
    }

    private int localVersionCode() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return (int) info.getLongVersionCode();
            }
            return info.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    // ---------- 沉浸式 ----------

    private void applyEdgeToEdge() {
        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            w.setStatusBarColor(Color.TRANSPARENT);
            w.setNavigationBarColor(Color.TRANSPARENT);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            w.setStatusBarContrastEnforced(false);
            w.setNavigationBarContrastEnforced(false);
        }
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        root.setSystemUiVisibility(flags);
        root.setFitsSystemWindows(true);
    }

    private void applyInsets() {
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                int top;
                int bottom;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    android.graphics.Insets bars = insets.getInsets(
                            WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                    top = bars.top;
                    bottom = bars.bottom;
                } else {
                    top = insets.getSystemWindowInsetTop();
                    bottom = insets.getSystemWindowInsetBottom();
                }
                if (top != topInset || bottom != bottomInset) {
                    topInset = top;
                    bottomInset = bottom;
                    webView.setPadding(0, top, 0, bottom);
                    FrameLayout.LayoutParams lp =
                            (FrameLayout.LayoutParams) progressBar.getLayoutParams();
                    lp.topMargin = top;
                    progressBar.setLayoutParams(lp);

                    FrameLayout.LayoutParams cl =
                            (FrameLayout.LayoutParams) versionChip.getLayoutParams();
                    cl.bottomMargin = (int) (10 * getResources().getDisplayMetrics().density) + bottom;
                    versionChip.setLayoutParams(cl);
                }
                return insets;
            }
        });
        root.requestApplyInsets();
    }

    /** 依据网页背景色决定状态栏图标深浅，避免深色背景配深色图标看不见 */
    private void syncSystemBarTheme(String colorString) {
        int color = parseColor(colorString, Color.parseColor("#0B0B0F"));
        root.setBackgroundColor(color);
        webView.setBackgroundColor(color);

        boolean lightBackground = luminance(color) > 0.6;
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        if (lightBackground && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        }
        if (lightBackground && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        final int finalFlags = flags;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                root.setSystemUiVisibility(finalFlags);
            }
        });
    }

    private static double luminance(int color) {
        double r = Color.red(color) / 255.0;
        double g = Color.green(color) / 255.0;
        double b = Color.blue(color) / 255.0;
        return 0.299 * r + 0.587 * g + 0.114 * b;
    }

    private static int parseColor(String value, int fallback) {
        if (TextUtils.isEmpty(value)) return fallback;
        String s = value.trim().toLowerCase(Locale.US);
        try {
            if (s.startsWith("#")) {
                if (s.length() == 9) {
                    // #aarrggbb
                    return Color.parseColor("#" + s.substring(3));
                }
                return Color.parseColor(s);
            }
            if (s.startsWith("rgb")) {
                int start = s.indexOf('(');
                int end = s.indexOf(')');
                if (start > 0 && end > start) {
                    String[] parts = s.substring(start + 1, end).split(",");
                    if (parts.length >= 3) {
                        int r = (int) Float.parseFloat(parts[0].trim());
                        int g = (int) Float.parseFloat(parts[1].trim());
                        int b = (int) Float.parseFloat(parts[2].trim());
                        if (parts.length >= 4) {
                            float a = Float.parseFloat(parts[3].trim());
                            if (a <= 0.01f) return fallback;
                        }
                        return Color.rgb(clamp(r), clamp(g), clamp(b));
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    // ---------- WebView ----------

    public class HostBridge {
        @JavascriptInterface
        public void onTheme(String background) {
            syncSystemBarTheme(background);
        }

        @JavascriptInterface
        public void onReady() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    versionChip.setVisibility(View.VISIBLE);
                }
            });
        }
    }

    private static final String THEME_PROBE_JS =
            "(function(){"
            + "function lum(c){var m=/rgba?\\((\\d+),\\s*(\\d+),\\s*(\\d+)(?:,\\s*([\\d.]+))?\\)/.exec(c||'');"
            + "if(!m)return -1;var r=+m[1],g=+m[2],b=+m[3];var a=m[4]===undefined?1:parseFloat(m[4]);"
            + "if(a<0.05)return -1;return (0.299*r+0.587*g+0.114*b)/255;}"
            + "function bgOf(el){try{return getComputedStyle(el).backgroundColor;}catch(e){return '';}}"
            + "function report(){"
            + "try{var el=document.body||document.documentElement;var c=bgOf(el);"
            + "if(lum(c)<0)c=bgOf(document.documentElement);"
            + "if(lum(c)<0)c='#0b0b0f';"
            + "if(window.StepStudioHost&&window.StepStudioHost.onTheme)window.StepStudioHost.onTheme(c);"
            + "}catch(e){}}"
            + "report();setTimeout(report,300);setTimeout(report,1200);setTimeout(report,3000);"
            + "try{new MutationObserver(report).observe(document.documentElement,"
            + "{attributes:true,attributeFilter:['class','style','data-theme']});"
            + "if(document.body)new MutationObserver(report).observe(document.body,"
            + "{attributes:true,attributeFilter:['class','style']});}catch(e){}"
            + "})();";

    private void configureWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        }
        s.setUserAgentString(s.getUserAgentString() + " StepStudioAndroid/1.1.0");
        s.setTextZoom(100);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cm.setAcceptThirdPartyCookies(webView, true);
        }

        webView.addJavascriptInterface(new HostBridge(), "StepStudioHost");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrl(url);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    return handleUrl(request.getUrl().toString());
                }
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progressBar.setVisibility(View.GONE);
                view.evaluateJavascript(THEME_PROBE_JS, null);
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                if (!pageErrorShown) {
                    pageErrorShown = true;
                    progressBar.setVisibility(View.GONE);
                    view.loadDataWithBaseURL(null, errorHtml(description),
                            "text/html", "utf-8", null);
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (newProgress < 100) {
                    progressBar.setVisibility(View.VISIBLE);
                    progressBar.setProgress(newProgress);
                } else {
                    progressBar.setVisibility(View.GONE);
                }
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (pendingFileCallback != null) {
                    pendingFileCallback.onReceiveValue(null);
                }
                pendingFileCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), REQ_FILE_CHOOSER);
                    return true;
                } catch (Exception e) {
                    pendingFileCallback = null;
                    return false;
                }
            }

            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (hasAudioPermission()) {
                            request.grant(request.getResources());
                        } else {
                            pendingPermissionRequest = request;
                            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},
                                    REQ_AUDIO_PERM);
                        }
                    }
                });
            }

            @Override
            public void onPermissionRequestCanceled(PermissionRequest request) {
                pendingPermissionRequest = null;
            }
        });

        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                        String mimeType, long contentLength) {
                downloadWithSystem(url, userAgent, contentDisposition, mimeType);
            }
        });
    }

    private boolean isInternalUrl(String url) {
        try {
            Uri uri = Uri.parse(url);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || (!scheme.equals("http") && !scheme.equals("https"))) return false;
            if (host == null) return false;
            for (String base : INTERNAL_HOSTS) {
                if (host.equals(base) || host.endsWith("." + base)) return true;
            }
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    private boolean handleUrl(String url) {
        if (url == null) return false;
        if (url.startsWith("about:") || url.startsWith("blob:") || url.startsWith("data:")) {
            return false;
        }
        if (!isInternalUrl(url)) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Exception ignored) {
            }
            return true;
        }
        return false;
    }

    private boolean hasAudioPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void downloadWithSystem(String url, String userAgent, String contentDisposition, String mimeType) {
        try {
            String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            req.setMimeType(mimeType);
            req.addRequestHeader("User-Agent", userAgent);
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null) {
                req.addRequestHeader("Cookie", cookie);
            }
            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            dm.enqueue(req);
            toast("已开始下载 " + fileName);
        } catch (Exception e) {
            toast("下载失败：" + e.getMessage());
        }
    }

    // ---------- 更新 ----------

    private void maybeAutoCheckUpdate() {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        long last = sp.getLong(KEY_LAST_CHECK, 0L);
        if (System.currentTimeMillis() - last < CHECK_INTERVAL_MS) return;
        sp.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply();
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                checkUpdate(false);
            }
        }, 2500);
    }

    private void checkUpdate(final boolean manual) {
        if (manual) toast("正在检查更新…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn = null;
                try {
                    URL url = new URL(RELEASES_API);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(10000);
                    conn.setRequestProperty("Accept", "application/vnd.github+json");
                    conn.setRequestProperty("User-Agent", "StepStudioAndroid");
                    int code = conn.getResponseCode();
                    if (code != 200) {
                        throw new IllegalStateException("HTTP " + code);
                    }
                    String body = readStream(conn.getInputStream());
                    JSONObject json = new JSONObject(body);
                    String tag = json.optString("tag_name", "");
                    String apkUrl = null;
                    String apkName = null;
                    JSONArray assets = json.optJSONArray("assets");
                    if (assets != null) {
                        for (int i = 0; i < assets.length(); i++) {
                            JSONObject a = assets.getJSONObject(i);
                            String name = a.optString("name", "");
                            if (name.toLowerCase(Locale.US).endsWith(".apk")) {
                                apkUrl = a.optString("browser_download_url", null);
                                apkName = name;
                                break;
                            }
                        }
                    }
                    final String fTag = tag;
                    final String fUrl = apkUrl;
                    final String fName = apkName;
                    final boolean newer = apkUrl != null && compareVersion(tag, localVersionName()) > 0;

                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (newer) {
                                onUpdateAvailable(fTag, fUrl, fName, manual);
                            } else if (manual) {
                                toast("已是最新版本 v" + localVersionName());
                            }
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (manual) toast("检查更新失败：" + e.getClass().getSimpleName());
                        }
                    });
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }
        }).start();
    }

    private void onUpdateAvailable(final String tag, final String apkUrl, final String apkName,
                                   boolean manual) {
        if (!manual) {
            // 自动更新：静默下载，下载完成后提示安装
            startApkDownload(apkUrl, apkName, tag, true);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("发现新版本 " + tag)
                .setMessage("当前版本 v" + localVersionName() + "，最新版本 " + tag + "。\n\n是否立即下载更新？")
                .setPositiveButton("立即更新", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        startApkDownload(apkUrl, apkName, tag, false);
                    }
                })
                .setNegativeButton("稍后", null)
                .show();
    }

    private void startApkDownload(String apkUrl, String apkName, String tag, final boolean silent) {
        try {
            String fileName = TextUtils.isEmpty(apkName) ? "StepStudio-" + tag + ".apk" : apkName;
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(apkUrl));
            req.setTitle("Step Studio " + tag);
            req.setDescription("正在下载新版本");
            req.setMimeType("application/vnd.android.package-archive");
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE);
            req.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, fileName);
            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            pendingApkUrl = apkUrl;
            pendingDownloadId = dm.enqueue(req);
            registerDownloadReceiver();
            toast(silent ? ("发现新版本 " + tag + "，正在后台下载") : "开始下载 " + tag);
        } catch (Exception e) {
            toast("更新下载失败：" + e.getMessage());
        }
    }

    private void registerDownloadReceiver() {
        if (downloadReceiver != null) return;
        downloadReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (pendingDownloadId != -1 && id == pendingDownloadId) {
                    onUpdateDownloaded();
                }
            }
        };
        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(downloadReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(downloadReceiver, filter);
        }
    }

    private void onUpdateDownloaded() {
        DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        Uri apkUri;
        try {
            apkUri = dm.getUriForDownloadedFile(pendingDownloadId);
        } catch (Exception e) {
            apkUri = null;
        }
        if (apkUri == null) {
            toast("更新包下载失败，请到应用内重试");
            return;
        }
        final Uri uri = apkUri;
        new AlertDialog.Builder(this)
                .setTitle("新版本已就绪")
                .setMessage("更新包已下载完成。点击「立即安装」后，请在系统弹窗中确认安装。")
                .setPositiveButton("立即安装", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        installApk(uri);
                    }
                })
                .setNegativeButton("稍后", null)
                .show();
    }

    private void installApk(Uri apkUri) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(apkUri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            toast("无法启动安装器：" + e.getMessage());
        }
    }

    /** 比较 v1.2.3 形式的版本号，remote > local 返回正数 */
    private static int compareVersion(String remote, String local) {
        int[] a = parseVersion(remote);
        int[] b = parseVersion(local);
        int len = Math.max(a.length, b.length);
        for (int i = 0; i < len; i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) return x - y;
        }
        return 0;
    }

    private static int[] parseVersion(String version) {
        if (version == null) return new int[]{0};
        String cleaned = version.trim().toLowerCase(Locale.US);
        if (cleaned.startsWith("v")) cleaned = cleaned.substring(1);
        String[] parts = cleaned.split("[^0-9]+");
        int count = 0;
        for (String p : parts) {
            if (!p.isEmpty()) count++;
        }
        if (count == 0) return new int[]{0};
        int[] out = new int[count];
        int idx = 0;
        for (String p : parts) {
            if (p.isEmpty()) continue;
            try {
                out[idx++] = Integer.parseInt(p);
            } catch (NumberFormatException e) {
                out[idx++] = 0;
            }
        }
        return out;
    }

    private static String readStream(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        in.close();
        return out.toString("UTF-8");
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    // ---------- 生命周期 ----------

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE_CHOOSER) {
            if (pendingFileCallback != null) {
                Uri[] results = null;
                if (resultCode == Activity.RESULT_OK && data != null) {
                    if (data.getClipData() != null) {
                        int count = data.getClipData().getItemCount();
                        results = new Uri[count];
                        for (int i = 0; i < count; i++) {
                            results[i] = data.getClipData().getItemAt(i).getUri();
                        }
                    } else if (data.getData() != null) {
                        results = new Uri[]{data.getData()};
                    }
                }
                pendingFileCallback.onReceiveValue(results);
                pendingFileCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQ_AUDIO_PERM && pendingPermissionRequest != null) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (granted) {
                pendingPermissionRequest.grant(pendingPermissionRequest.getResources());
            } else {
                pendingPermissionRequest.deny();
            }
            pendingPermissionRequest = null;
            return;
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onPause() {
        super.onPause();
        webView.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
    }

    @Override
    protected void onDestroy() {
        if (downloadReceiver != null) {
            try {
                unregisterReceiver(downloadReceiver);
            } catch (Exception ignored) {
            }
            downloadReceiver = null;
        }
        if (webView != null) {
            webView.removeJavascriptInterface("StepStudioHost");
            webView.setWebChromeClient(null);
            webView.setWebViewClient(null);
            webView.destroy();
        }
        super.onDestroy();
    }

    private String errorHtml(String message) {
        String safe = message == null ? "" : message.replace("<", "&lt;");
        return "<!DOCTYPE html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>body{margin:0;height:100vh;display:flex;align-items:center;justify-content:center;"
                + "background:#0B0B0F;color:#EAEAF2;font-family:-apple-system,sans-serif;text-align:center}"
                + "h2{font-size:17px;margin:0 0 10px}p{font-size:13px;color:#8D8DA5;margin:0 0 22px;padding:0 24px}"
                + "a{display:inline-block;padding:11px 26px;border-radius:24px;background:#6C5CE7;color:#fff;"
                + "text-decoration:none;font-size:14px}</style></head><body><div>"
                + "<h2>网络连接失败</h2><p>" + safe + "</p>"
                + "<a href='" + START_URL + "'>重新加载</a></div></body></html>";
    }
}
