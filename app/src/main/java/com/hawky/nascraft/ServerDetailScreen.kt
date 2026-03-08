package com.hawky.nascraft

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 页面导航选项
 */
enum class PageNav {
    UPLOAD,
    FILES
}

/**
 * 服务端详情页面
 * 包含上传和已上传文件列表两个标签页
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerDetailScreen(
    server: DiscoveredServer,
    albumUploadManager: AlbumUploadManager,
    fileUploadManager: FileUploadManager,
    uploadState: UploadState?,
    onBackClick: () -> Unit,
    onStartUpload: (DiscoveredServer) -> Unit,
    onStopUpload: () -> Unit
) {
    var currentPage by rememberSaveable { mutableStateOf(PageNav.UPLOAD) }
    var selectedTab by remember { mutableIntStateOf(0) }

    // 上传完成时切换到文件列表
    LaunchedEffect(uploadState?.status) {
        if (uploadState?.status is UploadStatus.Completed && currentPage == PageNav.UPLOAD) {
            // 可选：上传完成后自动切换到文件列表
            // currentPage = PageNav.FILES
            // selectedTab = 1
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = server.name,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${server.ip.hostAddress}:${server.port}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Tab 导航
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = {
                        selectedTab = 0
                        currentPage = PageNav.UPLOAD
                    },
                    text = { Text("自动上传") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = {
                        selectedTab = 1
                        currentPage = PageNav.FILES
                    },
                    text = { Text("已上传文件") }
                )
            }

            // 内容区域
            Box(modifier = Modifier.fillMaxSize()) {
                when (currentPage) {
                    PageNav.UPLOAD -> {
                        UploadContent(
                            albumUploadManager = albumUploadManager,
                            uploadState = uploadState,
                            server = server,
                            onStartUpload = onStartUpload,
                            onStopUpload = onStopUpload
                        )
                    }
                    PageNav.FILES -> {
                        UploadedFilesScreen(
                            fileUploadManager = fileUploadManager,
                            server = server,
                            onBackClick = { /* 已在顶部导航栏处理返回 */ }
                        )
                    }
                }
            }
        }
    }
}

/**
 * 上传内容区域
 */
@Composable
fun UploadContent(
    albumUploadManager: AlbumUploadManager,
    uploadState: UploadState?,
    server: DiscoveredServer,
    onStartUpload: (DiscoveredServer) -> Unit,
    onStopUpload: () -> Unit
) {
    val isUploading = albumUploadManager.isUploading()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // 服务器信息卡片
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    text = "服务器信息",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                ServerInfoRow("名称", server.name)
                ServerInfoRow("IP地址", server.ip.hostAddress ?: "")
                ServerInfoRow("端口", server.port.toString())
                ServerInfoRow("协议", server.proto)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 上传进度卡片
        uploadState?.let { state ->
            UploadProgressCard(state)
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 操作按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = {
                    if (isUploading) {
                        onStopUpload()
                    } else {
                        onStartUpload(server)
                    }
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isUploading)
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.primary
                )
            ) {
                if (isUploading) {
                    Text("停止上传")
                } else {
                    Text("开始上传")
                }
            }

            if (isUploading) {
                Button(
                    onClick = {
                        // 可以添加暂停功能
                    },
                    modifier = Modifier.weight(1f),
                    enabled = false
                ) {
                    Text("暂停")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 功能说明
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    text = "功能说明",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "• 自动上传手机相册中的所有照片\n• 支持断点续传\n• 支持文件去重（相同MD5不重复上传）\n• 实时显示上传进度",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 上传提示
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (isUploading)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.tertiaryContainer
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (isUploading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                    }
                    Text(
                        text = if (isUploading) "上传中，请勿关闭应用" else "点击开始上传按钮开始",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

/**
 * 服务器信息行
 */
@Composable
fun ServerInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * 上传进度卡片
 */
@Composable
fun UploadProgressCard(state: UploadState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "上传进度",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))

            if (state.fileName.isNotEmpty()) {
                Text(
                    text = "当前文件",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = state.fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            // 显示文件计数
            if (state.totalFiles != null && state.currentFileIndex != null) {
                Text(
                    text = "文件 ${state.currentFileIndex + 1}/${state.totalFiles}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            LinearProgressIndicator(
                progress = { state.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp),
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${(state.progress * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = when (state.status) {
                        is UploadStatus.Uploading -> "上传中"
                        is UploadStatus.Completed -> "已完成"
                        is UploadStatus.Failed -> "上传失败: ${(state.status as UploadStatus.Failed).error}"
                        else -> "等待中"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = when (state.status) {
                        is UploadStatus.Completed -> MaterialTheme.colorScheme.primary
                        is UploadStatus.Failed -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                )
            }
        }
    }
}
