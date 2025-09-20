package com.example.switchshot.qr

/**
 * Simple representation of the Wi-Fi QR payload provided by Nintendo Switch.
 */
data class WifiQr(
    val ssid: String,
    val password: String?,
    val security: String
)

private val keyValueRegex = Regex("([A-Z]):([^;]*)")

fun parseWifiQr(raw: String): WifiQr? {
    if (!raw.startsWith("WIFI:")) return null
    val content = raw.removePrefix("WIFI:")
    val parts = content.split(';')
    if (parts.isEmpty()) return null

    val values = mutableMapOf<String, String>()
    for (part in parts) {
        val match = keyValueRegex.matchEntire(part)
        if (match != null) {
            val key = match.groupValues[1]
            val value = match.groupValues[2]
            values[key] = value
        }
    }

    val ssid = values["S"]?.takeIf { it.isNotEmpty() } ?: return null
    val password = values["P"]?.takeIf { it.isNotEmpty() }
    val security = values["T"]?.takeIf { it.isNotEmpty() } ?: "WPA"

    return WifiQr(ssid = ssid, password = password, security = security)
}
