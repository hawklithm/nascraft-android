package com.hawky.nascraft

import android.util.Log
import org.json.JSONObject

/**
 * DLNA 管理器
 * 封装所有 DLNA 相关的 API 调用
 */
class DlnaManager(private val nativeBusiness: NativeBusinessClient? = null) {
    companion object {
        private const val TAG = "DlnaManager"
    }

    /**
     * 获取已发现的 DLNA 设备列表
     */
    suspend fun listRenderers(baseUrl: String): List<Pair<DlnaRenderer, PlaybackInfo>>? {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val response = nativeBusiness?.jsonSelected("GET", "/api/dlna/renderers") ?: return@withContext null
                parseDeviceListResponse(response.body.toString(Charsets.UTF_8))
            } catch (e: Exception) {
                Log.w(TAG, "Secure DLNA request did not complete")
                null
            }
        }
    }

    /**
     * 解析设备列表响应
     */
    private fun parseDeviceListResponse(responseBody: String): List<Pair<DlnaRenderer, PlaybackInfo>>? {
        return try {
            val root = JSONObject(responseBody)
            val responseStatus = root.optInt("status", -1)
            val code = root.optString("code", "-1")

            if (responseStatus != 1 || code != "0") {
                Log.w(TAG, "DLNA API returned a non-success response")
                return null
            }

            val data = root.optJSONObject("data")
            if (data == null) {
                Log.e(TAG, "Missing 'data' field in response")
                return null
            }

            val devicesJsonArray = data.getJSONArray("devices")
            val devices = mutableListOf<Pair<DlnaRenderer, PlaybackInfo>>()

            for (i in 0 until devicesJsonArray.length()) {
                // 单个设备解析失败只跳过该设备，不影响整体列表
                val parsed = try {
                    parseDeviceEntry(devicesJsonArray.getJSONArray(i))
                } catch (e: Exception) {
                    Log.e(TAG, "Skip malformed device entry #$i", e)
                    null
                } ?: continue
                devices.add(parsed)
            }

            devices
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing device list response", e)
            null
        }
    }

    /**
     * 解析单个设备条目 [renderer, playback]
     */
    private fun parseDeviceEntry(entry: org.json.JSONArray): Pair<DlnaRenderer, PlaybackInfo> {
        val rendererJson = entry.getJSONObject(0)
        val playbackJson = entry.getJSONObject(1)

        val renderer = DlnaRenderer(
            uuid = rendererJson.getString("uuid"),
            name = rendererJson.getString("name"),
            manufacturer = if (rendererJson.isNull("manufacturer")) null else rendererJson.optString("manufacturer"),
            modelName = if (rendererJson.isNull("model_name")) null else rendererJson.optString("model_name"),
            location = rendererJson.getString("location"),
            ipAddr = rendererJson.getString("ip_addr"),
            port = rendererJson.getInt("port")
        )

        val playback = PlaybackInfo(
            state = PlaybackState.fromString(playbackJson.optString("state", "Unknown")),
            currentUri = if (playbackJson.isNull("current_uri")) null else playbackJson.optString("current_uri"),
            currentMetadata = if (playbackJson.isNull("current_metadata")) null else playbackJson.optString("current_metadata"),
            volume = playbackJson.optInt("volume", 0),
            muted = playbackJson.optBoolean("muted", false),
            duration = if (playbackJson.isNull("duration")) null else playbackJson.optString("duration"),
            position = if (playbackJson.isNull("position")) null else playbackJson.optString("position")
        )

        return Pair(renderer, playback)
    }

    /**
     * 在指定渲染器上播放文件
     */
    suspend fun playOnRenderer(baseUrl: String, uuid: String, fileId: String): Boolean {
        return sendControlRequest(baseUrl, "/api/dlna/renderer/play", JSONObject().apply {
            put("uuid", uuid)
            put("file_id", fileId)
        })
    }

    /**
     * 暂停播放
     */
    suspend fun pause(baseUrl: String, uuid: String): Boolean {
        return sendControlRequest(baseUrl, "/api/dlna/renderer/pause", JSONObject().apply {
            put("uuid", uuid)
        })
    }

    /**
     * 继续/恢复播放
     */
    suspend fun resume(baseUrl: String, uuid: String): Boolean {
        return sendControlRequest(baseUrl, "/api/dlna/renderer/resume", JSONObject().apply {
            put("uuid", uuid)
        })
    }

    /**
     * 停止播放
     */
    suspend fun stop(baseUrl: String, uuid: String): Boolean {
        return sendControlRequest(baseUrl, "/api/dlna/renderer/stop", JSONObject().apply {
            put("uuid", uuid)
        })
    }

    /**
     * 跳转到指定位置（秒）
     */
    suspend fun seek(baseUrl: String, uuid: String, position: Int): Boolean {
        return sendControlRequest(baseUrl, "/api/dlna/renderer/seek", JSONObject().apply {
            put("uuid", uuid)
            put("position", position)
        })
    }

    /**
     * 设置音量 (0-100)
     */
    suspend fun setVolume(baseUrl: String, uuid: String, volume: Int): Boolean {
        return sendControlRequest(baseUrl, "/api/dlna/renderer/volume", JSONObject().apply {
            put("uuid", uuid)
            put("volume", volume)
        })
    }

    /**
     * 设置静音
     */
    suspend fun setMute(baseUrl: String, uuid: String, mute: Boolean): Boolean {
        return sendControlRequest(baseUrl, "/api/dlna/renderer/mute", JSONObject().apply {
            put("uuid", uuid)
            put("mute", mute)
        })
    }

    /**
     * 发送控制请求通用方法
     */
    private suspend fun sendControlRequest(baseUrl: String, path: String, body: JSONObject): Boolean {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val response = nativeBusiness?.jsonSelected("POST", path, body.toString()) ?: return@withContext false
                val envelope = JSONObject(response.body.toString(Charsets.UTF_8))
                envelope.optInt("status") == 1 && envelope.optString("code") == "0"
            } catch (e: Exception) {
                Log.w(TAG, "Secure DLNA control request did not complete")
                return@withContext false
            }
        }
    }
}
