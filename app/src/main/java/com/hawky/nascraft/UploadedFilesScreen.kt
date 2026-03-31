package com.hawky.nascraft

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Description
import coil.compose.AsyncImage
import com.google.accompanist.pager.ExperimentalPagerApi
import com.google.accompanist.pager.HorizontalPager
import com.google.accompanist.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.widget.Toast
import kotlinx.coroutines.launch

/**
 * 已上传文件列表页面 - 作为 Tab 内容使用，不包含独立 header
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalPagerApi::class)
@Composable
fun UploadedFilesScreen(
    fileUploadManager: FileUploadManager,
    dlnaManager: DlnaManager,
    server: DiscoveredServer
) {
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val baseUrl = "${server.proto}://${server.ip.hostAddress}:${server.port}"

    var isLoading by remember { mutableStateOf(true) }
    var uploadedFiles by remember { mutableStateOf<List<UploadedFile>>(emptyList()) }
    var totalFiles by remember { mutableIntStateOf(0) }
    var currentPage by remember { mutableIntStateOf(1) }
    var hasMore by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Image preview state - for swipeable gallery
    var showImagePreview by remember { mutableStateOf(false) }
    var initialPreviewIndex by remember { mutableIntStateOf(0) }
    val previewableFiles = remember(uploadedFiles) {
        uploadedFiles.filter { !it.thumbnailUrl.isNullOrEmpty() }
    }

    // DLNA cast state
    var showCastDeviceSelection by remember { mutableStateOf(false) }
    var selectedFileForCast by remember { mutableStateOf<UploadedFile?>(null) }
    var castDevices by remember { mutableStateOf<List<Pair<DlnaRenderer, PlaybackInfo>>>(emptyList()) }
    var castLoading by remember { mutableStateOf(false) }

    // 加载数据
    suspend fun loadFiles(refresh: Boolean = false) {
        if (refresh) {
            currentPage = 1
            hasMore = true
        }

        if (!hasMore) return

        isLoading = true
        errorMessage = null

        val baseUrl = "${server.proto}://${server.ip.hostAddress}:${server.port}"
        val response = fileUploadManager.getUploadedFiles(baseUrl, page = currentPage, pageSize = 20)

        if (response != null) {
            if (refresh) {
                uploadedFiles = response.files
            } else {
                uploadedFiles = uploadedFiles + response.files
            }
            totalFiles = response.totalFiles
            hasMore = uploadedFiles.size < totalFiles
            currentPage++
        } else {
            errorMessage = "加载失败，请重试"
        }

        isLoading = false
    }

    // 自动检测滚动到底部
    val shouldLoadMore by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull()
            lastVisibleItem != null && lastVisibleItem.index == layoutInfo.totalItemsCount - 1
        }
    }

    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore && !isLoading && hasMore) {
            loadFiles(refresh = false)
        }
    }

    // 初始加载
    LaunchedEffect(Unit) {
        loadFiles(refresh = true)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
    ) {
        // 统计信息
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
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
                Row {
                    Column {
                        Text(
                            text = "总文件数",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                        )
                        Text(
                            text = "$totalFiles",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                    Spacer(modifier = Modifier.width(32.dp))
                    Column {
                        Text(
                            text = "当前显示",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                        )
                        Text(
                            text = "${uploadedFiles.size}",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
                IconButton(
                    onClick = {
                        coroutineScope.launch {
                            loadFiles(refresh = true)
                        }
                    },
                    enabled = !isLoading
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "刷新")
                }
            }
        }

        // 文件列表
        if (errorMessage != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
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
                    Button(onClick = {
                        coroutineScope.launch {
                            loadFiles(refresh = true)
                        }
                    }) {
                        Text("重试")
                    }
                }
            }
        } else if (uploadedFiles.isEmpty() && !isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "暂无已上传文件",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 16.dp,
                    vertical = 16.dp
                )
            ) {
                items(uploadedFiles, key = { it.fileId }) { file ->
                    FileCard(
                        file = file,
                        fileUploadManager = fileUploadManager,
                        baseUrl = baseUrl,
                        onPreviewClick = {
                            // Find index in previewable list and open preview
                            val index = previewableFiles.indexOfFirst { it.fileId == file.fileId }
                            if (index >= 0) {
                                initialPreviewIndex = index
                                showImagePreview = true
                            }
                        },
                        onCastClick = { selectedFile ->
                            coroutineScope.launch {
                                castLoading = true
                                val devices = dlnaManager.listRenderers(baseUrl)
                                if (devices != null) {
                                    castDevices = devices
                                    selectedFileForCast = selectedFile
                                    showCastDeviceSelection = true
                                } else {
                                    Toast.makeText(context, "获取设备列表失败", Toast.LENGTH_SHORT).show()
                                }
                                castLoading = false
                            }
                        }
                    )
                }

                // 加载更多指示器
                if (isLoading && uploadedFiles.isNotEmpty()) {
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
                                Text("加载更多...")
                            }
                        }
                    }
                }
            }
        }

        // Swipeable image preview bottom sheet
        if (showImagePreview && previewableFiles.isNotEmpty()) {
            ModalBottomSheet(
                onDismissRequest = { showImagePreview = false },
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 600.dp)
                        .background(MaterialTheme.colorScheme.surface),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val pagerState = rememberPagerState(initialPage = initialPreviewIndex)

                    HorizontalPager(
                        state = pagerState,
                        count = previewableFiles.size,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(550.dp)
                            .padding(vertical = 16.dp)
                    ) { page ->
                        val file = previewableFiles[page]
                        val fullImageUrl = "$baseUrl/api/download/${file.fileId}"
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            AsyncImage(
                                model = fullImageUrl,
                                contentDescription = file.filename,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .align(Alignment.Center),
                                alignment = Alignment.Center
                            )
                        }
                    }

                    // Page indicator at bottom
                    if (previewableFiles.size > 1) {
                        Spacer(modifier = Modifier.height(8.dp))
                        com.google.accompanist.pager.HorizontalPagerIndicator(
                            pagerState = pagerState,
                            pageCount = previewableFiles.size,
                            modifier = Modifier
                                .padding(bottom = 16.dp),
                            activeColor = MaterialTheme.colorScheme.primary,
                            inactiveColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    }
                }
            }

            // DLNA 设备选择底部弹窗
            if (showCastDeviceSelection && selectedFileForCast != null) {
                ModalBottomSheet(
                    onDismissRequest = { showCastDeviceSelection = false },
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            .heightIn(max = 500.dp)
                    ) {
                        Text(
                            text = "选择投屏设备 - ${selectedFileForCast!!.filename}",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )

                        if (castLoading) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        } else if (castDevices.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "未发现DLNA设备\n请确认电视已开启且在同一局域网",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        } else {
                            LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(castDevices) { (renderer, playback) ->
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                coroutineScope.launch {
                                                    val success = dlnaManager.playOnRenderer(
                                                        baseUrl,
                                                        renderer.uuid,
                                                        selectedFileForCast!!.fileId
                                                    )
                                                    if (success) {
                                                        Toast.makeText(
                                                            context,
                                                            "投屏成功！已在 \"${renderer.name}\" 开始播放",
                                                            Toast.LENGTH_LONG
                                                        ).show()
                                                        showCastDeviceSelection = false
                                                    } else {
                                                        Toast.makeText(
                                                            context,
                                                            "投屏失败，请重试",
                                                            Toast.LENGTH_SHORT
                                                        ).show()
                                                    }
                                                }
                                            },
                                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(16.dp)
                                        ) {
                                            Text(
                                                text = renderer.name,
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = "${renderer.ipAddr}:${renderer.port} - ${formatState(playback.state)}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 格式化播放状态
 */
