package com.hawky.nascraft

import android.content.ContentUris
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 上传媒体类型
 */
enum class UploadMediaType { ALL, IMAGE, VIDEO }

/**
 * 相册照片信息
 */
data class PhotoInfo(
    val id: Long,
    val uri: String,
    val name: String,
    val mimeType: String,
    val size: Long,
    val dateAdded: Long,
    val dateModified: Long,
    val width: Int,
    val height: Int,
    val orientation: Int
)

/**
 * 上传状态
 */
sealed class UploadStatus {
    object Pending : UploadStatus()
    object Uploading : UploadStatus()
    object Completed : UploadStatus()
    data class Failed(val error: String) : UploadStatus()
}

private data class ChunkRange(
    val startOffset: Long,
    val endOffset: Long,
)

/**
 * 上传进度回调
 */
typealias UploadProgressCallback = (photoInfo: PhotoInfo, progress: Float, status: UploadStatus, totalFiles: Int?, currentFileIndex: Int?) -> Unit

/**
 * Android 相册上传管理器
 * 负责权限检查、相册查询和照片上传
 */
class AlbumUploadManager(private val context: Context) {
    companion object {
        private const val TAG = "AlbumUploadManager"
        private const val UPLOAD_RETRY_DELAY_MS = 2000L
        private const val PARALLEL_UPLOADS = 5 // 同时并行上传的文件数
    }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /** MD5 本地缓存：key = photo.uri；文件未变则复用，避免重复全量哈希。
     *  value = "md5|size"，同时缓存字节数，避免缓存命中时还得再扫一遍取大小。
     *  注意：key 必须用 uri 而非 id+dateModified——图片/视频的 MediaStore _ID 是各自独立的
     *  命名空间（都从 1 起），若用 id 做 key，图片 id=N 与视频 id=N 会碰撞，导致误读对方 MD5。 */
    private val md5Cache by lazy {
        context.getSharedPreferences("nascraft_md5_cache", Context.MODE_PRIVATE)
    }

    private fun getCachedDigest(uri: String): Pair<String, Long>? {
        val raw = md5Cache.getString("md5_$uri", null) ?: return null
        val parts = raw.split("|")
        if (parts.size != 2) return null
        val size = parts[1].toLongOrNull() ?: return null
        return Pair(parts[0], size)
    }

    private fun putCachedDigest(uri: String, md5: String, size: Long) {
        md5Cache.edit().putString("md5_$uri", "$md5|$size").apply()
    }

    private var isUploading = false
    private var shouldStop = false
    @Volatile
    private var isPaused = false
    private var uploadJob: Job? = null
    private val coroutineScope = CoroutineScope(Dispatchers.IO)

