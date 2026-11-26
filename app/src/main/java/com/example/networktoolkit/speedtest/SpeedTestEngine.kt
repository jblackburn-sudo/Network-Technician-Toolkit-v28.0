package com.example.networktoolkit.speedtest

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.max

object SpeedTestCalculator {
    fun calculateMbps(bytes: Long, elapsedNanos: Long): Double {
        if (bytes <= 0L || elapsedNanos <= 0L) return 0.0
        val seconds = elapsedNanos / 1_000_000_000.0
        return (bytes * 8.0) / seconds / 1_000_000.0
    }

    /**
     * Returns Pair(averageLatencyMs, rfc3550JitterMs).
     * Returns -1.0 for unavailable metrics (e.g. empty samples, or < 2 samples for jitter).
     */
    fun calculateLatencyAndJitter(samplesMs: List<Double>): Pair<Double, Double> {
        val valid = samplesMs.filter { it.isFinite() && it > 0.0 }
        if (valid.isEmpty()) return Pair(-1.0, -1.0)
        val avg = valid.average()
        if (valid.size < 2) return Pair(avg, -1.0)
        var diffSum = 0.0
        for (i in 1 until valid.size) {
            diffSum += abs(valid[i] - valid[i - 1])
        }
        return Pair(avg, diffSum / (valid.size - 1))
    }
}

/**
 * Native Network Speed Test Engine measuring internet download speed,
 * upload speed, latency, and jitter using Kotlin coroutines and OkHttp streaming.
 */
