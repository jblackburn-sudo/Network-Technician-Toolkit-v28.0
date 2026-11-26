package com.example.networktoolkit.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Repository abstracting Room database operations for ping history.
 */
class PingHistoryRepository(
    private val pingResultDao: PingResultDao
) {
    val allHistory: Flow<List<PingResultEntity>> = pingResultDao.getAllResults()

    fun searchHistory(query: String): Flow<List<PingResultEntity>> =
        pingResultDao.searchResults(query)

    suspend fun insert(result: PingResultEntity): Long =
        pingResultDao.insertResult(result)

    suspend fun deleteById(id: Long) =
        pingResultDao.deleteById(id)

    suspend fun clearAll() =
        pingResultDao.clearAll()

    companion object {
        @Volatile
        private var INSTANCE: PingHistoryRepository? = null

        fun getInstance(context: Context): PingHistoryRepository {
            return INSTANCE ?: synchronized(this) {
                val db = NetworkToolkitDatabase.getInstance(context)
                val repo = PingHistoryRepository(db.pingResultDao())
                INSTANCE = repo
                repo
            }
        }

        /**
         * Asynchronously saves a completed ping result into the Room database.
         */
        @JvmStatic
        fun saveResultAsync(
            context: Context,
            targetHost: String,
            resolvedIp: String,
            transmitted: Int,
            received: Int,
            lossPercentage: Double,
            minRttMs: Double,
            avgRttMs: Double,
            maxRttMs: Double,
            mdevRttMs: Double,
            protocolMode: String,
            durationMs: Long,
            logOutput: String
        ) {
            val repo = getInstance(context)
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val entity = PingResultEntity(
                        targetHost = targetHost,
                        resolvedIp = resolvedIp,
                        packetsTransmitted = transmitted,
                        packetsReceived = received,
                        packetLossPercentage = lossPercentage,
                        minRttMs = minRttMs,
                        avgRttMs = avgRttMs,
                        maxRttMs = maxRttMs,
                        mdevRttMs = mdevRttMs,
                        protocolMode = protocolMode,
                        durationMs = durationMs,
                        logOutput = logOutput
                    )
                    repo.insert(entity)
                } catch (_: Exception) {}
            }
        }
    }
}
