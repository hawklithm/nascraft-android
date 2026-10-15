package com.hawky.nascraft

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

class NativeAuthApiIdentityTest {
    @Test
    fun acceptsOnlyThePinnedServerRealmAndOrigin() {
        val profile = ServerProfile.validated(
            "srv_home_12345678",
            "Home NAS",
            "id.home.example.com",
            "https://console.home.example.com",
            "https://nas-lan.home.example.com",
            "https://nas-ts.home.example.com",
        )
        val api = NativeAuthApi(InstallationKeyStore(), DeviceAuthRepository(MemoryStorage()))
        val valid = JSONObject()
            .put("server_id", profile.serverId)
            .put("realm", "lan_native")
            .put("api_origin", "https://NAS-LAN.home.example.com:443")

        api.validateResponseIdentity(valid, profile, ApiRealm.LAN_NATIVE)
        api.validateResponseIdentity(valid, profile, ApiRealm.LAN_NATIVE, requireRealm = true)
        assertThrows(ServerIdentityMismatchException::class.java) {
            api.validateResponseIdentity(JSONObject(valid.toString()).put("server_id", "srv_other_12345678"), profile, ApiRealm.LAN_NATIVE)
        }
        assertThrows(ServerIdentityMismatchException::class.java) {
            api.validateResponseIdentity(JSONObject(valid.toString()).put("realm", "remote_api"), profile, ApiRealm.LAN_NATIVE)
        }
        assertThrows(ServerIdentityMismatchException::class.java) {
            api.validateResponseIdentity(JSONObject(valid.toString()).put("api_origin", "https://attacker.example.com"), profile, ApiRealm.LAN_NATIVE)
        }
        assertThrows(ServerIdentityMismatchException::class.java) {
            api.validateResponseIdentity(JSONObject(valid.toString()).put("realm", ""), profile, ApiRealm.LAN_NATIVE, requireRealm = true)
        }
    }

    @Test
    fun v5NativeSessionResponsesRequirePinnedRealmAndRefreshRotationClearsPendingRetryId() {
        val profile = ServerProfile.validated(
            "srv_home_12345678",
            "Home NAS",
            "id.home.example.com",
            "https://console.home.example.com",
            "https://nas-lan.home.example.com",
            "https://nas-ts.home.example.com",
        )
        val api = NativeAuthApi(InstallationKeyStore(), DeviceAuthRepository(MemoryStorage()))
        val identity = JSONObject()
            .put("server_id", profile.serverId)
            .put("realm", "lan_native")
            .put("api_origin", profile.lanApiOrigin)
        val registrationOrLogin = JSONObject(identity.toString())
            .put("mode", "personal")
            .put("user", JSONObject().put("id", "user_12345678").put("display_name", "Member").put("role", "member"))
            .put("device", JSONObject().put("id", "device_12345678").put("display_name", "Phone").put("mode", "personal"))
            .put("session", JSONObject()
                .put("id", "session_12345678")
                .put("access_token", "access")
                .put("access_expires_at", 1_797_000_000L)
                .put("refresh_token", "refresh")
                .put("refresh_expires_at", 1_800_000_000L)
                .put("device_nonce", "device-nonce"))

        val parsed = api.sessionFromResponse(registrationOrLogin, profile, ApiRealm.LAN_NATIVE)
        assertEquals(profile.serverId, parsed.serverId)
        assertEquals("device_12345678", parsed.deviceId)
        assertEquals("refresh", parsed.refreshToken)
        assertEquals("1797000000", parsed.accessExpiresAt)
        assertEquals("1800000000", parsed.refreshExpiresAt)
        assertThrows(ServerIdentityMismatchException::class.java) {
            api.sessionFromResponse(JSONObject(registrationOrLogin.toString()).put("api_origin", profile.remoteApiOrigin), profile, ApiRealm.LAN_NATIVE)
        }

        val current = parsed.copy(pendingRefreshRequestId = "refresh-request-1234567890")
        val refreshed = JSONObject(identity.toString()).put("session", JSONObject()
            .put("id", "session_12345678")
            .put("access_token", "rotated-access")
            .put("access_expires_at", 1_797_000_600L)
            .put("refresh_token", "rotated-refresh")
            .put("refresh_expires_at", 1_800_000_600L)
            .put("device_nonce", "rotated-device-nonce"))
            .put("mode", "personal")
            .put("device", JSONObject().put("mode", "personal"))
        val rotated = api.sessionFromRefreshResponse(refreshed, current, profile, ApiRealm.LAN_NATIVE)
        assertEquals("rotated-refresh", rotated.refreshToken)
        assertNull(rotated.pendingRefreshRequestId)
        assertThrows(ServerIdentityMismatchException::class.java) {
            api.sessionFromRefreshResponse(JSONObject(refreshed.toString()).put("realm", "remote_api"), current, profile, ApiRealm.LAN_NATIVE)
        }
    }

