package com.hawky.nascraft

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * DLNA 管理器
 * 封装所有 DLNA 相关的 API 调用
 */
class DlnaManager {
    companion object {
        private const val TAG = "DlnaManager"
    }

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * 获取已发现的 DLNA 设备列表
     */
    suspend fun listRenderers(baseUrl: String): List<Pair<DlnaRenderer, PlaybackInfo>>? {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val url = "$baseUrl/api/dlna/renderers"
                Log.d(TAG, "Fetching DLNA renderers: $url")

                val request = Request.Builder()
                    .url(url)
                    .get()
                    .build()

                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val body = response.body?.string()
                        Log.e(TAG, "Failed to fetch renderers: HTTP ${response.code}, body=${body?.take(500)}")
                        return@use null
                    }

                    val responseBody = response.body?.string()
                    if (responseBody == null) {
                        Log.e(TAG, "Empty response body")
                        return@use null
                    }

                    parseDeviceListResponse(responseBody)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching DLNA renderers", e)
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
                Log.e(TAG, "Server returned error: status=$responseStatus, code=$code")
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
                val devicePair = devicesJsonArray.getJSONArray(i)
                val rendererJson = devicePair.getJSONObject(0)
                val playbackJson = devicePair.getJSONObject(1)

                val renderer = DlnaRenderer(
                    uuid = rendererJson.getString("uuid"),
                    name = rendererJson.getString("name"),
                    manufacturer = if (rendererJson.isNull("manufacturer")) null else rendererJson.optString("manufacturer"),
                    modelName = if (rendererJson.isNull("model_name")) null else rendererJson.optString("model_name"),
                    location = rendererJson.getString("location"),
                    ipAddr = rendererJson.getString("ip_addr"),
                    port = rendererJson.getInt("port"),
                    avTransportService = if (rendererJson.isNull("av_transport_service")) null else {
                        val s = rendererJson.getJSONObject("av_transport_service")
                        ServiceInfo(
                            serviceId = s.getString("service_id"),
                            controlUrl = s.getString("control_url"),
                            eventSubUrl = s.getString("event_sub_url")
                        )
                    },
                    renderingControlService = if (rendererJson.isNull("rendering_control_service")) null else {
                        val s = rendererJson.getJSONObject("rendering_control_service")
                        ServiceInfo(
                            serviceId = s.getString("service_id"),
                            controlUrl = s.getString("control_url"),
                            eventSubUrl = s.getString("event_sub_url")
                        )
                    }
                )

                val playback = PlaybackInfo(
                    state = PlaybackState.valueOf(playbackJson.getString("state")),
                    currentUri = if (playbackJson.isNull("current_uri")) null else playbackJson.optString("current_uri"),
                    currentMetadata = if (playbackJson.isNull("current_metadata")) null else playbackJson.optString("current_metadata"),
                    volume = playbackJson.getInt("volume"),
                    muted = playbackJson.getBoolean("muted"),
                    duration = if (playbackJson.isNull("duration")) null else playbackJson.optString("duration"),
                    position = if (playbackJson.isNull("position")) null else playbackJson.optString("position")
                )

                devices.add(Pair(renderer, playback))
            }

            devices
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing device list response", e)
            null
        }
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
                val url = "$baseUrl$path"
                Log.d(TAG, "Sending control request: $url, body=$body")

                val requestBody = body.toString().toRequestBody(
                    "application/json; charset=utf-8".toMediaType()
                )

                val request = Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .build()

                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val bodyText = response.body?.string()
                        Log.e(TAG, "Control request failed: HTTP ${response.code}, body=$bodyText")
                        return@use false
                    }

                    Log.d(TAG, "Control request succeeded")
                    return@use true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending control request", e)
                return@withContext false
            }
        }
    }
}
