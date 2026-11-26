package com.example.networktoolkit.signalmap

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import com.example.networktoolkit.wifiscan.WifiNetworkInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

/**
 * Core engine managing live Wi-Fi telemetry polling, spatial heatmap interpolation,
 * coverage analysis, and survey report export.
 */
class SignalMapEngine(private val context: Context) {

    private val wifiManager: WifiManager? =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private val scope = CoroutineScope(Dispatchers.Default)
    private var telemetryJob: Job? = null

    private val _liveTelemetry = MutableStateFlow(LiveConnectedTelemetry())
    val liveTelemetry: StateFlow<LiveConnectedTelemetry> = _liveTelemetry.asStateFlow()

    init {
        pollCurrentTelemetry()
    }

    fun startLiveTelemetryPolling() {
        telemetryJob?.cancel()
        telemetryJob = scope.launch {
            while (isActive) {
                pollCurrentTelemetry()
                delay(1200) // Poll every 1.2s
            }
        }
    }

    fun stopLiveTelemetryPolling() {
        telemetryJob?.cancel()
    }

    @SuppressLint("MissingPermission")
    fun pollCurrentTelemetry() {
        val wm = wifiManager
        if (wm == null || !wm.isWifiEnabled) {
            _liveTelemetry.value = LiveConnectedTelemetry(
                isConnected = false,
                isWifiEnabled = wm?.isWifiEnabled ?: false
            )
            return
        }

        try {
            val info = wm.connectionInfo
            val rfTelemetry = com.example.networktoolkit.WifiTelemetryReader.readLiveTelemetry(
                context = context,
                cachedWifiInfo = info
            )
            val rawSsid = (info?.ssid ?: rfTelemetry.ssid)?.replace("\"", "")?.trim() ?: ""
            val bssid = (info?.bssid ?: rfTelemetry.bssid) ?: ""
            val isConnected = !bssid.isNullOrBlank() &&
                bssid != "00:00:00:00:00:00" &&
                bssid != "02:00:00:00:00:00" &&
                rawSsid.isNotBlank() &&
                rawSsid != "<unknown ssid>"

            if (isConnected) {
                val rssi = rfTelemetry.rssiDbm ?: info.rssi
                val linkSpeed = rfTelemetry.linkSpeedMbps ?: info.linkSpeed
                val freq = rfTelemetry.frequencyMhz ?: info.frequency
                val ch = WifiNetworkInfo.frequencyToChannel(freq)

                _liveTelemetry.value = LiveConnectedTelemetry(
                    isConnected = true,
                    ssid = rawSsid,
                    bssid = bssid.uppercase(Locale.US),
                    currentRssiDbm = rssi,
                    snrDb = rfTelemetry.snrDb,
                    noiseFloorDbm = rfTelemetry.noiseFloorDbm,
                    linkSpeedMbps = if (linkSpeed > 0) linkSpeed else 0,
                    frequencyMhz = freq,
                    channel = ch,
                    isWifiEnabled = true,
                    isSimulatedDemo = false
                )
            } else {
                _liveTelemetry.value = LiveConnectedTelemetry(
                    isConnected = false,
                    ssid = "Technician_Field_AP (Demo)",
                    bssid = "00:1A:2B:3C:4D:5E",
                    currentRssiDbm = -58,
                    snrDb = null,
                    noiseFloorDbm = null,
                    linkSpeedMbps = 433,
                    frequencyMhz = 5180,
                    channel = 36,
                    isWifiEnabled = true,
                    isSimulatedDemo = true
                )
            }
        } catch (e: Exception) {
            _liveTelemetry.value = LiveConnectedTelemetry(
                isConnected = false,
                isWifiEnabled = true,
                isSimulatedDemo = true
            )
        }
    }

