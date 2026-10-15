package com.hawky.nascraft

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class DownloadAuthorizationIdentity(
    val serverId: String,
    val userId: String,
    val installationId: String,
)

data class DownloadFileMetadata(
    val totalSize: Long,
    val etag: String,
    val sha256: String,
    val authorizationGeneration: String,
    val serverId: String,
    val fileId: String,
) {
    fun isValid(): Boolean = totalSize >= 0 &&
        etag == "\"sha256-$sha256\"" &&
        SHA256.matches(sha256) && SHA256.matches(authorizationGeneration) &&
        serverId.isNotBlank() && fileId.matches(FILE_ID)

    companion object {
        private val SHA256 = Regex("[0-9a-f]{64}")
        private val FILE_ID = Regex("[A-Za-z0-9_-]{8,160}")
    }
}

data class DownloadHeadResult(
    val statusCode: Int,
    val metadata: DownloadFileMetadata? = null,
    val retryAfterSeconds: Long? = null,
)

data class DownloadSegmentReceipt(
    val statusCode: Int,
    val start: Long,
    val endInclusive: Long,
    val totalSize: Long,
    val bytesWritten: Long,
    val metadata: DownloadFileMetadata,
)

interface DownloadResumeTransport {
    suspend fun headDownload(pathAndQuery: String, operationId: String): DownloadHeadResult
    suspend fun downloadRange(
        pathAndQuery: String,
        metadata: DownloadFileMetadata,
        start: Long,
        endInclusive: Long,
        output: OutputStream,
        operationId: String,
    ): DownloadSegmentReceipt

    fun cancelDownload(operationId: String)
}

enum class DownloadTaskStatus {
    PENDING,
    DOWNLOADING,
    PAUSED,
    WAITING_FOR_SERVER,
    WAITING_FOR_AUTH,
    SOURCE_CHANGED,
    READY_TO_EXPORT,
    EXPORTING,
    EXPORTED,
    EXPORT_FAILED,
    FAILED,
    CORRUPT,
}

data class DownloadSegmentCheckpoint(
    val start: Long,
    val length: Long,
    val sha256: String,
)

data class DownloadTask(
    val taskId: String,
    val identity: DownloadAuthorizationIdentity,
    val fileId: String,
    val filename: String,
    val pathAndQuery: String,
    val listedSizeHint: Long?,
    val metadata: DownloadFileMetadata? = null,
    val segments: List<DownloadSegmentCheckpoint> = emptyList(),
    val status: DownloadTaskStatus = DownloadTaskStatus.PENDING,
    val lastError: String? = null,
    val updatedAtEpochMillis: Long = System.currentTimeMillis(),
) {
    val committedBytes: Long get() = segments.sumOf { it.length }
    val totalSize: Long? get() = metadata?.totalSize
    val progress: Float
        get() = metadata?.totalSize?.let { if (it == 0L) 1f else (committedBytes.toDouble() / it).toFloat().coerceIn(0f, 1f) }
            ?: 0f
}

class DownloadStagingCorruptException(message: String) : IllegalStateException(message)

/** Stores only verified file metadata and segment hashes; credentials and nonces never enter this directory. */
class DownloadResumeStore(private val root: File) {
    @Synchronized
    fun createOrLoad(
        identity: DownloadAuthorizationIdentity,
        fileId: String,
        filename: String,
        pathAndQuery: String,
        listedSizeHint: Long?,
    ): DownloadTask {
        require(identity.serverId.isNotBlank() && identity.userId.isNotBlank() && identity.installationId.isNotBlank())
        require(fileId.matches(FILE_ID) && pathAndQuery == "/api/download/$fileId")
        require(filename.isNotBlank() && filename.length <= 1024 && (listedSizeHint == null || listedSizeHint >= 0))
        val id = taskId(identity, fileId)
        load(id)?.let { existing ->
            require(existing.identity == identity && existing.fileId == fileId) { "Download task identity mismatch" }
            return existing
        }
        ensureRoot()
        val task = DownloadTask(id, identity, fileId, filename, pathAndQuery, listedSizeHint)
        writeManifest(task, allowCreate = true)
        stage(id).let { if (!it.exists()) it.createNewFile() }
        return task
    }

    @Synchronized
    fun load(taskId: String): DownloadTask? {
        validateTaskId(taskId)
        recoverBackup(taskId)
        val file = manifest(taskId)
        if (!file.isFile) return null
        val parsed = runCatching { decode(file.readText(Charsets.UTF_8)) }.getOrElse {
            throw DownloadStagingCorruptException("The saved download task manifest is damaged")
        }
        if (parsed.taskId != taskId || parsed.taskId != taskId(parsed.identity, parsed.fileId)) {
            throw DownloadStagingCorruptException("The saved download task belongs to another identity")
        }
        return parsed
    }

