package com.example.networktoolkit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Compact visual gauge showing what kind of dBm signal strength a reading represents
 * relative to both the tier bounds (tier floor to tier ceiling) and overall RF floor (-100 dBm)
 * to RF ceiling (-30 dBm).
 */
@Composable
fun SignalCeilingFloorGauge(
    rssiDbm: Int,
    modifier: Modifier = Modifier,
    darkSurface: Boolean = false
) {
    val tier = SignalStrengthTier.fromDbm(rssiDbm)
    val tierColor = Color(tier.hexColor)
    val fraction = SignalStrengthTier.normalizedFraction(rssiDbm)
    val marginAboveFloor = SignalStrengthTier.marginAboveFloorDb(rssiDbm)
    val headroomBelowCeiling = SignalStrengthTier.headroomBelowCeilingDb(rssiDbm)

    val containerBg = if (darkSurface) {
        Color(0xFF0D182D)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    }
    val primaryTextColor = if (darkSurface) Color.White else MaterialTheme.colorScheme.onSurface
    val secondaryTextColor = if (darkSurface) Color(0xFFA0B5D0) else MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = containerBg,
        border = androidx.compose.foundation.BorderStroke(1.dp, tierColor.copy(alpha = 0.35f))
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(tierColor)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "${tier.label} • ${tier.ceilingFloorKind}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = tierColor
                    )
                }

                Text(
                    text = "Tier: ${tier.tierFloorDbm}..${tier.tierCeilingDbm} dBm",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = primaryTextColor
                )
            }

            Spacer(Modifier.height(6.dp))

            // Visual Floor-to-Ceiling Spectrum Bar
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(14.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                val trackWidth = maxWidth

                // Gradient spectrum bar from Noise Floor (-100) to Ceiling (-30)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(7.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    Color(0xFFFF1744), // Floor (-100)
                                    Color(0xFFFF9100), // Weak (-82..-76)
                                    Color(0xFFFFD600), // Fair (-75..-68)
                                    Color(0xFF00E5FF), // Good (-67..-56)
                                    Color(0xFF00E676)  // Ceiling (-55..-30)
                                )
                            )
                        )
                )

                // Current dBm Marker Dot
                val markerOffset = ((trackWidth - 12.dp) * fraction).coerceIn(0.dp, trackWidth - 12.dp)
                Box(
                    modifier = Modifier
                        .offset(x = markerOffset)
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .border(2.5.dp, tierColor, CircleShape)
                )
            }

            Spacer(Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Floor: ${SignalStrengthTier.OVERALL_FLOOR_DBM} dBm",
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    color = secondaryTextColor
                )
                Text(
                    text = "+$marginAboveFloor dB above floor • ${headroomBelowCeiling} dB below ceiling",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium,
                    color = secondaryTextColor
                )
                Text(
                    text = "Ceiling: ${SignalStrengthTier.OVERALL_CEILING_DBM} dBm",
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    color = secondaryTextColor
                )
            }
        }
    }
}

/**
 * Displays separate, clearly distinguished live Wi-Fi RSSI (dBm) and SNR (dB) cards.
 *
 * - RSSI (Received Signal Strength Indicator): raw Wi-Fi signal power in dBm + signal-strength quality.
 * - SNR (Signal-to-Noise Ratio): margin between received Wi-Fi signal and background radio noise in dB + quality (Excellent/Good/Fair/Poor).
 * - Displays "Unavailable" whenever either reading cannot be obtained directly from device telemetry.
 */
