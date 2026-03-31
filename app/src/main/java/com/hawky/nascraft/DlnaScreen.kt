package com.hawky.nascraft

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color

/**
 * DLNA 投屏设备列表和控制屏幕
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DlnaScreen(
    dlnaManager: DlnaManager,
    server: DiscoveredServer
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val baseUrl = "${server.proto}://${server.ip.hostAddress}:${server.port}"

    var isLoading by remember { mutableStateOf(true) }
    var renderers by remember { mutableStateOf<List<Pair<DlnaRenderer, PlaybackInfo>>>(emptyList()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var expandedRendererUuid by remember { mutableStateOf<String?>(null) }

    var tempVolume by remember { mutableStateOf<Map<String, Float>>(emptyMap()) }

    fun loadDevices() {
        coroutineScope.launch {
            isLoading = true
            errorMessage = null
            val devices = dlnaManager.listRenderers(baseUrl)
            if (devices != null) {
                renderers = devices
                // 初始化音量滑块
                tempVolume = devices.associate { (renderer, playback) ->
                    renderer.uuid to playback.volume.toFloat()
                }
            } else {
                errorMessage = "加载设备列表失败"
            }
            isLoading = false
        }
    }

    // 初始加载
    LaunchedEffect(Unit) {
        loadDevices()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // 顶部统计和刷新按钮
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "DLNA投屏设备",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                    )
                    Text(
                        text = "已发现 ${renderers.size} 个设备",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
                IconButton(
                    onClick = { loadDevices() },
                    enabled = !isLoading
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "刷新")
                }
            }
        }

        // 错误提示
        if (errorMessage != null && !isLoading && renderers.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = errorMessage ?: "",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error
                    )
                    Button(onClick = { loadDevices() }) {
                        Text("重试")
                    }
                }
            }
        } else if (renderers.isEmpty() && !isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "未发现DLNA设备",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "请确认电视已开启，且与手机在同一局域网",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                    Button(onClick = { loadDevices() }) {
                        Text("刷新")
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(renderers) { (renderer, playback) ->
                    val isExpanded = expandedRendererUuid == renderer.uuid
                    val currentVolume = tempVolume[renderer.uuid] ?: playback.volume.toFloat()

                    DlnaDeviceCard(
                        renderer = renderer,
                        playback = playback,
                        isExpanded = isExpanded,
                        currentVolume = currentVolume,
                        onToggleExpand = {
                            expandedRendererUuid = if (isExpanded) null else renderer.uuid
                        },
                        onVolumeChange = { volume ->
                            tempVolume = tempVolume.toMutableMap().apply {
                                put(renderer.uuid, volume)
                            }
                        },
                        onPlay = {
                            coroutineScope.launch {
                                val success = dlnaManager.resume(baseUrl, renderer.uuid)
                                if (success) {
                                    Toast.makeText(context, "继续播放", Toast.LENGTH_SHORT).show()
                                    loadDevices()
                                } else {
                                    Toast.makeText(context, "操作失败", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        onPause = {
                            coroutineScope.launch {
                                val success = dlnaManager.pause(baseUrl, renderer.uuid)
                                if (success) {
                                    Toast.makeText(context, "已暂停", Toast.LENGTH_SHORT).show()
                                    loadDevices()
                                } else {
                                    Toast.makeText(context, "操作失败", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        onStop = {
                            coroutineScope.launch {
                                val success = dlnaManager.stop(baseUrl, renderer.uuid)
                                if (success) {
                                    Toast.makeText(context, "已停止", Toast.LENGTH_SHORT).show()
                                    loadDevices()
                                } else {
                                    Toast.makeText(context, "操作失败", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        onVolumeConfirmed = {
                            coroutineScope.launch {
                                val volume = currentVolume.toInt()
                                val success = dlnaManager.setVolume(baseUrl, renderer.uuid, volume)
                                if (success) {
                                    Toast.makeText(context, "音量已设置为 $volume%", Toast.LENGTH_SHORT).show()
                                    loadDevices()
                                } else {
                                    Toast.makeText(context, "设置音量失败", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        onMuteChanged = { muted ->
                            coroutineScope.launch {
                                val success = dlnaManager.setMute(baseUrl, renderer.uuid, muted)
                                if (success) {
                                    Toast.makeText(context, if (muted) "已静音" else "已取消静音", Toast.LENGTH_SHORT).show()
                                    loadDevices()
                                } else {
                                    Toast.makeText(context, "设置静音失败", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    )
                }

                if (isLoading && renderers.isNotEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                                Text("加载中...")
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 获取状态对应的显示颜色
 */
@Composable
fun getStateColor(state: PlaybackState): Color {
    return when (state) {
        PlaybackState.Playing -> MaterialTheme.colorScheme.primary
        PlaybackState.Paused -> MaterialTheme.colorScheme.secondary
        PlaybackState.Stopped -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.error
    }
}

/**
 * 单个 DLNA 设备卡片
 */
@Composable
fun DlnaDeviceCard(
    renderer: DlnaRenderer,
    playback: PlaybackInfo,
    isExpanded: Boolean,
    currentVolume: Float,
    onToggleExpand: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onVolumeConfirmed: () -> Unit,
    onMuteChanged: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggleExpand() },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = if (isExpanded) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // 头部信息
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp)
                ) {
                    Text(
                        text = renderer.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        modifier = Modifier.basicMarquee()
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${renderer.ipAddr}:${renderer.port}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "状态: ",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = formatState(playback.state),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = getStateColor(playback.state)
                        )
                    }
                    if (!renderer.manufacturer.isNullOrEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${renderer.manufacturer} ${renderer.modelName ?: ""}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (playback.currentUri != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        val fileName = playback.currentUri.split("/").last()
                        Text(
                            text = "当前: $fileName",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            modifier = Modifier.basicMarquee()
                        )
                    }
                }
            }

            // 如果展开，显示控制面板
            if (isExpanded) {
                Spacer(modifier = Modifier.height(16.dp))

                // 播放控制按钮
                Text(
                    text = "播放控制",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 16.dp)
                ) {
                    Button(
                        onClick = onPlay,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("播放")
                    }
                    Button(
                        onClick = onPause,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Pause, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("暂停")
                    }
                    Button(
                        onClick = onStop,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("停止")
                    }
                }

                // 音量控制
                Text(
                    text = "音量: ${currentVolume.toInt()}%${if (playback.muted) " (静音)" else ""}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Slider(
                    value = currentVolume,
                    onValueChange = onVolumeChange,
                    valueRange = 0f..100f,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                )
                Button(
                    onClick = onVolumeConfirmed,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                ) {
                    Text("应用音量")
                }

                // 静音开关
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "静音",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    Checkbox(
                        checked = playback.muted,
                        onCheckedChange = onMuteChanged
                    )
                }
            }
        }
    }
}
