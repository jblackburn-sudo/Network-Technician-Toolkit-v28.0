package com.example.networktoolkit.speedtest

import android.content.Context
import android.net.Uri

data class SpeedTestServiceOption(
    val id: String,
    val name: String,
    val url: String,
    val description: String,
    val isCustom: Boolean = false
)

/**
 * Persists and manages the technician's selected web speed test service website.
 */
object SpeedTestServicePreferences {

    private const val PREFS_NAME = "SpeedTestPrefs"
    private const val KEY_SERVICE_ID = "selected_service_id"
    private const val KEY_CUSTOM_NAME = "custom_service_name"
    private const val KEY_CUSTOM_URL = "custom_service_url"

    const val CUSTOM_SERVICE_ID = "custom"
    const val DEFAULT_SERVICE_ID = "speedtest_net"

    @JvmField
    val PRESET_SERVICES: List<SpeedTestServiceOption> = listOf(
        SpeedTestServiceOption(
            id = "speedtest_net",
            name = "Speedtest.net",
            url = "https://www.speedtest.net",
            description = "Ookla global broadband & latency benchmark"
        ),
        SpeedTestServiceOption(
            id = "cloudflare",
            name = "Cloudflare Speed",
            url = "https://speed.cloudflare.com",
            description = "Throughput, packet loss & loaded latency"
        ),
        SpeedTestServiceOption(
            id = "fast_com",
            name = "Fast.com",
            url = "https://fast.com",
            description = "Netflix CDN download speed check"
        ),
        SpeedTestServiceOption(
            id = "librespeed",
            name = "LibreSpeed",
            url = "https://librespeed.org",
            description = "Lightweight HTML5 open-source speed test"
        )
    )

    @JvmStatic
    fun getPresetServices(): List<SpeedTestServiceOption> = PRESET_SERVICES

    @JvmStatic
    fun getSelectedService(context: Context): SpeedTestServiceOption {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val selectedId = prefs.getString(KEY_SERVICE_ID, DEFAULT_SERVICE_ID) ?: DEFAULT_SERVICE_ID

        if (selectedId == CUSTOM_SERVICE_ID) {
            val savedUrl = prefs.getString(KEY_CUSTOM_URL, null)
            val normalizedUrl = savedUrl?.let { normalizeServiceUrl(it) }
            if (normalizedUrl != null) {
                val savedName = prefs.getString(KEY_CUSTOM_NAME, null)?.trim().orEmpty()
                val displayName = savedName.ifEmpty {
                    Uri.parse(normalizedUrl).host ?: "Custom Speed Test"
                }
                return SpeedTestServiceOption(
                    id = CUSTOM_SERVICE_ID,
                    name = displayName,
                    url = normalizedUrl,
                    description = "Custom technician speed test website",
                    isCustom = true
                )
            }
        }

        return PRESET_SERVICES.firstOrNull { it.id == selectedId }
            ?: PRESET_SERVICES.first()
    }