    /**
     * 检查并请求相册访问权限
     * @return Boolean 是否已授予所有必要权限
     */
    fun hasRequiredPermissions(): Boolean {
        val permissions = getRequiredPermissions()
        return permissions.all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * 获取所需的权限列表（根据Android版本）
     */
    fun getRequiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ (API 33+)：图片与视频分别需要独立权限
            arrayOf(
                android.Manifest.permission.READ_MEDIA_IMAGES,
                android.Manifest.permission.READ_MEDIA_VIDEO
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11-12 (API 30-32)
            arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        } else {
            // Android 10及以下
            arrayOf(
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        }
    }

    /**
     * 获取相册中的媒体文件
     * @param mediaType 媒体类型（ALL / IMAGE / VIDEO）
     * @return List<PhotoInfo> 媒体文件信息列表
     */
    suspend fun getAlbumPhotos(mediaType: UploadMediaType = UploadMediaType.ALL): List<PhotoInfo> = withContext(Dispatchers.IO) {
        val photos = mutableListOf<PhotoInfo>()

        if (!hasRequiredPermissions()) {
            Log.e(TAG, "Missing required permissions. Cannot access album media.")
            return@withContext photos
        }

        try {
            if (mediaType == UploadMediaType.ALL || mediaType == UploadMediaType.IMAGE) {
                photos += queryImages()
            }
            if (mediaType == UploadMediaType.ALL || mediaType == UploadMediaType.VIDEO) {
                photos += queryVideos()
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied while accessing album media", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error while reading album media", e)
        }

        // 图片与视频合并后按加入相册时间倒序排列
        return@withContext photos.sortedByDescending { it.dateAdded }
    }

    /**
     * 查询相册中的图片
     */
    private fun queryImages(): List<PhotoInfo> {
        val photos = mutableListOf<PhotoInfo>()

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.ORIENTATION
        )

        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        Log.d(TAG, "Querying images from MediaStore. URI: $collection")

        val cursor = context.contentResolver.query(collection, projection, null, null, sortOrder)
        if (cursor == null) {
            Log.e(TAG, "MediaStore images query failed: cursor is null")
            return photos
        }

        cursor.use { c ->
            val idIndex = c.getColumnIndex(MediaStore.Images.Media._ID)
            val nameIndex = c.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
            val mimeTypeIndex = c.getColumnIndex(MediaStore.Images.Media.MIME_TYPE)
            val sizeIndex = c.getColumnIndex(MediaStore.Images.Media.SIZE)
            val dateAddedIndex = c.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
            val dateModifiedIndex = c.getColumnIndex(MediaStore.Images.Media.DATE_MODIFIED)
            val widthIndex = c.getColumnIndex(MediaStore.Images.Media.WIDTH)
            val heightIndex = c.getColumnIndex(MediaStore.Images.Media.HEIGHT)
            val orientationIndex = c.getColumnIndex(MediaStore.Images.Media.ORIENTATION)

            var count = 0
            while (c.moveToNext()) {
                val id = c.getLong(idIndex)
                val contentUri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    id
                )
                photos.add(
                    PhotoInfo(
                        id = id,
                        uri = contentUri.toString(),
                        name = c.getString(nameIndex) ?: "",
                        mimeType = c.getString(mimeTypeIndex) ?: "image/jpeg",
                        size = c.getLong(sizeIndex),
                        dateAdded = c.getLong(dateAddedIndex),
                        dateModified = c.getLong(dateModifiedIndex),
                        width = c.getInt(widthIndex),
                        height = c.getInt(heightIndex),
                        orientation = c.getInt(orientationIndex)
                    )
                )
                count++
            }
            Log.i(TAG, "Successfully loaded $count images")
        }