    @Test
    fun nonceRecoveryChallengeMustMatchTheConfiguredApiRealm() {
        val profile = ServerProfile.validated(
            "srv_home_12345678",
            "Home NAS",
            "id.home.example.com",
            "https://console.home.example.com",
            "https://nas-lan.home.example.com",
            "https://nas-ts.home.example.com",
        )
        val api = NativeAuthApi(InstallationKeyStore(), DeviceAuthRepository(MemoryStorage()))
        val challenge = JSONObject()
            .put("server_id", profile.serverId)
            .put("realm", "lan_native")
            .put("api_origin", profile.lanApiOrigin)
            .put("nonce", "nonce_from_server_123456")
            .put("expires_at", 1_797_000_000L)

        assertEquals("nonce_from_server_123456", api.nonceFromChallenge(challenge, profile, ApiRealm.LAN_NATIVE, "session", "session_id").value)
        assertThrows(ServerIdentityMismatchException::class.java) {
            api.nonceFromChallenge(JSONObject(challenge.toString()).put("api_origin", profile.remoteApiOrigin), profile, ApiRealm.LAN_NATIVE, "session", "session_id")
        }
    }

    @Test
    fun remotePinChangeRequiresTheLanServerToEndorseAnExactConfiguredOrigin() {
        val profile = profile().copy(remoteApiOrigin = "")
        val api = NativeAuthApi(InstallationKeyStore(), DeviceAuthRepository(MemoryStorage()))
        val approved = serverProfileMetadata(profile, "https://nas-ts.home.example.com")

        assertEquals("https://nas-ts.home.example.com", api.validateEndorsedRemoteOrigin(
            approved, profile, "https://nas-ts.home.example.com",
        ))
        assertEquals("", api.validateEndorsedRemoteOrigin(
            serverProfileMetadata(profile, null), profile,
        ))
        assertThrows(ServerIdentityMismatchException::class.java) {
            api.validateEndorsedRemoteOrigin(
                serverProfileMetadata(profile, "https://attacker.example.com"),
                profile,
                "https://nas-ts.home.example.com",
            )
        }
        assertThrows(ServerIdentityMismatchException::class.java) {
            val withoutEndorsement = JSONObject(approved.toString())
            withoutEndorsement.remove("configured_remote_api_origin")
            api.validateEndorsedRemoteOrigin(withoutEndorsement, profile)
        }
        assertThrows(ServerIdentityMismatchException::class.java) {
            api.validateEndorsedRemoteOrigin(
                JSONObject(approved.toString()).put("lan_api_origin", "https://attacker.home.example.com"),
                profile,
            )
        }
        assertThrows(ServerIdentityMismatchException::class.java) {
            api.validateEndorsedRemoteOrigin(
                JSONObject(approved.toString()).put("remote_api_origin", "https://attacker.example.com"),
                profile,
            )
        }
    }

