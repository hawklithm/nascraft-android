package com.hawky.nascraft

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class StoredNonce(
    val value: String,
    val expiresAtEpochSeconds: Long,
    val purpose: String,
    val bindingId: String,
)

data class NativeSessionState(
    val serverId: String,
    val userId: String,
    val userDisplayName: String,
    val userRole: String,
    val deviceId: String,
    val deviceName: String,
    val sessionId: String,
    val accessToken: String,
    val accessExpiresAt: String,
    val refreshToken: String?,
    val refreshExpiresAt: String?,
    val deviceNonce: String,
    val deviceMode: String = "personal",
    val pendingRefreshRequestId: String? = null,
)

data class PendingNativeFlow(
    val kind: Kind,
    val serverId: String,
    val keyAlias: String,
    val deviceName: String,
    val mode: String,
    val stage: String,
    val requestId: String? = null,
    val webUrl: String? = null,
    val locator: String? = null,
    val humanCode: String? = null,
    val registrationId: String? = null,
    val transactionId: String? = null,
    val publicKeyJson: String? = null,
    val otp: String? = null,
    val credentialJson: String? = null,
    val expiresAtEpochSeconds: Long? = null,
) {
    enum class Kind { PAIRING, REMOTE_OTP, LOGIN }
}

/** Encrypted connection state for a configured server that is not currently selected. */
data class ServerAuthSnapshot(
    val serverId: String,
    val pendingFlow: PendingNativeFlow? = null,
    val session: NativeSessionState? = null,
    val installationId: String? = null,
    val installationKeyAlias: String? = null,
    val lanNonces: List<StoredNonce> = emptyList(),
    val remoteNonces: List<StoredNonce> = emptyList(),
    val autoBackupEnabled: Boolean = false,
)

fun PendingNativeFlow.isExpired(nowEpochSeconds: Long = TimestampCodec.nowEpochSeconds()): Boolean =
    expiresAtEpochSeconds?.let { it <= nowEpochSeconds } ?: false

data class DeviceAuthState(
    val profiles: List<ServerProfile> = emptyList(),
    val inactiveServerStates: List<ServerAuthSnapshot> = emptyList(),
    val selectedServerId: String? = null,
    val pendingFlow: PendingNativeFlow? = null,
    val session: NativeSessionState? = null,
    val installationId: String? = null,
    val installationKeyAlias: String? = null,
    val lanNonces: List<StoredNonce> = emptyList(),
    val remoteNonces: List<StoredNonce> = emptyList(),
    val autoBackupEnabled: Boolean = false,
) {
    fun nonces(realm: ApiRealm, purpose: String? = null, bindingId: String? = null): List<StoredNonce> = (when (realm) {
        ApiRealm.LAN_NATIVE -> lanNonces
        ApiRealm.REMOTE_API -> remoteNonces
    }).let { values -> values.filter { (purpose == null || it.purpose == purpose) && (bindingId == null || it.bindingId == bindingId) } }

    fun profile(): ServerProfile? = profiles.firstOrNull { it.serverId == selectedServerId }

    fun withoutExpiredNonces(nowEpochSeconds: Long): DeviceAuthState = copy(
        lanNonces = lanNonces.filter { it.expiresAtEpochSeconds > nowEpochSeconds },
        remoteNonces = remoteNonces.filter { it.expiresAtEpochSeconds > nowEpochSeconds },
    )

    /** A 401/revocation clears credentials but preserves server identity and resumable task metadata. */
    fun clearSession(): DeviceAuthState = copy(session = null, lanNonces = emptyList(), remoteNonces = emptyList())
}

object DeviceAuthStateCodec {
    private const val MAGIC = 0x4e415343
    private const val VERSION = 6
    private const val MAX_PROFILES = 64
    private const val MAX_NONCES = 32

