package com.example.networktoolkit.signalmap

import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.example.networktoolkit.rememberShouldPauseNonEssentialAnimations

/**
 * Interactive Dynamic 2D Blueprint & Signal Heatmap Canvas.
 * Adapts to any configured number of rooms/floors, clips non-overlapping room labels cleanly,
 * renders radial heatmap fields and sample pins, and respects global battery-saver animation policy.
 */
@Composable
fun SignalMapCanvas(
    propertyType: PropertyType,
    rooms: List<RoomZone>,
    samples: List<SignalSample>,
    selectedRoom: RoomZone?,
    selectedSample: SignalSample?,
    onRoomSelected: (RoomZone) -> Unit,
    onCanvasTapped: (relX: Float, relY: Float) -> Unit,
    onSampleTapped: (SignalSample) -> Unit,
    modifier: Modifier = Modifier,
    isBeaconPulseActive: Boolean = samples.isNotEmpty()
) {
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val pauseForBatterySaver = rememberShouldPauseNonEssentialAnimations()
    val shouldPulse = isBeaconPulseActive &&
        !pauseForBatterySaver &&
        lifecycleState.isAtLeast(Lifecycle.State.STARTED) &&
        rooms.any { it.isPrimaryApLocation }
    val pulseRadiusScale = rememberBeaconPulseScale(isActive = shouldPulse)

    val dynamicAspectRatio = remember(propertyType, rooms) {
        DynamicFloorPlanLayoutEngine.recommendedAspectRatio(propertyType, rooms)
    }

    val roomLabelPaint = remember {
        TextPaint().apply {
            color = android.graphics.Color.WHITE
            textSize = 32f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
    }

    val roomSubtitlePaint = remember {
        TextPaint().apply {
            color = android.graphics.Color.argb(190, 160, 185, 220)
            textSize = 22f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
    }

    val pinBadgePaint = remember {
        Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 28f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
    }

    val pinBgPaint = remember {
        Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
        }
    }

    val visibleRoomIds = remember(rooms) { rooms.map { it.id }.toSet() }
    val displayedSamples = remember(samples, visibleRoomIds) {
        samples.filter { s ->
            s.roomId.isBlank() || visibleRoomIds.contains(s.roomId)
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(dynamicAspectRatio)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF0D162B))
            .border(1.5.dp, Color(0xFF1E3A5F), RoundedCornerShape(16.dp))
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(propertyType, displayedSamples, rooms) {
                    detectTapGestures { offset ->
                        val relX = (offset.x / size.width).coerceIn(0.01f, 0.99f)
                        val relY = (offset.y / size.height).coerceIn(0.01f, 0.99f)

                        val touchRadiusX = 36f / size.width
                        val touchRadiusY = 36f / size.height

                        val hitSample = displayedSamples.find { s ->
                            val dx = (s.relX - relX) / touchRadiusX
                            val dy = (s.relY - relY) / touchRadiusY
                            (dx * dx + dy * dy) <= 1.0f
                        }

                        if (hitSample != null) {
                            onSampleTapped(hitSample)
                            return@detectTapGestures
                        }

                        val hitRoom = rooms.find { it.containsPoint(relX, relY) }
                        if (hitRoom != null) {
                            onRoomSelected(hitRoom)
                        }

                        onCanvasTapped(relX, relY)
                    }
                }
        ) {
            val canvasW = size.width
            val canvasH = size.height

            drawBlueprintGrid(canvasW, canvasH)

            drawRooms(
                rooms = rooms,
                samples = displayedSamples,
                selectedRoom = selectedRoom,
                canvasW = canvasW,
                canvasH = canvasH,
                propertyType = propertyType,
                roomLabelPaint = roomLabelPaint,
                roomSubtitlePaint = roomSubtitlePaint
            )

            if (displayedSamples.isNotEmpty()) {
                drawSignalHeatmap(
                    samples = displayedSamples,
                    canvasW = canvasW,
                    canvasH = canvasH,
                    propertyType = propertyType
                )
            }

            val apRoom = rooms.find { it.isPrimaryApLocation }
            if (apRoom != null) {
                drawRouterBeacon(
                    apX = apRoom.centerX * canvasW,
                    apY = (apRoom.relY + minOf(apRoom.relHeight * 0.28f, 0.08f)) * canvasH,
                    pulseScale = pulseRadiusScale
                )
            }

            displayedSamples.forEachIndexed { index, sample ->
                drawSamplePin(
                    sample = sample,
                    index = index + 1,
                    isSelected = selectedSample?.id == sample.id,
                    canvasW = canvasW,
                    canvasH = canvasH,
                    pinBgPaint = pinBgPaint,
                    pinBadgePaint = pinBadgePaint
                )
            }
        }
    }
}

