package com.example.douyinweb;

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private static final String HOME_URL = "https://www.douyin.com";

    private static final String PREFS_NAME = "douyin_web_prefs";
    private static final String PREF_UA_MODE = "ua_mode";

    /** Douyin renders its responsive mobile layout for this identity. */
    private static final String MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/119.0.0.0 Mobile Safari/537.36";

    /**
     * Desktop Chrome identity. Kept as a fallback: it is the layout Douyin serves
     * when it refuses the mobile one, and it was verified to play video correctly.
     */
    private static final String DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36";

    private WebView webView;
    private ViewGroup fullscreenContainer;
    private WebChromeClient.CustomViewCallback fullscreenCallback;
    private long lastBackPressMs = 0L;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webview);
        fullscreenContainer = findViewById(R.id.fullscreen_container);

        configureWebView();
        configureCookieManager();
        applySystemBarInsets();
        wireBackNavigation();
        wireUserAgentToggle();

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            webView.loadUrl(HOME_URL);
        }
    }

    /** True when the persisted preference selects the desktop identity. */
    private boolean isDesktopMode() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(PREF_UA_MODE, false);
    }

    private String currentUserAgent() {
        return isDesktopMode() ? DESKTOP_UA : MOBILE_UA;
    }

    private void configureWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setUserAgentString(currentUserAgent());

        // Mobile-first rendering: let the page lay itself out for a phone-width
        // viewport instead of zooming a desktop layout down to fit.
        s.setUseWideViewPort(false);
        s.setLoadWithOverviewMode(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);

        // Media: Douyin autoplays; don't gate playback behind a user gesture.
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);

        // Without this, links that open a new window (Douyin uses these for
        // video detail pages) are silently dropped and the tap appears dead.
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);

        webView.setHorizontalScrollBarEnabled(false);
        webView.setWebViewClient(new DouyinWebViewClient());
        webView.setWebChromeClient(new DouyinChromeClient());
    }

    /**
     * The player controls live at the very bottom of the page. If the WebView is
     * laid out over the gesture/navigation bar, taps there hit the system UI and
     * the bottom of the page becomes untappable. Reserve that space as padding.
     *
     * API 30+ reads real insets. On older releases the window is not drawn edge
     * to edge by default, so the framework already keeps the WebView clear and
     * no extra padding is needed.
     */
    private void applySystemBarInsets() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return;
        }
        webView.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars =
                    insets.getInsets(WindowInsets.Type.navigationBars()
                            | WindowInsets.Type.statusBars()
                            | WindowInsets.Type.displayCutout());
            v.setPadding(0, bars.top, 0, bars.bottom);
            return insets;
        });
        webView.requestApplyInsets();
    }

    /**
     * Long-press the small hotspot in the top-left corner to flip between the
     * mobile and desktop identities, then reload. Douyin decides its layout from
     * the UA, so this is the escape hatch when the mobile layout is unavailable.
     * A separate overlay is used rather than a listener on the WebView itself so
     * that long-pressing video/text keeps working normally.
     */
    private void wireUserAgentToggle() {
        View hotspot = findViewById(R.id.ua_toggle_hotspot);
        if (hotspot == null) {
            return;
        }
        hotspot.setOnLongClickListener(v -> {
            boolean toDesktop = !isDesktopMode();
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putBoolean(PREF_UA_MODE, toDesktop)
                    .apply();
            webView.getSettings().setUserAgentString(currentUserAgent());
            webView.reload();
            Toast.makeText(this,
                    toDesktop ? R.string.switched_to_desktop : R.string.switched_to_mobile,
                    Toast.LENGTH_SHORT).show();
            return true;
        });
    }

    private void configureCookieManager() {
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        // Douyin's login flow relies on cross-site cookies.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cm.setAcceptThirdPartyCookies(webView, true);
        }
        // Persist the session so a cold start does not log the user out.
        cm.flush();
    }

    private void wireBackNavigation() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (webView == null) {
                    finish();
                    return;
                }
                if (webView.canGoBack()) {
                    webView.goBack();
                    return;
                }
                // Double-back to exit from the home page.
                long now = System.currentTimeMillis();
                if (now - lastBackPressMs < 2000L) {
                    finish();
                } else {
                    lastBackPressMs = now;
                    Toast.makeText(MainActivity.this,
                            R.string.press_back_again_to_exit, Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    /** Keeps Douyin in-app, but hands custom schemes off to the system. */
    private class DouyinWebViewClient extends WebViewClient {

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return handleUrl(request.getUrl().toString());
        }

        @SuppressWarnings("deprecation")
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return handleUrl(url);
        }

        private boolean handleUrl(String url) {
            if (url == null || url.isEmpty()) {
                return false;
            }
            Uri uri = Uri.parse(url);

            if ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) {
                // Stay inside the WebView for regular browsing.
                return false;
            }

            if ("intent".equals(uri.getScheme())) {
                // "intent://" URIs must be launched through the system.
                try {
                    Intent intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                    return true;
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this,
                            R.string.no_app_handled_link, Toast.LENGTH_SHORT).show();
                    return true;
                }
            }

            // Custom schemes such as snssdk1128://, weixin://, alipays://.
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } catch (ActivityNotFoundException e) {
                Toast.makeText(MainActivity.this,
                        R.string.no_app_handled_link, Toast.LENGTH_SHORT).show();
            }
            return true;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            // Re-flush after every page so the session survives a hard kill.
            CookieManager.getInstance().flush();
        }
    }

    /** Supports Douyin's built-in full-screen video player. */
    private class DouyinChromeClient extends WebChromeClient {

        @Override
        public void onShowCustomView(View view, CustomViewCallback callback) {
            if (fullscreenContainer == null) {
                return;
            }
            // A custom view is already showing; reject the duplicate.
            if (fullscreenContainer.getChildCount() > 0) {
                callback.onCustomViewHidden();
                return;
            }
            fullscreenCallback = callback;
            fullscreenContainer.addView(view, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            fullscreenContainer.setVisibility(View.VISIBLE);
            webView.setVisibility(View.GONE);

            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            hideSystemBars();
        }

        @Override
        public void onHideCustomView() {
            if (fullscreenContainer == null || fullscreenContainer.getChildCount() == 0) {
                return;
            }
            fullscreenContainer.removeAllViews();
            fullscreenContainer.setVisibility(View.GONE);
            webView.setVisibility(View.VISIBLE);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            showSystemBars();

            if (fullscreenCallback != null) {
                fullscreenCallback.onCustomViewHidden();
                fullscreenCallback = null;
            }
        }
    }

    private void hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().getInsetsController().hide(
                    WindowInsets.Type.statusBars()
                            | WindowInsets.Type.navigationBars());
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    private void showSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().getInsetsController().show(
                    WindowInsets.Type.statusBars()
                            | WindowInsets.Type.navigationBars());
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && event.getRepeatCount() > 0) {
            // Ignore back-key auto-repeat so a long press doesn't exit instantly.
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onPause() {
        if (webView != null) {
            webView.onPause();
            // Stop background JS/timers; otherwise Douyin keeps burning battery.
            webView.pauseTimers();
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.resumeTimers();
            webView.onResume();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (webView != null) {
            webView.saveState(outState);
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            // Detach first: destroy() with live callbacks/JS can crash.
            webView.setWebChromeClient(null);
            webView.setWebViewClient(new WebViewClient());
            webView.loadUrl("about:blank");
            webView.stopLoading();
            webView.clearHistory();
            if (webView.getParent() instanceof ViewGroup) {
                ((ViewGroup) webView.getParent()).removeView(webView);
            }
            webView.destroy();
            webView = null;
        }
        CookieManager.getInstance().flush();
        super.onDestroy();
    }
}