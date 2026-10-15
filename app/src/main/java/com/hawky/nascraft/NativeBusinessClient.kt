package com.hawky.nascraft

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.OutputStream

/** Adds proactive refresh and a single signed path for JSON, upload, preview and download calls. */
class NativeBusinessClient(
    private val api: NativeAuthApi,
    private val repository: DeviceAuthRepository,
    private val refreshSession: suspend (ServerProfile, ApiRealm) -> NativeSessionState,
    private val onSessionInvalidated: () -> Unit = {},
    private val onSessionRevoked: (DownloadAuthorizationIdentity) -> Unit = {},
) : DownloadResumeTransport {
    private val refreshLock = Mutex()
    @Volatile private var selectedRealm: ApiRealm = ApiRealm.LAN_NATIVE
    private val mutableAuthEvents = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val authEvents: kotlinx.coroutines.flow.StateFlow<Long> = mutableAuthEvents

    fun setRealm(realm: ApiRealm) {
        selectedRealm = if (isRealmConfigured(realm)) realm else ApiRealm.LAN_NATIVE
    }

    fun isRealmConfigured(realm: ApiRealm): Boolean =
        repository.snapshot().profile()?.let { profile ->
            realm == ApiRealm.LAN_NATIVE || profile.hasRemoteApiOrigin()
        } == true

    fun currentRealm(): ApiRealm = selectedRealm

    fun hasSelectedProfile(): Boolean = repository.snapshot().profile() != null

    fun hasAuthenticatedSession(): Boolean {
        val state = repository.snapshot()
        return state.profile()?.serverId?.let { it == state.session?.serverId } == true &&
            state.installationKeyAlias != null
    }

    fun downloadIdentityOrNull(): DownloadAuthorizationIdentity? {
        val snapshot = repository.snapshot()
        val session = snapshot.session ?: return null
        if (snapshot.profile()?.serverId != session.serverId || snapshot.selectedServerId != session.serverId) return null
        return DownloadAuthorizationIdentity(session.serverId, session.userId, session.deviceId)
    }

    override suspend fun headDownload(pathAndQuery: String, operationId: String): DownloadHeadResult {
        val snapshot = repository.snapshot()
        val profile = snapshot.profile() ?: throw NativeApiException(null, "PROFILE_REQUIRED", "Configure a server before downloading")
        if (snapshot.session?.serverId != profile.serverId) throw ServerIdentityMismatchException("Sign in to the selected server before downloading")
        return try {
            authorizedRetry(profile, selectedRealm) { api.businessDownloadHead(profile, selectedRealm, pathAndQuery, operationId) }
        } catch (failure: Exception) {
            notifyIfSessionInvalidated()
            throw failure
        }
    }

    override suspend fun downloadRange(
        pathAndQuery: String,
        metadata: DownloadFileMetadata,
        start: Long,
        endInclusive: Long,
        output: OutputStream,
        operationId: String,
    ): DownloadSegmentReceipt {
        val snapshot = repository.snapshot()
        val profile = snapshot.profile() ?: throw NativeApiException(null, "PROFILE_REQUIRED", "Configure a server before downloading")
        if (snapshot.session?.serverId != profile.serverId) throw ServerIdentityMismatchException("Sign in to the selected server before downloading")
        return try {
            val receipt = authorizedRetry(profile, selectedRealm) {
                api.businessDownloadRangeTo(profile, selectedRealm, pathAndQuery, metadata, start, endInclusive, output, operationId)
            }
            DownloadSegmentReceipt(
                statusCode = receipt.statusCode,
                start = receipt.start ?: start,
                endInclusive = receipt.endInclusive ?: endInclusive,
                totalSize = receipt.totalSize ?: metadata.totalSize,
                bytesWritten = receipt.bytesWritten,
                metadata = receipt.metadata ?: throw NativeApiException(receipt.statusCode, "BAD_DOWNLOAD_METADATA", "The server omitted file identity headers"),
            )
        } catch (failure: Exception) {
            notifyIfSessionInvalidated()
            throw failure
        }
    }

    override fun cancelDownload(operationId: String) { api.cancelBusinessDownload(operationId) }

    suspend fun requestSelected(
        method: String,
        pathAndQuery: String,
        body: ByteArray = ByteArray(0),
        contentType: String? = null,
        additionalHeaders: Map<String, String> = emptyMap(),
    ): NativeHttpResponse {
        val snapshot = repository.snapshot()
        val profile = snapshot.profile() ?: throw NativeApiException(null, "PROFILE_REQUIRED", "Configure a server before using NASCraft")
        if (snapshot.session?.serverId != profile.serverId) {
            throw ServerIdentityMismatchException("Sign in to the selected server before sending a request")
        }
        return try { authorizedRetry(profile, selectedRealm) {
            request(profile, selectedRealm, method, pathAndQuery, body, contentType, additionalHeaders)
        } } catch (failure: Exception) {
            notifyIfSessionInvalidated()
            throw failure
        }
    }

    suspend fun jsonSelected(
        method: String,
        pathAndQuery: String,
        body: String? = null,
        additionalHeaders: Map<String, String> = emptyMap(),
    ): NativeHttpResponse = requestSelected(
        method,
        pathAndQuery,
        body?.toByteArray(Charsets.UTF_8) ?: ByteArray(0),
        if (body == null) null else "application/json; charset=utf-8",
        additionalHeaders,
    )

    suspend fun downloadSelected(
        pathAndQuery: String,
        output: OutputStream,
        expectedSize: Long,
        additionalHeaders: Map<String, String> = emptyMap(),
    ): NativeDownloadReceipt {
        val snapshot = repository.snapshot()
        val profile = snapshot.profile() ?: throw NativeApiException(null, "PROFILE_REQUIRED", "Configure a server before downloading")
        if (snapshot.session?.serverId != profile.serverId) {
            throw ServerIdentityMismatchException("Sign in to the selected server before downloading")
        }
        return try { authorizedRetry(profile, selectedRealm) {
            api.businessDownloadTo(profile, selectedRealm, pathAndQuery, output, expectedSize, additionalHeaders)
        } } catch (failure: Exception) {
            notifyIfSessionInvalidated()
            throw failure
        }
    }

    suspend fun request(
        profile: ServerProfile,
        realm: ApiRealm,
        method: String,
        pathAndQuery: String,
        body: ByteArray = ByteArray(0),
        contentType: String? = null,
        additionalHeaders: Map<String, String> = emptyMap(),
    ): NativeHttpResponse {
        return authorizedRetry(profile, realm) {
            api.businessRequest(profile, realm, method, pathAndQuery, body, contentType, additionalHeaders)
        }
    }

    suspend fun json(
        profile: ServerProfile,
        realm: ApiRealm,
        method: String,
        pathAndQuery: String,
        body: String? = null,
        additionalHeaders: Map<String, String> = emptyMap(),
    ): NativeHttpResponse = request(
        profile = profile,
        realm = realm,
        method = method,
        pathAndQuery = pathAndQuery,
        body = body?.toByteArray(Charsets.UTF_8) ?: ByteArray(0),
        contentType = if (body == null) null else "application/json; charset=utf-8",
        additionalHeaders = additionalHeaders,
    )

    private suspend fun requireFreshAccess(profile: ServerProfile, realm: ApiRealm) {
        val session = repository.snapshot().session
            ?: throw NativeApiException(null, "AUTH_REQUIRED", "Sign in to continue")
        if (session.serverId != profile.serverId) throw ServerIdentityMismatchException("Session belongs to another server")
        if (expiryEpoch(session.accessExpiresAt) > TimestampCodec.nowEpochSeconds() + REFRESH_EARLY_SECONDS) return
        refreshLock.withLock {
            val latest = repository.snapshot().session
                ?: throw NativeApiException(null, "AUTH_REQUIRED", "Sign in to continue")
            if (expiryEpoch(latest.accessExpiresAt) > TimestampCodec.nowEpochSeconds() + REFRESH_EARLY_SECONDS) return
            if (latest.refreshToken == null) {
                repository.clearSession()
                mutableAuthEvents.value += 1
                onSessionInvalidated()
                throw NativeApiException(null, "SESSION_EXPIRED", "This temporary connection expired. Sign in again")
            }
            refreshSession(profile, realm)
        }
    }

    private suspend fun <T> authorizedRetry(profile: ServerProfile, realm: ApiRealm, request: suspend () -> T): T {
        requireFreshAccess(profile, realm)
        val before = repository.snapshot().session ?: throw NativeApiException(null, "AUTH_REQUIRED", "Sign in to continue")
        try {
            return request()
        } catch (failure: NativeApiException) {
            val terminal = failure.apiCode in TERMINAL_AUTH_CODES
            val accessExpired = failure.apiCode == "ACCESS_TOKEN_EXPIRED"
            if (failure.apiCode in CLEANUP_AUTH_CODES &&
                !(failure.apiCode == "SESSION_EXPIRED" && before.deviceMode == "temporary")) {
                onSessionRevoked(DownloadAuthorizationIdentity(before.serverId, before.userId, before.deviceId))
            }
            if (failure.httpStatus != 401 || terminal || (!accessExpired && failure.apiCode != null)) throw failure
            refreshAfterUnauthorized(profile, realm, before)
            return request()
        }
    }

    private suspend fun refreshAfterUnauthorized(profile: ServerProfile, realm: ApiRealm, used: NativeSessionState) {
        refreshLock.withLock {
            val latest = repository.snapshot().session
                ?: throw NativeApiException(null, "AUTH_REQUIRED", "Sign in to continue")
            if (latest.serverId != profile.serverId) throw ServerIdentityMismatchException("Session belongs to another server")
            if (latest.sessionId != used.sessionId || latest.accessToken != used.accessToken) return
            val refreshToken = latest.refreshToken
            if (refreshToken == null) {
                repository.clearSession()
                mutableAuthEvents.value += 1
                onSessionInvalidated()
                throw NativeApiException(401, "SESSION_EXPIRED", "This temporary connection expired. Sign in again")
            }
            refreshSession(profile, realm)
        }
    }

    private fun notifyIfSessionInvalidated() {
        if (repository.snapshot().session == null) {
            mutableAuthEvents.value += 1
            onSessionInvalidated()
        }
    }

    private fun expiryEpoch(value: String): Long =
        runCatching { TimestampCodec.parseEpochSeconds(value) }.getOrDefault(0L)

    companion object {
        private const val REFRESH_EARLY_SECONDS = 45L
        private val TERMINAL_AUTH_CODES = setOf(
            "AUTH_NOT_AVAILABLE", "AUTH_REQUIRED", "AUTH_REVOKED", "SESSION_INVALID", "SESSION_EXPIRED", "SESSION_REVOKED",
            "DEVICE_REVOKED", "INSTALLATION_REVOKED", "ACCOUNT_DISABLED",
        )
        private val CLEANUP_AUTH_CODES = setOf("SESSION_EXPIRED", "SESSION_REVOKED", "DEVICE_REVOKED", "INSTALLATION_REVOKED", "ACCOUNT_DISABLED")
    }
}