@Composable
fun WifiRssiAndSnrSection(
    rssiDbm: Int?,
    snrDb: Int?,
    noiseFloorDbm: Int? = null,
    isWifiConnected: Boolean = true,
    onCopyMetric: ((String, String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val rssiTier = rssiDbm?.let { SignalStrengthTier.fromDbm(it) }
    val snrTier = snrDb?.let { SnrQualityTier.fromSnrDb(it) }

    androidx.compose.material3.Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("wifi_rssi_snr_section"),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "WI-FI RF TELEMETRY (RSSI & SNR)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Separate signal power (dBm) and signal-to-noise margin (dB) readings",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isWifiConnected) Color(0xFF00E676).copy(alpha = 0.16f)
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isWifiConnected) Color(0xFF00E676)
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = if (isWifiConnected) "LIVE" else "NO WI-FI",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isWifiConnected) Color(0xFF00C853)
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 1. RSSI Card (dBm)
            val rssiAccent = rssiTier?.let { Color(it.hexColor) } ?: MaterialTheme.colorScheme.onSurfaceVariant
            val rssiValueText = rssiDbm?.let { "$it dBm" } ?: "Unavailable"
            val rssiQualityText = rssiTier?.label ?: "Unavailable"

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("wifi_rssi_card"),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, rssiAccent.copy(alpha = 0.4f)),
                onClick = {
                    onCopyMetric?.invoke(
                        "RSSI (Received Signal Strength Indicator)",
                        if (rssiDbm != null) "$rssiDbm dBm ($rssiQualityText)" else "Unavailable"
                    )
                }
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "RSSI",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(Modifier.width(6.dp))
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                ) {
                                    Text(
                                        text = "Unit: dBm",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Text(
                                text = "Received Signal Strength Indicator",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = rssiValueText,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.ExtraBold,
                                fontFamily = FontFamily.Monospace,
                                color = if (rssiDbm != null) rssiAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag("wifi_rssi_value")
                            )
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = rssiAccent.copy(alpha = 0.15f)
                            ) {
                                Text(
                                    text = "Quality: $rssiQualityText",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = rssiAccent,
                                    modifier = Modifier
                                        .padding(horizontal = 7.dp, vertical = 2.dp)
                                        .testTag("wifi_rssi_quality")
                                )
                            }
                        }
                    }

                    Text(
                        text = "Measures total received Wi-Fi signal power at the antenna (higher/less negative dBm is stronger).",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (rssiDbm != null) {
                        SignalCeilingFloorGauge(
                            rssiDbm = rssiDbm,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    } else {
                        Text(
                            text = "RSSI reading cannot be obtained from the device right now.",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 2. SNR Card (dB)
            val snrAccent = snrTier?.let { Color(it.hexColor) } ?: MaterialTheme.colorScheme.onSurfaceVariant
            val snrValueText = snrDb?.let { "$it dB" } ?: "Unavailable"
            val snrQualityText = snrTier?.label ?: "Unavailable"

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("wifi_snr_card"),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, snrAccent.copy(alpha = 0.4f)),
                onClick = {
                    onCopyMetric?.invoke(
                        "SNR (Signal-to-Noise Ratio)",
                        if (snrDb != null) "$snrDb dB ($snrQualityText)" else "Unavailable"
                    )
                }
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "SNR",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(Modifier.width(6.dp))
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.16f)
                                ) {
                                    Text(
                                        text = "Unit: dB",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Text(
                                text = "Signal-to-Noise Ratio",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = snrValueText,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.ExtraBold,
                                fontFamily = FontFamily.Monospace,
                                color = if (snrDb != null) snrAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag("wifi_snr_value")
                            )
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = snrAccent.copy(alpha = 0.15f)
                            ) {
                                Text(
                                    text = "Quality: $snrQualityText",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = snrAccent,
                                    modifier = Modifier
                                        .padding(horizontal = 7.dp, vertical = 2.dp)
                                        .testTag("wifi_snr_quality")
                                )
                            }
                        }
                    }

                    Text(
                        text = "Measures the relationship between the received Wi-Fi signal and background radio noise (signal margin in dB).",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (snrDb != null && snrTier != null) {
                        val snrFraction = (snrDb.toFloat() / 50f).coerceIn(0f, 1f)
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                        ) {
                            Column(modifier = Modifier.padding(9.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "${snrTier.label} (${snrTier.rangeLabel})",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = snrAccent
                                    )
                                    if (noiseFloorDbm != null) {
                                        Text(
                                            text = "Noise Floor: $noiseFloorDbm dBm",
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Spacer(Modifier.height(5.dp))
                                BoxWithConstraints(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(10.dp),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(6.dp)
                                            .clip(CircleShape)
                                            .background(
                                                Brush.horizontalGradient(
                                                    colors = listOf(
                                                        Color(0xFFFF1744), // Poor (<15 dB)
                                                        Color(0xFFFFD600), // Fair (15-24 dB)
                                                        Color(0xFF00E5FF), // Good (25-39 dB)
                                                        Color(0xFF00E676)  // Excellent (>=40 dB)
                                                    )
                                                )
                                            )
                                    )
                                    val dotOffset = ((maxWidth - 10.dp) * snrFraction).coerceIn(0.dp, maxWidth - 10.dp)
                                    Box(
                                        modifier = Modifier
                                            .offset(x = dotOffset)
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(Color.White)
                                            .border(2.dp, snrAccent, CircleShape)
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = snrTier.description,
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        Text(
                            text = "Background radio noise / SNR telemetry is unavailable from this device's Wi-Fi driver or OS.",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