    /**
     * Computes Inverse Distance Weighting (IDW) interpolated signal dBm at any normalized (x,y) point.
     */
    fun interpolateDbmAtPoint(
        targetX: Float,
        targetY: Float,
        samples: List<SignalSample>,
        fallbackDbm: Int = -75
    ): Float {
        if (samples.isEmpty()) return fallbackDbm.toFloat()
        if (samples.size == 1) {
            val s = samples[0]
            val dist = sqrt((targetX - s.relX) * (targetX - s.relX) + (targetY - s.relY) * (targetY - s.relY))
            val drop = (dist * 35f)
            return (s.rssiDbm - drop).coerceIn(-95f, -30f)
        }

        var sumWeights = 0.0
        var sumValues = 0.0
        val p = 2.0
        val smoothing = 0.0025

        for (sample in samples) {
            val dx = (targetX - sample.relX).toDouble()
            val dy = (targetY - sample.relY).toDouble()
            val distSq = dx * dx + dy * dy

            if (distSq < 0.0001) {
                return sample.rssiDbm.toFloat()
            }

            val weight = 1.0 / Math.pow(distSq + smoothing, p / 2.0)
            sumWeights += weight
            sumValues += weight * sample.rssiDbm
        }

        return if (sumWeights > 0) {
            (sumValues / sumWeights).toFloat().coerceIn(-95f, -30f)
        } else {
            fallbackDbm.toFloat()
        }
    }

