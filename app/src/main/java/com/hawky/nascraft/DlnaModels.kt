package com.hawky.nascraft

/**
 * DLNA 媒体渲染器设备（/api/dlna/renderers 返回的 JSON 快照）
 */
data class DlnaRenderer(
    val uuid: String,
    val name: String,
    val manufacturer: String?,
    val modelName: String?,
    val location: String,
    val ipAddr: String,
    val port: Int
)

/**
 * 播放状态枚举（与后端 PlaybackState 对应；后端新增状态时兜底为 Unknown）
 */
enum class PlaybackState {
    Unknown, Stopped, Playing, Paused, Transiting;

    companion object {
        fun fromString(value: String?): PlaybackState =
            entries.firstOrNull { it.name == value } ?: Unknown
    }
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
