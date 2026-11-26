package com.example.networktoolkit

import android.annotation.SuppressLint
import android.net.http.SslError
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.networktoolkit.diagnostics.IpAddressValidator
import com.example.ui.theme.DarkCard
import com.example.ui.theme.DarkSlate
import com.example.ui.theme.StatusAmber
import com.example.ui.theme.StatusBlue
import com.example.ui.theme.StatusGreen
import com.example.ui.theme.StatusRed
import com.example.ui.theme.StellarTeal
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Serialization and classification helpers for the Traceroute D3 Directed Graph.
 * Kept pure so unit/Robolectric tests can verify hop normalization, edge cases,
 * and JS payload construction deterministically.
 */
object TracerouteGraphDataBuilder {

    fun classifyHopIpScope(hop: TracerouteHop): String {
        if (hop.isTimedOut || hop.ip == "*") return "Timeout"
        if (hop.ip.isBlank() || hop.ip.equals("Unknown", ignoreCase = true) ||
            hop.ip.equals("Unknown Hop", ignoreCase = true)
        ) {
            return "Unknown"
        }
        return when (IpAddressValidator.classifyIp(hop.ip).category) {
            IpAddressValidator.IpCategory.PRIVATE_NETWORK -> "Private"
            IpAddressValidator.IpCategory.CARRIER_GRADE_NAT -> "CGNAT"
            IpAddressValidator.IpCategory.LOOPBACK -> "Loopback"
            IpAddressValidator.IpCategory.LINK_LOCAL -> "Link-Local"
            IpAddressValidator.IpCategory.PUBLIC_ROUTABLE,
            IpAddressValidator.IpCategory.NORMAL_OPERATIONAL -> "Public"
            IpAddressValidator.IpCategory.SPECIAL_USE -> "Reserved"
            else -> "Special"
        }
    }

    fun buildHopsJson(hops: List<TracerouteHop>): JSONArray {
        val array = JSONArray()
        for (hop in hops) {
            val cleanIp = hop.ip.trim().ifEmpty { if (hop.isTimedOut) "*" else "Unknown" }
            val cleanHost = hop.hostname?.trim()?.takeIf { it.isNotEmpty() && it != cleanIp } ?: ""
            val scope = classifyHopIpScope(hop)
            val altArray = JSONArray()
            hop.alternateIps.filter { it.isNotBlank() && it != cleanIp }.forEach { altArray.put(it) }

            val obj = JSONObject()
            obj.put("hopNumber", hop.hopNumber)
            obj.put("ip", cleanIp)
            obj.put("hostname", cleanHost)
            obj.put("rttMs", hop.rttMs ?: -1.0)
            obj.put("isDestinationReached", hop.isDestinationReached)
            obj.put("isTimedOut", hop.isTimedOut || cleanIp == "*")
            obj.put("statusText", hop.statusText)
            obj.put("ipScope", scope)
            obj.put("alternateIps", altArray)
            array.put(obj)
        }
        return array
    }

    fun buildUpdateScript(
        hopsJson: String,
        target: String,
        isCompleted: Boolean,
        destinationReached: Boolean
    ): String {
        val safeTarget = JSONObject.quote(target)
        return "if (window.updateGraphData) { window.updateGraphData($hopsJson, $safeTarget, $isCompleted, $destinationReached); }"
    }
}

