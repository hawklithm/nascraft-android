package com.hawky.nascraft

import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.hawky.nascraft.ui.theme.NascraftandroidTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UploadState(
    val fileName: String,
    val progress: Float, // 0.0 to 1.0
    val status: UploadStatus,
    val totalFiles: Int? = null,
    val currentFileIndex: Int? = null
)

class MainActivity : ComponentActivity() {
    private lateinit var discoveryManager: DiscoveryManager
    private lateinit var albumUploadManager: AlbumUploadManager
    private lateinit var fileUploadManager: FileUploadManager

    private var uploadState by mutableStateOf<UploadState?>(null)
    private var currentScreen by mutableStateOf<Screen>(Screen.DISCOVERY)
    private var connectedServer by mutableStateOf<DiscoveredServer?>(null)

    private var pendingUploadServer: DiscoveredServer? = null
    private val PERMISSION_REQUEST_CODE = 1001

    enum class Screen {
        DISCOVERY,
        SERVER_DETAIL
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        discoveryManager = DiscoveryManager(this)
        albumUploadManager = AlbumUploadManager(this)
        fileUploadManager = FileUploadManager(this)

        // 不再在启动时自动开始服务发现，等待用户点击按钮

        setContent {
            NascraftandroidTheme {
                MainScreen(
                    currentScreen = currentScreen,
                    connectedServer = connectedServer,
                    uploadState = uploadState,
                    discoveryManager = discoveryManager,
                    albumUploadManager = albumUploadManager,
                    fileUploadManager = fileUploadManager,
                    onConnectToServer = { server ->
                        onConnectToServer(server)
                    },
                    onStartUpload = { server ->
                        startAlbumUpload(server)
                    },
                    onStopUpload = {
                        albumUploadManager.stopAlbumUpload()
                        uploadState = null
                    },
                    onBackToDiscovery = {
                        currentScreen = Screen.DISCOVERY
                        connectedServer = null
                        albumUploadManager.stopAlbumUpload()
                    }
                )
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // 停止所有服务发现
        discoveryManager.stopDiscovery()
        discoveryManager.stopMDNSDiscovery()
    }

    private fun onConnectToServer(server: DiscoveredServer) {
        Log.d("MainActivity", "Connect clicked. Server: ${server.name}")

        // 保存连接的服务器并切换到详情页面
        connectedServer = server
        currentScreen = Screen.SERVER_DETAIL

        // 停止服务发现，避免与上传操作冲突
        discoveryManager.stopDiscovery()
    }

    private fun requestAlbumPermissions() {
        val permissions = albumUploadManager.getRequiredPermissions()
        
        // 检查哪些权限尚未授予
        val permissionsToRequest = permissions.filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissionsToRequest.isNotEmpty()) {
            Log.i("MainActivity", "Requesting permissions: ${permissionsToRequest.joinToString()}")
            requestPermissions(permissionsToRequest, PERMISSION_REQUEST_CODE)
        } else {
            // 所有权限都已授予
            pendingUploadServer?.let { server ->
                startAlbumUpload(server)
            }
            pendingUploadServer = null
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PERMISSION_REQUEST_CODE) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }

            if (allGranted) {
                Log.i("MainActivity", "All permissions granted")
                // 权限已授予，用户需要在详情页面手动点击开始上传
            } else {
                Log.w("MainActivity", "Some permissions were denied")
                // 可以显示提示信息
                Toast.makeText(
                    this,
                    "需要相册访问权限才能上传照片",
                    Toast.LENGTH_LONG
                ).show()
            }

            pendingUploadServer = null
        }
    }

    /**
     * 开始相册上传
     */
    fun startAlbumUpload(server: DiscoveredServer) {
        if (!albumUploadManager.hasRequiredPermissions()) {
            pendingUploadServer = server
            requestAlbumPermissions()
            return
        }

        val baseUrl = "${server.proto}://${server.ip.hostAddress}:${server.port}"
        Log.i("MainActivity", "Starting album upload to: $baseUrl")

        // 设置初始上传状态
        uploadState = UploadState(
            fileName = "准备上传...",
            progress = 0f,
            status = UploadStatus.Uploading
        )

        // 启动上传
        albumUploadManager.startAlbumUpload(baseUrl) { photoInfo, progress, status, totalFiles, currentFileIndex ->
            // 更新UI，这里可以显示上传进度
            Log.d("MainActivity", "Upload progress: ${photoInfo.name} - $progress, $status, totalFiles=$totalFiles, currentFileIndex=$currentFileIndex")
            runOnUiThread {
                uploadState = UploadState(
                    fileName = photoInfo.name,
                    progress = progress,
                    status = status,
                    totalFiles = totalFiles,
                    currentFileIndex = currentFileIndex
                )
            }

            // 上传完成提示
            if (status is UploadStatus.Completed && photoInfo.id == 0L && totalFiles != null && currentFileIndex != null) {
                // photoInfo.id == 0L 表示这是一个总体完成通知
                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        "全部照片已上传完成！（共${totalFiles}张照片）",
                        Toast.LENGTH_LONG
                    ).show()
                    Log.i("MainActivity", "All photos uploaded successfully: $totalFiles files")
                }
            }
        }
    }
}

/**
 * 主屏幕 - 根据当前页面显示不同内容
 */
