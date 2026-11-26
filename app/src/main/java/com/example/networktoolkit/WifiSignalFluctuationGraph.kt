package com.example.networktoolkit

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Real-time Wi-Fi Signal Fluctuation Visual Graph Component.
 *
 * Streams RSSI readings from [RealTimeWifiSignalProvider] and plots fluctuations over time
 * with explicit horizontal reference thresholds from -50 dBm (Excellent) down to -90 dBm (Poor),
 * enclosed within the RF Ceiling (-30 dBm) and RF Floor (-100 dBm) scale.
 */
@Composable
fun WifiSignalFluctuationGraph(
    modifier: Modifier = Modifier,
    externalProvider: RealTimeWifiSignalProvider? = null,
    autoStart: Boolean = true
) {
    val context = LocalContext.current
    val provider = remember(externalProvider) {
        externalProvider ?: RealTimeWifiSignalProvider(context)
    }
    val ownsProvider = externalProvider == null

    DisposableEffect(provider, autoStart) {
        if (autoStart) {
            provider.start()
        }
        onDispose {
            if (ownsProvider) {
                provider.dispose()
            } else {
                provider.stop()
            }
        }
    }

    val state by provider.state.collectAsStateWithLifecycle()
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val pauseForBatterySaver = rememberShouldPauseNonEssentialAnimations()
    var inspectedSample by remember { mutableStateOf<WifiSignalDataPoint?>(null) }

    val shouldPulse = lifecycleState.isAtLeast(Lifecycle.State.STARTED) &&
        !pauseForBatterySaver &&
        state.isWifiConnected &&
        state.isStreaming &&
        !state.isPaused &&
        state.samples.isNotEmpty()
    val pulseRadius = rememberLivePulseRadius(isActive = shouldPulse)

    val currentDbm = state.currentRssiDbm
    val activeTier = currentDbm?.let { SignalStrengthTier.fromDbm(it) }
    val activeColor = activeTier?.let { Color(it.hexColor) } ?: Color(0xFF4FC3F7)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("wifi_signal_fluctuation_graph"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF0D172A)
        ),
        border = BorderStroke(1.dp, Color(0xFF1F385C))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header Row: Live Stream Status + Current dBm Kind Pill + Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    state.isPaused -> Color(0xFFFFD600)
                                    state.isWifiConnected && state.isStreaming -> Color(0xFF00E676)
                                    else -> Color(0xFFFF5252)
                                }
                            )
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.ShowChart,
                                contentDescription = null,
                                tint = Color(0xFF4FC3F7),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "REAL-TIME WI-FI SIGNAL GRAPH",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF4FC3F7),
                                letterSpacing = 0.8.sp
                            )
                        }
                        val wifiPrivacy = rememberWifiPrivacySettings()
                        val displaySsid = state.ssid?.let { rawSsid ->
                            if (wifiPrivacy.showSsid) rawSsid else WifiPrivacyPreferences.MASKED_SSID
                        }
                        Text(
                            text = (displaySsid?.let { "$it • ${state.statusMessage}" } ?: state.statusMessage) +
                                if (pauseForBatterySaver) " • Battery Saver (Anim Paused)" else "",
                            fontSize = 11.sp,
                            color = if (pauseForBatterySaver) Color(0xFFFFD600) else Color(0xFFA0B5D0),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (currentDbm != null && activeTier != null) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = activeColor.copy(alpha = 0.16f),
                        border = BorderStroke(1.dp, activeColor.copy(alpha = 0.65f))
                    ) {
                        Text(
                            text = "$currentDbm dBm • ${activeTier.label}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = activeColor,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // Controls Row: Sampling Rate + Pause/Resume + Clear
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Sampling interval chips
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Rate:",
                        fontSize = 11.sp,
                        color = Color(0xFF8FA7C7)
                    )
                    listOf(500L to "0.5s", 1000L to "1s", 2000L to "2s").forEach { (ms, label) ->
                        val selected = state.sampleIntervalMs == ms
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (selected) Color(0xFF00E5FF).copy(alpha = 0.22f) else Color(0xFF152542),
                            border = BorderStroke(
                                1.dp,
                                if (selected) Color(0xFF00E5FF) else Color(0xFF28426B)
                            ),
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { provider.setSampleIntervalMs(ms) }
                        ) {
                            Text(
                                text = label,
                                fontSize = 11.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                color = if (selected) Color(0xFF00E5FF) else Color(0xFFB5C7E0),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                // Pause / Clear action buttons
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { provider.togglePause() },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("signal_graph_pause_button")
                    ) {
                        Icon(
                            imageVector = if (state.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                            contentDescription = if (state.isPaused) "Resume Live Signal Graph" else "Pause Live Signal Graph",
                            tint = Color(0xFF00E5FF)
                        )
                    }

                    IconButton(
                        onClick = {
                            inspectedSample = null
                            provider.clearHistory()
                        },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("signal_graph_clear_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = "Clear Signal Graph History",
                            tint = Color(0xFFFF8A65)
                        )
                    }
                }
            }

            // Inspected Sample Banner (when technician taps/drags across the chart)
            val activeInspect = inspectedSample
            if (activeInspect != null) {
                val inspectTier = SignalStrengthTier.fromDbm(activeInspect.rssiDbm)
                val inspectColor = Color(inspectTier.hexColor)
                val timeStr = remember(activeInspect.timestampMs) {
                    SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(activeInspect.timestampMs))
                }
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF152542),
                    border = BorderStroke(1.dp, inspectColor.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "#${activeInspect.sequence} ($timeStr): ${activeInspect.rssiDbm} dBm • ${inspectTier.label} (${inspectTier.tierCompactRange})",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = inspectColor,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "Tap to clear",
                            fontSize = 10.sp,
                            color = Color(0xFF8FA7C7),
                            modifier = Modifier.clickable { inspectedSample = null }
                        )
                    }
                }
            }

            // Interactive Time-Series Chart with -50 dBm to -90 dBm Threshold Lines
            SignalThresholdTimeChartCanvas(
                samples = state.samples,
                isWifiConnected = state.isWifiConnected,
                pulseRadius = pulseRadius,
                inspectedSample = inspectedSample,
                onSelectSample = { inspectedSample = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(225.dp)
            )

            Spacer(Modifier.height(10.dp))

            // Min / Avg / Max / Jitter Telemetry Summary
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GraphStatPill(
                    label = "MIN",
                    value = state.minDbm?.let { "$it dBm" } ?: "--",
                    color = state.minDbm?.let { Color(SignalStrengthTier.fromDbm(it).hexColor) } ?: Color(0xFF8FA7C7),
                    modifier = Modifier.weight(1f)
                )
                GraphStatPill(
                    label = "AVG",
                    value = state.avgDbm?.let { "${it.roundToInt()} dBm" } ?: "--",
                    color = state.avgDbm?.let { Color(SignalStrengthTier.fromDbm(it.roundToInt()).hexColor) } ?: Color(0xFF8FA7C7),
                    modifier = Modifier.weight(1f)
                )
                GraphStatPill(
                    label = "MAX",
                    value = state.maxDbm?.let { "$it dBm" } ?: "--",
                    color = state.maxDbm?.let { Color(SignalStrengthTier.fromDbm(it).hexColor) } ?: Color(0xFF8FA7C7),
                    modifier = Modifier.weight(1f)
                )
                GraphStatPill(
                    label = "FLUCTUATION",
                    value = if (state.samples.size >= 2) String.format(Locale.US, "±%.1f dB", state.fluctuationStdDevDb) else "--",
                    color = Color(0xFF4FC3F7),
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(10.dp))

            // Explicit dBm Threshold Legend (-50 dBm Excellent to -90 dBm Poor)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SignalGraphThreshold.entries.forEach { threshold ->
                    val tColor = Color(threshold.hexColor)
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = tColor.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, tColor.copy(alpha = 0.4f))
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(tColor)
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                text = threshold.label,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = tColor
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SignalThresholdTimeChartCanvas(
    samples: List<WifiSignalDataPoint>,
    isWifiConnected: Boolean,
    pulseRadius: Float,
    inspectedSample: WifiSignalDataPoint?,
    onSelectSample: (WifiSignalDataPoint?) -> Unit,
    modifier: Modifier = Modifier
) {
    val labelPaint = remember {
        Paint().apply {
            textSize = 26f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.RIGHT
            isAntiAlias = true
        }
    }

    val tagPaint = remember {
        Paint().apply {
            textSize = 23f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.RIGHT
            isAntiAlias = true
        }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF070E1C))
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(samples) {
                    detectTapGestures { offset ->
                        if (samples.isEmpty()) return@detectTapGestures
                        val leftPad = 148f
                        val rightPad = 24f
                        val plotW = (size.width - leftPad - rightPad).coerceAtLeast(1f)
                        val rel = ((offset.x - leftPad) / plotW).coerceIn(0f, 1f)
                        val idx = if (samples.size == 1) 0 else (rel * (samples.size - 1)).roundToInt()
                        onSelectSample(samples.getOrNull(idx))
                    }
                }
                .pointerInput(samples) {
                    detectDragGestures { change, _ ->
                        if (samples.isEmpty()) return@detectDragGestures
                        val leftPad = 148f
                        val rightPad = 24f
                        val plotW = (size.width - leftPad - rightPad).coerceAtLeast(1f)
                        val rel = ((change.position.x - leftPad) / plotW).coerceIn(0f, 1f)
                        val idx = if (samples.size == 1) 0 else (rel * (samples.size - 1)).roundToInt()
                        onSelectSample(samples.getOrNull(idx))
                    }
                }
        ) {
            val leftPad = 148f
            val rightPad = 24f
            val topPad = 24f
            val bottomPad = 24f

            val plotW = (size.width - leftPad - rightPad).coerceAtLeast(1f)
            val plotH = (size.height - topPad - bottomPad).coerceAtLeast(1f)

            val minDbm = RealTimeWifiSignalProvider.GRAPH_FLOOR_DBM.toFloat()   // -100 dBm
            val maxDbm = RealTimeWifiSignalProvider.GRAPH_CEILING_DBM.toFloat() // -30 dBm
            val spanDbm = (maxDbm - minDbm).coerceAtLeast(1f)

            fun dbmToY(dbm: Float): Float {
                val frac = ((dbm - minDbm) / spanDbm).coerceIn(0f, 1f)
                return topPad + plotH * (1f - frac)
            }

            // 1. Draw translucent Signal Strength Threshold Zone Bands
            // Excellent band (-30 to -50 dBm)
            drawRect(
                color = Color(0x1400E676),
                topLeft = Offset(leftPad, dbmToY(-30f)),
                size = Size(plotW, dbmToY(-50f) - dbmToY(-30f))
            )
            // Good band (-50 to -60 dBm)
            drawRect(
                color = Color(0x1000E5FF),
                topLeft = Offset(leftPad, dbmToY(-50f)),
                size = Size(plotW, dbmToY(-60f) - dbmToY(-50f))
            )
            // Fair band (-60 to -70 dBm)
            drawRect(
                color = Color(0x0DFFD600),
                topLeft = Offset(leftPad, dbmToY(-60f)),
                size = Size(plotW, dbmToY(-70f) - dbmToY(-60f))
            )
            // Weak band (-70 to -80 dBm)
            drawRect(
                color = Color(0x0EFF9100),
                topLeft = Offset(leftPad, dbmToY(-70f)),
                size = Size(plotW, dbmToY(-80f) - dbmToY(-70f))
            )
            // Poor band (-80 to -90 dBm) & Floor (-90 to -100 dBm)
            drawRect(
                color = Color(0x14FF1744),
                topLeft = Offset(leftPad, dbmToY(-80f)),
                size = Size(plotW, dbmToY(-100f) - dbmToY(-80f))
            )

            // 2. Draw RF Ceiling (-30) and RF Floor (-100) Boundary Lines
            val ceilingY = dbmToY(-30f)
            val floorY = dbmToY(-100f)
            drawLine(
                color = Color(0x44FFFFFF),
                start = Offset(leftPad, ceilingY),
                end = Offset(leftPad + plotW, ceilingY),
                strokeWidth = 1.5f
            )
            drawLine(
                color = Color(0x44FFFFFF),
                start = Offset(leftPad, floorY),
                end = Offset(leftPad + plotW, floorY),
                strokeWidth = 1.5f
            )

            // 3. Draw Explicit Threshold Lines & Labels from -50 dBm (Excellent) to -90 dBm (Poor)
            val dashEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)
            SignalGraphThreshold.entries.forEach { threshold ->
                val y = dbmToY(threshold.dbm.toFloat())
                val tColor = Color(threshold.hexColor)

                drawLine(
                    color = tColor.copy(alpha = 0.55f),
                    start = Offset(leftPad, y),
                    end = Offset(leftPad + plotW, y),
                    strokeWidth = 2f,
                    pathEffect = dashEffect
                )

                val tierTag = when (threshold) {
                    SignalGraphThreshold.EXCELLENT -> "EXCELLENT"
                    SignalGraphThreshold.GOOD -> "GOOD"
                    SignalGraphThreshold.FAIR -> "FAIR"
                    SignalGraphThreshold.WEAK -> "WEAK"
                    SignalGraphThreshold.POOR -> "POOR"
                }

                labelPaint.color = android.graphics.Color.argb(
                    235,
                    (tColor.red * 255).toInt(),
                    (tColor.green * 255).toInt(),
                    (tColor.blue * 255).toInt()
                )
                tagPaint.color = android.graphics.Color.argb(
                    185,
                    (tColor.red * 255).toInt(),
                    (tColor.green * 255).toInt(),
                    (tColor.blue * 255).toInt()
                )

                drawContext.canvas.nativeCanvas.apply {
                    drawText("${threshold.dbm} dBm", leftPad - 12f, y + 6f, labelPaint)
                    drawText(tierTag, leftPad + plotW - 6f, y - 6f, tagPaint)
                }
            }

            // 4. Vertical Time Grid Lines
            val vCols = 6
            for (i in 0..vCols) {
                val x = leftPad + (plotW * i / vCols.toFloat())
                drawLine(
                    color = Color(0x164FC3F7),
                    start = Offset(x, topPad),
                    end = Offset(x, topPad + plotH),
                    strokeWidth = 1f
                )
            }

            // 5. Plot Live Signal Fluctuation Curve & Gradient Fill
            if (samples.isNotEmpty()) {
                val maxVisibleSlots = RealTimeWifiSignalProvider.DEFAULT_MAX_SAMPLES
                val points = samples.mapIndexed { idx, sample ->
                    val xFraction = if (samples.size == 1) {
                        1f
                    } else if (samples.size < maxVisibleSlots) {
                        // Anchor newest sample to the right edge so waveform scrolls left smoothly
                        val startSlot = maxVisibleSlots - samples.size
                        (startSlot + idx).toFloat() / (maxVisibleSlots - 1).toFloat()
                    } else {
                        idx.toFloat() / (samples.size - 1).toFloat()
                    }
                    val x = leftPad + plotW * xFraction
                    val y = dbmToY(sample.rssiDbm.toFloat())
                    Offset(x, y) to sample
                }

                // Gradient area fill under the line
                if (points.size >= 2) {
                    val fillPath = Path().apply {
                        moveTo(points.first().first.x, topPad + plotH)
                        points.forEach { (pt, _) ->
                            lineTo(pt.x, pt.y)
                        }
                        lineTo(points.last().first.x, topPad + plotH)
                        close()
                    }

                    drawPath(
                        path = fillPath,
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color(0x5500E676),
                                Color(0x3300E5FF),
                                Color(0x14FFD600),
                                Color(0x05FF1744)
                            ),
                            startY = topPad,
                            endY = topPad + plotH
                        )
                    )

                    // Waveform stroke
                    val linePath = Path().apply {
                        moveTo(points.first().first.x, points.first().first.y)
                        for (i in 1 until points.size) {
                            val prev = points[i - 1].first
                            val curr = points[i].first
                            val midX = (prev.x + curr.x) / 2f
                            cubicTo(midX, prev.y, midX, curr.y, curr.x, curr.y)
                        }
                    }

                    drawPath(
                        path = linePath,
                        color = Color(0xFF00E5FF),
                        style = Stroke(
                            width = 4.5f,
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                }

                // Sample vertex dots colored by their SignalStrengthTier
                points.forEachIndexed { idx, (pt, sample) ->
                    val ptColor = Color(SignalStrengthTier.fromDbm(sample.rssiDbm).hexColor)
                    val isInspected = inspectedSample?.sequence == sample.sequence
                    val isLatest = idx == points.lastIndex

                    if (isLatest) {
                        // Pulsing live beacon on newest point
                        drawCircle(
                            color = ptColor.copy(alpha = 0.28f),
                            radius = pulseRadius,
                            center = pt
                        )
                        drawCircle(
                            color = ptColor,
                            radius = 7f,
                            center = pt
                        )
                        drawCircle(
                            color = Color.White,
                            radius = 3f,
                            center = pt
                        )
                    } else if (isInspected) {
                        drawCircle(
                            color = Color.White,
                            radius = 8f,
                            center = pt
                        )
                        drawCircle(
                            color = ptColor,
                            radius = 5f,
                            center = pt
                        )
                    } else if (points.size <= 30 || idx % 2 == 0) {
                        drawCircle(
                            color = ptColor,
                            radius = 3.5f,
                            center = pt
                        )
                    }
                }
            }
        }

        // Honest waiting state overlay when disconnected or waiting for first sample
        if (samples.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xDD0D172A),
                    border = BorderStroke(1.dp, Color(0xFF28426B))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isWifiConnected) Icons.Default.ShowChart else Icons.Default.WifiOff,
                            contentDescription = null,
                            tint = Color(0xFF4FC3F7),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (isWifiConnected) {
                                "Streaming first Wi-Fi RSSI sample..."
                            } else {
                                "Connect to Wi-Fi to plot live fluctuations (-50 to -90 dBm)"
                            },
                            fontSize = 12.sp,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GraphStatPill(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFF132238),
        border = BorderStroke(1.dp, Color(0xFF1E3A5F))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = label,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF8FA7C7),
                letterSpacing = 0.6.sp
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = value,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun rememberLivePulseRadius(isActive: Boolean): Float {
    if (!isActive) return 10f
    val pulseTransition = rememberInfiniteTransition(label = "live_signal_pulse")
    val pulseRadius by pulseTransition.animateFloat(
        initialValue = 6f,
        targetValue = 16f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 950),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_radius"
    )
    return pulseRadius
}
