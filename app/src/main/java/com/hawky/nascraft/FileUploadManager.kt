package com.hawky.nascraft

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 已上传文件信息
 */
data class UploadedFile(
    val fileId: String,
    val filename: String,
    val totalSize: Long,
    val checksum: String,
    val status: Int,
    val filePath: String,
    val lastUpdated: Long
) {
    companion object {
        private const val TAG = "UploadedFile"
    }
}

/**
 * 已上传文件列表响应
 */
data class UploadedFilesResponse(
    val totalFiles: Int,
    val files: List<UploadedFile>
)

/**
 * 文件上传管理器
 * 负责获取已上传文件列表、上传文件等操作
 */
class FileUploadManager(private val context: android.content.Context) {
    companion object {
        private const val TAG = "FileUploadManager"
        private const val DEFAULT_PAGE_SIZE = 20
    }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * 获取已上传文件列表
     * @param baseUrl 服务器基础URL (例如 "http://192.168.1.100:8080")
     * @param page 页码，从1开始
     * @param pageSize 每页数量，默认20
     * @param status 文件状态过滤，0=上传中, 1=处理中, 2=已完成，null表示不过滤
     * @return UploadedFilesResponse 文件列表响应
     */
    suspend fun getUploadedFiles(
        baseUrl: String,
        page: Int = 1,
        pageSize: Int = DEFAULT_PAGE_SIZE,
        status: Int? = null
    ): UploadedFilesResponse? {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val url = StringBuilder("$baseUrl/api/uploaded_files?page=$page&page_size=$pageSize")
                status?.let {
                    url.append("&status=$it")
                }

                Log.d(TAG, "Fetching uploaded files: url=$url")

                val request = Request.Builder()
                    .url(url.toString())
                    .get()
                    .build()

                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val body = response.body?.string()
                        Log.e(TAG, "Failed to fetch uploaded files: HTTP ${response.code}, body=${body?.take(500)}")
                        return@withContext null
                    }

                    val responseBody = response.body?.string()
                    if (responseBody == null) {
                        Log.e(TAG, "Empty response body")
                        return@withContext null
                    }

                    parseUploadedFilesResponse(responseBody)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching uploaded files", e)
                return@withContext null
            }
        }
    }

    /**
     * 解析已上传文件列表响应
     */
    private fun parseUploadedFilesResponse(responseBody: String): UploadedFilesResponse? {
        return try {
            val root = JSONObject(responseBody)
            val responseStatus = root.optInt("status", -1)
            val code = root.optString("code", "-1")

            if (responseStatus != 1 || code != "0") {
                Log.e(TAG, "Server returned error: status=$responseStatus, code=$code, raw=${responseBody.take(500)}")
                return null
            }

            val data = root.optJSONObject("data")
            if (data == null) {
                Log.e(TAG, "Missing 'data' field in response")
                return null
            }

            val totalFiles = data.getInt("total_files")
            val filesArray = data.getJSONArray("files")
            val files = mutableListOf<UploadedFile>()

            for (i in 0 until filesArray.length()) {
                val fileObj = filesArray.getJSONObject(i)
                val file = UploadedFile(
                    fileId = fileObj.getString("file_id"),
                    filename = fileObj.getString("filename"),
                    totalSize = fileObj.getLong("total_size"),
                    checksum = fileObj.getString("checksum"),
                    status = fileObj.getInt("status"),
                    filePath = fileObj.getString("file_path"),
                    lastUpdated = fileObj.getLong("last_updated")
                )
                files.add(file)
            }

            UploadedFilesResponse(totalFiles = totalFiles, files = files)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing uploaded files response", e)
            null
        }
    }

    /**
     * 格式化文件大小
     */
    fun formatFileSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.2f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format("%.2f MB", mb)
        val gb = mb / 1024.0
        return String.format("%.2f GB", gb)
    }

    /**
     * 获取文件状态文本
     */
    fun getStatusText(status: Int): String {
        return when (status) {
            0 -> "上传中"
            1 -> "处理中"
            2 -> "已完成"
            else -> "未知"
        }
    }

    /**
     * 格式化时间戳
     */
    fun formatTimestamp(timestamp: Long): String {
        if (timestamp <= 0) return "未知时间"
        val date = java.util.Date(timestamp * 1000L)
        val format = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        return format.format(date)
    }
}
