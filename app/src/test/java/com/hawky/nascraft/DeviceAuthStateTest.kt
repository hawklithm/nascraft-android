package com.hawky.nascraft

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.KeyPairGenerator
import javax.crypto.spec.SecretKeySpec

class DeviceAuthStateTest {
    @Test
    fun codecRestoresProfilesPendingOtpAndPersonalSession() {
        val profile = profile()
        val otherProfile = ServerProfile.validated(
            "srv_other_12345678",
            "Other NAS",
            "id.other.example.com",
            "https://console.other.example.com",
            "https://lan.other.example.com",
            "https://ts.other.example.com",
        )
        val state = DeviceAuthState(
            profiles = listOf(profile, otherProfile),
            inactiveServerStates = listOf(
                ServerAuthSnapshot(
                    serverId = "srv_other_12345678",
                    pendingFlow = PendingNativeFlow(
                        kind = PendingNativeFlow.Kind.PAIRING,
                        serverId = "srv_other_12345678",
                        keyAlias = "nascraft.flow.other.1234",
                        deviceName = "Other phone",
                        mode = "personal",
                        stage = "awaiting_web_approval",
                        requestId = "request_12345678",
                    ),
                    installationId = "device_other_12345678",
                    installationKeyAlias = "nascraft.install.other.1234",
                    remoteNonces = listOf(StoredNonce("other_nonce_123456", 1_900_000_000, "session", "session_other_123456")),
                    autoBackupEnabled = true,
                ),
            ),
            selectedServerId = profile.serverId,
            installationId = "device_12345678",
            installationKeyAlias = "nascraft.install.home.1234",
            pendingFlow = PendingNativeFlow(
                kind = PendingNativeFlow.Kind.REMOTE_OTP,
                serverId = profile.serverId,
                keyAlias = "nascraft.installation.home.1234",
                deviceName = "My phone",
                mode = "personal",
                stage = "registration_options",
                registrationId = "registration_12345678",
                publicKeyJson = "{\"rp\":{\"id\":\"id.home.example.com\"}}",
                otp = "opaque-one-time-secret-not-in-logs",
                credentialJson = "{\"type\":\"public-key\"}",
            ),
            session = NativeSessionState(
                serverId = profile.serverId,
                userId = "user_12345678",
                userDisplayName = "Member",
                userRole = "member",
                deviceId = "device_12345678",
                deviceName = "My phone",
                sessionId = "session_12345678",
                accessToken = "access-secret",
                accessExpiresAt = "2026-10-14T10:10:00Z",
                refreshToken = "refresh-secret",
                refreshExpiresAt = "2026-11-13T10:00:00Z",
                deviceNonce = "device-nonce",
                deviceMode = "temporary",
            ),
            lanNonces = listOf(StoredNonce("lan_nonce", 1_900_000_000, "session", "session_12345678")),
            remoteNonces = listOf(StoredNonce("remote_nonce", 1_900_000_000, "register", "registration_12345678")),
        )

        val decoded = DeviceAuthStateCodec.decode(DeviceAuthStateCodec.encode(state))

        assertEquals(state, decoded)
        assertEquals("https://nas-lan.home.example.com", decoded.profile()?.lanApiOrigin)
        assertEquals("device_12345678", decoded.installationId)
        assertEquals("nascraft.install.home.1234", decoded.installationKeyAlias)
        assertEquals("remote_nonce", decoded.nonces(ApiRealm.REMOTE_API, "register").single().value)
        assertEquals("device_other_12345678", decoded.inactiveServerStates.single().installationId)
        assertEquals("request_12345678", decoded.inactiveServerStates.single().pendingFlow?.requestId)
        assertTrue(decoded.inactiveServerStates.single().autoBackupEnabled)
    }

