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

/**
 * 上传进度回调
 */
typealias UploadProgressCallback = (photoInfo: PhotoInfo, progress: Float, status: UploadStatus) -> Unit

/**
 * Android 相册上传管理器
 * 负责权限检查、相册查询和照片上传
 */
class AlbumUploadManager(private val context: Context) {
    companion object {
        private const val TAG = "AlbumUploadManager"
        private const val UPLOAD_DELAY_MS = 10000L // 每个文件上传后等待10秒
        private const val MAX_RETRY_COUNT = 3 // 最大重试次数
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
            Log.e(TAG, "权限不足，无法访问相册")
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

            Log.d(TAG, "查询 MediaStore 中的图片... URI: $collection")

            // 查询MediaStore获取所有图片
            val cursor = context.contentResolver.query(
                collection,
                projection,
                null,
                null,
                sortOrder
            )

            if (cursor == null) {
                Log.e(TAG, "查询 MediaStore 失败 - cursor 为 null")
                return@withContext photos
            }

            cursor.use { cursor ->
                Log.d(TAG, "Cursor 列数: ${cursor.columnCount}")
                Log.d(TAG, "在 MediaStore 中找到 ${cursor.count} 张照片")

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
                        Log.d(TAG, "照片 $photoCount - 名称: ${photoInfo.name}, 大小: ${photoInfo.size}")
                    }
                }

