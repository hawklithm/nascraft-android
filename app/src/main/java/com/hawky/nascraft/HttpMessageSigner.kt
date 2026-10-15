package com.hawky.nascraft

import java.net.URI
import java.net.IDN
import java.security.MessageDigest

data class Rfc9421Headers(
    val contentDigest: String,
    val signatureInput: String,
    val signature: String,
    val targetUri: String,
    val canonicalBase: String,
)

/** RFC 9421 / RFC 9530 request proof for the frozen NASCraft native contract. */
object HttpMessageSigner {
    private val BASE_COMPONENTS = listOf("@method", "@target-uri", "content-digest", "nascraft-session", "nascraft-nonce")
    private val EXTRA_COMPONENT_ORDER = listOf("x-file-id", "x-start-offset", "content-range", "range", "if-range")
    private val SAFE_PARAMETER_TEXT = Regex("[A-Za-z0-9_:/.-]{1,512}")

    fun sign(
        method: String,
        configuredOrigin: String,
        rawPathAndQuery: String,
        body: ByteArray,
        sessionMarker: String,
        nonce: String,
        keyId: String,
        createdEpochSeconds: Long,
        expiresEpochSeconds: Long,
        coveredHeaders: Map<String, String> = emptyMap(),
        signer: (ByteArray) -> ByteArray,
    ): Rfc9421Headers {
        require(method.matches(Regex("[A-Z]{3,10}"))) { "HTTP method must be uppercase" }
        require(SAFE_PARAMETER_TEXT.matches(sessionMarker)) { "Invalid session marker" }
        require(SAFE_PARAMETER_TEXT.matches(nonce)) { "Invalid request nonce" }
        require(SAFE_PARAMETER_TEXT.matches(keyId)) { "Invalid signature key ID" }
        require(createdEpochSeconds > 0 && expiresEpochSeconds >= createdEpochSeconds) { "Invalid signature time" }
        require(expiresEpochSeconds - createdEpochSeconds <= 60) { "Signature validity exceeds 60 seconds" }
        val normalizedHeaders = coveredHeaders.entries.associate { (name, value) ->
            val normalizedName = name.lowercase()
            require(normalizedName in EXTRA_COMPONENT_ORDER) { "Unsupported signed request header" }
            validateCoveredHeaderValue(normalizedName, value)
            normalizedName to value
        }
        require(normalizedHeaders.size == coveredHeaders.size) { "Duplicate signed request header" }
        val uploadHeadersPresent = listOf("x-file-id", "x-start-offset", "content-range").count { it in normalizedHeaders }
        require(uploadHeadersPresent == 0 || uploadHeadersPresent == 3) { "Upload metadata must be covered as a complete set" }
        require("if-range" !in normalizedHeaders || "range" in normalizedHeaders) { "If-Range requires Range" }
        require(uploadHeadersPresent == 0 || ("range" !in normalizedHeaders && "if-range" !in normalizedHeaders)) {
            "Upload and download headers cannot be signed together"
        }
        if (uploadHeadersPresent == 3) {
            require(method == "POST") { "Upload metadata can only be signed on POST requests" }
            validateUploadHeaders(normalizedHeaders, body)
        }
        if ("range" in normalizedHeaders) {
            require(method == "GET" || method == "HEAD") { "Range can only be signed on GET or HEAD requests" }
            require(body.isEmpty()) { "Range requests must not include a body" }
            validateSingleRange(normalizedHeaders.getValue("range"))
        }
        val extraNames = EXTRA_COMPONENT_ORDER.filter { it in normalizedHeaders }
        val componentNames = BASE_COMPONENTS + extraNames
        val coveredComponents = componentNames.joinToString(" ", prefix = "(", postfix = ")") { "\"$it\"" }

        val origin = requireExactHttpsOrigin(configuredOrigin)
        val targetUri = buildTargetUri(origin, rawPathAndQuery)
        val digestBytes = MessageDigest.getInstance("SHA-256").digest(body)
        val contentDigest = "sha-256=:${Base64Codec.encodeStandard(digestBytes)}:"
        val params = "$coveredComponents;created=$createdEpochSeconds;expires=$expiresEpochSeconds;nonce=\"$nonce\";keyid=\"$keyId\";alg=\"ecdsa-p256-sha256\""
        val signatureInput = "sig1=$params"
        val canonicalBase = (listOf(
            "\"@method\": $method",
            "\"@target-uri\": $targetUri",
            "\"content-digest\": $contentDigest",
            "\"nascraft-session\": $sessionMarker",
            "\"nascraft-nonce\": $nonce",
        ) + extraNames.map { name -> "\"$name\": ${normalizedHeaders.getValue(name)}" } +
            "\"@signature-params\": $params").joinToString("\n")
        val rawSignature = signer(canonicalBase.toByteArray(Charsets.UTF_8))
        require(EcdsaP1363.isLowS(rawSignature)) { "Signer must return a low-S P-256 P1363 signature" }
        return Rfc9421Headers(
            contentDigest = contentDigest,
            signatureInput = signatureInput,
            signature = "sig1=:${Base64Codec.encodeStandard(rawSignature)}:",
            targetUri = targetUri,
            canonicalBase = canonicalBase,
        )
    }