    @Test
    fun acceptsBackendNoncePoolsOfFourAndEmptyDisabledRemotePool() {
        val profile = profile()
        val api = NativeAuthApi(InstallationKeyStore(), DeviceAuthRepository(MemoryStorage()))
        val response = sessionNonceResponse(profile, remoteCount = 4)

        val (lan, remote) = api.sessionNonces(response, profile, ApiRealm.LAN_NATIVE)
        assertEquals(4, lan.size)
        assertEquals(4, remote.size)
        assertTrue((lan + remote).all { it.bindingId == "session_12345678" })
        assertTrue(api.sessionNonces(sessionNonceResponse(profile, remoteCount = 4, lanCount = 0), profile, ApiRealm.LAN_NATIVE).first.isEmpty())

        val lanOnly = ServerProfile.validated(
            profile.serverId,
            profile.displayName,
            profile.rpId,
            profile.lanWebUrl,
            profile.lanApiOrigin,
            "",
        )
        val (lanOnlyPool, remotePool) = api.sessionNonces(sessionNonceResponse(lanOnly, remoteCount = 0), lanOnly, ApiRealm.LAN_NATIVE)
        assertEquals(4, lanOnlyPool.size)
        assertTrue(remotePool.isEmpty())
        val remoteUnavailable = api.sessionNonces(sessionNonceResponse(profile, remoteCount = 0), profile, ApiRealm.LAN_NATIVE)
        assertTrue(remoteUnavailable.second.isEmpty())
        assertTrue(api.sessionNonces(sessionNonceResponse(profile, remoteCount = 0), profile, ApiRealm.REMOTE_API).second.isEmpty())
        assertTrue(api.sessionNonces(sessionNonceResponse(lanOnly, remoteCount = 4), lanOnly, ApiRealm.LAN_NATIVE).second.isEmpty())
        assertThrows(NativeApiException::class.java) {
            api.sessionNonces(sessionNonceResponse(lanOnly, remoteCount = 0), lanOnly, ApiRealm.REMOTE_API)
        }
        assertThrows(NativeApiException::class.java) {
            api.sessionNonces(sessionNonceResponse(profile, remoteCount = 33), profile, ApiRealm.LAN_NATIVE)
        }
    }

    @Test
    fun acceptsTemporaryResponseWithNoRefreshAndUsesServerAuthorizedMode() {
        val profile = profile()
        val api = NativeAuthApi(InstallationKeyStore(), DeviceAuthRepository(MemoryStorage()))
        val response = JSONObject()
            .put("server_id", profile.serverId)
            .put("realm", "lan_native")
            .put("api_origin", profile.lanApiOrigin)
            .put("mode", "temporary")
            .put("user", JSONObject().put("id", "user_12345678").put("display_name", "Member").put("role", "member"))
            .put("device", JSONObject().put("id", "device_12345678").put("mode", "temporary"))
            .put("session", JSONObject()
                .put("id", "session_12345678")
                .put("access_token", "temporary-access")
                .put("access_expires_at", 1_797_000_000L)
                .put("device_nonce", "device-nonce"))

        val parsed = api.sessionFromResponse(response, profile, ApiRealm.LAN_NATIVE, expectedMode = "temporary")
        assertEquals("temporary", parsed.deviceMode)
        assertNull(parsed.refreshToken)
        assertNull(parsed.refreshExpiresAt)
        assertEquals("This device", parsed.deviceName)
        val explicitNullRefresh = JSONObject(response.toString())
        explicitNullRefresh.getJSONObject("session").put("refresh_token", JSONObject.NULL)
        assertNull(api.sessionFromResponse(explicitNullRefresh, profile, ApiRealm.LAN_NATIVE).refreshToken)

        assertThrows(NativeApiException::class.java) {
            api.sessionFromResponse(JSONObject(response.toString()).put("device", JSONObject().put("id", "device_12345678").put("mode", "personal")), profile, ApiRealm.LAN_NATIVE)
        }
        assertThrows(NativeApiException::class.java) {
            val unexpectedRefresh = JSONObject(response.toString())
            unexpectedRefresh.getJSONObject("session")
                .put("refresh_token", "unexpected")
                .put("refresh_expires_at", 1_800_000_000L)
            api.sessionFromResponse(unexpectedRefresh, profile, ApiRealm.LAN_NATIVE)
        }
        assertThrows(NativeApiException::class.java) {
            val incompletePersonal = JSONObject(personalResponse(profile).toString())
            incompletePersonal.getJSONObject("session").remove("refresh_expires_at")
            api.sessionFromResponse(incompletePersonal, profile, ApiRealm.LAN_NATIVE)
        }
    }

