package com.example.networktoolkit.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room database entity representing an individual past traceroute test session.
 * Stores target host, resolved IP, total hops, completion status, latency, duration,
 * and a JSON serialization of all intermediate hops.
 */
@Entity(tableName = "traceroute_results")
data class TracerouteResultEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val targetHost: String,
    val targetIp: String,
    val totalHops: Int,
    val isDestinationReached: Boolean,
    val avgRttMs: Double?,
    val durationMs: Long,
    val hopsJson: String,
    val timestamp: Long = System.currentTimeMillis()
)
