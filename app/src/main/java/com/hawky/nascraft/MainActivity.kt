package com.hawky.nascraft

import android.os.Bundle
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.hawky.nascraft.ui.theme.NascraftandroidTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var discoveryManager: DiscoveryManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        discoveryManager = DiscoveryManager()
        setContent {
            NascraftandroidTheme {
                DiscoveryScreen(discoveryManager)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        discoveryManager.stopDiscovery()
    }
}

@Composable
fun DiscoveryScreen(discoveryManager: DiscoveryManager) {
    val discoveredServers by discoveryManager.discoveredServers.collectAsState()
    var discoveryInProgress by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

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
                        ServerCard(server)
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
}

@Composable
fun ServerCard(server: DiscoveredServer) {
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
                    // TODO: 连接到选中的服务器
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
        DiscoveryScreen(DiscoveryManager())
    }
}