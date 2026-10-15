package com.hawky.nascraft

class DeviceAuthRepository(private val storage: DeviceAuthStorage) {
    @Synchronized
    fun snapshot(): DeviceAuthState = storage.read()

    @Synchronized
    fun resetSecureState() {
        storage.reset()
    }

    @Synchronized
    fun addProfile(profile: ServerProfile, select: Boolean = true): DeviceAuthState {
        val current = storage.read()
        val previous = current.profiles.firstOrNull { it.serverId == profile.serverId }
        if (previous != null && previous != profile) {
            throw ServerProfileConflictException("This server ID is already bound to different HTTPS origins")
        }
        val profiles = if (previous == null) current.profiles + profile else current.profiles
        val selected = if (select || current.selectedServerId == null) profile.serverId else current.selectedServerId
        val withProfiles = current.copy(profiles = profiles)
        val next = if (selected != current.selectedServerId) activateServer(withProfiles, selected)
        else withProfiles
        storage.write(next)
        return next
    }

    /** Updates only the remote API origin after the caller verifies the currently pinned LAN identity. */
    @Synchronized
    fun updateRemoteApiOriginAfterLanConfirmation(profile: ServerProfile): DeviceAuthState {
        val current = storage.read()
        val selected = current.profile()
            ?: throw IllegalStateException("Select the configured server before changing its remote address")
        require(current.selectedServerId == profile.serverId && selected.serverId == profile.serverId &&
            current.session?.serverId == profile.serverId && selected.copy(remoteApiOrigin = profile.remoteApiOrigin) == profile &&
            selected.remoteApiOrigin != profile.remoteApiOrigin) {
            "A remote address change requires an active session and an unchanged pinned LAN identity"
        }
        require(current.pendingFlow == null) { "Finish or cancel the current device flow before changing the remote address" }
        val profiles = current.profiles.map { if (it.serverId == profile.serverId) profile else it }
        val next = current.copy(profiles = profiles, remoteNonces = emptyList())
        storage.write(next)
        return next
    }

    @Synchronized
    fun selectProfile(serverId: String): DeviceAuthState {
        val current = storage.read()
        require(current.profiles.any { it.serverId == serverId }) { "Server profile is not configured" }
        if (current.selectedServerId == serverId) return current
        val next = activateServer(current, serverId)
        storage.write(next)
        return next
    }

    @Synchronized
    fun savePendingFlow(flow: PendingNativeFlow): DeviceAuthState {
        val current = storage.read()
        require(current.selectedServerId == flow.serverId) { "Pending flow does not match the selected server" }
        val next = current.copy(pendingFlow = flow)
        storage.write(next)
        return next
    }

    @Synchronized
    fun clearPendingFlow(): DeviceAuthState {
        val current = storage.read()
        val next = current.copy(pendingFlow = null)
        storage.write(next)
        return next
    }

    @Synchronized
    fun saveInstallationKeyAlias(serverId: String, alias: String): DeviceAuthState {
        val current = storage.read()
        require(current.selectedServerId == serverId) { "Installation key does not match the selected server" }
        val next = current.copy(installationKeyAlias = alias)
        storage.write(next)
        return next
    }

    @Synchronized
    fun saveSession(serverId: String, keyAlias: String, session: NativeSessionState, lan: List<StoredNonce>, remote: List<StoredNonce>): DeviceAuthState {
        val current = storage.read()
        require(current.selectedServerId == serverId && session.serverId == serverId) {
            "Session belongs to a different server"
        }
        val next = current.copy(
            pendingFlow = null,
            session = session,
            installationId = session.deviceId,
            installationKeyAlias = keyAlias,
            lanNonces = lan,
            remoteNonces = remote,
        )
        storage.write(next)
        return next
    }

    @Synchronized
    fun savePendingRefreshRequestId(requestId: String): DeviceAuthState {
        require(requestId.matches(Regex("[A-Za-z0-9_-]{16,128}"))) { "Invalid refresh request identifier" }
        val current = storage.read()
        val session = current.session ?: throw IllegalStateException("There is no active session to refresh")
        val next = current.copy(session = session.copy(pendingRefreshRequestId = requestId))
        storage.write(next)
        return next
    }