private fun DrawScope.drawBlueprintGrid(canvasW: Float, canvasH: Float) {
    val gridStep = 32f
    val gridColor = Color(0x184FC3F7)
    val axisColor = Color(0x284FC3F7)

    var x = 0f
    while (x <= canvasW) {
        val color = if ((x / gridStep).toInt() % 4 == 0) axisColor else gridColor
        drawLine(
            color = color,
            start = Offset(x, 0f),
            end = Offset(x, canvasH),
            strokeWidth = 1f
        )
        x += gridStep
    }

    var y = 0f
    while (y <= canvasH) {
        val color = if ((y / gridStep).toInt() % 4 == 0) axisColor else gridColor
        drawLine(
            color = color,
            start = Offset(0f, y),
            end = Offset(canvasW, y),
            strokeWidth = 1f
        )
        y += gridStep
    }
}

private fun DrawScope.drawRooms(
    rooms: List<RoomZone>,
    samples: List<SignalSample>,
    selectedRoom: RoomZone?,
    canvasW: Float,
    canvasH: Float,
    propertyType: PropertyType,
    roomLabelPaint: TextPaint,
    roomSubtitlePaint: TextPaint
) {
    val outerWallColor = when (propertyType) {
        PropertyType.HOUSE -> Color(0xFF4FC3F7)
        PropertyType.OFFICE -> Color(0xFF81C784)
        PropertyType.OTHER -> Color(0xFFFFB74D)
        PropertyType.APARTMENT -> Color(0xFF29B6F6)
    }
    drawRoundRect(
        color = outerWallColor,
        topLeft = Offset(canvasW * 0.02f, canvasH * 0.02f),
        size = Size(canvasW * 0.96f, canvasH * 0.96f),
        cornerRadius = CornerRadius(14f, 14f),
        style = Stroke(width = 3.5f)
    )

    val multiFloor = rooms.map { it.floorNumber }.distinct().size > 1

    rooms.forEach { room ->
        val roomLeft = room.relX * canvasW
        val roomTop = room.relY * canvasH
        val roomW = room.relWidth * canvasW
        val roomH = room.relHeight * canvasH

        val isSelected = selectedRoom?.id == room.id

        val roomFillColor = when {
            isSelected -> Color(0x3300E5FF)
            room.isPrimaryApLocation -> Color(0x1F00E676)
            else -> Color(0x141E3A5F)
        }

        drawRoundRect(
            color = roomFillColor,
            topLeft = Offset(roomLeft, roomTop),
            size = Size(roomW, roomH),
            cornerRadius = CornerRadius(8f, 8f)
        )

        val wallStrokeColor = if (isSelected) Color(0xFF00E5FF) else Color(0x664FC3F7)
        val wallWidth = if (isSelected) 3f else 1.8f
        val pathEffect = if (isSelected) null else PathEffect.dashPathEffect(floatArrayOf(12f, 6f), 0f)

        drawRoundRect(
            color = wallStrokeColor,
            topLeft = Offset(roomLeft, roomTop),
            size = Size(roomW, roomH),
            cornerRadius = CornerRadius(8f, 8f),
            style = Stroke(width = wallWidth, pathEffect = pathEffect)
        )

        // Adaptive non-overlapping text sizing clipped strictly within room walls
        val centerX = roomLeft + roomW / 2f
        val centerY = roomTop + roomH / 2f
        val availTextWidth = (roomW - 16f).coerceAtLeast(24f)

        val labelSize = when {
            roomW < 140f || roomH < 85f -> 22f
            roomW < 210f || roomH < 120f -> 26f
            else -> 32f
        }
        val subSize = (labelSize * 0.68f).coerceAtLeast(16f)
        roomLabelPaint.textSize = labelSize
        roomSubtitlePaint.textSize = subSize

        val titleCandidate = TextUtils.ellipsize(
            room.name,
            roomLabelPaint,
            availTextWidth,
            TextUtils.TruncateAt.END
        ).toString()

        val roomSamplesCount = samples.count { it.belongsToRoom(room) }
        val subtitleRaw = buildString {
            if (multiFloor) append("F${room.floorNumber} • ")
            append(room.effectiveRoomType)
            if (roomSamplesCount > 0) append(" ($roomSamplesCount)")
        }
        val subtitleCandidate = TextUtils.ellipsize(
            subtitleRaw,
            roomSubtitlePaint,
            availTextWidth,
            TextUtils.TruncateAt.END
        ).toString()

        drawContext.canvas.nativeCanvas.apply {
            save()
            clipRect(roomLeft + 4f, roomTop + 4f, roomLeft + roomW - 4f, roomTop + roomH - 4f)
            if (roomH >= 64f) {
                drawText(titleCandidate, centerX, centerY - 2f, roomLabelPaint)
                drawText(subtitleCandidate, centerX, centerY + subSize + 4f, roomSubtitlePaint)
            } else {
                drawText(titleCandidate, centerX, centerY + labelSize * 0.35f, roomLabelPaint)
            }
            restore()
        }
    }
}

