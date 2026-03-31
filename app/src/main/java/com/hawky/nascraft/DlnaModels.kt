package com.hawky.nascraft

/**
 * DLNA 服务信息
 */
data class ServiceInfo(
    val serviceId: String,
    val controlUrl: String,
    val eventSubUrl: String
)

/**
 * DLNA 媒体渲染器设备
 */
data class DlnaRenderer(
    val uuid: String,
    val name: String,
    val manufacturer: String?,
    val modelName: String?,
    val location: String,
    val ipAddr: String,
    val port: Int,
    val avTransportService: ServiceInfo?,
    val renderingControlService: ServiceInfo?
)

/**
 * 播放状态枚举
 */
enum class PlaybackState {
    Unknown, Stopped, Playing, Paused, Transiting
}

/**
 * 当前播放信息
 */
data class PlaybackInfo(
    val state: PlaybackState,
    val currentUri: String?,
    val currentMetadata: String?,
    val volume: Int,
    val muted: Boolean,
    val duration: String?,
    val position: String?
)

/**
 * 设备列表响应
 */
data class DeviceListResponse(
    val devices: List<List<Any>>
) {
    /**
     * 解析为成对的 (DlnaRenderer, PlaybackInfo)
     */
    fun parseDevices(): List<Pair<DlnaRenderer, PlaybackInfo>> {
        return devices.map { pair ->
            val rendererJson = pair[0] as org.json.JSONObject
            val playbackJson = pair[1] as org.json.JSONObject

            val renderer = DlnaRenderer(
                uuid = rendererJson.getString("uuid"),
                name = rendererJson.getString("name"),
                manufacturer = if (rendererJson.has("manufacturer")) rendererJson.optString("manufacturer") else null,
                modelName = if (rendererJson.has("model_name")) rendererJson.optString("model_name") else null,
                location = rendererJson.getString("location"),
                ipAddr = rendererJson.getString("ip_addr"),
                port = rendererJson.getInt("port"),
                avTransportService = if (rendererJson.has("av_transport_service") && !rendererJson.isNull("av_transport_service")) {
                    val svc = rendererJson.getJSONObject("av_transport_service")
                    ServiceInfo(
                        serviceId = svc.getString("service_id"),
                        controlUrl = svc.getString("control_url"),
                        eventSubUrl = svc.getString("event_sub_url")
                    )
                } else null,
                renderingControlService = if (rendererJson.has("rendering_control_service") && !rendererJson.isNull("rendering_control_service")) {
                    val svc = rendererJson.getJSONObject("rendering_control_service")
                    ServiceInfo(
                        serviceId = svc.getString("service_id"),
                        controlUrl = svc.getString("control_url"),
                        eventSubUrl = svc.getString("event_sub_url")
                    )
                } else null
            )

            val stateStr = playbackJson.getString("state")
            val state = try {
                PlaybackState.valueOf(stateStr)
            } catch (e: IllegalArgumentException) {
                PlaybackState.Unknown
            }

            val playback = PlaybackInfo(
                state = state,
                currentUri = if (playbackJson.has("current_uri") && !playbackJson.isNull("current_uri")) {
                    playbackJson.getString("current_uri")
                } else null,
                currentMetadata = if (playbackJson.has("current_metadata") && !playbackJson.isNull("current_metadata")) {
                    playbackJson.getString("current_metadata")
                } else null,
                volume = playbackJson.getInt("volume"),
                muted = playbackJson.getBoolean("muted"),
                duration = if (playbackJson.has("duration") && !playbackJson.isNull("duration")) {
                    playbackJson.getString("duration")
                } else null,
                position = if (playbackJson.has("position") && !playbackJson.isNull("position")) {
                    playbackJson.getString("position")
                } else null
            )

            Pair(renderer, playback)
        }
    }
}