/**
 * Interactive SVG / D3-style directed graph visualization component for traceroute path hops.
 * Renders an interactive topological graph showing the routing path from Local Device
 * through intermediate gateways and routers to the target destination.
 */
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
fun TracerouteD3GraphView(
    hops: List<TracerouteHop>,
    target: String,
    isCompleted: Boolean = false,
    destinationReached: Boolean = hops.any { it.isDestinationReached },
    modifier: Modifier = Modifier
) {
    var selectedHop by remember { mutableStateOf<TracerouteHop?>(null) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var isPageLoaded by remember { mutableStateOf(false) }

    val hopsJson = remember(hops) {
        TracerouteGraphDataBuilder.buildHopsJson(hops).toString()
    }

    // Always keep latest values accessible to WebViewClient / JavascriptInterface callbacks
    val latestHops by rememberUpdatedState(hops)
    val latestHopsJson by rememberUpdatedState(hopsJson)
    val latestTarget by rememberUpdatedState(target)
    val latestIsCompleted by rememberUpdatedState(isCompleted)
    val latestDestinationReached by rememberUpdatedState(destinationReached)

    // Keep selectedHop synchronized if hop list updates or shrinks on a new run
    LaunchedEffect(hops) {
        val currentSelected = selectedHop
        if (currentSelected != null) {
            selectedHop = hops.find { it.hopNumber == currentSelected.hopNumber }
        }
    }

    // Push graph data whenever the page finishes loading OR hops/completion status change
    LaunchedEffect(hopsJson, target, isCompleted, destinationReached, isPageLoaded, webViewRef) {
        val wv = webViewRef
        if (isPageLoaded && wv != null) {
            val script = TracerouteGraphDataBuilder.buildUpdateScript(
                hopsJson = hopsJson,
                target = target,
                isCompleted = isCompleted,
                destinationReached = destinationReached
            )
            wv.post {
                wv.evaluateJavascript(script, null)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.let { wv ->
                wv.stopLoading()
                wv.destroy()
            }
            webViewRef = null
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(16.dp))
            .background(DarkSlate)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
    ) {
        // Graph Header Controls
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(DarkCard)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Surface(
                    color = StellarTeal.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = "D3 TOPOLOGY",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = StellarTeal,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "${hops.size} Hops • Tap node for details",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = {
                        webViewRef?.evaluateJavascript("if (window.zoomGraphBy) { window.zoomGraphBy(1.25); }", null)
                    },
                    modifier = Modifier
                        .size(34.dp)
                        .testTag("zoom_in_graph_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.ZoomIn,
                        contentDescription = "Zoom In",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(
                    onClick = {
                        webViewRef?.evaluateJavascript("if (window.zoomGraphBy) { window.zoomGraphBy(0.8); }", null)
                    },
                    modifier = Modifier
                        .size(34.dp)
                        .testTag("zoom_out_graph_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.ZoomOut,
                        contentDescription = "Zoom Out",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(
                    onClick = {
                        webViewRef?.evaluateJavascript("if (window.fitGraphToViewport) { window.fitGraphToViewport(); }", null)
                    },
                    modifier = Modifier
                        .size(34.dp)
                        .testTag("fit_graph_view_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.FitScreen,
                        contentDescription = "Fit Graph",
                        tint = StellarTeal,
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(
                    onClick = {
                        webViewRef?.evaluateJavascript("if (window.resetGraphView) { window.resetGraphView(); }", null)
                    },
                    modifier = Modifier
                        .size(34.dp)
                        .testTag("reset_graph_view_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.RestartAlt,
                        contentDescription = "Reset View",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // D3 WebView Container (explicit FrameLayout + MATCH_PARENT prevents 0-height WebView bugs)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .heightIn(min = 260.dp)
        ) {
            AndroidView(
                factory = { context ->
                    val container = FrameLayout(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        setBackgroundColor(0xFF0F172A.toInt())
                    }

                    WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
                    val webView = WebView(context).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                        )
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        @Suppress("DEPRECATION")
                        settings.allowFileAccessFromFileURLs = false
                        @Suppress("DEPRECATION")
                        settings.allowUniversalAccessFromFileURLs = false
                        settings.setGeolocationEnabled(false)
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        // Avoid desktop 980px virtual viewport scaling that can push SVG off-screen
                        settings.useWideViewPort = false
                        settings.loadWithOverviewMode = false
                        settings.builtInZoomControls = false
                        settings.displayZoomControls = false
                        setBackgroundColor(0xFF0F172A.toInt())

                        // Allow pan & pinch inside WebView without Compose parent stealing gestures
                        setOnTouchListener { view, event ->
                            when (event.actionMasked) {
                                MotionEvent.ACTION_DOWN,
                                MotionEvent.ACTION_MOVE,
                                MotionEvent.ACTION_POINTER_DOWN -> {
                                    view.parent?.requestDisallowInterceptTouchEvent(true)
                                }
                            }
                            false
                        }

                        addJavascriptInterface(object {
                            @JavascriptInterface
                            fun onNodeSelected(hopNumber: Int) {
                                post {
                                    selectedHop = latestHops.find { it.hopNumber == hopNumber }
                                }
                            }
                        }, "AndroidBridge")

                        webChromeClient = object : WebChromeClient() {
                            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                                return super.onConsoleMessage(consoleMessage)
                            }
                        }

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
                                isPageLoaded = true
                                val script = TracerouteGraphDataBuilder.buildUpdateScript(
                                    hopsJson = latestHopsJson,
                                    target = latestTarget,
                                    isCompleted = latestIsCompleted,
                                    destinationReached = latestDestinationReached
                                )
                                view?.evaluateJavascript(script, null)
                            }
                        }

                        val html = buildD3GraphHtml()
                        loadDataWithBaseURL(
                            "https://appassets.androidplatform.net/",
                            html,
                            "text/html",
                            "UTF-8",
                            null
                        )
                    }

                    container.addView(webView)
                    webViewRef = webView
                    container
                },
                update = { container ->
                    val wv = container.getChildAt(0) as? WebView
                    if (wv != null) {
                        webViewRef = wv
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("traceroute_d3_webview")
            )
        }

        // Selected Hop Inspection Panel
        if (selectedHop != null) {
            val hop = selectedHop!!
            val scope = TracerouteGraphDataBuilder.classifyHopIpScope(hop)
            val scopeColor = when (scope) {
                "Private" -> StellarTeal
                "CGNAT" -> StatusBlue
                "Timeout" -> StatusRed
                "Reserved", "Special", "Unknown" -> StatusAmber
                else -> MaterialTheme.colorScheme.primary
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp)
                    .testTag("traceroute_selected_hop_card"),
                colors = CardDefaults.cardColors(containerColor = DarkCard),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Hop ${hop.hopNumber}",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = StellarTeal
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (hop.isTimedOut) "* (Timeout)" else hop.ip,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                color = scopeColor.copy(alpha = 0.18f),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = scope.uppercase(Locale.US),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = scopeColor,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }
                        }

                        Text(
                            text = hop.hostname?.takeIf { it.isNotBlank() } ?: "Hostname: Reverse DNS unavailable",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (hop.alternateIps.isNotEmpty()) {
                            Text(
                                text = "Alternate responders: ${hop.alternateIps.joinToString(", ")}",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = StatusBlue
                            )
                        }

                        if (hop.statusText.isNotBlank()) {
                            Text(
                                text = hop.statusText,
                                fontSize = 11.sp,
                                color = if (hop.isDestinationReached) StatusGreen else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (hop.isTimedOut) {
                            Surface(
                                color = StatusRed.copy(alpha = 0.2f),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = "Timeout (*)",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = StatusRed,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        } else if (hop.rttMs != null) {
                            val rtt = hop.rttMs
                            val color = when {
                                rtt < 30 -> StatusGreen
                                rtt < 80 -> StatusBlue
                                rtt < 150 -> StatusAmber
                                else -> StatusRed
                            }
                            Text(
                                text = String.format(Locale.US, "%.1f ms", rtt),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = color
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        IconButton(
                            onClick = { selectedHop = null },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Dismiss Hop Details",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Builds the complete self-contained SVG directed graph HTML bundle (zero external CDN dependencies).
 */
internal fun buildD3GraphHtml(): String {
    return """
<!DOCTYPE html>
<html>
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
    <style>
        * { box-sizing: border-box; margin: 0; padding: 0; -webkit-user-select: none; user-select: none; }
        html, body {
            background-color: #0F172A;
            color: #F8FAFC;
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
            overflow: hidden;
            width: 100%;
            height: 100%;
        }
        #graphContainer {
            position: relative;
            width: 100%;
            height: 100%;
            min-height: 240px;
            background: radial-gradient(circle at 50% 25%, #16233A 0%, #0F172A 75%);
        }
        #graphSvg {
            display: block;
            width: 100%;
            height: 100%;
            touch-action: none;
        }
        .link-active {
            stroke: #42B8A5;
            stroke-width: 2.5px;
            stroke-dasharray: 5, 4;
            animation: dash 1.2s linear infinite;
        }
        .link-slow {
            stroke: #F59E0B;
            stroke-width: 2.5px;
            stroke-dasharray: 5, 4;
            animation: dash 1.2s linear infinite;
        }
        .link-timeout {
            stroke: #EF4444;
            stroke-width: 2px;
            stroke-dasharray: 6, 4;
        }
        .node-group {
            cursor: pointer;
        }
        @keyframes dash {
            to { stroke-dashoffset: -18; }
        }
        #legend {
            position: absolute;
            bottom: 8px;
            left: 8px;
            right: 8px;
            background: rgba(15, 23, 42, 0.88);
            padding: 6px 10px;
            border-radius: 8px;
            font-size: 10px;
            display: flex;
            flex-wrap: wrap;
            justify-content: space-between;
            gap: 8px;
            border: 1px solid rgba(255,255,255,0.12);
            pointer-events: none;
        }
        .legend-items {
            display: flex;
            flex-wrap: wrap;
            gap: 10px;
            align-items: center;
        }
        .dot {
            display: inline-block;
            width: 8px;
            height: 8px;
            border-radius: 50%;
            margin-right: 4px;
            vertical-align: middle;
        }
        .hint {
            color: #94A3B8;
            font-size: 9px;
        }
    </style>
</head>
<body>
    <div id="graphContainer">
        <svg id="graphSvg" xmlns="http://www.w3.org/2000/svg">
            <defs>
                <marker id="arrow-green" viewBox="0 -5 10 10" refX="26" refY="0" markerWidth="6" markerHeight="6" orient="auto">
                    <path d="M0,-5L10,0L0,5" fill="#42B8A5" />
                </marker>
                <marker id="arrow-reached" viewBox="0 -5 10 10" refX="28" refY="0" markerWidth="6" markerHeight="6" orient="auto">
                    <path d="M0,-5L10,0L0,5" fill="#10B981" />
                </marker>
                <marker id="arrow-amber" viewBox="0 -5 10 10" refX="26" refY="0" markerWidth="6" markerHeight="6" orient="auto">
                    <path d="M0,-5L10,0L0,5" fill="#F59E0B" />
                </marker>
                <marker id="arrow-red" viewBox="0 -5 10 10" refX="26" refY="0" markerWidth="6" markerHeight="6" orient="auto">
                    <path d="M0,-5L10,0L0,5" fill="#EF4444" />
                </marker>
            </defs>
            <g id="viewport">
                <g id="linksGroup"></g>
                <g id="nodesGroup"></g>
            </g>
        </svg>

        <div id="legend">
            <div class="legend-items">
                <span><span class="dot" style="background:#3B82F6"></span>Source</span>
                <span><span class="dot" style="background:#42B8A5"></span>Normal</span>
                <span><span class="dot" style="background:#F59E0B"></span>High RTT</span>
                <span><span class="dot" style="background:#EF4444"></span>Timeout (*)</span>
                <span><span class="dot" style="background:#10B981"></span>Reached</span>
            </div>
            <span class="hint">Drag to pan • Pinch / +/- to zoom</span>
        </div>
    </div>

    <script>
        let currentHops = [];
        let targetHost = "";
        let traceCompleted = false;
        let destReached = false;
        let selectedHopNumber = -1;

        const container = document.getElementById('graphContainer');
        const svg = document.getElementById('graphSvg');
        const viewport = document.getElementById('viewport');
        const linksGroup = document.getElementById('linksGroup');
        const nodesGroup = document.getElementById('nodesGroup');

        let panX = 16;
        let panY = 20;
        let scale = 1.0;
        let contentBounds = { minX: 0, minY: 0, maxX: 340, maxY: 360 };
        let hasUserInteracted = false;

        // Pointer / Pinch zoom tracking
        const activePointers = new Map();
        let lastPinchDist = null;
        let dragStartX = 0;
        let dragStartY = 0;
        let pointerMoved = false;

        function getSvgSize() {
            const rect = container.getBoundingClientRect();
            const w = rect.width > 10 ? rect.width : (window.innerWidth || 360);
            const h = rect.height > 10 ? rect.height : (window.innerHeight || 420);
            return { w, h };
        }

        function pointerDist(p1, p2) {
            const dx = p1.x - p2.x;
            const dy = p1.y - p2.y;
            return Math.hypot(dx, dy);
        }

        svg.addEventListener('pointerdown', (e) => {
            activePointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
            try { svg.setPointerCapture(e.pointerId); } catch (_) {}
            if (activePointers.size === 1) {
                dragStartX = e.clientX - panX;
                dragStartY = e.clientY - panY;
                pointerMoved = false;
                lastPinchDist = null;
            } else if (activePointers.size === 2) {
                const pts = Array.from(activePointers.values());
                lastPinchDist = pointerDist(pts[0], pts[1]);
                pointerMoved = true;
            }
        });

        svg.addEventListener('pointermove', (e) => {
            if (!activePointers.has(e.pointerId)) return;
            activePointers.set(e.pointerId, { x: e.clientX, y: e.clientY });

            if (activePointers.size === 1) {
                const nextPanX = e.clientX - dragStartX;
                const nextPanY = e.clientY - dragStartY;
                if (Math.abs(nextPanX - panX) > 3 || Math.abs(nextPanY - panY) > 3) {
                    pointerMoved = true;
                    hasUserInteracted = true;
                }
                panX = nextPanX;
                panY = nextPanY;
                updateTransform();
            } else if (activePointers.size === 2) {
                const pts = Array.from(activePointers.values());
                const dist = pointerDist(pts[0], pts[1]);
                if (lastPinchDist && dist > 0) {
                    const factor = dist / lastPinchDist;
                    const rect = svg.getBoundingClientRect();
                    const midX = ((pts[0].x + pts[1].x) / 2) - rect.left;
                    const midY = ((pts[0].y + pts[1].y) / 2) - rect.top;
                    zoomAround(factor, midX, midY);
                    hasUserInteracted = true;
                }
                lastPinchDist = dist;
            }
        });

        function releasePtr(e) {
            activePointers.delete(e.pointerId);
            if (activePointers.size < 2) lastPinchDist = null;
            if (activePointers.size === 1) {
                const remaining = Array.from(activePointers.values())[0];
                dragStartX = remaining.x - panX;
                dragStartY = remaining.y - panY;
            }
            try { svg.releasePointerCapture(e.pointerId); } catch (_) {}
        }

        svg.addEventListener('pointerup', releasePtr);
        svg.addEventListener('pointercancel', releasePtr);

        function zoomAround(factor, cx, cy) {
            const newScale = Math.max(0.35, Math.min(2.6, scale * factor));
            const ratio = newScale / scale;
            panX = cx - (cx - panX) * ratio;
            panY = cy - (cy - panY) * ratio;
            scale = newScale;
            updateTransform();
        }

        window.zoomGraphBy = function(factor) {
            const { w, h } = getSvgSize();
            hasUserInteracted = true;
            zoomAround(factor, w / 2, (h - 36) / 2);
        };

        window.fitGraphToViewport = function() {
            const { w, h } = getSvgSize();
            const usableH = Math.max(160, h - 44);
            const usableW = Math.max(200, w - 16);
            const graphW = Math.max(180, contentBounds.maxX - contentBounds.minX);
            const graphH = Math.max(120, contentBounds.maxY - contentBounds.minY);

            const fitScaleX = usableW / graphW;
            const fitScaleY = usableH / graphH;
            scale = Math.max(0.42, Math.min(1.15, Math.min(fitScaleX, fitScaleY)));

            const scaledW = graphW * scale;
            const scaledH = graphH * scale;
            panX = (w - scaledW) / 2 - contentBounds.minX * scale;
            panY = Math.max(14, (usableH - scaledH) / 2) - contentBounds.minY * scale;
            updateTransform();
        };

        window.resetGraphView = function() {
            hasUserInteracted = false;
            window.fitGraphToViewport();
        };

        function updateTransform() {
            viewport.setAttribute('transform', 'translate(' + panX.toFixed(1) + ',' + panY.toFixed(1) + ') scale(' + scale.toFixed(3) + ')');
        }

        window.updateGraphData = function(hops, target, isCompleted, isReached) {
            currentHops = Array.isArray(hops) ? hops : [];
            targetHost = target || "";
            traceCompleted = Boolean(isCompleted);
            destReached = Boolean(isReached);
            renderGraph();
        };

        function createSvgEl(tag, attrs) {
            const el = document.createElementNS('http://www.w3.org/2000/svg', tag);
            if (attrs) {
                for (const [k, v] of Object.entries(attrs)) {
                    el.setAttribute(k, String(v));
                }
            }
            return el;
        }

        function truncateText(text, maxLen) {
            if (!text) return "";
            return text.length > maxLen ? text.slice(0, maxLen - 1) + "…" : text;
        }

        function renderGraph() {
            linksGroup.innerHTML = '';
            nodesGroup.innerHTML = '';

            const { w, h } = getSvgSize();
            svg.setAttribute('viewBox', '0 0 ' + w + ' ' + h);

            if (currentHops.length === 0) {
                const msg = createSvgEl('text', {
                    x: w / 2,
                    y: h / 2,
                    fill: '#94A3B8',
                    'font-size': '13px',
                    'text-anchor': 'middle'
                });
                msg.textContent = 'Waiting for traceroute hops...';
                nodesGroup.appendChild(msg);
                panX = 0; panY = 0; scale = 1.0;
                updateTransform();
                return;
            }

            const nodeX = 54;
            const nodeSpacingY = 74;
            const startY = 34;

            const allNodes = [{
                hopNumber: 0,
                ip: 'Local Device',
                hostname: targetHost ? ('Tracing → ' + truncateText(targetHost, 24)) : 'Traceroute Source',
                rttMs: 0,
                isDestinationReached: false,
                isTimedOut: false,
                ipScope: 'Source',
                x: nodeX,
                y: startY,
                color: '#3B82F6',
                type: 'source'
            }];

            currentHops.forEach((hop, idx) => {
                let color = '#42B8A5';
                if (hop.isTimedOut) color = '#EF4444';
                else if (hop.isDestinationReached) color = '#10B981';
                else if (hop.rttMs >= 100) color = '#F59E0B';

                allNodes.push({
                    hopNumber: hop.hopNumber,
                    ip: hop.ip || '*',
                    hostname: hop.hostname || '',
                    rttMs: hop.rttMs,
                    isDestinationReached: Boolean(hop.isDestinationReached),
                    isTimedOut: Boolean(hop.isTimedOut),
                    statusText: hop.statusText || '',
                    ipScope: hop.ipScope || '',
                    alternateIps: Array.isArray(hop.alternateIps) ? hop.alternateIps : [],
                    x: nodeX,
                    y: startY + (idx + 1) * nodeSpacingY,
                    color,
                    type: hop.isDestinationReached ? 'target' : 'hop'
                });
            });

            if (traceCompleted && !destReached) {
                allNodes.push({
                    hopNumber: -1,
                    ip: targetHost ? truncateText(targetHost, 24) : 'Destination',
                    hostname: 'Destination Not Reached (Max hops or ICMP filtered)',
                    rttMs: -1,
                    isDestinationReached: false,
                    isTimedOut: true,
                    ipScope: 'Unreached',
                    x: nodeX,
                    y: startY + allNodes.length * nodeSpacingY,
                    color: '#F59E0B',
                    type: 'unreached'
                });
            }

            const lastY = allNodes[allNodes.length - 1].y;
            contentBounds = {
                minX: 8,
                minY: 4,
                maxX: 332,
                maxY: lastY + 38
            };

            // Directed Edges
            for (let i = 0; i < allNodes.length - 1; i++) {
                const src = allNodes[i];
                const dst = allNodes[i + 1];

                let marker = 'url(#arrow-green)';
                let linkClass = 'link-active';
                if (dst.isTimedOut || dst.type === 'unreached') {
                    marker = 'url(#arrow-red)';
                    linkClass = 'link-timeout';
                } else if (dst.isDestinationReached) {
                    marker = 'url(#arrow-reached)';
                    linkClass = 'link-active';
                } else if (dst.rttMs >= 100) {
                    marker = 'url(#arrow-amber)';
                    linkClass = 'link-slow';
                }

                const line = createSvgEl('line', {
                    x1: src.x,
                    y1: src.y,
                    x2: dst.x,
                    y2: dst.y,
                    class: linkClass,
                    'marker-end': marker
                });
                linksGroup.appendChild(line);

                if (dst.rttMs > 0) {
                    const edgeRtt = createSvgEl('text', {
                        x: src.x - 10,
                        y: (src.y + dst.y) / 2 + 3,
                        fill: dst.rttMs >= 100 ? '#F59E0B' : '#94A3B8',
                        'font-size': '9px',
                        'font-family': 'monospace',
                        'text-anchor': 'end'
                    });
                    edgeRtt.textContent = dst.rttMs.toFixed(1) + 'ms';
                    linksGroup.appendChild(edgeRtt);
                }
            }

            // Nodes + Details Card per Node
            allNodes.forEach((node) => {
                const g = createSvgEl('g', {
                    transform: 'translate(' + node.x + ',' + node.y + ')',
                    class: 'node-group'
                });

                if (node.hopNumber > 0) {
                    g.addEventListener('click', () => {
                        if (pointerMoved) return;
                        selectedHopNumber = node.hopNumber;
                        renderGraph();
                        if (window.AndroidBridge && window.AndroidBridge.onNodeSelected) {
                            window.AndroidBridge.onNodeSelected(node.hopNumber);
                        }
                    });
                }

                const isSelected = node.hopNumber > 0 && node.hopNumber === selectedHopNumber;
                if (isSelected) {
                    g.appendChild(createSvgEl('circle', {
                        r: 25,
                        fill: 'none',
                        stroke: '#FFFFFF',
                        'stroke-width': 2,
                        'stroke-opacity': 0.8
                    }));
                }

                g.appendChild(createSvgEl('circle', {
                    r: node.type === 'target' ? 21 : 18,
                    fill: '#1E293B',
                    stroke: node.color,
                    'stroke-width': isSelected ? 3.5 : 2.5
                }));

                const badge = createSvgEl('text', {
                    y: 4,
                    'text-anchor': 'middle',
                    fill: node.color,
                    'font-size': node.hopNumber <= 0 ? '9px' : '11px',
                    'font-weight': 'bold'
                });
                badge.textContent = node.hopNumber === 0 ? 'SRC' : (node.hopNumber < 0 ? 'END' : '#' + node.hopNumber);
                g.appendChild(badge);

                // Node Info Card to the right of circle
                const cardBg = createSvgEl('rect', {
                    x: 28,
                    y: -21,
                    width: 242,
                    height: 42,
                    rx: 8,
                    fill: isSelected ? '#263549' : '#182436',
                    stroke: isSelected ? node.color : 'rgba(148, 163, 184, 0.22)',
                    'stroke-width': isSelected ? 1.5 : 1
                });
                g.appendChild(cardBg);

                const primaryText = createSvgEl('text', {
                    x: 36,
                    y: -4,
                    fill: '#F8FAFC',
                    'font-size': '11.5px',
                    'font-weight': 'bold',
                    'font-family': 'monospace'
                });
                const ipDisplay = node.isTimedOut && node.type !== 'unreached'
                    ? '* (Request Timed Out)'
                    : truncateText(node.ip, 28);
                primaryText.textContent = ipDisplay;
                g.appendChild(primaryText);

                const subText = createSvgEl('text', {
                    x: 36,
                    y: 12,
                    fill: node.isDestinationReached ? '#10B981' : '#94A3B8',
                    'font-size': '9.5px'
                });
                let subtitle = '';
                if (node.type === 'source') {
                    subtitle = node.hostname;
                } else if (node.type === 'unreached') {
                    subtitle = truncateText(node.hostname, 36);
                } else if (node.isDestinationReached) {
                    subtitle = node.hostname ? ('Reached • ' + truncateText(node.hostname, 24)) : 'Target Destination Reached';
                } else if (node.isTimedOut) {
                    subtitle = 'No ICMP response within timeout';
                } else if (node.hostname) {
                    subtitle = truncateText(node.hostname, 24) + (node.ipScope ? ' • ' + node.ipScope : '');
                } else {
                    subtitle = 'No reverse DNS • ' + (node.ipScope || 'Router Hop');
                }
                if (node.alternateIps && node.alternateIps.length > 0) {
                    subtitle += ' (+' + node.alternateIps.length + ' alt)';
                }
                subText.textContent = subtitle;
                g.appendChild(subText);

                // Scope / RTT Pill on top-right of card
                if (node.hopNumber > 0) {
                    const pillTextStr = node.isTimedOut
                        ? 'TIMEOUT'
                        : (node.rttMs > 0 ? node.rttMs.toFixed(1) + ' ms' : (node.ipScope || 'OK'));
                    const pill = createSvgEl('text', {
                        x: 262,
                        y: -4,
                        'text-anchor': 'end',
                        fill: node.color,
                        'font-size': '10px',
                        'font-weight': 'bold',
                        'font-family': 'monospace'
                    });
                    pill.textContent = pillTextStr;
                    g.appendChild(pill);
                }

                nodesGroup.appendChild(g);
            });

            if (!hasUserInteracted) {
                window.fitGraphToViewport();
            } else {
                updateTransform();
            }
        }

        if (typeof ResizeObserver !== 'undefined') {
            new ResizeObserver(() => {
                if (currentHops.length > 0 && !hasUserInteracted) {
                    renderGraph();
                }
            }).observe(container);
        }
        window.addEventListener('resize', () => {
            if (currentHops.length > 0 && !hasUserInteracted) {
                renderGraph();
            }
        });
    </script>
</body>
</html>
    """.trimIndent()
}