    fun requireExactHttpsOrigin(value: String): String {
        val uri = try { URI(value) } catch (_: Exception) {
            throw IllegalArgumentException("Configured API origin is invalid")
        }
        require(uri.scheme.equals("https", ignoreCase = true) && uri.host != null) {
            "Native API traffic requires the configured HTTPS origin"
        }
        require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/")) {
            "Configured API origin must not contain credentials, path, query, or fragment"
        }
        val host = try {
            IDN.toASCII(uri.host.trimEnd('.'), IDN.USE_STD3_ASCII_RULES).lowercase()
        } catch (_: Exception) {
            throw IllegalArgumentException("Configured API origin has an invalid domain name")
        }
        val labels = host.split('.')
        val numericAddressLike = labels.all { label ->
            label.all { it.isDigit() } || label.matches(Regex("0x[0-9a-f]+", RegexOption.IGNORE_CASE))
        }
        require(host.contains('.') && !numericAddressLike && labels.lastOrNull()?.any { it in 'a'..'z' } == true &&
            !host.contains(':')) {
            "Native API traffic requires a complete DNS domain name"
        }
        val port = uri.port
        require(port in -1..65535 && port != 0) { "Configured API origin has an invalid port" }
        return buildString {
            append("https://")
            append(host)
            if (port != -1 && port != 443) append(':').append(port)
        }
    }

    fun validateOriginForm(rawPathAndQuery: String) {
        require(rawPathAndQuery.startsWith('/') && !rawPathAndQuery.startsWith("//")) {
            "Signed target must be an origin-form path and query"
        }
        require(!rawPathAndQuery.contains('#') && !rawPathAndQuery.contains('\\')) {
            "Signed target contains an ambiguous delimiter"
        }
        require(rawPathAndQuery.all { it in '!'..'~' }) {
            "Signed target must use an ASCII URI encoding"
        }
        val path = rawPathAndQuery.substringBefore('?')
        require(path.split('/').none { it == "." || it == ".." }) {
            "Signed target must not contain dot segments"
        }
        require(!path.contains('%')) {
            "Signed path must not contain percent-encoded path segments"
        }
        val query = rawPathAndQuery.substringAfter('?', "")
        var index = 0
        while (index < query.length) {
            if (query[index] == '%') {
                require(index + 2 < query.length && query[index + 1].digitToIntOrNull(16) != null &&
                    query[index + 2].digitToIntOrNull(16) != null) {
                    "Signed query contains an invalid percent escape"
                }
                index += 3
            } else {
                index++
            }
        }
    }

    private fun validateCoveredHeaderValue(name: String, value: String) {
        require(value.isNotEmpty() && value.length <= 1024 && value == value.trim() &&
            value.all { it in ' '..'~' }) { "Invalid signed request header value" }
        when (name) {
            "x-file-id" -> require(value.length <= 128 && FILE_ID.matches(value)) {
                "Invalid upload file ID header"
            }
            "x-start-offset" -> parseCanonicalUnsigned(value)
            "content-range" -> {
                val match = CONTENT_RANGE.matchEntire(value) ?: throw IllegalArgumentException("Invalid Content-Range header")
                val start = parseCanonicalUnsigned(match.groupValues[1])
                val end = parseCanonicalUnsigned(match.groupValues[2])
                val total = parseCanonicalUnsigned(match.groupValues[3])
                require(start <= end && end < total) { "Invalid Content-Range bounds" }
            }
            "range" -> validateSingleRange(value)
            "if-range" -> Unit // Preserve the exact printable field value; the server applies validator semantics.
        }
    }

    private fun validateUploadHeaders(headers: Map<String, String>, body: ByteArray) {
        val offset = parseCanonicalUnsigned(headers.getValue("x-start-offset"))
        val match = CONTENT_RANGE.matchEntire(headers.getValue("content-range"))
            ?: throw IllegalArgumentException("Invalid Content-Range header")
        val start = parseCanonicalUnsigned(match.groupValues[1])
        val end = parseCanonicalUnsigned(match.groupValues[2])
        val total = parseCanonicalUnsigned(match.groupValues[3])
        require(body.isNotEmpty() && offset == start && end - start == body.size.toLong() - 1 && end < total) {
            "Upload headers do not match the signed chunk body"
        }
    }

    private fun validateSingleRange(value: String) {
        require(value.startsWith("bytes=") && !value.contains(',')) { "Only one byte range is supported" }
        val range = value.removePrefix("bytes=")
        if (range.startsWith('-')) {
            throw IllegalArgumentException("Suffix byte ranges are not supported by this client")
        }
        val separator = range.indexOf('-')
        require(separator > 0) { "Invalid byte range" }
        val start = parseCanonicalUnsigned(range.substring(0, separator))
        val endText = range.substring(separator + 1)
        if (endText.isNotEmpty()) {
            val end = parseCanonicalUnsigned(endText)
            require(end >= start) { "Invalid byte range bounds" }
        }
    }

    private fun parseCanonicalUnsigned(value: String): Long {
        require(value.isNotEmpty() && value.all { it in '0'..'9' }) { "Expected an unsigned integer header value" }
        val parsed = value.toLongOrNull() ?: throw IllegalArgumentException("Header integer is out of range")
        require(parsed.toString() == value) { "Header integer is not canonical" }
        return parsed
    }

    private fun buildTargetUri(origin: String, rawPathAndQuery: String): String {
        validateOriginForm(rawPathAndQuery)
        return origin + rawPathAndQuery
    }

    private val CONTENT_RANGE = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)")
    private val FILE_ID = Regex("[A-Za-z0-9._-]{1,128}")
}