fun formatState(state: PlaybackState): String {
    return when (state) {
        PlaybackState.Unknown -> "未知"
        PlaybackState.Stopped -> "已停止"
        PlaybackState.Playing -> "播放中"
        PlaybackState.Paused -> "已暂停"
        PlaybackState.Transiting -> "加载中"
    }
}

/**
 * 文件卡片
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileCard(
    file: UploadedFile,
    fileUploadManager: FileUploadManager,
    baseUrl: String,
    onPreviewClick: () -> Unit,
    onCastClick: (UploadedFile) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            // 第一行：缩略图 + 文件信息
            Row(
                modifier = Modifier
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Thumbnail preview
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(4.dp)
                        )
                        .then(
                            if (file.thumbnailUrl != null) {
                                Modifier.clickable { onPreviewClick() }
                            } else {
                                Modifier
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    val thumbnailFullUrl = if (file.thumbnailUrl != null) {
                        "$baseUrl${file.thumbnailUrl}"
                    } else {
                        null
                    }

                    if (!thumbnailFullUrl.isNullOrEmpty()) {
                        AsyncImage(
                            model = thumbnailFullUrl,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            alignment = Alignment.Center
                        )
                    } else {
                        // Show generic icon when no thumbnail
                        val isImage = file.filename.lowercase().let {
                            it.endsWith(".jpg") || it.endsWith(".jpeg") ||
                                    it.endsWith(".png") || it.endsWith(".gif") ||
                                    it.endsWith(".webp") || it.endsWith(".bmp")
                        }
                        if (isImage) {
                            Icon(
                                Icons.Default.Image,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Icon(
                                Icons.Default.Description,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    // 文件名和状态
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = file.filename,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = fileUploadManager.formatFileSize(file.totalSize),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // 状态标签
                        StatusChip(
                            status = file.status,
                            statusText = fileUploadManager.getStatusText(file.status)
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // 文件信息
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "MD5: ${file.checksum.take(8)}...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Text(
                            text = fileUploadManager.formatTimestamp(file.lastUpdated),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 第二行：投屏按钮
            if (file.status == 2) { // 只在上传完成后显示投屏按钮
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { onCastClick(file) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                ) {
                    Text("投屏到电视", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

/**
 * 状态标签
 */
@Composable
fun StatusChip(
    status: Int,
    statusText: String
) {
    val (backgroundColor, contentColor) = when (status) {
        0 -> Color(0xFFFFF9C4) to Color(0xFFF57F17) // 上传中 - 黄色
        1 -> Color(0xFFE1F5FE) to Color(0xFF0277BD) // 处理中 - 蓝色
        2 -> Color(0xFFE8F5E9) to Color(0xFF2E7D32) // 已完成 - 绿色
        else -> Color(0xFFECEFF1) to Color(0xFF546E7A) // 未知 - 灰色
    }

    Box(
        modifier = Modifier
            .background(
                color = backgroundColor,
                shape = RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = statusText,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            fontWeight = FontWeight.Medium
        )
    }
}
