package com.example.networktoolkit.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity representing an individual past ping test result.
 */
@Entity(tableName = "ping_results")
data class PingResultEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val targetHost: String,
    val resolvedIp: String,
    val packetsTransmitted: Int,
    val packetsReceived: Int,
    val packetLossPercentage: Double,
    val minRttMs: Double,
    val avgRttMs: Double,
    val maxRttMs: Double,
    val mdevRttMs: Double,
    val protocolMode: String,
    val durationMs: Long,
    val logOutput: String,
    val timestamp: Long = System.currentTimeMillis()
)
