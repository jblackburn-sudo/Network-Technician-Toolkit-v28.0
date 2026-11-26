package com.example.networktoolkit

import android.app.Application
import android.content.Context
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Looper
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.example.networktoolkit.data.DiagnosticReportExporter
import com.example.networktoolkit.data.PingCsvExporter
import com.example.networktoolkit.data.PingResultEntity
import com.example.networktoolkit.data.PrivacyVerificationException
import com.example.networktoolkit.data.SensitiveWifiIdentifiers
import com.example.networktoolkit.data.TextReportExportManager
import com.example.networktoolkit.iperf3.IPerf3Activity
import com.example.networktoolkit.iperf3.IPerf3ViewModel
import com.example.networktoolkit.iperf3.IPerfMode
import com.example.networktoolkit.iperf3.IPerfUiState
import com.example.networktoolkit.wifiscan.WifiScannerEngine
import kotlinx.coroutines.runBlocking
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
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PreReleaseHardeningTest {

    @Test
    fun `ping csv exporter redacts ssids bssids and mac addresses across csv columns`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val secretSsid = "Client_HQ_WiFi"
        val secretBssid = "AA:11:22:33:44:BB"
        val extraMac = "DE:AD:BE:EF:CA:FE"

        val record = PingResultEntity(
            id = 42L,
            targetHost = "gateway.$secretSsid.internal",
            resolvedIp = "192.168.10.1",
            packetsTransmitted = 4,
            packetsReceived = 4,
            packetLossPercentage = 0.0,
            minRttMs = 2.1,
            avgRttMs = 3.0,
            maxRttMs = 4.2,
            mdevRttMs = 0.5,
            protocolMode = "ICMP",
            durationMs = 1200L,
            logOutput = "SSID: $secretSsid\nBSSID: $secretBssid\nPeer MAC $extraMac"
        )

        val ids = SensitiveWifiIdentifiers(
            ssids = setOf(secretSsid),
            bssids = setOf(secretBssid)
        )

        val csv = PingCsvExporter.generateCsv(listOf(record), ids)
        assertFalse(csv.contains(secretSsid, ignoreCase = true))
        assertFalse(csv.contains(secretBssid, ignoreCase = true))
        assertFalse(csv.contains(extraMac, ignoreCase = true))
        assertTrue(csv.contains(TextReportExportManager.REDACTED_SSID))
        assertTrue(csv.contains(TextReportExportManager.REDACTED_BSSID))

        clearFileProviderCache()
        var sharedFile: File? = null
        try {
            val shareResult = PingCsvExporter.createShareIntent(
                context = context,
                records = listOf(record),
                filename = "test_redacted_ping.csv",
                identifiers = ids
            )
            assertTrue(
                "Expected privacy-verified share intent to succeed: ${shareResult.exceptionOrNull()}",
                shareResult.isSuccess
            )
            val file = File(File(context.cacheDir, "reports"), "test_redacted_ping.csv")
            sharedFile = file
            assertTrue(file.exists())
            val written = file.readText(Charsets.UTF_8)
            assertFalse(written.contains(secretSsid))
            assertFalse(written.contains(secretBssid))
            assertFalse(written.contains(extraMac))
        } finally {
            sharedFile?.delete()
            clearFileProviderCache()
        }

        val uriOutFile = File(context.cacheDir, "direct_ping_export.csv")
        try {
            val uriResult = PingCsvExporter.writeCsvToUri(
                context = context,
                uri = Uri.fromFile(uriOutFile),
                records = listOf(record),
                identifiers = ids
            )
            assertTrue(uriResult.isSuccess)
            val writtenUriCsv = uriOutFile.readText(Charsets.UTF_8)
            assertFalse(writtenUriCsv.contains(secretSsid))
            assertFalse(writtenUriCsv.contains(secretBssid))
            assertFalse(writtenUriCsv.contains(extraMac))
        } finally {
            uriOutFile.delete()
        }
    }

    private fun clearFileProviderCache() {
        runCatching {
            val field = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache")
            field.isAccessible = true
            (field.get(null) as? MutableMap<*, *>)?.clear()
        }
    }

    @Test
    fun `diagnostic report exporter emits BuildConfig VERSION_NAME in json and text`() {
        val ping = PingResultEntity(
            id = 1L,
            targetHost = "1.1.1.1",
            resolvedIp = "1.1.1.1",
            packetsTransmitted = 4,
            packetsReceived = 4,
            packetLossPercentage = 0.0,
            minRttMs = 5.0,
            avgRttMs = 6.0,
            maxRttMs = 7.0,
            mdevRttMs = 0.4,
            protocolMode = "ICMP",
            durationMs = 1000L,
            logOutput = "ok"
        )

        val json = DiagnosticReportExporter.generateJsonReport(listOf(ping), emptyList())
        val root = JSONObject(json)
        assertEquals(BuildConfig.VERSION_NAME, root.getString("toolkitVersion"))

        val text = DiagnosticReportExporter.generateTextReport(listOf(ping), emptyList())
        assertTrue(text.contains("Toolkit Version : ${BuildConfig.VERSION_NAME}"))
        assertFalse(text.contains("Toolkit Version : 3.0"))
    }

    @Test
    fun `ping d3 latency chart view enforces hardened webview settings navigation block and ssl cancel`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val chartView = PingD3LatencyChartView(context)
        val webView = findFirstWebView(chartView)
        assertNotNull(webView)
        val wv = webView!!

        assertFalse(wv.settings.allowFileAccess)
        assertFalse(wv.settings.allowContentAccess)
        assertFalse(wv.settings.domStorageEnabled)
        assertEquals(WebSettings.MIXED_CONTENT_NEVER_ALLOW, wv.settings.mixedContentMode)

        val shadowWebView = Shadows.shadowOf(wv)
        val loaded = shadowWebView.lastLoadDataWithBaseURL
        assertNotNull(loaded)
        assertEquals("https://appassets.androidplatform.net/", loaded!!.baseUrl)

        val client = shadowWebView.webViewClient
        assertTrue(
            "Ping chart WebView must block all navigation",
            client.shouldOverrideUrlLoading(wv, null as WebResourceRequest?)
        )
        @Suppress("DEPRECATION")
        assertTrue(
            "Ping chart WebView must block legacy navigation",
            client.shouldOverrideUrlLoading(wv, "https://evil.example/")
        )

        val sslOverride = client.javaClass.getMethod(
            "onReceivedSslError",
            WebView::class.java,
            SslErrorHandler::class.java,
            SslError::class.java
        )
        assertEquals(client.javaClass, sslOverride.declaringClass)
        val handler = ReflectionHelpers.callConstructor(SslErrorHandler::class.java)
        client.onReceivedSslError(wv, handler, null)
        client.onReceivedSslError(wv, null, null)

        chartView.destroyChart()
        chartView.destroyChart()
    }

    @Test
    fun `webtest activity cancels untrusted ssl certificates`() {
        val controller = Robolectric.buildActivity(WebTestActivity::class.java).create().start().resume()
        try {
            val activity = controller.get()
            val webView = findFirstWebView(activity.window.decorView)
            assertNotNull(webView)
            val wv = webView!!
            val client = Shadows.shadowOf(wv).webViewClient

            val sslOverride = client.javaClass.getMethod(
                "onReceivedSslError",
                WebView::class.java,
                SslErrorHandler::class.java,
                SslError::class.java
            )
            assertEquals(client.javaClass, sslOverride.declaringClass)

            val handler = ReflectionHelpers.callConstructor(SslErrorHandler::class.java)
            client.onReceivedSslError(wv, handler, null as SslError?)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(
                "Blocked untrusted SSL/TLS certificate",
                org.robolectric.shadows.ShadowToast.getTextOfLatestToast()
            )
        } finally {
            controller.pause().stop().destroy()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun `dns lookup engine rejects shell metacharacters cli flags and malformed targets`() = runBlocking {
        val maliciousInputs = listOf(
            "",
            "   ",
            "8.8.8.8;id",
            "google.com && whoami",
            "-v",
            "999.999.999.999",
            "bad host name",
            "evil.com\nrm -rf /"
        )
        for (input in maliciousInputs) {
            val result = DnsLookupEngine.lookup(target = input, queryType = "A")
            assertTrue("Expected failure for '$input'", result.isFailure)
            val err = result.exceptionOrNull()
            assertTrue("Expected DnsLookupException for '$input'", err is DnsLookupException)
            assertEquals(
                DnsFailureCategory.INVALID_INPUT,
                (err as DnsLookupException).details.category
            )
        }
    }

    @Test
    fun `iperf3 activity stops running local server when stopped without configuration change`() {
        val freePort = java.net.ServerSocket(0).use { it.localPort }
        val controller = Robolectric.buildActivity(IPerf3Activity::class.java).create().start().resume()
        try {
            val activity = controller.get()
            val vm = ViewModelProvider(activity)[IPerf3ViewModel::class.java]

            vm.setMode(IPerfMode.SERVER)
            vm.onPortInputChanged(freePort.toString())
            vm.startTest()
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            Thread.sleep(150)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertTrue(
                "Server should enter Running state after startTest",
                vm.uiState.value is IPerfUiState.Running
            )

            controller.pause().stop()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(100)
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            assertTrue(
                "Leaving foreground must stop running iPerf3 server",
                vm.uiState.value is IPerfUiState.Idle
            )
            assertNotNull(
                "Leaving foreground should surface stop notice",
                vm.serverNoticeMessage.value
            )

            java.net.ServerSocket(freePort).use { rebound ->
                assertEquals(freePort, rebound.localPort)
            }
        } finally {
            controller.pause().stop().destroy()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun `connectivity monitor reports unresponsive gateway instead of fabricated 2ms rtt`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val monitor = NetworkConnectivityMonitor.getInstance(context)
        val method = NetworkConnectivityMonitor::class.java.getDeclaredMethod(
            "testGatewayResponsiveness",
            String::class.java,
            Boolean::class.javaPrimitiveType
        ).apply { isAccessible = true }

        @Suppress("UNCHECKED_CAST")
        val result = method.invoke(monitor, "192.0.2.1", false) as Pair<GatewayStatus, Long?>
        assertEquals(GatewayStatus.UNRESPONSIVE, result.first)
        assertNull("Unreachable gateway must report null RTT instead of fake 2ms", result.second)
    }

    @Test
    fun `wifi scanner does not fabricate demo aps on non-robolectric android 14 device when scan results empty`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val origFingerprint = Build.FINGERPRINT
        val origHardware = Build.HARDWARE
        val origModel = Build.MODEL
        val origManufacturer = Build.MANUFACTURER
        val origProduct = Build.PRODUCT
        val origBrand = Build.BRAND
        val origDevice = Build.DEVICE
        try {
            ReflectionHelpers.setStaticField(Build::class.java, "FINGERPRINT", "google/panther/panther:14/AP2A.240605.024/11860263:user/release-keys")
            ReflectionHelpers.setStaticField(Build::class.java, "HARDWARE", "panther")
            ReflectionHelpers.setStaticField(Build::class.java, "MODEL", "Pixel 7")
            ReflectionHelpers.setStaticField(Build::class.java, "MANUFACTURER", "Google")
            ReflectionHelpers.setStaticField(Build::class.java, "PRODUCT", "panther")
            ReflectionHelpers.setStaticField(Build::class.java, "BRAND", "google")
            ReflectionHelpers.setStaticField(Build::class.java, "DEVICE", "panther")

            val engine = WifiScannerEngine(context)
            engine.startScan()
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            assertTrue(
                "Physical Android 14 device must not fabricate fake demo networks",
                engine.state.value.networks.none { it.ssid == "Stellar_Corporate_5G" }
            )
        } finally {
            ReflectionHelpers.setStaticField(Build::class.java, "FINGERPRINT", origFingerprint)
            ReflectionHelpers.setStaticField(Build::class.java, "HARDWARE", origHardware)
            ReflectionHelpers.setStaticField(Build::class.java, "MODEL", origModel)
            ReflectionHelpers.setStaticField(Build::class.java, "MANUFACTURER", origManufacturer)
            ReflectionHelpers.setStaticField(Build::class.java, "PRODUCT", origProduct)
            ReflectionHelpers.setStaticField(Build::class.java, "BRAND", origBrand)
            ReflectionHelpers.setStaticField(Build::class.java, "DEVICE", origDevice)
        }
    }

    @Test
    fun `startup temp cleanup removes expired share and orphan tmp files without touching persistent data`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val reportsDir = File(context.cacheDir, "reports").apply { mkdirs() }
        val staleShare = File(reportsDir, "old_report.rtf").apply {
            writeText("stale")
            setLastModified(System.currentTimeMillis() - 2L * 60L * 60L * 1000L)
        }
        val freshShare = File(reportsDir, "fresh_report.rtf").apply {
            writeText("fresh")
            setLastModified(System.currentTimeMillis())
        }

        val stagingDir = File(context.filesDir, "staged_txt_exports").apply { mkdirs() }
        val orphanTmp = File(stagingDir, "staged_orphan.rtf.tmp").apply {
            writeText("partial")
            setLastModified(System.currentTimeMillis() - 2L * 60L * 60L * 1000L)
        }

        TextReportExportManager.cleanupStartupTempFiles(context)

        assertFalse("Stale share file should be deleted", staleShare.exists())
        assertTrue("Fresh share file should remain", freshShare.exists())
        assertFalse("Orphan .tmp file older than 1h should be deleted", orphanTmp.exists())

        freshShare.delete()
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
}