                Log.i(TAG, "成功处理 $photoCount 张照片")
            }

        } catch (e: SecurityException) {
            Log.e(TAG, "访问照片时权限被拒绝", e)
        } catch (e: Exception) {
            Log.e(TAG, "访问相册时出错", e)
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
                Log.e(TAG, "无法为 URI 打开输入流: ${photoInfo.uri}")
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
                Log.i(TAG, "成功从照片读取 ${fileData.size} 字节: ${photoInfo.name}")
                return@withContext fileData
            }
        } catch (e: Exception) {
            Log.e(TAG, "读取照片数据时出错: ${photoInfo.name}", e)
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
            Log.i(TAG, "提交元数据请求: url=$url, filename=$filename, totalSize=$totalSize")
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
                    Log.e(TAG, "提交元数据失败: url=$url, HTTP ${response.code}, body=${body?.take(500)}")
                    return@withContext null
                }

                val responseBody = response.body?.string()
                Log.d(TAG, "元数据响应: url=$url, body=${responseBody?.take(1000)}")

                if (responseBody == null) {
                    Log.e(TAG, "响应体为空: url=$url")
                    return@withContext null
                }

                val root = JSONObject(responseBody)
                val code = root.optInt("code", -1)
                if (code != 200) {
                    Log.e(TAG, "服务器返回错误代码: url=$url, code=$code, raw=${responseBody.take(500)}")
                    return@withContext null
                }

                val data = root.optJSONObject("data")
                if (data == null) {
                    Log.e(TAG, "响应中缺少 data 字段: url=$url, raw=${responseBody.take(500)}")
                    return@withContext null
                }

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

                val result = mutableMapOf<String, Any>()
                result["id"] = fileId
                result["total_chunks"] = totalChunks
                result["chunks"] = chunks
                result["chunk_size"] = data.getLong("chunk_size")
                
                Log.i(TAG, "解析元数据成功: url=$url, fileId=$fileId, totalChunks=$totalChunks")
                return@withContext result
            }
        } catch (e: Exception) {
            Log.e(TAG, "提交元数据时出错: baseUrl=$baseUrl, filename=$filename", e)
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
        chunkIndex: Int,
        totalChunks: Int,
        fileId: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val chunkSize = CHUNK_SIZE
            val startOffset = chunkIndex * chunkSize
            val endOffset = minOf((chunkIndex + 1) * chunkSize, fileData.size) - 1
            
            if (startOffset >= fileData.size) {
                return@withContext true // 没有数据需要上传
            }

            val chunkData = fileData.copyOfRange(startOffset, endOffset + 1)
            
            val url = "$baseUrl/api/upload"
            Log.d(
                TAG,
                "上传分片请求: url=$url, fileId=$fileId, chunkIndex=$chunkIndex/$totalChunks, range=$startOffset-$endOffset/${fileData.size}, bytes=${chunkData.size}"
            )
            val request = Request.Builder()
                .url(url)
                .post(chunkData.toRequestBody("application/octet-stream".toMediaType()))
                .addHeader("X-File-ID", fileId)
                .addHeader("X-Start-Offset", startOffset.toString())
                .addHeader("Content-Range", "bytes $startOffset-$endOffset/${fileData.size}")
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                val success = response.isSuccessful
                if (!success) {
                    val body = response.body?.string()
                    Log.e(TAG, "分片上传失败: url=$url, fileId=$fileId, chunkIndex=$chunkIndex, HTTP ${response.code}, body=${body?.take(500)}")
                }
                return@withContext success
            }
        } catch (e: Exception) {
            Log.e(TAG, "分片上传时出错: baseUrl=$baseUrl, fileId=$fileId, chunkIndex=$chunkIndex/$totalChunks", e)
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
        progressCallback: UploadProgressCallback? = null
    ): Boolean = withContext(Dispatchers.IO) {
        Log.i(TAG, "开始上传文件: baseUrl=$baseUrl, filename=${photoInfo.name}")

        progressCallback?.invoke(photoInfo, 0f, UploadStatus.Uploading)

        try {
            // 1. 读取照片数据
            val fileData = readPhotoData(photoInfo)
            if (fileData == null || fileData.isEmpty()) {
                Log.e(TAG, "文件数据为空: ${photoInfo.name}")
                progressCallback?.invoke(photoInfo, 0f, UploadStatus.Failed("文件数据为空"))
                return@withContext false
            }

            // 2. 计算MD5
            val md5Hash = calculateMD5(fileData)
            Log.d(TAG, "文件 MD5: baseUrl=$baseUrl, filename=${photoInfo.name}, md5=$md5Hash")

            // 3. 提交元数据
            val metadata = submitMetadata(baseUrl, photoInfo.name, fileData.size.toLong(), md5Hash)
            if (metadata == null) {
                Log.e(TAG, "提交元数据失败: ${photoInfo.name}")
                progressCallback?.invoke(photoInfo, 0f, UploadStatus.Failed("提交元数据失败"))
                return@withContext false
            }

            val fileId = metadata["id"] as? String ?: "unknown"
            val totalChunks = metadata["total_chunks"] as? Int ?: 1

            // 4. 上传分片
            for (chunkIndex in 0 until totalChunks) {
                if (shouldStop) {
                    Log.i(TAG, "上传被用户停止: ${photoInfo.name}")
                    progressCallback?.invoke(photoInfo, 0f, UploadStatus.Failed("上传被停止"))
                    return@withContext false
                }

                var retryCount = 0
                var uploadSuccess = false

                while (retryCount < MAX_RETRY_COUNT && !uploadSuccess) {
                    uploadSuccess = uploadChunk(baseUrl, fileData, chunkIndex, totalChunks, fileId)
                    if (!uploadSuccess) {
                        retryCount++
                        Log.w(TAG, "分片 $chunkIndex 上传失败，重试 $retryCount/$MAX_RETRY_COUNT")
                        if (retryCount < MAX_RETRY_COUNT) {
                            kotlinx.coroutines.delay(2000) // 重试前等待2秒
                        }
                    }
                }

                if (!uploadSuccess) {
                    Log.e(TAG, "分片 $chunkIndex 上传失败，达到最大重试次数")
                    progressCallback?.invoke(photoInfo, chunkIndex.toFloat() / totalChunks, UploadStatus.Failed("分片上传失败"))
                    return@withContext false
                }

                // 更新进度
                val progress = (chunkIndex + 1).toFloat() / totalChunks
                progressCallback?.invoke(photoInfo, progress, UploadStatus.Uploading)
            }

            Log.i(TAG, "文件上传完成: ${photoInfo.name}")
            progressCallback?.invoke(photoInfo, 1f, UploadStatus.Completed)
            return@withContext true

        } catch (e: Exception) {
            Log.e(TAG, "上传文件失败: baseUrl=$baseUrl, filename=${photoInfo.name}", e)
            progressCallback?.invoke(photoInfo, 0f, UploadStatus.Failed(e.message ?: "未知错误"))
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
            Log.w(TAG, "相册上传已在运行中")
            return
        }

        Log.i(TAG, "startAlbumUpload: baseUrl=$baseUrl")

        if (!hasRequiredPermissions()) {
            Log.e(TAG, "权限不足，无法访问相册")
            progressCallback?.invoke(
                PhotoInfo(0, "", "", "", 0, 0, 0, 0, 0, 0),
                0f,
                UploadStatus.Failed("权限不足")
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
                    Log.i(TAG, "相册中没有找到照片")
                    isUploading = false
                    return@launch
                }

                Log.i(TAG, "找到 ${photos.size} 张照片，开始上传...")

                // 2. 逐个上传
                for ((index, photo) in photos.withIndex()) {
                    if (shouldStop) {
                        Log.i(TAG, "上传被用户停止")
                        break
                    }

                    Log.i(TAG, "上传照片 ${index + 1}/${photos.size}: ${photo.name}")
                    val success = uploadSingleFile(baseUrl, photo, progressCallback)

                    if (!success) {
                        Log.e(TAG, "照片上传失败: ${photo.name}")
                    }

                    // 每个文件上传后等待一段时间（最后一个文件不等待）
                    if (index < photos.size - 1 && !shouldStop) {
                        Log.d(TAG, "等待 ${UPLOAD_DELAY_MS}ms 后上传下一个文件...")
                        kotlinx.coroutines.delay(UPLOAD_DELAY_MS)
                    }
                }

                Log.i(TAG, "相册上传完成")
                progressCallback?.invoke(
                    PhotoInfo(0, "", "", "", 0, 0, 0, 0, 0, 0),
                    1f,
                    UploadStatus.Completed
                )

            } catch (e: Exception) {
                Log.e(TAG, "相册上传过程中出错", e)
                progressCallback?.invoke(
                    PhotoInfo(0, "", "", "", 0, 0, 0, 0, 0, 0),
                    0f,
                    UploadStatus.Failed(e.message ?: "未知错误")
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
        Log.i(TAG, "相册上传已停止")
    }

    /**
     * 检查是否正在上传
     */
    fun isUploading(): Boolean = isUploading
}