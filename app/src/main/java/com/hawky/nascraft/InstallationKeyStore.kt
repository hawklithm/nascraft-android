package com.hawky.nascraft

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec

data class InstallationKeyMaterial(
    val alias: String,
    val publicKeySpki: ByteArray,
    val publicKeyBase64Url: String,
    val thumbprint: String,
)

/** P-256 signing keys are generated and retained by Android Keystore. */
class InstallationKeyStore {
    fun getOrCreate(alias: String = DEFAULT_ALIAS): InstallationKeyMaterial {
        require(ALIAS_PATTERN.matches(alias)) { "Invalid installation key alias" }
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (!store.containsAlias(alias)) generate(alias)
        val certificate = store.getCertificate(alias)
            ?: throw IllegalStateException("Installation public key is unavailable")
        val spki = certificate.publicKey.encoded
        return InstallationKeyMaterial(
            alias = alias,
            publicKeySpki = spki,
            publicKeyBase64Url = InstallationKeyFormat.publicKeyBase64Url(spki),
            thumbprint = InstallationKeyFormat.thumbprint(spki),
        )
    }

    fun signP1363(alias: String, message: ByteArray): ByteArray {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val privateKey = store.getKey(alias, null) as? java.security.PrivateKey
            ?: throw IllegalStateException("Installation signing key is unavailable")
        val der = Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(message)
            sign()
        }
        return EcdsaP1363.toLowS(der)
    }

    fun delete(alias: String = DEFAULT_ALIAS) {
        require(ALIAS_PATTERN.matches(alias)) { "Invalid installation key alias" }
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (store.containsAlias(alias)) store.deleteEntry(alias)
    }

    private fun generate(alias: String) {
        val generator = KeyPairGenerator.getInstance("EC", ANDROID_KEYSTORE)
        generator.initialize(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        generator.generateKeyPair()
    }

    companion object {
        const val DEFAULT_ALIAS = "nascraft.installation.es256.v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private val ALIAS_PATTERN = Regex("[A-Za-z0-9._-]{1,120}")
    }
}

/** Contract-specific SPKI and thumbprint encodings, also exercised with JVM-generated P-256 keys. */
object InstallationKeyFormat {
    fun publicKeyBase64Url(spkiDer: ByteArray): String = Base64Codec.encodeUrlNoPadding(spkiDer)

    fun thumbprint(spkiDer: ByteArray): String = Base64Codec.encodeUrlNoPadding(
        MessageDigest.getInstance("SHA-256").digest(spkiDer),
    )
}

/** Converts JCA DER ECDSA output into the contract's low-S IEEE P1363 r||s form. */
object EcdsaP1363 {
    private val P256_ORDER = BigInteger("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16)
    private val P256_HALF_ORDER = P256_ORDER.shiftRight(1)

    fun fromDer(der: ByteArray): ByteArray {
        val reader = DerReader(der)
        require(reader.byte() == 0x30) { "Expected ECDSA DER sequence" }
        val sequenceLength = reader.length()
        require(sequenceLength == reader.remaining()) { "Invalid ECDSA DER sequence length" }
        val r = reader.integer()
        val s = reader.integer()
        require(reader.remaining() == 0) { "Trailing bytes in ECDSA DER signature" }
        return encode(r, s)
    }

    fun toLowS(der: ByteArray): ByteArray {
        val raw = fromDer(der)
        val r = BigInteger(1, raw.copyOfRange(0, 32))
        val originalS = BigInteger(1, raw.copyOfRange(32, 64))
        val lowS = if (originalS > P256_HALF_ORDER) P256_ORDER.subtract(originalS) else originalS
        return encode(r, lowS)
    }

    fun isLowS(signature: ByteArray): Boolean {
        if (signature.size != 64) return false
        val r = BigInteger(1, signature.copyOfRange(0, 32))
        val s = BigInteger(1, signature.copyOfRange(32, 64))
        return r.signum() > 0 && r < P256_ORDER && s.signum() > 0 && s <= P256_HALF_ORDER
    }

    private fun encode(r: BigInteger, s: BigInteger): ByteArray {
        require(r.signum() > 0 && r < P256_ORDER && s.signum() > 0 && s < P256_ORDER) {
            "ECDSA scalar is outside the P-256 group order"
        }
        return fixed32(r) + fixed32(s)
    }

    private fun fixed32(value: BigInteger): ByteArray {
        val bytes = value.toByteArray().let { if (it.size > 32 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
        require(bytes.size <= 32) { "ECDSA scalar is too large" }
        return ByteArray(32 - bytes.size) + bytes
    }

    private class DerReader(private val data: ByteArray) {
        private var offset = 0
        fun remaining(): Int = data.size - offset
        fun byte(): Int {
            require(offset < data.size) { "Truncated ECDSA DER signature" }
            return data[offset++].toInt() and 0xff
        }
        fun length(): Int {
            val first = byte()
            if (first < 0x80) return first
            val count = first and 0x7f
            require(count in 1..2) { "Invalid ECDSA DER length" }
            var result = 0
            repeat(count) { result = (result shl 8) or byte() }
            require(result >= 0x80) { "Non-canonical ECDSA DER length" }
            return result
        }
        fun integer(): BigInteger {
            require(byte() == 0x02) { "Expected ECDSA DER integer" }
            val length = length()
            require(length in 1..33 && length <= remaining()) { "Invalid ECDSA DER integer length" }
            val bytes = data.copyOfRange(offset, offset + length)
            offset += length
            require((bytes[0].toInt() and 0x80) == 0) { "Negative ECDSA DER integer" }
            require(!(bytes.size > 1 && bytes[0] == 0.toByte() && (bytes[1].toInt() and 0x80) == 0)) {
                "Non-canonical ECDSA DER integer"
            }
            return BigInteger(1, bytes)
        }
    }
}