    @Synchronized
    fun list(): List<DownloadTask> {
        ensureRoot()
        return root.listFiles { file -> file.name.matches(MANIFEST_NAME) }?.mapNotNull { file ->
            val id = file.name.removeSuffix(".json")
            runCatching { load(id) }.getOrNull()
        }.orEmpty()
            .sortedByDescending { it.updatedAtEpochMillis }
    }

    @Synchronized
    fun recoverInterrupted() {
        root.listFiles { file -> file.name.matches(MANIFEST_NAME) }?.forEach { file ->
            val task = runCatching { load(file.name.removeSuffix(".json")) }.getOrNull() ?: return@forEach
            val recovered = when (task.status) {
                DownloadTaskStatus.DOWNLOADING -> task.copy(status = DownloadTaskStatus.PAUSED, lastError = null)
                DownloadTaskStatus.EXPORTING -> task.copy(status = DownloadTaskStatus.READY_TO_EXPORT, lastError = null)
                else -> task
            }
            if (recovered != task) writeManifest(recovered)
        }
    }

    @Synchronized
    fun saveState(taskId: String, status: DownloadTaskStatus, error: String? = null): DownloadTask {
        val current = load(taskId) ?: throw IllegalStateException("Download task was cancelled")
        return current.copy(status = status, lastError = error, updatedAtEpochMillis = System.currentTimeMillis()).also(::writeManifest)
    }

    @Synchronized
    fun setMetadata(taskId: String, metadata: DownloadFileMetadata): DownloadTask {
        require(metadata.isValid()) { "Server returned invalid download metadata" }
        val current = load(taskId) ?: throw IllegalStateException("Download task was cancelled")
        if (metadata.serverId != current.identity.serverId || metadata.fileId != current.fileId) {
            throw NativeApiException(null, "SOURCE_CHANGED", "The file identity changed on the server")
        }
        val existing = current.metadata
        if (existing != null && existing != metadata) {
            throw NativeApiException(null, "SOURCE_CHANGED", "The file or account authorization changed; restart this download explicitly")
        }
        return current.copy(metadata = metadata, status = DownloadTaskStatus.DOWNLOADING, lastError = null,
            updatedAtEpochMillis = System.currentTimeMillis()).also(::writeManifest)
    }

    /** Verifies every committed segment and removes bytes written after the last atomic checkpoint. */
    fun repairStage(taskId: String, checkActive: () -> Unit = {}): Long {
        val task = load(taskId) ?: throw IllegalStateException("Download task was cancelled")
        ensureRoot()
        val file = stage(taskId)
        if (!file.exists()) file.createNewFile()
        var cursor = 0L
        RandomAccessFile(file, "rw").use { raf ->
            task.segments.forEach { segment ->
                checkActive()
                if (segment.start != cursor || segment.length <= 0 || !SHA256.matches(segment.sha256) ||
                    segment.length > (task.metadata?.totalSize ?: Long.MAX_VALUE) - cursor || raf.length() < cursor + segment.length) {
                    throw DownloadStagingCorruptException("The saved download has an invalid segment checkpoint")
                }
                val digest = digestRange(raf, cursor, segment.length, checkActive)
                if (digest != segment.sha256) throw DownloadStagingCorruptException("A saved download segment failed its integrity check")
                cursor += segment.length
            }
            if (raf.length() < cursor) throw DownloadStagingCorruptException("The saved download is shorter than its verified checkpoint")
            if (raf.length() != cursor) {
                raf.setLength(cursor)
                raf.fd.sync()
            }
        }
        return cursor
    }

    fun truncateToCommitted(taskId: String): Long {
        val task = load(taskId) ?: throw IllegalStateException("Download task was cancelled")
        ensureRoot()
        val file = stage(taskId)
        if (!file.exists()) file.createNewFile()
        val committed = task.committedBytes
        RandomAccessFile(file, "rw").use { raf ->
            if (raf.length() < committed) throw DownloadStagingCorruptException("The saved download is shorter than its verified checkpoint")
            if (raf.length() > committed) {
                raf.setLength(committed)
                raf.fd.sync()
            }
        }
        return committed
    }

    fun buildSegmentCheckpoint(
        taskId: String,
        start: Long,
        endInclusive: Long,
        checkActive: () -> Unit = {},
    ): DownloadSegmentCheckpoint {
        val current = load(taskId) ?: throw IllegalStateException("Download task was cancelled")
        val metadata = current.metadata ?: throw IllegalStateException("Download metadata is missing")
        require(start == current.committedBytes && endInclusive >= start && endInclusive < metadata.totalSize)
        val length = endInclusive - start + 1
        val hash = RandomAccessFile(stage(taskId), "r").use { raf ->
            if (raf.length() < endInclusive + 1) throw DownloadStagingCorruptException("The received segment is incomplete")
            digestRange(raf, start, length, checkActive)
        }
        return DownloadSegmentCheckpoint(start, length, hash)
    }

