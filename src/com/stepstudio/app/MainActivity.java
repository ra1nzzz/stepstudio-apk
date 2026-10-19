package com.stepstudio.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.provider.MediaStore;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
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
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final String START_URL = "https://studio.stepx.com/";
    private static final String RELEASES_API =
            "https://api.github.com/repos/ra1nzzz/stepstudio-apk/releases/latest";
    private static final String REPO_URL = "https://github.com/ra1nzzz/stepstudio-apk";
    /** 含预发布版本的列表，用于测试包直装 */
    private static final String RELEASES_LIST_API =
            "https://api.github.com/repos/ra1nzzz/stepstudio-apk/releases?per_page=10";
    private static final String[] INTERNAL_HOSTS = {"stepx.com", "stepfun.com"};

    private static final int REQ_FILE_CHOOSER = 1001;
    private static final int REQ_AUDIO_PERM = 1002;

    private static final String PREFS = "stepstudio";
    private static final String KEY_LAST_CHECK = "last_update_check";
    private static final String KEY_DL_ID = "pending_download_id";
    private static final String KEY_DL_TAG = "pending_download_tag";
    private static final String KEY_DL_FILE = "pending_download_file";
    private static final String KEY_DL_URL = "pending_download_url";
    private static final String KEY_DL_MIRROR = "pending_download_mirror";

    /** 直连失败时依次尝试的 GitHub 加速镜像（仅作用于 releases 下载地址） */
    private static final String[] DL_MIRRORS = {
            "",
            "https://ghfast.top/",
            "https://gh-proxy.com/",
            "https://ghproxy.net/",
            "https://gh.llkk.cc/",
            "https://github.moeyy.xyz/"
    };

    /** API 不通时的兜底：GitHub 的 latest/download 固定地址，不需要走 API 拿资产列表 */
    private static final String LATEST_APK_URL =
            "https://github.com/ra1nzzz/stepstudio-apk/releases/latest/download/StepStudio.apk";

    private static final String APK_MIME = "application/vnd.android.package-archive";

    private static final long CHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L;
    private static final long SPLASH_MIN_MS = 550L;
    private static final long SPLASH_TIMEOUT_MS = 12000L;

    private WebView webView;
    private FrameLayout root;
    private FrameLayout splashView;
    private ProgressBar progressBar;
    private TextView versionChip;
    private TextView testChip;

    private ValueCallback<Uri[]> pendingFileCallback;
    private PermissionRequest pendingPermissionRequest;

    private boolean pageErrorShown = false;
    private boolean splashDismissed = false;
    private boolean firstPageCommitted = false;
    private long splashShownAt = 0L;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private volatile boolean updateCheckInFlight = false;
    private long activeDownloadId = -1L;
    private boolean pendingIsTestPackage = false;
    private String detectedApkUrl = null;
    private BroadcastReceiver downloadReceiver;
    private int topInset = 0;
    private int bottomInset = 0;

    // ---------------- 生命周期 ----------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#0B0B0F"));

        webView = new WebView(this);
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        int barHeight = Math.max(2, dp(2));
        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, barHeight);
        pl.gravity = Gravity.TOP;
        progressBar.setLayoutParams(pl);
        progressBar.setMax(100);
        progressBar.setVisibility(View.GONE);
        root.addView(progressBar);

        versionChip = buildVersionChip();
        versionChip.setVisibility(View.GONE);
        root.addView(versionChip);

        testChip = buildTestChip();
        testChip.setVisibility(View.GONE);
        root.addView(testChip);

        splashView = buildSplash();
        root.addView(splashView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        splashShownAt = System.currentTimeMillis();

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

        // 闪屏兜底：网络极差时不至于永远停在闪屏
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                hideSplash();
            }
        }, SPLASH_TIMEOUT_MS);

        resumePendingInstall();
        maybeAutoCheckUpdate();
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // ---------------- 闪屏 ----------------

    private FrameLayout buildSplash() {
        FrameLayout splash = new FrameLayout(this);
        splash.setBackgroundColor(Color.WHITE);
        splash.setClickable(true); // 挡住过渡期间的误触

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);

        ImageView logo = new ImageView(this);
        int size = dp(84);
        try {
            Drawable icon = getPackageManager()
                    .getApplicationInfo(getPackageName(), 0).loadIcon(getPackageManager());
            logo.setImageDrawable(icon);
        } catch (Exception ignored) {
        }
        column.addView(logo, new LinearLayout.LayoutParams(size, size));

        TextView title = new TextView(this);
        title.setText("Step Studio");
        title.setTextColor(Color.parseColor("#111114"));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.topMargin = dp(16);
        column.addView(title, tl);

        TextView hint = new TextView(this);
        hint.setText("正在载入工作区…");
        hint.setTextColor(Color.parseColor("#8A8A99"));
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hl.topMargin = dp(7);
        column.addView(hint, hl);

        ProgressBar spinner = new ProgressBar(this);
        LinearLayout.LayoutParams spl = new LinearLayout.LayoutParams(dp(20), dp(20));
        spl.topMargin = dp(22);
        column.addView(spinner, spl);

        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cl.gravity = Gravity.CENTER;
        splash.addView(column, cl);

        TextView footer = new TextView(this);
        footer.setText("网络较慢时请稍候");
        footer.setTextColor(Color.parseColor("#B4B4C0"));
        footer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        FrameLayout.LayoutParams fl = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fl.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        fl.bottomMargin = dp(48);
        splash.addView(footer, fl);

        // 闪屏是浅色底，系统栏图标切成深色，避免看不清
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        root.setSystemUiVisibility(flags);

        return splash;
    }

    private void showSplashAgain() {
        if (splashView == null) return;
        splashDismissed = false;
        firstPageCommitted = false;
        splashShownAt = System.currentTimeMillis();
        splashView.setAlpha(1f);
        splashView.setVisibility(View.VISIBLE);
    }

    private void hideSplash() {
        if (splashDismissed || splashView == null) return;
        splashDismissed = true;
        long elapsed = System.currentTimeMillis() - splashShownAt;
        long delay = Math.max(0L, SPLASH_MIN_MS - elapsed);
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (splashView == null) return;
                splashView.animate()
                        .alpha(0f)
                        .setDuration(220L)
                        .setInterpolator(new DecelerateInterpolator())
                        .withEndAction(new Runnable() {
                            @Override
                            public void run() {
                                if (splashView != null) {
                                    splashView.setVisibility(View.GONE);
                                }
                                versionChip.setVisibility(View.VISIBLE);
                            }
                        })
                        .start();
            }
        }, delay);
    }

    // ---------------- 版本入口 ----------------

    private TextView buildVersionChip() {
        TextView chip = new TextView(this);
        chip.setText("v" + localVersionName());
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        chip.setTextColor(Color.parseColor("#C8C8D8"));
        chip.setPadding(dp(9), dp(4), dp(9), dp(4));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#66000000"));
        bg.setCornerRadius(dp(20));
        bg.setStroke(Math.max(1, dp(1)), Color.parseColor("#33FFFFFF"));
        chip.setBackground(bg);
        chip.setAlpha(0.6f);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM | Gravity.END;
        lp.rightMargin = dp(10);
        lp.bottomMargin = dp(10);
        chip.setLayoutParams(lp);
        chip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAboutDialog();
            }
        });
        chip.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                showInstallEntryDialog();
                return true;
            }
        });
        return chip;
    }

    /** 页面里出现测试包链接时浮现的独立按钮，不挂在版本胶囊上 */
    private TextView buildTestChip() {
        TextView chip = new TextView(this);
        chip.setText("装测试包");
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        chip.setTextColor(Color.WHITE);
        chip.setPadding(dp(12), dp(7), dp(12), dp(7));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#E62F6FED"));
        bg.setCornerRadius(dp(20));
        chip.setBackground(bg);
        chip.setElevation(dp(4));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM | Gravity.END;
        lp.rightMargin = dp(10);
        lp.bottomMargin = dp(46);
        chip.setLayoutParams(lp);
        chip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                installTestPackage();
            }
        });
        return chip;
    }

    private void showTestChip() {
        if (testChip == null) return;
        testChip.animate().cancel();
        testChip.setVisibility(View.VISIBLE);
        testChip.setAlpha(0f);
        testChip.animate().alpha(1f).setDuration(160).start();
    }

    private void hideTestChip() {
        if (testChip == null) return;
        testChip.setVisibility(View.GONE);
    }

    /** 页面没有 apk 链接时，看看剪贴板里有没有构建器「一键复制链接」留下的地址 */
    private void checkClipboardForApk() {
        if (detectedApkUrl != null) return;
        final String text = readClipboardText();
        if (text == null) return;
        final String url = text.trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) return;
        if (url.toLowerCase(Locale.US).indexOf(".apk") < 0) return;
        detectedApkUrl = url;
        showTestChip();
    }

    private void showAboutDialog() {
        if (isFinishing()) return;
        String message = "版本  v" + localVersionName() + "（构建 " + localVersionCode() + "）\n"
                + "包名  " + getPackageName() + "\n"
                + "更新源  " + REPO_URL + "\n\n"
                + "沉浸式显示 · 启动闪屏 · 自动检查更新\n"
                + "长按版本胶囊可打开直装入口";

        new AlertDialog.Builder(this)
                .setTitle("Step Studio")
                .setMessage(message)
                .setPositiveButton("下载并安装最新版", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        checkUpdate(true, true);
                    }
                })
                .setNeutralButton("检查更新", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        checkUpdate(true, false);
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    /** 长按版本胶囊打开：正式版 / 测试包 / 自定义地址 三个直装入口 */
    private void showInstallEntryDialog() {
        if (isFinishing()) return;
        final String[] items = {
                "正式版：下载最新 Release 并安装",
                "测试包：下载最新构建（含预发布）并安装",
                "自定义地址：粘贴任意 APK 链接"
        };
        new AlertDialog.Builder(this)
                .setTitle("直装入口")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            installFromChannel(0);
                        } else if (which == 1) {
                            installFromChannel(1);
                        } else {
                            showCustomUrlDialog();
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 拉取指定渠道的最新安装包并直接下载安装。
     * channel = 0：正式版（releases/latest，不含预发布）；channel = 1：测试包（releases 列表首个，可能是预发布）
     */
    private void installFromChannel(final int channel) {
        if (updateCheckInFlight) {
            toast("正在获取版本信息…");
            return;
        }
        if (!ensureInstallPermission()) return;
        updateCheckInFlight = true;
        toast(channel == 1 ? "正在获取最新测试包…" : "正在获取最新正式版…");

        io.execute(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn = null;
                try {
                    String endpoint = channel == 1 ? RELEASES_LIST_API : RELEASES_API;
                    conn = (HttpURLConnection) new URL(endpoint).openConnection();
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(10000);
                    conn.setRequestProperty("Accept", "application/vnd.github+json");
                    conn.setRequestProperty("User-Agent", "StepStudioAndroid");
                    int code = conn.getResponseCode();
                    if (code != 200) throw new IllegalStateException("HTTP " + code);

                    String body = readStream(conn.getInputStream());
                    JSONObject release;
                    if (channel == 1) {
                        JSONArray list = new JSONArray(body);
                        release = list.optJSONObject(0);
                    } else {
                        release = new JSONObject(body);
                    }
                    if (release == null) throw new IllegalStateException("无可用版本");

                    final String tag = release.optString("tag_name", "");
                    String apkUrl = null;
                    String apkName = null;
                    JSONArray assets = release.optJSONArray("assets");
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
                    if (apkUrl == null) throw new IllegalStateException("该版本没有 apk 资产");

                    final String fUrl = apkUrl;
                    final String fName = apkName;
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            updateCheckInFlight = false;
                            if (isFinishing()) return;
                            pendingIsTestPackage = false;
                            if (channel == 1) {
                                toast("测试包 " + tag + " 开始下载");
                            }
                            startApkDownload(fUrl, fName, tag, false);
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            updateCheckInFlight = false;
                            if (isFinishing()) return;
                            if (channel == 0) {
                                // API 不通就退回 latest/download 静态地址，配合镜像重试
                                toast("接口不通，改用静态地址下载");
                                startApkDownload(LATEST_APK_URL, "StepStudio-latest.apk", "latest", false);
                            } else {
                                toast("获取版本失败：" + e.getClass().getSimpleName());
                            }
                        }
                    });
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }
        });
    }

    /**
     * 安装测试包：优先用页面里嗅探到的 apk 链接，其次读剪贴板（对应构建器「一键复制链接」）。
     * 测试包是另一个应用，不做同签名校验，只校验下载结果确实是合法 APK。
     */
    private void installTestPackage() {
        String url = detectedApkUrl;
        String source = "页面链接";
        if (TextUtils.isEmpty(url)) {
            url = readClipboardText();
            source = "剪贴板链接";
        }
        if (TextUtils.isEmpty(url)) {
            toast("没找到测试包链接，请先在真机测试面板点「一键复制链接」");
            return;
        }
        url = url.trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            toast("链接格式不支持：" + url);
            return;
        }
        if (!ensureInstallPermission()) return;
        pendingIsTestPackage = true;
        hideTestChip();
        String name = "StepStudio-test-" + System.currentTimeMillis() + ".apk";
        startApkDownload(url, name, "测试包", false);
        toast("已从" + source + "读取，开始下载测试包");
    }

    private String readClipboardText() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) return null;
            ClipData clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return null;
            CharSequence text = clip.getItemAt(0).coerceToText(this);
            return text == null ? null : text.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** 自定义 APK 地址直装：粘贴链接即可下载并安装 */
    private void showCustomUrlDialog() {
        final EditText input = new EditText(this);
        input.setHint("https://…/StepStudio.apk");
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        input.setSingleLine(true);

        FrameLayout wrap = new FrameLayout(this);
        int pad = dp(18);
        wrap.setPadding(pad, dp(6), pad, 0);
        wrap.addView(input, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle("自定义安装包地址")
                .setView(wrap)
                .setPositiveButton("下载并安装", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String url = input.getText().toString().trim();
                        if (!url.startsWith("http://") && !url.startsWith("https://")) {
                            toast("请填写完整的 http(s) 地址");
                            return;
                        }
                        if (!ensureInstallPermission()) return;
                        pendingIsTestPackage = false;
                        String name = "StepStudio-test-" + System.currentTimeMillis() + ".apk";
                        startApkDownload(url, name, "测试包", false);
                    }
                })
                .setNegativeButton("取消", null)
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

    // ---------------- 沉浸式 ----------------

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
        root.setFitsSystemWindows(false);
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

                    FrameLayout.LayoutParams wl =
                            (FrameLayout.LayoutParams) webView.getLayoutParams();
                    wl.topMargin = top;
                    wl.bottomMargin = bottom;
                    webView.setLayoutParams(wl);

                    FrameLayout.LayoutParams pl =
                            (FrameLayout.LayoutParams) progressBar.getLayoutParams();
                    pl.topMargin = top;
                    progressBar.setLayoutParams(pl);

                    FrameLayout.LayoutParams cl =
                            (FrameLayout.LayoutParams) versionChip.getLayoutParams();
                    cl.bottomMargin = dp(10) + bottom;
                    versionChip.setLayoutParams(cl);

                    if (testChip != null) {
                        FrameLayout.LayoutParams tl =
                                (FrameLayout.LayoutParams) testChip.getLayoutParams();
                        tl.bottomMargin = dp(44) + bottom;
                        testChip.setLayoutParams(tl);
                    }
                }
                return insets;
            }
        });
        root.requestApplyInsets();
    }

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
                        int r = clamp((int) Float.parseFloat(parts[0].trim()));
                        int g = clamp((int) Float.parseFloat(parts[1].trim()));
                        int b = clamp((int) Float.parseFloat(parts[2].trim()));
                        if (parts.length >= 4) {
                            float a = Float.parseFloat(parts[3].trim());
                            if (a <= 0.01f) return fallback;
                        }
                        return Color.rgb(r, g, b);
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

    // ---------------- WebView ----------------

    public class HostBridge {
        @JavascriptInterface
        public void onTheme(String background) {
            syncSystemBarTheme(background);
        }

        /** 页面里出现 .apk 链接时上报，供「安装测试包」一键直装 */
        @JavascriptInterface
        public void onApkLink(final String url) {
            if (url == null || url.length() == 0) return;
            if (url.equals(detectedApkUrl)) return;
            detectedApkUrl = url;
            ui.post(new Runnable() {
                @Override
                public void run() {
                    showTestChip();
                    toast("检测到测试包，点右下角「装测试包」直接安装");
                }
            });
        }
    }

    private static final String APK_SNIFF_JS =
            "(function(){if(window.__ssSniff)return;window.__ssSniff=1;var last='';"
            + "function scan(){try{var urls=[];"
            + "var as=document.querySelectorAll('a[href]');"
            + "for(var i=0;i<as.length;i++){var h=as[i].href||'';"
            + "if(/\\.apk(\\?|$)/i.test(h))urls.push(h);}"
            + "var t=(document.body&&document.body.innerText)||'';"
            + "var m=t.match(/https?:\\/\\/[^\\s\"'<>]+\\.apk[^\\s\"'<>]*/ig);"
            + "if(m)urls=urls.concat(m);"
            + "if(urls.length&&urls[0]!==last){last=urls[0];"
            + "if(window.StepStudioHost&&window.StepStudioHost.onApkLink)"
            + "window.StepStudioHost.onApkLink(last);}}catch(e){}}"
            + "scan();setInterval(scan,2500);})();";

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
        s.setUserAgentString(s.getUserAgentString() + " StepStudioAndroid/1.1.2");
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
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                if (pageErrorShown && START_URL.equals(url)) {
                    pageErrorShown = false;
                    showSplashAgain();
                }
            }

            @Override
            public void onPageCommitVisible(WebView view, String url) {
                if (!firstPageCommitted) {
                    firstPageCommitted = true;
                    hideSplash();
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progressBar.setVisibility(View.GONE);
                view.evaluateJavascript(THEME_PROBE_JS, null);
                view.evaluateJavascript(APK_SNIFF_JS, null);
                ui.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        checkClipboardForApk();
                    }
                }, 1200L);
                hideSplash();
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                if (!pageErrorShown) {
                    pageErrorShown = true;
                    progressBar.setVisibility(View.GONE);
                    hideSplash();
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
            openExternal(url);
            return true;
        }
        return false;
    }

    private void openExternal(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception ignored) {
        }
    }

    private boolean hasAudioPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void downloadWithSystem(String url, String userAgent, String contentDisposition,
                                    String mimeType) {
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

    // ---------------- OTA 更新 ----------------

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private void maybeAutoCheckUpdate() {
        long last = prefs().getLong(KEY_LAST_CHECK, 0L);
        if (System.currentTimeMillis() - last < CHECK_INTERVAL_MS) return;
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                checkUpdate(false, false);
            }
        }, 2500L);
    }

    private void checkUpdate(final boolean manual, final boolean forceInstall) {
        if (updateCheckInFlight) {
            if (manual) toast("正在检查更新…");
            return;
        }
        if (forceInstall && !ensureInstallPermission()) return;
        updateCheckInFlight = true;
        if (manual) toast(forceInstall ? "正在获取最新版本…" : "正在检查更新…");

        io.execute(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn = null;
                try {
                    conn = (HttpURLConnection) new URL(RELEASES_API).openConnection();
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(10000);
                    conn.setRequestProperty("Accept", "application/vnd.github+json");
                    conn.setRequestProperty("User-Agent", "StepStudioAndroid");
                    int code = conn.getResponseCode();
                    if (code != 200) {
                        throw new IllegalStateException("HTTP " + code);
                    }
                    JSONObject json = new JSONObject(readStream(conn.getInputStream()));
                    final String tag = json.optString("tag_name", "");
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
                    final boolean newer =
                            apkUrl != null && compareVersion(tag, localVersionName()) > 0;

                    // 只有成功命中才记时间，失败下次仍会重试
                    prefs().edit()
                            .putLong(KEY_LAST_CHECK, System.currentTimeMillis())
                            .apply();

                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            updateCheckInFlight = false;
                            if (isFinishing()) return;
                            if (newer || (forceInstall && fUrl != null)) {
                                onUpdateAvailable(fTag, fUrl, fName, manual, forceInstall);
                            } else if (manual) {
                                toast("已是最新版本 v" + localVersionName());
                            }
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            updateCheckInFlight = false;
                            if (manual && !isFinishing()) {
                                toast("检查更新失败：" + e.getClass().getSimpleName());
                            }
                        }
                    });
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }
        });
    }

    private void onUpdateAvailable(final String tag, final String apkUrl, final String apkName,
                                   boolean manual, boolean forceInstall) {
        pendingIsTestPackage = false;
        if (!manual) {
            startApkDownload(apkUrl, apkName, tag, true);
            return;
        }
        if (forceInstall) {
            // 真机回归用：不等版本比较，直接拉最新包，装完就能验证一次完整链路
            startApkDownload(apkUrl, apkName, tag, false);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("发现新版本 " + tag)
                .setMessage("当前版本 v" + localVersionName() + "，最新版本 " + tag
                        + "。\n\n是否立即下载更新？")
                .setPositiveButton("立即更新", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        startApkDownload(apkUrl, apkName, tag, false);
                    }
                })
                .setNegativeButton("稍后", null)
                .show();
    }

    private File updateDir() {
        File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (dir == null) {
            dir = new File(getFilesDir(), "updates");
        }
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    private void startApkDownload(String apkUrl, String apkName, final String tag,
                                  final boolean silent) {
        try {
            final String fileName = TextUtils.isEmpty(apkName)
                    ? "StepStudio-" + tag + ".apk" : apkName;
            cleanOldUpdates(fileName);

            String finalUrl = mirrorUrl(apkUrl);
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(finalUrl));
            req.setTitle("Step Studio " + tag);
            req.setDescription("正在下载新版本");
            req.setMimeType("application/vnd.android.package-archive");
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE);
            req.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, fileName);

            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            long id = dm.enqueue(req);
            activeDownloadId = id;
            prefs().edit()
                    .putLong(KEY_DL_ID, id)
                    .putString(KEY_DL_TAG, tag)
                    .putString(KEY_DL_FILE, fileName)
                    .putString(KEY_DL_URL, apkUrl)
                    .apply();
            registerDownloadReceiver();
            toast(silent ? ("发现新版本 " + tag + "，正在后台下载")
                    : ("开始下载 " + tag + "，完成后会提示安装"));
        } catch (Exception e) {
            toast("更新下载失败：" + e.getMessage());
        }
    }

    /** 按已记录的镜像索引改写下载地址；直连（索引 0）保持原样 */
    private String mirrorUrl(String url) {
        int idx = prefs().getInt(KEY_DL_MIRROR, 0);
        if (idx <= 0 || idx >= DL_MIRRORS.length) return url;
        if (!url.startsWith("https://github.com/")) return url;
        return DL_MIRRORS[idx] + url;
    }

    /** 下载失败时换下一个镜像重试，全部试完才放弃 */
    private void retryWithNextMirror() {
        String url = prefs().getString(KEY_DL_URL, null);
        String tag = prefs().getString(KEY_DL_TAG, "");
        String name = prefs().getString(KEY_DL_FILE, null);
        if (TextUtils.isEmpty(url)) {
            clearPendingDownload();
            toast("更新下载失败，请稍后重试");
            return;
        }
        int idx = prefs().getInt(KEY_DL_MIRROR, 0);
        if (idx + 1 >= DL_MIRRORS.length) {
            prefs().edit().putInt(KEY_DL_MIRROR, 0).apply();
            clearPendingDownload();
            toast("更新包下载失败，请检查网络后重试");
            return;
        }
        prefs().edit().putInt(KEY_DL_MIRROR, idx + 1).apply();
        toast("直连下载失败，正在切换加速线路重试");
        startApkDownload(url, name, tag, false);
    }

    /** Android 8+ 需要「安装未知应用」授权，未授权时引导用户去设置 */
    private boolean ensureInstallPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true;
        if (getPackageManager().canRequestPackageInstalls()) return true;
        try {
            new AlertDialog.Builder(this)
                    .setTitle("需要安装权限")
                    .setMessage("下载完成后要拉起系统安装器，需要先允许 Step Studio 安装未知应用。"
                            + "\n\n点「去开启」后打开开关，再回来重新点一次下载。")
                    .setPositiveButton("去开启", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            try {
                                startActivity(new Intent(
                                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                        Uri.parse("package:" + getPackageName())));
                            } catch (Exception e) {
                                toast("请到系统设置中开启「安装未知应用」");
                            }
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
        } catch (Exception e) {
            toast("请到系统设置中开启「安装未知应用」");
        }
        return false;
    }

    private void cleanOldUpdates(String keepName) {
        File dir = updateDir();
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isFile() && f.getName().toLowerCase(Locale.US).endsWith(".apk")
                    && !f.getName().equals(keepName)) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    private void registerDownloadReceiver() {
        if (downloadReceiver != null) return;
        downloadReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L);
                long expected = prefs().getLong(KEY_DL_ID, -1L);
                if (expected != -1L && id == expected) {
                    onUpdateDownloaded(id);
                }
            }
        };
        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= 33) {
            // 系统广播，使用 NOT_EXPORTED 防止第三方伪造下载完成
            registerReceiver(downloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(downloadReceiver, filter);
        }
    }

    /** 应用重启后继续处理已完成的下载 */
    private void resumePendingInstall() {
        final long id = prefs().getLong(KEY_DL_ID, -1L);
        if (id == -1L) return;
        activeDownloadId = id;
        registerDownloadReceiver();
        io.execute(new Runnable() {
            @Override
            public void run() {
                DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                DownloadManager.Query q = new DownloadManager.Query().setFilterById(id);
                android.database.Cursor c = null;
                try {
                    c = dm.query(q);
                    if (c != null && c.moveToFirst()) {
                        int status = c.getInt(c.getColumnIndex(DownloadManager.COLUMN_STATUS));
                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            ui.post(new Runnable() {
                                @Override
                                public void run() {
                                    onUpdateDownloaded(id);
                                }
                            });
                        } else if (status == DownloadManager.STATUS_FAILED) {
                            clearPendingDownload();
                        }
                    } else {
                        clearPendingDownload();
                    }
                } catch (Exception ignored) {
                } finally {
                    if (c != null) c.close();
                }
            }
        });
    }

    private void clearPendingDownload() {
        prefs().edit()
                .remove(KEY_DL_ID)
                .remove(KEY_DL_TAG)
                .remove(KEY_DL_FILE)
                .apply();
        activeDownloadId = -1L;
    }

    private void onUpdateDownloaded(final long downloadId) {
        final String fileName = prefs().getString(KEY_DL_FILE, null);
        final String tag = prefs().getString(KEY_DL_TAG, "");
        if (TextUtils.isEmpty(fileName)) {
            clearPendingDownload();
            return;
        }
        io.execute(new Runnable() {
            @Override
            public void run() {
                final boolean failed = downloadFailed(downloadId);
                final File apk = new File(updateDir(), fileName);
                final boolean isTest = pendingIsTestPackage;
                PackageInfo testInfo = null;
                boolean ok;
                if (isTest) {
                    testInfo = apk.exists()
                            ? getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), 0)
                            : null;
                    ok = !failed && testInfo != null;
                } else {
                    ok = !failed && apk.exists() && verifiedSameSigner(apk);
                }
                final PackageInfo fTestInfo = testInfo;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        if (!ok) {
                            //noinspection ResultOfMethodCallIgnored
                            apk.delete();
                            if (failed) {
                                retryWithNextMirror();
                            } else {
                                clearPendingDownload();
                                toast(isTest ? "下载内容不是有效安装包，已拦截" : "更新包校验失败，已拦截安装");
                            }
                            return;
                        }
                        prefs().edit().putInt(KEY_DL_MIRROR, 0).apply();
                        String title = isTest ? "测试包已就绪 " + tag : "新版本已就绪 " + tag;
                        String body = isTest
                                ? ("已下载 " + (fTestInfo == null ? "安装包" : fTestInfo.packageName)
                                + "，点击「立即安装」后在系统弹窗中确认。")
                                : "更新包已下载并通过签名校验。点击「立即安装」后，请在系统弹窗中确认安装。";
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle(title)
                                .setMessage(body)
                                .setPositiveButton("立即安装", new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface d, int w) {
                                        installApk(downloadId);
                                    }
                                })
                                .setNegativeButton("稍后", null)
                                .show();
                    }
                });
            }
        });
    }

    private boolean downloadFailed(long downloadId) {
        DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        DownloadManager.Query q = new DownloadManager.Query().setFilterById(downloadId);
        android.database.Cursor c = null;
        try {
            c = dm.query(q);
            if (c != null && c.moveToFirst()) {
                int status = c.getInt(c.getColumnIndex(DownloadManager.COLUMN_STATUS));
                return status == DownloadManager.STATUS_FAILED;
            }
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.close();
        }
    }

    /** 校验下载包的包名与签名，二者必须与本应用完全一致才允许安装 */
    private boolean verifiedSameSigner(File apkFile) {
        try {
            PackageManager pm = getPackageManager();
            PackageInfo archive = pm.getPackageArchiveInfo(apkFile.getAbsolutePath(),
                    PackageManager.GET_SIGNATURES);
            if (archive == null || !getPackageName().equals(archive.packageName)) return false;
            Signature[] remote = archive.signatures;
            Signature[] local = pm.getPackageInfo(getPackageName(),
                    PackageManager.GET_SIGNATURES).signatures;
            if (remote == null || local == null || remote.length == 0 || local.length == 0) {
                return false;
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] a = digest.digest(remote[0].toByteArray());
            byte[] b = digest.digest(local[0].toByteArray());
            return Arrays.equals(a, b);
        } catch (Exception e) {
            return false;
        }
    }

    private void installApk(long downloadId) {
        final String fileName = prefs().getString(KEY_DL_FILE, "");
        File apk = TextUtils.isEmpty(fileName) ? null : new File(updateDir(), fileName);
        if (apk == null || !apk.exists()) {
            toast("安装包不存在，请重新下载");
            return;
        }
        if (!ensureInstallPermission()) return;

        Uri contentUri = ApkProvider.uriFor(apk);
        String lastError = null;

        // 1) ACTION_INSTALL_PACKAGE：多数 ROM 的包安装器对这条最稳
        Exception e1 = tryLaunchInstaller(new Intent(Intent.ACTION_INSTALL_PACKAGE), contentUri);
        if (e1 == null) return;
        lastError = e1.getClass().getSimpleName();

        // 2) ACTION_VIEW + apk mime
        Exception e2 = tryLaunchInstaller(new Intent(Intent.ACTION_VIEW), contentUri);
        if (e2 == null) return;
        lastError = e2.getClass().getSimpleName();

        // 3) DownloadManager 自己的 uri
        try {
            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            Uri dmUri = dm.getUriForDownloadedFile(downloadId);
            if (dmUri != null) {
                Exception e3 = tryLaunchInstaller(new Intent(Intent.ACTION_VIEW), dmUri);
                if (e3 == null) return;
                lastError = e3.getClass().getSimpleName();
            }
        } catch (Exception ignored) {
        }

        // 4) 兜底：把包复制到公共「下载」目录，让用户自己点一次
        String exported = exportToPublicDownloads(apk);
        if (exported != null) {
            toast("安装器没响应（" + lastError + "），已复制到 下载/" + apk.getName() + "，去文件管理点一下装");
        } else {
            toast("无法拉起安装器（" + lastError + "），请到文件管理安装 " + apk.getAbsolutePath());
        }
    }

    private Exception tryLaunchInstaller(Intent intent, Uri uri) {
        try {
            intent.setDataAndType(uri, APK_MIME);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            return null;
        } catch (Exception e) {
            return e;
        }
    }

    /** 复制到公共下载目录（29+ 走 MediaStore，无需存储权限） */
    private String exportToPublicDownloads(File apk) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, apk.getName());
                v.put(MediaStore.MediaColumns.MIME_TYPE, APK_MIME);
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri target = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (target == null) return null;
                OutputStream os = getContentResolver().openOutputStream(target);
                if (os == null) return null;
                copyFile(apk, os);
                return Environment.getExternalStorageDirectory().getAbsolutePath()
                        + "/" + Environment.DIRECTORY_DOWNLOADS + "/" + apk.getName();
            }
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!dir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            }
            File target = new File(dir, apk.getName());
            FileOutputStream os = new FileOutputStream(target);
            copyFile(apk, os);
            return target.getAbsolutePath();
        } catch (Exception e) {
            return null;
        }
    }

    private void copyFile(File src, OutputStream os) throws Exception {
        FileInputStream is = new FileInputStream(src);
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) {
                os.write(buf, 0, n);
            }
            os.flush();
        } finally {
            try {
                is.close();
            } catch (Exception ignored) {
            }
            try {
                os.close();
            } catch (Exception ignored) {
            }
        }
    }

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

    // ---------------- 回调与生命周期 ----------------

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
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
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
        ui.removeCallbacksAndMessages(null);
        if (downloadReceiver != null) {
            try {
                unregisterReceiver(downloadReceiver);
            } catch (Exception ignored) {
            }
            downloadReceiver = null;
        }
        io.shutdownNow();
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
