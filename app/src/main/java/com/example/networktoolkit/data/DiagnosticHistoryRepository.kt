package com.example.networktoolkit.data

import android.content.Context
import com.example.networktoolkit.TracerouteHop
import com.example.networktoolkit.TracerouteSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Repository abstracting Room database operations for all network diagnostic test sessions (Ping and Traceroute).
 */
class DiagnosticHistoryRepository(
    private val pingResultDao: PingResultDao,
    private val tracerouteResultDao: TracerouteResultDao
) {
    val allPingResults: Flow<List<PingResultEntity>> = pingResultDao.getAllResults()
    val allTracerouteResults: Flow<List<TracerouteResultEntity>> = tracerouteResultDao.getAllResults()

    fun searchPingResults(query: String): Flow<List<PingResultEntity>> =
        pingResultDao.searchResults(query)

    fun searchTracerouteResults(query: String): Flow<List<TracerouteResultEntity>> =
        tracerouteResultDao.searchResults(query)

    suspend fun insertPing(result: PingResultEntity): Long =
        pingResultDao.insertResult(result)

    suspend fun insertTraceroute(result: TracerouteResultEntity): Long =
        tracerouteResultDao.insertResult(result)

    suspend fun deletePingById(id: Long) =
        pingResultDao.deleteById(id)

    suspend fun deleteTracerouteById(id: Long) =
        tracerouteResultDao.deleteById(id)

    suspend fun clearAllPings() =
        pingResultDao.clearAll()

    suspend fun clearAllTraceroutes() =
        tracerouteResultDao.clearAll()

    suspend fun clearAllDiagnostics() {
        pingResultDao.clearAll()
        tracerouteResultDao.clearAll()
    }

    suspend fun getAllPingsList(): List<PingResultEntity> =
        pingResultDao.getAllResultsList()

    suspend fun getAllTraceroutesList(): List<TracerouteResultEntity> =
        tracerouteResultDao.getAllResultsList()

    companion object {
        @Volatile
        private var INSTANCE: DiagnosticHistoryRepository? = null

        fun getInstance(context: Context): DiagnosticHistoryRepository {
            return INSTANCE ?: synchronized(this) {
                val db = NetworkToolkitDatabase.getInstance(context)
                val repo = DiagnosticHistoryRepository(
                    pingResultDao = db.pingResultDao(),
                    tracerouteResultDao = db.tracerouteResultDao()
                )
                INSTANCE = repo
                repo
            }
        }

        /**
         * Asynchronously saves a completed traceroute session into the Room database.
         */
        @JvmStatic
        fun saveTracerouteAsync(
            context: Context,
            summary: TracerouteSummary
        ) {
            val appCtx = context.applicationContext
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val hopsArray = JSONArray()
                    summary.hops.forEach { hop ->
                        val obj = JSONObject().apply {
                            put("hopNumber", hop.hopNumber)
                            put("ip", hop.ip)
                            put("hostname", hop.hostname ?: "")
                            put("rttMs", hop.rttMs ?: -1.0)
                            put("isDestinationReached", hop.isDestinationReached)
                            put("isTimedOut", hop.isTimedOut)
                            put("statusText", hop.statusText)
                        }
                        hopsArray.put(obj)
                    }

                    val entity = TracerouteResultEntity(
                        targetHost = summary.target,
                        targetIp = summary.targetIp,
                        totalHops = summary.totalHops,
                        isDestinationReached = summary.isDestinationReached,
                        avgRttMs = summary.avgRttMs,
                        durationMs = summary.durationMs,
                        hopsJson = hopsArray.toString()
                    )

                    getInstance(appCtx).insertTraceroute(entity)
                } catch (e: Exception) {
                    android.util.Log.e("DiagnosticHistoryRepo", "Failed to save traceroute async", e)
                }
            }
        }

        /**
         * Parses a JSON string of hops back into a list of TracerouteHop items.
         */
        @JvmStatic
        fun parseHopsJson(jsonStr: String): List<TracerouteHop> {
            val list = mutableListOf<TracerouteHop>()
            if (jsonStr.isBlank()) return list
            try {
                val array = JSONArray(jsonStr)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val rtt = obj.optDouble("rttMs", -1.0)
                    list.add(
                        TracerouteHop(
                            hopNumber = obj.optInt("hopNumber", i + 1),
                            ip = obj.optString("ip", "*"),
                            hostname = obj.optString("hostname").takeIf { it.isNotBlank() },
                            rttMs = if (rtt >= 0.0) rtt else null,
                            isDestinationReached = obj.optBoolean("isDestinationReached", false),
                            isTimedOut = obj.optBoolean("isTimedOut", false),
                            statusText = obj.optString("statusText", "")
                        )
                    )
                }
            } catch (e: Exception) {
                android.util.Log.e("DiagnosticHistoryRepo", "Error parsing hops JSON", e)
            }
            return list
        }
    }
}