    /** Only the small atomic manifest update is performed in the manager's cancellation lock. */
    fun commitSegment(taskId: String, checkpoint: DownloadSegmentCheckpoint): DownloadTask {
        val current = load(taskId) ?: throw IllegalStateException("Download task was cancelled")
        val metadata = current.metadata ?: throw IllegalStateException("Download metadata is missing")
        require(checkpoint.start == current.committedBytes && checkpoint.length > 0 &&
            checkpoint.length <= metadata.totalSize - checkpoint.start && SHA256.matches(checkpoint.sha256))
        val next = current.copy(
            segments = current.segments + checkpoint,
            status = DownloadTaskStatus.DOWNLOADING,
            lastError = null,
            updatedAtEpochMillis = System.currentTimeMillis(),
        )
        writeManifest(next)
        return next
    }

    fun verifyComplete(taskId: String, checkActive: () -> Unit = {}): DownloadTask {
        val current = load(taskId) ?: throw IllegalStateException("Download task was cancelled")
        val metadata = current.metadata ?: throw IllegalStateException("Download metadata is missing")
        if (current.committedBytes != metadata.totalSize || current.segments.fold(0L) { offset, segment ->
                if (segment.start != offset) throw DownloadStagingCorruptException("Download segments are not contiguous")
                offset + segment.length
            } != metadata.totalSize) {
            throw IllegalStateException("The download is not complete")
        }
        val file = stage(taskId)
        if (!file.isFile || file.length() != metadata.totalSize) throw DownloadStagingCorruptException("The staged download size is invalid")
        val digest = digestFile(file, checkActive)
        if (digest != metadata.sha256) throw DownloadStagingCorruptException("The downloaded file failed its SHA-256 check")
        return current.copy(status = DownloadTaskStatus.READY_TO_EXPORT, lastError = null,
            updatedAtEpochMillis = System.currentTimeMillis()).also(::writeManifest)
    }

    @Synchronized
    fun resetForRestart(taskId: String): DownloadTask {
        val current = load(taskId) ?: throw IllegalStateException("Download task was cancelled")
        RandomAccessFile(stage(taskId), "rw").use { raf -> raf.setLength(0); raf.fd.sync() }
        return current.copy(metadata = null, segments = emptyList(), status = DownloadTaskStatus.PENDING,
            lastError = null, updatedAtEpochMillis = System.currentTimeMillis()).also(::writeManifest)
    }

    @Synchronized
    fun remove(taskId: String) {
        validateTaskId(taskId)
        manifest(taskId).delete()
        File(root, "$taskId.json.tmp").delete()
        File(root, "$taskId.json.bak").delete()
        stage(taskId).delete()
    }

    @Synchronized
    fun clearIdentity(identity: DownloadAuthorizationIdentity) {
        list().filter { it.identity == identity }.forEach { remove(it.taskId) }
    }

    fun stageFile(taskId: String): File {
        validateTaskId(taskId)
        ensureRoot()
        return stage(taskId)
    }

