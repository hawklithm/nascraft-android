package com.hawky.nascraft

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

class InstallationKeyFormatTest {
    @Test
    fun generatedP256SpkiUsesContractBase64UrlThumbprintAndDerEncoding() {
        val publicKey = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair().public as ECPublicKey
        val spki = publicKey.encoded
        val encoded = InstallationKeyFormat.publicKeyBase64Url(spki)
        val thumbprint = InstallationKeyFormat.thumbprint(spki)

        assertEquals("EC", publicKey.algorithm)
        assertEquals(0x30, spki.first().toInt() and 0xff)
        assertArrayEquals(spki, Base64Codec.decodeUrlNoPadding(encoded))
        assertEquals(
            Base64Codec.encodeUrlNoPadding(MessageDigest.getInstance("SHA-256").digest(spki)),
            thumbprint,
        )
        assertTrue(encoded.matches(Regex("[A-Za-z0-9_-]+")))
        assertTrue(thumbprint.matches(Regex("[A-Za-z0-9_-]{43}")))
        assertFalse(encoded.contains('='))
    }
}
