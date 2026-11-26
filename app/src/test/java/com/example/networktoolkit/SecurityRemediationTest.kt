package com.example.networktoolkit

import android.app.Application
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Looper
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.example.networktoolkit.data.DiagnosticReportExporter
import com.example.networktoolkit.data.PingResultEntity
import com.example.networktoolkit.data.PrivacyVerificationException
import com.example.networktoolkit.data.SensitiveWifiIdentifiers
import com.example.networktoolkit.data.TextReportExportManager
import com.example.networktoolkit.diagnostics.IpAddressValidator
import com.example.networktoolkit.iperf3.IPerf3Engine
import com.example.networktoolkit.iperf3.IPerfServerIdleTimeoutException
import com.example.networktoolkit.signalmap.FloorPlanTemplates
import com.example.networktoolkit.signalmap.LiveConnectedTelemetry
import com.example.networktoolkit.signalmap.PropertyType
import com.example.networktoolkit.signalmap.SignalMapEngine
import com.example.networktoolkit.signalmap.SignalSample
import com.example.networktoolkit.speedtest.SpeedTestServicePreferences
import com.example.networktoolkit.wifiscan.WifiScannerViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SecurityRemediationTest {

    // =====================================================================
    // 1. JSON PRIVACY REDACTION TESTS
    // =====================================================================

    @Test
    fun `json privacy redaction sanitizes wifi scanner and signal map json including nested fields`() {
        val secretSsid = "Corp_Private_5G"
        val secretBssid = "AA:BB:CC:11:22:33"
        val altMac = "44-55-66-77-88-99"

        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = SignalMapEngine(context)
        val rooms = FloorPlanTemplates.getRoomsForProperty(PropertyType.OFFICE)
        val samples = listOf(
            SignalSample(
                roomName = rooms.first().name,
                relX = 0.5f,
                relY = 0.5f,
                rssiDbm = -52,
                snrDb = 39,
                noiseFloorDbm = -91,
                linkSpeedMbps = 720,
                frequencyMhz = 5220,
                channel = 44,
                bssid = secretBssid,
                ssid = secretSsid,
                notes = "Measured near AP $altMac on $secretSsid"
            )
        )
        val telemetry = LiveConnectedTelemetry(
            isConnected = true,
            ssid = secretSsid,
            bssid = secretBssid,
            currentRssiDbm = -52,
            snrDb = 39,
            noiseFloorDbm = -91,
            linkSpeedMbps = 720,
            frequencyMhz = 5220,
            channel = 44,
            isWifiEnabled = true
        )
        val analysis = engine.calculateCoverageAnalysis(PropertyType.OFFICE, rooms, samples)
        val rawSignalMapJson = engine.generateJsonReport(
            propertyType = PropertyType.OFFICE,
            rooms = rooms,
            samples = samples,
            analysis = analysis,
            telemetry = telemetry
        )

        val identifiers = SensitiveWifiIdentifiers(
            ssids = setOf(secretSsid),
            bssids = setOf(secretBssid, altMac)
        )
        val sanitizedJson = TextReportExportManager
            .sanitizeAndVerifyJsonReport(rawSignalMapJson, identifiers)
            .getOrThrow()

        assertFalse(sanitizedJson.contains(secretSsid, ignoreCase = true))
        assertFalse(sanitizedJson.contains(secretBssid, ignoreCase = true))
        assertFalse(sanitizedJson.contains(altMac, ignoreCase = true))

        val parsed = JSONObject(sanitizedJson)
        assertEquals(TextReportExportManager.PRIVACY_NOTICE_MESSAGE, parsed.getString("privacyNotice"))
        assertEquals(TextReportExportManager.REDACTED_SSID, parsed.getString("ssid"))
        assertEquals(TextReportExportManager.REDACTED_BSSID, parsed.getString("bssid"))
        assertEquals(-52, parsed.getJSONArray("samples").getJSONObject(0).getInt("rssiDbm"))
        val sampleNotes = parsed.getJSONArray("samples").getJSONObject(0).getString("notes")
        assertFalse(sampleNotes.contains(secretSsid))
        assertFalse(sampleNotes.contains(altMac))
        assertTrue(sampleNotes.contains(TextReportExportManager.REDACTED_BSSID))
    }

    // =====================================================================
    // 2. SSID / BSSID / MAC REDACTION VERIFICATION TESTS
    // =====================================================================

    @Test
    fun `verifyPrivacyRedaction rejects leaked ssid, bssid, colon mac, dash mac, and cisco dot mac`() {
        val ids = SensitiveWifiIdentifiers(
            ssids = setOf("SecretLabWiFi"),
            bssids = setOf("10:20:30:40:50:60")
        )

        val leakedSsid = """{"ssid": "SecretLabWiFi", "bssid": "Hidden MAC"}"""
        val leakedBssid = """{"ssid": "Hidden Network", "bssid": "10:20:30:40:50:60"}"""
        val leakedCiscoMac = """{"notes": "Switch port MAC aabb.cc11.2233"}"""
        val cleanJson = """{"ssid": "Hidden Network", "bssid": "Hidden MAC"}"""

        assertThrowsPrivacyException("SSID") {
            TextReportExportManager.verifyPrivacyRedaction(leakedSsid, ids)
        }
        assertThrowsPrivacyException("BSSID") {
            TextReportExportManager.verifyPrivacyRedaction(leakedBssid, ids)
        }
        assertThrowsPrivacyException("MAC") {
            TextReportExportManager.verifyPrivacyRedaction(leakedCiscoMac, ids)
        }

        // Clean output must verify without throwing
        TextReportExportManager.verifyPrivacyRedaction(cleanJson, ids)
    }

    // =====================================================================
    // 3. REDACTION BYPASS PREVENTION TESTS
    // =====================================================================

    @Test
    fun `createRedactedShareIntent enforces json redaction on shared file and intent extra and blocks bypass`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val secretSsid = "ExecBoardroom_6G"
        val secretBssid = "DE:AD:BE:EF:12:34"
        val rawWifiJson = JSONObject().apply {
            put("reportTitle", "Wi-Fi Site Survey")
            put("connectedSsid", secretSsid)
            put("connectedBssid", secretBssid)
            put("accessPoints", JSONArray().apply {
                put(JSONObject().apply {
                    put("ssid", secretSsid)
                    put("displaySsid", secretSsid)
                    put("bssid", secretBssid)
                    put("signalDbm", -44)
                })
            })
        }.toString(2)

        val ids = SensitiveWifiIdentifiers(
            ssids = setOf(secretSsid),
            bssids = setOf(secretBssid)
        )

        val intentResult = TextReportExportManager.createRedactedShareIntent(
            context = context,
            rawContent = rawWifiJson,
            filename = "WiFi_Test_Report.json",
            subject = "Wi-Fi JSON",
            chooserTitle = "Share",
            identifiers = ids,
            isJson = true
        )
        assertTrue(intentResult.isSuccess)
        val chooserIntent = intentResult.getOrThrow()
        @Suppress("DEPRECATION")
        val targetIntent = chooserIntent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertNotNull(targetIntent)
        val extraText = targetIntent?.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        assertFalse(extraText.contains(secretSsid))
        assertFalse(extraText.contains(secretBssid))
        assertTrue(extraText.contains(TextReportExportManager.REDACTED_SSID))

        val sharedFile = File(File(context.cacheDir, "reports"), "WiFi_Test_Report.json")
        assertTrue(sharedFile.exists())
        val diskText = sharedFile.readText(Charsets.UTF_8)
        assertFalse(diskText.contains(secretSsid))
        assertFalse(diskText.contains(secretBssid))
        assertTrue(diskText.contains(TextReportExportManager.PRIVACY_NOTICE_MESSAGE))

        // Post-redaction transform leak must trigger PrivacyVerificationException
        val bypassAttempt = TextReportExportManager.sanitizeAndVerifyJsonReport(
            rawJson = rawWifiJson,
            identifiers = ids,
            postRedactionTransform = { json -> json.replace("Hidden Network", secretSsid) }
        )
        assertTrue(bypassAttempt.isFailure)
        assertTrue(bypassAttempt.exceptionOrNull() is PrivacyVerificationException)
    }

    // =====================================================================
    // 4. CLIPBOARD PRIVACY TESTS
    // =====================================================================

    @Test
    fun `clipboard copies redact sensitive wifi identifiers and mark EXTRA_IS_SENSITIVE on Android 13+`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val secretSsid = "CampusCore_AP"
        val secretBssid = "AA:11:BB:22:CC:33"
        val ids = SensitiveWifiIdentifiers(ssids = setOf(secretSsid), bssids = setOf(secretBssid))

        val rawJson = JSONObject().apply {
            put("connectedSsid", secretSsid)
            put("connectedBssid", secretBssid)
        }.toString(2)

        val res = TextReportExportManager.copyRedactedToClipboard(
            context = context,
            label = "Wi-Fi Scan (JSON)",
            rawContent = rawJson,
            identifiers = ids,
            isJson = true,
            markSensitive = true
        )
        assertTrue(res.isSuccess)
        val clip = cm.primaryClip
        assertNotNull(clip)
        val copiedText = clip!!.getItemAt(0).text.toString()
        assertFalse(copiedText.contains(secretSsid))
        assertFalse(copiedText.contains(secretBssid))
        assertTrue(copiedText.contains(TextReportExportManager.REDACTED_SSID))
        assertTrue(
            "Clipboard must include EXTRA_IS_SENSITIVE on API 33+",
            clip.description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) == true
        )

        // DiagnosticReportExporter clipboard redaction check (including logOutput MAC)
        val pingEntity = PingResultEntity(
            targetHost = "192.168.1.1",
            resolvedIp = "192.168.1.1",
            packetsTransmitted = 1,
            packetsReceived = 1,
            packetLossPercentage = 0.0,
            minRttMs = 2.0,
            avgRttMs = 2.0,
            maxRttMs = 2.0,
            mdevRttMs = 0.0,
            protocolMode = "ICMP",
            durationMs = 100L,
            logOutput = "Gateway MAC $secretBssid responded"
        )
        val diagJson = DiagnosticReportExporter.generateSinglePingJson(pingEntity)
        DiagnosticReportExporter.copyToClipboard(context, diagJson, "Diagnostic JSON", isJson = true)
        val diagClip = cm.primaryClip!!
        val diagCopied = diagClip.getItemAt(0).text.toString()
        assertFalse(diagCopied.contains(secretBssid))
        assertTrue(diagCopied.contains(TextReportExportManager.REDACTED_BSSID))
        assertTrue(diagClip.description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) == true)

        // WifiScannerViewModel clipboard path smoke test with proper lifecycle cleanup
        val vmStore = ViewModelStore()
        try {
            val vm = ViewModelProvider(
                vmStore,
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        return WifiScannerViewModel(context.applicationContext as Application) as T
                    }
                }
            )[WifiScannerViewModel::class.java]
            vm.copyReportToClipboard(context, isJson = true)
            assertNotNull(cm.primaryClip)
        } finally {
            vmStore.clear()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
    }

    // =====================================================================
    // 5. ANDROID BACKUP CONFIGURATION VERIFICATION
    // =====================================================================

    @Test
    fun `manifest disables android allowBackup and exclusion rules protect databases and prefs`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val allowBackupEnabled = (context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP) != 0
        assertFalse("android:allowBackup must be false", allowBackupEnabled)

        val backupXml = readXmlTags(context, R.xml.backup_rules)
        assertTrue(backupXml.contains("exclude:database:network_toolkit_database"))
        assertTrue(backupXml.contains("exclude:sharedpref:SignalMapSurveyStore.xml"))

        val extractionXml = readXmlTags(context, R.xml.data_extraction_rules)
        assertTrue(extractionXml.contains("exclude:database:network_toolkit_database"))
        assertTrue(extractionXml.contains("exclude:sharedpref:SignalMapSurveyStore.xml"))

        val filePathsXml = readXmlTags(context, R.xml.file_paths)
        assertTrue(filePathsXml.contains("cache-path:reports:reports/"))
        assertFalse(filePathsXml.contains("cache_root"))
    }

    // =====================================================================
    // 6. WEBVIEW URL SCHEME VALIDATION TESTS
    // =====================================================================

    @Test
    fun `webview navigation allowlist blocks unsafe schemes and external http while allowing https and local http`() {
        val blockedAlways = listOf(
            null,
            "",
            "   ",
            "javascript:alert(1)",
            "JAVASCRIPT:document.cookie",
            "file:///etc/passwd",
            "content://com.android.contacts/contacts",
            "intent://scan/#Intent;end",
            "data:text/html,<script>alert(1)</script>",
            "blob:https://example.com/123",
            "about:blank",
            "http://example.com/speedtest",
            "http://8.8.8.8/",
            "https://user:pass@www.speedtest.net/"
        )
        for (url in blockedAlways) {
            assertFalse("Expected blocked: $url", WebTestActivity.shouldAllowNavigation(url, true))
            assertFalse("Expected blocked: $url", WebTestActivity.shouldAllowNavigation(url, false))
        }

        val allowedHttps = listOf(
            "https://www.speedtest.net",
            "https://speed.cloudflare.com/test",
            "https://fast.com"
        )
        for (url in allowedHttps) {
            assertTrue("Expected allowed HTTPS: $url", WebTestActivity.shouldAllowNavigation(url, false))
            assertTrue("Expected allowed HTTPS: $url", WebTestActivity.shouldAllowNavigation(url, true))
        }

        // Local/private HTTP allowed ONLY when allowLocalHttp == true
        val localHttp = listOf(
            "http://192.168.1.1/status",
            "http://10.0.0.1:8080",
            "http://localhost:3000",
            "http://router.local"
        )
        for (url in localHttp) {
            assertTrue("Expected allowed local HTTP when opted-in: $url", WebTestActivity.shouldAllowNavigation(url, true))
            assertFalse("Expected blocked local HTTP during HTTPS session: $url", WebTestActivity.shouldAllowNavigation(url, false))
        }
    }

    // =====================================================================
    // 7. MIXED-CONTENT & WEBVIEW HARDENING VERIFICATION
    // =====================================================================

    @Test
    fun `WebTestActivity configures MIXED_CONTENT_NEVER_ALLOW, disables file and content access, and rejects unsafe intent url`() {
        val launchIntent = Intent(
            ApplicationProvider.getApplicationContext(),
            WebTestActivity::class.java
        ).apply {
            putExtra("target_url", "javascript:alert('xss')")
            putExtra("target_title", "Malicious Test")
        }

        val controller = Robolectric.buildActivity(WebTestActivity::class.java, launchIntent).setup()
        try {
            val activity = controller.get()

            val webView = findFirstWebView(activity.window.decorView)
            assertNotNull("WebView should exist in WebTestActivity", webView)
            val settings = webView!!.settings
            assertEquals(WebSettings.MIXED_CONTENT_NEVER_ALLOW, settings.mixedContentMode)
            assertFalse(settings.allowFileAccess)
            assertFalse(settings.allowContentAccess)

            val shadowWebView = Shadows.shadowOf(webView)
            val loadedUrl = shadowWebView.lastLoadedUrl
            assertNotNull(loadedUrl)
            assertFalse("Unsafe javascript URL must never be loaded", loadedUrl!!.startsWith("javascript:"))
            assertTrue("Fallback URL must be HTTPS", loadedUrl.startsWith("https://"))

            val client = shadowWebView.webViewClient
            assertNotNull(client)
            @Suppress("DEPRECATION")
            assertTrue(client.shouldOverrideUrlLoading(webView, "file:///etc/hosts"))
            @Suppress("DEPRECATION")
            assertTrue(client.shouldOverrideUrlLoading(webView, "http://untrusted.example.com"))
            @Suppress("DEPRECATION")
            assertFalse(client.shouldOverrideUrlLoading(webView, "https://speed.cloudflare.com"))
        } finally {
            controller.pause().stop().destroy()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
    }

    // =====================================================================
    // 8. CUSTOM SPEED-TEST URL VALIDATION TESTS
    // =====================================================================

    @Test
    fun `custom speed test normalizeServiceUrl rejects unsafe schemes and external http while normalizing https`() {
        assertNull(SpeedTestServicePreferences.normalizeServiceUrl(""))
        assertNull(SpeedTestServicePreferences.normalizeServiceUrl("javascript:alert(1)"))
        assertNull(SpeedTestServicePreferences.normalizeServiceUrl("file:///sdcard/test.html"))
        assertNull(SpeedTestServicePreferences.normalizeServiceUrl("intent://evil"))
        assertNull(SpeedTestServicePreferences.normalizeServiceUrl("http://public-speedtest.example.com"))
        assertNull(SpeedTestServicePreferences.normalizeServiceUrl("https://evil host.com"))
        assertNull(SpeedTestServicePreferences.normalizeServiceUrl("https://user:pass@example.com"))

        assertEquals(
            "https://speed.cloudflare.com",
            SpeedTestServicePreferences.normalizeServiceUrl("speed.cloudflare.com")
        )
        assertEquals(
            "https://librespeed.org/backend",
            SpeedTestServicePreferences.normalizeServiceUrl("https://librespeed.org/backend")
        )
        assertEquals(
            "http://192.168.1.1:8080",
            SpeedTestServicePreferences.normalizeServiceUrl("http://192.168.1.1:8080")
        )
    }

    // =====================================================================
    // 9. PROCESSBUILDER TARGET VALIDATION TESTS
    // =====================================================================

    @Test
    fun `diagnostic target sanitizer and engines block cli flags and shell metacharacters while permitting valid hosts`() {
        val invalidTargets = listOf(
            null,
            "",
            "   ",
            "-c",
            "-f",
            "-c 1 8.8.8.8",
            "8.8.8.8; rm -rf /",
            "8.8.8.8 && id",
            "8.8.8.8 | cat /etc/passwd",
            "`whoami`",
            "$(id)",
            "https://8.8.8.8",
            "192.168.1.1/24",
            "999.999.999.999",
            "host with spaces"
        )
        for (bad in invalidTargets) {
            assertNull("Expected rejected target: $bad", IpAddressValidator.sanitizeDiagnosticTarget(bad))
        }

        assertEquals("8.8.8.8", IpAddressValidator.sanitizeDiagnosticTarget(" 8.8.8.8 "))
        assertEquals("192.168.1.1", IpAddressValidator.sanitizeDiagnosticTarget("192.168.1.1"))
        assertEquals("2001:4860:4860::8888", IpAddressValidator.sanitizeDiagnosticTarget("[2001:4860:4860::8888]"))
        assertEquals("dns.google", IpAddressValidator.sanitizeDiagnosticTarget("dns.google."))
        assertEquals("router.local", IpAddressValidator.sanitizeDiagnosticTarget("router.local"))

        // Verify CoroutinePingEngine rejects CLI injection immediately
        val pingLatch = CountDownLatch(1)
        val pingErrorRef = AtomicReference<String?>()
        CoroutinePingEngine().startPing(
            target = "-f",
            count = 1,
            timeoutSec = 1,
            mode = CoroutinePingEngine.PingMode.ICMP,
            callback = object : CoroutinePingEngine.PingCallback {
                override fun onStarted(target: String, count: Int, mode: CoroutinePingEngine.PingMode) {}
                override fun onDnsResolved(hostname: String, ip: String, dnsTimeMs: Long) {}
                override fun onPacketReceived(packet: CoroutinePingEngine.PingPacket) {}
                override fun onProgress(current: Int, total: Int) {}
                override fun onSummary(summary: CoroutinePingEngine.PingSummary) {}
                override fun onError(message: String) {
                    pingErrorRef.set(message)
                    pingLatch.countDown()
                }
                override fun onCompleted(cancelled: Boolean) {}
            }
        )
        assertTrue(pingLatch.await(2, TimeUnit.SECONDS))
        assertTrue(pingErrorRef.get().orEmpty().contains("Invalid target"))

        // Verify TracerouteEngine rejects shell injection immediately
        val traceLatch = CountDownLatch(1)
        val traceErrorRef = AtomicReference<String?>()
        TracerouteEngine().startTraceroute(
            target = "8.8.8.8;id",
            maxHops = 3,
            timeoutSec = 1,
            callback = object : TracerouteEngine.Callback {
                override fun onStarted(target: String, targetIp: String, maxHops: Int) {}
                override fun onHopReceived(hop: TracerouteHop) {}
                override fun onProgress(currentHop: Int, maxHops: Int) {}
                override fun onCompleted(summary: TracerouteSummary) {}
                override fun onError(message: String) {
                    traceErrorRef.set(message)
                    traceLatch.countDown()
                }
            }
        )
        assertTrue(traceLatch.await(2, TimeUnit.SECONDS))
        assertTrue(traceErrorRef.get().orEmpty().contains("Invalid target"))
    }

    // =====================================================================
    // 10. IPERF3 SERVER LIFECYCLE & IDLE TIMEOUT TESTS
    // =====================================================================

    @Test
    fun `iperf3 server enforces idle timeout and cleans up socket reliably`() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val engine = IPerf3Engine()

        val result = engine.runServer(
            port = port,
            idleTimeoutMs = 200L,
            onClientConnected = {},
            onInterval = {}
        )

        assertTrue("Idle server should return timeout failure", result.isFailure)
        assertTrue(result.exceptionOrNull() is IPerfServerIdleTimeoutException)
        assertFalse("Server must no longer report running after timeout", engine.isRunning())

        // Port must be completely released immediately after timeout
        ServerSocket(port).use { rebound ->
            assertEquals(port, rebound.localPort)
        }
    }

    @Test
    fun `iperf3 server stops cleanly via stop() and releases listening port`() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val engine = IPerf3Engine()

        val job = async {
            engine.runServer(
                port = port,
                idleTimeoutMs = 30_000L,
                onClientConnected = {},
                onInterval = {}
            )
        }

        delay(100L)
        assertTrue(engine.isRunning())
        engine.stop()
        val res = job.await()
        assertTrue(res.isFailure)
        assertFalse(engine.isRunning())

        ServerSocket(port).use { rebound ->
            assertEquals(port, rebound.localPort)
        }
    }

    private fun assertThrowsPrivacyException(expectedField: String, block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected PrivacyVerificationException for $expectedField")
        } catch (e: PrivacyVerificationException) {
            assertEquals(expectedField, e.leakedFieldType)
        }
    }

    private fun findFirstWebView(view: android.view.View?): WebView? {
        if (view == null) return null
        if (view is WebView) return view
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findFirstWebView(view.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }

    private fun readXmlTags(context: Context, xmlResId: Int): String {
        val parser = context.resources.getXml(xmlResId)
        val entries = mutableListOf<String>()
        var event = parser.eventType
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (event == org.xmlpull.v1.XmlPullParser.START_TAG) {
                val attrs = (0 until parser.attributeCount).associate { i ->
                    parser.getAttributeName(i) to (parser.getAttributeValue(i) ?: "")
                }
                val parts = listOfNotNull(
                    parser.name,
                    attrs["domain"]?.takeIf { it.isNotEmpty() },
                    attrs["name"]?.takeIf { it.isNotEmpty() },
                    attrs["path"]?.takeIf { it.isNotEmpty() }
                )
                entries.add(parts.joinToString(":"))
            }
            event = parser.next()
        }
        parser.close()
        return entries.joinToString("\n")
    }
}
