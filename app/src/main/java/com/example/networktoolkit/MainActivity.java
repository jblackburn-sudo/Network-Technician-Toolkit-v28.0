package com.example.networktoolkit;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import com.example.networktoolkit.data.PingHistoryRepository;
import com.example.networktoolkit.data.SensitiveWifiIdentifiers;
import com.example.networktoolkit.data.StageExportResult;
import com.example.networktoolkit.data.StagedTextExport;
import com.example.networktoolkit.data.TextExportOutcome;
import com.example.networktoolkit.data.TextReportExportManager;
import com.example.networktoolkit.data.WifiReportExportPrivacyChoice;
import com.example.networktoolkit.learning.LearningGuideActivity;
import com.example.networktoolkit.learning.LearningGuideCatalog;
import com.example.networktoolkit.learning.LearningGuidePreferences;
import com.example.networktoolkit.learning.ToolGuideContent;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.net.LinkAddress;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Typeface;

public class MainActivity extends ComponentActivity {

    private LinearLayout mainLayout;

    private int backgroundColor;
    private int textColor;
    private int tileColor;

    private SharedPreferences preferences;

    private final Handler handler =
        new Handler(Looper.getMainLooper());

    private View headerMonitorCard;
    private TextView headerInternetBadge;
    private TextView headerTransportText;
    private TextView headerVpnText;
    private TextView headerGatewayText;
    private NetworkConnectivityMonitor.ConnectivityListener connectivityListener;

    private volatile String currentScreen = "home";
    private volatile boolean pingRunning = false;
    private volatile Process currentPingProcess = null;
    private volatile boolean tracerouteStopped = false;
    private volatile boolean lanScanRunning = false;

private ExecutorService lanScanExecutor;

private TextView lanScanResults;

private Button lanScanStartButton;

private Button lanScanStopButton;

private int lanScanFound = 0;

    private final CoroutinePingEngine pingEngine = new CoroutinePingEngine();
    private CoroutinePingEngine.PingMode selectedPingMode = CoroutinePingEngine.PingMode.AUTO;

    private EditText pingHostInput;
    private EditText pingCountInput;

    private TextView pingResults;
    private TextView pingSummary;
    private PingD3LatencyChartView pingD3ChartView;

    private Button pingStartButton;
    private Button pingStopButton;
    private Button pingModeAutoBtn;
    private Button pingModeIcmpBtn;
    private Button pingModeSocketBtn;

    private final List<Double> pingTimes =
        new ArrayList<>();

    private volatile int pingSent = 0;
    private volatile int pingReceived = 0;

