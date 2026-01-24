package com.hawky.nascraft

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.InterfaceAddress

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
    }

    private val _discoveredServers = MutableStateFlow<List<DiscoveredServer>>(emptyList())
    val discoveredServers: StateFlow<List<DiscoveredServer>> = _discoveredServers.asStateFlow()

    private var discoveryJob: Job? = null
    private val discoveryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

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
                
                parseResponse(responseJson, receivePacket.address)
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
                        if (servers.none { s -> s.ip == server.ip && s.port == server.port }) {
                            servers.add(server)
                            _discoveredServers.value = servers.toList()
                            Log.i(TAG, "Discovered server: ${server.name} at ${server.ip}:${server.port}")
                        }
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