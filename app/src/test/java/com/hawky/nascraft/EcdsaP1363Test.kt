package com.hawky.nascraft

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

class EcdsaP1363Test {
    @Test
    fun derSignatureBecomesFixedWidthLowSAndStillVerifies() {
        val pair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val message = "nascraft-test-proof".toByteArray()
        val der = Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private)
            update(message)
            sign()
        }

        val p1363 = EcdsaP1363.toLowS(der)
        assertTrue(p1363.size == 64)
        assertTrue(EcdsaP1363.isLowS(p1363))
        assertTrue(Signature.getInstance("SHA256withECDSA").run {
            initVerify(pair.public)
            update(message)
            verify(toDer(p1363))
        })
    }

    @Test
    fun rejectsMalformedDerAndHighSWireValues() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            EcdsaP1363.fromDer(byteArrayOf(1, 2, 3))
        }
        val raw = ByteArray(64)
        raw[31] = 1
        raw[63] = 1
        assertTrue(EcdsaP1363.isLowS(raw))
        val highS = raw.copyOf()
        val order = BigInteger("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16)
        val s = order.subtract(BigInteger.ONE).toByteArray().let { if (it.size > 32) it.copyOfRange(1, it.size) else it }
        val fixed = ByteArray(32 - s.size) + s
        fixed.copyInto(highS, 32)
        assertFalse(EcdsaP1363.isLowS(highS))
    }

    private fun toDer(raw: ByteArray): ByteArray {
        require(raw.size == 64)
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