        return photos
    }

    /**
     * 查询相册中的视频
     */
    private fun queryVideos(): List<PhotoInfo> {
        val photos = mutableListOf<PhotoInfo>()

        // 视频没有 ORIENTATION 列，宽高列名与图片一致
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT
        )

        val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        Log.d(TAG, "Querying videos from MediaStore. URI: $collection")

        val cursor = context.contentResolver.query(collection, projection, null, null, sortOrder)
        if (cursor == null) {
            Log.e(TAG, "MediaStore videos query failed: cursor is null")
            return photos
        }

        cursor.use { c ->
            val idIndex = c.getColumnIndex(MediaStore.Video.Media._ID)
            val nameIndex = c.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
            val mimeTypeIndex = c.getColumnIndex(MediaStore.Video.Media.MIME_TYPE)
            val sizeIndex = c.getColumnIndex(MediaStore.Video.Media.SIZE)
            val dateAddedIndex = c.getColumnIndex(MediaStore.Video.Media.DATE_ADDED)
            val dateModifiedIndex = c.getColumnIndex(MediaStore.Video.Media.DATE_MODIFIED)
            val widthIndex = c.getColumnIndex(MediaStore.Video.Media.WIDTH)
            val heightIndex = c.getColumnIndex(MediaStore.Video.Media.HEIGHT)

            var count = 0
            while (c.moveToNext()) {
                val id = c.getLong(idIndex)
                val contentUri = ContentUris.withAppendedId(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    id
                )
                photos.add(
                    PhotoInfo(
                        id = id,
                        uri = contentUri.toString(),
                        name = c.getString(nameIndex) ?: "",
                        mimeType = c.getString(mimeTypeIndex) ?: "video/mp4",
                        size = c.getLong(sizeIndex),
                        dateAdded = c.getLong(dateAddedIndex),
                        dateModified = c.getLong(dateModifiedIndex),
                        width = c.getInt(widthIndex),
                        height = c.getInt(heightIndex),
                        orientation = 0
                    )
                )
                count++
            }
            Log.i(TAG, "Successfully loaded $count videos")
        }

        return photos
    }

    /**
     * 文件摘要（流式哈希结果）：MD5 + 真实字节数
     */
    data class FileDigest(val md5: String, val size: Long)

    /**
     * 获取文件摘要（MD5 + 真实字节数），带本地缓存：
     * 缓存命中直接复用，未命中则流式哈希并写入缓存。
     * 供上传流程与「本地相册」已上传判定共用，保证两处 MD5 一致。
     */
    suspend fun getFileDigest(photoInfo: PhotoInfo): FileDigest? {
        getCachedDigest(photoInfo.uri)?.let { (md5, size) ->
            return FileDigest(md5, size)
        }
        val digest = hashFileStreaming(photoInfo) ?: return null
        putCachedDigest(photoInfo.uri, digest.md5, digest.size)
        return digest
    }

    /**
     * 流式计算文件 MD5（边读边 update，不整文件驻留内存），同时统计真实字节数。
     * 用于替代原来的「整文件读入 + 全量 MD5」，避免大视频 OOM。
     */
    private suspend fun hashFileStreaming(photoInfo: PhotoInfo): FileDigest? = withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(Uri.parse(photoInfo.uri))
                ?: run {
                    Log.e(TAG, "Failed to open InputStream for uri=${photoInfo.uri}")
                    return@withContext null
                }
            inputStream.use { stream ->
                val md = MessageDigest.getInstance("MD5")
                val buf = ByteArray(64 * 1024)
                var total = 0L
                var n: Int
                while (stream.read(buf).also { n = it } != -1) {
                    md.update(buf, 0, n)
                    total += n
                }
                FileDigest(md.digest().joinToString("") { "%02x".format(it) }, total)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Streaming hash failed: ${photoInfo.name}", e)
            null
        }
    }

    /**
     * 按需读取指定偏移的一段分片数据（O(1) seek，不整文件驻留内存）
     * @param startOffset 起始偏移
     * @param length 读取长度（字节）
     */
    private suspend fun readChunkAt(photoInfo: PhotoInfo, startOffset: Long, length: Int): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val pfd = context.contentResolver.openFileDescriptor(Uri.parse(photoInfo.uri), "r")
                ?: run {
                    Log.e(TAG, "Failed to open FileDescriptor for uri=${photoInfo.uri}")
                    return@withContext null
                }
            pfd.use {
                val fis = FileInputStream(it.fileDescriptor)
                fis.channel.position(startOffset)
                val out = ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                var remaining = length
                while (remaining > 0) {
                    val n = fis.read(buf, 0, minOf(buf.size, remaining))
                    if (n < 0) break
                    out.write(buf, 0, n)
                    remaining -= n
                }
                out.toByteArray()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Read chunk failed: ${photoInfo.name}", e)
            null
        }
    }

    /**
     * 提交文件元数据到服务器
     * @param baseUrl 服务器基础URL (例如 "http://192.168.1.100:8080")
     * @param filename 文件名
     * @param totalSize 文件总大小
     * @param md5Hash MD5哈希值
     * @param description 文件描述
     * @return 服务器返回的元数据 (包含 fileId 和分片信息)
     */
    private suspend fun submitMetadata(
        baseUrl: String,
        filename: String,
        totalSize: Long,
        md5Hash: String,
        description: String = ""
    ): Map<String, Any>? = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/api/submit_metadata"
            Log.i(TAG, "提交文件元数据到服务端（检查是否已上传）")
            Log.d(TAG, "  URL: $url")
            Log.d(TAG, "  文件名: $filename")
            Log.d(TAG, "  文件大小: $totalSize bytes")
            Log.d(TAG, "  文件MD5: $md5Hash")
            val json = JSONObject()
            json.put("filename", filename)
            json.put("total_size", totalSize)
            json.put("checksum", md5Hash)
            // 上传来源设备（厂商 + 型号，如 "Xiaomi 14 Pro"）
            json.put("source_device", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
            // 可选字段 description
            if (description.isNotEmpty()) {
                json.put("description", description)
            }

            val request = Request.Builder()
                .url(url)
                .post(json.toString().toRequestBody("application/json".toMediaType()))
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val body = response.body?.string()
                    Log.e(TAG, "Metadata request failed: url=$url, HTTP ${response.code}, body=${body?.take(500)}")
                    return@withContext null
                }

                val responseBody = response.body?.string()
                Log.d(TAG, "Metadata response: url=$url, body=${responseBody?.take(1000)}")

                if (responseBody == null) {
                    Log.e(TAG, "Empty response body: url=$url")
                    return@withContext null
                }

                val root = JSONObject(responseBody)
                val status = root.optInt("status", -1)
                val code = root.optString("code", "-1")
                val message = root.optString("message", "")
                
                if (status != 1 || code != "0") {
                    Log.e(TAG, "Server returned error: url=$url, status=$status, code=$code, message=$message, raw=${responseBody.take(500)}")
                    return@withContext null
                }

                val data = root.optJSONObject("data")
                if (data == null) {
                    Log.e(TAG, "Missing 'data' field in response: url=$url, raw=${responseBody.take(500)}")
                    return@withContext null
                }

                // 检查是否为重复文件（服务端已存在）
                val skipped = data.optBoolean("skipped", false)
                val result = mutableMapOf<String, Any>()
                
                if (skipped) {
                    // 文件已存在，不需要上传分片
                    result["skipped"] = true
                    result["id"] = data.optString("id", "")
                    result["filename"] = data.optString("filename", "")
                    result["checksum"] = data.optString("checksum", "")
                    result["file_path"] = data.optString("file_path", "")
                    result["total_size"] = data.optLong("total_size", 0)
                    Log.i(TAG, "检测到文件已存在（服务端返回skipped=true）: filename=$filename, md5=$md5Hash")
                    return@withContext result
                }
                
                // 新文件，需要解析分片信息
                val fileId = data.getString("id")
                val totalChunks = data.getInt("total_chunks")
                val chunksArray = data.getJSONArray("chunks")
                val chunks = mutableListOf<Map<String, Any>>()
                
                for (i in 0 until chunksArray.length()) {
                    val chunkObj = chunksArray.getJSONObject(i)
                    val chunkMap = mapOf(
                        "start_offset" to chunkObj.getLong("start_offset"),
                        "end_offset" to chunkObj.getLong("end_offset"),
                        "chunk_size" to chunkObj.getLong("chunk_size")
                    )
                    chunks.add(chunkMap)
                }

                result["id"] = fileId
                result["total_chunks"] = totalChunks
                result["chunks"] = chunks
                result["chunk_size"] = data.getLong("chunk_size")
                result["skipped"] = false

                // 断点续传：服务端可能复用同 checksum 的未完成记录，并返回已完成的分片偏移
                val uploadedOffsets = mutableListOf<Long>()
                val uploadedArray = data.optJSONArray("uploaded_chunks")
                if (uploadedArray != null) {
                    for (i in 0 until uploadedArray.length()) {
                        uploadedOffsets.add(uploadedArray.getLong(i))
                    }
                }
                result["uploaded_offsets"] = uploadedOffsets

                Log.i(TAG, "Metadata parsed: url=$url, fileId=$fileId, totalChunks=$totalChunks, skipped=false, resumableChunks=${uploadedOffsets.size}")
                return@withContext result
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error while submitting metadata: baseUrl=$baseUrl, filename=$filename", e)
            return@withContext null
        }
    }

    /**
     * 上传单个分片到服务器
     * @param baseUrl 服务器基础URL
     * @param chunkData 分片字节（调用方已按 offset 读取，不再持有整文件）
     * @param startOffset 分片起始偏移
     * @param endOffset 分片结束偏移
     * @param totalSize 文件总大小（用于 Content-Range 与日志）
     * @param chunkIndex 分片索引
     * @param totalChunks 总分片数
     * @param fileId 文件ID
     * @return Boolean 是否上传成功
     */
    private suspend fun uploadChunk(
        baseUrl: String,
        chunkData: ByteArray,
        startOffset: Long,
        endOffset: Long,
        totalSize: Long,
        chunkIndex: Int,
        totalChunks: Int,
        fileId: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            if (chunkData.isEmpty()) {
                return@withContext true // 没有数据需要上传
            }

            val url = "$baseUrl/api/upload"
            Log.d(
                TAG,
                "Chunk upload request: url=$url, fileId=$fileId, chunkIndex=$chunkIndex/$totalChunks, range=$startOffset-$endOffset/$totalSize, bytes=${chunkData.size}"
            )
            val request = Request.Builder()
                .url(url)
                .post(chunkData.toRequestBody("application/octet-stream".toMediaType()))
                .addHeader("X-File-ID", fileId)
                .addHeader("X-Start-Offset", startOffset.toString())
                .addHeader("Content-Range", "bytes $startOffset-$endOffset/$totalSize")
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()
                if (!response.isSuccessful) {
                    Log.e(TAG, "Chunk upload failed: url=$url, fileId=$fileId, chunkIndex=$chunkIndex, HTTP ${response.code}, body=${responseBody?.take(500)}")
                    return@withContext false
                }
                
                // 解析JSON响应并检查status和code字段
                try {
                    if (responseBody != null && responseBody.isNotEmpty()) {
                        val root = JSONObject(responseBody)
                        val status = root.optInt("status", -1)
                        val code = root.optString("code", "-1")
                        val message = root.optString("message", "")
                        
                        if (status != 1 || code != "0") {
                            Log.e(TAG, "Chunk upload server error: url=$url, fileId=$fileId, chunkIndex=$chunkIndex, status=$status, code=$code, message=$message")
                            return@withContext false
                        }
                        // 成功
                        Log.d(TAG, "Chunk uploaded: url=$url, fileId=$fileId, chunkIndex=$chunkIndex")
                        return@withContext true
                    } else {
                        // 空响应体，假设成功（可能某些实现返回空）
                        Log.d(TAG, "Chunk uploaded (empty response): url=$url, fileId=$fileId, chunkIndex=$chunkIndex")
                        return@withContext true
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse chunk upload response: url=$url, fileId=$fileId, chunkIndex=$chunkIndex, body=${responseBody?.take(500)}", e)
                    return@withContext false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error while uploading chunk: baseUrl=$baseUrl, fileId=$fileId, chunkIndex=$chunkIndex/$totalChunks", e)
            return@withContext false
        }
    }

    /**
     * 上传单个照片文件
     * @param baseUrl 服务器基础URL
     * @param photoInfo 照片信息
     * @param progressCallback 进度回调（可选）
     * @return Boolean 是否上传成功
     */
    private suspend fun uploadSingleFile(
        baseUrl: String,
        photoInfo: PhotoInfo,
        progressCallback: UploadProgressCallback? = null,
        totalFiles: Int? = null,
        currentFileIndex: Int? = null
    ): Boolean = withContext(Dispatchers.IO) {
        Log.i(TAG, "══════════════════════════════════════════════════════════════")
        Log.i(TAG, "开始上传文件: ${photoInfo.name} (${currentFileIndex ?: "?"}/${totalFiles ?: "?"})")
        Log.i(TAG, "  文件大小: ${photoInfo.size} bytes")
        Log.i(TAG, "  服务器地址: $baseUrl")
        Log.i(TAG, "══════════════════════════════════════════════════════════════")

        progressCallback?.invoke(photoInfo, 0f, UploadStatus.Uploading, totalFiles, currentFileIndex)

        try {
            // 1. 计算 MD5（流式，带本地缓存：文件未变则复用，避免重传重复扫全量）
            val digest = getFileDigest(photoInfo)
            if (digest == null || digest.size <= 0) {
                Log.e(TAG, "File data is empty or hash failed: ${photoInfo.name}")
                progressCallback?.invoke(photoInfo, 0f, UploadStatus.Failed("File data is empty"), totalFiles, currentFileIndex)
                return@withContext false
            }
            val md5Hash = digest.md5
            val totalSize = digest.size
            Log.d(TAG, "File MD5: baseUrl=$baseUrl, filename=${photoInfo.name}, md5=$md5Hash, size=$totalSize")

            // 2. 提交元数据（包含去重检查）
            val metadata = submitMetadata(baseUrl, photoInfo.name, totalSize, md5Hash)
            if (metadata == null) {
                Log.w(TAG, "Metadata submission failed. Will retry: ${photoInfo.name}")
                progressCallback?.invoke(photoInfo, 0f, UploadStatus.Uploading, totalFiles, currentFileIndex)
                return@withContext false
            }

            // 检查是否为重复文件（服务端已存在）
            val skipped = metadata["skipped"] as? Boolean ?: false
            if (skipped) {
                val existingFileId = metadata["id"] as? String ?: ""
                val existingFilename = metadata["filename"] as? String ?: ""
                val existingChecksum = metadata["checksum"] as? String ?: ""
                Log.i(TAG, "══════════════════════════════════════════════════════════════")
                Log.i(TAG, "文件已存在，跳过上传（MD5去重）")
                Log.i(TAG, "  当前文件名: ${photoInfo.name}")
                Log.i(TAG, "  当前文件MD5: $md5Hash")
                Log.i(TAG, "  当前文件大小: $totalSize bytes")
                Log.i(TAG, "  服务端文件ID: $existingFileId")
                Log.i(TAG, "  服务端文件名: $existingFilename")
                Log.i(TAG, "  服务端文件MD5: $existingChecksum")
                Log.i(TAG, "══════════════════════════════════════════════════════════════")
                progressCallback?.invoke(photoInfo, 1f, UploadStatus.Completed, totalFiles, currentFileIndex)
                return@withContext true
            }

            val fileId = metadata["id"] as? String ?: "unknown"
            val chunksAny = metadata["chunks"] as? List<*>
            val chunkRanges = mutableListOf<ChunkRange>()
            if (chunksAny != null) {
                for (c in chunksAny) {
                    val m = c as? Map<*, *> ?: continue
                    val start = m["start_offset"] as? Long
                    val end = m["end_offset"] as? Long
                    if (start != null && end != null) {
                        chunkRanges.add(ChunkRange(startOffset = start, endOffset = end))
                    }
                }
            }
            val totalChunks = chunkRanges.size
            if (totalChunks <= 0) {
                Log.e(TAG, "No chunks returned from metadata: filename=${photoInfo.name}")
                progressCallback?.invoke(photoInfo, 0f, UploadStatus.Failed("No chunks returned from server"), totalFiles, currentFileIndex)
                return@withContext false
            }

            // 断点续传：跳过服务端已确认上传完成的分片（中断后重连可续传）
            @Suppress("UNCHECKED_CAST")
            val uploadedOffsets = (metadata["uploaded_offsets"] as? List<*>)
                ?.mapNotNull { (it as? Number)?.toLong() }
                ?.toSet() ?: emptySet()
            val skippedChunks = if (uploadedOffsets.isNotEmpty()) chunkRanges.count { it.startOffset in uploadedOffsets } else 0
            // 至少保留最后一个分片走一遍上传：最后一片会触发服务端的合并/校验流程
            val pendingChunks = chunkRanges.filter { it.startOffset !in uploadedOffsets || it == chunkRanges.last() }
            var doneCount = skippedChunks
            if (skippedChunks > 0) {
                Log.i(TAG, "Resuming ${photoInfo.name}: skipping $skippedChunks/$totalChunks already-uploaded chunks")
            }

            // 4. 上传分片
            for (chunkRange in pendingChunks) {
                val chunkIndex = chunkRanges.indexOf(chunkRange)
                if (shouldStop) {
                    Log.i(TAG, "Upload stopped by user: ${photoInfo.name}")
                    progressCallback?.invoke(photoInfo, 0f, UploadStatus.Failed("Upload stopped"), totalFiles, currentFileIndex)
                    return@withContext false
                }

                waitWhilePaused()

                while (true) {
                    if (shouldStop) {
                        Log.i(TAG, "Upload stopped by user during retry: ${photoInfo.name}")
                        progressCallback?.invoke(photoInfo, 0f, UploadStatus.Failed("Upload stopped"), totalFiles, currentFileIndex)
                        return@withContext false
                    }

                    // 按需读取该分片字节（不整文件驻留内存，避免大视频 OOM）
                    val chunkLength = (chunkRange.endOffset - chunkRange.startOffset + 1).toInt()
                    val chunkData = readChunkAt(photoInfo, chunkRange.startOffset, chunkLength)
                    val uploadSuccess = if (chunkData == null) {
                        Log.e(TAG, "Failed to read chunk at offset ${chunkRange.startOffset}: ${photoInfo.name}")
                        false
                    } else {
                        uploadChunk(
                            baseUrl = baseUrl,
                            chunkData = chunkData,
                            startOffset = chunkRange.startOffset,
                            endOffset = chunkRange.endOffset,
                            totalSize = totalSize,
                            chunkIndex = chunkIndex,
                            totalChunks = totalChunks,
                            fileId = fileId
                        )
                    }
                    if (uploadSuccess) break

                    Log.w(TAG, "Chunk upload failed. Will retry after ${UPLOAD_RETRY_DELAY_MS}ms. chunkIndex=$chunkIndex, file=${photoInfo.name}")
                    kotlinx.coroutines.delay(UPLOAD_RETRY_DELAY_MS)
                }

                // 更新进度
                doneCount++
                val progress = doneCount.toFloat() / totalChunks
                progressCallback?.invoke(photoInfo, progress, UploadStatus.Uploading, totalFiles, currentFileIndex)
            }

            Log.i(TAG, "══════════════════════════════════════════════════════════════")
            Log.i(TAG, "文件上传完成: ${photoInfo.name}")
            Log.i(TAG, "  文件大小: $totalSize bytes")
            Log.i(TAG, "  文件MD5: $md5Hash")
            Log.i(TAG, "  服务端文件ID: $fileId")
            Log.i(TAG, "  分片数量: $totalChunks")
            Log.i(TAG, "══════════════════════════════════════════════════════════════")
            progressCallback?.invoke(photoInfo, 1f, UploadStatus.Completed, totalFiles, currentFileIndex)
            return@withContext true

        } catch (e: Exception) {
            Log.e(TAG, "File upload failed: baseUrl=$baseUrl, filename=${photoInfo.name}", e)
            progressCallback?.invoke(photoInfo, 0f, UploadStatus.Failed(e.message ?: "Unknown error"), totalFiles, currentFileIndex)
            return@withContext false
        }
    }

    /**
     * 启动相册自动上传
     * @param baseUrl 服务器基础URL
     * @param mediaType 媒体类型（ALL / IMAGE / VIDEO）
     * @param progressCallback 进度回调（可选）
     */
    fun startAlbumUpload(
        baseUrl: String,
        mediaType: UploadMediaType = UploadMediaType.ALL,
        progressCallback: UploadProgressCallback? = null
    ) {
        if (isUploading) {
            Log.w(TAG, "Album upload is already running")
            return
        }

        Log.i(TAG, "startAlbumUpload: baseUrl=$baseUrl, mediaType=$mediaType")

        if (!hasRequiredPermissions()) {
            Log.e(TAG, "Missing required permissions. Cannot access album photos.")
            progressCallback?.invoke(
                PhotoInfo(0, "", "", "", 0, 0, 0, 0, 0, 0),
                0f,
                UploadStatus.Failed("Missing permissions"),
                null,
                null
            )
            return
        }

        isUploading = true
        shouldStop = false
        isPaused = false

        uploadJob = coroutineScope.launch {
            try {
                // 1. 获取相册媒体文件
                val photos = getAlbumPhotos(mediaType)
                if (photos.isEmpty()) {
                    Log.i(TAG, "No media files found in album")
                    isUploading = false
                    return@launch
                }

                Log.i(TAG, "Found ${photos.size} media files. Starting upload with $PARALLEL_UPLOADS parallel workers...")

                val nextIndex = AtomicInteger(0)
                val successCount = AtomicInteger(0)
                val failedAttempts = AtomicInteger(0)
                val settledFiles = AtomicInteger(0)

                // 单个 worker：循环从队列领取下一个待上传文件，直到队列耗尽或用户停止
                val worker: suspend () -> Unit = worker@{
                    while (!shouldStop) {
                        waitWhilePaused()
                        val index = nextIndex.getAndIncrement()
                        if (index >= photos.size) return@worker

                        val photo = photos[index]
                        Log.i(TAG, "Worker picked photo ${index + 1}/${photos.size}: ${photo.name}")

                        // 整文件失败后重试直到成功或用户停止（保持原有语义）
                        while (!shouldStop) {
                            val success = uploadSingleFile(baseUrl, photo, progressCallback, photos.size, index)
                            if (success) {
                                successCount.incrementAndGet()
                                break
                            }
                            failedAttempts.incrementAndGet()
                            Log.w(TAG, "File upload failed. Will retry after ${UPLOAD_RETRY_DELAY_MS}ms: ${photo.name}")
                            kotlinx.coroutines.delay(UPLOAD_RETRY_DELAY_MS)
                        }

                        if (!shouldStop) {
                            val settled = settledFiles.incrementAndGet()
                            Log.i(TAG, "Progress: $settled/${photos.size} files settled (success=${successCount.get()})")
                        }
                    }
                }

                // 2. 并行启动 worker 同时上传多个文件
                val workers = List(PARALLEL_UPLOADS) { launch { worker() } }
                workers.joinAll()

                Log.i(TAG, "══════════════════════════════════════════════════════════════")
                Log.i(TAG, "相册上传完成！统计信息：")
                Log.i(TAG, "  总文件数: ${photos.size}")
                Log.i(TAG, "  成功上传: ${successCount.get()}")
                Log.i(TAG, "  失败重试次数: ${failedAttempts.get()}")
                Log.i(TAG, "  注: 已上传过的文件会显示'文件已存在，跳过上传'日志")
                Log.i(TAG, "══════════════════════════════════════════════════════════════")
                progressCallback?.invoke(
                    PhotoInfo(0, "", "", "", 0, 0, 0, 0, 0, 0),
                    1f,
                    UploadStatus.Completed,
                    photos.size,
                    photos.size - 1
                )

            } catch (e: Exception) {
                Log.e(TAG, "Error during album upload", e)
                progressCallback?.invoke(
                    PhotoInfo(0, "", "", "", 0, 0, 0, 0, 0, 0),
                    0f,
                    UploadStatus.Failed(e.message ?: "Unknown error"),
                    null,
                    null
                )
            } finally {
                isUploading = false
            }
        }
    }

    /**
     * 停止相册上传
     */
    fun stopAlbumUpload() {
        shouldStop = true
        isUploading = false
        uploadJob?.cancel()
        Log.i(TAG, "Album upload stopped")
    }

    /**
     * 暂停相册上传（不取消任务，worker 在分片边界挂起）
     */
    fun pauseAlbumUpload() {
        isPaused = true
        Log.i(TAG, "Album upload paused")
    }

    /**
     * 恢复相册上传
     */
    fun resumeAlbumUpload() {
        isPaused = false
        Log.i(TAG, "Album upload resumed")
    }

    /**
     * 检查是否已暂停
     */
    fun isPaused(): Boolean = isPaused

    /**
     * 暂停期间挂起当前协程，直到恢复或停止
     */
    private suspend fun waitWhilePaused() {
        while (isPaused && !shouldStop) {
            kotlinx.coroutines.delay(100L)
        }
    }

    /**
     * 检查是否正在上传
     */
    fun isUploading(): Boolean = isUploading
}