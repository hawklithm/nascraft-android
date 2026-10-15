package com.hawky.nascraft

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

class DownloadResumeTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun headPreparationResponsesRequireBodylessStatusSpecificMetadata() = runBlocking {
        val payload = byteArrayOf(9)
        val env = httpsEnvironment(payload, sha256(payload))
        val requestCount = AtomicInteger()
        env.server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                assertEquals("HEAD", request.method)
                return when (requestCount.getAndIncrement()) {
                    0 -> MockResponse().setResponseCode(503)
                        .setHeader("Content-Length", "4")
                        .setHeader("Retry-After", "4")
                        .setHeader("Nascraft-Download-State", "busy")
                    1 -> MockResponse().setResponseCode(503)
                        .setHeader("Content-Length", "0")
                        .setHeader("Nascraft-Download-State", "busy")
                    2 -> MockResponse().setResponseCode(503)
                        .setHeader("Content-Length", "0")
                        .setHeader("Retry-After", "4")
                        .setHeader("Nascraft-Download-State", "preparing")
                    3 -> MockResponse().setResponseCode(503)
                        .setHeader("Content-Length", "0")
                        .setHeader("Retry-After", "4")
                        .setHeader("Nascraft-Download-State", "busy")
                    else -> MockResponse().setResponseCode(202)
                        .setHeader("Content-Length", "0")
                        .setHeader("Retry-After", "0")
                        .setHeader("Nascraft-Download-State", "preparing")
                }
            }
        }

        repeat(3) {
            val failure = try {
                env.transport.headDownload("/api/download/${env.fileId}", "head-validation")
                null
            } catch (caught: NativeApiException) {
                caught
            }
            assertEquals("BAD_DOWNLOAD_STATUS", failure?.apiCode)
        }
        val busy = env.transport.headDownload("/api/download/${env.fileId}", "head-validation")
        assertEquals(503, busy.statusCode)
        assertEquals(4L, busy.retryAfterSeconds)
        assertNull(busy.metadata)
        val preparing = env.transport.headDownload("/api/download/${env.fileId}", "head-validation")
        assertEquals(202, preparing.statusCode)
        assertEquals(0L, preparing.retryAfterSeconds)
        env.close()
    }

    @Test
    fun accessOrSessionLossPausesWithoutDeletingPrivateStaging() = runBlocking {
        val identity = identity()
        val fileId = "file_id_12345678"
        val transport = object : DownloadResumeTransport {
            override suspend fun headDownload(pathAndQuery: String, operationId: String): DownloadHeadResult =
                throw NativeApiException(401, "SESSION_INVALID", "Sign in again")

            override suspend fun downloadRange(
                pathAndQuery: String,
                metadata: DownloadFileMetadata,
                start: Long,
                endInclusive: Long,
                output: java.io.OutputStream,
                operationId: String,
            ) = error("a terminal HEAD failure must not start a range")

            override fun cancelDownload(operationId: String) = Unit
        }
        val store = DownloadResumeStore(temporaryFolder.newFolder("auth-wait"))
        val manager = DownloadResumeManager(store, transport)
        val task = manager.createOrLoad(identity, fileId, "pending.bin", "/api/download/$fileId", 8L)

        manager.resumeNow(task.taskId, identity)

        val paused = store.load(task.taskId)!!
        assertEquals(DownloadTaskStatus.WAITING_FOR_AUTH, paused.status)
        assertTrue(store.stageFile(task.taskId).exists())
    }

    @Test
    fun processRecoveryRestoresPausedStateAndTruncatesUncommittedTail() {
        val identity = identity()
        val fileId = "file_id_12345678"
        val storeRoot = temporaryFolder.newFolder("process-recovery")
        val store = DownloadResumeStore(storeRoot)
        val task = store.createOrLoad(identity, fileId, "recover.bin", "/api/download/$fileId", 4L)
        val fullPayload = byteArrayOf(1, 2, 3, 4)
        store.setMetadata(task.taskId, metadata(fullPayload, identity.serverId, fileId))
        java.io.RandomAccessFile(store.stageFile(task.taskId), "rw").use { stage ->
            stage.write(byteArrayOf(1, 2, 99, 100))
            stage.fd.sync()
        }
        val firstSegment = store.buildSegmentCheckpoint(task.taskId, 0, 1)
        store.commitSegment(task.taskId, firstSegment)
        store.saveState(task.taskId, DownloadTaskStatus.DOWNLOADING)

        val restored = DownloadResumeStore(storeRoot)
        restored.recoverInterrupted()
        assertEquals(DownloadTaskStatus.PAUSED, restored.load(task.taskId)!!.status)
        assertEquals(2L, restored.repairStage(task.taskId))
        assertArrayEquals(byteArrayOf(1, 2), restored.stageFile(task.taskId).readBytes())
    }

    @Test
    fun exportClosesPickerOutputWhenTaskIsGoneOrIdentityChanged() = runBlocking {
        val identity = identity()
        val fileId = "file_id_12345678"
        val store = DownloadResumeStore(temporaryFolder.newFolder("export-ownership"))
        val manager = DownloadResumeManager(store, idleTransport())
        val deleted = manager.createOrLoad(identity, fileId, "gone.bin", "/api/download/$fileId", 0L)
        manager.cancel(deleted.taskId)
        withTimeout(5_000) {
            while (store.load(deleted.taskId) != null) delay(10)
        }

        val missingTarget = TrackingOutputStream()
        val missingFailure = runCatching { manager.exportVerified(deleted.taskId, identity, missingTarget) }.exceptionOrNull()
        assertNotNull(missingFailure)
        assertTrue(missingTarget.wasClosed)

        val current = manager.createOrLoad(identity, fileId, "identity.bin", "/api/download/$fileId", 0L)
        val changedTarget = TrackingOutputStream()
        val changedIdentity = identity.copy(userId = "another_user_12345678")
        val identityFailure = runCatching { manager.exportVerified(current.taskId, changedIdentity, changedTarget) }.exceptionOrNull()
        assertNotNull(identityFailure)
        assertTrue(changedTarget.wasClosed)
        assertEquals(DownloadTaskStatus.PENDING, store.load(current.taskId)!!.status)
    }

    @Test
    fun exportIsSuccessfulOnlyAfterOneSuccessfulProviderClose() = runBlocking {
        val identity = identity()
        val fileId = "file_id_12345678"
        val payload = byteArrayOf(3, 1, 4, 1, 5)
        val expected = metadata(payload, identity.serverId, fileId)
        val transport = object : DownloadResumeTransport {
            override suspend fun headDownload(pathAndQuery: String, operationId: String) = DownloadHeadResult(200, expected)

            override suspend fun downloadRange(
                pathAndQuery: String,
                metadata: DownloadFileMetadata,
                start: Long,
                endInclusive: Long,
                output: java.io.OutputStream,
                operationId: String,
            ): DownloadSegmentReceipt {
                val count = (endInclusive - start + 1).toInt()
                output.write(payload, start.toInt(), count)
                return DownloadSegmentReceipt(206, start, endInclusive, payload.size.toLong(), count.toLong(), expected)
            }

            override fun cancelDownload(operationId: String) = Unit
        }
        val store = DownloadResumeStore(temporaryFolder.newFolder("export-close-failure"))
        val manager = DownloadResumeManager(store, transport)
        val task = manager.createOrLoad(identity, fileId, "close.bin", "/api/download/$fileId", payload.size.toLong())
        manager.resumeNow(task.taskId, identity)
        assertEquals(DownloadTaskStatus.READY_TO_EXPORT, store.load(task.taskId)!!.status)

        val target = ThrowingCloseOutputStream()
        val failure = runCatching { manager.exportVerified(task.taskId, identity, target) }.exceptionOrNull()

        assertNotNull(failure)
        assertEquals(1, target.closeCount)
        assertArrayEquals(payload, target.toByteArray())
        assertEquals(DownloadTaskStatus.EXPORT_FAILED, store.load(task.taskId)!!.status)
    }

    @Test
    fun signedHttpsSegmentsResumeWithFreshNoncesAndExportOnlyAfterWholeFileHash() = runBlocking {
        val payload = ByteArray(4 * 1024 * 1024 + 37) { ((it * 31) % 251).toByte() }
        val expectedDigest = sha256(payload)
        val env = httpsEnvironment(payload, expectedDigest)
        val requests = ConcurrentLinkedQueue<RecordedRequest>()
        env.server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return when (request.method) {
                    "HEAD" -> metadataResponse(200, env, payload.size.toLong(), expectedDigest)
                    "GET" -> {
                        val range = parseRange(request.getHeader("Range")) ?: return MockResponse().setResponseCode(400)
                        if (request.getHeader("If-Range") != env.etag) return MockResponse().setResponseCode(200)
                        if (range.first < 0 || range.last >= payload.size) return MockResponse().setResponseCode(416)
                        val body = payload.copyOfRange(range.first, range.last + 1)
                        metadataResponse(206, env, body.size.toLong(), expectedDigest)
                            .setHeader("Content-Range", "bytes ${range.first}-${range.last}/${payload.size}")
                            .setBody(Buffer().write(body))
                    }
                    else -> MockResponse().setResponseCode(405)
                }
            }
        }

        val store = DownloadResumeStore(temporaryFolder.newFolder("signed-download"))
        val manager = DownloadResumeManager(store, env.transport)
        val task = manager.createOrLoad(env.identity, env.fileId, "large.bin", "/api/download/${env.fileId}", payload.size.toLong())
        manager.resumeNow(task.taskId, env.identity)

        val ready = store.load(task.taskId)!!
        assertEquals(DownloadTaskStatus.READY_TO_EXPORT, ready.status)
        assertEquals(payload.size.toLong(), ready.committedBytes)
        assertEquals(listOf(4L * 1024 * 1024, 37L), ready.segments.map { it.length })
        assertArrayEquals(payload, store.stageFile(task.taskId).readBytes())

        val exported = TrackingOutputStream()
        manager.exportVerified(task.taskId, env.identity, exported)
        assertEquals(DownloadTaskStatus.EXPORTED, store.load(task.taskId)!!.status)
        assertArrayEquals(payload, exported.toByteArray())
        assertTrue(exported.wasClosed)

        val observed = requests.toList()
        assertEquals(4, observed.size) // one HEAD, two ranges, and a fresh HEAD before SAF export
        val nonces = observed.map { it.getHeader("Nascraft-Nonce") }
        assertTrue(nonces.all { !it.isNullOrBlank() })
        assertEquals(nonces.size, nonces.distinct().size)
        observed.filter { it.method == "GET" }.forEach { request ->
            val range = request.getHeader("Range")
            assertNotNull(range)
            assertEquals(env.etag, request.getHeader("If-Range"))
            assertTrue(request.getHeader("Signature-Input")!!.contains("\"range\" \"if-range\""))
            assertTrue(request.getHeader("Signature")!!.startsWith("sig1=:"))
            assertTrue(request.getHeader("Content-Digest")!!.startsWith("sha-256=:"))
        }
        assertEquals("bytes=0-${4 * 1024 * 1024 - 1}", observed[1].getHeader("Range"))
        assertEquals("bytes=${4 * 1024 * 1024}-${payload.lastIndex}", observed[2].getHeader("Range"))
        env.close()
    }

    @Test
    fun interruptedRangeRetriesFromCommittedOffsetAndRejectsWholeResponseToRange() = runBlocking {
        val payload = ByteArray(64 * 1024) { (it % 239).toByte() }
        val digest = sha256(payload)
        val env = httpsEnvironment(payload, digest)
        val rangeCount = AtomicInteger()
        env.server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.method) {
                "HEAD" -> metadataResponse(200, env, payload.size.toLong(), digest)
                "GET" -> {
                    val index = rangeCount.incrementAndGet()
                    if (index == 1) {
                        MockResponse()
                            .setResponseCode(206)
                            .setHeaders(metadataHeaders(env, payload.size.toLong(), digest))
                            .setHeader("Content-Range", "bytes 0-${payload.lastIndex}/${payload.size}")
                            .setHeader("Content-Length", payload.size)
                            .setBody(Buffer().write(payload.copyOfRange(0, 1024)))
                            .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
                    } else {
                        metadataResponse(206, env, payload.size.toLong(), digest)
                            .setHeader("Content-Range", "bytes 0-${payload.lastIndex}/${payload.size}")
                            .setBody(Buffer().write(payload))
                    }
                }
                else -> MockResponse().setResponseCode(405)
            }
        }

        val store = DownloadResumeStore(temporaryFolder.newFolder("interrupted"))
        val manager = DownloadResumeManager(store, env.transport, wait = {})
        val task = manager.createOrLoad(env.identity, env.fileId, "retry.bin", "/api/download/${env.fileId}", payload.size.toLong())
        manager.resumeNow(task.taskId, env.identity)
        val resumed = store.load(task.taskId)!!
        assertTrue("the first TLS response must be retried", rangeCount.get() >= 2)
        assertEquals(DownloadTaskStatus.READY_TO_EXPORT, resumed.status)
        assertEquals(1, resumed.segments.size)
        assertArrayEquals(payload, store.stageFile(task.taskId).readBytes())
        env.close()

        val changedPayload = ByteArray(32 * 1024) { 7 }
        val changed = httpsEnvironment(changedPayload, sha256(changedPayload))
        changed.server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.method) {
                "HEAD" -> metadataResponse(200, changed, changedPayload.size.toLong(), sha256(changedPayload))
                "GET" -> MockResponse().setResponseCode(200).setBody(Buffer().write(changedPayload))
                else -> MockResponse().setResponseCode(405)
            }
        }
        val changedStore = DownloadResumeStore(temporaryFolder.newFolder("source-changed"))
        val changedManager = DownloadResumeManager(changedStore, changed.transport)
        val changedTask = changedManager.createOrLoad(changed.identity, changed.fileId, "changed.bin", "/api/download/${changed.fileId}", changedPayload.size.toLong())
        changedManager.resumeNow(changedTask.taskId, changed.identity)
        assertEquals(DownloadTaskStatus.SOURCE_CHANGED, changedStore.load(changedTask.taskId)!!.status)
        assertEquals(0L, changedStore.stageFile(changedTask.taskId).length())
        changed.close()
    }

    @Test
    fun wrongCompleteDigestIsNeverExportable() = runBlocking {
        val expected = ByteArray(8192) { 1 }
        val served = ByteArray(8192) { 2 }
        val env = httpsEnvironment(expected, sha256(expected))
        env.server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.method) {
                "HEAD" -> metadataResponse(200, env, expected.size.toLong(), sha256(expected))
                "GET" -> metadataResponse(206, env, served.size.toLong(), sha256(expected))
                    .setHeader("Content-Range", "bytes 0-${served.lastIndex}/${served.size}")
                    .setBody(Buffer().write(served))
                else -> MockResponse().setResponseCode(405)
            }
        }
        val store = DownloadResumeStore(temporaryFolder.newFolder("bad-digest"))
        val manager = DownloadResumeManager(store, env.transport)
        val task = manager.createOrLoad(env.identity, env.fileId, "tampered.bin", "/api/download/${env.fileId}", expected.size.toLong())
        manager.resumeNow(task.taskId, env.identity)
        assertEquals(DownloadTaskStatus.CORRUPT, store.load(task.taskId)!!.status)
        assertFalse(store.load(task.taskId)!!.status in setOf(DownloadTaskStatus.READY_TO_EXPORT, DownloadTaskStatus.EXPORTED))
        env.close()
    }

    @Test
    fun pauseCancelsActiveRequestAndDiskWorkRunsOffTheCallingThread() = runBlocking {
        val identity = identity()
        val fileId = "file_id_12345678"
        val enteredRanges = Channel<Thread>(Channel.UNLIMITED)
        val cancelCount = AtomicInteger()
        val transport = object : DownloadResumeTransport {
            override suspend fun headDownload(pathAndQuery: String, operationId: String): DownloadHeadResult =
                DownloadHeadResult(200, metadata(ByteArray(256 * 1024) { 4 }, identity.serverId, fileId))

            override suspend fun downloadRange(
                pathAndQuery: String,
                metadata: DownloadFileMetadata,
                start: Long,
                endInclusive: Long,
                output: java.io.OutputStream,
                operationId: String,
            ): DownloadSegmentReceipt {
                enteredRanges.send(Thread.currentThread())
                awaitCancellation()
            }

            override fun cancelDownload(operationId: String) { cancelCount.incrementAndGet() }
        }
        val store = DownloadResumeStore(temporaryFolder.newFolder("pause-cancel"))
        val rangedMetadata = metadata(ByteArray(256 * 1024) { 4 }, identity.serverId, fileId)
        val rangedManager = DownloadResumeManager(store, transport)
        val task = rangedManager.createOrLoad(identity, fileId, "pause.bin", "/api/download/$fileId", rangedMetadata.totalSize)
        val callerThread = Thread.currentThread()
        val scope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val worker = scope.launch { rangedManager.resumeNow(task.taskId, identity) }
        val rangeThread = enteredRanges.receive()
        assertNotEquals(callerThread, rangeThread)
        rangedManager.pause(task.taskId)
        worker.join()
        assertTrue(cancelCount.get() > 0)
        withTimeout(5_000) {
            while (store.load(task.taskId)?.status != DownloadTaskStatus.PAUSED) delay(10)
        }
        assertEquals(DownloadTaskStatus.PAUSED, store.load(task.taskId)!!.status)
        assertTrue(store.load(task.taskId)!!.segments.isEmpty())
        assertEquals(0L, store.stageFile(task.taskId).length())
        val logoutRace = scope.launch { rangedManager.resumeNow(task.taskId, identity) }
        enteredRanges.receive()
        rangedManager.clearIdentity(identity)
        logoutRace.join()
        withTimeout(5_000) {
            while (store.load(task.taskId) != null || store.stageFile(task.taskId).exists()) delay(10)
        }
        assertNull(store.load(task.taskId))
        assertTrue(rangedManager.visibleTasks(identity).isEmpty())
        scope.cancel()
    }

    @Test
    fun explicitLocalLogoutWithoutSessionStillRemovesThisInstallationStaging() = runBlocking {
        val identity = identity()
        val profile = ServerProfile.validated(
            identity.serverId, "Test NAS", "nas.test", "https://web.nas.test", "https://nas.test", "",
        )
        val initial = DeviceAuthState(
            profiles = listOf(profile),
            selectedServerId = identity.serverId,
            installationId = identity.installationId,
            installationKeyAlias = "test-key",
        )
        val repository = DeviceAuthRepository(TestStorage(initial))
        val store = DownloadResumeStore(temporaryFolder.newFolder("logout-no-session"))
        val manager = DownloadResumeManager(
            store,
            object : DownloadResumeTransport {
                override suspend fun headDownload(pathAndQuery: String, operationId: String) = error("not used")
                override suspend fun downloadRange(
                    pathAndQuery: String,
                    metadata: DownloadFileMetadata,
                    start: Long,
                    endInclusive: Long,
                    output: java.io.OutputStream,
                    operationId: String,
                ) = error("not used")
                override fun cancelDownload(operationId: String) = Unit
            },
        )
        val task = manager.createOrLoad(identity, "file_id_12345678", "offline.bin", "/api/download/file_id_12345678", 64L)
        var logoutCallbackReceived = false
        val coordinator = NativeAuthCoordinator(
            InstallationKeyStore(), repository, NativeAuthApi(InstallationKeyStore(), repository),
            onExplicitLogout = { session ->
                logoutCallbackReceived = true
                if (session == null) {
                    val snapshot = repository.snapshot()
                    if (snapshot.selectedServerId != null && snapshot.installationId != null) {
                        manager.clearInstallation(snapshot.selectedServerId, snapshot.installationId)
                    }
                } else {
                    manager.clearIdentity(DownloadAuthorizationIdentity(session.serverId, session.userId, session.deviceId))
                }
            },
        )

        coordinator.logoutLocally()
        withTimeout(5_000) {
            while (store.load(task.taskId) != null || store.stageFile(task.taskId).exists() || manager.visibleTasks(identity).isNotEmpty()) delay(10)
        }
        assertTrue(logoutCallbackReceived)
        assertNull(repository.snapshot().session)
        assertNull(manager.tasks.value.firstOrNull { it.taskId == task.taskId })
    }

    private data class HttpsEnvironment(
        val server: MockWebServer,
        val profile: ServerProfile,
        val identity: DownloadAuthorizationIdentity,
        val fileId: String,
        val etag: String,
        val transport: DownloadResumeTransport,
    ) {
        fun close() = server.shutdown()
    }

    private fun httpsEnvironment(payload: ByteArray, digest: String): HttpsEnvironment {
        val fileId = "file_id_12345678"
        val serverId = "server_id_12345678"
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("nas.test").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val server = MockWebServer().apply {
            useHttps(serverTls.sslSocketFactory(), false)
            start()
        }
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        val client = OkHttpClient.Builder()
            .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> = listOf(InetAddress.getByName("127.0.0.1"))
            })
            .build()
        val origin = "https://nas.test:${server.port}"
        val profile = ServerProfile.validated(
            serverId, "Test NAS", "nas.test", "https://web.nas.test", origin, "",
        )
        val sessionId = "session_test_12345678"
        val deviceId = "device_test_12345678"
        val repository = DeviceAuthRepository(TestStorage())
        repository.addProfile(profile)
        repository.saveInstallationKeyAlias(serverId, "test-key")
        val nonceBatch = List(16) {
            StoredNonce(UUID.randomUUID().toString().replace("-", ""), TimestampCodec.nowEpochSeconds() + 3600, "session", sessionId)
        }
        repository.saveSession(
            serverId,
            "test-key",
            NativeSessionState(
                serverId = serverId,
                userId = "user_test_12345678",
                userDisplayName = "Test User",
                userRole = "member",
                deviceId = deviceId,
                deviceName = "Test Device",
                sessionId = sessionId,
                accessToken = "access-token-test",
                accessExpiresAt = (TimestampCodec.nowEpochSeconds() + 3600).toString(),
                refreshToken = "refresh-token-test",
                refreshExpiresAt = (TimestampCodec.nowEpochSeconds() + 7200).toString(),
                deviceNonce = "device_nonce_test_12345678",
            ),
            nonceBatch,
            emptyList(),
        )
        val api = NativeAuthApi(InstallationKeyStore(), repository, client) { _, _ -> lowSSignature() }
        val transport = object : DownloadResumeTransport {
            override suspend fun headDownload(pathAndQuery: String, operationId: String): DownloadHeadResult =
                api.businessDownloadHead(profile, ApiRealm.LAN_NATIVE, pathAndQuery, operationId)

            override suspend fun downloadRange(
                pathAndQuery: String,
                metadata: DownloadFileMetadata,
                start: Long,
                endInclusive: Long,
                output: java.io.OutputStream,
                operationId: String,
            ): DownloadSegmentReceipt {
                val receipt = api.businessDownloadRangeTo(profile, ApiRealm.LAN_NATIVE, pathAndQuery, metadata, start, endInclusive, output, operationId)
                return DownloadSegmentReceipt(
                    receipt.statusCode, receipt.start!!, receipt.endInclusive!!, receipt.totalSize!!, receipt.bytesWritten,
                    receipt.metadata!!,
                )
            }

            override fun cancelDownload(operationId: String) { api.cancelBusinessDownload(operationId) }
        }
        return HttpsEnvironment(server, profile, DownloadAuthorizationIdentity(serverId, "user_test_12345678", deviceId), fileId,
            "\"sha256-$digest\"", transport)
    }

    private fun metadataResponse(status: Int, env: HttpsEnvironment, contentLength: Long, digest: String): MockResponse =
        MockResponse().setResponseCode(status).setHeaders(metadataHeaders(env, contentLength, digest))

    private fun metadataHeaders(env: HttpsEnvironment, contentLength: Long, digest: String): okhttp3.Headers = okhttp3.Headers.Builder()
        .add("ETag", "\"sha256-$digest\"")
        .add("Content-Length", contentLength.toString())
        .add("Accept-Ranges", "bytes")
        .add("Nascraft-File-Sha256", digest)
        .add("Nascraft-Authorization-Generation", "a".repeat(64))
        .add("Nascraft-Server-Id", env.profile.serverId)
        .add("Nascraft-File-Id", env.fileId)
        .build()

    private fun metadata(bytes: ByteArray, serverId: String, fileId: String) = DownloadFileMetadata(
        bytes.size.toLong(), "\"sha256-${sha256(bytes)}\"", sha256(bytes), "b".repeat(64), serverId, fileId,
    )

    private fun parseRange(header: String?): IntRange? {
        val match = header?.let { Regex("bytes=([0-9]+)-([0-9]+)").matchEntire(it) } ?: return null
        return match.groupValues[1].toIntOrNull()?.let { start ->
            match.groupValues[2].toIntOrNull()?.takeIf { it >= start }?.let { end -> start..end }
        }
    }

    private fun identity() = DownloadAuthorizationIdentity("server_id_12345678", "user_test_12345678", "device_test_12345678")

    private fun idleTransport() = object : DownloadResumeTransport {
        override suspend fun headDownload(pathAndQuery: String, operationId: String): DownloadHeadResult = error("not used")
        override suspend fun downloadRange(
            pathAndQuery: String,
            metadata: DownloadFileMetadata,
            start: Long,
            endInclusive: Long,
            output: java.io.OutputStream,
            operationId: String,
        ) = error("not used")
        override fun cancelDownload(operationId: String) = Unit
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun lowSSignature() = ByteArray(64).apply { this[31] = 1; this[63] = 1 }

    private class TestStorage(initialState: DeviceAuthState = DeviceAuthState()) : DeviceAuthStorage {
        private var state = initialState
        override fun read(): DeviceAuthState = state
        override fun write(state: DeviceAuthState) { this.state = state }
    }

    private class TrackingOutputStream : ByteArrayOutputStream() {
        var wasClosed = false
        override fun close() {
            wasClosed = true
            super.close()
        }
    }

    private class ThrowingCloseOutputStream : ByteArrayOutputStream() {
        var closeCount = 0
        override fun close() {
            closeCount++
            throw java.io.IOException("The SAF provider failed to close the document")
        }
    }
}
