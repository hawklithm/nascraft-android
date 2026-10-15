package com.hawky.nascraft

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class NativeProof(
    val keyAlias: String,
    val keyId: String,
    val sessionMarker: String,
    val nonce: String,
    val accessToken: String? = null,
)

class NativeApiException(
    val httpStatus: Int?,
    val apiCode: String?,
    message: String,
    val retryAfterSeconds: Long? = null,
) : IllegalStateException(message)

class ServerIdentityMismatchException(message: String) : IllegalStateException(message)

data class NativeHttpResponse(
    val statusCode: Int,
    val contentType: String?,
    val body: ByteArray,
)

data class NativeDownloadReceipt(
    val statusCode: Int,
    val contentType: String?,
    val bytesWritten: Long,
    val contentRange: String?,
    val acceptRanges: String?,
    val metadata: DownloadFileMetadata? = null,
    val start: Long? = null,
    val endInclusive: Long? = null,
    val totalSize: Long? = null,
)

data class ParsedContentRange(val start: Long, val endInclusive: Long, val total: Long) {
    val byteCount: Long get() = endInclusive - start + 1
}

object NativeDownloadStream {
    fun parseContentRange(value: String?): ParsedContentRange? {
        val match = value?.let { Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matchEntire(it) } ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val total = match.groupValues[3].toLongOrNull() ?: return null
        if (start < 0 || end < start || total <= end) return null
        return ParsedContentRange(start, end, total)
    }

    fun copyExactly(input: java.io.InputStream, output: OutputStream, expectedBytes: Long): Long {
        require(expectedBytes >= 0)
        var written = 0L
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count.toLong() > expectedBytes - written) throw IllegalStateException("Download response exceeded its verified byte range")
            written += count.toLong()
            output.write(buffer, 0, count)
        }
        if (written != expectedBytes) throw IllegalStateException("Download response did not match its verified byte range")
        output.flush()
        return written
    }
}

object NativeBusinessTarget {
    fun validate(method: String, pathAndQuery: String) {
        require(method in setOf("GET", "HEAD", "POST", "PUT", "DELETE")) {
            "Unsupported native business method"
        }
        require(pathAndQuery.startsWith("/api/") && !pathAndQuery.startsWith("//") &&
            !pathAndQuery.startsWith("/api/lan/") && !pathAndQuery.startsWith("/api/remote/")) {
            "Business request must use a common NASCraft API path"
        }
        HttpMessageSigner.validateOriginForm(pathAndQuery)
    }

    fun validateAdditionalHeaders(headers: Map<String, String>) {
        val names = headers.keys.map { it.lowercase() }
        val allowed = setOf("x-file-id", "x-start-offset", "content-range", "range", "if-range")
        val uploadFields = setOf("x-file-id", "x-start-offset", "content-range")
        require(names.distinct().size == names.size && names.all { it in allowed } &&
            headers.keys.all { it.matches(Regex("[A-Za-z0-9-]{1,64}")) } &&
            headers.values.all { it.length in 1..1024 && it == it.trim() && it.all { char -> char in ' '..'~' } } &&
            (names.intersect(uploadFields).isEmpty() || names.containsAll(uploadFields)) &&
            ("if-range" !in names || "range" in names)) {
            "Invalid native request header"
        }
    }
}