    /** Consumes before network I/O; a lost response burns that nonce and cannot replay it locally. */
    @Synchronized
    fun takeNonce(realm: ApiRealm, purpose: String, bindingId: String, nowEpochSeconds: Long): StoredNonce? {
        val original = storage.read()
        val current = original.withoutExpiredNonces(nowEpochSeconds)
        val pool = current.nonces(realm)
        val selected = pool.firstOrNull { it.purpose == purpose && it.bindingId == bindingId } ?: run {
            if (current != original) storage.write(current)
            return null
        }
        val nextPool = pool.filterNot { it == selected }
        val next = when (realm) {
            ApiRealm.LAN_NATIVE -> current.copy(lanNonces = nextPool)
            ApiRealm.REMOTE_API -> current.copy(remoteNonces = nextPool)
        }
        storage.write(next)
        return selected
    }

    @Synchronized
    fun appendNonces(realm: ApiRealm, values: List<StoredNonce>, expectedServerId: String): DeviceAuthState {
        val current = storage.read()
        require(current.selectedServerId == expectedServerId) { "Nonce response belongs to another server" }
        require(values.size <= 32 && values.all {
            NONCE.matches(it.value) && it.expiresAtEpochSeconds > 0 && it.bindingId.isNotBlank()
        }) {
            "Invalid server nonce batch"
        }
        val existing = current.nonces(realm)
        val merged = (existing + values).distinctBy { it.bindingId to it.value }.takeLast(32)
        val next = when (realm) {
            ApiRealm.LAN_NATIVE -> current.copy(lanNonces = merged)
            ApiRealm.REMOTE_API -> current.copy(remoteNonces = merged)
        }
        storage.write(next)
        return next
    }

    @Synchronized
    fun clearNonces(realm: ApiRealm, purpose: String, bindingId: String): DeviceAuthState {
        val current = storage.read()
        val filtered = current.nonces(realm).filterNot { it.purpose == purpose && it.bindingId == bindingId }
        val next = when (realm) {
            ApiRealm.LAN_NATIVE -> current.copy(lanNonces = filtered)
            ApiRealm.REMOTE_API -> current.copy(remoteNonces = filtered)
        }
        storage.write(next)
        return next
    }

    @Synchronized
    fun setAutoBackupEnabled(enabled: Boolean): DeviceAuthState {
        val current = storage.read()
        val next = current.copy(autoBackupEnabled = enabled)
        storage.write(next)
        return next
    }

    @Synchronized
    fun clearSession(): DeviceAuthState {
        val current = storage.read()
        val next = current.clearSession()
        storage.write(next)
        return next
    }

    @Synchronized
    fun clearRevokedInstallation(): DeviceAuthState {
        val current = storage.read()
        val next = current.clearSession().copy(
            pendingFlow = null,
            installationId = null,
            installationKeyAlias = null,
            inactiveServerStates = current.inactiveServerStates.filterNot { it.serverId == current.selectedServerId },
        )
        storage.write(next)
        return next
    }

    private fun activateServer(current: DeviceAuthState, serverId: String): DeviceAuthState {
        val inactive = current.inactiveServerStates.associateBy { it.serverId }.toMutableMap()
        current.selectedServerId?.let { activeId ->
            inactive[activeId] = ServerAuthSnapshot(
                serverId = activeId,
                pendingFlow = current.pendingFlow,
                session = current.session,
                installationId = current.installationId,
                installationKeyAlias = current.installationKeyAlias,
                lanNonces = current.lanNonces,
                remoteNonces = current.remoteNonces,
                autoBackupEnabled = current.autoBackupEnabled,
            )
        }
        val target = inactive.remove(serverId) ?: ServerAuthSnapshot(serverId)
        require(target.pendingFlow == null || target.pendingFlow.serverId == serverId) {
            "Saved device flow belongs to a different server"
        }
        require(target.session == null || target.session.serverId == serverId) {
            "Saved session belongs to a different server"
        }
        return current.copy(
            selectedServerId = serverId,
            pendingFlow = target.pendingFlow,
            session = target.session,
            installationId = target.installationId,
            installationKeyAlias = target.installationKeyAlias,
            lanNonces = target.lanNonces,
            remoteNonces = target.remoteNonces,
            autoBackupEnabled = target.autoBackupEnabled,
            inactiveServerStates = inactive.values.toList(),
        )
    }

    companion object {
        private val NONCE = Regex("[A-Za-z0-9_-]{16,512}")
    }
}

class ServerProfileConflictException(message: String) : IllegalStateException(message)
