package com.hawky.nascraft

import org.json.JSONObject
import java.net.IDN
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/** A configured server identity. Discovery addresses are deliberately not server identities. */
data class ServerProfile(
    val serverId: String,
    val displayName: String,
    val rpId: String,
    val lanWebUrl: String,
    val lanApiOrigin: String,
    val remoteApiOrigin: String,
) {
    fun apiOrigin(realm: ApiRealm): String = when (realm) {
        ApiRealm.LAN_NATIVE -> lanApiOrigin
        ApiRealm.REMOTE_API -> remoteApiOrigin.takeIf { it.isNotBlank() }
            ?: throw NativeApiException(
                null,
                "REMOTE_API_NOT_CONFIGURED",
                "Configure the Tailscale HTTPS API origin before using remote access",
            )
    }

    fun hasRemoteApiOrigin(): Boolean = remoteApiOrigin.isNotBlank()

    fun toQrJson(): String = JSONObject()
        .put("format", QR_FORMAT)
        .put("server_id", serverId)
        .put("display_name", displayName)
        .put("rp_id", rpId)
        .put("lan_web_url", lanWebUrl)
        .put("lan_api_origin", lanApiOrigin)
        .put("remote_api_origin", remoteApiOrigin)
        .toString()

    fun toQrPayload(): String = "nascraft://profile?data=" +
        Base64Codec.encodeUrlNoPadding(toQrJson().toByteArray(StandardCharsets.UTF_8))

    /** Checks the returned URL against the provisioned LAN Web origin without rebuilding it. */
    fun validateReturnedWebUrl(raw: String, requestId: String): String {
        val uri = try { URI(raw) } catch (_: Exception) {
            throw IllegalArgumentException("The server returned an invalid LAN confirmation URL")
        }
        require(uri.scheme.equals("https", ignoreCase = true) && uri.rawUserInfo == null &&
            uri.rawFragment == null && uri.rawPath == "/connect") {
            "The server returned an invalid LAN confirmation URL"
        }
        val actualOrigin = buildString {
            append("https://")
            append(uri.host ?: throw IllegalArgumentException("The server returned an invalid LAN confirmation URL"))
            if (uri.port != -1 && uri.port != 443) append(':').append(uri.port)
        }.lowercase(Locale.ROOT)
        val query = uri.rawQuery ?: throw IllegalArgumentException("The server returned an invalid LAN confirmation URL")
        require(actualOrigin == lanWebUrl && query.startsWith("request_id=")) {
            "The LAN confirmation URL belongs to another site"
        }
        val encodedId = query.removePrefix("request_id=")
        val decodedId = try {
            URLDecoder.decode(encodedId.replace("+", "%2B"), StandardCharsets.UTF_8.name())
        } catch (_: Exception) {
            throw IllegalArgumentException("The server returned an invalid LAN confirmation URL")
        }
        require(decodedId == requestId && !encodedId.contains('&') && !encodedId.contains('=')) {
            "The server returned a confirmation URL for a different device request"
        }
        return raw
    }

    companion object {
        const val QR_FORMAT = "nascraft.server-profile.v1"

        fun validated(
            serverId: String,
            displayName: String,
            rpId: String,
            lanWebUrl: String,
            lanApiOrigin: String,
            remoteApiOrigin: String,
        ): ServerProfile {
            val normalizedId = serverId.trim()
            require(SERVER_ID.matches(normalizedId)) { "Server identity is invalid" }
            val safeName = displayName.trim()
            require(safeName.isNotEmpty() && safeName.length <= 80) { "Enter a server name" }
            val normalizedRpId = normalizeDnsName(rpId, "RP ID")
            val webOrigin = normalizeHttpsOrigin(lanWebUrl, "LAN Web URL")
            val lanOrigin = normalizeHttpsOrigin(lanApiOrigin, "LAN API URL")
            val remoteOrigin = remoteApiOrigin.trim().takeIf { it.isNotEmpty() }
                ?.let { normalizeHttpsOrigin(it, "Tailscale API URL") }
                .orEmpty()
            return ServerProfile(
                serverId = normalizedId,
                displayName = safeName,
                rpId = normalizedRpId,
                lanWebUrl = webOrigin,
                lanApiOrigin = lanOrigin,
                remoteApiOrigin = remoteOrigin,
            )
        }

        fun fromQrJson(value: String): ServerProfile {
            val json = JSONObject(value)
            require(json.optString("format") == QR_FORMAT) { "This QR code is not a NASCraft server profile" }
            return validated(
                serverId = json.getString("server_id"),
                displayName = json.getString("display_name"),
                rpId = json.getString("rp_id"),
                lanWebUrl = json.getString("lan_web_url"),
                lanApiOrigin = json.getString("lan_api_origin"),
                remoteApiOrigin = if (!json.has("remote_api_origin") || json.isNull("remote_api_origin")) {
                    ""
                } else {
                    json.getString("remote_api_origin")
                },
            )
        }

        fun fromQrPayload(value: String): ServerProfile {
            val input = value.trim()
            val json = if (input.startsWith("{")) input else {
                val uri = URI(input)
                require(uri.scheme == "nascraft" && uri.host == "profile" && uri.rawQuery?.startsWith("data=") == true) {
                    "This QR code does not contain a NASCraft server profile"
                }
                val encoded = uri.rawQuery.removePrefix("data=")
                val decoded = try {
                    Base64Codec.decodeUrlNoPadding(encoded)
                } catch (_: Exception) {
                    throw IllegalArgumentException("The server profile QR code is malformed")
                }
                String(decoded, StandardCharsets.UTF_8)
            }
            return fromQrJson(json)
        }

        private fun normalizeHttpsOrigin(raw: String, label: String): String {
            val input = raw.trim()
            val uri = try {
                URI(input)
            } catch (_: Exception) {
                throw IllegalArgumentException("$label must be a complete HTTPS domain")
            }
            require(uri.scheme.equals("https", ignoreCase = true)) { "$label must use HTTPS" }
            require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
                "$label must be an origin without credentials, query, or fragment"
            }
            require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") {
                "$label must be an origin without a path"
            }
            val host = uri.host ?: throw IllegalArgumentException("$label must contain a domain name")
            val dnsName = normalizeDnsName(host, label)
            val port = uri.port
            require(port in -1..65535 && port != 0) { "$label has an invalid port" }
            return buildString {
                append("https://")
                append(dnsName)
                if (port != -1 && port != 443) append(':').append(port)
            }
        }

        private fun normalizeDnsName(raw: String, label: String): String {
            val host = raw.trim().trimEnd('.').lowercase(Locale.ROOT)
            require(host.isNotEmpty() && !host.contains(':') && !IPV4.matches(host)) {
                "$label must be a DNS name, not an IP address"
            }
            val ascii = try {
                IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
            } catch (_: Exception) {
                throw IllegalArgumentException("$label is not a valid DNS name")
            }
            val labels = ascii.split('.')
            val numericAddressLike = labels.all { label ->
                label.all { it.isDigit() } || label.matches(Regex("0x[0-9a-f]+", RegexOption.IGNORE_CASE))
            }
            require(ascii.contains('.') && !numericAddressLike && labels.lastOrNull()?.any { it in 'a'..'z' } == true &&
                ascii.length <= 253 && labels.all { it.isNotEmpty() && it.length <= 63 }) {
                "$label must be a complete DNS name"
            }
            return ascii
        }

        private val SERVER_ID = Regex("[A-Za-z0-9_-]{8,128}")
        private val IPV4 = Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")
    }
}

enum class ApiRealm(val routePrefix: String) {
    LAN_NATIVE("/api/lan/native/v1"),
    REMOTE_API("/api/remote/v1"),
}

fun ServerProfile.asConnectedServer(): DiscoveredServer {
    val uri = URI(lanApiOrigin)
    return DiscoveredServer(
        name = displayName,
        ip = java.net.InetAddress.getLoopbackAddress(),
        port = if (uri.port == -1) 443 else uri.port,
        proto = "https",
        rawResponse = "",
        configuredApiOrigin = lanApiOrigin,
        displayHost = uri.host,
    )
}

fun DiscoveredServer.apiBaseUrl(): String = configuredApiOrigin
    ?: "${proto}://${ip.hostAddress}:${port}"

fun DiscoveredServer.displayEndpoint(): String = displayHost ?: ip.hostAddress.orEmpty()

/** A discovery result is an untrusted LAN address hint, never an auth profile. */
data class LanDiscoveryCandidate(
    val displayName: String,
    val address: String,
    val port: Int,
    val transport: String,
)
