package com.hawky.nascraft

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

class DeviceAuthContractFixtureTest {
    @Test
    fun sharedBackendFixtureVerifiesAndAndroidBuildsTheSameCanonicalMessages() {
        val fixture = JSONObject(
            javaClass.getResourceAsStream("/device_auth_contract.json")!!
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() },
        )
        val spki = Base64Codec.decodeUrlNoPadding(fixture.getString("public_key_spki_der_b64url"))
        val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki))
        assertEquals(fixture.getString("public_key_thumbprint_b64url"), InstallationKeyFormat.thumbprint(spki))

        val vectors = fixture.getJSONArray("requests")
        assertEquals(3, vectors.length())
        for (index in 0 until vectors.length()) {
            val vector = vectors.getJSONObject(index)
            val body = Base64.getDecoder().decode(vector.getString("body_base64"))
            val headers = vector.getJSONObject("headers")
            val extras = linkedMapOf<String, String>()
            val extraJson = vector.getJSONObject("extras")
            val extraKeys = extraJson.keys()
            while (extraKeys.hasNext()) {
                val name = extraKeys.next()
                extras[name] = extraJson.getString(name)
            }

            val generatedPair = KeyPairGenerator.getInstance("EC").apply {
                initialize(ECGenParameterSpec("secp256r1"))
            }.generateKeyPair()
            val proof = HttpMessageSigner.sign(
                method = vector.getString("method"),
                configuredOrigin = vector.getString("api_origin"),
                rawPathAndQuery = vector.getString("raw_path_and_query"),
                body = body,
                sessionMarker = vector.getString("session_marker"),
                nonce = headers.getString("nascraft-nonce"),
                keyId = vector.getString("expected_key_id"),
                createdEpochSeconds = vector.getLong("now_unix_seconds") - 30,
                expiresEpochSeconds = vector.getLong("now_unix_seconds") + 30,
                coveredHeaders = extras,
            ) { canonical ->
                Signature.getInstance("SHA256withECDSA").run {
                    initSign(generatedPair.private)
                    update(canonical)
                    EcdsaP1363.toLowS(sign())
                }
            }

            assertEquals(vector.getString("signature_base"), proof.canonicalBase)
            assertEquals(headers.getString("content-digest"), proof.contentDigest)
            assertEquals(headers.getString("signature-input"), proof.signatureInput)
            assertEquals(vector.getString("api_origin") + vector.getString("raw_path_and_query"), proof.targetUri)

            val p1363 = Base64.getDecoder().decode(
                headers.getString("signature").removePrefix("sig1=:").removeSuffix(":"),
            )
            assertTrue("${vector.getString("id")} is a low-S P1363 signature", EcdsaP1363.isLowS(p1363))
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(publicKey)
            verifier.update(vector.getString("signature_base").toByteArray(Charsets.UTF_8))
            assertTrue("${vector.getString("id")} backend signature verifies", verifier.verify(p1363ToDer(p1363)))
        }
    }

    private fun p1363ToDer(raw: ByteArray): ByteArray {
        require(raw.size == 64)
        val r = derInteger(raw.copyOfRange(0, 32))
        val s = derInteger(raw.copyOfRange(32, 64))
        val sequence = byteArrayOf(0x02, r.size.toByte()) + r + byteArrayOf(0x02, s.size.toByte()) + s
        require(sequence.size < 128)
        return byteArrayOf(0x30, sequence.size.toByte()) + sequence
    }

    private fun derInteger(raw: ByteArray): ByteArray {
        val first = raw.indexOfFirst { it != 0.toByte() }.let { if (it < 0) raw.lastIndex else it }
        val magnitude = raw.copyOfRange(first, raw.size)
        return if ((magnitude[0].toInt() and 0x80) != 0) byteArrayOf(0) + magnitude else magnitude
    }
}
