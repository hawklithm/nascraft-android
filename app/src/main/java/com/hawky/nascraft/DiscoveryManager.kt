package com.hawky.nascraft

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.InterfaceAddress
import java.util.concurrent.TimeUnit

/**
 * 表示一个被发现的服务端实例
 */
data class DiscoveredServer(
    val name: String,
    val ip: InetAddress,
    val port: Int,
    val proto: String,
    val rawResponse: String
)

/**
 * 服务发现管理器，使用UDP广播探测
 */
class DiscoveryManager {

    companion object {
        private const val TAG = "DiscoveryManager"
        // 与服务端配置匹配的默认端口
        const val UDP_DISCOVERY_PORT = 53530
        // 探测消息类型和版本
        private const val PROBE_TYPE = "nascraft_discover"
        private const val PROBE_VERSION = 1
        // 响应消息类型
        private const val RESPONSE_TYPE = "nascraft_here"
        // 广播地址
        private val BROADCAST_ADDRESS = InetAddress.getByName("255.255.255.255")
        // 超时时间（秒）
        const val DISCOVERY_TIMEOUT_SECONDS = 5L

        private const val HELLO_PATH = "/api/hello"
        private const val HELLO_TIMEOUT_MS = 1500L
    }

    private val _discoveredServers = MutableStateFlow<List<DiscoveredServer>>(emptyList())
    val discoveredServers: StateFlow<List<DiscoveredServer>> = _discoveredServers.asStateFlow()