@Composable
fun MainScreen(
    currentScreen: MainActivity.Screen,
    connectedServer: DiscoveredServer?,
    uploadState: UploadState?,
    discoveryManager: DiscoveryManager,
    albumUploadManager: AlbumUploadManager,
    fileUploadManager: FileUploadManager,
    onConnectToServer: (DiscoveredServer) -> Unit,
    onStartUpload: (DiscoveredServer) -> Unit,
    onStopUpload: () -> Unit,
    onBackToDiscovery: () -> Unit
) {
    when (currentScreen) {
        MainActivity.Screen.DISCOVERY -> {
            DiscoveryScreen(
                discoveryManager = discoveryManager,
                onConnectClick = onConnectToServer,
                uploadState = uploadState
            )
        }
        MainActivity.Screen.SERVER_DETAIL -> {
            connectedServer?.let { server ->
                ServerDetailScreen(
                    server = server,
                    albumUploadManager = albumUploadManager,
                    fileUploadManager = fileUploadManager,
                    uploadState = uploadState,
                    onBackClick = onBackToDiscovery,
                    onStartUpload = onStartUpload,
                    onStopUpload = onStopUpload
                )
            }
        }
    }
}

@Composable
fun DiscoveryScreen(
    discoveryManager: DiscoveryManager,
    onConnectClick: (DiscoveredServer) -> Unit,
    uploadState: UploadState? = null
) {
    val discoveredServers by discoveryManager.discoveredServers.collectAsState()
    var discoveryInProgress by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    var showCompletionDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // 当上传状态变为完成时显示弹窗
    LaunchedEffect(uploadState?.status) {
        if (uploadState?.status is UploadStatus.Completed && uploadState?.fileName.isNullOrEmpty()) {
            showCompletionDialog = true
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "局域网服务发现",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 24.dp)
            )

            // 上传进度显示
            uploadState?.let { state ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "上传进度",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        if (state.fileName.isNotEmpty()) {
                            Text(
                                text = "文件: ${state.fileName}",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                        }
                        // 显示文件计数
                        if (state.totalFiles != null && state.currentFileIndex != null) {
                            Text(
                                text = "文件 ${state.currentFileIndex + 1}/${state.totalFiles}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                        }
                        LinearProgressIndicator(
                            progress = { state.progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .padding(vertical = 8.dp)
                        )
                        Text(
                            text = when (state.status) {
                                is UploadStatus.Uploading -> "上传中..."
                                is UploadStatus.Completed -> "上传完成"
                                is UploadStatus.Failed -> "上传失败"
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

            Button(
                onClick = {
                    discoveryInProgress = true
                    coroutineScope.launch {
                        try {
                            discoveryManager.clearDiscoveredServers()

                            // 双向发现模式：同时启动主动探测和被动监听

                            // UDP双向发现：主动探测 + 被动监听服务端广播
                            val udpSuccess = discoveryManager.startDiscovery()
                            Log.d("MainActivity", "UDP双向发现 started (主动+被动): $udpSuccess")

                            // mDNS双向发现：主动探测 + 被动监听服务端广播（由Android NSD自动处理）
                            val mdnsSuccess = discoveryManager.startMDNSDiscovery()
                            Log.d("MainActivity", "mDNS双向发现 started (主动+被动): $mdnsSuccess")

                            // SSDP双向发现：主动M-SEARCH + 被动监听NOTIFY
                            val ssdpSuccess = discoveryManager.startSSDPDiscovery()
                            Log.d("MainActivity", "SSDP双向发现 started (M-SEARCH+NOTIFY): $ssdpSuccess")

                            if (!udpSuccess) {
                                Log.e("MainActivity", "UDP双向发现失败，尝试备用方式")
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(
                                        context,
                                        "UDP发现失败，尝试备用方式...",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }

                            // 等待UDP主通道完成（5秒），mDNS继续运行作为长连接
                            delay(DiscoveryManager.DISCOVERY_TIMEOUT_SECONDS * 1000L)
                        } finally {
                            // Always stop discovery so next run is reliable.
                            discoveryManager.stopDiscovery()
                            discoveryManager.stopMDNSDiscovery()
                            discoveryManager.stopSSDPDiscovery()
                            discoveryInProgress = false
                        }
                    }
                },
                enabled = !discoveryInProgress,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (discoveryInProgress) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("发现中...")
                } else {
                    Text("开始发现服务")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (discoveredServers.isNotEmpty()) {
                Text(
                    text = "发现的服务端 (${discoveredServers.size})",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(discoveredServers) { server ->
                        ServerCard(server, onConnectClick)
                    }
                }
            } else {
                if (discoveryInProgress) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("正在通过 mDNS 扫描服务...")
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "同时使用 UDP 作为辅助发现",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("点击上方按钮开始发现服务")
                    }
                }
            }
        }
    }

    // 上传完成弹窗
    if (showCompletionDialog) {
        AlertDialog(
            onDismissRequest = { showCompletionDialog = false },
            title = { Text("上传完成") },
            text = { Text("所有照片已成功上传到服务器。") },
            confirmButton = {
                Button(
                    onClick = { showCompletionDialog = false }
                ) {
                    Text("确定")
                }
            }
        )
    }
}

@Composable
fun ServerCard(
    server: DiscoveredServer,
    onConnectClick: (DiscoveredServer) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = server.name,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "IP地址: ${server.ip.hostAddress}",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "端口: ${server.port}",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "协议: ${server.proto}",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = {
                    onConnectClick(server)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("连接")
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun DiscoveryScreenPreview() {
    NascraftandroidTheme {
        // 使用一个模拟的 DiscoveryManager 进行预览
        // 注意：预览中不能使用真实的context，这里使用LocalContext.current
        val context = LocalContext.current
        DiscoveryScreen(DiscoveryManager(context), onConnectClick = {}, uploadState = null)
    }
}