    fun encode(state: DeviceAuthState): ByteArray {
        require(state.profiles.size <= MAX_PROFILES)
        return ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(VERSION)
                out.writeInt(state.profiles.size)
                state.profiles.forEach { profile ->
                    out.writeUTF(profile.serverId)
                    out.writeUTF(profile.displayName)
                    out.writeUTF(profile.rpId)
                    out.writeUTF(profile.lanWebUrl)
                    out.writeUTF(profile.lanApiOrigin)
                    out.writeUTF(profile.remoteApiOrigin)
                }
                out.writeNullable(state.selectedServerId)
                out.writeNullable(state.installationId)
                out.writeNullable(state.installationKeyAlias)
                out.writeBoolean(state.pendingFlow != null)
                state.pendingFlow?.let { flow ->
                    out.writeUTF(flow.kind.name)
                    out.writeUTF(flow.serverId)
                    out.writeUTF(flow.keyAlias)
                    out.writeUTF(flow.deviceName)
                    out.writeUTF(flow.mode)
                    out.writeUTF(flow.stage)
                    out.writeNullable(flow.requestId)
                    out.writeNullable(flow.webUrl)
                    out.writeNullable(flow.locator)
                    out.writeNullable(flow.humanCode)
                    out.writeNullable(flow.registrationId)
                    out.writeNullable(flow.transactionId)
                    out.writeNullable(flow.publicKeyJson)
                    out.writeNullable(flow.otp)
                    out.writeNullable(flow.credentialJson)
                    out.writeBoolean(flow.expiresAtEpochSeconds != null)
                    flow.expiresAtEpochSeconds?.let(out::writeLong)
                }
                out.writeBoolean(state.session != null)
                state.session?.let { session ->
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
                }
                out.writeNonceList(state.lanNonces)
                out.writeNonceList(state.remoteNonces)
                out.writeBoolean(state.autoBackupEnabled)
                require(state.inactiveServerStates.size <= MAX_PROFILES)
                out.writeInt(state.inactiveServerStates.size)
                state.inactiveServerStates.forEach { snapshot ->
                    out.writeUTF(snapshot.serverId)
                    out.writeBoolean(snapshot.pendingFlow != null)
                    snapshot.pendingFlow?.let { out.writePendingFlow(it) }
                    out.writeBoolean(snapshot.session != null)
                    snapshot.session?.let { out.writeSession(it) }
                    out.writeNullable(snapshot.installationId)
                    out.writeNullable(snapshot.installationKeyAlias)
                    out.writeNonceList(snapshot.lanNonces)
                    out.writeNonceList(snapshot.remoteNonces)
                    out.writeBoolean(snapshot.autoBackupEnabled)
                }
            }
            bytes.toByteArray()
        }
    }

    fun decode(bytes: ByteArray): DeviceAuthState = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        require(input.readInt() == MAGIC) { "Unrecognized secure state" }
        val version = input.readInt()
        require(version in 2..VERSION) { "Unsupported secure state version" }
        val profileCount = input.readInt()
        require(profileCount in 0..MAX_PROFILES) { "Invalid server profile count" }
        val profiles = List(profileCount) {
            ServerProfile.validated(
                serverId = input.readUTF(),
                displayName = input.readUTF(),
                rpId = input.readUTF(),
                lanWebUrl = input.readUTF(),
                lanApiOrigin = input.readUTF(),
                remoteApiOrigin = input.readUTF(),
            )
        }
        val selectedServerId = input.readNullable()
        val installationId = input.readNullable()
        val installationKeyAlias = input.readNullable()
        val pending = if (input.readBoolean()) {
            PendingNativeFlow(
                kind = PendingNativeFlow.Kind.valueOf(input.readUTF()),
                serverId = input.readUTF(),
                keyAlias = input.readUTF(),
                deviceName = input.readUTF(),
                mode = input.readUTF(),
                stage = input.readUTF(),
                requestId = input.readNullable(),
                webUrl = input.readNullable(),
                locator = input.readNullable(),
                humanCode = input.readNullable(),
                registrationId = input.readNullable(),
                transactionId = input.readNullable(),
                publicKeyJson = input.readNullable(),
                otp = input.readNullable(),
                credentialJson = input.readNullable(),
                expiresAtEpochSeconds = if (input.readBoolean()) input.readLong() else null,
            )
        } else null
        val session = if (input.readBoolean()) {
            NativeSessionState(
                serverId = input.readUTF(),
                userId = input.readUTF(),
                userDisplayName = input.readUTF(),
                userRole = input.readUTF(),
                deviceId = input.readUTF(),
                deviceName = input.readUTF(),
                sessionId = input.readUTF(),
                accessToken = input.readUTF(),
                accessExpiresAt = input.readUTF(),
                refreshToken = input.readNullable(),
                refreshExpiresAt = input.readNullable(),
                deviceNonce = input.readUTF(),
                deviceMode = if (version >= 3) input.readUTF() else "personal",
                pendingRefreshRequestId = if (version >= 5) input.readNullable() else null,
            )
        } else null
        val lanNonces = input.readNonceList(version)
        val remoteNonces = input.readNonceList(version)
        val autoBackupEnabled = input.readBoolean()
        val inactiveServerStates = if (version >= 4) {
            val snapshotCount = input.readInt()
            require(snapshotCount in 0..MAX_PROFILES) { "Invalid server auth snapshot count" }
            List(snapshotCount) {
                val serverId = input.readUTF()
                val pendingSnapshot = if (input.readBoolean()) input.readPendingFlow() else null
                val sessionSnapshot = if (input.readBoolean()) input.readSession(version) else null
                ServerAuthSnapshot(
                    serverId = serverId,
                    pendingFlow = pendingSnapshot,
                    session = sessionSnapshot,
                    installationId = input.readNullable(),
                    installationKeyAlias = input.readNullable(),
                    lanNonces = input.readNonceList(version),
                    remoteNonces = input.readNonceList(version),
                    autoBackupEnabled = input.readBoolean(),
                )
            }
        } else emptyList()
        val state = DeviceAuthState(
            profiles = profiles,
            inactiveServerStates = inactiveServerStates,
            selectedServerId = selectedServerId,
            pendingFlow = pending,
            session = session,
            installationId = installationId,
            installationKeyAlias = installationKeyAlias,
            lanNonces = lanNonces,
            remoteNonces = remoteNonces,
            autoBackupEnabled = autoBackupEnabled,
        )
        require(input.available() == 0) { "Trailing secure state data" }
        state
    }

    private fun DataOutputStream.writePendingFlow(flow: PendingNativeFlow) {
        writeUTF(flow.kind.name)
        writeUTF(flow.serverId)
        writeUTF(flow.keyAlias)
        writeUTF(flow.deviceName)
        writeUTF(flow.mode)
        writeUTF(flow.stage)
        writeNullable(flow.requestId)
        writeNullable(flow.webUrl)
        writeNullable(flow.locator)
        writeNullable(flow.humanCode)
        writeNullable(flow.registrationId)
        writeNullable(flow.transactionId)
        writeNullable(flow.publicKeyJson)
        writeNullable(flow.otp)
        writeNullable(flow.credentialJson)
        writeBoolean(flow.expiresAtEpochSeconds != null)
        flow.expiresAtEpochSeconds?.let(::writeLong)
    }

    private fun DataInputStream.readPendingFlow(): PendingNativeFlow = PendingNativeFlow(
        kind = PendingNativeFlow.Kind.valueOf(readUTF()),
        serverId = readUTF(),
        keyAlias = readUTF(),
        deviceName = readUTF(),
        mode = readUTF(),
        stage = readUTF(),
        requestId = readNullable(),
        webUrl = readNullable(),
        locator = readNullable(),
        humanCode = readNullable(),
        registrationId = readNullable(),
        transactionId = readNullable(),
        publicKeyJson = readNullable(),
        otp = readNullable(),
        credentialJson = readNullable(),
        expiresAtEpochSeconds = if (readBoolean()) readLong() else null,
    )

    private fun DataOutputStream.writeSession(session: NativeSessionState) {
        writeUTF(session.serverId)
        writeUTF(session.userId)
        writeUTF(session.userDisplayName)
        writeUTF(session.userRole)
        writeUTF(session.deviceId)
        writeUTF(session.deviceName)
        writeUTF(session.sessionId)
        writeUTF(session.accessToken)
        writeUTF(session.accessExpiresAt)
        writeNullable(session.refreshToken)
        writeNullable(session.refreshExpiresAt)
        writeUTF(session.deviceNonce)
        writeUTF(session.deviceMode)
        writeNullable(session.pendingRefreshRequestId)
    }

    private fun DataInputStream.readSession(version: Int): NativeSessionState = NativeSessionState(
        serverId = readUTF(),
        userId = readUTF(),
        userDisplayName = readUTF(),
        userRole = readUTF(),
        deviceId = readUTF(),
        deviceName = readUTF(),
        sessionId = readUTF(),
        accessToken = readUTF(),
        accessExpiresAt = readUTF(),
        refreshToken = readNullable(),
        refreshExpiresAt = readNullable(),
        deviceNonce = readUTF(),
        deviceMode = if (version >= 3) readUTF() else "personal",
        pendingRefreshRequestId = if (version >= 5) readNullable() else null,
    )

    private fun DataOutputStream.writeNullable(value: String?) {
        writeBoolean(value != null)
        if (value != null) writeUTF(value)
    }

    private fun DataInputStream.readNullable(): String? = if (readBoolean()) readUTF() else null

    private fun DataOutputStream.writeNonceList(values: List<StoredNonce>) {
        require(values.size <= MAX_NONCES)
        writeInt(values.size)
        values.forEach {
            writeUTF(it.value)
            writeLong(it.expiresAtEpochSeconds)
            writeUTF(it.purpose)
            writeUTF(it.bindingId)
        }
    }

    private fun DataInputStream.readNonceList(version: Int): List<StoredNonce> {
        val size = readInt()
        require(size in 0..MAX_NONCES) { "Invalid nonce count" }
        return List(size) {
            val value = readUTF()
            val expiresAt = readLong()
            val purpose = readUTF()
            if (version >= 6) StoredNonce(value, expiresAt, purpose, readUTF()) else null
        }.filterNotNull()
    }
}

