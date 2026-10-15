package com.hawky.nascraft

import android.app.Activity
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/** Owns resumable pairing, remote OTP registration, native Passkey login and session lifecycle. */
class NativeAuthCoordinator(
    private val keys: InstallationKeyStore,
    private val repository: DeviceAuthRepository,
    private val api: NativeAuthApi,
    private val onExplicitLogout: (NativeSessionState?) -> Unit = {},
) {
    suspend fun confirmRemoteApiOrigin(candidate: ServerProfile) {
        val current = repository.snapshot().profile()
            ?: throw IllegalStateException("Add and select the server profile first")
        require(candidate.serverId == current.serverId && current.copy(remoteApiOrigin = candidate.remoteApiOrigin) == candidate &&
            current.remoteApiOrigin != candidate.remoteApiOrigin) {
            "Only the Tailscale API origin can be changed through LAN confirmation"
        }
        api.verifyTrustedLanIdentity(current, candidate.remoteApiOrigin)
        repository.updateRemoteApiOriginAfterLanConfirmation(candidate)
    }

    suspend fun startPairing(
        profile: ServerProfile,
        deviceName: String,
        mode: String,
    ): PendingNativeFlow {
        require(mode == "personal" || mode == "temporary")
        val name = deviceName.trim()
        require(name.isNotEmpty() && name.length <= 80) { "Enter a device name" }
        prepareProfile(profile)
        val existing = repository.snapshot().pendingFlow
        val flow = if (existing == null) {
            val key = createFlowKey(profile.serverId)
            PendingNativeFlow(
                kind = PendingNativeFlow.Kind.PAIRING,
                serverId = profile.serverId,
                keyAlias = key.alias,
                deviceName = name,
                mode = mode,
                stage = "creating_request",
            )
        } else {
            require(existing.kind == PendingNativeFlow.Kind.PAIRING &&
                existing.serverId == profile.serverId && existing.requestId == null) {
                "Finish or cancel the previous device flow before starting another"
            }
            existing.copy(deviceName = name, mode = mode, stage = "creating_request")
        }
        repository.savePendingFlow(flow)

        return createPairingRequest(profile, flow)
    }

    /** Recreates a locator after process death or a lost create response. */
    suspend fun resumePairing(profile: ServerProfile): PendingNativeFlow {
        val flow = requirePending(PendingNativeFlow.Kind.PAIRING, profile)
        require(flow.requestId == null) { "This pairing request already has a server locator" }
        ensureFlowActive(flow)
        return createPairingRequest(profile, flow.copy(stage = "creating_request"))
    }

    private suspend fun createPairingRequest(profile: ServerProfile, flow: PendingNativeFlow): PendingNativeFlow {
        val key = keys.getOrCreate(flow.keyAlias)
        api.requestPreAuthNonces(
            profile = profile,
            realm = ApiRealm.LAN_NATIVE,
            purpose = "pair",
            count = 1,
            installationPublicKey = key.publicKeyBase64Url,
            bindingId = "preauth:pair",
        )
        val nonce = requiredNonce(ApiRealm.LAN_NATIVE, "pair", "preauth:pair")
        val body = JSONObject()
            .put("installation_public_key", key.publicKeyBase64Url)
            .put("device_name", flow.deviceName)
            .put("platform", "android")
            .put("mode", flow.mode)
        val data = api.postSigned(
            profile,
            ApiRealm.LAN_NATIVE,
            "${ApiRealm.LAN_NATIVE.routePrefix}/devices/pairing",
            body,
            NativeProof(key.alias, key.thumbprint, "preauth:pair", nonce.value),
        )
        api.validateResponseIdentity(data, profile, ApiRealm.LAN_NATIVE)
        val requestId = data.getString("request_id").also(::requireOpaqueId)
        val webOrigin = HttpMessageSigner.requireExactHttpsOrigin(data.getString("web_origin"))
        require(webOrigin == profile.lanWebUrl) { "The pairing request points to a different LAN Web server" }
        val webUrl = profile.validateReturnedWebUrl(data.getString("web_url"), requestId)
        val completed = flow.copy(
            stage = "awaiting_web_approval",
            requestId = requestId,
            webUrl = webUrl,
            locator = data.optString("locator").takeIf { it.isNotEmpty() },
            humanCode = data.optString("human_code").takeIf { it.isNotEmpty() },
            expiresAtEpochSeconds = api.expirationEpochSeconds(data),
        )
        repository.savePendingFlow(completed)
        return completed
    }

    /** The exact server-returned URL is opened; callback query values are never accepted as credentials. */
    fun pairingWebUrl(flow: PendingNativeFlow): String? =
        flow.takeIf { it.kind == PendingNativeFlow.Kind.PAIRING && !it.isExpired() }?.webUrl

    suspend fun tryClaimPairing(profile: ServerProfile, realm: ApiRealm): NativeSessionState {
        val flow = requirePending(PendingNativeFlow.Kind.PAIRING, profile)
        ensureFlowActive(flow)
        val requestId = flow.requestId ?: throw IllegalStateException("The pairing request is not ready to claim")
        val material = keys.getOrCreate(flow.keyAlias)
        api.requestPreAuthNonces(
            profile = profile,
            realm = realm,
            purpose = "claim",
            count = 1,
            installationPublicKey = material.publicKeyBase64Url,
            bindingId = "pair:$requestId",
            pairingRequestId = requestId,
        )
        val challengeNonce = requiredNonce(realm, "claim", "pair:$requestId")
        val path = "${realm.routePrefix}/devices/pairing/$requestId/claim-challenge"
        val challenge = api.postSigned(
            profile,
            realm,
            path,
            JSONObject(),
            NativeProof(flow.keyAlias, material.thumbprint, "pair:$requestId", challengeNonce.value),
        )
        val claimNonce = api.nonceFromChallenge(challenge, profile, realm, "claim", "pair:$requestId", "claim_nonce")
        require(claimNonce.expiresAtEpochSeconds > TimestampCodec.nowEpochSeconds()) {
            "The device confirmation expired; check the page and try again"
        }
        val result = api.postSigned(
            profile,
            realm,
            "${realm.routePrefix}/devices/pairing/$requestId/claim",
            JSONObject(),
            NativeProof(flow.keyAlias, material.thumbprint, "pair:$requestId", claimNonce.value),
        )
        val session = api.sessionFromResponse(result, profile, realm, expectedMode = flow.mode)
        val pools = api.sessionNonces(result, profile, realm)
        saveSessionAndEnsureActivePool(profile, flow.keyAlias, session, pools, realm)
        return session
    }

    suspend fun cancelPairing(profile: ServerProfile, realm: ApiRealm): Boolean {
        val flow = requirePending(PendingNativeFlow.Kind.PAIRING, profile)
        if (flow.isExpired()) {
            repository.clearPendingFlow()
            keys.delete(flow.keyAlias)
            return true
        }
        val requestId = flow.requestId ?: run {
            repository.clearPendingFlow()
            keys.delete(flow.keyAlias)
            return true
        }
        val key = keys.getOrCreate(flow.keyAlias)
        api.requestPreAuthNonces(
            profile = profile,
            realm = realm,
            purpose = "claim",
            count = 1,
            installationPublicKey = key.publicKeyBase64Url,
            bindingId = "pair:$requestId",
            pairingRequestId = requestId,
        )
        val nonce = requiredNonce(realm, "claim", "pair:$requestId")
        val response = api.postSigned(
            profile,
            realm,
            "${realm.routePrefix}/devices/pairing/$requestId/cancel",
            JSONObject(),
            NativeProof(flow.keyAlias, key.thumbprint, "pair:$requestId", nonce.value),
        )
        if (response.optString("status") != "cancelled") {
            throw NativeApiException(null, "BAD_RESPONSE", "The server did not confirm cancellation")
        }
        repository.clearPendingFlow()
        keys.delete(flow.keyAlias)
        return true
    }

    suspend fun redeemRemoteOtp(
        profile: ServerProfile,
        deviceName: String,
        otp: String,
    ): PendingNativeFlow {
        if (!NativePasskeySupport.isSupportedPlatform()) {
            throw NativeApiException(
                null,
                "PASSKEY_UNSUPPORTED",
                "此 Android 版本不支持原生通行密钥；请连接 LAN 并使用设备配对",
            )
        }
        val name = deviceName.trim()
        require(name.isNotEmpty() && name.length <= 80) { "Enter a device name" }
        val normalizedOtp = otp.trim().replace(" ", "").replace("-", "").uppercase(Locale.ROOT)
        require(OTP_CODE.matches(normalizedOtp)) { "Enter the 26-character one-time registration code" }
        prepareProfile(profile)
        require(repository.snapshot().pendingFlow == null) { "Finish or cancel the current device flow first" }
        val key = createFlowKey(profile.serverId)
        var flow = PendingNativeFlow(
            kind = PendingNativeFlow.Kind.REMOTE_OTP,
            serverId = profile.serverId,
            keyAlias = key.alias,
            deviceName = name,
            mode = "pending",
            stage = "redeeming_otp",
            otp = normalizedOtp,
        )
        repository.savePendingFlow(flow)
        return redeemPersistedOtp(profile, flow)
    }

    fun abandonRemoteRegistration(profile: ServerProfile) {
        val flow = requirePending(PendingNativeFlow.Kind.REMOTE_OTP, profile)
        require(flow.credentialJson == null) {
            "The server may have received a Passkey response; recover the registration before removing local keys"
        }
        repository.clearPendingFlow()
        keys.delete(flow.keyAlias)
    }

    private suspend fun redeemPersistedOtp(profile: ServerProfile, flow: PendingNativeFlow): PendingNativeFlow {
        val otp = flow.otp ?: throw IllegalStateException("The one-time code is no longer available on this device")
        val key = keys.getOrCreate(flow.keyAlias)
        // The challenge is not signed: the fixed Tailscale API listener supplies and binds the real principal.
        val challenge = api.postUnsigned(
            profile,
            ApiRealm.REMOTE_API,
            "${ApiRealm.REMOTE_API.routePrefix}/otp/redeem-challenge",
            JSONObject()
                .put("installation_public_key", key.publicKeyBase64Url)
                .put("device_name", flow.deviceName)
                .put("platform", "android"),
        )
        val registrationNonce = api.registrationNonceFromChallenge(challenge, profile)
        require(registrationNonce.expiresAtEpochSeconds > TimestampCodec.nowEpochSeconds()) {
            "The one-time registration code expired. Ask the LAN operator for a new code"
        }
        val redemption = api.postSigned(
            profile,
            ApiRealm.REMOTE_API,
            "${ApiRealm.REMOTE_API.routePrefix}/otp/redeem",
            JSONObject()
                .put("otp", otp)
                .put("installation_public_key", key.publicKeyBase64Url)
                .put("device_name", flow.deviceName)
                .put("platform", "android"),
            NativeProof(key.alias, key.thumbprint, "preauth:otp", registrationNonce.value),
        )
        api.validateResponseIdentity(redemption, profile, ApiRealm.REMOTE_API, requireRealm = true)
        val authorizedMode = api.authorizedMode(redemption)
        val registrationId = redemption.getString("registration_id").also(::requireOpaqueId)
        val publicKey = redemption.getJSONObject("publicKey").toString()
        require(NativePasskeySupport.isValidCreationOptions(publicKey, profile.rpId)) {
            "The server did not provide a compatible Passkey registration challenge"
        }
        val nonceValues = api.registrationNonces(redemption)
        repository.appendNonces(ApiRealm.REMOTE_API, nonceValues, profile.serverId)
        val completed = flow.copy(
            mode = authorizedMode,
            stage = "registration_ready",
            otp = null,
            registrationId = registrationId,
            publicKeyJson = publicKey,
            expiresAtEpochSeconds = api.expirationEpochSeconds(redemption),
        )
        repository.savePendingFlow(completed)
        return completed
    }

    suspend fun createAndFinishRemotePasskey(activity: Activity): NativePasskeyResult {
        val snapshot = repository.snapshot()
        val profile = snapshot.profile() ?: throw IllegalStateException("Add a server profile first")
        val flow = requirePending(PendingNativeFlow.Kind.REMOTE_OTP, profile)
        ensureFlowActive(flow)
        val registrationId = flow.registrationId ?: throw IllegalStateException("Enter the one-time code first")
        val publicKey = flow.publicKeyJson ?: refreshRegistrationOptions(profile, flow)
        val passkeyResult = NativePasskeyClient(activity).create(publicKey, profile.rpId)
        if (passkeyResult !is NativePasskeyResult.Success) return passkeyResult
        val resumable = flow.copy(stage = "finishing_registration", credentialJson = passkeyResult.credentialJson)
        repository.savePendingFlow(resumable)
        finishRemoteRegistration(profile, resumable, passkeyResult.credentialJson)
        return passkeyResult
    }

    suspend fun resumeRemoteRegistration(): NativeSessionState? {
        val snapshot = repository.snapshot()
        val profile = snapshot.profile() ?: return null
        val flow = snapshot.pendingFlow?.takeIf { it.kind == PendingNativeFlow.Kind.REMOTE_OTP } ?: return null
        if (flow.registrationId == null) {
            if (flow.otp == null) throw IllegalStateException("The one-time code is no longer available on this device")
            redeemPersistedOtp(profile, flow)
            return null
        }
        if (flow.credentialJson != null) {
            recoverRemoteRegistration(profile, flow)
            return repository.snapshot().session
        }
        if (flow.isExpired()) {
            repository.clearPendingFlow()
            keys.delete(flow.keyAlias)
            return null
        }
        if (flow.publicKeyJson == null) refreshRegistrationOptions(profile, flow)
        return null
    }

    /** Resolves a lost finish response through signed status/claim before retrying creation. */
    suspend fun recoverRemoteRegistration(
        profile: ServerProfile,
        flow: PendingNativeFlow = requirePending(PendingNativeFlow.Kind.REMOTE_OTP, profile),
    ): NativeSessionState? {
        val registrationId = flow.registrationId ?: return null
        val key = keys.getOrCreate(flow.keyAlias)
        val statusNonce = registrationNonce(profile, registrationId)
        val status = api.getSigned(
            profile,
            ApiRealm.REMOTE_API,
            "${ApiRealm.REMOTE_API.routePrefix}/otp/registration/$registrationId",
            NativeProof(flow.keyAlias, key.thumbprint, registrationId, statusNonce.value),
        ).optString("status")
        when (status) {
            "registered", "claimed", "completed" -> {
                val claimNonce = registrationNonce(profile, registrationId)
                val result = api.postSigned(
                    profile,
                    ApiRealm.REMOTE_API,
                    "${ApiRealm.REMOTE_API.routePrefix}/otp/registration/$registrationId/claim",
                    JSONObject(),
                    NativeProof(flow.keyAlias, key.thumbprint, registrationId, claimNonce.value),
                )
                val session = api.sessionFromResponse(result, profile, ApiRealm.REMOTE_API, expectedMode = flow.mode)
                val pools = api.sessionNonces(result, profile, ApiRealm.REMOTE_API)
                saveSessionAndEnsureActivePool(profile, flow.keyAlias, session, pools, ApiRealm.REMOTE_API)
                return session
            }
            "active" -> {
                if (flow.isExpired()) {
                    repository.clearPendingFlow()
                    keys.delete(flow.keyAlias)
                    throw NativeApiException(null, "FLOW_EXPIRED", "The one-time registration expired. Ask the LAN operator for a new code")
                }
                val credential = flow.credentialJson ?: return null
                finishRemoteRegistration(profile, flow, credential)
                return repository.snapshot().session
            }
            "expired", "revoked" -> {
                repository.clearPendingFlow()
                keys.delete(flow.keyAlias)
                throw NativeApiException(null, "FLOW_EXPIRED", "The one-time registration expired. Ask the LAN operator for a new code")
            }
            else -> throw NativeApiException(null, "BAD_RESPONSE", "The server could not confirm the registration status")
        }
    }

    suspend fun loginWithPasskey(profile: ServerProfile, realm: ApiRealm, activity: Activity): NativePasskeyResult {
        if (!NativePasskeySupport.isSupportedPlatform()) return NativePasskeyResult.Unsupported
        prepareProfile(profile)
        val snapshot = repository.snapshot()
        val installationId = snapshot.installationId
            ?: throw IllegalStateException("Pair this device or register a Passkey on it first")
        val keyAlias = snapshot.installationKeyAlias
            ?: throw IllegalStateException("This installation needs to be paired again")
        val material = keys.getOrCreate(keyAlias)
        val existing = snapshot.pendingFlow
        require(existing == null || existing.kind == PendingNativeFlow.Kind.LOGIN) {
            "Finish or cancel the current device flow first"
        }
        val existingTransactionId = existing?.transactionId
        val existingHasFinishNonce = existingTransactionId != null &&
            repository.snapshot().nonces(realm, "login", "login:$existingTransactionId")
                .any { it.expiresAtEpochSeconds > TimestampCodec.nowEpochSeconds() }
        if (existing?.credentialJson != null && existing.serverId == profile.serverId &&
            !existing.isExpired() && existingHasFinishNonce) {
            finishNativeLogin(profile, realm, existing, existing.credentialJson)
            return NativePasskeyResult.Success(existing.credentialJson)
        }
        val validOptions = existing?.takeIf {
            it.serverId == profile.serverId && !it.isExpired() && it.publicKeyJson != null &&
                it.transactionId?.let { transactionId ->
                    repository.snapshot().nonces(realm, "login", "login:$transactionId")
                        .any { nonce -> nonce.expiresAtEpochSeconds > TimestampCodec.nowEpochSeconds() }
                } == true
        }
        val flow: PendingNativeFlow
        val publicKeyJson: String
        if (validOptions != null) {
            flow = validOptions
            publicKeyJson = validOptions.publicKeyJson!!
        } else {
            val expired = existing
            if (expired != null) {
                expired.transactionId?.let { repository.clearNonces(realm, "login", "login:$it") }
                repository.clearPendingFlow()
            }
            val preAuthBinding = "preauth:login:$installationId"
            api.requestPreAuthNonces(
                profile, realm, "login", 16, deviceId = installationId, bindingId = preAuthBinding,
            )
            val nonce = requiredNonce(realm, "login", preAuthBinding)
            val challenge = api.postSigned(
                profile,
                realm,
                "${realm.routePrefix}/auth/login-challenge",
                JSONObject().put("device_id", installationId),
                NativeProof(keyAlias, installationId, "preauth:login:$installationId", nonce.value),
            )
            api.validateResponseIdentity(challenge, profile, realm, requireRealm = true)
            val transactionId = challenge.getString("transaction_id").also(::requireOpaqueId)
            publicKeyJson = challenge.getJSONObject("publicKey").toString()
            require(NativePasskeySupport.isValidRequestOptions(publicKeyJson, profile.rpId)) {
                "The server did not provide a compatible Passkey sign-in challenge"
            }
            val challengeNonces = api.nonceBatch(challenge, purpose = "login", bindingId = "login:$transactionId")
            if (challengeNonces.isEmpty()) {
                throw NativeApiException(null, "BAD_NONCE_BATCH", "The sign-in challenge had no transaction-bound request nonce")
            }
            repository.appendNonces(realm, challengeNonces, profile.serverId)
            flow = PendingNativeFlow(
                kind = PendingNativeFlow.Kind.LOGIN,
                serverId = profile.serverId,
                keyAlias = keyAlias,
                deviceName = snapshot.session?.deviceName ?: "This device",
                mode = "personal",
                stage = "login_ready",
                transactionId = transactionId,
                publicKeyJson = publicKeyJson,
                expiresAtEpochSeconds = api.expirationEpochSeconds(challenge),
            )
            repository.savePendingFlow(flow)
        }
        val result = NativePasskeyClient(activity).get(publicKeyJson, profile.rpId)
        if (result is NativePasskeyResult.Success) {
            val resumable = flow.copy(stage = "finishing_login", credentialJson = result.credentialJson)
            repository.savePendingFlow(resumable)
            finishNativeLogin(profile, realm, resumable, result.credentialJson)
        }
        return result
    }

    suspend fun resumeNativeLogin(profile: ServerProfile, realm: ApiRealm): NativeSessionState? {
        val flow = repository.snapshot().pendingFlow?.takeIf {
            it.kind == PendingNativeFlow.Kind.LOGIN && it.serverId == profile.serverId
        } ?: return null
        if (flow.isExpired()) {
            repository.clearPendingFlow()
            return null
        }
        val credential = flow.credentialJson ?: return null
        finishNativeLogin(profile, realm, flow, credential)
        return repository.snapshot().session
    }

    suspend fun refreshSession(profile: ServerProfile, realm: ApiRealm): NativeSessionState {
        val snapshot = repository.snapshot()
        val session = snapshot.session ?: throw NativeApiException(null, "AUTH_REQUIRED", "Sign in to continue")
        if (session.refreshToken == null) throw NativeApiException(null, "SESSION_EXPIRED", "This temporary connection has expired; sign in again")
        val keyAlias = snapshot.installationKeyAlias ?: throw NativeApiException(null, "AUTH_REQUIRED", "Pair this device again")
        val nonce = sessionNonce(profile, realm)
        val refreshRequestId = session.pendingRefreshRequestId ?: UUID.randomUUID().toString().also {
            repository.savePendingRefreshRequestId(it)
        }
        val response = api.postSigned(
            profile,
            realm,
            "${realm.routePrefix}/auth/refresh",
            JSONObject().put("refresh_token", session.refreshToken).put("refresh_request_id", refreshRequestId),
            NativeProof(keyAlias, session.deviceId, session.sessionId, nonce.value),
        )
        val refreshed = api.sessionFromRefreshResponse(response, session, profile, realm)
        val pools = api.sessionNonces(response, profile, realm)
        saveSessionAndEnsureActivePool(profile, keyAlias, refreshed, pools, realm)
        return refreshed
    }

    suspend fun logout(profile: ServerProfile, realm: ApiRealm) {
        val snapshot = repository.snapshot()
        val session = snapshot.session ?: return
        val keyAlias = snapshot.installationKeyAlias ?: return clearSessionAndDownloads(session)
        val nonce = sessionNonce(profile, realm)
        api.postSigned(
            profile,
            realm,
            "${realm.routePrefix}/auth/logout",
            JSONObject(),
            NativeProof(keyAlias, session.deviceId, session.sessionId, nonce.value, session.accessToken),
        )
        clearSessionAndDownloads(session)
    }

    /** Drops local bearer material when offline; installation identity remains available for Passkey re-login. */
    fun logoutLocally() {
        val session = repository.snapshot().session
        repository.clearSession()
        onExplicitLogout(session)
    }

    private fun clearSessionAndDownloads(session: NativeSessionState) {
        repository.clearSession()
        onExplicitLogout(session)
    }

    private suspend fun refreshRegistrationOptions(profile: ServerProfile, flow: PendingNativeFlow): String {
        val registrationId = flow.registrationId ?: throw IllegalStateException("Registration has not been started")
        val nonce = registrationNonce(profile, registrationId)
        val data = api.postSigned(
            profile,
            ApiRealm.REMOTE_API,
            "${ApiRealm.REMOTE_API.routePrefix}/otp/registration/options",
            JSONObject().put("registration_id", registrationId),
            NativeProof(flow.keyAlias, keys.getOrCreate(flow.keyAlias).thumbprint, registrationId, nonce.value),
        )
        api.validateResponseIdentity(data, profile, ApiRealm.REMOTE_API, requireRealm = true)
        if (api.authorizedMode(data) != flow.mode) {
            throw NativeApiException(null, "BAD_RESPONSE", "The server changed the authorized device mode during registration")
        }
        val json = data.getJSONObject("publicKey").toString()
        require(NativePasskeySupport.isValidCreationOptions(json, profile.rpId)) {
            "The server did not provide a compatible Passkey registration challenge"
        }
        repository.savePendingFlow(flow.copy(stage = "registration_ready", publicKeyJson = json))
        return json
    }

    private suspend fun finishRemoteRegistration(profile: ServerProfile, flow: PendingNativeFlow, credential: String) {
        ensureFlowActive(flow)
        val registrationId = flow.registrationId ?: throw IllegalStateException("Registration has expired")
        val nonce = registrationNonce(profile, registrationId)
        val result = api.postSigned(
            profile,
            ApiRealm.REMOTE_API,
            "${ApiRealm.REMOTE_API.routePrefix}/otp/registration/finish",
            JSONObject().put("registration_id", registrationId).put("credential", JSONObject(credential)),
            NativeProof(flow.keyAlias, keys.getOrCreate(flow.keyAlias).thumbprint, registrationId, nonce.value),
        )
        val session = api.sessionFromResponse(result, profile, ApiRealm.REMOTE_API, expectedMode = flow.mode)
        val pools = api.sessionNonces(result, profile, ApiRealm.REMOTE_API)
        saveSessionAndEnsureActivePool(profile, flow.keyAlias, session, pools, ApiRealm.REMOTE_API)
    }

    private suspend fun finishNativeLogin(profile: ServerProfile, realm: ApiRealm, flow: PendingNativeFlow, credential: String) {
        ensureFlowActive(flow)
        val transactionId = flow.transactionId ?: throw IllegalStateException("The sign-in challenge has expired")
        val installationId = repository.snapshot().installationId
            ?: throw IllegalStateException("This installation needs to be paired again")
        if (repository.snapshot().nonces(realm, "login", "login:$transactionId")
                .none { it.expiresAtEpochSeconds > TimestampCodec.nowEpochSeconds() }) {
            // The transaction and its finish nonces share a two-minute lifetime. A completed
            // credential cannot be replayed against a fresh transaction, so require a new prompt.
            repository.clearNonces(realm, "login", "login:$transactionId")
            repository.clearPendingFlow()
            throw NativeApiException(null, "FLOW_EXPIRED", "The Passkey sign-in timed out. Start sign-in again")
        }
        val nonce = requiredNonce(realm, "login", "login:$transactionId")
        val result = api.postSigned(
            profile,
            realm,
            "${realm.routePrefix}/auth/login-finish",
            JSONObject().put("transaction_id", transactionId).put("credential", JSONObject(credential)),
            NativeProof(flow.keyAlias, installationId, "login:$transactionId", nonce.value),
        )
        val session = api.sessionFromResponse(result, profile, realm)
        val pools = api.sessionNonces(result, profile, realm)
        saveSessionAndEnsureActivePool(profile, flow.keyAlias, session, pools, realm)
    }

    private suspend fun saveSessionAndEnsureActivePool(
        profile: ServerProfile,
        keyAlias: String,
        session: NativeSessionState,
        pools: Pair<List<StoredNonce>, List<StoredNonce>>,
        activeRealm: ApiRealm,
    ) {
        repository.saveSession(profile.serverId, keyAlias, session, pools.first, pools.second)
        val activePool = if (activeRealm == ApiRealm.LAN_NATIVE) pools.first else pools.second
        if (activePool.isEmpty()) {
            // Backend deployments can disable a realm or omit its initial batch. Recover
            // through the same bound-key/session challenge path before using that realm.
            api.requestSessionNonces(profile, activeRealm)
        }
    }

    private suspend fun registrationNonce(profile: ServerProfile, registrationId: String): StoredNonce {
        if (repository.snapshot().nonces(ApiRealm.REMOTE_API, "register", registrationId)
                .none { it.expiresAtEpochSeconds > TimestampCodec.nowEpochSeconds() }) {
            api.requestPreAuthNonces(
                profile,
                ApiRealm.REMOTE_API,
                purpose = "register",
                count = 4,
                registrationId = registrationId,
                bindingId = registrationId,
            )
        }
        return requiredNonce(ApiRealm.REMOTE_API, "register", registrationId)
    }

    private suspend fun sessionNonce(profile: ServerProfile, realm: ApiRealm): StoredNonce {
        val now = TimestampCodec.nowEpochSeconds()
        val sessionId = repository.snapshot().session?.sessionId
            ?: throw NativeApiException(null, "AUTH_REQUIRED", "Sign in to continue")
        if (repository.snapshot().nonces(realm, "session", sessionId).none { it.expiresAtEpochSeconds > now }) {
            api.requestSessionNonces(profile, realm)
        }
        return requiredNonce(realm, "session", sessionId)
    }

    private fun requiredNonce(realm: ApiRealm, purpose: String, bindingId: String): StoredNonce =
        repository.takeNonce(realm, purpose, bindingId, TimestampCodec.nowEpochSeconds())
            ?: throw NativeApiException(null, "NONCE_REQUIRED", "The secure request challenge expired. Retry when the server is reachable")

    private fun createFlowKey(serverId: String): InstallationKeyMaterial {
        val serverTag = serverId.toByteArray().let { bytes ->
            MessageDigest.getInstance("SHA-256").digest(bytes).take(10)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
        // A pending authorization must not overwrite or delete a previously authorized
        // installation's key when the user cancels or the server rejects this flow.
        val alias = "nascraft.flow.$serverTag.${UUID.randomUUID().toString().replace("-", "")}"
        return keys.getOrCreate(alias)
    }

    private fun prepareProfile(profile: ServerProfile) {
        val current = repository.snapshot()
        if (current.profiles.none { it.serverId == profile.serverId }) repository.addProfile(profile)
        else if (current.selectedServerId != profile.serverId) repository.selectProfile(profile.serverId)
        else repository.addProfile(profile)
    }

    private fun requirePending(kind: PendingNativeFlow.Kind, profile: ServerProfile): PendingNativeFlow {
        val flow = repository.snapshot().pendingFlow
            ?: throw IllegalStateException("There is no device connection to continue")
        require(flow.kind == kind && flow.serverId == profile.serverId) {
            "This device flow belongs to another server or connection method"
        }
        return flow
    }

    private fun ensureFlowActive(flow: PendingNativeFlow) {
        if (flow.isExpired()) {
            repository.clearPendingFlow()
            if (flow.kind != PendingNativeFlow.Kind.LOGIN) keys.delete(flow.keyAlias)
            throw NativeApiException(null, "FLOW_EXPIRED", "This request expired. Start a new connection to continue")
        }
    }

    private fun requireOpaqueId(value: String) {
        require(OPAQUE_ID.matches(value)) { "The server returned an invalid request identifier" }
    }

    companion object {
        private val OPAQUE_ID = Regex("[A-Za-z0-9_-]{8,192}")
        private val OTP_CODE = Regex("[A-Z2-7]{26}")
    }
}