    private static final int REQUEST_CODE_SAVE_PING_TXT = 4102;
    private static final String KEY_PENDING_PING_EXPORT_ID = "pending_ping_export_id";
    private static final String PING_SCREEN_KEY = "ping_screen";
    private String pendingPingExportId = null;
    private final ExecutorService exportIoExecutor = Executors.newSingleThreadExecutor();
    private OnBackPressedCallback backCallback;
    private AlertDialog onboardingDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            pendingPingExportId = savedInstanceState.getString(KEY_PENDING_PING_EXPORT_ID, null);
        }

        backCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                if (!"home".equals(currentScreen)) {
                    if ("ping".equals(currentScreen) || "traceroute".equals(currentScreen)) {
                        tracerouteStopped = true;
                        stopPingProcess();
                    } else if ("lan".equals(currentScreen)) {
                        stopLanScan();
                    }
                    buildHomeScreen();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        };
        getOnBackPressedDispatcher().addCallback(this, backCallback);

        preferences = getSharedPreferences(
            "ToolkitSettings", MODE_PRIVATE);

        applyColors();
        buildHomeScreen();
        maybeShowFirstLaunchOnboarding();
        exportIoExecutor.execute(() -> TextReportExportManager.cleanupStartupTempFiles(MainActivity.this));
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (pendingPingExportId != null) {
            outState.putString(KEY_PENDING_PING_EXPORT_ID, pendingPingExportId);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (connectivityListener != null) {
            NetworkConnectivityMonitor.Companion.getInstance(this).addListener(connectivityListener);
            NetworkConnectivityMonitor.Companion.getInstance(this).refreshNow();
        }
        maybeShowFirstLaunchOnboarding();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (connectivityListener != null) {
            NetworkConnectivityMonitor.Companion.getInstance(this).removeListener(connectivityListener);
        }
    }

    @Override
    protected void onDestroy() {
        if (onboardingDialog != null && onboardingDialog.isShowing()) {
            onboardingDialog.dismiss();
        }
        onboardingDialog = null;
        stopPingProcess();
        stopLanScan();
        destroyPingChartIfPresent();
        exportIoExecutor.shutdownNow();
        super.onDestroy();
    }

    private void destroyPingChartIfPresent() {
        if (pingD3ChartView != null) {
            pingD3ChartView.destroyChart();
            pingD3ChartView = null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_SAVE_PING_TXT) {
            final String exportId = pendingPingExportId;
            pendingPingExportId = null;
            if (resultCode != RESULT_OK || data == null || data.getData() == null) {
                exportIoExecutor.execute(() -> {
                    TextReportExportManager.handleSafPickerCancelled(
                        MainActivity.this, exportId, PING_SCREEN_KEY
                    );
                    handler.post(() -> Toast.makeText(
                        MainActivity.this,
                        "Export cancelled — no file saved",
                        Toast.LENGTH_SHORT
                    ).show());
                });
                return;
            }
            final Uri uri = data.getData();
            final String fallbackName = TextReportExportManager.suggestFilename("Ping_Report", "rtf");
            exportIoExecutor.execute(() -> {
                TextExportOutcome outcome = TextReportExportManager.exportStagedReportToUri(
                    MainActivity.this, exportId, uri, fallbackName, PING_SCREEN_KEY
                );
                handler.post(() -> {
                    if (outcome instanceof TextExportOutcome.Success) {
                        TextExportOutcome.Success ok = (TextExportOutcome.Success) outcome;
                        String privacyLabel = ok.isWifiUnredacted()
                            ? "unredacted SSID/BSSID for this export"
                            : "SSID/BSSID redacted";
                        Toast.makeText(
                            MainActivity.this,
                            "Saved " + ok.getDisplayName() + " (" + ok.getBytesWritten() + " bytes, " + privacyLabel + ")",
                            Toast.LENGTH_LONG
                        ).show();
                    } else if (outcome instanceof TextExportOutcome.Cancelled) {
                        Toast.makeText(MainActivity.this, "Export cancelled — no file saved", Toast.LENGTH_SHORT).show();
                    } else if (outcome instanceof TextExportOutcome.AlreadyProcessed) {
                        // Duplicate callback safely ignored
                    } else if (outcome instanceof TextExportOutcome.Failure) {
                        showExportFailureDialog((TextExportOutcome.Failure) outcome);
                    }
                });
            });
        }
    }

    // ==========================================
    // DISPLAY AND THEME
    // ==========================================

    private int dp(int value) {
        return (int)(value *
            getResources().getDisplayMetrics().density + 0.5f);
    }

    private void applyColors() {

        String theme = preferences.getString(
            "theme", "dark");

        boolean night =
            (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;

        if (theme.equals("light") ||
            (theme.equals("system") && !night)) {

            backgroundColor = Color.WHITE;
            textColor = Color.BLACK;
            tileColor = Color.rgb(230,230,230);

        } else {

            backgroundColor = Color.rgb(25,25,25);
            textColor = Color.WHITE;
            tileColor = Color.rgb(45,55,65);
        }
    }

    private void showThemeSettings() {

        final String[] themes = {
            "Dark",
            "Light",
            "System Default"
        };

        final String[] values = {
            "dark",
            "light",
            "system"
        };

        String current = preferences.getString(
            "theme", "dark");

        int selected = 0;

        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(current)) {
                selected = i;
                break;
            }
        }

        final int[] themeChoice = {selected};

        AppBatterySaverManager batterySaver = AppBatterySaverManager.getInstance(this);
        batterySaver.refreshBatteryStateFromSystem();
        BatterySaverAnimationMode[] batteryModes = BatterySaverAnimationMode.values();
        String currentBatKey = batterySaver.getModeKey();
        int initialBatChoice = 0;
        for (int i = 0; i < batteryModes.length; i++) {
            if (batteryModes[i].getPrefKey().equals(currentBatKey)) {
                initialBatChoice = i;
                break;
            }
        }
        final int[] batChoice = {initialBatChoice};

        final boolean initialShowSsid = WifiPrivacyPreferences.isShowSsidEnabled(this);
        final boolean initialShowBssid = WifiPrivacyPreferences.isShowBssidEnabled(this);

        ScrollView scrollView = new ScrollView(this);
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(20), dp(12), dp(20), dp(8));
        scrollView.addView(container);

        TextView themeHeading = new TextView(this);
        themeHeading.setText("Theme Mode");
        themeHeading.setTypeface(null, Typeface.BOLD);
        themeHeading.setPadding(0, dp(4), 0, dp(6));
        container.addView(themeHeading);

        android.widget.RadioGroup themeGroup = new android.widget.RadioGroup(this);
        for (int i = 0; i < themes.length; i++) {
            android.widget.RadioButton rb = new android.widget.RadioButton(this);
            rb.setId(View.generateViewId());
            rb.setText(themes[i]);
            final int idx = i;
            rb.setOnClickListener(v -> themeChoice[0] = idx);
            themeGroup.addView(rb);
            if (i == selected) {
                rb.setChecked(true);
            }
        }
        container.addView(themeGroup);

        TextView batteryHeading = new TextView(this);
        batteryHeading.setText("Global Battery-Saver Animation Policy");
        batteryHeading.setTypeface(null, Typeface.BOLD);
        batteryHeading.setPadding(0, dp(16), 0, dp(4));
        container.addView(batteryHeading);

        TextView batteryStatusInfo = new TextView(this);
        batteryStatusInfo.setText(batterySaver.getStatusSummary());
        batteryStatusInfo.setTextSize(12);
        batteryStatusInfo.setPadding(0, 0, 0, dp(6));
        container.addView(batteryStatusInfo);

        android.widget.RadioGroup batGroup = new android.widget.RadioGroup(this);
        for (int i = 0; i < batteryModes.length; i++) {
            android.widget.RadioButton rb = new android.widget.RadioButton(this);
            rb.setId(View.generateViewId());
            rb.setText(batteryModes[i].getTitle());
            final int idx = i;
            rb.setOnClickListener(v -> batChoice[0] = idx);
            batGroup.addView(rb);
            if (i == initialBatChoice) {
                rb.setChecked(true);
            }
        }
        container.addView(batGroup);

        TextView privacyHeading = new TextView(this);
        privacyHeading.setText("Wi-Fi Privacy");
        privacyHeading.setTypeface(null, Typeface.BOLD);
        privacyHeading.setPadding(0, dp(16), 0, dp(4));
        container.addView(privacyHeading);

        TextView privacySectionSummary = new TextView(this);
        privacySectionSummary.setText("Both controls are OFF by default and operate independently. Exported reports always default to redacted regardless of these on-screen toggles.");
        privacySectionSummary.setTextSize(12);
        privacySectionSummary.setPadding(0, 0, 0, dp(8));
        container.addView(privacySectionSummary);

        android.widget.Switch ssidSwitch = new android.widget.Switch(this);
        ssidSwitch.setId(View.generateViewId());
        ssidSwitch.setText("Show SSID");
        ssidSwitch.setContentDescription("Show SSID toggle");
        ssidSwitch.setChecked(initialShowSsid);
        ssidSwitch.setMinHeight(dp(48));
        container.addView(ssidSwitch);

        TextView ssidExplanation = new TextView(this);
        ssidExplanation.setText("Shows Wi-Fi network names (SSID) on diagnostic, scanner, and signal screens. Enabling Show SSID does not turn on Show BSSID.");
        ssidExplanation.setTextSize(12);
        ssidExplanation.setPadding(0, dp(2), 0, dp(10));
        container.addView(ssidExplanation);

        android.widget.Switch bssidSwitch = new android.widget.Switch(this);
        bssidSwitch.setId(View.generateViewId());
        bssidSwitch.setText("Show BSSID");
        bssidSwitch.setContentDescription("Show BSSID toggle");
        bssidSwitch.setChecked(initialShowBssid);
        bssidSwitch.setMinHeight(dp(48));
        container.addView(bssidSwitch);

        TextView bssidExplanation = new TextView(this);
        bssidExplanation.setText("Shows access point hardware MAC addresses (BSSID) on screen. Enabling Show BSSID does not turn on Show SSID.");
        bssidExplanation.setTextSize(12);
        bssidExplanation.setPadding(0, dp(2), 0, dp(6));
        container.addView(bssidExplanation);

        new AlertDialog.Builder(this)
            .setTitle("Settings & Appearance")
            .setView(scrollView)
            .setPositiveButton("Apply",
                (dialog, which) -> {

                    preferences.edit()
                        .putString("theme", values[themeChoice[0]])
                        .apply();
                    batterySaver.setMode(batteryModes[batChoice[0]], true);
                    WifiPrivacyPreferences.updateSettings(
                        MainActivity.this,
                        ssidSwitch.isChecked(),
                        bssidSwitch.isChecked()
                    );

                    applyColors();
                    buildHomeScreen();

                    Toast.makeText(
                        this,
                        "Settings & Wi-Fi privacy preferences updated",
                        Toast.LENGTH_SHORT
                    ).show();
                })
            .setNeutralButton("Learning Guide", (dialog, which) ->
                startActivity(LearningGuideActivity.createIntent(MainActivity.this)))
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void maybeShowFirstLaunchOnboarding() {
        if (isFinishing() || !LearningGuidePreferences.shouldShowFirstLaunchPrompt(this)) {
            return;
        }
        if (onboardingDialog != null && onboardingDialog.isShowing()) {
            return;
        }
        final String message =
            "Welcome to Network Technician Toolkit!\n\n" +
            "Would you like a guided introduction to the tools available in this application?\n\n" +
            "Whether you're new to networking or an experienced technician, this guide can help you " +
            "understand the tools, their terminology, and how to interpret their results.\n\n" +
            "You can customize the guide to focus on the tools you would like to learn more about.";

        onboardingDialog = new AlertDialog.Builder(this)
            .setTitle("Technician Learning Guide")
            .setMessage(message)
            .setCancelable(true)
            .setPositiveButton("Yes, show me around.", (dialog, which) -> {
                LearningGuidePreferences.markOnboardingCompleted(MainActivity.this);
                startActivity(LearningGuideActivity.createIntent(MainActivity.this));
            })
            .setNeutralButton("Customize my learning guide.", (dialog, which) -> {
                LearningGuidePreferences.markOnboardingCompleted(MainActivity.this);
                startActivity(LearningGuideActivity.createAssessmentIntent(MainActivity.this));
            })
            .setNegativeButton("Not right now.", (dialog, which) ->
                LearningGuidePreferences.markOnboardingSkipped(MainActivity.this))
            .setOnCancelListener(dialog ->
                LearningGuidePreferences.markOnboardingSkipped(MainActivity.this))
            .show();
    }

    private void showPingContextualHelpDialog() {
        if (isFinishing()) {
            return;
        }
        ToolGuideContent guide = LearningGuideCatalog.INSTANCE.findToolById("ping_test");
        StringBuilder body = new StringBuilder();
        if (guide != null) {
            body.append(guide.getWhatItDoes()).append("\n\n");
            body.append("Key Concepts:\n");
            for (String bullet : guide.getQuickHelpBullets()) {
                body.append("• ").append(bullet).append("\n");
            }
            body.append("\nQuick Tip: ").append(guide.getBeginnerTip());
        } else {
            body.append("Ping sends test packets (ICMP or TCP Socket) to measure round-trip latency (RTT), jitter (mdev), TTL, and packet loss.");
        }

        new AlertDialog.Builder(this)
            .setTitle("Ping Test Help & Guide")
            .setMessage(body.toString())
            .setPositiveButton("Open Full Walkthrough", (dialog, which) ->
                startActivity(LearningGuideActivity.createIntent(MainActivity.this, "ping_test")))
            .setNeutralButton("Networking Glossary", (dialog, which) ->
                startActivity(LearningGuideActivity.createGlossaryIntent(MainActivity.this)))
            .setNegativeButton("Close", null)
            .show();
    }

    // ==========================================
    // HOME SCREEN
    // ==========================================

    private void buildHomeScreen() {

        destroyPingChartIfPresent();
        currentScreen = "home";
        if (backCallback != null) {
            backCallback.setEnabled(false);
        }
        applyColors();

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(backgroundColor);

        mainLayout = new LinearLayout(this);
        mainLayout.setOrientation(
            LinearLayout.VERTICAL);

        mainLayout.setPadding(
            dp(16), dp(20), dp(16), dp(20));

        mainLayout.setBackgroundColor(
            backgroundColor);

        scroll.addView(mainLayout);
        setContentView(scroll);

        TextView title = new TextView(this);

        title.setText(
            "NETWORK TECHNICIAN TOOLKIT");

        title.setTextSize(23);
        title.setTextColor(textColor);
        title.setGravity(Gravity.CENTER);
        title.setTypeface(null, Typeface.BOLD);

        mainLayout.addView(title);

        TextView subtitle = new TextView(this);

        subtitle.setText(
            "FIELD TECHNICIAN • NETWORK TOOLS");

        subtitle.setTextColor(textColor);
        subtitle.setGravity(Gravity.CENTER);

        subtitle.setPadding(
            0, dp(8), 0, dp(14));

        mainLayout.addView(subtitle);

        headerMonitorCard = createHeaderConnectivityMonitorCard();
        mainLayout.addView(headerMonitorCard);

        Button settings = new Button(this);

        settings.setText("Settings & Appearance");
        settings.setAllCaps(false);

        settings.setOnClickListener(
            v -> showThemeSettings());

        mainLayout.addView(settings);

        Button learningGuideBtn = new Button(this);
        learningGuideBtn.setText("Technician Learning Guide & Glossary");
        learningGuideBtn.setAllCaps(false);
        learningGuideBtn.setOnClickListener(v ->
            startActivity(LearningGuideActivity.createIntent(MainActivity.this)));
        mainLayout.addView(learningGuideBtn);

        TextView heading = new TextView(this);

        heading.setText("NETWORK TOOLS");
        heading.setTextSize(18);
        heading.setTextColor(textColor);
        heading.setTypeface(null, Typeface.BOLD);

        heading.setPadding(
            0, dp(24), 0, dp(12));

        mainLayout.addView(heading);

        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(2);

        addTool(grid,
            "IP Address",
            "View network information");

        addTool(grid,
            "Ping Test",
            "Test connectivity");

        addTool(grid,
            "DNS Lookup",
            "Check DNS records");

        addTool(grid,
            "Gateway Info",
            "View network gateway");

        addTool(grid,
            "Wi-Fi Tools",
            "Wireless information");

        addTool(grid,
            "Wi-Fi Scanner",
            "Scan nearby APs & signal");

        addTool(grid,
            "Signal Mapper",
            "Map coverage in apt / house");

        addTool(grid,
            "Speed Test",
            "Measure connection speed");

        addTool(grid,
            "Traceroute",
            "Trace network routes");
            
        addTool(grid,
            "LAN Scanner",
            "Discover devices on your network");

        addTool(grid,
            "Subnet Calculator",
            "Calculate network ranges");

        addTool(grid,
            "Network Notes",
            "Field technician notes");

        addTool(grid,
            "iPerf3 Client / Server",
            "Network throughput test");

        addTool(grid,
            "Test History",
            "Diagnostic test records");

        mainLayout.addView(grid);
    }

    private void addTool(
        GridLayout grid,
        String name,
        String description) {

        Button button = new Button(this);

        button.setText(
            name + "\n" + description);

        button.setAllCaps(false);
        button.setTextSize(13);
        button.setTextColor(textColor);
        button.setBackgroundColor(tileColor);
        button.setMinHeight(dp(95));

        GridLayout.LayoutParams params =
            new GridLayout.LayoutParams();

        params.width = 0;
        params.height = dp(105);

        params.columnSpec =
            GridLayout.spec(
                GridLayout.UNDEFINED, 1f);

        params.setMargins(
            dp(4), dp(4), dp(4), dp(4));

        button.setLayoutParams(params);

        button.setOnClickListener(v -> {

            if (name.equals("IP Address") ||
                name.equals("Gateway Info") ||
                name.equals("Wi-Fi Tools")) {

                try {
                    Intent intent = new Intent(this, NetworkInfoActivity.class);
                    if (name.equals("Gateway Info")) {
                        intent.putExtra("target_section", "gateway");
                    } else if (name.equals("Wi-Fi Tools")) {
                        intent.putExtra("target_section", "wifi");
                    }
                    startActivity(intent);
                } catch (Exception e) {
                    showNetworkInfo();
                }

            } else if (name.equals("Ping Test")) {

                showPingScreen();

            } else if (name.equals("Traceroute")) {

                try {
                    Intent intent = new Intent(this, TracerouteActivity.class);
                    startActivity(intent);
                } catch (Exception e) {
                    showTracerouteScreen();
                }
                
            } else if (name.equals("LAN Scanner")) {

                try {
                    Intent intent = new Intent(this, LanScannerActivity.class);
                    startActivity(intent);
                } catch (Exception e) {
                    showLanScannerScreen();
                }    

            } else if (name.equals("Speed Test")) {

                try {
                    Intent intent = new Intent(this, com.example.networktoolkit.speedtest.SpeedTestActivity.class);
                    startActivity(intent);
                } catch (Exception e) {
                    com.example.networktoolkit.speedtest.SpeedTestServiceOption selectedService =
                        com.example.networktoolkit.speedtest.SpeedTestServicePreferences.getSelectedService(this);
                    try {
                        Intent webIntent = new Intent(this, WebTestActivity.class);
                        webIntent.putExtra("target_url", selectedService.getUrl());
                        webIntent.putExtra("target_title", selectedService.getName());
                        startActivity(webIntent);
                    } catch (Exception e2) {
                        openWebsite(selectedService.getUrl());
                    }
                }

            } else if (name.equals("Test History") || name.equals("Ping History")) {

                try {
                    Intent intent = new Intent(this, PingHistoryActivity.class);
                    startActivity(intent);
                } catch (Exception e) {
                    Toast.makeText(this, "Unable to open history", Toast.LENGTH_SHORT).show();
                }

            } else if (name.equals("DNS Lookup")) {

                try {
                    Intent intent = new Intent(this, DnsLookupActivity.class);
                    startActivity(intent);
                } catch (Exception e) {
                    Toast.makeText(this, "Unable to open DNS Lookup: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }

            } else if (name.equals("Subnet Calculator")) {

                try {
                    Intent intent = new Intent(this, SubnetCalculatorActivity.class);
                    startActivity(intent);
                } catch (Exception e) {
                    Toast.makeText(this, "Unable to open Subnet Calculator: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }

            } else if (name.equals("Network Notes")) {

                try {
                    Intent intent = new Intent(this, NetworkNotesActivity.class);
                    startActivity(intent);
                } catch (Exception e) {
                    Toast.makeText(this, "Unable to open Network Notes: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }

            } else if (name.equals("iPerf3 Client / Server") || name.contains("iPerf3")) {

                try {
                    Intent intent = new Intent(this, com.example.networktoolkit.iperf3.IPerf3Activity.class);
                    startActivity(intent);
                } catch (Exception e) {
                    Toast.makeText(this, "Unable to open iPerf3: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }

            } else if (name.equals("Wi-Fi Scanner") || name.contains("Wi-Fi Scan")) {

                try {
                    Intent intent = new Intent(this, com.example.networktoolkit.wifiscan.WifiScannerActivity.class);
                    startActivity(intent);
                } catch (Exception e) {
                    Toast.makeText(this, "Unable to open Wi-Fi Scanner: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }

            } else if (name.equals("Signal Mapper") || name.contains("Signal Map")) {

                try {
                    Intent intent = new Intent(this, com.example.networktoolkit.signalmap.SignalMapperActivity.class);
                    startActivity(intent);
                } catch (Exception e) {
                    Toast.makeText(this, "Unable to open Signal Mapper: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }

            } else {

                new AlertDialog.Builder(this)
                    .setTitle(name)
                    .setMessage(
                        description +
                        "\n\nThis tool is planned for a future update.")
                    .setPositiveButton("OK", null)
                    .show();
            }
        });

        grid.addView(button);
    }

    private View createHeaderConnectivityMonitorCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));

        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setCornerRadius(dp(10));
        cardBg.setColor(tileColor);
        card.setBackground(cardBg);

        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        cardParams.setMargins(0, 0, 0, dp(14));
        card.setLayoutParams(cardParams);

        // Header Row: "REAL-TIME DIAGNOSTICS" + "Refresh" button
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView label = new TextView(this);
        label.setText("LIVE CONNECTIVITY DIAGNOSTICS");
        label.setTextSize(11);
        label.setTypeface(null, Typeface.BOLD);
        label.setTextColor(Color.parseColor("#4FC3F7"));
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        headerRow.addView(label, labelParams);

        TextView refreshBtn = new TextView(this);
        refreshBtn.setText("REFRESH");
        refreshBtn.setTextSize(11);
        refreshBtn.setTypeface(null, Typeface.BOLD);
        refreshBtn.setTextColor(Color.parseColor("#64B5F6"));
        refreshBtn.setPadding(dp(8), dp(4), dp(8), dp(4));
        refreshBtn.setOnClickListener(v -> {
            Toast.makeText(this, "Refreshing network diagnostics...", Toast.LENGTH_SHORT).show();
            NetworkConnectivityMonitor.Companion.getInstance(this).refreshNow();
        });
        headerRow.addView(refreshBtn);
        card.addView(headerRow);

        // Internet Status Line
        headerInternetBadge = new TextView(this);
        headerInternetBadge.setText("Checking internet reachability...");
        headerInternetBadge.setTextSize(14);
        headerInternetBadge.setTypeface(null, Typeface.BOLD);
        headerInternetBadge.setTextColor(textColor);
        headerInternetBadge.setPadding(0, dp(6), 0, dp(4));
        card.addView(headerInternetBadge);

        // Transport & IP Line
        headerTransportText = new TextView(this);
        headerTransportText.setText("Network: Detecting...");
        headerTransportText.setTextSize(12);
        headerTransportText.setTextColor(textColor);
        headerTransportText.setPadding(0, 0, 0, dp(2));
        card.addView(headerTransportText);

        // VPN Status Line
        headerVpnText = new TextView(this);
        headerVpnText.setText("VPN: Detecting...");
        headerVpnText.setTextSize(12);
        headerVpnText.setTextColor(textColor);
        headerVpnText.setPadding(0, 0, 0, dp(2));
        card.addView(headerVpnText);

        // Gateway Status & Responsiveness Line
        headerGatewayText = new TextView(this);
        headerGatewayText.setText("Gateway: Probing RTT...");
        headerGatewayText.setTextSize(12);
        headerGatewayText.setTextColor(textColor);
        card.addView(headerGatewayText);

        if (connectivityListener != null) {
            NetworkConnectivityMonitor.Companion.getInstance(this).removeListener(connectivityListener);
        }
        connectivityListener = snapshot -> handler.post(() -> updateConnectivityCard(snapshot));
        NetworkConnectivityMonitor.Companion.getInstance(this).addListener(connectivityListener);

        card.setOnClickListener(v -> NetworkConnectivityMonitor.Companion.getInstance(this).refreshNow());

        return card;
    }

    private void updateConnectivityCard(ConnectivitySnapshot s) {
        if (headerInternetBadge == null) return;

        // Internet status
        switch (s.getInternetStatus()) {
            case ONLINE:
                headerInternetBadge.setText("● INTERNET ONLINE");
                headerInternetBadge.setTextColor(Color.parseColor("#4CAF50"));
                break;
            case CAPTIVE_OR_LIMITED:
                headerInternetBadge.setText("▲ LIMITED / CAPTIVE PORTAL");
                headerInternetBadge.setTextColor(Color.parseColor("#FF9800"));
                break;
            case OFFLINE:
                headerInternetBadge.setText("■ NO INTERNET ACCESS");
                headerInternetBadge.setTextColor(Color.parseColor("#F44336"));
                break;
            case CHECKING:
            default:
                headerInternetBadge.setText("○ VALIDATING INTERNET...");
                headerInternetBadge.setTextColor(Color.parseColor("#9E9E9E"));
                break;
        }

        // Connection Type & Active Network Interface
        String transport = s.getTransportName();
        String ifaceName = s.getActiveInterfaceName() != null ? s.getActiveInterfaceName() : s.getPhysicalInterfaceName();
        String iface = ifaceName != null ? " (" + ifaceName + ")" : "";
        String ip = s.getLocalIp() != null ? " • IP: " + s.getLocalIp() : " • No IP";
        headerTransportText.setText("Connection Type: " + transport + iface + ip);

        // VPN Status
        if (s.isVpnActive()) {
            String vpnIface = s.getVpnInterfaceName() != null ? " (" + s.getVpnInterfaceName() + ")" : "";
            headerVpnText.setText("VPN Status: Active" + vpnIface + " (Virtual Tunnel)");
            headerVpnText.setTextColor(Color.parseColor("#26C6DA"));
        } else {
            headerVpnText.setText("VPN Status: Inactive (Unencapsulated Route)");
            headerVpnText.setTextColor(textColor);
        }

        // Default Gateway Reachability (local subnet router only)
        String gwIp = s.getGatewayIp() != null ? s.getGatewayIp() : "Unavailable";
        switch (s.getGatewayStatus()) {
            case RESPONSIVE:
                String rtt = s.getGatewayResponsivenessMs() != null ? s.getGatewayResponsivenessMs() + " ms RTT" : "Responsive";
                headerGatewayText.setText("Default Gateway: " + gwIp + " • " + rtt + " (Local Router Reachable)");
                headerGatewayText.setTextColor(Color.parseColor("#81C784"));
                break;
            case UNRESPONSIVE:
                headerGatewayText.setText("Default Gateway: " + gwIp + " • Unresponsive to probes");
                headerGatewayText.setTextColor(Color.parseColor("#EF5350"));
                break;
            case SUPPRESSED_BY_VPN:
                headerGatewayText.setText("Default Gateway: " + gwIp + " • Routed through VPN tunnel");
                headerGatewayText.setTextColor(Color.parseColor("#B0BEC5"));
                break;
            case NO_GATEWAY:
                headerGatewayText.setText("Default Gateway: Unavailable (No gateway assigned)");
                headerGatewayText.setTextColor(textColor);
                break;
            case CHECKING:
            default:
                headerGatewayText.setText("Default Gateway: " + gwIp + " • Probing latency...");
                headerGatewayText.setTextColor(textColor);
                break;
        }
    }

    private void openWebsite(String url) {

        try {

            Intent intent = new Intent(
                Intent.ACTION_VIEW,
                Uri.parse(url));

            startActivity(intent);

        } catch (Exception e) {

            Toast.makeText(
                this,
                "Unable to open website.",
                Toast.LENGTH_SHORT
            ).show();
        }
    }

    // ==========================================
    // NETWORK INFORMATION
    // ==========================================

    private void showNetworkInfo() {

        currentScreen = "info";
        if (backCallback != null) {
            backCallback.setEnabled(true);
        }
        applyColors();

        ScrollView scroll = new ScrollView(this);

        LinearLayout layout = new LinearLayout(this);

        layout.setOrientation(
            LinearLayout.VERTICAL);

        layout.setPadding(
            dp(16), dp(16), dp(16), dp(16));

        layout.setBackgroundColor(backgroundColor);

        scroll.addView(layout);
        setContentView(scroll);

        TextView title = new TextView(this);

        title.setText("NETWORK INFORMATION");
        title.setTextSize(22);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(textColor);
        title.setGravity(Gravity.CENTER);

        layout.addView(title);

        Button refresh = new Button(this);

        refresh.setText(
            "Refresh Network Information");

        refresh.setAllCaps(false);

        layout.addView(refresh);

        refresh.setOnClickListener(
            v -> showNetworkInfo());

        Button home = new Button(this);

        home.setText("Back to Home");
        home.setAllCaps(false);

        layout.addView(home);

        home.setOnClickListener(
            v -> buildHomeScreen());

        addSection(layout, "CONNECTION INFORMATION");

        ConnectivityManager cm =
            (ConnectivityManager)getSystemService(
                Context.CONNECTIVITY_SERVICE);

        if (cm == null) {

            addValue(layout,
                "Connection Type", NetworkConnectionIdentifier.CONNECTION_DISCONNECTED);

            return;
        }

        Network network = cm.getActiveNetwork();

        if (network == null) {

            addValue(layout,
                "Connection Type", NetworkConnectionIdentifier.CONNECTION_DISCONNECTED);
            addValue(layout,
                "Connection Status", "Disconnected");

            return;
        }

        LinkProperties lp =
            cm.getLinkProperties(network);

        NetworkCapabilities caps =
            cm.getNetworkCapabilities(network);

        NetworkConnectionIdentifier.ActiveNetworkClassification classification =
            NetworkConnectionIdentifier.inspectActiveConnectivity(cm);

        addValue(layout,
            "Connection Type", classification.getConnectionType());

        addValue(layout,
            "Network Transport", classification.getNetworkTransport());

        addValue(layout,
            "VPN Status",
            classification.isVpnActive()
                ? "Active (Virtual Tunnel)"
                : "Inactive (Unencapsulated Route)");

        if (classification.isVpnActive() && classification.getUnderlyingTransport() != null) {
            addValue(layout,
                "Underlying Connection", classification.getUnderlyingTransport());
        }

        if (caps != null) {

            boolean isValidated = caps.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_VALIDATED);

            addValue(layout,
                "Connection Status",
                isValidated ? "Connected (Internet Validated)" : "Connected (Unvalidated)");

            addValue(layout,
                "Internet Capability",
                caps.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    ? "Available" : "Not reported");

            addValue(layout,
                "Metered Connection",
                caps.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                    ? "Unmetered" : "Metered or unknown");

        } else {

            addValue(layout,
                "Connection Status", "Unknown");
        }

        if (lp == null) {

            addValue(layout,
                "Network Details",
                "Information unavailable");

            return;
        }

        String interfaceName =
            NetworkConnectionIdentifier.resolveInterfaceName(lp);

        addValue(layout,
            "Active Network Interface",
            interfaceName == null
                ? "Unavailable"
                : interfaceName);

        // IP CONFIGURATION

        addSection(layout, "IP CONFIGURATION");

        boolean found4 = false;
        boolean found6 = false;
        Integer firstIpv4Prefix = null;

        List<android.net.LinkAddress> addresses =
            lp.getLinkAddresses();

        for (android.net.LinkAddress address : addresses) {

            InetAddress ip = address.getAddress();

            if (ip == null ||
                ip.isLoopbackAddress()) {
                continue;
            }

            String value =
                ip.getHostAddress() +
                "/" + address.getPrefixLength();

            if (ip instanceof Inet4Address) {

                addValue(layout,
                    "IPv4 Address", value);
                if (firstIpv4Prefix == null) {
                    firstIpv4Prefix = address.getPrefixLength();
                }

                found4 = true;

            } else if (ip instanceof Inet6Address) {

                addValue(layout,
                    "IPv6 Address", value);

                found6 = true;
            }
        }

        if (!found4) {

            addValue(layout,
                "IPv4 Address",
                "Unavailable");
        }

        String subnetMask = firstIpv4Prefix != null
            ? NetworkConnectionIdentifier.prefixLengthToIpv4SubnetMask(firstIpv4Prefix)
            : null;
        addValue(layout,
            "Subnet Mask",
            subnetMask != null ? subnetMask : "Unavailable");
        addValue(layout,
            "Prefix Length",
            firstIpv4Prefix != null ? "/" + firstIpv4Prefix : "Unavailable");

        if (!found6) {

            addValue(layout,
                "IPv6 Address",
                "No IPv6 Assigned");
        }

        String mtuText = "Unavailable";
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q && lp.getMtu() > 0) {
            mtuText = lp.getMtu() + " bytes";
        }
        addValue(layout,
            "MTU", mtuText);

        // DEFAULT GATEWAY

        addSection(layout, "DEFAULT GATEWAY");

        boolean gateway4 = false;
        boolean gateway6 = false;

        for (RouteInfo route : lp.getRoutes()) {

            if (route.isDefaultRoute() &&
                route.getGateway() != null) {

                InetAddress gateway =
                    route.getGateway();

                if (gateway instanceof Inet4Address) {

                    if (!gateway4) {

                        addValue(layout,
                            "Default Gateway (IPv4)",
                            gateway.getHostAddress());
                    }

                    gateway4 = true;

                } else if (gateway instanceof Inet6Address) {

                    if (!gateway6) {

                        addValue(layout,
                            "Default Gateway (IPv6)",
                            gateway.getHostAddress());
                    }

                    gateway6 = true;
                }
            }
        }

        if (!gateway4) {

            addValue(layout,
                "Default Gateway (IPv4)", "Unavailable");
        }

        if (!gateway6) {

            addValue(layout,
                "Default Gateway (IPv6)", "Unavailable");
        }

        // DNS SERVERS

        addSection(layout, "DNS SERVERS");

        List<InetAddress> dnsServers =
            lp.getDnsServers();

        if (dnsServers.isEmpty()) {

            addValue(layout,
                "DNS Servers", "Unavailable");

        } else {

            int dnsNumber = 1;

            for (InetAddress dns : dnsServers) {

                addValue(layout,
                    "DNS Server " + dnsNumber,
                    dns.getHostAddress());

                dnsNumber++;
            }
        }

        // CONNECTION-SPECIFIC INFORMATION
        if (classification.isCellular()) {
            addSection(layout, "MOBILE DATA DETAILS");
            NetworkConnectionIdentifier.CellularTelemetry cell =
                NetworkConnectionIdentifier.readCellularTelemetry(this);
            addValue(layout, "Mobile Network Type",
                cell.getNetworkType() != null ? cell.getNetworkType() : "Unavailable");
            addValue(layout, "Network Operator",
                cell.getOperatorName() != null ? cell.getOperatorName() : "Unavailable");
        } else if (classification.isEthernet()) {
            addSection(layout, "ETHERNET DETAILS");
            addValue(layout, "Ethernet Interface",
                interfaceName != null ? interfaceName : "Unavailable");
            addValue(layout, "Link Speed", "Unavailable (Not exposed via standard SDK)");
        }
    }

    private String joinStrings(
        List<String> values,
        String separator) {

        StringBuilder result =
            new StringBuilder();

        for (String value : values) {

            if (result.length() > 0) {
                result.append(separator);
            }

            result.append(value);
        }

        return result.toString();
    }

    // ==========================================
    // REUSABLE DISPLAY COMPONENTS
    // ==========================================

    private void addSection(
        LinearLayout layout,
        String heading) {

        TextView section = new TextView(this);

        section.setText(heading);
        section.setTextSize(16);
        section.setTextColor(textColor);
        section.setTypeface(null, Typeface.BOLD);

        section.setPadding(
            0, dp(20), 0, dp(8));

        layout.addView(section);
    }

    private void addValue(
        LinearLayout layout,
        String label,
        String value) {

        LinearLayout row =
            new LinearLayout(this);

        row.setOrientation(
            LinearLayout.HORIZONTAL);

        row.setGravity(Gravity.CENTER_VERTICAL);

        row.setPadding(
            dp(8), dp(8), dp(4), dp(8));

        row.setBackgroundColor(tileColor);

        LinearLayout textColumn =
            new LinearLayout(this);

        textColumn.setOrientation(
            LinearLayout.VERTICAL);

        TextView labelView =
            new TextView(this);

        labelView.setText(label);
        labelView.setTextSize(12);
        labelView.setTextColor(Color.GRAY);

        TextView valueView =
            new TextView(this);

        valueView.setText(value);
        valueView.setTextSize(14);
        valueView.setTextColor(textColor);
        valueView.setTextIsSelectable(true);

        textColumn.addView(labelView);
        textColumn.addView(valueView);

        row.addView(textColumn,
            new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f));

        Button copy = new Button(this);

        copy.setText("Copy");
        copy.setAllCaps(false);
        copy.setTextSize(11);

        copy.setOnClickListener(v -> {
            if (TextReportExportManager.copySensitiveToClipboard(this, label, value, true)) {
                Toast.makeText(
                    this,
                    label + " copied",
                    Toast.LENGTH_SHORT
                ).show();
            }
        });

        row.addView(copy);

        LinearLayout.LayoutParams params =
            new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);

        params.setMargins(
            0, dp(3), 0, dp(3));

        layout.addView(row, params);
    }
        // ==========================================
    // PING TEST SCREEN
    // ==========================================

    private void showPingScreen() {

        currentScreen = "ping";
        if (backCallback != null) {
            backCallback.setEnabled(true);
        }
        applyColors();

        ScrollView scroll = new ScrollView(this);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(16), dp(16), dp(16), dp(16));
        layout.setBackgroundColor(backgroundColor);

        scroll.addView(layout);
        setContentView(scroll);

        TextView title = new TextView(this);
        title.setText("PING TEST (COROUTINES)");
        title.setTextSize(22);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(textColor);
        title.setGravity(Gravity.CENTER);

        layout.addView(title);

        TextView description = new TextView(this);
        description.setText(
            "Test network latency & packet reachability to any IP or domain with non-blocking Kotlin Coroutines.");
        description.setTextColor(textColor);
        description.setPadding(0, dp(8), 0, dp(12));

        layout.addView(description);

        addSection(layout, "TARGET HOST OR DOMAIN");

        pingHostInput = new EditText(this);
        pingHostInput.setSingleLine(true);
        pingHostInput.setText("8.8.8.8");
        pingHostInput.setHint("IP address or domain (e.g. 8.8.8.8, google.com)");
        pingHostInput.setTextColor(textColor);
        pingHostInput.setHintTextColor(Color.GRAY);
        pingHostInput.setInputType(
            InputType.TYPE_CLASS_TEXT |
            InputType.TYPE_TEXT_VARIATION_URI);

        layout.addView(pingHostInput);

        // Target Preset Chips
        LinearLayout targetPresets = new LinearLayout(this);
        targetPresets.setOrientation(LinearLayout.HORIZONTAL);
        targetPresets.setPadding(0, dp(4), 0, dp(12));

        addPingPresetChip(targetPresets, "8.8.8.8", "8.8.8.8");
        addPingPresetChip(targetPresets, "1.1.1.1", "1.1.1.1");
        addPingPresetChip(targetPresets, "google.com", "google.com");
        addPingPresetChip(targetPresets, "Gateway", "GATEWAY");

        layout.addView(targetPresets);

        addSection(layout, "PING PROTOCOL MODE");

        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        modeRow.setPadding(0, dp(4), 0, dp(12));

        pingModeAutoBtn = new Button(this);
        pingModeAutoBtn.setText("Auto (Smart)");
        pingModeAutoBtn.setAllCaps(false);
        LinearLayout.LayoutParams pAuto = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        pAuto.setMargins(dp(2), 0, dp(2), 0);
        pingModeAutoBtn.setLayoutParams(pAuto);
        pingModeAutoBtn.setOnClickListener(v -> {
            selectedPingMode = CoroutinePingEngine.PingMode.AUTO;
            updatePingModeButtons();
        });
        modeRow.addView(pingModeAutoBtn);

        pingModeIcmpBtn = new Button(this);
        pingModeIcmpBtn.setText("ICMP Only");
        pingModeIcmpBtn.setAllCaps(false);
        LinearLayout.LayoutParams pIcmp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        pIcmp.setMargins(dp(2), 0, dp(2), 0);
        pingModeIcmpBtn.setLayoutParams(pIcmp);
        pingModeIcmpBtn.setOnClickListener(v -> {
            selectedPingMode = CoroutinePingEngine.PingMode.ICMP;
            updatePingModeButtons();
        });
        modeRow.addView(pingModeIcmpBtn);

        pingModeSocketBtn = new Button(this);
        pingModeSocketBtn.setText("Socket (TCP)");
        pingModeSocketBtn.setAllCaps(false);
        LinearLayout.LayoutParams pSocket = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        pSocket.setMargins(dp(2), 0, dp(2), 0);
        pingModeSocketBtn.setLayoutParams(pSocket);
        pingModeSocketBtn.setOnClickListener(v -> {
            selectedPingMode = CoroutinePingEngine.PingMode.SOCKET;
            updatePingModeButtons();
        });
        modeRow.addView(pingModeSocketBtn);

        layout.addView(modeRow);
        updatePingModeButtons();

        addSection(layout, "PACKET COUNT");

        LinearLayout countRow = new LinearLayout(this);
        countRow.setOrientation(LinearLayout.HORIZONTAL);
        countRow.setGravity(Gravity.CENTER_VERTICAL);

        pingCountInput = new EditText(this);
        pingCountInput.setSingleLine(true);
        pingCountInput.setText("4");
        pingCountInput.setHint("Count (1-100)");
        pingCountInput.setTextColor(textColor);
        pingCountInput.setHintTextColor(Color.GRAY);
        pingCountInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        LinearLayout.LayoutParams countParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        pingCountInput.setLayoutParams(countParams);
        countRow.addView(pingCountInput);

        addPingCountPreset(countRow, "4", 4);
        addPingCountPreset(countRow, "10", 10);
        addPingCountPreset(countRow, "20", 20);
        addPingCountPreset(countRow, "Cont.", 100);

        layout.addView(countRow);

        // Action Buttons Row
        LinearLayout actionsRow = new LinearLayout(this);
        actionsRow.setOrientation(LinearLayout.HORIZONTAL);
        actionsRow.setPadding(0, dp(12), 0, dp(8));

        pingStartButton = new Button(this);
        pingStartButton.setText("Start Ping");
        pingStartButton.setAllCaps(false);
        LinearLayout.LayoutParams pStart = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        pStart.setMargins(dp(2), 0, dp(2), 0);
        pingStartButton.setLayoutParams(pStart);
        actionsRow.addView(pingStartButton);

        pingStopButton = new Button(this);
        pingStopButton.setText("Stop Ping");
        pingStopButton.setAllCaps(false);
        pingStopButton.setEnabled(false);
        LinearLayout.LayoutParams pStop = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        pStop.setMargins(dp(2), 0, dp(2), 0);
        pingStopButton.setLayoutParams(pStop);
        actionsRow.addView(pingStopButton);

        layout.addView(actionsRow);

        // Utility Buttons Row: Copy & Clear
        LinearLayout utilRow = new LinearLayout(this);
        utilRow.setOrientation(LinearLayout.HORIZONTAL);
        utilRow.setPadding(0, 0, 0, dp(12));

        Button copyBtn = new Button(this);
        copyBtn.setText("Copy Results");
        copyBtn.setAllCaps(false);
        LinearLayout.LayoutParams pCopy = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        pCopy.setMargins(dp(2), 0, dp(2), 0);
        copyBtn.setLayoutParams(pCopy);
        copyBtn.setOnClickListener(v -> {
            String text = (pingSummary != null ? pingSummary.getText() : "") + "\n\n" +
                          (pingResults != null ? pingResults.getText() : "");
            SensitiveWifiIdentifiers identifiers = TextReportExportManager.collectIdentifiers(
                this,
                java.util.Collections.emptyList(),
                java.util.Collections.emptyList()
            );
            String safeText = TextReportExportManager.applyPrivacyRedaction(text, identifiers);
            if (TextReportExportManager.copySensitiveToClipboard(this, "Ping Results", safeText, true)) {
                Toast.makeText(this, "Redacted ping results copied to clipboard", Toast.LENGTH_SHORT).show();
            }
        });
        utilRow.addView(copyBtn);

        Button clearBtn = new Button(this);
        clearBtn.setText("Clear Log");
        clearBtn.setAllCaps(false);
        LinearLayout.LayoutParams pClear = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        pClear.setMargins(dp(2), 0, dp(2), 0);
        clearBtn.setLayoutParams(pClear);
        clearBtn.setOnClickListener(v -> {
            if (pingResults != null) pingResults.setText("");
            if (pingSummary != null) pingSummary.setText("Log cleared. Ready for next test.");
            if (pingD3ChartView != null) pingD3ChartView.resetChart("");
        });
        utilRow.addView(clearBtn);

        Button historyBtn = new Button(this);
        historyBtn.setText("Saved History");
        historyBtn.setAllCaps(false);
        LinearLayout.LayoutParams pHist = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        pHist.setMargins(dp(2), 0, dp(2), 0);
        historyBtn.setLayoutParams(pHist);
        historyBtn.setOnClickListener(v -> {
            try {
                Intent intent = new Intent(this, PingHistoryActivity.class);
                startActivity(intent);
            } catch (Exception e) {
                Toast.makeText(this, "Unable to open history: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
        utilRow.addView(historyBtn);

        layout.addView(utilRow);

        // Export & Share Row (SAF RTF + Privacy-Redacted Share)
        LinearLayout exportRow = new LinearLayout(this);
        exportRow.setOrientation(LinearLayout.HORIZONTAL);
        exportRow.setPadding(0, 0, 0, dp(12));

        Button saveTxtBtn = new Button(this);
        saveTxtBtn.setText("Save RTF");
        saveTxtBtn.setAllCaps(false);
        LinearLayout.LayoutParams pSaveTxt = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        pSaveTxt.setMargins(dp(2), 0, dp(2), 0);
        saveTxtBtn.setLayoutParams(pSaveTxt);
        saveTxtBtn.setOnClickListener(v -> launchSavePingTxt());
        exportRow.addView(saveTxtBtn);

        Button shareTxtBtn = new Button(this);
        shareTxtBtn.setText("Share RTF");
        shareTxtBtn.setAllCaps(false);
        LinearLayout.LayoutParams pShareTxt = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        pShareTxt.setMargins(dp(2), 0, dp(2), 0);
        shareTxtBtn.setLayoutParams(pShareTxt);
        shareTxtBtn.setOnClickListener(v -> shareRedactedPingTxt());
        exportRow.addView(shareTxtBtn);

        Button pingHelpBtn = new Button(this);
        pingHelpBtn.setText("Ping Help");
        pingHelpBtn.setAllCaps(false);
        LinearLayout.LayoutParams pHelp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        pHelp.setMargins(dp(2), 0, dp(2), 0);
        pingHelpBtn.setLayoutParams(pHelp);
        pingHelpBtn.setOnClickListener(v -> showPingContextualHelpDialog());
        exportRow.addView(pingHelpBtn);

        layout.addView(exportRow);

        addSection(layout, "REAL-TIME METRICS & SUMMARY");

        pingSummary = new TextView(this);
        pingSummary.setText("Ready to start ping test.");
        pingSummary.setTextSize(14);
        pingSummary.setTextColor(textColor);
        pingSummary.setPadding(dp(8), dp(8), dp(8), dp(8));
        pingSummary.setBackgroundColor(tileColor);

        layout.addView(pingSummary);

        addSection(layout, "LATENCY TRENDS (D3 LINE CHART)");

        pingD3ChartView = new PingD3LatencyChartView(this);
        LinearLayout.LayoutParams pChart = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(240)
        );
        pChart.setMargins(0, dp(4), 0, dp(12));
        pingD3ChartView.setLayoutParams(pChart);
        layout.addView(pingD3ChartView);

        addSection(layout, "DETAILED PACKET STREAM");

        pingResults = new TextView(this);
        pingResults.setTextSize(12);
        pingResults.setTypeface(android.graphics.Typeface.MONOSPACE);
        pingResults.setTextColor(textColor);
        pingResults.setTextIsSelectable(true);
        pingResults.setPadding(dp(10), dp(10), dp(10), dp(10));
        pingResults.setBackgroundColor(tileColor);
        pingResults.setMinLines(6);

        layout.addView(pingResults);

        Button home = new Button(this);
        home.setText("Back to Home");
        home.setAllCaps(false);
        LinearLayout.LayoutParams pHome = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pHome.setMargins(0, dp(16), 0, dp(16));
        home.setLayoutParams(pHome);

        layout.addView(home);

        home.setOnClickListener(v -> {
            stopPingProcess();
            buildHomeScreen();
        });

        pingStartButton.setOnClickListener(v -> startPingTest());
        pingStopButton.setOnClickListener(v -> stopPingProcess());
    }

    private String buildPingTxtReport() {
        String host = pingHostInput != null ? pingHostInput.getText().toString().trim() : "";
        String count = pingCountInput != null ? pingCountInput.getText().toString().trim() : "";
        String summary = pingSummary != null ? pingSummary.getText().toString().trim() : "";
        String stream = pingResults != null ? pingResults.getText().toString().trim() : "";
        if (summary.isEmpty() && stream.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("============================================================\n");
        sb.append("           NETWORK TOOLKIT - PING DIAGNOSTIC REPORT\n");
        sb.append("============================================================\n");
        sb.append("Target Host     : ").append(host.isEmpty() ? "N/A" : host).append("\n");
        sb.append("Ping Count      : ").append(count.isEmpty() ? "N/A" : count).append("\n");
        sb.append("Mode            : ").append(selectedPingMode.name()).append("\n");
        sb.append("Device          : ").append(android.os.Build.MANUFACTURER).append(" ").append(android.os.Build.MODEL)
            .append(" (Android ").append(android.os.Build.VERSION.RELEASE).append(")\n");
        sb.append("\n--- SUMMARY ---\n");
        sb.append(summary.isEmpty() ? "N/A" : summary).append("\n");
        if (!stream.isEmpty()) {
            sb.append("\n--- PACKET STREAM ---\n");
            sb.append(stream).append("\n");
        }
        sb.append("============================================================\n");
        return sb.toString();
    }

    private interface WifiExportPrivacyCallback {
        void onChosen(WifiReportExportPrivacyChoice choice);
    }

    private void showWifiExportPrivacyDialog(String actionLabel, WifiExportPrivacyCallback callback) {
        final WifiReportExportPrivacyChoice[] selectedChoice = {WifiReportExportPrivacyChoice.REDACTED};

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(20), dp(12), dp(20), dp(8));

        TextView subtitle = new TextView(this);
        subtitle.setText("Choose how Wi-Fi SSID and BSSID are handled for this " + actionLabel + ". On-screen visibility toggles do not change export redaction automatically.");
        subtitle.setTextSize(13);
        subtitle.setPadding(0, 0, 0, dp(10));
        container.addView(subtitle);

        android.widget.RadioGroup group = new android.widget.RadioGroup(this);
        android.widget.RadioButton optA = new android.widget.RadioButton(this);
        optA.setId(View.generateViewId());
        optA.setText("Option A — Redacted (Recommended)\nRedacts SSID and BSSID from exported report.");
        optA.setMinHeight(dp(48));
        optA.setOnClickListener(v -> selectedChoice[0] = WifiReportExportPrivacyChoice.REDACTED);
        group.addView(optA);

        android.widget.RadioButton optB = new android.widget.RadioButton(this);
        optB.setId(View.generateViewId());
        optB.setText("Option B — Include SSID and BSSID (Unredacted)\nIncludes readable network identifiers for this export only.");
        optB.setMinHeight(dp(48));
        optB.setOnClickListener(v -> selectedChoice[0] = WifiReportExportPrivacyChoice.UNREDACTED_CONFIRMED);
        group.addView(optB);

        optA.setChecked(true);
        container.addView(group);

        TextView note = new TextView(this);
        note.setText("Note: Selecting Option B requires confirmation because the exported file will contain readable, unencrypted Wi-Fi network identification.");
        note.setTextSize(12);
        note.setPadding(0, dp(8), 0, 0);
        container.addView(note);

        new AlertDialog.Builder(this)
            .setTitle("Export Privacy Options")
            .setView(container)
            .setPositiveButton("Continue", (d, w) -> {
                if (selectedChoice[0] == WifiReportExportPrivacyChoice.REDACTED) {
                    callback.onChosen(WifiReportExportPrivacyChoice.REDACTED);
                } else {
                    new AlertDialog.Builder(this)
                        .setTitle("Confirm Unredacted Export")
                        .setMessage("Warning: This exported report will contain readable, unencrypted Wi-Fi network identification information (SSID and BSSID).\n\nThis applies only to the current export operation and will reset to Redacted (Recommended) afterward. Existing credential encryption remains protected.")
                        .setPositiveButton("Confirm & Export Unredacted", (d2, w2) ->
                            callback.onChosen(WifiReportExportPrivacyChoice.UNREDACTED_CONFIRMED))
                        .setNegativeButton("Cancel", null)
                        .show();
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void launchSavePingTxt() {
        final String rawReport = buildPingTxtReport();
        if (rawReport.trim().isEmpty()) {
            showExportFailureDialog(TextReportExportManager.classifyException(
                new IllegalArgumentException("Cannot export an empty report."),
                "Preparing Ping RTF report for SAF export"
            ));
            return;
        }
        showWifiExportPrivacyDialog("Ping RTF export", privacyChoice -> executeSavePingTxt(rawReport, privacyChoice));
    }

    private void executeSavePingTxt(final String rawReport, final WifiReportExportPrivacyChoice privacyChoice) {
        final String filename = TextReportExportManager.suggestFilename("Ping_Report", "rtf");
        exportIoExecutor.execute(() -> {
            SensitiveWifiIdentifiers ids = TextReportExportManager.collectIdentifiers(
                MainActivity.this, java.util.Collections.emptyList(), java.util.Collections.emptyList()
            );
            StageExportResult stageResult = TextReportExportManager.stageTextReportForExport(
                MainActivity.this, rawReport, ids, filename, PING_SCREEN_KEY, privacyChoice
            );
            handler.post(() -> {
                if (stageResult instanceof StageExportResult.Failed) {
                    showExportFailureDialog(((StageExportResult.Failed) stageResult).getFailure());
                    return;
                }
                StagedTextExport staged = ((StageExportResult.Ready) stageResult).getStaged();
                pendingPingExportId = staged.getExportId();
                try {
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType(TextReportExportManager.MIME_RTF);
                    intent.putExtra(Intent.EXTRA_TITLE, staged.getSuggestedFilename());
                    startActivityForResult(intent, REQUEST_CODE_SAVE_PING_TXT);
                } catch (ActivityNotFoundException e) {
                    final String cancelId = pendingPingExportId;
                    pendingPingExportId = null;
                    exportIoExecutor.execute(() -> TextReportExportManager.handleSafPickerCancelled(
                        MainActivity.this, cancelId, PING_SCREEN_KEY
                    ));
                    showExportFailureDialog(TextReportExportManager.classifyException(
                        e, "Opening Android Storage Access Framework file picker"
                    ));
                } catch (Throwable t) {
                    final String cancelId = pendingPingExportId;
                    pendingPingExportId = null;
                    exportIoExecutor.execute(() -> TextReportExportManager.handleSafPickerCancelled(
                        MainActivity.this, cancelId, PING_SCREEN_KEY
                    ));
                    showExportFailureDialog(TextReportExportManager.classifyException(
                        t, "Preparing Ping RTF report for SAF export"
                    ));
                }
            });
        });
    }

    private void shareRedactedPingTxt() {
        final String rawReport = buildPingTxtReport();
        if (rawReport.trim().isEmpty()) {
            showExportFailureDialog(TextReportExportManager.classifyException(
                new IllegalArgumentException("Cannot export an empty report."),
                "Preparing Ping RTF report for sharing"
            ));
            return;
        }
        showWifiExportPrivacyDialog("Ping RTF share", privacyChoice -> executeSharePingTxt(rawReport, privacyChoice));
    }

    private void executeSharePingTxt(final String rawReport, final WifiReportExportPrivacyChoice privacyChoice) {
        String filename = TextReportExportManager.suggestFilename("Ping_Report", "rtf");
        TextReportExportManager.ShareIntentPreparation prep = TextReportExportManager.prepareShareIntentWithPrivacy(
            this,
            rawReport,
            filename,
            "Network Technician Toolkit - Ping Report",
            "Share Ping Diagnostic Report",
            privacyChoice
        );
        if (prep.getChooserIntent() != null) {
            try {
                startActivity(prep.getChooserIntent());
            } catch (Throwable t) {
                showExportFailureDialog(TextReportExportManager.classifyException(
                    t, "Launching Android share chooser"
                ));
            }
        } else if (prep.getFailure() != null) {
            showExportFailureDialog(prep.getFailure());
        }
    }

    private void showExportFailureDialog(TextExportOutcome.Failure failure) {
        if (failure == null || isFinishing()) return;
        new AlertDialog.Builder(this)
            .setTitle(failure.getCategory().getTitle())
            .setMessage(failure.getFormattedDiagnostics())
            .setPositiveButton("Dismiss", null)
            .show();
    }

    private void addPingPresetChip(LinearLayout parent, String label, String value) {
        Button chip = new Button(this);
        chip.setText(label);
        chip.setAllCaps(false);
        chip.setTextSize(11);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        p.setMargins(dp(2), 0, dp(2), 0);
        chip.setLayoutParams(p);
        chip.setOnClickListener(v -> {
            if (value.equals("GATEWAY")) {
                String gw = detectDefaultGateway();
                if (gw != null && !gw.isEmpty()) {
                    pingHostInput.setText(gw);
                    Toast.makeText(this, "Detected gateway: " + gw, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "Default gateway could not be detected", Toast.LENGTH_SHORT).show();
                }
            } else {
                pingHostInput.setText(value);
            }
        });
        parent.addView(chip);
    }

    private void addPingCountPreset(LinearLayout parent, String label, int count) {
        Button btn = new Button(this);
        btn.setText(label);
        btn.setAllCaps(false);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(54), ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(dp(2), 0, dp(2), 0);
        btn.setLayoutParams(p);
        btn.setOnClickListener(v -> pingCountInput.setText(String.valueOf(count)));
        parent.addView(btn);
    }

    private void updatePingModeButtons() {
        if (pingModeAutoBtn == null || pingModeIcmpBtn == null || pingModeSocketBtn == null) return;

        int activeColor = Color.rgb(66, 184, 165); // Stellar Teal
        int normalColor = tileColor;

        pingModeAutoBtn.setBackgroundColor(selectedPingMode == CoroutinePingEngine.PingMode.AUTO ? activeColor : normalColor);
        pingModeIcmpBtn.setBackgroundColor(selectedPingMode == CoroutinePingEngine.PingMode.ICMP ? activeColor : normalColor);
        pingModeSocketBtn.setBackgroundColor(selectedPingMode == CoroutinePingEngine.PingMode.SOCKET ? activeColor : normalColor);
    }

    private String detectDefaultGateway() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                Network network = cm.getActiveNetwork();
                if (network != null) {
                    LinkProperties lp = cm.getLinkProperties(network);
                    if (lp != null) {
                        for (RouteInfo route : lp.getRoutes()) {
                            if (route.isDefaultRoute() && route.getGateway() != null) {
                                if (route.getGateway() instanceof Inet4Address) {
                                    return route.getGateway().getHostAddress();
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ==========================================
    // START PING TEST (KOTLIN COROUTINES)
    // ==========================================

    private void startPingTest() {

        if (pingRunning) {
            return;
        }

        String rawHost = pingHostInput.getText()
            .toString().trim();

        if (rawHost.isEmpty()) {
            pingHostInput.setError(
                "Enter an IP address or hostname");
            return;
        }

        String host = com.example.networktoolkit.diagnostics.IpAddressValidator.sanitizeDiagnosticTarget(rawHost);
        if (host == null) {
            pingHostInput.setError(
                "Enter a valid IPv4, IPv6, or hostname without options or spaces");
            return;
        }

        String countText = pingCountInput.getText()
            .toString().trim();

        int count;

        try {
            count = Integer.parseInt(countText);
            if (count < 1 || count > 100) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException e) {
            pingCountInput.setError(
                "Enter a number between 1 and 100");
            return;
        }

        pingTimes.clear();
        pingSent = 0;
        pingReceived = 0;

        pingResults.setText("Starting Coroutine Ping test...\nTarget: " + host + "\nMode: " + selectedPingMode + "\nCount: " + count + "\n\n");
        pingSummary.setText("Resolving hostname & initializing coroutines...");

        pingRunning = true;

        if (pingStartButton != null) pingStartButton.setEnabled(false);
        if (pingStopButton != null) pingStopButton.setEnabled(true);

        if (pingD3ChartView != null) {
            pingD3ChartView.resetChart(host);
            pingD3ChartView.setRunning(true);
        }

        final CoroutinePingEngine.PingSummary[] lastSummaryHolder = new CoroutinePingEngine.PingSummary[1];

        pingEngine.startPing(host, count, 2, selectedPingMode, new CoroutinePingEngine.PingCallback() {
            @Override
            public void onStarted(String target, int count, CoroutinePingEngine.PingMode mode) {
                // Initialized
            }

            @Override
            public void onDnsResolved(String hostname, String ip, long dnsTimeMs) {
                if (pingResults != null) {
                    pingResults.append(String.format(Locale.US, "DNS Resolved: %s -> %s (%d ms)\n", hostname, ip, dnsTimeMs));
                }
                if (pingSummary != null) {
                    pingSummary.setText("Pinging " + hostname + " [" + ip + "]...");
                }
            }

            @Override
            public void onPacketReceived(CoroutinePingEngine.PingPacket packet) {
                if (pingResults != null) {
                    pingResults.append(packet.getDisplayLine() + "\n");
                }
                if (packet.isSuccess()) {
                    pingReceived++;
                    pingTimes.add(packet.getRttMs());
                }
                if (pingD3ChartView != null) {
                    pingD3ChartView.addPingSample(packet.getSequence(), packet.getRttMs(), packet.isSuccess());
                }
            }

            @Override
            public void onProgress(int current, int total) {
                pingSent = current;
            }

            @Override
            public void onSummary(CoroutinePingEngine.PingSummary summary) {
                lastSummaryHolder[0] = summary;
                updatePingSummaryWithEngine(summary);
            }

            @Override
            public void onError(String message) {
                if (pingResults != null) {
                    pingResults.append("[Notice] " + message + "\n");
                }
            }

            @Override
            public void onCompleted(boolean cancelled) {
                pingRunning = false;
                if (pingStartButton != null) pingStartButton.setEnabled(true);
                if (pingStopButton != null) pingStopButton.setEnabled(false);
                if (pingD3ChartView != null) {
                    pingD3ChartView.setRunning(false);
                }

                if (pingResults != null) {
                    if (cancelled) {
                        pingResults.append("\n--- Ping test stopped by user ---\n");
                    } else {
                        pingResults.append("\n--- Ping test completed ---\n");
                    }
                }

                if (lastSummaryHolder[0] != null && lastSummaryHolder[0].getTransmitted() > 0) {
                    try {
                        String fullLog = pingResults != null ? pingResults.getText().toString() : "";
                        String recordedMode = lastSummaryHolder[0].getProtocolUsed();
                        if (lastSummaryHolder[0].getWasCancelled()) {
                            recordedMode = recordedMode + " (Stopped)";
                        }
                        PingHistoryRepository.saveResultAsync(
                            MainActivity.this,
                            lastSummaryHolder[0].getTarget(),
                            lastSummaryHolder[0].getIp(),
                            lastSummaryHolder[0].getTransmitted(),
                            lastSummaryHolder[0].getReceived(),
                            lastSummaryHolder[0].getLossPercentage(),
                            lastSummaryHolder[0].getMinRtt(),
                            lastSummaryHolder[0].getAvgRtt(),
                            lastSummaryHolder[0].getMaxRtt(),
                            lastSummaryHolder[0].getMdevRtt(),
                            recordedMode,
                            lastSummaryHolder[0].getTotalTimeMs(),
                            fullLog
                        );
                    } catch (Exception e) {
                        android.util.Log.e("MainActivity", "Failed to save ping result to database", e);
                    }
                }
            }
        });
    }

    private void updatePingSummaryWithEngine(CoroutinePingEngine.PingSummary summary) {
        if (pingSummary == null) return;

        StringBuilder sb = new StringBuilder();
        String statusBadge = summary.getWasCancelled() ? " [Stopped by user — Partial]" : "";
        sb.append(String.format(Locale.US, "Host: %s (%s) • Protocol: %s%s\n",
            summary.getTarget(), summary.getIp(), summary.getProtocolUsed(), statusBadge));
        sb.append(String.format(Locale.US, "Packets: %d sent, %d received (%.1f%% loss)\n",
            summary.getTransmitted(), summary.getReceived(), summary.getLossPercentage()));

        if (summary.getReceived() > 0) {
            sb.append(String.format(Locale.US, "rtt min/avg/max/mdev: %.2f / %.2f / %.2f / %.2f ms",
                summary.getMinRtt(), summary.getAvgRtt(), summary.getMaxRtt(), summary.getMdevRtt()));
        } else {
            sb.append("No response packets received yet.");
        }
        if (summary.getReachabilityNote() != null && !summary.getReachabilityNote().isEmpty()) {
            sb.append("\nNote: ").append(summary.getReachabilityNote());
        }

        pingSummary.setText(sb.toString());
    }

    // ==========================================
    // STOP PING PROCESS (COROUTINES)
    // ==========================================

    private void stopPingProcess() {

        pingRunning = false;
        pingEngine.stopPing();

        Process process = currentPingProcess;

        if (process != null) {
            process.destroy();
            currentPingProcess = null;
        }

        if (pingStartButton != null) {
            pingStartButton.setEnabled(true);
        }

        if (pingStopButton != null) {
            pingStopButton.setEnabled(false);
        }

        if (pingD3ChartView != null) {
            pingD3ChartView.setRunning(false);
        }

        if (pingSummary != null && pingSent == 0 && pingReceived == 0) {
            pingSummary.setText("Ping test stopped by user before any probe completed.");
        }
    }
    // ==========================================
    // TRACEROUTE SCREEN
    // ==========================================

    private void showTracerouteScreen() {
        currentScreen = "traceroute";
        if (backCallback != null) {
            backCallback.setEnabled(true);
        }
        Button startButton = new Button(this);
        startButton.setText("Start Traceroute");
        Button stopButton = new Button(this);
        stopButton.setText("Stop Traceroute");
        stopButton.setEnabled(false);

        applyColors();

        ScrollView scroll = new ScrollView(this);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(
               dp(16), dp(16), dp(16), dp(16));

        layout.setBackgroundColor(backgroundColor);

        scroll.addView(layout);

        TextView title = new TextView(this);
        title.setText("Traceroute");
        title.setTextSize(23);
        title.setTextColor(textColor);
        title.setPadding(0, 0, 0, dp(12));

        layout.addView(title);

        TextView description = new TextView(this);
        description.setText(
            "Trace the network path to a destination.");
        description.setTextSize(14);
        description.setTextColor(textColor);

        layout.addView(description);

        EditText hostInput = new EditText(this);
        hostInput.setHint("IP address or hostname");
        hostInput.setSingleLine(true);
        hostInput.setText("8.8.8.8");
        hostInput.setBackgroundColor(Color.WHITE);
        hostInput.setTextColor(Color.BLACK);
        hostInput.setHintTextColor(Color.GRAY);

        layout.addView(hostInput);

        startButton.setText("Start Traceroute");
        startButton.setAllCaps(false);

        layout.addView(startButton);
        layout.addView(stopButton);

        TextView results = new TextView(this);
results.setTextSize(13);
results.setTextColor(textColor);
results.setPadding(0, dp(12), 0, 0);
results.setTextIsSelectable(true);

// STOP TRACEROUTE BUTTON
stopButton.setOnClickListener(v -> {

    tracerouteStopped = true;

    stopPingProcess();

});
        layout.addView(results);

        startButton.setOnClickListener(v -> {

            String rawHost = hostInput.getText()
                .toString().trim();

            if (rawHost.isEmpty()) {
                hostInput.setError(
                    "Enter an IP address or hostname");
                return;
            }

            String host = com.example.networktoolkit.diagnostics.IpAddressValidator.sanitizeDiagnosticTarget(rawHost);
            if (host == null) {
                hostInput.setError(
                    "Enter a valid IPv4, IPv6, or hostname without options or spaces");
                return;
            }

results.setText(
    "Traceroute to " + host + "\n\n");

results.append(
    String.format(
        Locale.US,
        "%-6s %-20s %s\n",
        "HOP",
        "IP ADDRESS",
        "RESPONSE TIME"));

results.append(
    "------------------------------------------\n");

            startTraceroute(host, results, startButton, stopButton);
        });
        
        setContentView(scroll);
    }
    // ==========================================
    // START TRACEROUTE
    // ==========================================




private void startTraceroute(
    String rawHost,
    TextView results,
    Button startButton,
    Button stopButton) {

    final String host = com.example.networktoolkit.diagnostics.IpAddressValidator.sanitizeDiagnosticTarget(rawHost);
    if (host == null) {
        results.setText("Invalid target host or IP address.");
        return;
    }

    if (pingRunning) {
        results.setText(
            "Another network test is already running.");
        return;
    }

    tracerouteStopped = false;
    pingRunning = true;

    startButton.setEnabled(false);
    stopButton.setEnabled(true);

    results.setText(
        "Traceroute to " + host + "\n\n");

    new Thread(() -> {

        int totalHops = 0;
        int unresponsiveHops = 0;

        boolean destinationReachedOverall = false;
        boolean networkFailureOverall = false;

        String finalResponseTime = "Unavailable";
        String destinationIp = host;

        java.util.ArrayList<Double> responseTimes =
            new java.util.ArrayList<>();

        String probeTarget = host;
        try {
            InetAddress[] resolvedAll = InetAddress.getAllByName(host);
            for (InetAddress addr : resolvedAll) {
                if (addr instanceof java.net.Inet4Address && addr.getHostAddress() != null) {
                    probeTarget = addr.getHostAddress();
                    break;
                }
            }
            if (probeTarget.equals(host) && resolvedAll.length > 0 && resolvedAll[0].getHostAddress() != null) {
                probeTarget = resolvedAll[0].getHostAddress();
            }
        } catch (Exception ignored) {}
        destinationIp = probeTarget;

        try {

            for (int ttl = 1; ttl <= 20; ttl++) {

                if (!pingRunning) {
                    break;
                }

                final int hopNumber = ttl;

                final long hopStartNanos =
                    System.nanoTime();

                ProcessBuilder pb = probeTarget.contains(":")
                    ? new ProcessBuilder("ping", "-6", "-n", "-c", "1", "-W", "2", "-t", String.valueOf(ttl), probeTarget)
                    : new ProcessBuilder("ping", "-n", "-c", "1", "-W", "2", "-t", String.valueOf(ttl), probeTarget);

                Process process = pb.redirectErrorStream(true).start();

                currentPingProcess = process;

                BufferedReader reader =
                    new BufferedReader(
                        new InputStreamReader(
                            process.getInputStream()));

                String line;

                boolean destinationReached = false;
                boolean hopResponded = false;
                boolean networkFailure = false;

                while ((line = reader.readLine()) != null) {

                    String lowerLine =
                        line.toLowerCase(Locale.US);

                    // Check for network errors.

                    if (
                        lowerLine.contains("network is unreachable")
                        || lowerLine.contains("no route to host")
                        || lowerLine.contains("name or service not known")
                        || lowerLine.contains("temporary failure in name resolution")
                        || lowerLine.contains("unknown host")
                        || lowerLine.contains("bad address")
                    ) {

                        networkFailure = true;
                        networkFailureOverall = true;

                        final String errorMessage =
                            "NETWORK ERROR: " + line;

                        handler.post(() -> {
                            results.append(
                                "\n" + errorMessage + "\n");
                        });

                        break;
                    }

                    ParsedPingProbe parsed = TracerouteOutputParser.parsePingProbeLine(line);
                    if (parsed.getResponded()) {
                        hopResponded = true;
                        String ip = parsed.getHopIp() != null ? parsed.getHopIp() : "Unknown IP";
                        double responseMs;
                        boolean approximate = false;
                        if (parsed.getRttMs() != null) {
                            responseMs = parsed.getRttMs();
                        } else {
                            responseMs = (System.nanoTime() - hopStartNanos) / 1_000_000.0;
                            approximate = true;
                        }
                        responseTimes.add(responseMs);

                        final String responseText = approximate
                            ? "~" + String.format(Locale.US, "%.1f", responseMs) + " ms (approx.)"
                            : String.format(Locale.US, "%.2f ms", responseMs);

                        if (parsed.isDestinationReached() && parsed.getErrorStatus() == null) {
                            destinationIp = ip;
                            finalResponseTime = responseText;
                            destinationReached = true;
                            destinationReachedOverall = true;
                        }

                        String suffix = "";
                        if (parsed.isDestinationReached() && parsed.getErrorStatus() == null) {
                            suffix = "  DESTINATION";
                        } else if (parsed.getErrorStatus() != null) {
                            suffix = "  [" + parsed.getErrorStatus() + "]";
                        } else if (com.example.networktoolkit.diagnostics.IpAddressValidator.INSTANCE.classifyIp(ip, false).getRangeName().startsWith("Reserved (240.0.0.0/4)")) {
                            suffix = "  [Reserved/Carrier-Translated]";
                        }

                        final String hopResult =
                            String.format(
                                Locale.US,
                                "%-6d %-20s %s%s",
                                hopNumber,
                                ip,
                                responseText,
                                suffix);

                        handler.post(() -> {
                            results.append(
                                hopResult + "\n");
                        });
                    }
                }

                reader.close();

                process.waitFor();

                // Count this hop.

                totalHops++;

                // Check whether Stop was pressed.

                if (!pingRunning) {
                    break;
                }

                // Stop on network failure.

                if (networkFailure) {
                    break;
                }

                // Report an unresponsive hop.

                if (!hopResponded) {

                    unresponsiveHops++;

                    final String hopResult =
                        String.format(
                            Locale.US,
                            "%-6d %-20s %s",
                            hopNumber,
                            "*",
                            "No response - continuing");

                    handler.post(() -> {
                        results.append(
                            hopResult + "\n");
                    });
                }

                // Stop when destination is reached.

                if (destinationReached) {
                    break;
                }
            }

        } catch (Exception e) {

            if (!tracerouteStopped) {

                final String error =
                    "Traceroute error: " + e.getMessage();

                handler.post(() -> {
                    results.append(
                        "\n" + error + "\n");
                });
            }

        } finally {

            pingRunning = false;
            currentPingProcess = null;

            final int summaryHops = totalHops;
            final int summaryTimeouts = unresponsiveHops;

            final boolean summaryReached =
                destinationReachedOverall;

            final boolean summaryNetworkFailure =
                networkFailureOverall;

            final boolean summaryStopped =
                tracerouteStopped;

            final String summaryDestination = destinationIp;

            final String summaryResponseTime =
                finalResponseTime;

            // Calculate response-time statistics.

            double averageResponse = 0;
            double fastestResponse = Double.MAX_VALUE;
            double slowestResponse = 0;

            for (double time : responseTimes) {

                averageResponse += time;

                if (time < fastestResponse) {
                    fastestResponse = time;
                }

                if (time > slowestResponse) {
                    slowestResponse = time;
                }
            }

            String averageText = "Unavailable";
            String fastestText = "Unavailable";
            String slowestText = "Unavailable";

            if (!responseTimes.isEmpty()) {

                averageResponse =
                    averageResponse / responseTimes.size();

                averageText = String.format(
                    Locale.US,
                    "%.2f ms",
                    averageResponse);

                fastestText = String.format(
                    Locale.US,
                    "%.2f ms",
                    fastestResponse);

                slowestText = String.format(
                    Locale.US,
                    "%.2f ms",
                    slowestResponse);
            }

            final String summaryAverage = averageText;
            final String summaryFastest = fastestText;
            final String summarySlowest = slowestText;

            handler.post(() -> {

                startButton.setEnabled(true);
                stopButton.setEnabled(false);

                if (summaryStopped) {

                    results.append(
                        "\nTraceroute stopped.");

                } else if (summaryNetworkFailure) {

                    results.append(
                        "\nTraceroute ended because of a network error.");

                } else {

                    results.append(
                        "\nTraceroute finished.");
                }

                results.append(
                    "\n\nTraceroute Summary"
                    + "\n------------------------------"
                    + "\nDestination: " + summaryDestination
                    + "\nTotal Hops: " + summaryHops
                    + "\nDestination Reached: "
                    + (summaryReached ? "YES" : "NO")
                    + "\nUnresponsive Hops: "
                    + summaryTimeouts
                    + "\nFinal Response Time: "
                    + summaryResponseTime
                    + "\nAverage Hop Response Time: "
                    + summaryAverage
                    + "\nFastest Hop: "
                    + summaryFastest
                    + "\nSlowest Hop: "
                    + summarySlowest
                    + "\n");
            });
        }

    }).start();
}


private void showLanScannerScreen() {

    currentScreen = "lan";
    if (backCallback != null) {
        backCallback.setEnabled(true);
    }
    mainLayout.removeAllViews();
    mainLayout.setOrientation(LinearLayout.VERTICAL);
    mainLayout.setPadding(
        dp(16), dp(16), dp(16), dp(16));

    TextView title = new TextView(this);
    title.setText("LAN Scanner");
    title.setTextSize(24);
    title.setTextColor(textColor);
    title.setPadding(0, 0, 0, dp(8));
    mainLayout.addView(title);

    TextView description = new TextView(this);
    description.setText(
        "Discover devices connected to your local " +
        "Wi-Fi or Ethernet network.\n\n" +
        "Devices that do not respond to network " +
        "probes may not appear in the results."
    );
    description.setTextSize(14);
    description.setTextColor(textColor);
    mainLayout.addView(description);

    lanScanStartButton = new Button(this);
    lanScanStartButton.setText("Start Scan");
    mainLayout.addView(lanScanStartButton);

    lanScanStopButton = new Button(this);
    lanScanStopButton.setText("Stop Scan");
    lanScanStopButton.setEnabled(false);
    mainLayout.addView(lanScanStopButton);

    Button backButton = new Button(this);
    backButton.setText("Back to Home");
    mainLayout.addView(backButton);

    lanScanResults = new TextView(this);
    lanScanResults.setTextSize(14);
    lanScanResults.setTextColor(textColor);
    lanScanResults.setTextIsSelectable(true);
    lanScanResults.setPadding(
        0, dp(16), 0, dp(16));

    ScrollView scrollView = new ScrollView(this);
    scrollView.addView(lanScanResults);

    LinearLayout.LayoutParams scrollParams =
        new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1
        );

    mainLayout.addView(scrollView, scrollParams);

    lanScanResults.setText(
        "Ready to scan your local network."
    );

    lanScanStartButton.setOnClickListener(v ->
        startLanScan()
    );

    lanScanStopButton.setOnClickListener(v ->
        stopLanScan()
    );

    backButton.setOnClickListener(v -> {
        stopLanScan();
        buildHomeScreen();
    });
}



private void startLanScan() {

    if (lanScanRunning) {
        return;
    }

    ConnectivityManager cm =
        (ConnectivityManager) getSystemService(
            Context.CONNECTIVITY_SERVICE);

    Network network = cm.getActiveNetwork();

    if (network == null) {
        lanScanResults.setText(
            "No active network connection found."
        );
        return;
    }

    NetworkCapabilities capabilities =
        cm.getNetworkCapabilities(network);

    if (capabilities == null ||
        capabilities.hasTransport(
            NetworkCapabilities.TRANSPORT_VPN) ||
        (!capabilities.hasTransport(
            NetworkCapabilities.TRANSPORT_WIFI) &&
         !capabilities.hasTransport(
            NetworkCapabilities.TRANSPORT_ETHERNET))) {

        lanScanResults.setText(
            "Please connect to Wi-Fi or Ethernet " +
            "before scanning.\n\n" +
            "Mobile data and VPN connections are " +
            "not supported by this scanner."
        );
        return;
    }

    LinkProperties linkProperties =
        cm.getLinkProperties(network);

    if (linkProperties == null) {
        lanScanResults.setText(
            "Unable to read network information."
        );
        return;
    }

    Inet4Address localAddress = null;
    int prefixLength = -1;

    for (LinkAddress linkAddress :
            linkProperties.getLinkAddresses()) {

        InetAddress address = linkAddress.getAddress();

        if (address instanceof Inet4Address) {
            localAddress = (Inet4Address) address;
            prefixLength = linkAddress.getPrefixLength();
            break;
        }
    }

    if (localAddress == null ||
        prefixLength < 1 ||
        prefixLength > 30) {

        lanScanResults.setText(
            "Unable to determine a supported IPv4 subnet."
        );
        return;
    }

    // Make a final copy for use inside lambda expressions.
    final int scanPrefixLength = prefixLength;

    long totalHosts =
        (1L << (32 - scanPrefixLength)) - 2;

    if (totalHosts > 254 || totalHosts < 1) {

        lanScanResults.setText(
            "The detected network is too large " +
            "for this scanner.\n\n" +
            "Detected subnet: /" + scanPrefixLength +
            "\nMaximum supported: 254 devices."
        );
        return;
    }

    int addressInt = ipToInt(localAddress);
    int mask = -1 << (32 - scanPrefixLength);

    long networkAddress =
        (addressInt & mask) & 0xffffffffL;

    long broadcastAddress =
        (networkAddress |
        (~mask & 0xffffffffL));

    long firstHost = networkAddress + 1;
    long lastHost = broadcastAddress - 1;

    String localIp = localAddress.getHostAddress();

    lanScanRunning = true;
    lanScanFound = 0;

    lanScanStartButton.setEnabled(false);
    lanScanStopButton.setEnabled(true);

    lanScanResults.setText(
        "Scanning local network...\n\n" +
        "Your IP: " + localIp +
        "\nSubnet: /" + scanPrefixLength +
        "\nPossible devices: " + totalHosts +
        "\n\nPlease wait..."
    );

    lanScanExecutor =
        Executors.newFixedThreadPool(32);

    List<Future<String>> scanTasks =
        new ArrayList<>();

    for (long host = firstHost;
         host <= lastHost;
         host++) {

        if (!lanScanRunning) {
            break;
        }

        String targetIp = intToIp(host);

        if (targetIp.equals(localIp)) {
            continue;
        }

        Future<String> task =
            lanScanExecutor.submit(
                () -> scanLanHost(targetIp)
            );

        scanTasks.add(task);
    }

    final int totalTasks = scanTasks.size();

    new Thread(() -> {

        List<String> discoveredDevices =
            new ArrayList<>();

        int completed = 0;

        for (Future<String> task : scanTasks) {

            if (!lanScanRunning) {
                break;
            }

            try {
                String result = task.get();

                if (result != null) {
                    discoveredDevices.add(result);
                }

            } catch (Exception e) {
                // Ignore individual scan failures.
            }

            completed++;

            final int progress = completed;
            final int found = discoveredDevices.size();

            handler.post(() -> {

                if (lanScanRunning &&
                    lanScanResults != null) {

                    lanScanResults.setText(
                        "Scanning local network...\n\n" +
                        "Your IP: " + localIp +
                        "\nSubnet: /" + scanPrefixLength +
                        "\nProgress: " + progress +
                        " / " + totalTasks +
                        "\nDevices found: " + found
                    );
                }
            });
        }

        if (lanScanExecutor != null) {
            lanScanExecutor.shutdownNow();
            lanScanExecutor = null;
        }

        final boolean completedNormally =
            lanScanRunning;

        lanScanRunning = false;

        handler.post(() -> {

            if (lanScanResults == null) {
                return;
            }

            lanScanStartButton.setEnabled(true);
            lanScanStopButton.setEnabled(false);

            StringBuilder output = new StringBuilder();

            if (completedNormally) {
                output.append("SCAN COMPLETE\n\n");
            } else {
                output.append("SCAN STOPPED\n\n");
            }

            output.append("Your IP: ")
                .append(localIp)
                .append("\n");

            output.append("Subnet: /")
                .append(scanPrefixLength)
                .append("\n");

            output.append("Devices found: ")
                .append(discoveredDevices.size())
                .append("\n\n");

            if (discoveredDevices.isEmpty()) {

                output.append(
                    "No responding devices were detected.\n\n" +
                    "Some devices may block network probes."
                );

            } else {

                output.append("DISCOVERED DEVICES\n\n");

                for (String device :
                        discoveredDevices) {

                    output.append(device)
                        .append("\n\n");
                }
            }

            lanScanResults.setText(output.toString());
        });

   }).start();

} // End of startLanScan()


private String scanLanHost(String ip) {

    boolean reachable = false;

    try {
        InetAddress address =
            InetAddress.getByName(ip);

        reachable = address.isReachable(350);

    } catch (Exception e) {
        // Continue with TCP checks.
    }

    if (reachable) {
        return ip + "  (Ping response)";
    }

    int[] ports = {80, 443, 22, 445};

    for (int port : ports) {

        if (!lanScanRunning) {
            return null;
        }

        try (Socket socket = new Socket()) {

            socket.connect(
                new InetSocketAddress(ip, port),
                250
            );

            return ip + "  (TCP port " +
                port + " responding)";

        } catch (Exception e) {
            // Try the next port.
        }
    }

    return null;
}


private int ipToInt(Inet4Address address) {

    byte[] bytes = address.getAddress();

    return ((bytes[0] & 0xff) << 24) |
           ((bytes[1] & 0xff) << 16) |
           ((bytes[2] & 0xff) << 8) |
           (bytes[3] & 0xff);
}


private String intToIp(long value) {

    return ((value >> 24) & 0xff) + "." +
           ((value >> 16) & 0xff) + "." +
           ((value >> 8) & 0xff) + "." +
           (value & 0xff);
}


private void stopLanScan() {

    lanScanRunning = false;

    if (lanScanExecutor != null) {
        lanScanExecutor.shutdownNow();
        lanScanExecutor = null;
    }

    if (lanScanStartButton != null) {
        lanScanStartButton.setEnabled(true);
    }

    if (lanScanStopButton != null) {
        lanScanStopButton.setEnabled(false);
    }

    if (lanScanResults != null) {
        lanScanResults.setText(
            "Scan stopped."
        );
    }
}

}
