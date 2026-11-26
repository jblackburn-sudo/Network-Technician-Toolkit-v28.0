package com.example.networktoolkit;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import android.graphics.Color;
import android.net.http.SslError;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import com.example.networktoolkit.speedtest.SpeedTestServicePreferences;
import java.util.Locale;

/**
 * Activity hosting the configured Speed Test service web view.
 * Includes JavaScript, DOM storage, back navigation within web pages and back to the app.
 */
public class WebTestActivity extends ComponentActivity {

    private WebView webView;
    private ProgressBar progressBar;
    private OnBackPressedCallback backCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        backCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                if (webView != null && webView.canGoBack()) {
                    webView.goBack();
                    setEnabled(webView.canGoBack());
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        };
        getOnBackPressedDispatcher().addCallback(this, backCallback);

        LinearLayout rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setBackgroundColor(Color.parseColor("#0F172A"));

        // Header Toolbar with Back Button
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setBackgroundColor(Color.parseColor("#1E293B"));
        header.setPadding(dp(12), dp(10), dp(12), dp(10));
        header.setGravity(Gravity.CENTER_VERTICAL);

        Button backButton = new Button(this);
        backButton.setText("← Back");
        backButton.setAllCaps(false);
        backButton.setTextSize(13);
        backButton.setTextColor(Color.WHITE);
        backButton.setBackgroundColor(Color.parseColor("#334155"));
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            dp(48)
        );
        backButton.setLayoutParams(btnParams);
        backButton.setOnClickListener(v -> finish());
        header.addView(backButton);

        com.example.networktoolkit.speedtest.SpeedTestServiceOption selectedService =
            com.example.networktoolkit.speedtest.SpeedTestServicePreferences.getSelectedService(this);

        String targetUrl = getIntent().getStringExtra("target_url");
        if (targetUrl == null || targetUrl.trim().isEmpty()) {
            targetUrl = selectedService.getUrl();
        }
        if (!SpeedTestServicePreferences.isAllowedWebViewUrl(targetUrl, true)) {
            Toast.makeText(
                this,
                "Blocked unsafe URL; opening configured HTTPS speed test service.",
                Toast.LENGTH_SHORT
            ).show();
            targetUrl = SpeedTestServicePreferences.isAllowedWebViewUrl(selectedService.getUrl(), true)
                ? selectedService.getUrl()
                : SpeedTestServicePreferences.PRESET_SERVICES.get(0).getUrl();
        }
        final boolean allowLocalHttpForSession =
            targetUrl.toLowerCase(Locale.US).startsWith("http://") &&
            SpeedTestServicePreferences.isAllowedWebViewUrl(targetUrl, true);

        String targetTitle = getIntent().getStringExtra("target_title");
        if (targetTitle == null || targetTitle.trim().isEmpty()) {
            if (targetUrl.equals(selectedService.getUrl())) {
                targetTitle = selectedService.getName();
            } else {
                String host = android.net.Uri.parse(targetUrl).getHost();
                targetTitle = (host != null && !host.isEmpty()) ? host : "Web Speed Test";
            }
        }

        LinearLayout titleColumn = new LinearLayout(this);
        titleColumn.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1.0f
        );
        titleParams.setMargins(dp(14), 0, 0, 0);
        titleColumn.setLayoutParams(titleParams);

        TextView title = new TextView(this);
        title.setText(targetTitle);
        title.setTextSize(16);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(Color.WHITE);
        titleColumn.addView(title);

        TextView urlSubtitle = new TextView(this);
        urlSubtitle.setText(targetUrl);
        urlSubtitle.setTextSize(11);
        urlSubtitle.setTextColor(Color.parseColor("#94A3B8"));
        urlSubtitle.setSingleLine(true);
        titleColumn.addView(urlSubtitle);

        header.addView(titleColumn);

        rootLayout.addView(header);

        // Progress indicator for page loading
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(4)
        ));
        progressBar.setMax(100);
        rootLayout.addView(progressBar);

        // WebView
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);
        webView = new WebView(this);
        webView.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1.0f
        ));
        webView.setBackgroundColor(Color.parseColor("#0F172A"));

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        setLegacyFileUrlAccessDisabled(settings);
        settings.setGeolocationEnabled(false);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (progressBar != null) {
                    if (newProgress < 100) {
                        progressBar.setVisibility(View.VISIBLE);
                        progressBar.setProgress(newProgress);
                    } else {
                        progressBar.setVisibility(View.GONE);
                    }
                }
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = (request != null && request.getUrl() != null)
                    ? request.getUrl().toString()
                    : null;
                if (!shouldAllowNavigation(url, allowLocalHttpForSession)) {
                    Toast.makeText(
                        WebTestActivity.this,
                        "Blocked navigation to unsafe or non-HTTPS URL scheme",
                        Toast.LENGTH_SHORT
                    ).show();
                    return true;
                }
                return false;
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (!shouldAllowNavigation(url, allowLocalHttpForSession)) {
                    Toast.makeText(
                        WebTestActivity.this,
                        "Blocked navigation to unsafe or non-HTTPS URL scheme",
                        Toast.LENGTH_SHORT
                    ).show();
                    return true;
                }
                return false;
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                if (handler != null) {
                    handler.cancel();
                }
                Toast.makeText(
                    WebTestActivity.this,
                    "Blocked untrusted SSL/TLS certificate",
                    Toast.LENGTH_SHORT
                ).show();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request != null && request.isForMainFrame()) {
                    Toast.makeText(
                        WebTestActivity.this,
                        "Failed to load speed test page",
                        Toast.LENGTH_SHORT
                    ).show();
                }
            }

            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                super.doUpdateVisitedHistory(view, url, isReload);
                if (backCallback != null) {
                    backCallback.setEnabled(view.canGoBack());
                }
            }
        });

        rootLayout.addView(webView);
        setContentView(rootLayout);

        webView.loadUrl(targetUrl);
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
        }
        super.onDestroy();
    }

    public static boolean shouldAllowNavigation(String url, boolean allowLocalHttp) {
        return SpeedTestServicePreferences.isAllowedWebViewUrl(url, allowLocalHttp);
    }

    @SuppressWarnings("deprecation")
    private static void setLegacyFileUrlAccessDisabled(WebSettings settings) {
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return (int) (value * density + 0.5f);
    }
}
