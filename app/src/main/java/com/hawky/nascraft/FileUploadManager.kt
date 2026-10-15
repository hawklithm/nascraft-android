package com.hawky.nascraft

import android.util.Log
import org.json.JSONObject

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
    val lastUpdated: Long,
    val thumbnailUrl: String?,
    val takenAt: Long = 0,
    val sourceDevice: String? = null
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
    val totalSize: Long,
    val files: List<UploadedFile>
)

/**
 * 文件上传管理器
 * 负责获取已上传文件列表、上传文件等操作
 */
class FileUploadManager(
    private val context: android.content.Context,
    private val nativeBusiness: NativeBusinessClient? = null,
) {
    companion object {
        private const val TAG = "FileUploadManager"
        private const val DEFAULT_PAGE_SIZE = 20
    }

    /**
     * 获取已上传文件列表
     * @param baseUrl 当前配置的固定 HTTPS API Origin（业务请求本身由 NativeBusinessClient 签名发送）
     * @param page 页码，从1开始
     * @param pageSize 每页数量，默认20
     * @param status 文件状态过滤，0=上传中, 1=处理中, 2=已完成，null表示不过滤
     * @param sortBy 排序字段，id=上传时间, taken_at=拍摄时间
     * @param order 排序方向，asc/desc
     * @param sourceDevice 来源设备过滤，null表示不过滤
     * @param mediaType 媒体类型过滤，image/video/other，null表示不过滤
     * @param filename 文件名关键词模糊搜索，null或空白表示不过滤
     * @return UploadedFilesResponse 文件列表响应
     */
    suspend fun getUploadedFiles(
        baseUrl: String,
        page: Int = 1,
        pageSize: Int = DEFAULT_PAGE_SIZE,
        status: Int? = null,
        sortBy: String? = null,
        order: String? = null,
        sourceDevice: String? = null,
        mediaType: String? = null,
        filename: String? = null
    ): UploadedFilesResponse? {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val path = StringBuilder("/api/uploaded_files?page=$page&page_size=$pageSize")
                status?.let {
                    path.append("&status=$it")
                }
                sortBy?.let {
                    path.append("&sort_by=").append(queryEncode(it))
                }
                order?.let {
                    path.append("&order=").append(queryEncode(it))
                }
                sourceDevice?.let {
                    path.append("&source_device=").append(queryEncode(it))
                }
                mediaType?.let {
                    path.append("&media_type=").append(queryEncode(it))
                }
                filename?.takeIf { it.isNotBlank() }?.let {
                    path.append("&filename=").append(queryEncode(it.trim()))
                }
                val response = nativeBusiness?.jsonSelected("GET", path.toString()) ?: return@withContext null
                parseUploadedFilesResponse(response.body.toString(Charsets.UTF_8))
            } catch (e: Exception) {
                Log.w(TAG, "Secure file-list request did not complete")
                return@withContext null
            }
        }
    }

    /**
     * 获取所有来源设备列表（用于来源筛选器）
     */
    suspend fun getSources(baseUrl: String): List<String>? {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val response = nativeBusiness?.jsonSelected("GET", "/api/uploaded_files/sources") ?: return@withContext null
                val body = response.body.toString(Charsets.UTF_8)
                    val root = JSONObject(body)
                    if (root.optInt("status", -1) != 1 || root.optString("code", "-1") != "0") {
                        return@withContext null
                    }
                    val data = root.optJSONObject("data") ?: return@withContext emptyList()
                    val arr = data.optJSONArray("sources") ?: return@withContext emptyList()
                    val list = mutableListOf<String>()
                    for (i in 0 until arr.length()) {
                        list.add(arr.getString(i))
                    }
                    list
            } catch (e: Exception) {
                Log.w(TAG, "Secure source-list request did not complete")
                null
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
                Log.w(TAG, "File-list API returned a non-success response")
                return null
            }

            val data = root.optJSONObject("data")
            if (data == null) {
                Log.w(TAG, "File-list API returned an incomplete response")
                return null
            }

            val totalFiles = data.getInt("total_files")
            val totalSize = data.optLong("total_size", 0L)
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
                    lastUpdated = fileObj.getLong("last_updated"),
                    thumbnailUrl = if (fileObj.has("thumbnail_url") && !fileObj.isNull("thumbnail_url")) {
                        fileObj.getString("thumbnail_url")
                    } else {
                        null
                    },
                    takenAt = fileObj.optLong("taken_at", 0L),
                    sourceDevice = if (fileObj.has("source_device") && !fileObj.isNull("source_device")) {
                        fileObj.getString("source_device")
                    } else {
                        null
                    }
                )
                files.add(file)
            }

            UploadedFilesResponse(totalFiles = totalFiles, totalSize = totalSize, files = files)
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

    private fun queryEncode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
