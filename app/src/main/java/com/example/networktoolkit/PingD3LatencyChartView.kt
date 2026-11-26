package com.example.networktoolkit

import android.annotation.SuppressLint
import android.content.Context
import android.net.http.SslError
import android.util.AttributeSet
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.ui.theme.DarkSlate
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Data model for an individual ping latency sample plotted on the D3 line chart.
 */
data class PingLatencyPoint(
    val sequence: Int,
    val rttMs: Double,
    val isSuccess: Boolean,
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * Custom D3 / SVG-based continuous latency trend line chart view.
 *
 * Visualizes ping latency variations over time, showing:
 * - Real-time smooth Bezier curve line chart
 * - Area gradient fill with neon glow
 * - Dynamic Y-axis latency scaling and horizontal gridlines
 * - Average latency horizontal reference line (dashed amber)
 * - Individual packet markers with interactive tooltips
 * - Packet loss / timeout indicators
 * - Window controls (Last 20, Last 50, All)
 *
 * Can be instantiated as an Android View in Java/XML layouts or as a Jetpack Compose composable.
 */
@SuppressLint("SetJavaScriptEnabled")
class PingD3LatencyChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val webView: WebView = WebView(context)
    private val dataPoints = mutableListOf<PingLatencyPoint>()
    private var isPageLoaded = false
    private var isDestroyed = false
    private var currentTarget: String = ""
    private var isRunning: Boolean = false

    init {
        val lp = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        webView.layoutParams = lp
        addView(webView)

        setupWebView()
    }

    private fun setupWebView() {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        webView.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            @Suppress("DEPRECATION")
            settings.allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            settings.allowUniversalAccessFromFileURLs = false
            settings.setGeolocationEnabled(false)
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            setBackgroundColor(0xFF101820.toInt()) // Dark slate matching DarkSlate theme

            addJavascriptInterface(object {
                @JavascriptInterface
                fun onPointSelected(seq: Int, rtt: Double) {
                    // Hook for future native interactions if needed
                }
            }, "AndroidBridge")

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean = true

                @Deprecated("Deprecated in Java")
                @Suppress("DEPRECATION")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = true

                override fun onReceivedSslError(
                    view: WebView?,
                    handler: SslErrorHandler?,
                    error: SslError?
                ) {
                    handler?.cancel()
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (!isDestroyed) {
                        isPageLoaded = true
                        pushDataToChart()
                    }
                }
            }

            val html = buildD3ChartHtml()
            loadDataWithBaseURL(
                "https://appassets.androidplatform.net/",
                html,
                "text/html",
                "UTF-8",
                null
            )
        }
    }

    fun destroyChart() {
        if (isDestroyed) return
        isDestroyed = true
        isPageLoaded = false
        removeAllViews()
        webView.stopLoading()
        webView.destroy()
    }

    /**
     * Appends a new ping packet result to the latency line chart in real time.
     */
    fun addPingSample(sequence: Int, rttMs: Double, isSuccess: Boolean) {
        val point = PingLatencyPoint(
            sequence = sequence,
            rttMs = if (isSuccess) rttMs else 0.0,
            isSuccess = isSuccess
        )
        synchronized(dataPoints) {
            dataPoints.add(point)
        }
        pushDataToChart()
    }

    /**
     * Clears existing data points and resets the chart for a new target host.
     */
    fun resetChart(targetHost: String = "") {
        currentTarget = targetHost
        synchronized(dataPoints) {
            dataPoints.clear()
        }
        pushDataToChart()
    }

    /**
     * Updates the running state (shows/hides live indicator).
     */
    fun setRunning(running: Boolean) {
        isRunning = running
        if (isPageLoaded && !isDestroyed) {
            post {
                if (!isDestroyed) {
                    webView.evaluateJavascript(
                        "if (window.setRunningState) { window.setRunningState($running); }",
                        null
                    )
                }
            }
        }
    }

    private fun pushDataToChart() {
        if (!isPageLoaded || isDestroyed) return

        val array = JSONArray()
        var minRtt = Double.MAX_VALUE
        var maxRtt = 0.0
        var sumRtt = 0.0
        var successCount = 0
        var lossCount = 0

        synchronized(dataPoints) {
            for (p in dataPoints) {
                val obj = JSONObject()
                obj.put("seq", p.sequence)
                obj.put("rtt", p.rttMs)
                obj.put("success", p.isSuccess)
                array.put(obj)

                if (p.isSuccess) {
                    successCount++
                    sumRtt += p.rttMs
                    if (p.rttMs < minRtt) minRtt = p.rttMs
                    if (p.rttMs > maxRtt) maxRtt = p.rttMs
                } else {
                    lossCount++
                }
            }
        }

        val total = successCount + lossCount
        val avgRtt = if (successCount > 0) sumRtt / successCount else 0.0
        val lossPct = if (total > 0) (lossCount.toDouble() / total) * 100.0 else 0.0
        if (minRtt == Double.MAX_VALUE) minRtt = 0.0

        val stats = JSONObject().apply {
            put("target", currentTarget)
            put("count", total)
            put("min", minRtt)
            put("avg", avgRtt)
            put("max", maxRtt)
            put("lossPct", lossPct)
            put("isRunning", isRunning)
        }

        post {
            if (!isDestroyed) {
                val js = "if (window.updateChartData) { window.updateChartData($array, $stats); }"
                webView.evaluateJavascript(js, null)
            }
        }
    }

    companion object {
        fun buildD3ChartHtml(): String {
            return """
<!DOCTYPE html>
<html>
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
    <title>Ping Latency Trend Chart</title>
    <style>
        * {
            box-sizing: border-box;
            margin: 0;
            padding: 0;
            -webkit-user-select: none;
            user-select: none;
        }
        body {
            background-color: #101820;
            color: #F1F5F9;
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
            overflow: hidden;
            width: 100vw;
            height: 100vh;
            display: flex;
            flex-direction: column;
        }
        #chartHeader {
            display: flex;
            justify-content: space-between;
            align-items: center;
            padding: 6px 12px;
            background: #18222C;
            border-bottom: 1px solid rgba(255,255,255,0.08);
            font-size: 11px;
        }
        .header-title-box {
            display: flex;
            align-items: center;
            gap: 6px;
        }
        .live-badge {
            display: inline-flex;
            align-items: center;
            gap: 4px;
            padding: 2px 6px;
            border-radius: 4px;
            font-size: 9px;
            font-weight: bold;
            background: rgba(16, 185, 129, 0.2);
            color: #10B981;
        }
        .live-badge.idle {
            background: rgba(148, 163, 184, 0.2);
            color: #94A3B8;
        }
        .pulse-dot {
            width: 6px;
            height: 6px;
            border-radius: 50%;
            background-color: #10B981;
            animation: pulse 1.5s infinite;
        }
        .live-badge.idle .pulse-dot {
            background-color: #94A3B8;
            animation: none;
        }
        @keyframes pulse {
            0% { transform: scale(0.95); opacity: 0.8; }
            50% { transform: scale(1.3); opacity: 1; }
            100% { transform: scale(0.95); opacity: 0.8; }
        }
        #statsRow {
            display: flex;
            justify-content: space-around;
            padding: 4px 8px;
            background: #151E28;
            border-bottom: 1px solid rgba(255,255,255,0.05);
            font-size: 10px;
        }
        .stat-item {
            display: flex;
            flex-direction: column;
            align-items: center;
        }
        .stat-label {
            color: #94A3B8;
            font-size: 9px;
            text-transform: uppercase;
        }
        .stat-val {
            font-weight: bold;
            color: #42B8A5;
            font-family: monospace;
        }
        .stat-val.loss {
            color: #EF4444;
        }
        #chartContainer {
            position: relative;
            flex: 1;
            width: 100%;
            min-height: 120px;
        }
        svg {
            width: 100%;
            height: 100%;
            display: block;
        }
        .grid-line {
            stroke: rgba(255, 255, 255, 0.08);
            stroke-dasharray: 3 3;
            stroke-width: 1;
        }
        .axis-text {
            fill: #94A3B8;
            font-size: 9px;
            font-family: monospace;
        }
        .avg-line {
            stroke: #F59E0B;
            stroke-dasharray: 4 4;
            stroke-width: 1.5;
            opacity: 0.85;
        }
        .avg-label {
            fill: #F59E0B;
            font-size: 9px;
            font-weight: bold;
            font-family: monospace;
        }
        .trend-line {
            fill: none;
            stroke: #42B8A5;
            stroke-width: 2.5;
            stroke-linecap: round;
            stroke-linejoin: round;
            filter: drop-shadow(0 0 4px rgba(66, 184, 165, 0.5));
        }
        .trend-area {
            fill: url(#areaGradient);
        }
        .point {
            fill: #42B8A5;
            stroke: #101820;
            stroke-width: 2;
            cursor: pointer;
            transition: r 0.15s ease;
        }
        .point.high {
            fill: #F59E0B;
        }
        .point.loss {
            fill: #EF4444;
        }
        .point:hover, .point:active {
            r: 6;
        }
        #tooltip {
            position: absolute;
            display: none;
            padding: 4px 8px;
            background: rgba(15, 23, 42, 0.95);
            border: 1px solid #42B8A5;
            border-radius: 6px;
            color: #FFFFFF;
            font-size: 10px;
            pointer-events: none;
            transform: translate(-50%, -120%);
            box-shadow: 0 4px 12px rgba(0,0,0,0.5);
            font-family: monospace;
            white-space: nowrap;
            z-index: 10;
        }
        #emptyState {
            position: absolute;
            top: 50%;
            left: 50%;
            transform: translate(-50%, -50%);
            text-align: center;
            color: #64748B;
            font-size: 11px;
            display: flex;
            flex-direction: column;
            align-items: center;
            gap: 6px;
            pointer-events: none;
        }
        .window-select {
            display: flex;
            gap: 4px;
        }
        .win-btn {
            background: rgba(255,255,255,0.06);
            border: 1px solid rgba(255,255,255,0.1);
            color: #94A3B8;
            font-size: 9px;
            padding: 2px 6px;
            border-radius: 4px;
            cursor: pointer;
        }
        .win-btn.active {
            background: rgba(66, 184, 165, 0.25);
            border-color: #42B8A5;
            color: #42B8A5;
            font-weight: bold;
        }
    </style>
</head>
<body>
    <div id="chartHeader">
        <div class="header-title-box">
            <span style="font-weight:bold; color:#42B8A5;">D3 LATENCY TRENDS</span>
            <div id="liveBadge" class="live-badge idle">
                <div class="pulse-dot"></div>
                <span id="liveText">IDLE</span>
            </div>
        </div>
        <div class="window-select">
            <button id="btn20" class="win-btn" onclick="setWindowSize(20)">20</button>
            <button id="btn50" class="win-btn" onclick="setWindowSize(50)">50</button>
            <button id="btnAll" class="win-btn active" onclick="setWindowSize(0)">ALL</button>
        </div>
    </div>

    <div id="statsRow">
        <div class="stat-item">
            <span class="stat-label">Packets</span>
            <span id="statCount" class="stat-val">0</span>
        </div>
        <div class="stat-item">
            <span class="stat-label">Min</span>
            <span id="statMin" class="stat-val">0.0 ms</span>
        </div>
        <div class="stat-item">
            <span class="stat-label">Avg</span>
            <span id="statAvg" class="stat-val">0.0 ms</span>
        </div>
        <div class="stat-item">
            <span class="stat-label">Max</span>
            <span id="statMax" class="stat-val">0.0 ms</span>
        </div>
        <div class="stat-item">
            <span class="stat-label">Loss</span>
            <span id="statLoss" class="stat-val">0.0%</span>
        </div>
    </div>

    <div id="chartContainer">
        <div id="emptyState">
            <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="#64748B" stroke-width="2">
                <polyline points="22 12 18 12 15 21 9 3 6 12 2 12"></polyline>
            </svg>
            <span>Waiting for continuous ping packet stream...</span>
        </div>

        <div id="tooltip"></div>

        <svg id="chartSvg">
            <defs>
                <linearGradient id="areaGradient" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0%" stop-color="#42B8A5" stop-opacity="0.45" />
                    <stop offset="85%" stop-color="#42B8A5" stop-opacity="0.05" />
                    <stop offset="100%" stop-color="#42B8A5" stop-opacity="0.0" />
                </linearGradient>
            </defs>
            <g id="gridGroup"></g>
            <g id="plotGroup">
                <path id="trendArea" class="trend-area"></path>
                <path id="trendLine" class="trend-line"></path>
                <line id="avgLine" class="avg-line"></line>
                <text id="avgText" class="avg-label"></text>
                <g id="pointsGroup"></g>
            </g>
        </svg>
    </div>

    <script>
        let allData = [];
        let windowLimit = 0; // 0 = all
        let isRunningState = false;

        const svg = document.getElementById('chartSvg');
        const gridGroup = document.getElementById('gridGroup');
        const trendLine = document.getElementById('trendLine');
        const trendArea = document.getElementById('trendArea');
        const avgLine = document.getElementById('avgLine');
        const avgText = document.getElementById('avgText');
        const pointsGroup = document.getElementById('pointsGroup');
        const tooltip = document.getElementById('tooltip');
        const emptyState = document.getElementById('emptyState');

        const liveBadge = document.getElementById('liveBadge');
        const liveText = document.getElementById('liveText');
        const statCount = document.getElementById('statCount');
        const statMin = document.getElementById('statMin');
        const statAvg = document.getElementById('statAvg');
        const statMax = document.getElementById('statMax');
        const statLoss = document.getElementById('statLoss');

        function setWindowSize(size) {
            windowLimit = size;
            document.querySelectorAll('.win-btn').forEach(b => b.classList.remove('active'));
            if (size === 20) document.getElementById('btn20').classList.add('active');
            else if (size === 50) document.getElementById('btn50').classList.add('active');
            else document.getElementById('btnAll').classList.add('active');
            renderChart();
        }

        window.setRunningState = function(running) {
            isRunningState = running;
            if (running) {
                liveBadge.className = "live-badge";
                liveText.textContent = "STREAMING";
            } else {
                liveBadge.className = "live-badge idle";
                liveText.textContent = "IDLE";
            }
        };

        window.updateChartData = function(dataArray, stats) {
            allData = dataArray || [];

            if (stats) {
                statCount.textContent = stats.count || allData.length;
                statMin.textContent = (stats.min || 0).toFixed(1) + " ms";
                statAvg.textContent = (stats.avg || 0).toFixed(1) + " ms";
                statMax.textContent = (stats.max || 0).toFixed(1) + " ms";
                statLoss.textContent = (stats.lossPct || 0).toFixed(1) + "%";
                if (stats.lossPct > 0) {
                    statLoss.classList.add('loss');
                } else {
                    statLoss.classList.remove('loss');
                }
                if (typeof stats.isRunning === "boolean") {
                    window.setRunningState(stats.isRunning);
                }
            }

            renderChart();
        };

        function renderChart() {
            if (!allData || allData.length === 0) {
                emptyState.style.display = "flex";
                trendLine.setAttribute('d', '');
                trendArea.setAttribute('d', '');
                avgLine.style.display = 'none';
                avgText.style.display = 'none';
                pointsGroup.innerHTML = '';
                gridGroup.innerHTML = '';
                return;
            }

            emptyState.style.display = "none";

            const data = (windowLimit > 0 && allData.length > windowLimit)
                ? allData.slice(allData.length - windowLimit)
                : allData;

            const width = svg.clientWidth || 360;
            const height = svg.clientHeight || 150;
            const padLeft = 40;
            const padRight = 32;
            const padTop = 16;
            const padBottom = 24;

            const plotW = width - padLeft - padRight;
            const plotH = height - padTop - padBottom;

            // Determine Y-scale range
            let maxLatency = 20.0;
            let sumLatency = 0;
            let validCount = 0;

            data.forEach(d => {
                if (d.success) {
                    validCount++;
                    sumLatency += d.rtt;
                    if (d.rtt > maxLatency) maxLatency = d.rtt;
                }
            });

            // Headroom on Y-axis
            maxLatency = Math.ceil(maxLatency * 1.25);
            if (maxLatency < 25) maxLatency = 25;
            const avgLatency = validCount > 0 ? (sumLatency / validCount) : 0;

            const getX = (index) => {
                if (data.length <= 1) return padLeft + plotW / 2;
                return padLeft + (index / (data.length - 1)) * plotW;
            };

            const getY = (val) => {
                const ratio = Math.min(1.0, Math.max(0.0, val / maxLatency));
                return padTop + plotH - (ratio * plotH);
            };

            // 1. Draw Gridlines
            gridGroup.innerHTML = '';
            const ySteps = 4;
            for (let i = 0; i <= ySteps; i++) {
                const yVal = (maxLatency / ySteps) * i;
                const py = getY(yVal);

                const line = document.createElementNS("http://www.w3.org/2000/svg", "line");
                line.setAttribute("x1", padLeft);
                line.setAttribute("y1", py);
                line.setAttribute("x2", width - padRight);
                line.setAttribute("y2", py);
                line.setAttribute("class", "grid-line");
                gridGroup.appendChild(line);

                const label = document.createElementNS("http://www.w3.org/2000/svg", "text");
                label.setAttribute("x", padLeft - 6);
                label.setAttribute("y", py + 3);
                label.setAttribute("text-anchor", "end");
                label.setAttribute("class", "axis-text");
                label.textContent = Math.round(yVal) + "m";
                gridGroup.appendChild(label);
            }

            // X-axis min and max sequence labels
            if (data.length > 0) {
                const xLabelStart = document.createElementNS("http://www.w3.org/2000/svg", "text");
                xLabelStart.setAttribute("x", padLeft);
                xLabelStart.setAttribute("y", height - 6);
                xLabelStart.setAttribute("text-anchor", "start");
                xLabelStart.setAttribute("class", "axis-text");
                xLabelStart.textContent = "#" + data[0].seq;
                gridGroup.appendChild(xLabelStart);

                const xLabelEnd = document.createElementNS("http://www.w3.org/2000/svg", "text");
                xLabelEnd.setAttribute("x", width - padRight);
                xLabelEnd.setAttribute("y", height - 6);
                xLabelEnd.setAttribute("text-anchor", "end");
                xLabelEnd.setAttribute("class", "axis-text");
                xLabelEnd.textContent = "#" + data[data.length - 1].seq;
                gridGroup.appendChild(xLabelEnd);
            }

            // 2. Average Latency Reference Line
            if (avgLatency > 0) {
                const avgY = getY(avgLatency);
                avgLine.setAttribute("x1", padLeft);
                avgLine.setAttribute("y1", avgY);
                avgLine.setAttribute("x2", width - padRight);
                avgLine.setAttribute("y2", avgY);
                avgLine.style.display = 'block';

                avgText.setAttribute("x", width - padRight + 4);
                avgText.setAttribute("y", avgY + 3);
                avgText.textContent = avgLatency.toFixed(0);
                avgText.style.display = 'block';
            } else {
                avgLine.style.display = 'none';
                avgText.style.display = 'none';
            }

            // 3. Build Smooth Bezier Line & Area paths (breaking segments across dropped packets)
            let pathD = "";
            let areaD = "";
            pointsGroup.innerHTML = '';

            const baselineY = getY(0);
            const points = data.map((d, i) => ({
                x: getX(i),
                y: d.success ? getY(d.rtt) : baselineY,
                rtt: d.rtt,
                seq: d.seq,
                success: d.success
            }));

            let segmentStartPoint = null;
            let prevSuccessPoint = null;
            let currentAreaSegment = "";

            for (let i = 0; i < points.length; i++) {
                const p = points[i];
                if (p.success) {
                    if (prevSuccessPoint === null) {
                        pathD += (pathD ? " " : "") + "M " + p.x + " " + p.y;
                        segmentStartPoint = p;
                        currentAreaSegment = "M " + p.x + " " + p.y;
                    } else {
                        const mx = (prevSuccessPoint.x + p.x) / 2;
                        const curveCmd = " C " + mx + " " + prevSuccessPoint.y + ", " + mx + " " + p.y + ", " + p.x + " " + p.y;
                        pathD += curveCmd;
                        currentAreaSegment += curveCmd;
                    }
                    prevSuccessPoint = p;
                } else {
                    if (prevSuccessPoint !== null && segmentStartPoint !== null) {
                        areaD += (areaD ? " " : "") + currentAreaSegment +
                            " L " + prevSuccessPoint.x + " " + baselineY +
                            " L " + segmentStartPoint.x + " " + baselineY + " Z";
                    }
                    segmentStartPoint = null;
                    prevSuccessPoint = null;
                    currentAreaSegment = "";
                }
            }
            if (prevSuccessPoint !== null && segmentStartPoint !== null) {
                areaD += (areaD ? " " : "") + currentAreaSegment +
                    " L " + prevSuccessPoint.x + " " + baselineY +
                    " L " + segmentStartPoint.x + " " + baselineY + " Z";
            }

            trendLine.setAttribute("d", pathD);
            trendArea.setAttribute("d", areaD);

            // 4. Draw interactive circles and loss indicators
            points.forEach((p) => {
                if (p.success) {
                    const circle = document.createElementNS("http://www.w3.org/2000/svg", "circle");
                    circle.setAttribute("cx", p.x);
                    circle.setAttribute("cy", p.y);
                    circle.setAttribute("r", points.length > 30 ? 3 : 4);
                    circle.setAttribute("class", p.rtt > 80 ? "point high" : "point");

                    circle.addEventListener('pointerdown', (e) => showTooltip(e, p));
                    circle.addEventListener('pointerenter', (e) => showTooltip(e, p));
                    pointsGroup.appendChild(circle);
                } else {
                    // Loss marker 'X'
                    const text = document.createElementNS("http://www.w3.org/2000/svg", "text");
                    text.setAttribute("x", p.x);
                    text.setAttribute("y", baselineY - 4);
                    text.setAttribute("text-anchor", "middle");
                    text.setAttribute("fill", "#EF4444");
                    text.setAttribute("font-size", "10px");
                    text.setAttribute("font-weight", "bold");
                    text.textContent = "✕";
                    text.addEventListener('pointerdown', (e) => showTooltip(e, p));
                    pointsGroup.appendChild(text);
                }
            });
        }

        function showTooltip(e, p) {
            tooltip.style.display = 'block';
            tooltip.style.left = p.x + 'px';
            tooltip.style.top = p.y + 'px';
            if (p.success) {
                tooltip.innerHTML = "<strong>#" + p.seq + "</strong>: " + p.rtt.toFixed(2) + " ms";
            } else {
                tooltip.innerHTML = "<strong>#" + p.seq + "</strong>: <span style='color:#EF4444;'>TIMEOUT / LOSS</span>";
            }
            if (window.AndroidBridge && window.AndroidBridge.onPointSelected) {
                window.AndroidBridge.onPointSelected(p.seq, p.rtt);
            }
        }

        document.addEventListener('pointerdown', (e) => {
            if (!e.target.classList.contains('point') && e.target.tagName !== 'text') {
                tooltip.style.display = 'none';
            }
        });

        window.addEventListener('resize', () => {
            renderChart();
        });
    </script>
</body>
</html>
            """.trimIndent()
        }
    }
}