    private var discoveryJob: Job? = null
    private val discoveryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val helloHttpClient = OkHttpClient.Builder()
        .connectTimeout(HELLO_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(HELLO_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(HELLO_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    private val validationMutex = Mutex()
    private val validatingServerKeys = HashSet<String>()

    /**
     * 开始服务发现过程
     */
    fun startDiscovery() {
        if (discoveryJob?.isActive == true) {
            Log.d(TAG, "Discovery already in progress")
            return
        }
        Log.i(TAG, "Starting service discovery")
        discoveryJob = discoveryScope.launch {
            runDiscovery()
        }
    }

    /**
     * 停止服务发现
     */
    fun stopDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
        Log.d(TAG, "Discovery stopped")
    }

    /**
     * 清空已发现的服务器列表
     */
    fun clearDiscoveredServers() {
        _discoveredServers.value = emptyList()
    }
    
    /**
     * 直接测试特定的IP地址，返回发现的服务器或null
     */
    suspend fun testServerDirectly(ipAddress: String): DiscoveredServer? {
        return try {
            val targetAddress = InetAddress.getByName(ipAddress)
            val socket = DatagramSocket().apply {
                broadcast = false
                soTimeout = 3000 // 3秒超时
            }
            
            try {
                // 构建探测消息
                val probeJson = """{"t":"$PROBE_TYPE","v":$PROBE_VERSION}"""
                val probeData = probeJson.toByteArray(Charsets.UTF_8)
                val probePacket = DatagramPacket(
                    probeData,
                    probeData.size,
                    targetAddress,
                    UDP_DISCOVERY_PORT
                )
                
                Log.d(TAG, "Testing server directly at $ipAddress:$UDP_DISCOVERY_PORT")
                socket.send(probePacket)
                
                val buffer = ByteArray(2048)
                val receivePacket = DatagramPacket(buffer, buffer.size)
                
                socket.receive(receivePacket)
                val responseData = receivePacket.data.copyOf(receivePacket.length)
                val responseJson = String(responseData, Charsets.UTF_8)
                Log.d(TAG, "Received direct response: ${responseJson.take(200)}")

                val server = parseResponse(responseJson, receivePacket.address)
                if (server == null) {
                    null
                } else {
                    if (validateServerHello(server)) server else null
                }
            } finally {
                socket.close()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Direct test failed for $ipAddress", e)
            null
        }
    }

    private suspend fun runDiscovery() {
        Log.d(TAG, "=== Starting network discovery ===")
        logNetworkInterfaces()
        
        val servers = mutableListOf<DiscoveredServer>()
        val socket = try {
            DatagramSocket().apply {
                broadcast = true
                setReuseAddress(true)
                soTimeout = (DISCOVERY_TIMEOUT_SECONDS * 1000).toInt()
                Log.d(TAG, "Socket created: broadcast=$broadcast, reuseAddress=$reuseAddress, timeout=$soTimeout")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create UDP socket", e)
            return
        }

        try {
            // 构建探测消息
            val probeJson = """{"t":"$PROBE_TYPE","v":$PROBE_VERSION}"""
            val probeData = probeJson.toByteArray(Charsets.UTF_8)
            Log.d(TAG, "Probe JSON: $probeJson, bytes: ${probeData.size}")
            
            // 尝试多种广播地址
            val broadcastAddresses = getBroadcastAddresses()
            Log.d(TAG, "Using broadcast addresses: $broadcastAddresses")
            
            var probeSent = false
            for (broadcastAddr in broadcastAddresses) {
                try {
                    val probePacket = DatagramPacket(
                        probeData,
                        probeData.size,
                        broadcastAddr,
                        UDP_DISCOVERY_PORT
                    )
                    Log.d(TAG, "Sending discovery probe to ${broadcastAddr.hostAddress}:$UDP_DISCOVERY_PORT")
                    socket.send(probePacket)
                    probeSent = true
                    Log.d(TAG, "Probe sent successfully to ${broadcastAddr.hostAddress}")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to send probe to ${broadcastAddr.hostAddress}: $e")
                }
            }
            
            if (!probeSent) {
                Log.e(TAG, "Failed to send probe to any broadcast address")
                return
            }

            // 接收响应
            val buffer = ByteArray(2048)
            val receivePacket = DatagramPacket(buffer, buffer.size)

            val startTime = System.currentTimeMillis()
            var responseCount = 0
            Log.d(TAG, "Starting to listen for responses for ${DISCOVERY_TIMEOUT_SECONDS} seconds")
            
            while (System.currentTimeMillis() - startTime < DISCOVERY_TIMEOUT_SECONDS * 1000) {
                try {
                    socket.receive(receivePacket)
                    responseCount++
                    val responseData = receivePacket.data.copyOf(receivePacket.length)
                    val responseJson = String(responseData, Charsets.UTF_8)
                    Log.d(TAG, "Received response #$responseCount from ${receivePacket.address}: ${responseJson.take(200)}")

                    // 解析响应
                    val server = parseResponse(responseJson, receivePacket.address)
                    server?.let {
                        validateAndAddServer(server, servers)
                    }
                } catch (e: java.net.SocketTimeoutException) {
                    // 超时正常退出
                    Log.d(TAG, "Socket timeout reached, stopping discovery")
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "Error receiving packet", e)
                    // 继续等待其他响应
                }
            }

            Log.i(TAG, "Discovery completed, found ${servers.size} servers out of $responseCount total responses")
        } catch (e: Exception) {
            Log.e(TAG, "Discovery error", e)
        } finally {
            socket.close()
        }
    }

    private fun serverKey(server: DiscoveredServer): String {
        return "${server.proto}://${server.ip.hostAddress}:${server.port}"
    }

    private fun validateAndAddServer(server: DiscoveredServer, servers: MutableList<DiscoveredServer>) {
        discoveryScope.launch {
            val key = serverKey(server)

            val shouldValidate = validationMutex.withLock {
                val alreadyAdded = servers.any { s -> s.ip == server.ip && s.port == server.port && s.proto == server.proto }
                if (alreadyAdded) {
                    false
                } else if (validatingServerKeys.contains(key)) {
                    false
                } else {
                    validatingServerKeys.add(key)
                    true
                }
            }

            if (!shouldValidate) {
                return@launch
            }

            try {
                val ok = validateServerHello(server)
                if (!ok) {
                    Log.w(TAG, "Hello check failed, ignoring server: $key")
                    return@launch
                }

                validationMutex.withLock {
                    if (servers.none { s -> s.ip == server.ip && s.port == server.port && s.proto == server.proto }) {
                        servers.add(server)
                        _discoveredServers.value = servers.toList()
                        Log.i(TAG, "Discovered server (hello ok): ${server.name} at $key")
                    }
                }
            } finally {
                validationMutex.withLock {
                    validatingServerKeys.remove(key)
                }
            }
        }
    }

    private suspend fun validateServerHello(server: DiscoveredServer): Boolean {
        val baseUrl = serverKey(server)
        val url = "$baseUrl$HELLO_PATH"
        Log.d(TAG, "Checking server health: $url")

        return try {
            withTimeout(HELLO_TIMEOUT_MS) {
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .build()

                helloHttpClient.newCall(request).execute().use { response ->
                    val ok = response.isSuccessful
                    if (!ok) {
                        val body = response.body?.string()
                        Log.w(TAG, "Hello check HTTP ${response.code} from $url, body=${body?.take(200)}")
                    }
                    ok
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "Hello check timeout: $url")
            false
        } catch (e: Exception) {
            Log.w(TAG, "Hello check error: $url", e)
            false
        }
    }

    private fun parseResponse(json: String, sourceAddress: InetAddress): DiscoveredServer? {
        return try {
            val obj = JSONObject(json)
            val type = obj.optString("t")
            if (type != RESPONSE_TYPE) {
                Log.d(TAG, "Response type mismatch: $type")
                return null
            }
            val version = obj.optInt("v")
            if (version != PROBE_VERSION) {
                Log.d(TAG, "Response version mismatch: $version")
                return null
            }
            val name = obj.optString("name", "unknown")
            val proto = obj.optString("proto", "http")
            val port = obj.optInt("port")
            if (port == 0) {
                Log.d(TAG, "Missing port in response")
                return null
            }
            DiscoveredServer(
                name = name,
                ip = sourceAddress,
                port = port,
                proto = proto,
                rawResponse = json
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse response", e)
            null
        }
    }
    
    private fun getBroadcastAddresses(): List<InetAddress> {
        val addresses = mutableListOf<InetAddress>()
        
        // 1. 全局广播地址
        try {
            addresses.add(InetAddress.getByName("255.255.255.255"))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get global broadcast address", e)
        }
        
        // 2. 尝试获取网络接口的子网广播地址
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                try {
                    if (!networkInterface.isUp || networkInterface.isLoopback) {
                        continue
                    }
                    
                    for (interfaceAddress in networkInterface.interfaceAddresses) {
                        val broadcast = interfaceAddress.broadcast
                        if (broadcast != null) {
                            Log.d(TAG, "Found broadcast address: ${broadcast.hostAddress} on interface ${networkInterface.displayName}")
                            addresses.add(broadcast)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error processing network interface ${networkInterface.displayName}", e)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to enumerate network interfaces", e)
        }
        
        // 如果没有找到任何广播地址，至少返回一个默认的
        if (addresses.isEmpty()) {
            try {
                addresses.add(InetAddress.getByName("255.255.255.255"))
            } catch (e: Exception) {
                // 如果这都失败，返回空列表
            }
        }
        
        Log.d(TAG, "Total broadcast addresses: ${addresses.size}")
        return addresses.distinctBy { it.hostAddress }
    }
    
    private fun logNetworkInterfaces() {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            var interfaceCount = 0
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                interfaceCount++
                try {
                    val isUp = networkInterface.isUp
                    val isLoopback = networkInterface.isLoopback
                    val displayName = networkInterface.displayName
                    val name = networkInterface.name
                    val hardwareAddress = networkInterface.hardwareAddress?.let { bytes ->
                        bytes.joinToString(":") { "%02x".format(it) }
                    } ?: "null"
                    
                    Log.d(TAG, "Interface #$interfaceCount: name=$name, display=$displayName, up=$isUp, loopback=$isLoopback, mac=$hardwareAddress")
                    
                    for (inetAddr in networkInterface.inetAddresses) {
                        Log.d(TAG, "  Address: ${inetAddr.hostAddress} (${inetAddr.hostName})")
                    }
                    
                    for (interfaceAddress in networkInterface.interfaceAddresses) {
                        val address = interfaceAddress.address
                        val broadcast = interfaceAddress.broadcast
                        val networkPrefixLength = interfaceAddress.networkPrefixLength
                        Log.d(TAG, "  Interface Address: ${address.hostAddress}/$networkPrefixLength, broadcast=${broadcast?.hostAddress ?: "null"}")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error logging interface details", e)
                }
            }
            Log.d(TAG, "Total network interfaces found: $interfaceCount")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to enumerate network interfaces for logging", e)
        }
    }
}