    @Synchronized
    private fun writeManifest(task: DownloadTask, allowCreate: Boolean = false) {
        ensureRoot()
        if (!allowCreate && !manifest(task.taskId).exists()) throw IllegalStateException("Download task was cancelled")
        val target = manifest(task.taskId)
        val temp = File(root, "${task.taskId}.json.tmp")
        val backup = File(root, "${task.taskId}.json.bak")
        FileOutputStream(temp).use { output ->
            output.write(encode(task).toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        if (target.exists() && !target.renameTo(backup)) {
            temp.delete()
            throw IllegalStateException("Could not checkpoint the download task")
        }
        if (!temp.renameTo(target)) {
            if (backup.exists()) backup.renameTo(target)
            temp.delete()
            throw IllegalStateException("Could not atomically checkpoint the download task")
        }
        backup.delete()
    }

    private fun encode(task: DownloadTask): String = JSONObject()
        .put("version", VERSION)
        .put("task_id", task.taskId)
        .put("server_id", task.identity.serverId)
        .put("user_id", task.identity.userId)
        .put("installation_id", task.identity.installationId)
        .put("file_id", task.fileId)
        .put("filename", task.filename)
        .put("path", task.pathAndQuery)
        .put("listed_size_hint", task.listedSizeHint ?: JSONObject.NULL)
        .put("metadata", task.metadata?.let(::encodeMetadata) ?: JSONObject.NULL)
        .put("segments", JSONArray().also { array -> task.segments.forEach { array.put(JSONObject()
            .put("start", it.start).put("length", it.length).put("sha256", it.sha256)) } })
        .put("status", task.status.name)
        .put("last_error", task.lastError ?: JSONObject.NULL)
        .put("updated_at", task.updatedAtEpochMillis)
        .toString()

    private fun decode(json: String): DownloadTask {
        val source = JSONObject(json)
        require(source.optInt("version") == VERSION)
        val identity = DownloadAuthorizationIdentity(source.getString("server_id"), source.getString("user_id"), source.getString("installation_id"))
        val metadata = if (source.isNull("metadata")) null else decodeMetadata(source.getJSONObject("metadata"))
        val segments = source.getJSONArray("segments").let { array -> List(array.length()) { index ->
            val item = array.getJSONObject(index)
            DownloadSegmentCheckpoint(item.getLong("start"), item.getLong("length"), item.getString("sha256"))
        } }
        val hint = if (source.isNull("listed_size_hint")) null else source.getLong("listed_size_hint")
        return DownloadTask(
            taskId = source.getString("task_id"), identity = identity,
            fileId = source.getString("file_id"), filename = source.getString("filename"), pathAndQuery = source.getString("path"),
            listedSizeHint = hint, metadata = metadata, segments = segments,
            status = DownloadTaskStatus.valueOf(source.getString("status")),
            lastError = if (source.isNull("last_error")) null else source.getString("last_error"),
            updatedAtEpochMillis = source.getLong("updated_at"),
        ).also { task ->
            require(task.identity.serverId.isNotBlank() && task.identity.userId.isNotBlank() && task.identity.installationId.isNotBlank())
            require(task.fileId.matches(FILE_ID) && task.pathAndQuery == "/api/download/${task.fileId}")
            require(task.filename.isNotBlank() && task.filename.length <= 1024 && (hint == null || hint >= 0))
            require(task.metadata == null || task.metadata.isValid())
            require(task.segments.size <= MAX_SEGMENTS)
        }
    }

    private fun encodeMetadata(metadata: DownloadFileMetadata) = JSONObject()
        .put("total_size", metadata.totalSize).put("etag", metadata.etag).put("sha256", metadata.sha256)
        .put("authorization_generation", metadata.authorizationGeneration).put("server_id", metadata.serverId).put("file_id", metadata.fileId)

    private fun decodeMetadata(json: JSONObject) = DownloadFileMetadata(
        json.getLong("total_size"), json.getString("etag"), json.getString("sha256"),
        json.getString("authorization_generation"), json.getString("server_id"), json.getString("file_id"),
    )

    private fun digestRange(raf: RandomAccessFile, start: Long, length: Long, checkActive: () -> Unit = {}): String {
        val digest = MessageDigest.getInstance("SHA-256")
        raf.seek(start)
        val buffer = ByteArray(64 * 1024)
        var remaining = length
        while (remaining > 0) {
            checkActive()
            val count = raf.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (count < 0) throw DownloadStagingCorruptException("The saved download is truncated")
            digest.update(buffer, 0, count)
            remaining -= count
        }
        return digest.digest().toHex()
    }

    private fun digestFile(file: File, checkActive: () -> Unit = {}): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                checkActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    private fun recoverBackup(taskId: String) {
        val target = manifest(taskId)
        val backup = File(root, "$taskId.json.bak")
        if (!target.exists() && backup.exists()) backup.renameTo(target)
        if (target.exists()) backup.delete()
    }

    private fun ensureRoot() { if (!root.exists()) check(root.mkdirs() || root.isDirectory) { "Could not create the private download directory" } }
    private fun manifest(id: String) = File(root, "$id.json")
    private fun stage(id: String) = File(root, "$id.part")
    private fun validateTaskId(id: String) { require(id.matches(TASK_ID)) }

    companion object {
        private const val VERSION = 1
        private const val MAX_SEGMENTS = 1_000_000
        private val FILE_ID = Regex("[A-Za-z0-9_-]{8,160}")
        private val TASK_ID = Regex("[0-9a-f]{64}")
        private val MANIFEST_NAME = Regex("[0-9a-f]{64}\\.json")

        fun taskId(identity: DownloadAuthorizationIdentity, fileId: String): String = MessageDigest.getInstance("SHA-256")
            .digest("${identity.serverId}\u0000${identity.userId}\u0000${identity.installationId}\u0000$fileId".toByteArray(Charsets.UTF_8)).toHex()
    }
}

/** One writer per task; completed bytes are visible only after the server hash matches. */
class DownloadResumeManager(
    private val store: DownloadResumeStore,
    private val transport: DownloadResumeTransport,
    private val wait: suspend (Long) -> Unit = { delay(it) },
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val maintenanceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val initialized = CompletableDeferred<Unit>()
    private val taskMutexes = ConcurrentHashMap<String, Mutex>()
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val runIds = ConcurrentHashMap<String, String>()
    private val cancelledIds = ConcurrentHashMap.newKeySet<String>()
    private val pendingRemovalIds = ConcurrentHashMap.newKeySet<String>()
    private val taskOverlays = ConcurrentHashMap<String, DownloadTask>()
    private val maintenanceJobs = ConcurrentHashMap<String, Job>()
    private val mutableTasks = MutableStateFlow(emptyList<DownloadTask>())
    val tasks: StateFlow<List<DownloadTask>> = mutableTasks.asStateFlow()

    init {
        maintenanceScope.launch {
            val restored = runCatching {
                store.recoverInterrupted()
                store.list()
            }.getOrDefault(emptyList())
            mutableTasks.value = restored
            initialized.complete(Unit)
        }
    }

    suspend fun refreshTasks() {
        initialized.await()
        publish()
    }

    fun visibleTasks(identity: DownloadAuthorizationIdentity?): List<DownloadTask> =
        if (identity == null) emptyList() else mutableTasks.value.filter { it.identity == identity }

    suspend fun createOrLoad(
        identity: DownloadAuthorizationIdentity,
        fileId: String,
        filename: String,
        pathAndQuery: String,
        listedSizeHint: Long?,
    ): DownloadTask {
        initialized.await()
        awaitMaintenance(DownloadResumeStore.taskId(identity, fileId))
        val task = withContext(Dispatchers.IO) {
            store.createOrLoad(identity, fileId, filename, pathAndQuery, listedSizeHint)
        }
        cancelledIds.remove(task.taskId)
        taskOverlays.remove(task.taskId)
        publish()
        return task
    }

    fun resume(taskId: String, identity: DownloadAuthorizationIdentity, scope: CoroutineScope) {
        scope.launch { resumeNow(taskId, identity) }
    }

    suspend fun resumeNow(taskId: String, identity: DownloadAuthorizationIdentity) {
        initialized.await()
        awaitMaintenance(taskId)
        if (taskId in pendingRemovalIds) return
        taskOverlays.remove(taskId)
        val job = currentCoroutineContext()[Job]
        val runId = UUID.randomUUID().toString()
        synchronized(this) {
            val active = activeJobs[taskId]
            if (active != null && active.isActive && active !== job) return
            activeJobs[taskId] = job ?: return
            runIds[taskId] = runId
            cancelledIds.remove(taskId)
        }
        val mutex = taskMutexes.getOrPut(taskId) { Mutex() }
        try {
            mutex.withLock { withContext(Dispatchers.IO) { runDownload(taskId, identity, runId) } }
        } catch (_: CancellationException) {
            if (isCurrentRun(taskId, runId)) {
                withContext(NonCancellable + Dispatchers.IO) { updateState(taskId, runId, DownloadTaskStatus.PAUSED, null) }
            }
        } catch (failure: DownloadStagingCorruptException) {
            updateState(taskId, runId, DownloadTaskStatus.CORRUPT, failure.message)
        } catch (failure: NativeApiException) {
            when (failure.apiCode) {
                "SOURCE_CHANGED" -> updateState(taskId, runId, DownloadTaskStatus.SOURCE_CHANGED, failure.message)
                "AUTH_REQUIRED", "AUTH_NOT_AVAILABLE", "ACCESS_TOKEN_EXPIRED", "SESSION_INVALID", "SESSION_EXPIRED", "SESSION_REVOKED", "DEVICE_REVOKED", "INSTALLATION_REVOKED", "ACCOUNT_DISABLED" ->
                    updateState(taskId, runId, DownloadTaskStatus.WAITING_FOR_AUTH, failure.message)
                "DOWNLOAD_PREPARATION_PENDING" -> updateState(taskId, runId, DownloadTaskStatus.WAITING_FOR_SERVER, failure.message)
                else -> updateState(taskId, runId, DownloadTaskStatus.PAUSED, failure.message)
            }
        } catch (failure: Exception) {
            updateState(taskId, runId, DownloadTaskStatus.PAUSED, failure.message ?: "The connection stopped. Resume to continue")
        } finally {
            synchronized(this) {
                if (activeJobs[taskId] === job) activeJobs.remove(taskId)
                if (runIds[taskId] == runId) runIds.remove(taskId)
            }
        }
    }

    fun pause(taskId: String) {
        val task = mutableTasks.value.firstOrNull { it.taskId == taskId }
        val job = synchronized(this) {
            val run = runIds.remove(taskId)
            if (run != null) cancelledIds += taskId
            activeJobs.remove(taskId)
        }
        transport.cancelDownload(taskId)
        job?.cancel()
        task?.let {
            taskOverlays[taskId] = it.copy(status = DownloadTaskStatus.PAUSED, lastError = null, updatedAtEpochMillis = nowMillis())
            mutableTasks.value = mutableTasks.value.map { current -> if (current.taskId == taskId) taskOverlays.getValue(taskId) else current }
        }
        enqueueMaintenance(taskId) {
            if (store.load(taskId) != null) store.saveState(taskId, DownloadTaskStatus.PAUSED)
            taskOverlays.remove(taskId)
        }
    }

    fun cancel(taskId: String) {
        val job = synchronized(this) {
            runIds.remove(taskId)
            cancelledIds += taskId
            activeJobs.remove(taskId)
        }
        transport.cancelDownload(taskId)
        job?.cancel()
        pendingRemovalIds += taskId
        taskOverlays.remove(taskId)
        mutableTasks.value = mutableTasks.value.filterNot { it.taskId == taskId }
        enqueueMaintenance(taskId) {
            store.remove(taskId)
            pendingRemovalIds.remove(taskId)
            cancelledIds.remove(taskId)
        }
    }

    suspend fun restart(taskId: String, identity: DownloadAuthorizationIdentity) {
        initialized.await()
        awaitMaintenance(taskId)
        if (taskId in pendingRemovalIds) return
        val task = withContext(Dispatchers.IO) { store.load(taskId) } ?: return
        require(task.identity == identity) { "Download belongs to another account or device" }
        val job = synchronized(this) {
            runIds.remove(taskId)
            cancelledIds += taskId
            activeJobs.remove(taskId)
        }
        transport.cancelDownload(taskId)
        job?.cancelAndJoin()
        val mutex = taskMutexes.getOrPut(taskId) { Mutex() }
        mutex.withLock { withContext(Dispatchers.IO) { store.resetForRestart(taskId) } }
        cancelledIds.remove(taskId)
        taskOverlays.remove(taskId)
        publish()
    }

    /** Logout removes only this account/installation's private staging, never completed SAF documents. */
    fun clearIdentity(identity: DownloadAuthorizationIdentity) {
        clearTasksWhere { it.identity == identity }
    }

    fun clearInstallation(serverId: String, installationId: String) {
        clearTasksWhere { it.identity.serverId == serverId && it.identity.installationId == installationId }
    }

    private fun clearTasksWhere(predicate: (DownloadTask) -> Boolean) {
        maintenanceScope.launch {
            initialized.await()
            val tasks = withContext(Dispatchers.IO) { store.list() }
            tasks.filter(predicate).forEach { cancel(it.taskId) }
        }
    }

    suspend fun exportVerified(taskId: String, identity: DownloadAuthorizationIdentity, output: OutputStream) {
        var outputCloseAttempted = false
        var primaryFailure: Throwable? = null
        var job: Job? = null
        var runId: String? = null
        try {
            initialized.await()
            awaitMaintenance(taskId)
            val task = withContext(Dispatchers.IO) { store.load(taskId) }
                ?: throw IllegalStateException("Download task no longer exists")
            require(task.identity == identity && task.status in setOf(DownloadTaskStatus.READY_TO_EXPORT, DownloadTaskStatus.EXPORT_FAILED, DownloadTaskStatus.EXPORTED)) {
                "This download is not ready to export for the current account and device"
            }
            job = currentCoroutineContext()[Job]
            val activeRunId = UUID.randomUUID().toString()
            synchronized(this) {
                val active = activeJobs[taskId]
                if (active != null && active.isActive && active !== job) throw IllegalStateException("This download is already active")
                activeJobs[taskId] = job ?: throw IllegalStateException("Download export needs a cancellable task")
                runIds[taskId] = activeRunId
            }
            runId = activeRunId
            val mutex = taskMutexes.getOrPut(taskId) { Mutex() }
            mutex.withLock {
                val current = withContext(Dispatchers.IO) { store.load(taskId) }
                    ?: throw IllegalStateException("Download task was cancelled")
                val currentMetadata = current.metadata ?: throw IllegalStateException("Download metadata is missing")
                ensureCurrent(taskId, activeRunId)
                val latest = fetchReadyHead(task.pathAndQuery, taskId)
                ensureCurrent(taskId, activeRunId)
                if (latest != currentMetadata) {
                    throw NativeApiException(null, "SOURCE_CHANGED", "The file or account authorization changed; restart this download explicitly")
                }
                withContext(Dispatchers.IO) {
                    synchronized(this@DownloadResumeManager) {
                        ensureCurrent(taskId, activeRunId)
                        store.saveState(taskId, DownloadTaskStatus.EXPORTING)
                    }
                }
                publish()
                val source = withContext(Dispatchers.IO) { store.stageFile(taskId) }
                val sha = withContext(Dispatchers.IO) { sha256File(source) { ensureCurrent(taskId, activeRunId) } }
                if (sha != currentMetadata.sha256 || source.length() != currentMetadata.totalSize) {
                    throw DownloadStagingCorruptException("The verified download changed before export")
                }
                withContext(Dispatchers.IO) {
                    val digest = MessageDigest.getInstance("SHA-256")
                    var copied = 0L
                    source.inputStream().buffered().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            ensureCurrent(taskId, activeRunId)
                            val count = input.read(buffer)
                            if (count < 0) break
                            digest.update(buffer, 0, count)
                            copied += count
                            output.write(buffer, 0, count)
                        }
                    }
                    output.flush()
                    if (copied != currentMetadata.totalSize || digest.digest().toHex() != currentMetadata.sha256) {
                        throw DownloadStagingCorruptException("The verified download changed during export")
                    }
                }
                // A successful state requires a successful provider close. Mark
                // the attempt before calling close so a throwing provider is not
                // invoked a second time during cleanup.
                outputCloseAttempted = true
                withContext(NonCancellable + Dispatchers.IO) { output.close() }
                withContext(Dispatchers.IO) {
                    synchronized(this@DownloadResumeManager) {
                        ensureCurrent(taskId, activeRunId)
                        store.saveState(taskId, DownloadTaskStatus.EXPORTED)
                    }
                }
                publish()
            }
        } catch (failure: Throwable) {
            primaryFailure = failure
            val currentRunId = runId
            if (failure is Exception && currentRunId != null && isCurrentRun(taskId, currentRunId)) {
                withContext(NonCancellable + Dispatchers.IO) {
                    synchronized(this@DownloadResumeManager) {
                        if (isCurrentRun(taskId, currentRunId) && store.load(taskId) != null) {
                            val state = if (failure is NativeApiException && failure.apiCode == "SOURCE_CHANGED")
                                DownloadTaskStatus.SOURCE_CHANGED else DownloadTaskStatus.EXPORT_FAILED
                            runCatching { store.saveState(taskId, state, failure.message) }
                        }
                    }
                }
            }
            throw failure
        } finally {
            if (!outputCloseAttempted) {
                outputCloseAttempted = true
                val closeFailure = withContext(NonCancellable + Dispatchers.IO) { runCatching { output.close() }.exceptionOrNull() }
                if (closeFailure != null) primaryFailure?.addSuppressed(closeFailure)
            }
            val currentRunId = runId
            synchronized(this) {
                if (activeJobs[taskId] === job) activeJobs.remove(taskId)
                if (currentRunId != null && runIds[taskId] == currentRunId) runIds.remove(taskId)
            }
            withContext(NonCancellable) { publish() }
        }
    }

