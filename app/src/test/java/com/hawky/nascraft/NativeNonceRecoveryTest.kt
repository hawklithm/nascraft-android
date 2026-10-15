package com.hawky.nascraft

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class NativeNonceRecoveryTest {
    @Test
    fun expiredPersonalPoolRenewsWithRefreshProofThenSignsBusinessRequest() = runBlocking {
        exerciseExpiredPoolRecovery(mode = "personal", refreshToken = "refresh-secret")
    }

    @Test
    fun expiredTemporaryPoolRenewsWithActiveBearerAndCannotRenewAfterSessionExpiry() = runBlocking {
        exerciseExpiredPoolRecovery(mode = "temporary", refreshToken = null)
        val (profile, repository) = configuredRepository(
            mode = "temporary",
            refreshToken = null,
            accessExpiry = TimestampCodec.nowEpochSeconds() - 1,
            withExpiredNonce = true,
        )
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            response(chain.request(), "{}")
        }.build()
        val api = NativeAuthApi(InstallationKeyStore(), repository, client, requestSigner = { _, _ -> ByteArray(64) })

        val failure = runCatching {
            api.businessRequest(profile, ApiRealm.LAN_NATIVE, "GET", "/api/uploaded_files")
        }.exceptionOrNull()
        assertTrue(failure is NativeApiException)
        assertEquals("SESSION_EXPIRED", (failure as NativeApiException).apiCode)
        assertEquals(0, requests)
        assertNull(repository.snapshot().session)
    }

    @Test
    fun missingActiveRealmPoolRecoversThroughTheSameBoundChallenge() = runBlocking {
        exerciseExpiredPoolRecovery(mode = "personal", refreshToken = "refresh-secret", withExpiredNonce = false)
    }

    private suspend fun exerciseExpiredPoolRecovery(mode: String, refreshToken: String?, withExpiredNonce: Boolean = true) {
        val (profile, repository) = configuredRepository(
            mode = mode,
            refreshToken = refreshToken,
            accessExpiry = TimestampCodec.nowEpochSeconds() + 3600,
            withExpiredNonce = withExpiredNonce,
        )
        val keyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val signedBases = mutableListOf<ByteArray>()
        val seen = mutableListOf<Pair<Request, String>>()
        val freshNonces = JSONArray()
        val nonceExpiry = TimestampCodec.nowEpochSeconds() + 600
        repeat(16) { index ->
            freshNonces.put(JSONObject()
                .put("value", "new_session_nonce_${index.toString().padStart(2, '0')}")
                .put("expires_at", nonceExpiry))
        }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = request.body?.let { body -> Buffer().also(body::writeTo).readUtf8() } ?: ""
            seen.add(request to body)
            val envelope = when (request.url.encodedPath) {
                "/api/lan/native/v1/auth/nonce-challenge" -> {
                    assertNull(request.header("Authorization"))
                    assertNull(request.header("Signature"))
                    val challengeBody = JSONObject(body)
                    assertEquals("device_12345678", challengeBody.getString("device_id"))
                    assertEquals("session_12345678", challengeBody.getString("session_id"))
                    JSONObject()
                        .put("server_id", profile.serverId)
                        .put("realm", "lan_native")
                        .put("api_origin", profile.lanApiOrigin)
                        .put("nonce", "server_challenge_nonce_123456")
                        .put("expires_at", TimestampCodec.nowEpochSeconds() + 60)
                }
                "/api/lan/native/v1/auth/nonces" -> {
                    assertNotNull(request.header("Signature"))
                    assertNotNull(request.header("Signature-Input"))
                    assertEquals("session_12345678", request.header("Nascraft-Session"))
                    assertEquals("server_challenge_nonce_123456", request.header("Nascraft-Nonce"))
                    val renewBody = JSONObject(body)
                    assertEquals("device_12345678", renewBody.getString("device_id"))
                    assertEquals("session_12345678", renewBody.getString("session_id"))
                    assertEquals("session", renewBody.getString("purpose"))
                    assertEquals(16, renewBody.getInt("count"))
                    if (refreshToken != null) {
                        assertEquals(refreshToken, renewBody.getString("refresh_token"))
                        assertNull(request.header("Authorization"))
                    } else {
                        assertFalse(renewBody.has("refresh_token"))
                        assertEquals("Bearer access-token", request.header("Authorization"))
                    }
                    JSONObject()
                        .put("server_id", profile.serverId)
                        .put("realm", "lan_native")
                        .put("api_origin", profile.lanApiOrigin)
                        .put("nonces", freshNonces)
                }
                "/api/uploaded_files" -> {
                    assertNotNull(request.header("Signature"))
                    assertEquals("Bearer access-token", request.header("Authorization"))
                    assertEquals("new_session_nonce_00", request.header("Nascraft-Nonce"))
                    assertEquals("filename=a%2Fb%3Dc", request.url.encodedQuery)
                    JSONObject().put("ok", true)
                }
                else -> throw AssertionError("Unexpected HTTP path ${request.url.encodedPath}")
            }
            if (request.header("Signature") != null) {
                // The injected signer records the exact bytes passed from the real request builder.
                val rawSignature = Base64.getDecoder().decode(
                    request.header("Signature")!!.removePrefix("sig1=:").removeSuffix(":"),
                )
                assertTrue(EcdsaP1363.isLowS(rawSignature))
                val base = signedBases[if (request.url.encodedPath.endsWith("/auth/nonces")) 0 else 1]
                val verifier = Signature.getInstance("SHA256withECDSA")
                verifier.initVerify(keyPair.public)
                verifier.update(base)
                assertTrue(verifier.verify(p1363ToDer(rawSignature)))
            }
            response(request, JSONObject().put("status", 1).put("code", "0").put("data", envelope).toString())
        }.build()
        val api = NativeAuthApi(
            InstallationKeyStore(),
            repository,
            client,
            requestSigner = { alias, canonical ->
                assertEquals("installation-key", alias)
                signedBases += canonical.copyOf()
                Signature.getInstance("SHA256withECDSA").run {
                    initSign(keyPair.private)
                    update(canonical)
                    EcdsaP1363.toLowS(sign())
                }
            },
        )

        val result = api.businessRequest(
            profile,
            ApiRealm.LAN_NATIVE,
            "GET",
            "/api/uploaded_files?filename=a%2Fb%3Dc",
        )
        assertEquals(200, result.statusCode)
        assertEquals(3, seen.size)
        assertEquals("/api/lan/native/v1/auth/nonce-challenge", seen[0].first.url.encodedPath)
        assertEquals("/api/lan/native/v1/auth/nonces", seen[1].first.url.encodedPath)
        assertEquals("/api/uploaded_files", seen[2].first.url.encodedPath)
        assertEquals(15, repository.snapshot().nonces(ApiRealm.LAN_NATIVE, "session", "session_12345678").size)
        assertEquals(2, signedBases.size)
    }

    private fun configuredRepository(
        mode: String,
        refreshToken: String?,
        accessExpiry: Long,
        withExpiredNonce: Boolean,
    ): Pair<ServerProfile, DeviceAuthRepository> {
        val profile = ServerProfile.validated(
            "srv_home_12345678",
            "Home NAS",
            "id.home.example.com",
            "https://console.home.example.com",
            "https://nas-lan.home.example.com",
            "https://nas-ts.home.example.com",
        )
        val repository = DeviceAuthRepository(MemoryStorage())
        repository.addProfile(profile)
        val now = TimestampCodec.nowEpochSeconds()
        val session = NativeSessionState(
            serverId = profile.serverId,
            userId = "user_12345678",
            userDisplayName = "Member",
            userRole = "member",
            deviceId = "device_12345678",
            deviceName = "Phone",
            sessionId = "session_12345678",
            accessToken = "access-token",
            accessExpiresAt = accessExpiry.toString(),
            refreshToken = refreshToken,
            refreshExpiresAt = refreshToken?.let { (now + 86400).toString() },
            deviceNonce = "device_nonce_12345678",
            deviceMode = mode,
        )
        val expired = if (withExpiredNonce) listOf(StoredNonce("expired_session_nonce_123456", now - 1, "session", "session_12345678")) else emptyList()
        repository.saveSession(profile.serverId, "installation-key", session, expired, emptyList())
        return profile to repository
    }

    private fun response(request: Request, body: String): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .header("Content-Type", "application/json; charset=utf-8")
        .body(body.toResponseBody("application/json; charset=utf-8".toMediaType()))
        .build()

    private fun p1363ToDer(raw: ByteArray): ByteArray {
        val r = derInteger(BigInteger(1, raw.copyOfRange(0, 32)))
        val s = derInteger(BigInteger(1, raw.copyOfRange(32, 64)))
        val sequence = byteArrayOf(0x02, r.size.toByte()) + r + byteArrayOf(0x02, s.size.toByte()) + s
        return byteArrayOf(0x30, sequence.size.toByte()) + sequence
    }

    private fun derInteger(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        return if (raw[0] < 0) byteArrayOf(0) + raw else raw
    }

    private class MemoryStorage : DeviceAuthStorage {
        private var state = DeviceAuthState()
        override fun read(): DeviceAuthState = state
        override fun write(state: DeviceAuthState) { this.state = state }
    }
}