private fun DrawScope.drawSignalHeatmap(
    samples: List<SignalSample>,
    canvasW: Float,
    canvasH: Float,
    propertyType: PropertyType
) {
    val baseRadius = if (propertyType == PropertyType.APARTMENT) canvasW * 0.34f else canvasW * 0.25f

    samples.forEach { sample ->
        val centerX = sample.relX * canvasW
        val centerY = sample.relY * canvasH

        val heatColor = when {
            sample.rssiDbm >= -55 -> Color(0x8800E676)
            sample.rssiDbm >= -67 -> Color(0x7700E5FF)
            sample.rssiDbm >= -75 -> Color(0x66FFD600)
            sample.rssiDbm >= -82 -> Color(0x66FF9100)
            else -> Color(0x77FF1744)
        }

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    heatColor,
                    heatColor.copy(alpha = 0.35f),
                    heatColor.copy(alpha = 0.08f),
                    Color.Transparent
                ),
                center = Offset(centerX, centerY),
                radius = baseRadius
            ),
            radius = baseRadius,
            center = Offset(centerX, centerY)
        )
    }
}

private fun DrawScope.drawRouterBeacon(
    apX: Float,
    apY: Float,
    pulseScale: Float
) {
    drawCircle(
        color = Color(0x4400E676),
        radius = 24f * pulseScale,
        center = Offset(apX, apY),
        style = Stroke(width = 2f)
    )
    drawCircle(
        color = Color(0xFF00E676),
        radius = 9f,
        center = Offset(apX, apY)
    )
    drawCircle(
        color = Color.White,
        radius = 3.5f,
        center = Offset(apX, apY)
    )
}

private fun DrawScope.drawSamplePin(
    sample: SignalSample,
    index: Int,
    isSelected: Boolean,
    canvasW: Float,
    canvasH: Float,
    pinBgPaint: Paint,
    pinBadgePaint: Paint
) {
    val px = sample.relX * canvasW
    val py = sample.relY * canvasH

    val ratingColor = sample.rating.color

    if (isSelected) {
        drawCircle(
            color = Color.White,
            radius = 22f,
            center = Offset(px, py),
            style = Stroke(width = 3.5f)
        )
    }

    if (sample.isSimulated) {
        drawCircle(
            color = Color(0xFFFFD600),
            radius = 19f,
            center = Offset(px, py),
            style = Stroke(
                width = 2.2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f), 0f)
            )
        )
    }

    drawCircle(
        color = ratingColor,
        radius = 15f,
        center = Offset(px, py)
    )

    drawCircle(
        color = Color(0xFF0B132B),
        radius = 8f,
        center = Offset(px, py)
    )

    val badgeLabel = if (sample.isSimulated) "${sample.rssiDbm} dBm SIM" else "${sample.rssiDbm} dBm"
    val badgeW = if (sample.isSimulated) 128f else 96f
    val badgeH = 32f
    val badgeLeft = (px - badgeW / 2f).coerceIn(4f, canvasW - badgeW - 4f)
    val badgeTop = (py - 44f).coerceIn(4f, canvasH - badgeH - 4f)

    pinBgPaint.color = android.graphics.Color.argb(220, 15, 25, 45)
    drawContext.canvas.nativeCanvas.drawRoundRect(
        badgeLeft,
        badgeTop,
        badgeLeft + badgeW,
        badgeTop + badgeH,
        10f,
        10f,
        pinBgPaint
    )

    drawRoundRect(
        color = if (sample.isSimulated) Color(0xFFFFD600) else ratingColor,
        topLeft = Offset(badgeLeft, badgeTop),
        size = Size(badgeW, badgeH),
        cornerRadius = CornerRadius(10f, 10f),
        style = Stroke(width = 1.5f)
    )

    drawContext.canvas.nativeCanvas.apply {
        drawText(
            badgeLabel,
            badgeLeft + badgeW / 2f,
            badgeTop + 23f,
            pinBadgePaint
        )
    }
}

@Composable
private fun rememberBeaconPulseScale(isActive: Boolean): Float {
    if (!isActive) return 1.15f
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseRadiusScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseRadius"
    )
    return pulseRadiusScale
}