/** Strict HTTPS transport for authentication routes. Redirects are disabled to avoid credential forwarding. */
class NativeAuthApi(
    private val installationKeys: InstallationKeyStore,
    private val repository: DeviceAuthRepository,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build(),
    private val requestSigner: (String, ByteArray) -> ByteArray = { alias, message ->
        installationKeys.signP1363(alias, message)
    },
) {
    private val sessionNonceLock = Mutex()
    private val activeDownloadCalls = ConcurrentHashMap<String, okhttp3.Call>()

    suspend fun postUnsigned(profile: ServerProfile, realm: ApiRealm, path: String, body: JSONObject): JSONObject =
        withContext(Dispatchers.IO) { execute(profile, realm, "POST", path, body.toString(), proof = null) }

    suspend fun getUnsigned(profile: ServerProfile, realm: ApiRealm, path: String): JSONObject =
        withContext(Dispatchers.IO) { execute(profile, realm, "GET", path, null, proof = null) }

    suspend fun getSigned(
        profile: ServerProfile,
        realm: ApiRealm,
        path: String,
        proof: NativeProof,
    ): JSONObject = withContext(Dispatchers.IO) { execute(profile, realm, "GET", path, null, proof) }

    suspend fun postSigned(
        profile: ServerProfile,
        realm: ApiRealm,
        path: String,
        body: JSONObject,
        proof: NativeProof,
    ): JSONObject = withContext(Dispatchers.IO) { execute(profile, realm, "POST", path, body.toString(), proof) }

    /** All NASCraft business HTTP calls go through this signed, fixed-origin path. */
    suspend fun businessRequest(
        profile: ServerProfile,
        realm: ApiRealm,
        method: String,
        pathAndQuery: String,
        body: ByteArray = ByteArray(0),
        contentType: String? = null,
        additionalHeaders: Map<String, String> = emptyMap(),
    ): NativeHttpResponse = withContext(Dispatchers.IO) {
        val request = buildSignedBusinessRequest(profile, realm, method, pathAndQuery, body, contentType, additionalHeaders)
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val bytes = response.body?.byteStream()?.let { readAtMost(it, MAX_ERROR_BODY_BYTES) } ?: ByteArray(0)
                val envelope = runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }.getOrNull()
                val code = envelope?.optString("code")?.takeIf { it.isNotEmpty() }
                handleAuthorizationFailure(response.code, code, request.header("Nascraft-Session"), request.header("Authorization")?.removePrefix("Bearer "))
                throw NativeApiException(response.code, code, publicError(code, response.code))
            }
            val contentTypeHeader = response.header("Content-Type")
            val limit = if (contentTypeHeader?.substringBefore(';')?.trim()?.startsWith("image/", ignoreCase = true) == true) {
                MAX_PREVIEW_RESPONSE_BYTES
            } else {
                MAX_JSON_RESPONSE_BYTES
            }
            val contentLength = response.body?.contentLength() ?: 0L
            if (contentLength > limit) throw NativeApiException(response.code, "RESPONSE_TOO_LARGE", "This response is too large to preview safely")
            val bytes = response.body?.byteStream()?.let { readAtMost(it, limit) } ?: ByteArray(0)
            if (bytes.size > limit) throw NativeApiException(response.code, "RESPONSE_TOO_LARGE", "This response is too large to preview safely")
            NativeHttpResponse(response.code, contentTypeHeader, bytes)
        }
    }

    /** Streams an authenticated media response directly to a caller-selected destination. */
    suspend fun businessDownloadTo(
        profile: ServerProfile,
        realm: ApiRealm,
        pathAndQuery: String,
        output: OutputStream,
        expectedSize: Long,
        additionalHeaders: Map<String, String> = emptyMap(),
    ): NativeDownloadReceipt = withContext(Dispatchers.IO) {
        require(expectedSize >= 0) { "Expected download size must not be negative" }
        val request = buildSignedBusinessRequest(
            profile, realm, "GET", pathAndQuery, ByteArray(0), null, additionalHeaders,
        )
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val bytes = response.body?.byteStream()?.let { readAtMost(it, MAX_ERROR_BODY_BYTES) } ?: ByteArray(0)
                val envelope = runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }.getOrNull()
                val code = envelope?.optString("code")?.takeIf { it.isNotEmpty() }
                handleAuthorizationFailure(response.code, code, request.header("Nascraft-Session"), request.header("Authorization")?.removePrefix("Bearer "))
                throw NativeApiException(response.code, code, publicError(code, response.code))
            }
            if (response.code !in setOf(200, 206)) {
                throw NativeApiException(response.code, "BAD_DOWNLOAD_STATUS", "The server returned an unsupported download response")
            }
            val body = response.body ?: throw NativeApiException(response.code, "BAD_RESPONSE", "The server returned no file data")
            val rangeRequested = additionalHeaders.entries.firstOrNull { it.key.equals("range", ignoreCase = true) }?.value
            val contentRange = NativeDownloadStream.parseContentRange(response.header("Content-Range"))
            val transferSize = when (response.code) {
                200 -> {
                    if (rangeRequested != null || response.header("Content-Range") != null) {
                        throw NativeApiException(response.code, "BAD_CONTENT_RANGE", "The server returned an invalid full-file response")
                    }
                    expectedSize
                }
                else -> {
                    if (rangeRequested == null || contentRange == null || contentRange.total != expectedSize ||
                        !rangeMatches(rangeRequested, contentRange, expectedSize)) {
                        throw NativeApiException(response.code, "BAD_CONTENT_RANGE", "The server returned a different byte range than requested")
                    }
                    contentRange.byteCount
                }
            }
            val declaredLength = body.contentLength()
            if (declaredLength >= 0 && declaredLength != transferSize) {
                throw NativeApiException(response.code, "DOWNLOAD_SIZE_MISMATCH", "The server returned more data than the file listing reported")
            }
            val written = try {
                body.byteStream().use { input -> NativeDownloadStream.copyExactly(input, output, transferSize) }
            } catch (failure: IllegalStateException) {
                throw NativeApiException(response.code, "DOWNLOAD_SIZE_MISMATCH", "The downloaded file size did not match the file listing")
            }
            NativeDownloadReceipt(
                statusCode = response.code,
                contentType = response.header("Content-Type"),
                bytesWritten = written,
                contentRange = response.header("Content-Range"),
                acceptRanges = response.header("Accept-Ranges"),
            )
        }
    }

    /** Signed HEAD for the resumable-download protocol. HEAD responses are always bodyless. */
    suspend fun businessDownloadHead(
        profile: ServerProfile,
        realm: ApiRealm,
        pathAndQuery: String,
        operationId: String,
    ): DownloadHeadResult = withContext(Dispatchers.IO) {
        val request = buildSignedBusinessRequest(profile, realm, "HEAD", pathAndQuery, ByteArray(0), null, emptyMap())
        val call = client.newCall(request)
        activeDownloadCalls[operationId] = call
        try {
            // A pause can race nonce acquisition/signing. Register first, then
            // observe cancellation before entering the blocking OkHttp call.
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            call.execute().use { response ->
                if (response.code == 202 || response.code == 503) {
                    val expectedState = if (response.code == 202) "preparing" else "busy"
                    if (response.header("Content-Length")?.toLongOrNull() != 0L ||
                        response.header("Nascraft-Download-State") != expectedState) {
                        throw NativeApiException(response.code, "BAD_DOWNLOAD_STATUS", "The server returned invalid preparation metadata")
                    }
                    val retryAfter = retryAfterSeconds(response.header("Retry-After"))
                        ?: throw NativeApiException(response.code, "BAD_DOWNLOAD_STATUS", "The server omitted its retry interval")
                    return@withContext DownloadHeadResult(response.code, retryAfterSeconds = retryAfter)
                }
                if (response.code == 401) {
                    val code = response.header("Nascraft-Auth-Error")?.takeIf { it.matches(API_CODE) }
                    handleAuthorizationFailure(response.code, code, request.header("Nascraft-Session"), request.header("Authorization")?.removePrefix("Bearer "))
                    throw NativeApiException(response.code, code, publicError(code, response.code))
                }
                if (response.code == 409 || response.code == 416) {
                    throw NativeApiException(response.code, "SOURCE_CHANGED", "The file changed on the server; restart this download to use the new version")
                }
                if (response.code != 200) {
                    val code = response.header("Nascraft-Auth-Error")?.takeIf { it.matches(API_CODE) }
                    handleAuthorizationFailure(response.code, code, request.header("Nascraft-Session"), request.header("Authorization")?.removePrefix("Bearer "))
                    throw NativeApiException(response.code, code, publicError(code, response.code))
                }
                DownloadHeadResult(
                    statusCode = response.code,
                    metadata = parseDownloadMetadata(response, profile.serverId, downloadFileId(pathAndQuery)),
                )
            }
        } finally {
            activeDownloadCalls.remove(operationId, call)
        }
    }

    /** Signs and streams exactly one 4 MiB-or-smaller range into private staging. */
    suspend fun businessDownloadRangeTo(
        profile: ServerProfile,
        realm: ApiRealm,
        pathAndQuery: String,
        metadata: DownloadFileMetadata,
        start: Long,
        endInclusive: Long,
        output: OutputStream,
        operationId: String,
    ): NativeDownloadReceipt = withContext(Dispatchers.IO) {
        require(metadata.isValid() && metadata.serverId == profile.serverId && metadata.fileId == downloadFileId(pathAndQuery))
        require(start >= 0 && endInclusive >= start && endInclusive < metadata.totalSize && endInclusive - start + 1 <= 4L * 1024 * 1024)
        val range = "bytes=$start-$endInclusive"
        val headers = mapOf("Range" to range, "If-Range" to metadata.etag)
        val request = buildSignedBusinessRequest(profile, realm, "GET", pathAndQuery, ByteArray(0), null, headers)
        val call = client.newCall(request)
        activeDownloadCalls[operationId] = call
        try {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            call.execute().use { response ->
                if (response.code == 200) {
                    // A server that ignores Range must never have its full body appended to staging.
                    throw NativeApiException(response.code, "SOURCE_CHANGED", "The server returned a full response to a ranged download")
                }
                if (response.code == 409) {
                    // A stale range is terminal for this task. Do not consume
                    // any response body or append a byte from the new version.
                    throw NativeApiException(response.code, "SOURCE_CHANGED", "The file changed on the server; restart this download to use the new version")
                }
                if (response.code == 202 || response.code == 503) {
                    if (response.code == 202 && (response.header("Content-Length")?.toLongOrNull() != 0L ||
                            response.header("Nascraft-Download-State") != "preparing")) {
                        throw NativeApiException(response.code, "BAD_DOWNLOAD_STATUS", "The server returned invalid preparation metadata")
                    }
                    val retryAfter = retryAfterSeconds(response.header("Retry-After"))
                        ?: throw NativeApiException(response.code, "BAD_DOWNLOAD_STATUS", "The server omitted its retry interval")
                    val bytes = if (response.code == 503) response.body?.byteStream()?.let { readAtMost(it, MAX_ERROR_BODY_BYTES) } ?: ByteArray(0) else ByteArray(0)
                    val envelope = runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }.getOrNull()
                    val code = envelope?.optString("code")?.takeIf { it.isNotEmpty() }
                        ?: if (response.code == 202) "DOWNLOAD_PREPARING" else "DOWNLOAD_PREPARATION_BUSY"
                    throw NativeApiException(response.code, code, publicError(code, response.code), retryAfter)
                }
                if (response.code == 416) throw NativeApiException(response.code, "SOURCE_CHANGED", "The requested file range is no longer available")
                if (response.code == 401) {
                    val bytes = response.body?.byteStream()?.let { readAtMost(it, MAX_ERROR_BODY_BYTES) } ?: ByteArray(0)
                    val envelope = runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }.getOrNull()
                    val code = envelope?.optString("code")?.takeIf { it.isNotEmpty() }
                        ?: response.header("Nascraft-Auth-Error")?.takeIf { it.matches(API_CODE) }
                    handleAuthorizationFailure(response.code, code, request.header("Nascraft-Session"), request.header("Authorization")?.removePrefix("Bearer "))
                    throw NativeApiException(response.code, code, publicError(code, response.code))
                }
                if (response.code != 206) {
                    throw NativeApiException(response.code, "BAD_DOWNLOAD_STATUS", "The server returned an unsupported ranged response")
                }
                val actualMetadata = parseDownloadMetadata(response, profile.serverId, metadata.fileId, metadata.totalSize)
                if (actualMetadata != metadata) throw NativeApiException(response.code, "SOURCE_CHANGED", "The file or account authorization changed during download")
                val expectedContentRange = "bytes $start-$endInclusive/${metadata.totalSize}"
                val contentRange = response.header("Content-Range")
                if (contentRange != expectedContentRange) throw NativeApiException(response.code, "BAD_CONTENT_RANGE", "The server returned a different byte range than requested")
                val expectedLength = endInclusive - start + 1
                val declaredLength = response.header("Content-Length")?.toLongOrNull()
                if (declaredLength != expectedLength || response.body?.contentLength() != expectedLength) {
                    throw NativeApiException(response.code, "DOWNLOAD_SIZE_MISMATCH", "The server returned an incomplete byte range")
                }
                val body = response.body ?: throw NativeApiException(response.code, "BAD_RESPONSE", "The server returned no file data")
                val written = try {
                    body.byteStream().use { NativeDownloadStream.copyExactly(it, output, expectedLength) }
                } catch (failure: IllegalStateException) {
                    throw NativeApiException(response.code, "DOWNLOAD_SIZE_MISMATCH", "The downloaded range was incomplete")
                }
                NativeDownloadReceipt(
                    statusCode = response.code,
                    contentType = response.header("Content-Type"),
                    bytesWritten = written,
                    contentRange = contentRange,
                    acceptRanges = response.header("Accept-Ranges"),
                    metadata = actualMetadata,
                    start = start,
                    endInclusive = endInclusive,
                    totalSize = metadata.totalSize,
                )
            }
        } finally {
            activeDownloadCalls.remove(operationId, call)
        }
    }

    fun cancelBusinessDownload(operationId: String) { activeDownloadCalls[operationId]?.cancel() }

    private suspend fun buildSignedBusinessRequest(
        profile: ServerProfile,
        realm: ApiRealm,
        method: String,
        pathAndQuery: String,
        body: ByteArray,
        contentType: String?,
        additionalHeaders: Map<String, String>,
    ): Request {
        NativeBusinessTarget.validate(method, pathAndQuery)
        NativeBusinessTarget.validateAdditionalHeaders(additionalHeaders)
        require(method !in setOf("GET", "HEAD") || body.isEmpty()) {
            "GET and HEAD requests cannot carry a signed body"
        }
        val origin = HttpMessageSigner.requireExactHttpsOrigin(profile.apiOrigin(realm))
        val proof = sessionProofWithNonce(profile, realm)
        val builder = Request.Builder().url(origin + pathAndQuery)
            .header("Accept", "application/json, image/*, video/*, application/octet-stream")
        additionalHeaders.forEach { (name, value) -> builder.header(name, value) }
        val requestBody = if (method == "GET" || method == "HEAD") null else {
            body.toRequestBody((contentType ?: "application/octet-stream").toMediaType())
        }
        builder.method(method, requestBody)
        // Sign the caller's origin-form bytes exactly; OkHttp's final URL must
        // remain byte-for-byte equivalent or this request is rejected below.
        val rawTarget = pathAndQuery
        val now = TimestampCodec.nowEpochSeconds()
        val coveredHeaders = additionalHeaders.entries.associate { it.key.lowercase() to it.value }
        val signed = HttpMessageSigner.sign(
            method = method,
            configuredOrigin = origin,
            rawPathAndQuery = rawTarget,
            body = if (requestBody == null) ByteArray(0) else body,
            sessionMarker = proof.sessionMarker,
            nonce = proof.nonce,
            keyId = proof.keyId,
            createdEpochSeconds = now,
            expiresEpochSeconds = now + 30,
            coveredHeaders = coveredHeaders,
        ) { canonical -> requestSigner(proof.keyAlias, canonical) }
        builder.header("Content-Digest", signed.contentDigest)
            .header("Nascraft-Session", proof.sessionMarker)
            .header("Nascraft-Nonce", proof.nonce)
            .header("Signature-Input", signed.signatureInput)
            .header("Signature", signed.signature)
        proof.accessToken?.let { builder.header("Authorization", "Bearer $it") }
        val request = builder.build()
        check(origin + encodedPathAndQuery(request) == signed.targetUri) {
            "Signed target does not match the configured server origin"
        }
        return request
    }

    private fun rangeMatches(requested: String, response: ParsedContentRange, total: Long): Boolean {
        val match = Regex("bytes=([0-9]+)-([0-9]*)").matchEntire(requested) ?: return false
        val start = match.groupValues[1].toLongOrNull() ?: return false
        val requestedEnd = match.groupValues[2].takeIf { it.isNotEmpty() }?.toLongOrNull() ?: (total - 1)
        return start == response.start && minOf(requestedEnd, total - 1) == response.endInclusive
    }

    private fun parseDownloadMetadata(
        response: okhttp3.Response,
        expectedServerId: String,
        expectedFileId: String,
        expectedTotalSize: Long? = null,
    ): DownloadFileMetadata {
        val etag = response.header("ETag") ?: throw NativeApiException(response.code, "BAD_DOWNLOAD_METADATA", "The server omitted the file ETag")
        val etagHash = Regex("\"sha256-([0-9a-f]{64})\"").matchEntire(etag)?.groupValues?.get(1)
            ?: throw NativeApiException(response.code, "BAD_DOWNLOAD_METADATA", "The server returned an invalid file ETag")
        val sha256 = response.header("Nascraft-File-Sha256")
            ?: throw NativeApiException(response.code, "BAD_DOWNLOAD_METADATA", "The server omitted the file digest")
        val generation = response.header("Nascraft-Authorization-Generation")
            ?: throw NativeApiException(response.code, "BAD_DOWNLOAD_METADATA", "The server omitted the authorization generation")
        val serverId = response.header("Nascraft-Server-Id")
            ?: throw NativeApiException(response.code, "BAD_DOWNLOAD_METADATA", "The server omitted its identity")
        val fileId = response.header("Nascraft-File-Id")
            ?: throw NativeApiException(response.code, "BAD_DOWNLOAD_METADATA", "The server omitted the file identity")
        val length = expectedTotalSize ?: response.header("Content-Length")?.toLongOrNull()
            ?: throw NativeApiException(response.code, "BAD_DOWNLOAD_METADATA", "The server returned an invalid file length")
        if (response.header("Accept-Ranges") != "bytes" || etagHash != sha256 ||
            serverId != expectedServerId || fileId != expectedFileId) {
            throw NativeApiException(response.code, "SOURCE_CHANGED", "The server returned a different file or server identity")
        }
        val metadata = DownloadFileMetadata(length, etag, sha256, generation, serverId, fileId)
        if (!metadata.isValid()) throw NativeApiException(response.code, "BAD_DOWNLOAD_METADATA", "The server returned invalid file metadata")
        return metadata
    }

    private fun downloadFileId(pathAndQuery: String): String =
        Regex("/api/download/([A-Za-z0-9_-]{8,160})(?:\\?.*)?").matchEntire(pathAndQuery)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("Download path does not contain a safe file identifier")

    private fun retryAfterSeconds(value: String?): Long? = value
        ?.takeIf { RETRY_AFTER_SECONDS.matches(it) }
        ?.toLongOrNull()

    private fun readAtMost(input: java.io.InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(limit, 8192))
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > limit) return output.toByteArray() + ByteArray(1)
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private suspend fun sessionProofWithNonce(profile: ServerProfile, realm: ApiRealm): NativeProof =
        sessionNonceLock.withLock {
            val snapshot = repository.snapshot()
            val session = snapshot.session ?: throw NativeApiException(null, "AUTH_REQUIRED", "Sign in to continue")
            val alias = snapshot.installationKeyAlias
                ?: throw NativeApiException(null, "AUTH_REQUIRED", "This device needs to sign in again")
            if (session.serverId != profile.serverId || snapshot.selectedServerId != profile.serverId) {
                throw ServerIdentityMismatchException("The selected server identity does not match this session")
            }
            val now = TimestampCodec.nowEpochSeconds()
            val available = repository.snapshot().nonces(realm, "session", session.sessionId)
                .count { it.expiresAtEpochSeconds > now }
            if (available <= 2) {
                requestSessionNonces(profile, realm, 16)
            }
            val nonce = repository.takeNonce(realm, "session", session.sessionId, now)
                ?: throw NativeApiException(null, "NONCE_REQUIRED", "The secure connection could not renew its request challenge")
            NativeProof(alias, session.deviceId, session.sessionId, nonce.value, session.accessToken)
        }

    private fun clearRevokedInstallation() {
        val snapshot = repository.snapshot()
        repository.clearRevokedInstallation()
        listOfNotNull(snapshot.installationKeyAlias, snapshot.pendingFlow?.keyAlias)
            .distinct()
            .forEach { alias -> runCatching { installationKeys.delete(alias) } }
    }

    suspend fun requestPreAuthNonces(
        profile: ServerProfile,
        realm: ApiRealm,
        purpose: String,
        count: Int,
        installationPublicKey: String? = null,
        deviceId: String? = null,
        registrationId: String? = null,
        bindingId: String,
        pairingRequestId: String? = null,
    ): List<StoredNonce> {
        require(purpose in NONCE_PURPOSES && count in 1..32)
        require(listOf(installationPublicKey, deviceId, registrationId).count { it != null } == 1) {
            "Exactly one pre-authentication identity is required"
        }
        val body = JSONObject().put("purpose", purpose).put("count", count)
        installationPublicKey?.let { body.put("installation_public_key", it) }
        deviceId?.let { body.put("device_id", it) }
        registrationId?.let { body.put("registration_id", it) }
        pairingRequestId?.let { body.put("pairing_request_id", it) }
        require((pairingRequestId == null || (purpose == "claim" && installationPublicKey != null)) &&
            (purpose != "claim" || (pairingRequestId != null && bindingId == "pair:$pairingRequestId"))) {
            "Pairing claim nonces must be bound to the matching request"
        }
        when (purpose) {
            "pair" -> require(installationPublicKey != null && bindingId == "preauth:pair")
            "login" -> if (deviceId != null) require(bindingId == "preauth:login:$deviceId")
            "register" -> require(registrationId != null && bindingId == registrationId)
        }
        repository.clearNonces(realm, purpose, bindingId)
        val data = postUnsigned(profile, realm, "${realm.routePrefix}/auth/nonces", body)
        verifyRealmOrigin(data, profile, realm)
        if (data.optString("realm") != realm.wireName) {
            throw ServerIdentityMismatchException("The selected server returned a challenge for another network realm")
        }
        return parseNonces(data.optJSONArray("nonces") ?: JSONArray(), purpose, bindingId).also { values ->
            if (values.size != count) throw NativeApiException(null, "BAD_NONCE_BATCH", "The server returned an incomplete request challenge")
            repository.appendNonces(realm, values, profile.serverId)
        }
    }

    suspend fun requestSessionNonces(profile: ServerProfile, realm: ApiRealm, count: Int = 16): List<StoredNonce> {
        require(count in 1..32)
        val snapshot = repository.snapshot()
        val session = snapshot.session ?: throw NativeApiException(null, "AUTH_REQUIRED", "Sign in to continue")
        val keyAlias = snapshot.installationKeyAlias ?: throw NativeApiException(null, "AUTH_REQUIRED", "This device needs to sign in again")
        if (session.serverId != profile.serverId) throw ServerIdentityMismatchException("Session belongs to another server")
        val refreshToken = session.refreshToken
        if (refreshToken == null && runCatching { TimestampCodec.parseEpochSeconds(session.accessExpiresAt) }
                .getOrDefault(0L) <= TimestampCodec.nowEpochSeconds()) {
            repository.clearSession()
            throw NativeApiException(null, "SESSION_EXPIRED", "This temporary connection expired; sign in again")
        }
        val nonce = repository.takeNonce(realm, "session", session.sessionId, TimestampCodec.nowEpochSeconds())
            ?: requestSessionNonceChallenge(profile, realm, session)
        val body = JSONObject()
            .put("device_id", session.deviceId)
            .put("session_id", session.sessionId)
            .put("purpose", "session")
            .put("count", count)
        refreshToken?.let { body.put("refresh_token", it) }
        val data = postSigned(
            profile,
            realm,
            "${realm.routePrefix}/auth/nonces",
            body,
            NativeProof(
                keyAlias,
                session.deviceId,
                session.sessionId,
                nonce.value,
                accessToken = if (refreshToken == null) session.accessToken else null,
            ),
        )
        verifyRealmOrigin(data, profile, realm, requireRealm = true)
        return parseNonces(data.optJSONArray("nonces") ?: JSONArray(), "session", session.sessionId).also { values ->
            if (values.size != count) {
                throw NativeApiException(null, "BAD_NONCE_BATCH", "The server returned an incomplete request challenge")
            }
            repository.appendNonces(realm, values, profile.serverId)
        }
    }

    /** Confirms the pinned LAN API identifies this server and endorses the exact requested remote origin. */
    suspend fun verifyTrustedLanIdentity(profile: ServerProfile, expectedRemoteApiOrigin: String): String {
        val snapshot = repository.snapshot()
        val session = snapshot.session
            ?: throw NativeApiException(null, "AUTH_REQUIRED", "Sign in through the configured LAN server before changing its remote address")
        if (snapshot.profile() != profile || session.serverId != profile.serverId || profile.serverId != snapshot.selectedServerId) {
            throw ServerIdentityMismatchException("Select this server and verify its LAN connection before changing the remote address")
        }
        // The fixed LAN hostname is already TLS-pinned. The LAN-only metadata route
        // must endorse the exact remote origin before the local profile can change.
        val data = getUnsigned(profile, ApiRealm.LAN_NATIVE, "/api/lan/server-profile")
        return validateEndorsedRemoteOrigin(data, profile, expectedRemoteApiOrigin)
    }

    fun validateEndorsedRemoteOrigin(
        data: JSONObject,
        profile: ServerProfile,
        expectedRemoteApiOrigin: String? = null,
    ): String {
        if (data.optString("format") != ServerProfile.QR_FORMAT ||
            requiredString(data, "server_id") != profile.serverId) {
            throw ServerIdentityMismatchException("The LAN server profile does not match the selected server")
        }
        if (!data.has("configured_remote_api_origin")) {
            throw ServerIdentityMismatchException("The LAN server did not confirm its configured Tailscale API origin")
        }
        if (!data.has("remote_api_origin")) {
            throw ServerIdentityMismatchException("The LAN server profile omitted its compatibility remote origin")
        }
        val configuredRemoteOrigin = optionalString(data, "configured_remote_api_origin").orEmpty()
        val compatibilityRemoteOrigin = optionalString(data, "remote_api_origin").orEmpty()
        return try {
            val normalized = ServerProfile.validated(
                serverId = profile.serverId,
                displayName = profile.displayName,
                rpId = requiredString(data, "rp_id"),
                lanWebUrl = requiredString(data, "lan_web_url"),
                lanApiOrigin = requiredString(data, "lan_api_origin"),
                remoteApiOrigin = configuredRemoteOrigin,
            )
            val compatibility = ServerProfile.validated(
                serverId = profile.serverId,
                displayName = profile.displayName,
                rpId = profile.rpId,
                lanWebUrl = profile.lanWebUrl,
                lanApiOrigin = profile.lanApiOrigin,
                remoteApiOrigin = compatibilityRemoteOrigin,
            ).remoteApiOrigin
            if (normalized.rpId != profile.rpId || normalized.lanWebUrl != profile.lanWebUrl ||
                normalized.lanApiOrigin != profile.lanApiOrigin ||
                normalized.remoteApiOrigin != compatibility || configuredRemoteOrigin != normalized.remoteApiOrigin) {
                throw ServerIdentityMismatchException("The LAN server profile changed a pinned origin or returned a noncanonical remote origin")
            }
            if (expectedRemoteApiOrigin != null && normalized.remoteApiOrigin != expectedRemoteApiOrigin) {
                throw ServerIdentityMismatchException("The LAN server has not configured the requested Tailscale API origin")
            }
            normalized.remoteApiOrigin
        } catch (_: IllegalArgumentException) {
            throw ServerIdentityMismatchException("The LAN server returned an invalid configured Tailscale API origin")
        }
    }

    private suspend fun requestSessionNonceChallenge(
        profile: ServerProfile,
        realm: ApiRealm,
        session: NativeSessionState,
    ): StoredNonce {
        val data = requestSessionNonceChallengeData(profile, realm, session)
        val value = data.optString("nonce")
        if (!NONCE.matches(value)) throw NativeApiException(null, "BAD_NONCE_BATCH", "The server returned an invalid request challenge")
        val expiresAt = parseEpoch(data, "expires_at")
        if (expiresAt <= TimestampCodec.nowEpochSeconds()) {
            throw NativeApiException(null, "BAD_NONCE_BATCH", "The server returned an expired request challenge")
        }
        return StoredNonce(value, expiresAt, "session", session.sessionId)
    }

    private suspend fun requestSessionNonceChallengeData(
        profile: ServerProfile,
        realm: ApiRealm,
        session: NativeSessionState,
    ): JSONObject {
        val data = postUnsigned(
            profile,
            realm,
            "${realm.routePrefix}/auth/nonce-challenge",
            JSONObject().put("device_id", session.deviceId).put("session_id", session.sessionId),
        )
        verifyRealmOrigin(data, profile, realm, requireRealm = true)
        val value = data.optString("nonce")
        if (!NONCE.matches(value)) throw NativeApiException(null, "BAD_NONCE_BATCH", "The server returned an invalid request challenge")
        val expiresAt = parseEpoch(data, "expires_at")
        if (expiresAt <= TimestampCodec.nowEpochSeconds()) {
            throw NativeApiException(null, "BAD_NONCE_BATCH", "The server returned an expired request challenge")
        }
        return data
    }

    fun nonceFromChallenge(
        data: JSONObject,
        profile: ServerProfile,
        realm: ApiRealm,
        purpose: String,
        bindingId: String,
        valueField: String = "nonce",
    ): StoredNonce {
        verifyRealmOrigin(data, profile, realm)
        if (data.optString("realm") != realm.wireName) {
            throw ServerIdentityMismatchException("The selected server returned a challenge for another network realm")
        }
        return StoredNonce(requiredString(data, valueField), parseEpoch(data, "expires_at"), purpose, bindingId)
    }

    fun registrationNonceFromChallenge(data: JSONObject, profile: ServerProfile): StoredNonce {
        verifyRealmOrigin(data, profile, ApiRealm.REMOTE_API)
        if (data.optString("realm") != "remote_api") {
            throw ServerIdentityMismatchException("The remote server returned a challenge for another network realm")
        }
        return StoredNonce(requiredString(data, "registration_nonce"), parseEpoch(data, "expires_at"), "otp", "preauth:otp")
    }

    fun validateResponseIdentity(
        data: JSONObject,
        profile: ServerProfile,
        realm: ApiRealm,
        requireRealm: Boolean = false,
    ) {
        verifyRealmOrigin(data, profile, realm, requireRealm)
    }

    fun nonceBatch(data: JSONObject, field: String = "nonces", purpose: String, bindingId: String): List<StoredNonce> =
        parseNonces(data.optJSONArray(field) ?: JSONArray(), purpose, bindingId)

    fun expirationEpochSeconds(data: JSONObject, field: String = "expires_at"): Long =
        parseEpoch(data, field)

    fun authorizedMode(data: JSONObject): String = parseMode(requiredString(data, "mode"))

    fun sessionFromResponse(
        data: JSONObject,
        profile: ServerProfile,
        realm: ApiRealm,
        expectedMode: String? = null,
    ): NativeSessionState {
        verifyRealmOrigin(data, profile, realm, requireRealm = true)
        val session = data.getJSONObject("session")
        val user = data.getJSONObject("user")
        val device = data.getJSONObject("device")
        val responseMode = parseMode(requiredString(data, "mode"))
        val deviceMode = parseMode(requiredString(device, "mode"))
        if (responseMode != deviceMode || (expectedMode != null && responseMode != parseMode(expectedMode))) {
            throw NativeApiException(null, "BAD_RESPONSE", "The server returned inconsistent device authorization mode")
        }
        val refreshToken = optionalString(session, "refresh_token")
        val refreshExpiresAt = optionalEpoch(session, "refresh_expires_at")
        if ((refreshToken == null) != (refreshExpiresAt == null) ||
            (refreshToken != null && refreshToken.isBlank()) ||
            (deviceMode == "personal" && refreshToken == null) ||
            (deviceMode == "temporary" && refreshToken != null)) {
            throw NativeApiException(null, "BAD_RESPONSE", "The server returned incomplete refresh credentials")
        }
        return NativeSessionState(
            serverId = profile.serverId,
            userId = requiredString(user, "id"),
            userDisplayName = requiredString(user, "display_name"),
            userRole = requiredString(user, "role"),
            deviceId = requiredString(device, "id"),
            deviceName = optionalString(device, "display_name") ?: "This device",
            sessionId = requiredString(session, "id"),
            accessToken = requiredString(session, "access_token"),
            accessExpiresAt = parseEpoch(session, "access_expires_at").toString(),
            refreshToken = refreshToken,
            refreshExpiresAt = refreshExpiresAt?.toString(),
            deviceNonce = requiredString(session, "device_nonce"),
            deviceMode = deviceMode,
        )
    }

    fun sessionNonces(
        data: JSONObject,
        profile: ServerProfile,
        activeRealm: ApiRealm,
    ): Pair<List<StoredNonce>, List<StoredNonce>> {
        if (activeRealm == ApiRealm.REMOTE_API && !profile.hasRemoteApiOrigin()) {
            throw NativeApiException(null, "REMOTE_API_NOT_CONFIGURED", "Configure the Tailscale HTTPS API origin before using remote access")
        }
        val nonces = data.getJSONObject("nonces")
        val sessionId = requiredString(data.getJSONObject("session"), "id")
        val lan = parseNonces(nonceArray(nonces, "lan_native"), "session", sessionId)
        val receivedRemote = parseNonces(nonceArray(nonces, "remote_api"), "session", sessionId)
        // Remote nonces are unusable until the user pins the remote origin. Do not retain
        // a batch issued before that explicit pin confirmation.
        val remote = if (profile.hasRemoteApiOrigin()) receivedRemote else emptyList()
        return lan to remote
    }

    fun sessionFromRefreshResponse(
        data: JSONObject,
        current: NativeSessionState,
        profile: ServerProfile,
        realm: ApiRealm,
    ): NativeSessionState {
        verifyRealmOrigin(data, profile, realm, requireRealm = true)
        val session = data.getJSONObject("session")
        val responseMode = parseMode(requiredString(data, "mode"))
        val deviceMode = parseMode(requiredString(data.getJSONObject("device"), "mode"))
        if (responseMode != current.deviceMode || deviceMode != current.deviceMode) {
            throw NativeApiException(null, "BAD_RESPONSE", "The server returned a different device authorization mode")
        }
        val refreshToken = optionalString(session, "refresh_token")
        val refreshExpiresAt = optionalEpoch(session, "refresh_expires_at")
        if (refreshToken.isNullOrBlank() || refreshExpiresAt == null || current.deviceMode != "personal") {
            throw NativeApiException(null, "BAD_RESPONSE", "The server did not rotate the personal refresh credentials")
        }
        return current.copy(
            sessionId = requiredString(session, "id"),
            accessToken = requiredString(session, "access_token"),
            accessExpiresAt = parseEpoch(session, "access_expires_at").toString(),
            refreshToken = refreshToken,
            refreshExpiresAt = refreshExpiresAt.toString(),
            deviceNonce = requiredString(session, "device_nonce"),
            pendingRefreshRequestId = null,
        )
    }

    fun registrationNonces(data: JSONObject): List<StoredNonce> {
        val nonces = data.getJSONObject("nonces").getJSONArray("remote_api")
        val values = parseNonces(nonces, "register", data.getString("registration_id"))
        if (values.isEmpty()) throw NativeApiException(null, "BAD_NONCE_BATCH", "The server returned no registration challenges")
        return values
    }

    private fun execute(
        profile: ServerProfile,
        realm: ApiRealm,
        method: String,
        path: String,
        bodyText: String?,
        proof: NativeProof?,
    ): JSONObject {
        val origin = HttpMessageSigner.requireExactHttpsOrigin(profile.apiOrigin(realm))
        require((path.startsWith(realm.routePrefix) ||
            (realm == ApiRealm.LAN_NATIVE && path == "/api/lan/server-profile")) &&
            path.startsWith('/') && !path.contains('#')) {
            "Request path does not belong to selected API realm"
        }
        val bodyBytes = bodyText?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        val url = origin + path
        val body = bodyText?.toRequestBody(JSON_MEDIA_TYPE)
        val builder = Request.Builder().url(url).header("Accept", "application/json")
        if (method == "GET") builder.get() else builder.post(body ?: ByteArray(0).toRequestBody(JSON_MEDIA_TYPE))
        val unsignedRequest = builder.build()
        val rawTarget = encodedPathAndQuery(unsignedRequest)

        var signedTargetUri: String? = null
        if (proof != null) {
            val now = TimestampCodec.nowEpochSeconds()
            val signed = HttpMessageSigner.sign(
                method = method,
                configuredOrigin = origin,
                rawPathAndQuery = rawTarget,
                body = bodyBytes,
                sessionMarker = proof.sessionMarker,
                nonce = proof.nonce,
                keyId = proof.keyId,
                createdEpochSeconds = now,
                expiresEpochSeconds = now + 30,
            ) { canonical -> requestSigner(proof.keyAlias, canonical) }
            signedTargetUri = signed.targetUri
            builder.header("Content-Digest", signed.contentDigest)
                .header("Nascraft-Session", proof.sessionMarker)
                .header("Nascraft-Nonce", proof.nonce)
                .header("Signature-Input", signed.signatureInput)
                .header("Signature", signed.signature)
            proof.accessToken?.let { token -> builder.header("Authorization", "Bearer $token") }
        }

        val request = builder.build()
        val actualTarget = origin + encodedPathAndQuery(request)
        if (signedTargetUri != null) check(actualTarget == signedTargetUri) {
            "Signed target does not match the configured server origin"
        }

        return client.newCall(request).execute().use { response ->
            val responseLimit = if (response.isSuccessful) MAX_JSON_RESPONSE_BYTES else MAX_ERROR_BODY_BYTES
            val rawBytes = response.body?.byteStream()?.let { readAtMost(it, responseLimit) } ?: ByteArray(0)
            if (rawBytes.size > responseLimit) {
                handleAuthorizationFailure(response.code, null, request.header("Nascraft-Session"), request.header("Authorization")?.removePrefix("Bearer "))
                throw NativeApiException(response.code, "RESPONSE_TOO_LARGE", "The NASCraft server returned an oversized response")
            }
            val raw = rawBytes.toString(Charsets.UTF_8)
            val envelope = try { JSONObject(raw) } catch (_: Exception) {
                handleAuthorizationFailure(response.code, null, request.header("Nascraft-Session"), request.header("Authorization")?.removePrefix("Bearer "))
                throw NativeApiException(response.code, null, "The NASCraft server returned an invalid response")
            }
            val success = response.isSuccessful && envelope.optInt("status", 0) == 1 && envelope.optString("code") == "0"
            if (!success) {
                val code = envelope.optString("code").takeIf { it.isNotEmpty() }
                handleAuthorizationFailure(response.code, code, request.header("Nascraft-Session"), request.header("Authorization")?.removePrefix("Bearer "))
                throw NativeApiException(response.code, code, publicError(code, response.code))
            }
            envelope.optJSONObject("data") ?: throw NativeApiException(response.code, "BAD_RESPONSE", "The server returned an incomplete response")
        }
    }

    private fun handleAuthorizationFailure(
        httpStatus: Int,
        code: String?,
        expectedSessionId: String? = null,
        expectedAccessToken: String? = null,
    ) {
        if (code == "ACCESS_TOKEN_EXPIRED") return
        val installationRevoked = code != null && code in INSTALLATION_FAILURE_CODES
        val sessionFailure = code != null && code in SESSION_FAILURE_CODES
        if (!sessionFailure && !installationRevoked) return
        val current = repository.snapshot().session ?: return
        if (expectedSessionId == null || expectedSessionId.startsWith("preauth:") || current.sessionId != expectedSessionId) return
        if (installationRevoked) {
            clearRevokedInstallation()
        } else {
            // A revoked/expired session must stop protected work, but retaining the
            // installation key permits a fresh Passkey login on an authorized device.
            repository.clearSession()
        }
    }

    private fun verifyRealmOrigin(
        data: JSONObject,
        profile: ServerProfile,
        realm: ApiRealm,
        requireRealm: Boolean = false,
    ) {
        val serverId = data.optString("server_id")
        val responseRealm = data.optString("realm")
        val responseOrigin = data.optString("api_origin")
        if (serverId != profile.serverId || (requireRealm && responseRealm != realm.wireName) ||
            (responseRealm.isNotEmpty() && responseRealm != realm.wireName) ||
            responseOrigin.isEmpty() || HttpMessageSigner.requireExactHttpsOrigin(responseOrigin) != HttpMessageSigner.requireExactHttpsOrigin(profile.apiOrigin(realm))) {
            throw ServerIdentityMismatchException("The selected server identity does not match its configured profile")
        }
    }

    private fun parseNonces(values: JSONArray, purpose: String, bindingId: String): List<StoredNonce> = buildList {
        if (values.length() > 32) throw NativeApiException(null, "BAD_NONCE_BATCH", "The server returned too many request challenges")
        for (index in 0 until values.length()) {
            val item = values.optJSONObject(index) ?: throw NativeApiException(null, "BAD_NONCE_BATCH", "The server returned an invalid request challenge")
            val value = item.optString("value")
            if (!NONCE.matches(value)) throw NativeApiException(null, "BAD_NONCE_BATCH", "The server returned an invalid request challenge")
            val expiresAt = parseEpoch(item, "expires_at")
            if (expiresAt <= TimestampCodec.nowEpochSeconds()) {
                throw NativeApiException(null, "BAD_NONCE_BATCH", "The server returned an expired request challenge")
            }
            if (any { stored -> stored.value == value }) {
                throw NativeApiException(null, "BAD_NONCE_BATCH", "The server returned a duplicate request challenge")
            }
            add(StoredNonce(value, expiresAt, purpose, bindingId))
        }
    }

    private fun parseEpoch(source: JSONObject, field: String): Long = try {
        val value = source.opt(field) ?: throw IllegalArgumentException("Missing timestamp")
        val seconds = when (value) {
            is Byte, is Short, is Int, is Long -> (value as Number).toLong()
            is java.math.BigInteger -> value.longValueExact()
            is String -> {
                require(!value.matches(Regex("[+-]?[0-9]+"))) { "Timestamp strings must use RFC 3339" }
                TimestampCodec.parseEpochSeconds(value)
            }
            else -> throw IllegalArgumentException("Timestamp must be integer seconds or RFC 3339")
        }
        require(seconds >= 0) { "Timestamp cannot be negative" }
        seconds
    } catch (_: Exception) {
        throw NativeApiException(null, "BAD_RESPONSE", "The server returned an invalid expiration time")
    }

    private fun optionalEpoch(source: JSONObject, field: String): Long? =
        if (!source.has(field) || source.isNull(field)) null else parseEpoch(source, field)

    private fun optionalString(source: JSONObject, field: String): String? {
        if (!source.has(field) || source.isNull(field)) return null
        return source.opt(field) as? String
            ?: throw NativeApiException(null, "BAD_RESPONSE", "The server returned an invalid $field field")
    }

    private fun requiredString(source: JSONObject, field: String): String =
        optionalString(source, field)?.takeIf { it.isNotEmpty() }
            ?: throw NativeApiException(null, "BAD_RESPONSE", "The server returned an incomplete $field field")

    private fun nonceArray(source: JSONObject, field: String): JSONArray {
        if (!source.has(field) || source.isNull(field)) return JSONArray()
        return source.opt(field) as? JSONArray
            ?: throw NativeApiException(null, "BAD_NONCE_BATCH", "The server returned an invalid $field nonce pool")
    }

    private fun parseMode(value: String): String {
        if (value != "personal" && value != "temporary") {
            throw NativeApiException(null, "BAD_RESPONSE", "The server returned an invalid device authorization mode")
        }
        return value
    }

    private fun encodedPathAndQuery(request: Request): String {
        val url = request.url
        return url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")
    }

    private fun publicError(code: String?, httpStatus: Int): String = when (code) {
        "AUTH_NOT_AVAILABLE", "AUTH_REQUIRED", "AUTH_REVOKED", "DEVICE_REVOKED", "ACCESS_TOKEN_EXPIRED" -> "This device needs to reconnect or sign in again"
        "OTP_INVALID", "OTP_EXPIRED", "OTP_REVOKED" -> "That one-time code is unavailable. Check it or ask the LAN administrator for a new code"
        "DOWNLOAD_PREPARATION_BUSY", "DOWNLOAD_PREPARING" -> "The server is preparing this file"
        "SOURCE_CHANGED" -> "The file or account authorization changed. Restart this download to continue"
        else -> if (httpStatus in 300..399) "The NASCraft server redirected this secure request" else "The NASCraft request could not be completed"
    }

    companion object {
        private const val MAX_ERROR_BODY_BYTES = 64 * 1024
        private const val MAX_JSON_RESPONSE_BYTES = 2 * 1024 * 1024
        private const val MAX_PREVIEW_RESPONSE_BYTES = 16 * 1024 * 1024
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val NONCE = Regex("[A-Za-z0-9_-]{16,512}")
        private val API_CODE = Regex("[A-Z][A-Z0-9_]{1,63}")
        private val RETRY_AFTER_SECONDS = Regex("[0-9]{1,19}")
        private val NONCE_PURPOSES = setOf("pair", "otp", "register", "login", "claim", "session")
        private val SESSION_FAILURE_CODES = setOf(
            "AUTH_NOT_AVAILABLE", "AUTH_REQUIRED", "AUTH_REVOKED", "SESSION_INVALID", "SESSION_EXPIRED", "SESSION_REVOKED", "ACCOUNT_DISABLED",
        )
        private val INSTALLATION_FAILURE_CODES = setOf("DEVICE_REVOKED", "INSTALLATION_REVOKED")
    }
}

private val ApiRealm.wireName: String
    get() = when (this) {
        ApiRealm.LAN_NATIVE -> "lan_native"
        ApiRealm.REMOTE_API -> "remote_api"
    }
