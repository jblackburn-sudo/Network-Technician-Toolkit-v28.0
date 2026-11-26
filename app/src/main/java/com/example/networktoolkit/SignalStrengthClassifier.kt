package com.example.networktoolkit

/**
 * Standardized RF Wi-Fi Signal Strength classification using explicit Ceiling & Floor dBm bounds.
 *
 * Overall RF Scale:
 * - RF Ceiling (Max Close-Range Saturation): -30 dBm
 * - RF Noise Floor (Hardware / Unusable Threshold): -100 dBm
 */
enum class SignalStrengthTier(
    val label: String,
    val ceilingFloorKind: String,
    val tierCeilingDbm: Int,
    val tierFloorDbm: Int,
    val hexColor: Long,
    val recommendation: String
) {
    EXCELLENT(
        label = "Excellent",
        ceilingFloorKind = "Ceiling Tier (Peak)",
        tierCeilingDbm = -30,
        tierFloorDbm = -55,
        hexColor = 0xFF00E676,
        recommendation = "Optimal for 4K/8K streaming, competitive gaming & ultra-low latency"
    ),
    GOOD(
        label = "Good",
        ceilingFloorKind = "Upper-Mid (Strong)",
        tierCeilingDbm = -56,
        tierFloorDbm = -67,
        hexColor = 0xFF00E5FF,
        recommendation = "Reliable for HD video conferencing & high-throughput downloads"
    ),
    FAIR(
        label = "Fair",
        ceilingFloorKind = "Mid-Range (Moderate)",
        tierCeilingDbm = -68,
        tierFloorDbm = -75,
        hexColor = 0xFFFFD600,
        recommendation = "Acceptable for light web & email; potential buffering under load"
    ),
    WEAK(
        label = "Weak",
        ceilingFloorKind = "Low-Margin (Near Floor)",
        tierCeilingDbm = -76,
        tierFloorDbm = -82,
        hexColor = 0xFFFF9100,
        recommendation = "Frequent packet drops & reduced speed; borderline coverage"
    ),
    DEAD_ZONE(
        label = "Dead Zone",
        ceilingFloorKind = "Noise Floor (Unusable)",
        tierCeilingDbm = -83,
        tierFloorDbm = -100,
        hexColor = 0xFFFF1744,
        recommendation = "Unusable signal near noise floor; mesh satellite or extender required"
    );

    val tierRangeLabel: String
        get() = "$tierFloorDbm dBm floor to $tierCeilingDbm dBm ceiling"

    val tierCompactRange: String
        get() = "$tierFloorDbm..$tierCeilingDbm dBm"

    companion object {
        const val OVERALL_CEILING_DBM = -30
        const val OVERALL_FLOOR_DBM = -100

        fun fromDbm(dbm: Int): SignalStrengthTier {
            return when {
                dbm >= -55 -> EXCELLENT
                dbm >= -67 -> GOOD
                dbm >= -75 -> FAIR
                dbm >= -82 -> WEAK
                else -> DEAD_ZONE
            }
        }

        /**
         * Margin in dB above the -100 dBm RF noise floor.
         */
        fun marginAboveFloorDb(dbm: Int): Int {
            return (dbm - OVERALL_FLOOR_DBM).coerceAtLeast(0)
        }

        /**
         * Headroom in dB below the -30 dBm RF saturation ceiling.
         */
        fun headroomBelowCeilingDb(dbm: Int): Int {
            return (OVERALL_CEILING_DBM - dbm).coerceAtLeast(0)
        }

        /**
         * Normalized 0f..1f position between OVERALL_FLOOR_DBM (-100) and OVERALL_CEILING_DBM (-30).
         */
        fun normalizedFraction(dbm: Int): Float {
            val span = (OVERALL_CEILING_DBM - OVERALL_FLOOR_DBM).toFloat()
            return ((dbm - OVERALL_FLOOR_DBM).toFloat() / span).coerceIn(0f, 1f)
        }

        /**
         * Human-readable summary describing what kind of dBm strength this value represents
         * according to its tier ceiling & floor and the overall RF ceiling & floor.
         */
        fun formatCompactKind(dbm: Int): String {
            val tier = fromDbm(dbm)
            return "${tier.label} • ${tier.ceilingFloorKind} (${tier.tierCompactRange})"
        }

        /**
         * Detailed technician telemetry line with overall floor/ceiling and margin above floor.
         */
        fun formatFullDescription(dbm: Int): String {
            val tier = fromDbm(dbm)
            val margin = marginAboveFloorDb(dbm)
            return "$dbm dBm • ${tier.label} [${tier.ceilingFloorKind}] (Tier: ${tier.tierFloorDbm} to ${tier.tierCeilingDbm} dBm | RF Floor: $OVERALL_FLOOR_DBM dBm, Ceiling: $OVERALL_CEILING_DBM dBm, +$margin dB above floor)"
        }
    }
}

/**
 * Standardized 802.11 Signal-to-Noise Ratio (SNR) quality tiers measured in dB.
 * SNR represents the ratio between received Wi-Fi signal power and background radio noise.
 */
enum class SnrQualityTier(
    val label: String,
    val minDb: Int,
    val maxDb: Int?,
    val hexColor: Long,
    val description: String
) {
    EXCELLENT(
        label = "Excellent",
        minDb = 40,
        maxDb = null,
        hexColor = 0xFF00E676,
        description = ">= 40 dB: High signal clarity above background noise; peak throughput"
    ),
    GOOD(
        label = "Good",
        minDb = 25,
        maxDb = 39,
        hexColor = 0xFF00E5FF,
        description = "25–39 dB: Strong signal-to-noise margin for reliable HD streaming & VoIP"
    ),
    FAIR(
        label = "Fair",
        minDb = 15,
        maxDb = 24,
        hexColor = 0xFFFFD600,
        description = "15–24 dB: Moderate background noise; lower data rates or jitter possible"
    ),
    POOR(
        label = "Poor",
        minDb = 0,
        maxDb = 14,
        hexColor = 0xFFFF1744,
        description = "< 15 dB: High radio noise relative to signal; frequent retries & drops"
    );

    val rangeLabel: String
        get() = if (maxDb != null) "$minDb–$maxDb dB" else ">= $minDb dB"

    companion object {
        fun fromSnrDb(snrDb: Int): SnrQualityTier {
            return when {
                snrDb >= 40 -> EXCELLENT
                snrDb >= 25 -> GOOD
                snrDb >= 15 -> FAIR
                else -> POOR
            }
        }
    }
}