/**
 * Jetpack Compose wrapper for the custom D3 Latency Line Chart.
 */
@Composable
fun PingD3LatencyChart(
    points: List<PingLatencyPoint>,
    targetHost: String,
    isRunning: Boolean,
    modifier: Modifier = Modifier
) {
    var chartViewRef by remember { mutableStateOf<PingD3LatencyChartView?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            chartViewRef?.destroyChart()
            chartViewRef = null
        }
    }

    LaunchedEffect(targetHost) {
        chartViewRef?.resetChart(targetHost)
    }

    LaunchedEffect(points.size, isRunning) {
        chartViewRef?.let { chart ->
            chart.setRunning(isRunning)
            chart.resetChart(targetHost)
            points.forEach { pt ->
                chart.addPingSample(pt.sequence, pt.rttMs, pt.isSuccess)
            }
        }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(DarkSlate)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
    ) {
        AndroidView(
            factory = { context ->
                PingD3LatencyChartView(context).apply {
                    resetChart(targetHost)
                    setRunning(isRunning)
                    points.forEach { pt ->
                        addPingSample(pt.sequence, pt.rttMs, pt.isSuccess)
                    }
                    chartViewRef = this
                }
            },
            update = { chart ->
                chartViewRef = chart
            },
            modifier = Modifier
                .fillMaxSize()
                .testTag("ping_d3_latency_chart_view")
        )
    }
}