    @JvmStatic
    fun getSavedCustomUrl(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_CUSTOM_URL, "") ?: ""
    }

    @JvmStatic
    fun getSavedCustomName(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_CUSTOM_NAME, "") ?: ""
    }

    @JvmStatic
    fun saveSelectedPreset(context: Context, serviceId: String): SpeedTestServiceOption {
        val preset = PRESET_SERVICES.firstOrNull { it.id == serviceId } ?: PRESET_SERVICES.first()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SERVICE_ID, preset.id)
            .apply()
        return preset
    }

    @JvmStatic
    fun saveCustomService(
        context: Context,
        customName: String?,
        rawUrl: String
    ): SpeedTestServiceOption? {
        val normalizedUrl = normalizeServiceUrl(rawUrl) ?: return null
        val trimmedName = customName?.trim().orEmpty()
        val displayName = trimmedName.ifEmpty {
            Uri.parse(normalizedUrl).host ?: "Custom Speed Test"
        }

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SERVICE_ID, CUSTOM_SERVICE_ID)
            .putString(KEY_CUSTOM_NAME, displayName)
            .putString(KEY_CUSTOM_URL, normalizedUrl)
            .apply()

        return SpeedTestServiceOption(
            id = CUSTOM_SERVICE_ID,
            name = displayName,
            url = normalizedUrl,
            description = "Custom technician speed test website",
            isCustom = true
        )
    }

    /**
     * Returns true only for local loopback, mDNS/LAN suffixes, or private RFC 1918 / link-local
     * IPv4 hosts where technicians legitimately test local router/AP diagnostic pages over HTTP.
     */
    @JvmStatic
    fun isLocalOrPrivateDiagnosticHost(rawHost: String?): Boolean {
        val clean = rawHost?.trim()?.removePrefix("[")?.removeSuffix("]")?.lowercase().orEmpty()
        if (clean.isEmpty()) return false
        if (clean == "localhost" || clean == "127.0.0.1" || clean == "::1") return true
        if (clean.endsWith(".local") || clean.endsWith(".lan") || clean.endsWith(".internal") || clean.endsWith(".home.arpa")) {
            return true
        }
        val parts = clean.split(".")
        if (parts.size == 4) {
            val octets = parts.mapNotNull { it.toIntOrNull() }
            if (octets.size == 4 && octets.all { it in 0..255 }) {
                val o1 = octets[0]
                val o2 = octets[1]
                if (o1 == 127) return true
                if (o1 == 10) return true
                if (o1 == 172 && o2 in 16..31) return true
                if (o1 == 192 && o2 == 168) return true
                if (o1 == 169 && o2 == 254) return true
            }
        }
        if (clean.contains(":")) {
            if (clean.startsWith("fe80:") || clean.startsWith("fc") || clean.startsWith("fd")) {
                return true
            }
        }
        return false
    }

    /**
     * Validates URLs before loading or navigating inside WebView.
     * Security guarantee: blocks javascript:, file:, content:, intent:, data:, blob:, about:,
     * embedded userInfo credentials, whitespace/control chars, and external cleartext http:// URLs.
     * Local/private diagnostic http:// hosts are permitted only when [allowLocalHttp] is true.
     */
    @JvmStatic
    @JvmOverloads
    fun isAllowedWebViewUrl(rawUrl: String?, allowLocalHttp: Boolean = true): Boolean {
        val trimmed = rawUrl?.trim().orEmpty()
        if (trimmed.isEmpty()) return false
        if (trimmed.any { it.isWhitespace() || it.code < 0x20 }) return false

        val parsed = try {
            Uri.parse(trimmed)
        } catch (_: Exception) {
            return false
        }

        val scheme = parsed.scheme?.lowercase().orEmpty()
        if (scheme != "https" && scheme != "http") return false
        if (!parsed.userInfo.isNullOrEmpty()) return false
        val authority = parsed.encodedAuthority.orEmpty()
        if (authority.contains("@")) return false

        val host = parsed.host?.trim().orEmpty()
        if (host.isEmpty()) return false
        val validHostSyntax = host.contains(".") || host.equals("localhost", ignoreCase = true) || host.contains(":")
        if (!validHostSyntax) return false

        return if (scheme == "https") {
            true
        } else {
            allowLocalHttp && isLocalOrPrivateDiagnosticHost(host)
        }
    }

    /**
     * Normalizes a custom speed-test URL entered by the technician.
     * Security implication: external public endpoints must use https:// (bare domains default to https://);
     * cleartext http:// is allowed strictly for local/private diagnostic hosts (localhost, .local/.lan,
     * RFC 1918, or link-local). Unsafe schemes such as javascript:, file:, content:, intent:, or data:
     * are rejected.
     */
    @JvmStatic
    fun normalizeServiceUrl(rawUrl: String?): String? {
        val trimmed = rawUrl?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() || it.code < 0x20 }) return null

        val schemeMatch = Regex("""^([A-Za-z][A-Za-z0-9+.\-]*):""").find(trimmed)
        val explicitScheme = schemeMatch?.groupValues?.getOrNull(1)?.lowercase()
        if (explicitScheme != null && explicitScheme != "http" && explicitScheme != "https") {
            return null
        }

        val withScheme = if (explicitScheme != null) {
            trimmed
        } else {
            "https://$trimmed"
        }

        if (!isAllowedWebViewUrl(withScheme, allowLocalHttp = true)) {
            return null
        }

        val parsed = try {
            Uri.parse(withScheme)
        } catch (_: Exception) {
            return null
        }
        return parsed.toString()
    }
}
