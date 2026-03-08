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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

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
        private const val CHUNK_SIZE = 2 * 1024 * 1024 // 2MB分片
    }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private var isUploading = false
    private var shouldStop = false
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
            // Android 13+ (API 33+)
            arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES)
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
     * 获取相册中所有照片
     * @return List<PhotoInfo> 照片信息列表
     */
    suspend fun getAlbumPhotos(): List<PhotoInfo> = withContext(Dispatchers.IO) {
        val photos = mutableListOf<PhotoInfo>()
        
        if (!hasRequiredPermissions()) {
            Log.e(TAG, "Missing required permissions. Cannot access album photos.")
            return@withContext photos
        }

        try {
            // MediaStore查询的列
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

            // 使用外部存储的内容URI
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }

            Log.d(TAG, "Querying images from MediaStore. URI: $collection")

            // 查询MediaStore获取所有图片
            val cursor = context.contentResolver.query(
                collection,
                projection,
                null,
                null,
                sortOrder
            )

            if (cursor == null) {
                Log.e(TAG, "MediaStore query failed: cursor is null")
                return@withContext photos
            }

            cursor.use { cursor ->
                Log.d(TAG, "Cursor columns: ${cursor.columnCount}")
                Log.d(TAG, "Found ${cursor.count} photos in MediaStore")

                val idIndex = cursor.getColumnIndex(MediaStore.Images.Media._ID)
                val nameIndex = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                val mimeTypeIndex = cursor.getColumnIndex(MediaStore.Images.Media.MIME_TYPE)
                val sizeIndex = cursor.getColumnIndex(MediaStore.Images.Media.SIZE)
                val dateAddedIndex = cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
                val dateModifiedIndex = cursor.getColumnIndex(MediaStore.Images.Media.DATE_MODIFIED)
                val widthIndex = cursor.getColumnIndex(MediaStore.Images.Media.WIDTH)
                val heightIndex = cursor.getColumnIndex(MediaStore.Images.Media.HEIGHT)
                val orientationIndex = cursor.getColumnIndex(MediaStore.Images.Media.ORIENTATION)

                var photoCount = 0
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIndex)

                    // 构建内容URI
                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id
                    )

                    val photoInfo = PhotoInfo(
                        id = id,
                        uri = contentUri.toString(),
                        name = cursor.getString(nameIndex) ?: "",
                        mimeType = cursor.getString(mimeTypeIndex) ?: "image/jpeg",
                        size = cursor.getLong(sizeIndex),
                        dateAdded = cursor.getLong(dateAddedIndex),
                        dateModified = cursor.getLong(dateModifiedIndex),
                        width = cursor.getInt(widthIndex),
                        height = cursor.getInt(heightIndex),
                        orientation = cursor.getInt(orientationIndex)
                    )

                    photos.add(photoInfo)
                    photoCount++

                    // 调试：记录前几张照片
                    if (photoCount <= 3) {
                        Log.d(TAG, "Photo $photoCount - name=${photoInfo.name}, size=${photoInfo.size}")
                    }
                }

                Log.i(TAG, "Successfully loaded $photoCount photos")
            }

        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied while accessing photos", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error while reading album photos", e)
        }

        return@withContext photos
    }

    /**
     * 读取照片文件内容为字节数组
     */
    private suspend fun readPhotoData(photoInfo: PhotoInfo): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val contentUri = Uri.parse(photoInfo.uri)
            val inputStream: InputStream? = context.contentResolver.openInputStream(contentUri)
            if (inputStream == null) {
                Log.e(TAG, "Failed to open InputStream for uri=${photoInfo.uri}")
                return@withContext null
            }

            inputStream.use { stream ->
                val buffer = ByteArrayOutputStream()
                val data = ByteArray(8192)
                var bytesRead: Int

                while (stream.read(data).also { bytesRead = it } != -1) {
                    buffer.write(data, 0, bytesRead)
                }

                val fileData = buffer.toByteArray()
                Log.i(TAG, "Read ${fileData.size} bytes from photo: ${photoInfo.name}")
                return@withContext fileData
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read photo data: ${photoInfo.name}", e)
            return@withContext null
        }
    }

    /**
     * 计算字节数组的 MD5 哈希值
     */
    private fun calculateMD5(data: ByteArray): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(data)
        return digest.joinToString("") { "%02x".format(it) }
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
                
                Log.i(TAG, "Metadata parsed: url=$url, fileId=$fileId, totalChunks=$totalChunks, skipped=false")
                return@withContext result
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error while submitting metadata: baseUrl=$baseUrl, filename=$filename", e)
            return@withContext null
        }
    }

    /**
     * 上传分片到服务器
     * @param baseUrl 服务器基础URL
     * @param fileData 文件数据字节数组
     * @param chunkIndex 分片索引
     * @param totalChunks 总分片数
     * @param fileId 文件ID
     * @return Boolean 是否上传成功
     */
    private suspend fun uploadChunk(
        baseUrl: String,
        fileData: ByteArray,
        startOffset: Long,
        endOffset: Long,
        chunkIndex: Int,
        totalChunks: Int,
        fileId: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            if (startOffset >= fileData.size) {
                return@withContext true // 没有数据需要上传
            }

            val safeEndOffset = minOf(endOffset, fileData.size.toLong() - 1)
            if (safeEndOffset < startOffset) {
                Log.e(TAG, "Invalid chunk range: startOffset=$startOffset, endOffset=$endOffset, fileSize=${fileData.size}")
                return@withContext false
            }

            val chunkData = fileData.copyOfRange(startOffset.toInt(), safeEndOffset.toInt() + 1)
            
            val url = "$baseUrl/api/upload"
            Log.d(
                TAG,
                "Chunk upload request: url=$url, fileId=$fileId, chunkIndex=$chunkIndex/$totalChunks, range=$startOffset-$safeEndOffset/${fileData.size}, bytes=${chunkData.size}"
            )
            val request = Request.Builder()
                .url(url)
                .post(chunkData.toRequestBody("application/octet-stream".toMediaType()))
                .addHeader("X-File-ID", fileId)
                .addHeader("X-Start-Offset", startOffset.toString())
                .addHeader("Content-Range", "bytes $startOffset-$safeEndOffset/${fileData.size}")
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
            // 1. 读取照片数据
            val fileData = readPhotoData(photoInfo)
            if (fileData == null || fileData.isEmpty()) {
                Log.e(TAG, "File data is empty: ${photoInfo.name}")
                progressCallback?.invoke(photoInfo, 0f, UploadStatus.Failed("File data is empty"), totalFiles, currentFileIndex)
                return@withContext false
            }

            // 2. 计算MD5
            val md5Hash = calculateMD5(fileData)
            Log.d(TAG, "File MD5: baseUrl=$baseUrl, filename=${photoInfo.name}, md5=$md5Hash")

            // 3. 提交元数据（包含去重检查）
            val metadata = submitMetadata(baseUrl, photoInfo.name, fileData.size.toLong(), md5Hash)
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
                Log.i(TAG, "  当前文件大小: ${fileData.size} bytes")
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

            // 4. 上传分片
            for ((chunkIndex, chunkRange) in chunkRanges.withIndex()) {
                if (shouldStop) {
                    Log.i(TAG, "Upload stopped by user: ${photoInfo.name}")
                    progressCallback?.invoke(photoInfo, 0f, UploadStatus.Failed("Upload stopped"), totalFiles, currentFileIndex)
                    return@withContext false
                }

                while (true) {
                    if (shouldStop) {
                        Log.i(TAG, "Upload stopped by user during retry: ${photoInfo.name}")
                        progressCallback?.invoke(photoInfo, 0f, UploadStatus.Failed("Upload stopped"), totalFiles, currentFileIndex)
                        return@withContext false
                    }

                    val uploadSuccess = uploadChunk(
                        baseUrl = baseUrl,
                        fileData = fileData,
                        startOffset = chunkRange.startOffset,
                        endOffset = chunkRange.endOffset,
                        chunkIndex = chunkIndex,
                        totalChunks = totalChunks,
                        fileId = fileId
                    )
                    if (uploadSuccess) break

                    Log.w(TAG, "Chunk upload failed. Will retry after ${UPLOAD_RETRY_DELAY_MS}ms. chunkIndex=$chunkIndex, file=${photoInfo.name}")
                    kotlinx.coroutines.delay(UPLOAD_RETRY_DELAY_MS)
                }

                // 更新进度
                val progress = (chunkIndex + 1).toFloat() / totalChunks
                progressCallback?.invoke(photoInfo, progress, UploadStatus.Uploading, totalFiles, currentFileIndex)
            }

            Log.i(TAG, "══════════════════════════════════════════════════════════════")
            Log.i(TAG, "文件上传完成: ${photoInfo.name}")
            Log.i(TAG, "  文件大小: ${fileData.size} bytes")
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
     * @param progressCallback 进度回调（可选）
     */
    fun startAlbumUpload(baseUrl: String, progressCallback: UploadProgressCallback? = null) {
        if (isUploading) {
            Log.w(TAG, "Album upload is already running")
            return
        }

        Log.i(TAG, "startAlbumUpload: baseUrl=$baseUrl")

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

        uploadJob = coroutineScope.launch {
            try {
                // 1. 获取相册照片
                val photos = getAlbumPhotos()
                if (photos.isEmpty()) {
                    Log.i(TAG, "No photos found in album")
                    isUploading = false
                    return@launch
                }

                Log.i(TAG, "Found ${photos.size} photos. Starting upload...")

                var successCount = 0
                var skippedCount = 0
                var failedCount = 0

                // 2. 逐个上传
                for ((index, photo) in photos.withIndex()) {
                    if (shouldStop) {
                        Log.i(TAG, "Upload stopped by user")
                        break
                    }

                    Log.i(TAG, "Uploading photo ${index + 1}/${photos.size}: ${photo.name}")

                    // Retry the whole file until success (or until stopped)
                    while (!shouldStop) {
                        val success = uploadSingleFile(baseUrl, photo, progressCallback, photos.size, index)
                        if (success) {
                            successCount++
                            // 检查是否是跳过的文件（通过日志判断）
                            // 由于我们无法直接获取跳过信息，这里只是统计总数
                            break
                        }
                        failedCount++
                        Log.w(TAG, "File upload failed. Will retry after ${UPLOAD_RETRY_DELAY_MS}ms: ${photo.name}")
                        kotlinx.coroutines.delay(UPLOAD_RETRY_DELAY_MS)
                    }
                }

                Log.i(TAG, "══════════════════════════════════════════════════════════════")
                Log.i(TAG, "相册上传完成！统计信息：")
                Log.i(TAG, "  总文件数: ${photos.size}")
                Log.i(TAG, "  成功上传: $successCount")
                Log.i(TAG, "  失败: $failedCount")
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
     * 检查是否正在上传
     */
    fun isUploading(): Boolean = isUploading
}