    private suspend fun runDownload(taskId: String, identity: DownloadAuthorizationIdentity, runId: String) {
        var task = store.load(taskId) ?: return
        require(task.identity == identity) { "Download belongs to another account or device" }
        ensureCurrent(taskId, runId)
        updateState(taskId, runId, DownloadTaskStatus.WAITING_FOR_SERVER, null)
        val metadata = fetchReadyHead(task.pathAndQuery, taskId)
        ensureCurrent(taskId, runId)
        task = store.setMetadata(taskId, metadata)
        val offset = store.repairStage(taskId) { ensureCurrent(taskId, runId) }
        ensureCurrent(taskId, runId)
        if (metadata.totalSize == 0L) {
            store.verifyComplete(taskId) { ensureCurrent(taskId, runId) }
            publish()
            return
        }
        updateState(taskId, runId, DownloadTaskStatus.DOWNLOADING, null)
        var committed = offset
        while (committed < metadata.totalSize) {
            ensureCurrent(taskId, runId)
            store.truncateToCommitted(taskId)
            val start = committed
            val end = minOf(start + SEGMENT_BYTES - 1, metadata.totalSize - 1)
            var attempt = 0
            val prepStarted = nowMillis()
            while (true) {
                try {
                    RandomAccessFile(store.stageFile(taskId), "rw").use { raf ->
                        raf.seek(start)
                        val output = object : OutputStream() {
                            override fun write(value: Int) = raf.write(value)
                            override fun write(bytes: ByteArray, offset: Int, length: Int) = raf.write(bytes, offset, length)
                            override fun flush() = raf.fd.sync()
                            override fun close() = Unit
                        }
                        val receipt = transport.downloadRange(task.pathAndQuery, metadata, start, end, output, taskId)
                        ensureCurrent(taskId, runId)
                        if (receipt.statusCode != 206 || receipt.start != start || receipt.endInclusive != end ||
                            receipt.totalSize != metadata.totalSize || receipt.bytesWritten != end - start + 1 || receipt.metadata != metadata) {
                            throw NativeApiException(receipt.statusCode, "SOURCE_CHANGED", "The server returned a different file version")
                        }
                        raf.fd.sync()
                    }
                    ensureCurrent(taskId, runId)
                    val checkpoint = store.buildSegmentCheckpoint(taskId, start, end) { ensureCurrent(taskId, runId) }
                    synchronized(this) {
                        ensureCurrent(taskId, runId)
                        task = store.commitSegment(taskId, checkpoint)
                    }
                    committed = task.committedBytes
                    publish()
                    break
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: NativeApiException) {
                    if (failure.apiCode == "SOURCE_CHANGED") throw failure
                    if (failure.httpStatus == 202 || failure.httpStatus == 503 || failure.apiCode == "DOWNLOAD_PREPARING" ||
                        failure.apiCode == "DOWNLOAD_PREPARATION_BUSY") {
                        if (nowMillis() - prepStarted >= MAX_PREPARATION_WAIT_MILLIS) {
                            updateState(taskId, runId, DownloadTaskStatus.WAITING_FOR_SERVER, "The server is still preparing this file. Resume later")
                            return
                        }
                        wait((failure.retryAfterSeconds ?: 1L).coerceIn(1L, 10L) * 1000L)
                        ensureCurrent(taskId, runId)
                        continue
                    }
                    if (!retryable(failure) || attempt >= MAX_NETWORK_RETRIES - 1) throw failure
                    wait((1L shl attempt) * 1000L)
                    attempt++
                    store.truncateToCommitted(taskId)
                } catch (failure: java.io.IOException) {
                    if (attempt >= MAX_NETWORK_RETRIES - 1) throw failure
                    wait((1L shl attempt) * 1000L)
                    attempt++
                    store.truncateToCommitted(taskId)
                }
            }
        }
        ensureCurrent(taskId, runId)
        store.verifyComplete(taskId) { ensureCurrent(taskId, runId) }
        publish()
    }