class SpeedTestEngine @JvmOverloads constructor(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main),
    private val client: OkHttpClient = defaultOkHttpClient(),
    private val latencyEndpoints: List<String> = DEFAULT_LATENCY_ENDPOINTS,
    private val downloadEndpoints: List<String> = DEFAULT_DOWNLOAD_ENDPOINTS,
    private val uploadEndpoints: List<String> = DEFAULT_UPLOAD_ENDPOINTS
) {

    companion object {
        private val DEFAULT_LATENCY_ENDPOINTS = listOf(
            "https://speed.cloudflare.com/__down?bytes=0",
            "https://www.google.com/generate_204",
            "https://1.1.1.1"
        )

        private val DEFAULT_DOWNLOAD_ENDPOINTS = listOf(
            "https://speed.cloudflare.com/__down?bytes=10000000",
            "https://speed.cloudflare.com/__down?bytes=25000000"
        )

        private val DEFAULT_UPLOAD_ENDPOINTS = listOf(
            "https://speed.cloudflare.com/__up"
        )

        fun defaultOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    enum class TestPhase {
        IDLE,
        LATENCY_JITTER,
        DOWNLOAD,
        UPLOAD,
        COMPLETED
    }

    data class SpeedTestResult(
        val downloadMbps: Double,
        val uploadMbps: Double,
        val latencyMs: Double,
        val jitterMs: Double,
        val peakDownloadMbps: Double,
        val peakUploadMbps: Double,
        val bytesDownloaded: Long,
        val bytesUploaded: Long,
        val serverName: String,
        val timestamp: Long = System.currentTimeMillis(),
        val latencyEndpointUsed: String = "Cloudflare Global Edge CDN",
        val methodologyNote: String = "Native Single-Stream HTTP CDN Benchmark"
    )

    interface Callback {
        fun onPhaseChanged(phase: TestPhase)
        fun onLatencyMeasured(latencyMs: Double, jitterMs: Double)
        fun onDownloadProgress(currentMbps: Double, progress: Float, totalBytes: Long)
        fun onUploadProgress(currentMbps: Double, progress: Float, totalBytes: Long)
        fun onCompleted(result: SpeedTestResult)
        fun onError(errorMessage: String)
        fun onCancelled() {}
    }

    private val sessionCounter = AtomicLong(0L)

    @Volatile
    private var activeSessionId: Long = 0L

    @Volatile
    private var activeCall: Call? = null

    private var activeJob: Job? = null

    val isRunning: Boolean
        get() = activeJob?.isActive == true

    fun startTest(callback: Callback) {
        if (isRunning) return

        val sessionId = sessionCounter.incrementAndGet()
        activeSessionId = sessionId

        activeJob = scope.launch {
            try {
                runSpeedTest(sessionId, callback)
            } catch (_: CancellationException) {
                withContext(NonCancellable + Dispatchers.Main) {
                    if (activeSessionId != sessionId) {
                        callback.onCancelled()
                    } else {
                        activeSessionId = sessionCounter.incrementAndGet()
                        callback.onCancelled()
                    }
                }
            } catch (e: Exception) {
                if (activeSessionId == sessionId) {
                    withContext(Dispatchers.Main) {
                        if (activeSessionId == sessionId) {
                            callback.onError(e.message ?: "Speed test encountered an unexpected error")
                        }
                    }
                }
            } finally {
                activeCall = null
                if (activeSessionId == sessionId) {
                    activeJob = null
                }
            }
        }
    }

    fun stop() {
        // Invalidate the current session token immediately before cancelling jobs/sockets
        // so queued Main-thread callbacks from the aborted run are discarded deterministically.
        activeSessionId = sessionCounter.incrementAndGet()
        val callToCancel = activeCall
        activeCall = null
        try {
            callToCancel?.cancel()
        } catch (_: Exception) {}
        activeJob?.cancel()
        activeJob = null
    }

    private fun isSessionAlive(sessionId: Long, coroutineActive: Boolean): Boolean {
        return coroutineActive && activeSessionId == sessionId
    }

    private fun dispatchIfAlive(sessionId: Long, action: () -> Unit) {
        if (activeSessionId != sessionId) return
        scope.launch(Dispatchers.Main) {
            if (activeSessionId == sessionId) {
                action()
            }
        }
    }

    private fun describeEndpoint(url: String): String {
        return when {
            url.contains("cloudflare.com", ignoreCase = true) -> "Cloudflare Global Edge CDN"
            url.contains("google.com", ignoreCase = true) -> "Google Connectivity Check (generate_204)"
            url.contains("1.1.1.1") -> "Cloudflare Anycast (1.1.1.1)"
            else -> url
        }
    }

    private suspend fun runSpeedTest(sessionId: Long, callback: Callback) = withContext(Dispatchers.IO) {
        // 1. LATENCY & JITTER PHASE
        dispatchIfAlive(sessionId) { callback.onPhaseChanged(TestPhase.LATENCY_JITTER) }

        val latencies = mutableListOf<Double>()
        var selectedLatencyUrl: String? = null

        // Select a single healthy endpoint during warmup so primary and fallback RTTs
        // are never mixed into the same RFC 3550 jitter sequence.
        for (candidate in latencyEndpoints) {
            if (!isSessionAlive(sessionId, isActive)) return@withContext
            val warmupReq = Request.Builder()
                .url(candidate)
                .header("Cache-Control", "no-cache")
                .build()
            val call = client.newCall(warmupReq)
            activeCall = call
            try {
                call.execute().use { response ->
                    if (response.isSuccessful || response.code == 204) {
                        selectedLatencyUrl = candidate
                    }
                }
            } catch (_: Exception) {
                if (!isSessionAlive(sessionId, isActive)) return@withContext
            } finally {
                if (activeCall === call) activeCall = null
            }
            if (selectedLatencyUrl != null) break
        }

        if (selectedLatencyUrl != null && isSessionAlive(sessionId, isActive)) {
            delay(80)
            for (i in 1..6) {
                if (!isSessionAlive(sessionId, isActive)) return@withContext
                val req = Request.Builder()
                    .url(selectedLatencyUrl!!)
                    .header("Cache-Control", "no-cache")
                    .build()
                val call = client.newCall(req)
                activeCall = call
                val start = System.nanoTime()
                try {
                    call.execute().use { response ->
                        if (response.isSuccessful || response.code == 204) {
                            val durationMs = (System.nanoTime() - start) / 1_000_000.0
                            latencies.add(durationMs)
                        }
                    }
                } catch (_: Exception) {
                    if (!isSessionAlive(sessionId, isActive)) return@withContext
                } finally {
                    if (activeCall === call) activeCall = null
                }
                delay(120)
            }
        }

        if (!isSessionAlive(sessionId, isActive)) return@withContext

        val (latencyAvg, jitterAvg) = SpeedTestCalculator.calculateLatencyAndJitter(latencies)
        val latencyEndpointLabel = selectedLatencyUrl?.let(::describeEndpoint) ?: "Unavailable"

        withContext(Dispatchers.Main) {
            if (activeSessionId == sessionId) {
                callback.onLatencyMeasured(latencyAvg, jitterAvg)
            }
        }

        delay(200)
        if (!isSessionAlive(sessionId, isActive)) return@withContext

        // 2. DOWNLOAD PHASE
        dispatchIfAlive(sessionId) { callback.onPhaseChanged(TestPhase.DOWNLOAD) }

        var totalBytesDownloaded = 0L
        var accumulatedDownloadNanos = 0L
        var peakDownloadMbps = 0.0
        val downloadDurationMs = 8000L
        val downloadPhaseDeadlineMs = 10000L
        val downloadWallStartNano = System.nanoTime()
        val downloadUrl = downloadEndpoints.first()
        val buffer = ByteArray(32 * 1024)

        while (isSessionAlive(sessionId, isActive) &&
            ((System.nanoTime() - downloadWallStartNano) / 1_000_000 < downloadPhaseDeadlineMs) &&
            (accumulatedDownloadNanos / 1_000_000 < downloadDurationMs)
        ) {
            val req = Request.Builder()
                .url(downloadUrl)
                .header("Cache-Control", "no-cache")
                .build()
            val call = client.newCall(req)
            activeCall = call
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        delay(200)
                        return@use
                    }
                    val stream: InputStream? = response.body?.byteStream()
                    if (stream != null) {
                        val streamStartNano = System.nanoTime()
                        var lastSampleNano = streamStartNano
                        var bytesSinceLastSample = 0L
                        var bytesRead = 0

                        while (isSessionAlive(sessionId, isActive) && stream.read(buffer).also { bytesRead = it } != -1) {
                            totalBytesDownloaded += bytesRead
                            bytesSinceLastSample += bytesRead

                            val now = System.nanoTime()
                            val sampleElapsedNano = now - lastSampleNano
                            val activeElapsedNanos = accumulatedDownloadNanos + (now - streamStartNano)

                            if (sampleElapsedNano >= 150_000_000L) {
                                val currentMbps = SpeedTestCalculator.calculateMbps(bytesSinceLastSample, sampleElapsedNano)
                                peakDownloadMbps = max(peakDownloadMbps, currentMbps)

                                val totalActiveMs = activeElapsedNanos / 1_000_000
                                val progress = (totalActiveMs.toFloat() / downloadDurationMs).coerceIn(0f, 1f)

                                dispatchIfAlive(sessionId) {
                                    callback.onDownloadProgress(currentMbps, progress, totalBytesDownloaded)
                                }

                                lastSampleNano = now
                                bytesSinceLastSample = 0L

                                if (totalActiveMs >= downloadDurationMs) break
                            }
                        }
                        accumulatedDownloadNanos += max(1L, System.nanoTime() - streamStartNano)
                    }
                }
            } catch (_: Exception) {
                if (!isSessionAlive(sessionId, isActive)) return@withContext
                delay(200)
            } finally {
                if (activeCall === call) activeCall = null
            }
        }

        if (!isSessionAlive(sessionId, isActive)) return@withContext

        val effectiveDownloadNanos = if (totalBytesDownloaded > 0L) {
            max(100_000_000L, accumulatedDownloadNanos)
        } else 0L
        val finalDownloadMbps = if (totalBytesDownloaded > 0L) {
            SpeedTestCalculator.calculateMbps(totalBytesDownloaded, effectiveDownloadNanos)
        } else -1.0
        val finalPeakDownloadMbps = if (totalBytesDownloaded > 0L) {
            max(peakDownloadMbps, finalDownloadMbps)
        } else -1.0

        withContext(Dispatchers.Main) {
            if (activeSessionId == sessionId) {
                callback.onDownloadProgress(finalDownloadMbps, 1f, totalBytesDownloaded)
            }
        }

        delay(250)
        if (!isSessionAlive(sessionId, isActive)) return@withContext

        // 3. UPLOAD PHASE
        dispatchIfAlive(sessionId) { callback.onPhaseChanged(TestPhase.UPLOAD) }

        var confirmedBytesUploaded = 0L
        var accumulatedUploadNanos = 0L
        var confirmedPeakUploadMbps = 0.0
        val uploadDurationMs = 6000L
        val uploadPhaseDeadlineMs = 9000L
        val uploadWallStartNano = System.nanoTime()
        val uploadUrl = uploadEndpoints.first()
        val uploadPayload = ByteArray(64 * 1024) { 0x5A }

        while (isSessionAlive(sessionId, isActive) &&
            ((System.nanoTime() - uploadWallStartNano) / 1_000_000 < uploadPhaseDeadlineMs) &&
            (accumulatedUploadNanos / 1_000_000 < uploadDurationMs)
        ) {
            var attemptBytes = 0L
            var attemptDurationNanos = 0L
            var attemptPeakMbps = 0.0

            val customBody = object : RequestBody() {
                override fun contentType() = "application/octet-stream".toMediaType()
                override fun writeTo(sink: BufferedSink) {
                    var loopBytes = 0L
                    val writeStartNano = System.nanoTime()
                    var lastSampleNano = writeStartNano
                    var bytesSinceLastSample = 0L

                    while (isSessionAlive(sessionId, isActive) &&
                        ((accumulatedUploadNanos + (System.nanoTime() - writeStartNano)) / 1_000_000 < uploadDurationMs) &&
                        loopBytes < 4 * 1024 * 1024
                    ) {
                        sink.write(uploadPayload)
                        loopBytes += uploadPayload.size
                        attemptBytes += uploadPayload.size
                        bytesSinceLastSample += uploadPayload.size

                        // Flush regularly so bytes cannot sit un-transmitted inside Okio user-space buffers
                        if (loopBytes % (256 * 1024) == 0L) {
                            sink.flush()
                        }

                        val now = System.nanoTime()
                        val sampleElapsedNano = now - lastSampleNano
                        if (sampleElapsedNano >= 150_000_000L) {
                            sink.flush()
                            val postFlushNow = System.nanoTime()
                            val flushElapsedNano = postFlushNow - lastSampleNano
                            val currentMbps = SpeedTestCalculator.calculateMbps(bytesSinceLastSample, flushElapsedNano)
                            attemptPeakMbps = max(attemptPeakMbps, currentMbps)

                            val activeUploadMs = (accumulatedUploadNanos + (postFlushNow - writeStartNano)) / 1_000_000
                            val progress = (activeUploadMs.toFloat() / uploadDurationMs).coerceIn(0f, 1f)

                            val provisionalTotal = confirmedBytesUploaded + attemptBytes
                            dispatchIfAlive(sessionId) {
                                callback.onUploadProgress(currentMbps, progress, provisionalTotal)
                            }

                            lastSampleNano = postFlushNow
                            bytesSinceLastSample = 0L
                        }
                    }
                    sink.flush()
                    attemptDurationNanos = max(1L, System.nanoTime() - writeStartNano)
                }
            }

            val req = Request.Builder()
                .url(uploadUrl)
                .post(customBody)
                .build()
            val call = client.newCall(req)
            activeCall = call
            try {
                call.execute().use { response ->
                    // Commit uploaded bytes and duration strictly when the server confirms receipt with 2xx.
                    if (response.isSuccessful && isSessionAlive(sessionId, isActive)) {
                        confirmedBytesUploaded += attemptBytes
                        accumulatedUploadNanos += attemptDurationNanos
                        confirmedPeakUploadMbps = max(confirmedPeakUploadMbps, attemptPeakMbps)
                    } else if (!response.isSuccessful) {
                        delay(200)
                    }
                }
            } catch (_: Exception) {
                if (!isSessionAlive(sessionId, isActive)) return@withContext
                delay(200)
            } finally {
                if (activeCall === call) activeCall = null
            }
        }

        if (!isSessionAlive(sessionId, isActive)) return@withContext

        if (latencies.isEmpty() && totalBytesDownloaded == 0L && confirmedBytesUploaded == 0L) {
            withContext(Dispatchers.Main) {
                if (activeSessionId == sessionId) {
                    callback.onError("Unable to reach test servers. Check Internet connectivity and try again.")
                }
            }
            return@withContext
        }

        val effectiveUploadNanos = if (confirmedBytesUploaded > 0L) {
            max(100_000_000L, accumulatedUploadNanos)
        } else 0L
        val finalUploadMbps = if (confirmedBytesUploaded > 0L) {
            SpeedTestCalculator.calculateMbps(confirmedBytesUploaded, effectiveUploadNanos)
        } else -1.0
        val finalPeakUploadMbps = if (confirmedBytesUploaded > 0L) {
            max(confirmedPeakUploadMbps, finalUploadMbps)
        } else -1.0

        withContext(Dispatchers.Main) {
            if (activeSessionId == sessionId) {
                callback.onUploadProgress(finalUploadMbps, 1f, confirmedBytesUploaded)
            }
        }

        delay(200)
        if (!isSessionAlive(sessionId, isActive)) return@withContext

        // 4. COMPLETED
        val result = SpeedTestResult(
            downloadMbps = finalDownloadMbps,
            uploadMbps = finalUploadMbps,
            latencyMs = latencyAvg,
            jitterMs = jitterAvg,
            peakDownloadMbps = finalPeakDownloadMbps,
            peakUploadMbps = finalPeakUploadMbps,
            bytesDownloaded = totalBytesDownloaded,
            bytesUploaded = confirmedBytesUploaded,
            serverName = "Cloudflare Global Edge CDN",
            latencyEndpointUsed = latencyEndpointLabel,
            methodologyNote = "Native Single-Stream HTTP CDN Benchmark (Cloudflare Edge)"
        )

        withContext(Dispatchers.Main) {
            if (activeSessionId == sessionId) {
                callback.onPhaseChanged(TestPhase.COMPLETED)
                callback.onCompleted(result)
            }
        }
    }
}