/** Auth/session material is encrypted before it reaches SharedPreferences. */
interface DeviceAuthStorage {
    fun read(): DeviceAuthState
    fun write(state: DeviceAuthState)
    fun reset() { write(DeviceAuthState()) }
}

class AndroidKeystoreDeviceAuthStore(context: Context) : DeviceAuthStorage {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val random = SecureRandom()

    @Synchronized
    override fun read(): DeviceAuthState {
        val encoded = preferences.getString(STATE_KEY, null) ?: return DeviceAuthState()
        return try {
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            DeviceAuthStateCodec.decode(AesGcmEnvelope.decrypt(getOrCreateKey(), packed))
        } catch (e: Exception) {
            throw SecureStateUnavailableException("Saved device authorization cannot be opened", e)
        }
    }

    @Synchronized
    override fun write(state: DeviceAuthState) {
        val ciphertext = AesGcmEnvelope.encrypt(getOrCreateKey(), DeviceAuthStateCodec.encode(state), random)
        val success = preferences.edit()
            .putString(STATE_KEY, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .commit()
        check(success) { "Could not save protected device state" }
    }

    @Synchronized
    override fun reset() {
        runCatching {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (store.containsAlias(KEY_ALIAS)) store.deleteEntry(KEY_ALIAS)
        }
        check(preferences.edit().clear().commit()) { "Could not clear unreadable protected device state" }
    }

    @Synchronized
    fun clearCredentials() {
        val state = read()
        write(state.clearSession().copy(pendingFlow = null))
    }

    private fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val PREFERENCES = "nascraft_device_auth_secure_v2"
        private const val STATE_KEY = "ciphertext"
        private const val KEY_ALIAS = "nascraft.device-auth.aesgcm.v1"
    }
}

class SecureStateUnavailableException(message: String, cause: Throwable) : IllegalStateException(message, cause)

object AesGcmEnvelope {
    private const val IV_SIZE = 12
    private const val TAG_BITS = 128
    private val AAD = "nascraft-device-auth-v2".toByteArray(Charsets.UTF_8)

    fun encrypt(key: SecretKey, plaintext: ByteArray, random: SecureRandom = SecureRandom()): ByteArray {
        val iv = ByteArray(IV_SIZE).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(AAD)
        return byteArrayOf(1) + iv + cipher.doFinal(plaintext)
    }

    fun decrypt(key: SecretKey, envelope: ByteArray): ByteArray {
        require(envelope.size >= 1 + IV_SIZE + TAG_BITS / 8 && envelope[0].toInt() == 1) {
            "Invalid encrypted state envelope"
        }
        val iv = envelope.copyOfRange(1, 1 + IV_SIZE)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(AAD)
        return cipher.doFinal(envelope, 1 + IV_SIZE, envelope.size - 1 - IV_SIZE)
    }
}