    /**
     * Calculates comprehensive statistical coverage analysis for the site survey.
     */
    @JvmOverloads
    fun calculateCoverageAnalysis(
        propertyType: PropertyType,
        rooms: List<RoomZone>,
        samples: List<SignalSample>,
        propertyConfig: PropertySurveyConfig? = null
    ): CoverageAnalysis {
        if (samples.isEmpty()) {
            return CoverageAnalysis(
                propertyType = propertyType,
                totalSamples = 0,
                averageDbm = 0,
                coverageScorePercent = 0,
                deadZonesCount = 0,
                weakZonesCount = 0,
                goodZonesCount = 0,
                bestRoom = "None",
                weakestRoom = "None",
                recommendedApPlacement = "Select a room or walk through areas and tap 'Record Sample' to generate coverage heat analysis.",
                summaryRecommendation = "No survey samples recorded yet.",
                realSamplesCount = 0,
                simulatedSamplesCount = 0,
                hasSimulatedData = false,
                isSimulatedOnly = false
            )
        }

        val realSamples = samples.filterNot { it.isSimulated }
        val simulatedSamples = samples.filter { it.isSimulated }
        val hasSimulated = simulatedSamples.isNotEmpty()
        val isSimulatedOnly = realSamples.isEmpty()
        // Keep real RF metrics strictly isolated from simulated/demo points whenever real data exists.
        val analysisSamples = if (realSamples.isNotEmpty()) realSamples else simulatedSamples

        val roomsById = rooms.associateBy { it.id }
        val avgDbm = analysisSamples.map { it.rssiDbm }.average().toInt()
        val deadCount = analysisSamples.count { it.rssiDbm < -82 }
        val weakCount = analysisSamples.count { it.rssiDbm in -82..-75 }
        val goodCount = analysisSamples.count { it.rssiDbm > -75 }

        val rawScore = ((avgDbm + 90) * 2.5).toInt()
        val coverageScore = rawScore.coerceIn(5, 100)

        fun resolveDisplayRoomName(s: SignalSample): String {
            return if (s.roomId.isNotBlank()) {
                roomsById[s.roomId]?.name ?: s.roomName
            } else {
                s.roomName
            }
        }

        val roomSamples = analysisSamples.groupBy { resolveDisplayRoomName(it) }
        val roomAverages = roomSamples.mapValues { (_, sList) -> sList.map { it.rssiDbm }.average() }
        val bestRoomName = roomAverages.maxByOrNull { it.value }?.key
            ?: analysisSamples.maxByOrNull { it.rssiDbm }?.let { resolveDisplayRoomName(it) }
            ?: "Unknown"
        val weakestRoomName = roomAverages.minByOrNull { it.value }?.key
            ?: analysisSamples.minByOrNull { it.rssiDbm }?.let { resolveDisplayRoomName(it) }
            ?: "Unknown"

        val recBuilder = StringBuilder()
        val apPlacementBuilder = StringBuilder()

        if (isSimulatedOnly) {
            recBuilder.append("[SIMULATED PREVIEW ONLY — Not live Wi-Fi RF measurements] ")
            apPlacementBuilder.append("[Demo Estimate] ")
        } else if (hasSimulated) {
            recBuilder.append("[Metrics calculated from ${realSamples.size} real RF sample(s); ${simulatedSamples.size} simulated sample(s) excluded] ")
        }

        val propTitle = propertyConfig?.displayPropertyTitle ?: propertyType.title
        val floorSuffix = if ((propertyConfig?.floorCount ?: 1) > 1) ", ${propertyConfig?.floorCount} floors" else ""
        val spaceDesc = "$propTitle (${propertyType.scaleLabel}$floorSuffix)"
        if (deadCount > 0) {
            recBuilder.append("Critical coverage holes detected in $weakestRoomName ($deadCount dead zone points). ")
            recBuilder.append("A secondary Wi-Fi mesh node or wired AP is urgently recommended for $spaceDesc.")
            apPlacementBuilder.append("Install an additional Wi-Fi 6/7 Mesh Satellite midway between the Primary Router and $weakestRoomName.")
        } else if (weakCount > 0) {
            recBuilder.append("Marginal coverage observed in $weakestRoomName. Video conferencing and heavy downloads may buffer.")
            apPlacementBuilder.append("Reposition the main gateway higher and away from metal/appliances, or add a range extender towards $weakestRoomName.")
        } else {
            recBuilder.append("Strong uniform Wi-Fi RF signal strength observed across the surveyed $spaceDesc (RF strength does not verify end-to-end Internet throughput).")
            apPlacementBuilder.append("Current AP placement provides strong local RF coverage. No extra access points or extenders indicated by RSSI.")
        }

        return CoverageAnalysis(
            propertyType = propertyType,
            totalSamples = samples.size,
            averageDbm = avgDbm,
            coverageScorePercent = coverageScore,
            deadZonesCount = deadCount,
            weakZonesCount = weakCount,
            goodZonesCount = goodCount,
            bestRoom = bestRoomName,
            weakestRoom = weakestRoomName,
            recommendedApPlacement = apPlacementBuilder.toString(),
            summaryRecommendation = recBuilder.toString(),
            realSamplesCount = realSamples.size,
            simulatedSamplesCount = simulatedSamples.size,
            hasSimulatedData = hasSimulated,
            isSimulatedOnly = isSimulatedOnly
        )
    }