    @Test
    fun timestampReaderAcceptsOnlyIntegerSecondsOrRfc3339AndRejectsInvalidNumbers() {
        val api = NativeAuthApi(InstallationKeyStore(), DeviceAuthRepository(MemoryStorage()))
        assertEquals(1_797_000_000L, api.expirationEpochSeconds(JSONObject().put("expires_at", 1_797_000_000L)))
        assertEquals(1_796_083_200L, api.expirationEpochSeconds(JSONObject().put("expires_at", "2026-12-01T00:00:00Z")))
        assertThrows(NativeApiException::class.java) {
            api.expirationEpochSeconds(JSONObject().put("expires_at", 1.5))
        }
        assertThrows(NativeApiException::class.java) {
            api.expirationEpochSeconds(JSONObject().put("expires_at", -1L))
        }
        assertThrows(NativeApiException::class.java) {
            api.expirationEpochSeconds(JSONObject().put("expires_at", BigInteger("9223372036854775808")))
        }
        assertThrows(NativeApiException::class.java) {
            api.expirationEpochSeconds(JSONObject().put("expires_at", "1797000000"))
        }
    }

    private fun sessionNonceResponse(profile: ServerProfile, remoteCount: Int, lanCount: Int = 4): JSONObject {
        fun pool(prefix: String, count: Int) = org.json.JSONArray().apply {
            repeat(count) { index ->
                put(JSONObject()
                    .put("value", "${prefix}_nonce_value_${index.toString().padStart(2, '0')}")
                    .put("expires_at", 1_797_000_000L))
            }
        }
        return JSONObject()
            .put("server_id", profile.serverId)
            .put("realm", "lan_native")
            .put("api_origin", profile.lanApiOrigin)
            .put("session", JSONObject().put("id", "session_12345678"))
            .put("nonces", JSONObject().put("lan_native", pool("lan", lanCount)).put("remote_api", pool("remote", remoteCount)))
    }

    private fun personalResponse(profile: ServerProfile): JSONObject = JSONObject()
        .put("server_id", profile.serverId)
        .put("realm", "lan_native")
        .put("api_origin", profile.lanApiOrigin)
        .put("mode", "personal")
        .put("user", JSONObject().put("id", "user_12345678").put("display_name", "Member").put("role", "member"))
        .put("device", JSONObject().put("id", "device_12345678").put("mode", "personal"))
        .put("session", JSONObject()
            .put("id", "session_12345678")
            .put("access_token", "access")
            .put("access_expires_at", 1_797_000_000L)
            .put("refresh_token", "refresh")
            .put("refresh_expires_at", 1_800_000_000L)
            .put("device_nonce", "device-nonce"))

    private fun serverProfileMetadata(profile: ServerProfile, remoteOrigin: String?): JSONObject = JSONObject()
        .put("format", ServerProfile.QR_FORMAT)
        .put("server_id", profile.serverId)
        .put("display_name", profile.displayName)
        .put("rp_id", profile.rpId)
        .put("lan_web_url", profile.lanWebUrl)
        .put("lan_api_origin", profile.lanApiOrigin)
        .put("configured_remote_api_origin", remoteOrigin ?: JSONObject.NULL)
        .put("remote_api_origin", remoteOrigin ?: JSONObject.NULL)

    private class MemoryStorage : DeviceAuthStorage {
        private var state = DeviceAuthState()
        override fun read(): DeviceAuthState = state
        override fun write(state: DeviceAuthState) { this.state = state }
    }

    private fun profile() = ServerProfile.validated(
        "srv_home_12345678",
        "Home NAS",
        "id.home.example.com",
        "https://console.home.example.com",
        "https://nas-lan.home.example.com",
        "https://nas-ts.home.example.com",
    )
}