    private suspend fun fetchReadyHead(path: String, taskId: String): DownloadFileMetadata {
        val startedAt = nowMillis()
        var networkAttempt = 0
        while (true) {
            val result = try {
                transport.headDownload(path, taskId).also { networkAttempt = 0 }
            } catch (failure: java.io.IOException) {
                if (networkAttempt >= MAX_NETWORK_RETRIES - 1) throw failure
                wait((1L shl networkAttempt) * 1000L)
                networkAttempt++
                continue
            }
            when (result.statusCode) {
                200 -> return result.metadata?.takeIf { it.isValid() }
                    ?: throw NativeApiException(200, "BAD_DOWNLOAD_METADATA", "The server returned invalid download metadata")
                202, 503 -> {
                    if (nowMillis() - startedAt >= MAX_PREPARATION_WAIT_MILLIS) {
                        throw NativeApiException(result.statusCode, "DOWNLOAD_PREPARATION_PENDING", "The server is still preparing this file. Resume later",
                            retryAfterSeconds = result.retryAfterSeconds)
                    }
                    wait((result.retryAfterSeconds ?: 1L).coerceIn(1L, 10L) * 1000L)
                }
                else -> throw NativeApiException(result.statusCode, "BAD_DOWNLOAD_STATUS", "The server could not provide download metadata")
            }
        }
    }