    @Test
    fun legacyCodecKeepsSessionAndProfileButDropsUnboundNoncePools() {
        val profile = profile()
        val session = NativeSessionState(
            serverId = profile.serverId,
            userId = "user_12345678",
            userDisplayName = "Member",
            userRole = "member",
            deviceId = "device_12345678",
            deviceName = "Phone",
            sessionId = "session_12345678",
            accessToken = "access-secret",
            accessExpiresAt = "2026-10-14T10:10:00Z",
            refreshToken = "refresh-secret",
            refreshExpiresAt = "2026-11-13T10:00:00Z",
            deviceNonce = "device-nonce",
            pendingRefreshRequestId = "refresh_request_12345678",
        )
        val legacy = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeInt(0x4e415343)
                out.writeInt(5)
                out.writeInt(1)
                out.writeUTF(profile.serverId)
                out.writeUTF(profile.displayName)
                out.writeUTF(profile.rpId)
                out.writeUTF(profile.lanWebUrl)
                out.writeUTF(profile.lanApiOrigin)
                out.writeUTF(profile.remoteApiOrigin)
                out.writeNullable(profile.serverId)
                out.writeNullable("device_12345678")
                out.writeNullable("nascraft.install.home.1234")
                out.writeBoolean(false)
                out.writeBoolean(true)
                out.writeUTF(session.serverId)
                out.writeUTF(session.userId)
                out.writeUTF(session.userDisplayName)
                out.writeUTF(session.userRole)
                out.writeUTF(session.deviceId)
                out.writeUTF(session.deviceName)
                out.writeUTF(session.sessionId)
                out.writeUTF(session.accessToken)
                out.writeUTF(session.accessExpiresAt)
                out.writeNullable(session.refreshToken)
                out.writeNullable(session.refreshExpiresAt)
                out.writeUTF(session.deviceNonce)
                out.writeUTF(session.deviceMode)
                out.writeNullable(session.pendingRefreshRequestId)
                out.writeInt(1)
                out.writeUTF("old_lan_nonce_123456")
                out.writeLong(1_900_000_000)
                out.writeUTF("session")
                out.writeInt(1)
                out.writeUTF("old_remote_nonce_123456")
                out.writeLong(1_900_000_000)
                out.writeUTF("register")
                out.writeBoolean(true)
                out.writeInt(0)
            }
            bytes.toByteArray()
        }

        val decoded = DeviceAuthStateCodec.decode(legacy)

        assertEquals(profile, decoded.profile())
        assertEquals(session, decoded.session)
        assertTrue(decoded.autoBackupEnabled)
        assertTrue(decoded.lanNonces.isEmpty())
        assertTrue(decoded.remoteNonces.isEmpty())
    }

    @Test
    fun expiredNoncesAreDroppedAndSessionClearKeepsServerIdentity() {
        val profile = profile()
        val state = DeviceAuthState(
            profiles = listOf(profile),
            selectedServerId = profile.serverId,
            installationId = "device_id_12345678",
            installationKeyAlias = "nascraft.install.home.1234",
            session = NativeSessionState(profile.serverId, "u", "User", "member", "d", "Phone", "s", "a", "expiry", "r", "r-expiry", "dn"),
        lanNonces = listOf(StoredNonce("expired", 10, "session", "session_id_12345678"), StoredNonce("valid", 20, "session", "session_id_12345678")),
        )
        val expired = state.withoutExpiredNonces(nowEpochSeconds = 15)
        val cleared = expired.clearSession()
        assertEquals(listOf(StoredNonce("valid", 20, "session", "session_id_12345678")), expired.lanNonces)
        assertNull(cleared.session)
        assertEquals(profile, cleared.profile())
        assertEquals("device_id_12345678", cleared.installationId)
        assertEquals("nascraft.install.home.1234", cleared.installationKeyAlias)
        assertTrue(cleared.lanNonces.isEmpty())
    }

    @Test
    fun pendingFlowExpirationSurvivesProcessStateRoundTrip() {
        val flow = PendingNativeFlow(
            kind = PendingNativeFlow.Kind.PAIRING,
            serverId = "srv_home_12345678",
            keyAlias = "nascraft.install.home.1234",
            deviceName = "Phone",
            mode = "personal",
            stage = "awaiting_web_approval",
            requestId = "request_12345678",
            expiresAtEpochSeconds = 100,
        )
        val saved = DeviceAuthStateCodec.decode(
            DeviceAuthStateCodec.encode(DeviceAuthState(pendingFlow = flow)),
        ).pendingFlow!!

        assertFalse(saved.isExpired(99))
        assertTrue(saved.isExpired(100))
        assertTrue(saved.isExpired(101))
        assertFalse(saved.copy(expiresAtEpochSeconds = null).isExpired(100))
    }

    @Test
    fun unreadableSecureStateCanBeResetWithoutRestoringAUsableSession() {
        val storage = object : DeviceAuthStorage {
            private var readable = false
            private var state = DeviceAuthState()

            override fun read(): DeviceAuthState {
                check(readable) { "tampered encrypted envelope" }
                return state
            }

            override fun write(state: DeviceAuthState) {
                this.state = state
                readable = true
            }
        }
        val repository = DeviceAuthRepository(storage)

        assertThrows(IllegalStateException::class.java) { repository.snapshot() }
        repository.resetSecureState()

        assertEquals(DeviceAuthState(), repository.snapshot())
    }

    @Test
    fun gcmEnvelopeAuthenticatesCiphertextAndNeverStoresPayloadAsPlaintext() {
        val key = KeyPairGenerator.getInstance("EC").apply {
            initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val derivedTestKey = SecretKeySpec(java.security.MessageDigest.getInstance("SHA-256").digest(key.private.encoded), "AES")
        val secret = "otp-and-refresh-token".toByteArray()
        val envelope = AesGcmEnvelope.encrypt(derivedTestKey, secret)

        assertFalse(envelope.toString(Charsets.ISO_8859_1).contains("otp-and-refresh-token"))
        assertArrayEquals(secret, AesGcmEnvelope.decrypt(derivedTestKey, envelope))
        val tampered = envelope.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertThrows(Exception::class.java) { AesGcmEnvelope.decrypt(derivedTestKey, tampered) }
    }

    @Test
    fun repositoryPinsProfileOriginsAndConsumesNonceBeforeUse() {
        val first = profile()
        val memory = MemoryDeviceAuthStorage()
        val repository = DeviceAuthRepository(memory)
        repository.addProfile(first)
        repository.appendNonces(
            ApiRealm.LAN_NATIVE,
            listOf(
                StoredNonce("abcdefghijklmnop", 100, "pair", "preauth:pair"),
                StoredNonce("another_nonce_123456", 100, "pair", "pair:request_12345678"),
            ),
            first.serverId,
        )

        assertNull(repository.takeNonce(ApiRealm.LAN_NATIVE, "pair", "pair:another_request", 50))
        assertEquals("abcdefghijklmnop", repository.takeNonce(ApiRealm.LAN_NATIVE, "pair", "preauth:pair", 50)?.value)
        assertEquals("another_nonce_123456", repository.takeNonce(ApiRealm.LAN_NATIVE, "pair", "pair:request_12345678", 50)?.value)
        assertNull(repository.takeNonce(ApiRealm.LAN_NATIVE, "pair", "preauth:pair", 50))
        assertThrows(ServerProfileConflictException::class.java) {
            repository.addProfile(first.copy(lanApiOrigin = "https://attacker.home.example.com"))
        }
    }

    @Test
    fun remoteApiPinCanChangeOnlyAfterTheLanVerifiedRepositoryGate() {
        val original = profile()
        val repository = DeviceAuthRepository(MemoryDeviceAuthStorage())
        repository.addProfile(original)
        val session = NativeSessionState(
            original.serverId, "user_12345678", "Member", "member", "device_12345678", "Phone",
            "session_12345678", "access-secret", "1900000000", "refresh-secret", "2000000000", "device-nonce",
        )
        repository.saveSession(
            original.serverId,
            "nascraft.install.home.1234",
            session,
            listOf(StoredNonce("lan_nonce_12345678", 1_900_000_000, "session", session.sessionId)),
            listOf(StoredNonce("remote_nonce_12345678", 1_900_000_000, "session", session.sessionId)),
        )

        assertThrows(IllegalArgumentException::class.java) {
            repository.updateRemoteApiOriginAfterLanConfirmation(original.copy(lanApiOrigin = "https://attacker.home.example.com"))
        }
        val changed = repository.updateRemoteApiOriginAfterLanConfirmation(
            original.copy(remoteApiOrigin = "https://new-ts.home.example.com"),
        )

        assertEquals("https://nas-lan.home.example.com", changed.profile()?.lanApiOrigin)
        assertEquals("https://new-ts.home.example.com", changed.profile()?.remoteApiOrigin)
        assertEquals(session, changed.session)
        assertEquals("lan_nonce_12345678", changed.lanNonces.single().value)
        assertTrue(changed.remoteNonces.isEmpty())
    }

    @Test
    fun pendingRefreshRequestIdSurvivesEncryptedStateEncodingForIdempotentRetry() {
        val profile = profile()
        val repository = DeviceAuthRepository(MemoryDeviceAuthStorage())
        repository.addProfile(profile)
        val session = NativeSessionState(
            profile.serverId, "user_12345678", "Member", "member", "device_12345678", "Phone",
            "session_12345678", "access-secret", "2026-10-14T10:10:00Z", "refresh-secret",
            "2026-11-13T10:00:00Z", "device-nonce",
        )
        repository.saveSession(profile.serverId, "nascraft.install.home.1234", session, emptyList(), emptyList())
        repository.savePendingRefreshRequestId("9a87b2b1-4f3c-45d8-9a32-c6f144578932")

        val restored = DeviceAuthStateCodec.decode(DeviceAuthStateCodec.encode(repository.snapshot()))

        assertEquals("9a87b2b1-4f3c-45d8-9a32-c6f144578932", restored.session?.pendingRefreshRequestId)
        assertEquals("refresh-secret", restored.session?.refreshToken)
    }

    @Test
    fun revokedInstallationDropsLocalIdentityButKeepsPinnedServer() {
        val profile = profile()
        val repository = DeviceAuthRepository(MemoryDeviceAuthStorage())
        repository.addProfile(profile)
        repository.saveInstallationKeyAlias(profile.serverId, "nascraft.install.home.1234")
        repository.saveSession(
            profile.serverId,
            "nascraft.install.home.1234",
            NativeSessionState(profile.serverId, "u", "User", "member", "device", "Phone", "session", "access", "expiry", "refresh", "refresh-expiry", "nonce"),
            listOf(StoredNonce("abcdefghijklmnop", 100, "session", "session")),
            emptyList(),
        )

        val state = repository.clearRevokedInstallation()

        assertEquals(profile, state.profile())
        assertNull(state.session)
        assertNull(state.installationId)
        assertNull(state.installationKeyAlias)
        assertTrue(state.lanNonces.isEmpty())
    }

    @Test
    fun pendingEnrollmentKeyDoesNotReplaceAnAlreadyAuthorizedInstallation() {
        val profile = profile()
        val repository = DeviceAuthRepository(MemoryDeviceAuthStorage())
        repository.addProfile(profile)
        repository.saveInstallationKeyAlias(profile.serverId, "nascraft.install.home.existing")
        repository.saveSession(
            profile.serverId,
            "nascraft.install.home.existing",
            NativeSessionState(profile.serverId, "u", "User", "member", "device_existing", "Phone", "session_existing", "access", "expiry", "refresh", "refresh-expiry", "nonce"),
            emptyList(),
            emptyList(),
        )

        val pending = PendingNativeFlow(
            kind = PendingNativeFlow.Kind.PAIRING,
            serverId = profile.serverId,
            keyAlias = "nascraft.flow.home.new",
            deviceName = "Second phone",
            mode = "personal",
            stage = "creating_request",
        )
        val state = repository.savePendingFlow(pending)

        assertEquals("nascraft.install.home.existing", state.installationKeyAlias)
        assertEquals("device_existing", state.installationId)
        assertEquals("session_existing", state.session?.sessionId)
        assertEquals("nascraft.flow.home.new", state.pendingFlow?.keyAlias)
    }

    @Test
    fun switchingServerProfilesPreservesEachServersAuthorizationAndBackupState() {
        val first = profile()
        val second = ServerProfile.validated(
            "srv_other_12345678",
            "Other NAS",
            "id.other.example.com",
            "https://console.other.example.com",
            "https://lan.other.example.com",
            "https://ts.other.example.com",
        )
        val repository = DeviceAuthRepository(MemoryDeviceAuthStorage())
        repository.addProfile(first)
        val firstSession = NativeSessionState(
            first.serverId,
            "user_home_12345678",
            "Home member",
            "member",
            "device_home_12345678",
            "Phone",
            "session_home_12345678",
            "access-home-secret",
            "2026-10-14T10:10:00Z",
            "refresh-home-secret",
            "2026-11-13T10:00:00Z",
            "device-nonce-home",
        )
        repository.saveSession(
            first.serverId,
            "nascraft.install.home.1234",
            firstSession,
            listOf(StoredNonce("home_nonce_123456", 1_900_000_000, "session", "session_home_12345678")),
            emptyList(),
        )
        repository.savePendingFlow(
            PendingNativeFlow(
                kind = PendingNativeFlow.Kind.PAIRING,
                serverId = first.serverId,
                keyAlias = "nascraft.flow.home.pending",
                deviceName = "Second phone",
                mode = "personal",
                stage = "awaiting_web_approval",
                requestId = "request_home_123456",
                webUrl = "https://console.home.example.com/connect?request_id=request_home_123456",
            ),
        )
        repository.setAutoBackupEnabled(true)

        val switched = repository.addProfile(second)

        assertEquals(second.serverId, switched.selectedServerId)
        assertFalse(switched.autoBackupEnabled)
        assertNull(switched.session)
        assertEquals("nascraft.install.home.1234", switched.inactiveServerStates.single().installationKeyAlias)

        val restored = repository.selectProfile(first.serverId)
        assertEquals(first.serverId, restored.selectedServerId)
        assertEquals(firstSession, restored.session)
        assertEquals("nascraft.install.home.1234", restored.installationKeyAlias)
        assertEquals("request_home_123456", restored.pendingFlow?.requestId)
        assertEquals("home_nonce_123456", restored.lanNonces.single().value)
        assertTrue(restored.autoBackupEnabled)
    }

    private class MemoryDeviceAuthStorage : DeviceAuthStorage {
        private var state = DeviceAuthState()
        override fun read(): DeviceAuthState = state
        override fun write(state: DeviceAuthState) { this.state = state }
    }

    private fun DataOutputStream.writeNullable(value: String?) {
        writeBoolean(value != null)
        if (value != null) writeUTF(value)
    }

    private fun profile() = ServerProfile.validated(
        serverId = "srv_home_12345678",
        displayName = "Home NAS",
        rpId = "id.home.example.com",
        lanWebUrl = "https://console.home.example.com",
        lanApiOrigin = "https://nas-lan.home.example.com",
        remoteApiOrigin = "https://nas-ts.home.example.com",
    )
}
