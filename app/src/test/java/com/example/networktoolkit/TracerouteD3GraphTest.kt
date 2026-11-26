package com.example.networktoolkit

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TracerouteD3GraphTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `graph data builder serializes google com 11 hop trace with 64_60 ms avg rtt and destination reached`() {
        val rtts = listOf(2.4, 11.2, 18.5, 29.0, 41.3, 55.8, 72.1, 88.4, 112.0, 134.2, 145.7)
        val avgRtt = rtts.average()
        assertEquals(64.60, avgRtt, 0.01)

        val hops = rtts.mapIndexed { idx, rtt ->
            val hopNum = idx + 1
            TracerouteHop(
                hopNumber = hopNum,
                ip = when (hopNum) {
                    1 -> "192.168.1.1"
                    2 -> "100.64.10.1"
                    11 -> "142.250.190.46"
                    else -> "74.125.$hopNum.1"
                },
                hostname = if (hopNum == 11) "iad23s96-in-f14.1e100.net" else null,
                rttMs = rtt,
                isDestinationReached = hopNum == 11,
                statusText = if (hopNum == 11) "Destination Reached" else "Intermediate Hop"
            )
        }

        val json: JSONArray = TracerouteGraphDataBuilder.buildHopsJson(hops)
        assertEquals(11, json.length())

        val h1 = json.getJSONObject(0)
        assertEquals("Private", h1.getString("ipScope"))
        assertEquals("", h1.getString("hostname"))

        val h2 = json.getJSONObject(1)
        assertEquals("CGNAT", h2.getString("ipScope"))

        val h11 = json.getJSONObject(10)
        assertEquals("Public", h11.getString("ipScope"))
        assertTrue(h11.getBoolean("isDestinationReached"))
        assertEquals("iad23s96-in-f14.1e100.net", h11.getString("hostname"))
        assertEquals(145.7, h11.getDouble("rttMs"), 0.01)
    }

    @Test
    fun `graph data builder handles empty, timeout, unknown, missing rdns, ecmp alternate ips, and all ip scopes`() {
        val emptyJson = TracerouteGraphDataBuilder.buildHopsJson(emptyList())
        assertEquals(0, emptyJson.length())

        val hops = listOf(
            TracerouteHop(hopNumber = 1, ip = "192.168.1.1", hostname = " ", rttMs = 2.4, statusText = "Intermediate Hop"),
            TracerouteHop(hopNumber = 2, ip = "100.64.12.1", hostname = "cgnat-gw.isp.net", rttMs = 14.8, statusText = "Intermediate Hop", alternateIps = listOf("100.64.12.2", "100.64.12.1", "")),
            TracerouteHop(hopNumber = 3, ip = "*", hostname = null, rttMs = null, isTimedOut = true, statusText = "Request Timed Out"),
            TracerouteHop(hopNumber = 4, ip = "Unknown Hop", hostname = null, rttMs = 120.5, statusText = "Intermediate Hop"),
            TracerouteHop(hopNumber = 5, ip = "127.0.0.1", hostname = "localhost", rttMs = 0.4, statusText = "Loopback"),
            TracerouteHop(hopNumber = 6, ip = "169.254.1.5", hostname = "169.254.1.5", rttMs = 1.2, statusText = "Link-Local"),
            TracerouteHop(hopNumber = 7, ip = "8.8.8.8", hostname = "dns.google", rttMs = 64.6, isDestinationReached = true, statusText = "Destination Reached")
        )

        val json = TracerouteGraphDataBuilder.buildHopsJson(hops)
        assertEquals(7, json.length())

        assertEquals("Private", json.getJSONObject(0).getString("ipScope"))
        assertEquals("", json.getJSONObject(0).getString("hostname"))

        val cgnat = json.getJSONObject(1)
        assertEquals("CGNAT", cgnat.getString("ipScope"))
        assertEquals(1, cgnat.getJSONArray("alternateIps").length())
        assertEquals("100.64.12.2", cgnat.getJSONArray("alternateIps").getString(0))

        val timeout = json.getJSONObject(2)
        assertTrue(timeout.getBoolean("isTimedOut"))
        assertEquals("Timeout", timeout.getString("ipScope"))
        assertEquals(-1.0, timeout.getDouble("rttMs"), 0.01)

        assertEquals("Unknown", json.getJSONObject(3).getString("ipScope"))
        assertEquals("Loopback", json.getJSONObject(4).getString("ipScope"))
        assertEquals("Link-Local", json.getJSONObject(5).getString("ipScope"))
        // Identical hostname to IP should be normalized to empty
        assertEquals("", json.getJSONObject(5).getString("hostname"))
        assertEquals("Public", json.getJSONObject(6).getString("ipScope"))
        assertTrue(json.getJSONObject(6).getBoolean("isDestinationReached"))
    }

    @Test
    fun `update script safely encodes empty, incomplete, unreached, and special character targets`() {
        val emptyJson = TracerouteGraphDataBuilder.buildHopsJson(emptyList()).toString()
        val unreachedScript = TracerouteGraphDataBuilder.buildUpdateScript(
            hopsJson = emptyJson,
            target = "host'with\"quotes/slash",
            isCompleted = true,
            destinationReached = false
        )
        assertTrue(unreachedScript.contains("window.updateGraphData"))
        assertTrue(unreachedScript.contains("true, false"))
        assertTrue(
            unreachedScript.contains("\"host'with\\\"quotes\\/slash\"") ||
                unreachedScript.contains("\"host'with\\\"quotes/slash\"")
        )

        val incompleteScript = TracerouteGraphDataBuilder.buildUpdateScript(
            hopsJson = emptyJson,
            target = "google.com",
            isCompleted = false,
            destinationReached = false
        )
        assertTrue(incompleteScript.contains("\"google.com\", false, false"))
    }

    @Test
    fun `offline self-contained html has zero external network dependencies and contains required graph apis`() {
        val html = buildD3GraphHtml()
        assertTrue(html.contains("xmlns=\"http://www.w3.org/2000/svg\""))
        assertTrue(html.contains("http://www.w3.org/2000/svg"))

        val strippedSvgNs = html.replace("http://www.w3.org/2000/svg", "")
        assertFalse("Graph HTML must not reference external http:// URLs", strippedSvgNs.contains("http://"))
        assertFalse("Graph HTML must not reference external https:// URLs", strippedSvgNs.contains("https://"))
        assertFalse("Graph HTML must not load external script src", strippedSvgNs.contains("<script src"))
        assertFalse("Graph HTML must not load external stylesheets", strippedSvgNs.contains("<link "))
        assertFalse("Graph HTML must not call fetch()", strippedSvgNs.contains("fetch("))
        assertFalse("Graph HTML must not use XMLHttpRequest", strippedSvgNs.contains("XMLHttpRequest"))

        assertTrue(html.contains("window.updateGraphData"))
        assertTrue(html.contains("window.fitGraphToViewport"))
        assertTrue(html.contains("window.zoomGraphBy"))
        assertTrue(html.contains("window.resetGraphView"))
        assertTrue(html.contains("ResizeObserver"))
        assertTrue(html.contains("AndroidBridge.onNodeSelected"))
        assertTrue(html.contains("Destination Not Reached"))
        assertTrue(html.contains("node.ipScope"))
    }

    @Test
    fun `traceroute d3 graph view configures webview, dispatches payload on page finish, and handles controls`() {
        val sampleHops = (1..11).map { idx ->
            TracerouteHop(
                hopNumber = idx,
                ip = when (idx) {
                    1 -> "192.168.1.1"
                    4 -> "*"
                    11 -> "142.250.190.46"
                    else -> "172.16.$idx.1"
                },
                hostname = if (idx == 11) "iad23s96-in-f14.1e100.net" else null,
                rttMs = if (idx == 4) null else 10.0 + idx * 5.0,
                isTimedOut = idx == 4,
                isDestinationReached = idx == 11,
                statusText = if (idx == 11) "Destination Reached" else if (idx == 4) "Request Timed Out" else "Intermediate Hop"
            )
        }

        composeTestRule.setContent {
            TracerouteD3GraphView(
                hops = sampleHops,
                target = "google.com",
                isCompleted = true,
                destinationReached = true
            )
        }

        composeTestRule.onNodeWithText("D3 TOPOLOGY").assertIsDisplayed()
        composeTestRule.onNodeWithText("11 Hops • Tap node for details").assertIsDisplayed()
        composeTestRule.onNodeWithTag("traceroute_d3_webview").assertIsDisplayed()

        val webView = findWebView(composeTestRule.activity.window.decorView)
        assertNotNull("Expected Android WebView inside FrameLayout container", webView)
        val nonNullWebView = webView!!
        val parentFrame = nonNullWebView.parent as? FrameLayout
        assertNotNull("WebView must be hosted inside a FrameLayout", parentFrame)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, parentFrame!!.layoutParams.width)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, parentFrame.layoutParams.height)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, nonNullWebView.layoutParams.width)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, nonNullWebView.layoutParams.height)

        assertTrue(nonNullWebView.settings.javaScriptEnabled)
        assertTrue(nonNullWebView.settings.domStorageEnabled)
        assertFalse(nonNullWebView.settings.useWideViewPort)
        assertFalse(nonNullWebView.settings.loadWithOverviewMode)

        val shadowWebView = Shadows.shadowOf(nonNullWebView)
        val loaded = shadowWebView.lastLoadDataWithBaseURL
        assertNotNull(loaded)
        assertEquals("https://appassets.androidplatform.net/", loaded.baseUrl)
        assertEquals("text/html", loaded.mimeType)
        assertEquals("UTF-8", loaded.encoding)
        assertNotNull(shadowWebView.getJavascriptInterface("AndroidBridge"))

        composeTestRule.runOnUiThread {
            shadowWebView.webViewClient.onPageFinished(
                nonNullWebView,
                "https://appassets.androidplatform.net/"
            )
        }
        composeTestRule.waitForIdle()

        val updateCall = shadowWebView.lastEvaluatedJavascript
        assertNotNull(updateCall)
        assertTrue(updateCall.contains("window.updateGraphData"))
        assertTrue(updateCall.contains("google.com"))
        assertTrue(updateCall.contains("true, true"))

        composeTestRule.onNodeWithTag("zoom_in_graph_button").performClick()
        assertTrue(shadowWebView.lastEvaluatedJavascript.contains("window.zoomGraphBy(1.25)"))

        composeTestRule.onNodeWithTag("zoom_out_graph_button").performClick()
        assertTrue(shadowWebView.lastEvaluatedJavascript.contains("window.zoomGraphBy(0.8)"))

        composeTestRule.onNodeWithTag("fit_graph_view_button").performClick()
        assertTrue(shadowWebView.lastEvaluatedJavascript.contains("window.fitGraphToViewport()"))

        composeTestRule.onNodeWithTag("reset_graph_view_button").performClick()
        assertTrue(shadowWebView.lastEvaluatedJavascript.contains("window.resetGraphView()"))
    }

    @Test
    fun `traceroute d3 graph view survives state restoration`() {
        val sampleHops = listOf(
            TracerouteHop(hopNumber = 1, ip = "192.168.1.1", hostname = null, rttMs = 2.0, statusText = "Intermediate Hop"),
            TracerouteHop(hopNumber = 2, ip = "8.8.8.8", hostname = "dns.google", rttMs = 15.0, isDestinationReached = true, statusText = "Destination Reached")
        )

        val restorationTester = StateRestorationTester(composeTestRule)
        restorationTester.setContent {
            TracerouteD3GraphView(
                hops = sampleHops,
                target = "8.8.8.8",
                isCompleted = true,
                destinationReached = true
            )
        }

        composeTestRule.onNodeWithTag("traceroute_d3_webview").assertIsDisplayed()
        restorationTester.emulateSavedInstanceStateRestore()
        composeTestRule.onNodeWithTag("traceroute_d3_webview").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h915dp")
    fun `traceroute screen toggles between d3 graph and hop list modes`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vmStore = ViewModelStore()
        try {
            val vm = ViewModelProvider(
                vmStore,
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        return TracerouteViewModel(app) as T
                    }
                }
            )[TracerouteViewModel::class.java]

            composeTestRule.setContent {
                TracerouteScreen(
                    viewModel = vm,
                    onBackClick = {}
                )
            }

            composeTestRule.onNodeWithText("Traceroute Path Hops").assertIsDisplayed()
            composeTestRule.onNodeWithText("PATH TOPOLOGY (0 HOPS)").assertIsDisplayed()
            composeTestRule.onNodeWithTag("traceroute_view_graph_button").assertIsDisplayed()
            composeTestRule.onNodeWithTag("traceroute_view_list_button").assertIsDisplayed()

            composeTestRule.onNodeWithTag("traceroute_view_list_button").performClick()
            composeTestRule.onNodeWithText("No Hops Probed Yet").assertIsDisplayed()

            composeTestRule.onNodeWithTag("traceroute_view_graph_button").performClick()
            composeTestRule.onNodeWithTag("traceroute_target_input").assertIsDisplayed()
            composeTestRule.onNodeWithText("google.com").performClick()
            composeTestRule.onNodeWithTag("traceroute_start_button").assertIsDisplayed()
        } finally {
            vmStore.clear()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val match = findWebView(view.getChildAt(i))
                if (match != null) return match
            }
        }
        return null
    }
}