    private fun retryable(failure: NativeApiException): Boolean =
        failure.apiCode == "DOWNLOAD_SIZE_MISMATCH" || failure.apiCode == "NETWORK_ERROR" || failure.httpStatus in 500..599

    private fun ensureCurrent(taskId: String, runId: String) {
        if (!isCurrentRun(taskId, runId)) throw CancellationException("Download was paused or cancelled")
    }

    private fun isCurrentRun(taskId: String, runId: String): Boolean = runIds[taskId] == runId && taskId !in cancelledIds

    private suspend fun updateState(taskId: String, runId: String, status: DownloadTaskStatus, error: String?) {
        val shouldPublish = withContext(Dispatchers.IO) {
            synchronized(this@DownloadResumeManager) {
                if (!isCurrentRun(taskId, runId)) return@withContext false
                runCatching { store.saveState(taskId, status, error) }
                taskOverlays.remove(taskId)
                true
            }
        }
        if (shouldPublish) publish()
    }

    private suspend fun publish() {
        val saved = withContext(Dispatchers.IO) { store.list() }
        mutableTasks.value = saved
            .filterNot { it.taskId in pendingRemovalIds }
            .map { taskOverlays[it.taskId] ?: it }
    }

    private suspend fun awaitMaintenance(taskId: String) {
        while (true) {
            val pending = maintenanceJobs[taskId] ?: return
            pending.join()
            if (maintenanceJobs[taskId] === pending) return
        }
    }

    private fun enqueueMaintenance(taskId: String, action: suspend () -> Unit) {
        val previous = maintenanceJobs[taskId]
        val job = maintenanceScope.launch(start = CoroutineStart.LAZY) {
            runCatching {
                previous?.join()
                val mutex = taskMutexes.getOrPut(taskId) { Mutex() }
                mutex.withLock { action() }
            }
            val currentJob = currentCoroutineContext()[Job]
            if (currentJob != null) maintenanceJobs.remove(taskId, currentJob)
            runCatching { publish() }
        }
        maintenanceJobs[taskId] = job
        job.start()
    }

    private companion object {
        const val SEGMENT_BYTES = 4L * 1024 * 1024
        const val MAX_PREPARATION_WAIT_MILLIS = 10L * 60 * 1000
        const val MAX_NETWORK_RETRIES = 3
    }
}

private val SHA256 = Regex("[0-9a-f]{64}")

private fun sha256File(file: File, checkActive: () -> Unit = {}): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            checkActive()
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().toHex()
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
