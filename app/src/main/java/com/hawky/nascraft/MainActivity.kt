package com.hawky.nascraft

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.hawky.nascraft.ui.theme.NascraftandroidTheme
import com.hawky.nascraft.UploadStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

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
    
    private var uploadState by mutableStateOf<UploadState?>(null)
    
    private var pendingUploadServer: DiscoveredServer? = null
    private val PERMISSION_REQUEST_CODE = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        discoveryManager = DiscoveryManager()
        albumUploadManager = AlbumUploadManager(this)
        setContent {
            NascraftandroidTheme {
                DiscoveryScreen(
                    discoveryManager = discoveryManager,
                    onConnectClick = { server ->
                        onConnectToServer(server)
                    },
                    uploadState = uploadState
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        discoveryManager.stopDiscovery()
    }

    private fun onConnectToServer(server: DiscoveredServer) {
        Log.d("MainActivity", "Connect clicked. Server: ${server.name}")
        
        if (albumUploadManager.hasRequiredPermissions()) {
            startAlbumUpload(server)
        } else {
            pendingUploadServer = server
            requestAlbumPermissions()
        }
    }

    private fun startAlbumUpload(server: DiscoveredServer) {
        val baseUrl = "${server.proto}://${server.ip.hostAddress}:${server.port}"
        Log.i("MainActivity", "Starting album upload to: $baseUrl")
        
        // 停止服务发现，避免与上传操作冲突
        discoveryManager.stopDiscovery()
        
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
        }
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
                pendingUploadServer?.let { server ->
                    startAlbumUpload(server)
                }
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
                        discoveryManager.clearDiscoveredServers()
                        discoveryManager.startDiscovery()
                        // 模拟超时后停止发现
                        delay(DiscoveryManager.DISCOVERY_TIMEOUT_SECONDS * 1000L)
                        discoveryInProgress = false
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
                            Text("正在扫描局域网...")
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
        DiscoveryScreen(DiscoveryManager(), onConnectClick = {}, uploadState = null)
    }
}