    /**
     * Generates a plain-text technician site survey report for export and sharing.
     */
    @JvmOverloads
    fun generateTextReport(
        propertyType: PropertyType,
        rooms: List<RoomZone>,
        samples: List<SignalSample>,
        analysis: CoverageAnalysis,
        telemetry: LiveConnectedTelemetry,
        propertyConfig: PropertySurveyConfig? = null
    ): String {
        val sb = StringBuilder()
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val now = sdf.format(Date())

        val dataIntegrityLabel = when {
            samples.isEmpty() -> "No Samples Recorded"
            analysis.isSimulatedOnly -> "SIMULATED / DEMO PREVIEW ONLY (Not Live RF)"
            analysis.hasSimulatedData -> "Mixed (${analysis.realSamplesCount} Real RF / ${analysis.simulatedSamplesCount} Simulated; stats from Real RF only)"
            else -> "Verified Live Wi-Fi RF Measurements Only"
        }

        sb.append("=================================================================\n")
        sb.append("          NETWORK TECHNICIAN TOOLKIT - WI-FI SIGNAL MAP          \n")
        sb.append("                       SITE SURVEY REPORT                        \n")
        sb.append("=================================================================\n")
        sb.append("Survey Date        : ").append(now).append("\n")
        if (!propertyConfig?.propertyName.isNullOrBlank()) {
            sb.append("Property Name / ID : ").append(propertyConfig?.propertyName).append("\n")
        }
        sb.append("Property Type      : ").append(propertyType.title).append(" (").append(propertyType.scaleLabel).append(")\n")
        sb.append("Configured Rooms   : ").append(rooms.size).append("\n")
        sb.append("Configured Floors  : ").append(propertyConfig?.floorCount ?: 1).append("\n")
        sb.append("Property Size      : ").append(String.format(Locale.US, "~%,d sq ft", propertyType.estimatedSqFt)).append("\n")
        sb.append("Data Provenance    : ").append(dataIntegrityLabel).append("\n")
        sb.append("Connected SSID     : ").append(if (telemetry.ssid.isNotBlank()) telemetry.ssid else "Unavailable").append("\n")
        sb.append("Connected BSSID    : ").append(if (telemetry.bssid.isNotBlank()) telemetry.bssid else "Unavailable").append("\n")
        sb.append("Connection State   : ").append(
            when {
                telemetry.isConnected && !telemetry.isSimulatedDemo -> "Connected (Live Wi-Fi RF Telemetry)"
                telemetry.isSimulatedDemo -> "Survey Demo Mode (Offline / Emulated)"
                !telemetry.isWifiEnabled -> "Wi-Fi Disabled"
                else -> "Disconnected"
            }
        ).append("\n")
        val freqStr = if (telemetry.frequencyMhz > 0) "${telemetry.frequencyMhz} MHz (Ch ${telemetry.channel})" else "Unavailable"
        sb.append("Frequency / Ch     : ").append(freqStr).append("\n")
        val speedStr = if (telemetry.linkSpeedMbps > 0) "${telemetry.linkSpeedMbps} Mbps" else "Unavailable"
        sb.append("Connected Link Spd : ").append(speedStr).append("\n")
        val liveRssiRating = telemetry.rating
        sb.append("Current RSSI       : ").append(telemetry.currentRssiDbm).append(" dBm (")
            .append(liveRssiRating.label).append(" - ").append(liveRssiRating.ceilingFloorKind).append(")\n")
        val snrStr = telemetry.snrDb?.let { snr ->
            val snrTier = com.example.networktoolkit.SnrQualityTier.fromSnrDb(snr)
            "$snr dB (${snrTier.label})"
        } ?: "Unavailable"
        sb.append("SNR (Signal/Noise) : ").append(snrStr).append("\n")
        val noiseStr = telemetry.noiseFloorDbm?.let { "$it dBm" } ?: "Unavailable"
        sb.append("Noise Floor        : ").append(noiseStr).append("\n")
        sb.append("Device Platform    : ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL)
            .append(" (Android ").append(Build.VERSION.RELEASE).append(")\n")
        val avgRating = if (samples.isNotEmpty()) SignalRating.fromDbm(analysis.averageDbm) else null
        sb.append("Total Samples      : ").append(samples.size)
            .append(" (Real: ").append(analysis.realSamplesCount)
            .append(", Simulated: ").append(analysis.simulatedSamplesCount).append(")\n")
        sb.append("Average Signal     : ").append(if (samples.isNotEmpty()) "${analysis.averageDbm} dBm" else "Unavailable")
        if (avgRating != null) {
            sb.append(" (").append(avgRating.label).append(" - ").append(avgRating.ceilingFloorKind)
                .append(" [").append(avgRating.rangeLabel).append("])")
        }
        sb.append("\n")
        sb.append("RF Ceiling / Floor : ").append(SignalRating.RF_CEILING_DBM).append(" dBm Ceiling / ")
            .append(SignalRating.RF_FLOOR_DBM).append(" dBm Noise Floor\n")
        sb.append("Coverage Score     : ").append(analysis.coverageScorePercent).append("% / 100%\n")
        sb.append("Best Area          : ").append(analysis.bestRoom).append("\n")
        sb.append("Weakest Area       : ").append(analysis.weakestRoom).append("\n")
        sb.append("Dead Zones (< -82) : ").append(analysis.deadZonesCount).append("\n")
        sb.append("Weak Zones (-75..82): ").append(analysis.weakZonesCount).append("\n")
        sb.append("Strong Zones (>-75): ").append(analysis.goodZonesCount).append("\n")
        sb.append("=================================================================\n\n")

        sb.append("--- CONFIGURED ROOMS & AREAS (${rooms.size}) ---\n")
        rooms.forEachIndexed { idx, room ->
            val count = samples.count { it.belongsToRoom(room) }
            val apTag = if (room.isPrimaryApLocation) " [PRIMARY AP]" else ""
            sb.append(String.format(
                Locale.US,
                "%02d. %-22s | Type: %-18s | Floor %d | Samples: %d%s\n",
                idx + 1,
                room.name,
                room.effectiveRoomType,
                room.floorNumber,
                count,
                apTag
            ))
        }
        sb.append("\n")

        sb.append("--- ROOM-BY-ROOM MEASUREMENTS ---\n")
        if (samples.isEmpty()) {
            sb.append("No measurement samples recorded yet.\n\n")
        } else {
            sb.append(String.format(
                Locale.US,
                "%-4s %-18s %-10s %-8s %-8s %-28s %-12s %-14s %-12s\n",
                "#", "ROOM / ZONE", "SOURCE", "SIGNAL", "SNR", "DBM KIND (FLOOR..CEILING)", "LINK SPEED", "FREQ / CH", "POSITION"
            ))
            sb.append("---- ------------------ ---------- -------- -------- ---------------------------- ------------ -------------- ------------\n")

            samples.forEachIndexed { index, s ->
                val kindStr = "${s.rating.label} (${s.rating.rangeLabel})"
                val sampleSnrStr = s.snrDb?.let { "${it} dB" } ?: "Unavail."
                val sampleFreqStr = if (s.frequencyMhz > 0) "${s.frequencyMhz}MHz/Ch${s.channel}" else "Unavailable"
                val sampleLinkStr = if (s.linkSpeedMbps > 0) "${s.linkSpeedMbps} Mbps" else "Unavailable"
                val sourceStr = if (s.isSimulated) "SIMULATED" else "REAL_WIFI"
                sb.append(String.format(
                    Locale.US,
                    "%02d   %-18s %-10s %4d dBm %-8s %-28s %-12s %-14s (%.2f, %.2f)\n",
                    index + 1,
                    s.roomName.take(18),
                    sourceStr,
                    s.rssiDbm,
                    sampleSnrStr,
                    kindStr.take(28),
                    sampleLinkStr.take(12),
                    sampleFreqStr.take(14),
                    s.relX,
                    s.relY
                ))
            }
            sb.append("\n")
        }

        sb.append("--- TECHNICIAN RECOMMENDATIONS ---\n")
        sb.append("Analysis: ").append(analysis.summaryRecommendation).append("\n\n")
        sb.append("AP Placement: ").append(analysis.recommendedApPlacement).append("\n\n")

        sb.append("=================================================================\n")
        sb.append("                   END OF SIGNAL MAP REPORT                      \n")
        sb.append("=================================================================\n")
        return sb.toString()
    }

    /**
     * Generates a structured JSON survey report.
     */
    @JvmOverloads
    fun generateJsonReport(
        propertyType: PropertyType,
        rooms: List<RoomZone>,
        samples: List<SignalSample>,
        analysis: CoverageAnalysis,
        telemetry: LiveConnectedTelemetry,
        propertyConfig: PropertySurveyConfig? = null
    ): String {
        val root = JSONObject()
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val now = System.currentTimeMillis()

        root.put("reportTitle", "Wi-Fi Signal Heatmap & Site Survey Report")
        root.put("timestamp", now)
        root.put("dateFormatted", sdf.format(Date(now)))
        root.put("surveyId", propertyConfig?.surveyId ?: "")
        root.put("propertyName", propertyConfig?.propertyName ?: "")
        root.put("floorCount", propertyConfig?.floorCount ?: 1)
        root.put("configuredRoomCount", rooms.size)
        root.put("propertyType", propertyType.name)
        root.put("propertyTitle", propertyType.title)
        root.put("scaleLabel", propertyType.scaleLabel)
        root.put("estimatedSqFt", propertyType.estimatedSqFt)
        root.put("ssid", telemetry.ssid)
        root.put("bssid", telemetry.bssid)
        root.put("frequencyMhz", telemetry.frequencyMhz)
        root.put("channel", telemetry.channel)
        root.put("rfCeilingDbm", SignalRating.RF_CEILING_DBM)
        root.put("rfFloorDbm", SignalRating.RF_FLOOR_DBM)
        root.put("totalSamples", samples.size)
        root.put("realSamplesCount", analysis.realSamplesCount)
        root.put("simulatedSamplesCount", analysis.simulatedSamplesCount)
        root.put("containsSimulatedData", analysis.hasSimulatedData)
        root.put("isSimulatedOnly", analysis.isSimulatedOnly)
        root.put("averageDbm", analysis.averageDbm)
        if (samples.isNotEmpty()) {
            val avgR = SignalRating.fromDbm(analysis.averageDbm)
            root.put("averageSignalKind", avgR.label)
            root.put("averageSignalCeilingFloorKind", avgR.ceilingFloorKind)
        }
        root.put("coverageScorePercent", analysis.coverageScorePercent)
        root.put("deadZonesCount", analysis.deadZonesCount)
        root.put("weakZonesCount", analysis.weakZonesCount)
        root.put("goodZonesCount", analysis.goodZonesCount)
        root.put("bestRoom", analysis.bestRoom)
        root.put("weakestRoom", analysis.weakestRoom)
        root.put("summaryRecommendation", analysis.summaryRecommendation)
        root.put("recommendedApPlacement", analysis.recommendedApPlacement)

        val roomsArray = JSONArray()
        for (room in rooms) {
            roomsArray.put(JSONObject().apply {
                put("id", room.id)
                put("name", room.name)
                put("roomType", room.roomType.name)
                put("effectiveRoomType", room.effectiveRoomType)
                put("floorNumber", room.floorNumber)
                put("orderIndex", room.orderIndex)
                put("isPrimaryApLocation", room.isPrimaryApLocation)
            })
        }
        root.put("rooms", roomsArray)

        val samplesArray = JSONArray()
        for (s in samples) {
            val obj = JSONObject().apply {
                put("id", s.id)
                put("roomId", s.roomId)
                put("roomName", s.roomName)
                put("roomTypeLabel", s.roomTypeLabel)
                put("floorNumber", s.floorNumber)
                put("surveyId", s.surveyId)
                put("relX", s.relX)
                put("relY", s.relY)
                put("rssiDbm", s.rssiDbm)
                if (s.snrDb != null) put("snrDb", s.snrDb)
                if (s.noiseFloorDbm != null) put("noiseFloorDbm", s.noiseFloorDbm)
                put("rating", s.rating.name)
                put("ratingLabel", s.rating.label)
                put("ceilingFloorKind", s.rating.ceilingFloorKind)
                put("tierFloorDbm", s.rating.floorDbm)
                put("tierCeilingDbm", s.rating.ceilingDbm)
                put("linkSpeedMbps", s.linkSpeedMbps)
                put("isSimulated", s.isSimulated)
                put("sampleSource", if (s.isSimulated) "SIMULATED" else "REAL_WIFI")
                put("timestamp", s.timestampMs)
                put("notes", s.notes)
            }
            samplesArray.put(obj)
        }
        root.put("samples", samplesArray)

        return root.toString(2)
    }
}
