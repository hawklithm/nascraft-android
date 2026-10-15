package com.hawky.nascraft

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class HttpMessageSignerTest {
    @Test
    fun emitsRfc9421AndContentDigestForExactRealmTarget() {
        val pair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val body = "{\"device_id\":\"device_12345678\"}".toByteArray()
        val proof = HttpMessageSigner.sign(
            method = "POST",
            configuredOrigin = "https://Nas-Ts.Home.Example.com",
            rawPathAndQuery = "/api/remote/v1/auth/login-challenge?x=1&x=2",
            body = body,
            sessionMarker = "preauth:login:device_12345678",
            nonce = "nonce_1234567890",
            keyId = "device_12345678",
            createdEpochSeconds = 1_800_000_000,
            expiresEpochSeconds = 1_800_000_030,
        ) { canonical ->
            val der = Signature.getInstance("SHA256withECDSA").run {
                initSign(pair.private)
                update(canonical)
                sign()
            }
            EcdsaP1363.toLowS(der)
        }

        assertEquals("https://nas-ts.home.example.com/api/remote/v1/auth/login-challenge?x=1&x=2", proof.targetUri)
        assertEquals(
            "sha-256=:${Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(body))}:",
            proof.contentDigest,
        )
        assertTrue(proof.signatureInput.startsWith("sig1=(\"@method\" \"@target-uri\" \"content-digest\" \"nascraft-session\" \"nascraft-nonce\")"))
        assertTrue(proof.signatureInput.contains("nonce=\"nonce_1234567890\";keyid=\"device_12345678\";alg=\"ecdsa-p256-sha256\""))
        val wireSignature = Base64.getDecoder().decode(proof.signature.removePrefix("sig1=:").removeSuffix(":"))
        assertTrue(EcdsaP1363.isLowS(wireSignature))
        assertTrue(Signature.getInstance("SHA256withECDSA").run {
            initVerify(pair.public)
            update(proof.canonicalBase.toByteArray())
            verify(toDer(wireSignature))
        })
    }

    @Test
    fun refusesDowngradeAmbiguousTargetAndHighS() {
        val alwaysFailingSigner: (ByteArray) -> ByteArray = { ByteArray(64) }
        assertThrows(IllegalArgumentException::class.java) {
            HttpMessageSigner.sign("POST", "http://lan.home.example.com", "/api/x", byteArrayOf(), "preauth:pair", "nonce_1234567890", "key_12345678", 10, 20, signer = alwaysFailingSigner)
        }
        assertThrows(IllegalArgumentException::class.java) {
            HttpMessageSigner.sign("POST", "https://lan.home.example.com", "/api/../secret", byteArrayOf(), "preauth:pair", "nonce_1234567890", "key_12345678", 10, 20, signer = alwaysFailingSigner)
        }
        assertThrows(IllegalArgumentException::class.java) {
            HttpMessageSigner.sign("POST", "https://lan.home.example.com", "/api/%2fsecret", byteArrayOf(), "preauth:pair", "nonce_1234567890", "key_12345678", 10, 20, signer = alwaysFailingSigner)
        }
        assertThrows(IllegalArgumentException::class.java) {
            HttpMessageSigner.sign("GET", "https://lan.home.example.com", "/api/files?name=bad%GG", byteArrayOf(), "preauth:pair", "nonce_1234567890", "key_12345678", 10, 20, signer = alwaysFailingSigner)
        }
        assertThrows(IllegalArgumentException::class.java) {
            HttpMessageSigner.sign("POST", "https://lan.home.example.com", "/api/x", byteArrayOf(), "preauth:pair", "nonce_1234567890", "key_12345678", 10, 81, signer = alwaysFailingSigner)
        }
    }

    @Test
    fun uploadMetadataAndRangeHeadersAreIncludedInCanonicalSignature() {
        val pair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val headers = linkedMapOf(
            "X-File-ID" to "file_12345678",
            "X-Start-Offset" to "1048576",
            "Content-Range" to "bytes 1048576-1049599/2097152",
        )
        val proof = HttpMessageSigner.sign(
            method = "POST",
            configuredOrigin = "https://nas-lan.home.example.com",
            rawPathAndQuery = "/api/upload",
            body = ByteArray(1024),
            sessionMarker = "session_12345678",
            nonce = "nonce_1234567890",
            keyId = "device_12345678",
            createdEpochSeconds = 1_800_000_000,
            expiresEpochSeconds = 1_800_000_030,
            coveredHeaders = headers,
        ) { canonical ->
            Signature.getInstance("SHA256withECDSA").run {
                initSign(pair.private)
                update(canonical)
                EcdsaP1363.toLowS(sign())
            }
        }

        assertTrue(proof.signatureInput.contains("\"x-file-id\" \"x-start-offset\" \"content-range\""))
        assertTrue(proof.canonicalBase.contains("\"x-file-id\": file_12345678\n\"x-start-offset\": 1048576\n\"content-range\": bytes 1048576-1049599/2097152"))

        val rangeProof = HttpMessageSigner.sign(
            method = "GET",
            configuredOrigin = "https://nas-lan.home.example.com",
            rawPathAndQuery = "/api/download/file_12345678",
            body = ByteArray(0),
            sessionMarker = "session_12345678",
            nonce = "nonce_1234567891",
            keyId = "device_12345678",
            createdEpochSeconds = 1_800_000_000,
            expiresEpochSeconds = 1_800_000_030,
            coveredHeaders = mapOf("Range" to "bytes=0-1023", "If-Range" to "\"etag-1\""),
        ) { canonical ->
            Signature.getInstance("SHA256withECDSA").run {
                initSign(pair.private)
                update(canonical)
                EcdsaP1363.toLowS(sign())
            }
        }
        assertTrue(rangeProof.signatureInput.contains("\"range\" \"if-range\""))
        assertTrue(rangeProof.canonicalBase.contains("\"range\": bytes=0-1023\n\"if-range\": \"etag-1\""))
    }

    @Test
    fun rejectsPartiallyCoveredUploadMetadataAndIfRangeWithoutRange() {
        val signer: (ByteArray) -> ByteArray = { ByteArray(64) }
        assertThrows(IllegalArgumentException::class.java) {
            HttpMessageSigner.sign(
                "POST", "https://nas-lan.home.example.com", "/api/upload", ByteArray(0),
                "session_12345678", "nonce_1234567890", "device_12345678", 10, 20,
                coveredHeaders = mapOf("X-File-ID" to "file_12345678"), signer = signer,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            HttpMessageSigner.sign(
                "GET", "https://nas-lan.home.example.com", "/api/download/file_12345678", ByteArray(0),
                "session_12345678", "nonce_1234567890", "device_12345678", 10, 20,
                coveredHeaders = mapOf("If-Range" to "etag"), signer = signer,
            )
        }
    }

    @Test
    fun validatesPrintableRangeAndChunkValuesWithoutChangingTheirSerialization() {
        val pair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        var called = false
        val signer: (ByteArray) -> ByteArray = { canonical ->
            called = true
            Signature.getInstance("SHA256withECDSA").run {
                initSign(pair.private)
                update(canonical)
                EcdsaP1363.toLowS(sign())
            }
        }

        assertThrows(IllegalArgumentException::class.java) {
            HttpMessageSigner.sign(
                "GET", "https://nas-lan.home.example.com", "/api/download/file_12345678", ByteArray(0),
                "session_12345678", "nonce_1234567890", "device_12345678", 10, 20,
                coveredHeaders = mapOf("Range" to "bytes=0-1,3-4"), signer = signer,
            )
        }
        assertTrue(!called)
        assertThrows(IllegalArgumentException::class.java) {
            HttpMessageSigner.sign(
                "GET", "https://nas-lan.home.example.com", "/api/download/file_12345678", ByteArray(0),
                "session_12345678", "nonce_1234567890", "device_12345678", 10, 20,
                coveredHeaders = mapOf("Range" to "bytes=0-4\r\nAuthorization: Bearer leaked"), signer = signer,
            )
        }
        assertTrue(!called)
        assertThrows(IllegalArgumentException::class.java) {
            HttpMessageSigner.sign(
                "POST", "https://nas-lan.home.example.com", "/api/upload", ByteArray(3),
                "session_12345678", "nonce_1234567890", "device_12345678", 10, 20,
                coveredHeaders = mapOf(
                    "X-File-ID" to "file_12345678",
                    "X-Start-Offset" to "0",
                    "Content-Range" to "bytes 0-3/10",
                ), signer = signer,
            )
        }
        assertTrue(!called)

        val range = "bytes=0-4"
        val validator = "\"etag,with-comma\""
        val proof = HttpMessageSigner.sign(
            method = "GET",
            configuredOrigin = "https://nas-lan.home.example.com",
            rawPathAndQuery = "/api/download/file_12345678?name=a%2Fb%3Dc",
            body = ByteArray(0),
            sessionMarker = "session_12345678",
            nonce = "nonce_1234567890",
            keyId = "device_12345678",
            createdEpochSeconds = 10,
            expiresEpochSeconds = 20,
            coveredHeaders = mapOf("Range" to range, "If-Range" to validator),
            signer = signer,
        )
        assertTrue(called)
        assertTrue(proof.canonicalBase.contains("/api/download/file_12345678?name=a%2Fb%3Dc"))
        assertTrue(proof.canonicalBase.contains("\"range\": bytes=0-4\n\"if-range\": \"etag,with-comma\""))
    }

    private fun toDer(raw: ByteArray): ByteArray {
        val r = derInteger(raw.copyOfRange(0, 32))
        val s = derInteger(raw.copyOfRange(32, 64))
        val body = byteArrayOf(0x02, r.size.toByte()) + r + byteArrayOf(0x02, s.size.toByte()) + s
        return byteArrayOf(0x30, body.size.toByte()) + body
    }

    private fun derInteger(value: ByteArray): ByteArray {
        val firstNonZero = value.indexOfFirst { it != 0.toByte() }.let { if (it < 0) value.lastIndex else it }
        val stripped = value.copyOfRange(firstNonZero, value.size)
        return if ((stripped[0].toInt() and 0x80) != 0) byteArrayOf(0) + stripped else stripped
    }
}
