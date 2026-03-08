package com.hawky.nascraft

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
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
import java.net.InetSocketAddress
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URI
import java.net.NetworkInterface
import java.net.InterfaceAddress
import java.net.MulticastSocket
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
class DiscoveryManager(private val context: Context) {

    companion object {
        private const val TAG = "DiscoveryManager"
        // 与服务端配置匹配的默认端口
        const val UDP_DISCOVERY_PORT = 53530
        // mDNS服务端口
        const val MDNS_SERVICE_PORT = 8080
        // 探测消息类型和版本
        private const val PROBE_TYPE = "nascraft_discover"
        private const val PROBE_VERSION = 1
        // 响应消息类型
        private const val RESPONSE_TYPE = "nascraft_here"
        // 广播地址
        private val BROADCAST_ADDRESS = InetAddress.getByName("255.255.255.255")
        // 超时时间（秒）
        const val DISCOVERY_TIMEOUT_SECONDS = 5L
        // mDNS超时时间（秒）- 设置为1分钟作为主通道
        const val MDNS_DISCOVERY_TIMEOUT_SECONDS = 60L

        private const val HELLO_PATH = "/api/hello"
        private const val HELLO_TIMEOUT_MS = 1500L
        
        // 权限检查相关
        private const val INTERNET_PERMISSION = "android.permission.INTERNET"
        private const val ACCESS_NETWORK_STATE_PERMISSION = "android.permission.ACCESS_NETWORK_STATE"
        private const val ACCESS_WIFI_STATE_PERMISSION = "android.permission.ACCESS_WIFI_STATE"
        private const val CHANGE_WIFI_MULTICAST_STATE_PERMISSION = "android.permission.CHANGE_WIFI_MULTICAST_STATE"
        
        // mDNS服务类型（Android NSD discoverServices() 通常使用不带 domain 的写法：_service._proto.）
        // 服务端会广播 _nascraft._tcp.local.，但 Android 侧 discover 参数更稳定的是 _nascraft._tcp.
        const val MDNS_SERVICE_TYPE = "_nascraft._tcp."
        // SSDP服务类型（与服务端保持一致）
        private const val NASCRAFT_SSDP_ST = "urn:nascraft:service:remote:1"
    }

    /**
     * 开始 SSDP (UPnP) 服务发现（M-SEARCH）
     */
    fun startSSDPDiscovery(): Boolean {
        if (ssdpDiscoveryJob?.isActive == true) {
            Log.d(TAG, "SSDP discovery already in progress")
            return true
        }

        if (!checkRequiredPermissions()) {
            Log.e(TAG, "Missing required permissions for SSDP discovery")
            return false
        }

        if (!isNetworkAvailable()) {
            Log.e(TAG, "Network not available for SSDP discovery")
            return false
        }

        Log.i(TAG, "Starting SSDP discovery and notify listener")
        ssdpDiscoveryJob = discoveryScope.launch {
            runSSDPDiscovery()
        }
        ssdpNotifyListenJob = discoveryScope.launch {
            runSSDPNotifyListener()
        }
        return true
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
    
    // mDNS相关属性
    private var mdnsManager: NsdManager? = null
    private var mdnsDiscoveryListener: NsdManager.DiscoveryListener? = null
    private var mdnsDiscoveryJob: Job? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    // SSDP (UPnP) discovery
    private var ssdpDiscoveryJob: Job? = null
    private var ssdpSocket: DatagramSocket? = null
    // SSDP Notify监听
    private var ssdpNotifyListenJob: Job? = null

    // UDP broadcast listener（监听服务端主动广播）
    private var udpBroadcastListenJob: Job? = null

    /**
     * 开始服务发现过程
     */
    fun startDiscovery(): Boolean {
        if (discoveryJob?.isActive == true) {
            Log.d(TAG, "Discovery already in progress")
            return true
        }
        
        // 检查权限
        if (!checkRequiredPermissions()) {
            Log.e(TAG, "Missing required permissions for network discovery")
            return false
        }
        
        // 检查网络连接
        if (!isNetworkAvailable()) {
            Log.e(TAG, "Network not available for discovery")
            return false
        }
        
        Log.i(TAG, "Starting service discovery and UDP broadcast listener")
        discoveryJob = discoveryScope.launch {
            runDiscovery()
        }
        udpBroadcastListenJob = discoveryScope.launch {
            runUDPBroadcastListener()
        }
        return true
    }
    
    /**
     * 开始mDNS服务发现
     */
    fun startMDNSDiscovery(): Boolean {
        if (mdnsDiscoveryJob?.isActive == true) {
            Log.d(TAG, "mDNS discovery already in progress")
            return true
        }

        // If previous discovery wasn't cleaned up correctly, stop it before starting again.
        if (mdnsDiscoveryListener != null) {
            Log.w(TAG, "mDNS listener still present; stopping previous discovery before restarting")
            stopMDNSDiscovery()
        }
        
        // 检查权限
        if (!checkRequiredPermissions()) {
            Log.e(TAG, "Missing required permissions for mDNS discovery")
            return false
        }
        
        // 检查网络连接
        if (!isNetworkAvailable()) {
            Log.e(TAG, "Network not available for mDNS discovery")
            return false
        }
        
        // 检查网络类型，mDNS通常只在WiFi下工作
        val networkType = getNetworkType()
        if (networkType != "wifi" && networkType != "ethernet") {
            Log.w(TAG, "mDNS may not work properly on network type: $networkType")
        }
        
        // 初始化NsdManager
        mdnsManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (mdnsManager == null) {
            Log.e(TAG, "Failed to get NsdManager service")
            return false
        }

        // 检查mDNS服务是否可用
        if (!isMDNSServiceAvailable()) {
            Log.w(TAG, "mDNS service not available, continuing without it")
            return false
        }

        Log.i(TAG, "Starting mDNS discovery (主通道) on network type: $networkType")
        mdnsDiscoveryJob = discoveryScope.launch {
            runMDNSDiscovery()
        }
        return true
    }

    /**
     * 停止服务发现
     */
    fun stopDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
        udpBroadcastListenJob?.cancel()
        udpBroadcastListenJob = null
        Log.d(TAG, "Discovery stopped")
    }
    
    /**
     * 停止mDNS服务发现
     */
    fun stopMDNSDiscovery() {
        mdnsDiscoveryJob?.cancel()
        mdnsDiscoveryJob = null

        // 清理mDNS资源
        mdnsDiscoveryListener?.let { listener ->
            try {
                mdnsManager?.stopServiceDiscovery(listener)
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping mDNS discovery", e)
            }
        }
        mdnsDiscoveryListener = null

        multicastLock?.let { lock ->
            try {
                if (lock.isHeld) lock.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing MulticastLock", e)
            }
        }
        multicastLock = null

        mdnsManager = null
        
        Log.d(TAG, "mDNS discovery stopped")
    }

    /**
     * 停止 SSDP 发现
     */
    fun stopSSDPDiscovery() {
        ssdpDiscoveryJob?.cancel()
        ssdpDiscoveryJob = null
        ssdpNotifyListenJob?.cancel()
        ssdpNotifyListenJob = null
        try {
            ssdpSocket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing SSDP socket", e)
        }
        ssdpSocket = null
        Log.d(TAG, "SSDP discovery stopped")
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
        // 检查权限和网络连接
        if (!checkRequiredPermissions()) {
            Log.e(TAG, "Missing required permissions for direct server test")
            return null
        }
        
        if (!isNetworkAvailable()) {
            Log.e(TAG, "Network not available for direct server test")
            return null
        }
        
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
        Log.i(TAG, "=== Starting UDP discovery (辅助手段) ===")
        logNetworkInterfaces()

        // 输出当前移动设备的IP地址
        logCurrentDeviceIP()

        val servers = mutableListOf<DiscoveredServer>()
        _discoveredServers.value = emptyList()

        val wifiIPv4 = findWifiIPv4Address()
        if (wifiIPv4 == null) {
            Log.e(TAG, "No WiFi IPv4 address found; cannot reliably perform UDP broadcast discovery")
            return
        }
        Log.d(TAG, "Using WiFi IPv4 address for UDP bind: ${wifiIPv4.hostAddress}")

        val socket = try {
            // 同样绑定到0.0.0.0而不是特定WiFi IP，避免本地回环问题
            DatagramSocket(InetSocketAddress(InetAddress.getByName("0.0.0.0"), 0)).apply {
                broadcast = true
                setReuseAddress(true)
                soTimeout = 1000 // 1s timeout so we can retry sending probes and check cancellation
                Log.d(TAG, "Socket created: broadcast=$broadcast, reuseAddress=$reuseAddress, timeout=$soTimeout, localAddr=$localAddress")
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
            
            fun sendProbeOnce(): Boolean {
                var sent = false
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
                        sent = true
                        Log.d(TAG, "Probe sent successfully to ${broadcastAddr.hostAddress}")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to send probe to ${broadcastAddr.hostAddress}: $e")
                    }
                }
                return sent
            }

            if (!sendProbeOnce()) {
                Log.e(TAG, "Failed to send probe to any broadcast address")
                return
            }

            // 接收响应
            val buffer = ByteArray(2048)
            val receivePacket = DatagramPacket(buffer, buffer.size)

            val startTime = System.currentTimeMillis()
            var responseCount = 0
            Log.d(TAG, "Starting to listen for responses for ${DISCOVERY_TIMEOUT_SECONDS} seconds")

            var lastProbeSentAt = startTime
            
            while (System.currentTimeMillis() - startTime < DISCOVERY_TIMEOUT_SECONDS * 1000) {
                // 检查协程是否已取消，如果是则退出
                try {
                    kotlin.coroutines.coroutineContext.ensureActive()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    Log.d(TAG, "Discovery cancelled, exiting")
                    throw e // 重新抛出取消异常
                }
                
                try {
                    receivePacket.length = buffer.size
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
                    // socket超时是正常的；周期性重发 probe 提高在弱网络/丢包情况下的成功率
                    val now = System.currentTimeMillis()
                    if (now - lastProbeSentAt >= 1000) {
                        sendProbeOnce()
                        lastProbeSentAt = now
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error receiving packet", e)
                    // 继续等待其他响应
                }
            }

            Log.i(TAG, "Discovery completed, found ${servers.size} servers out of $responseCount total responses")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // 重新抛出取消异常
        } catch (e: Exception) {
            Log.e(TAG, "Discovery error", e)
        } finally {
            try {
                socket.close()
                Log.d(TAG, "Discovery socket closed")
            } catch (e: Exception) {
                Log.w(TAG, "Error closing socket", e)
            }
        }
    }

    /**
     * 监听UDP广播消息（服务端主动广播）
     */
    private suspend fun runUDPBroadcastListener() {
        Log.i(TAG, "=== Starting UDP broadcast listener ===")

        val servers = mutableListOf<DiscoveredServer>()

        val socket = try {
            DatagramSocket(null).apply {
                bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), UDP_DISCOVERY_PORT))
                broadcast = true
                soTimeout = 1000
                Log.d(TAG, "UDP broadcast listener socket created: localPort=$localPort")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create UDP broadcast listener socket", e)
            return
        }

        val buffer = ByteArray(2048)
        val receivePacket = DatagramPacket(buffer, buffer.size)

        try {
            while (true) {
                kotlin.coroutines.coroutineContext.ensureActive()

                try {
                    receivePacket.length = buffer.size
                    socket.receive(receivePacket)
                    val msg = String(receivePacket.data, 0, receivePacket.length, Charsets.UTF_8)

                    val json = try {
                        JSONObject(msg)
                    } catch (e: Exception) {
                        Log.d(TAG, "Received non-JSON UDP broadcast message, ignoring")
                        null
                    } ?: continue

                    val type = json.optString("t")
                    if (type != "nascraft_announce") {
                        continue
                    }

                    val version = json.optInt("v")
                    if (version != PROBE_VERSION) {
                        continue
                    }

                    val name = json.optString("name", "unknown")
                    val proto = json.optString("proto", "http")
                    val port = json.optInt("port")
                    if (port == 0) {
                        continue
                    }

                    Log.d(TAG, "UDP broadcast announce received from ${receivePacket.address}: $name:$port")

                    val server = DiscoveredServer(
                        name = name,
                        ip = receivePacket.address,
                        port = port,
                        proto = proto,
                        rawResponse = msg
                    )
                    validateAndAddServer(server, servers)
                } catch (e: java.net.SocketTimeoutException) {
                    // 正常超时，继续监听
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Error in UDP broadcast listener", e)
                }
            }
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
            }
            Log.d(TAG, "UDP broadcast listener stopped")
        }
    }

    private suspend fun runSSDPDiscovery() {
        Log.i(TAG, "=== Starting SSDP discovery (UPnP M-SEARCH) ===")

        // 输出当前移动设备的IP地址
        logCurrentDeviceIP()

        val servers = mutableListOf<DiscoveredServer>()

        val wifiIPv4 = findWifiIPv4Address()
        if (wifiIPv4 == null) {
            Log.e(TAG, "No WiFi IPv4 address found; cannot reliably perform SSDP discovery")
            return
        }
        Log.d(TAG, "Using WiFi IPv4 address for SSDP: ${wifiIPv4.hostAddress}")

        val multicastAddr = InetAddress.getByName("239.255.255.250")
        val target = InetSocketAddress(multicastAddr, 1900)

        // 使用MulticastSocket而不是普通的DatagramSocket，这样才能加入多播组
        val socket = try {
            java.net.MulticastSocket(null).apply {
                // 绑定到0.0.0.0（所有接口）而不是特定WiFi IP
                // 这样可以接收来自任何源的响应，包括同机器上的服务端
                bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), 0))
                broadcast = true
                soTimeout = 1000 // 增加超时时间，从700ms到1000ms

                // 设置多播TTL（Time To Live），确保多播包能到达同一网段的所有设备
                try {
                    timeToLive = 4 // 4足够覆盖局域网
                    Log.d(TAG, "SSDP TTL set to 4")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to set TTL", e)
                }

                // 关键：加入SSDP多播组以接收响应
                try {
                    val groupAddr = InetAddress.getByName("239.255.255.250")
                    joinGroup(groupAddr)
                    Log.d(TAG, "Successfully joined SSDP multicast group 239.255.255.250")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to join SSDP multicast group", e)
                    // 继续执行，可能仍能接收单播响应
                }

                Log.d(TAG, "SSDP socket created: localPort=$localPort, broadcast=$broadcast, timeout=$soTimeout, localAddr=$localAddress")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create SSDP multicast socket", e)
            return
        }

        ssdpSocket = socket

        try {
            val searchTargets = listOf(
                "urn:nascraft:service:remote:1",
                "ssdp:all"
            )

            fun buildMSearch(st: String): ByteArray {
                val msg = (
                    "M-SEARCH * HTTP/1.1\r\n" +
                        "HOST: 239.255.255.250:1900\r\n" +
                        "MAN: \"ssdp:discover\"\r\n" +
                        "MX: 1\r\n" +
                        "ST: $st\r\n" +
                        "\r\n"
                    )
                return msg.toByteArray(Charsets.UTF_8)
            }

            fun sendProbeOnce() {
                for (st in searchTargets) {
                    val data = buildMSearch(st)
                    try {
                        socket.send(DatagramPacket(data, data.size, target))
                        Log.d(TAG, "SSDP M-SEARCH sent to $target: st=$st, ${data.size} bytes")
                        Log.v(TAG, "SSDP M-SEARCH content:\n${String(data, Charsets.UTF_8)}")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to send SSDP M-SEARCH (st=$st)", e)
                    }
                }
            }

            val startTime = System.currentTimeMillis()
            val timeoutMs = DISCOVERY_TIMEOUT_SECONDS * 1000L // 使用与UDP一致的超时时间
            var lastProbeAt = 0L

            val buffer = ByteArray(4096)
            val receivePacket = DatagramPacket(buffer, buffer.size)

            while (System.currentTimeMillis() - startTime < timeoutMs) {
                kotlin.coroutines.coroutineContext.ensureActive()

                val now = System.currentTimeMillis()
                if (now - lastProbeAt >= 1000) {
                    sendProbeOnce()
                    lastProbeAt = now
                }

                try {
                    receivePacket.length = buffer.size
                    socket.receive(receivePacket)
                    val resp = String(receivePacket.data, 0, receivePacket.length, Charsets.UTF_8)

                    // 详细记录收到的SSDP响应
                    Log.d(TAG, "SSDP response received from ${receivePacket.address}:${receivePacket.port}, ${receivePacket.length} bytes")
                    Log.v(TAG, "SSDP response content:\n${resp.take(500)}")

                    val location = parseSsdpHeader(resp, "location")
                    if (location.isNullOrBlank()) {
                        Log.d(TAG, "SSDP response has no LOCATION header, skipping")
                        continue
                    }
                    Log.d(TAG, "SSDP response parsed location: $location")

                    val parsed = parseLocationHostPort(location, receivePacket.address) ?: continue
                    val (host, port) = parsed
                    val server = DiscoveredServer(
                        name = "nascraft",
                        ip = host,
                        port = port,
                        proto = "http",
                        rawResponse = "ssdp:$location"
                    )
                    validateAndAddServer(server, servers)
                } catch (e: java.net.SocketTimeoutException) {
                    // normal, just continue waiting
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Error in SSDP receive loop", e)
                }
            }

            Log.i(TAG, "SSDP discovery completed")
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
            }
            if (ssdpSocket === socket) ssdpSocket = null
        }
    }

    private fun parseSsdpHeader(resp: String, header: String): String? {
        val headerLower = header.lowercase()
        for (line in resp.split("\r\n", "\n")) {
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val name = line.substring(0, idx).trim().lowercase()
            if (name != headerLower) continue
            return line.substring(idx + 1).trim()
        }
        return null
    }

    private fun parseLocationHostPort(location: String, fallbackIp: InetAddress): Pair<InetAddress, Int>? {
        return try {
            val uri = URI(location)
            val hostStr = uri.host
            val port = if (uri.port > 0) uri.port else MDNS_SERVICE_PORT
            val ip = if (!hostStr.isNullOrBlank()) InetAddress.getByName(hostStr) else fallbackIp
            Pair(ip, port)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse SSDP LOCATION: $location", e)
            null
        }
    }

    /**
     * 监听SSDP NOTIFY消息（服务端主动广播）
     */
    private suspend fun runSSDPNotifyListener() {
        Log.i(TAG, "=== Starting SSDP NOTIFY listener ===")

        val servers = mutableListOf<DiscoveredServer>()

        val wifiIPv4 = findWifiIPv4Address()
        if (wifiIPv4 == null) {
            Log.e(TAG, "No WiFi IPv4 address found; cannot start SSDP NOTIFY listener")
            return
        }
        Log.d(TAG, "Using WiFi IPv4 address for SSDP notify: ${wifiIPv4.hostAddress}")

        val multicastAddr = InetAddress.getByName("239.255.255.250")

        val socket = try {
            java.net.MulticastSocket(null).apply {
                bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), 1900))
                soTimeout = 1000

                try {
                    timeToLive = 4
                    Log.d(TAG, "SSDP notify listener TTL set to 4")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to set TTL", e)
                }

                try {
                    joinGroup(multicastAddr)
                    Log.d(TAG, "Successfully joined SSDP multicast group for NOTIFY")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to join SSDP multicast group for NOTIFY", e)
                }

                Log.d(TAG, "SSDP notify listener socket created: localPort=$localPort, localAddr=$localAddress")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create SSDP notify listener socket", e)
            return
        }

        val buffer = ByteArray(4096)
        val receivePacket = DatagramPacket(buffer, buffer.size)

        try {
            while (true) {
                kotlin.coroutines.coroutineContext.ensureActive()

                try {
                    receivePacket.length = buffer.size
                    socket.receive(receivePacket)
                    val msg = String(receivePacket.data, 0, receivePacket.length, Charsets.UTF_8)

                    // 检查是否是NOTIFY消息
                    if (!msg.startsWith("NOTIFY * HTTP/1.1") && !msg.startsWith("NOTIFY * HTTP/1.0")) {
                        continue
                    }

                    // 检查NTS是否为ssdp:alive
                    val nts = parseSsdpHeader(msg, "nts")
                    if (nts != "ssdp:alive") {
                        continue
                    }

                    val nt = parseSsdpHeader(msg, "nt")
                    if (nt != NASCRAFT_SSDP_ST && nt != "ssdp:all") {
                        continue
                    }

                    val location = parseSsdpHeader(msg, "location")
                    if (location.isNullOrBlank()) {
                        continue
                    }

                    Log.d(TAG, "SSDP NOTIFY received from ${receivePacket.address}: ${msg.take(300)}")
                    Log.d(TAG, "SSDP NOTIFY location: $location, nt: $nt")

                    val parsed = parseLocationHostPort(location, receivePacket.address) ?: continue
                    val (host, port) = parsed
                    val server = DiscoveredServer(
                        name = "nascraft",
                        ip = host,
                        port = port,
                        proto = "http",
                        rawResponse = "ssdp_notify:$location"
                    )
                    validateAndAddServer(server, servers)
                } catch (e: java.net.SocketTimeoutException) {
                    // 正常超时，继续监听
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Error in SSDP notify receive loop", e)
                }
            }
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
            }
            Log.d(TAG, "SSDP notify listener stopped")
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
            Log.d(TAG, "Added global broadcast address: 255.255.255.255")
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
                        if (broadcast != null && !broadcast.isLoopbackAddress) {
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
                Log.w(TAG, "No broadcast addresses found, using default: 255.255.255.255")
            } catch (e: Exception) {
                // 如果这都失败，返回空列表
            }
        }

        val distinctAddresses = addresses.distinctBy { it.hostAddress }
        Log.d(TAG, "Total broadcast addresses: ${distinctAddresses.size}")
        return distinctAddresses
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

    /**
     * 输出当前移动设备的IP地址
     */
    private fun logCurrentDeviceIP() {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            val ipAddresses = mutableListOf<String>()
            var wifiIP: String? = null

            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                try {
                    if (!networkInterface.isUp || networkInterface.isLoopback) {
                        continue
                    }

                    // 检测是否是WiFi接口
                    val isWifi = networkInterface.name.contains("wlan", ignoreCase = true) ||
                        networkInterface.displayName.contains("wlan", ignoreCase = true) ||
                        networkInterface.displayName.contains("wifi", ignoreCase = true) ||
                        networkInterface.name.contains("wlan", ignoreCase = true)

                    for (inetAddr in networkInterface.inetAddresses) {
                        val hostAddress = inetAddr.hostAddress ?: continue
                        // 只收集IPv4地址
                        if (hostAddress.contains('.')) {
                            ipAddresses.add("${networkInterface.name}:$hostAddress")
                            if (isWifi) {
                                wifiIP = hostAddress
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error getting IP for interface ${networkInterface.name}", e)
                }
            }

            Log.i(TAG, "=== 当前移动设备IP地址 ===")
            if (ipAddresses.isNotEmpty()) {
                ipAddresses.forEach { ip ->
                    Log.i(TAG, "  $ip")
                }
            } else {
                Log.w(TAG, "  未找到任何IPv4地址")
            }

            if (wifiIP != null) {
                Log.i(TAG, "  WiFi IP: $wifiIP")
            } else {
                Log.w(TAG, "  未找到WiFi接口IP")
            }
            Log.i(TAG, "========================")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get current device IP", e)
        }
    }
    
    /**
     * 运行mDNS服务发现
     */
    private suspend fun runMDNSDiscovery() {
        Log.i(TAG, "=== Starting mDNS discovery (主通道) ===")

        // 输出当前移动设备的IP地址
        logCurrentDeviceIP()

        val servers = mutableListOf<DiscoveredServer>()
        _discoveredServers.value = emptyList()

        // Android NSD 对 serviceType 格式要求：_service._tcp.
        // 尝试多种可能的格式以提高兼容性
        val candidateServiceTypes = listOf(
            "_nascraft._tcp.",        // Android NSD 标准格式
            "_nascraft._tcp.local."   // 带local后缀的格式
        ).distinct()

        fun normalizeNsdServiceType(input: String): String {
            var s = input.trim()
            // map "_x._tcp.local." -> "_x._tcp."
            if (s.endsWith(".local.", ignoreCase = true)) {
                s = s.removeSuffix(".local.") + "."
            } else if (s.endsWith(".local", ignoreCase = true)) {
                s = s.removeSuffix(".local") + "."
            }

            // NSD expects trailing dot for type: "_http._tcp."
            if (!s.endsWith(".")) {
                s += "."
            }
            return s
        }

        // 获取 MulticastLock（很多机型如果不持有锁，mDNS 包会被丢弃）
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wifiManager != null) {
                val lock = wifiManager.createMulticastLock("nascraft-mdns")
                lock.setReferenceCounted(false)
                lock.acquire()
                multicastLock = lock
                Log.d(TAG, "MulticastLock acquired")
            } else {
                Log.w(TAG, "WifiManager not available; mDNS may not work on this device")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire MulticastLock", e)
        }
        
        var attempt = 1
        val maxAttempts = 3

        try {
            while (attempt <= maxAttempts) {
                try {
                    Log.d(TAG, "mDNS discovery attempt $attempt/$maxAttempts")

                    // 创建发现监听器
                    mdnsDiscoveryListener = createMDNSDiscoveryListener(servers)

                    // Some devices report discovery "started" but never return results if the type is not exactly matched.
                    // Try multiple service types, spending a short time on each.
                    val perTypeWaitMs = 2500L
                    var anyTypeStarted = false
                    var foundAny = false
                    var attemptFailed = false

                    for (serviceType in candidateServiceTypes) {
                        kotlin.coroutines.coroutineContext.ensureActive()

                        val normalized = normalizeNsdServiceType(serviceType)
                        val started = withContext(Dispatchers.Main) {
                            try {
                                // 确保监听器状态干净
                                cleanupMDNSDiscovery()
                                mdnsDiscoveryListener = createMDNSDiscoveryListener(servers)
                                
                                mdnsManager?.discoverServices(normalized, NsdManager.PROTOCOL_DNS_SD, mdnsDiscoveryListener)
                                Log.i(TAG, "mDNS discovery request sent successfully: $normalized")
                                true
                            } catch (e: SecurityException) {
                                Log.e(TAG, "Security exception when starting mDNS discovery: $normalized", e)
                                attemptFailed = true
                                false
                            } catch (e: IllegalArgumentException) {
                                Log.w(TAG, "IllegalArgument when starting mDNS discovery: $normalized - ${e.message}")
                                attemptFailed = true
                                false
                            } catch (e: Exception) {
                                Log.e(TAG, "Unexpected exception when starting mDNS discovery: $normalized", e)
                                attemptFailed = true
                                false
                            }
                        }

                        if (!started) {
                            Log.w(TAG, "Failed to start discovery for type: $normalized, trying next type")
                            // 如果所有类型都失败了，跳出循环
                            if (attemptFailed && candidateServiceTypes.indexOf(serviceType) == candidateServiceTypes.size - 1) {
                                Log.e(TAG, "All mDNS service types failed, mDNS discovery not available on this device")
                            }
                            continue
                        }
                        
                        anyTypeStarted = true
                        Log.i(TAG, "mDNS discovery started successfully: $normalized")

                        val typeStart = System.currentTimeMillis()
                        while (System.currentTimeMillis() - typeStart < perTypeWaitMs) {
                            kotlin.coroutines.coroutineContext.ensureActive()
                            if (servers.isNotEmpty()) {
                                foundAny = true
                                Log.i(TAG, "Found services for type: $normalized")
                                break
                            }
                            delay(100)
                        }

                        // Stop current type discovery before trying next type.
                        cleanupMDNSDiscovery()
                        if (foundAny) {
                            Log.i(TAG, "Found services, stopping type iteration")
                            break
                        }
                        // Recreate listener for next type (NSD is sensitive to reusing listener state)
                        mdnsDiscoveryListener = createMDNSDiscoveryListener(servers)
                    }

                    if (!anyTypeStarted) {
                        throw RuntimeException("Failed to start mDNS discovery")
                    }

                    // Continue waiting (up to configured timeout) if we have at least one server.
                    if (servers.isNotEmpty()) {
                        delay(MDNS_DISCOVERY_TIMEOUT_SECONDS * 1000)
                    }
                    Log.i(TAG, "mDNS discovery completed, found ${servers.size} servers")
                    return
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "mDNS discovery attempt $attempt failed", e)
                    if (attempt >= maxAttempts) {
                        Log.e(TAG, "All mDNS discovery attempts failed")
                        return
                    }
                    cleanupMDNSDiscovery()
                    delay(2000L)
                    attempt++
                }
            }
        } finally {
            // Always cleanup. Otherwise subsequent runs frequently fail with NSD internal/already-active.
            cleanupMDNSDiscovery()
            multicastLock?.let { lock ->
                try {
                    if (lock.isHeld) lock.release()
                } catch (e: Exception) {
                    Log.w(TAG, "Error releasing MulticastLock", e)
                }
            }
            multicastLock = null
        }
    }
    
    /**
     * 清理mDNS发现资源
     */
    private fun cleanupMDNSDiscovery() {
        // 清理资源，但避免重复停止发现
        try {
            val listener = mdnsDiscoveryListener
            if (listener != null) {
                mdnsManager?.stopServiceDiscovery(listener)
                Log.d(TAG, "mDNS discovery stopped in cleanup")
            } else {
                Log.d(TAG, "mDNS listener already null, skipping stop")
            }
        } catch (e: IllegalArgumentException) {
            // listener not registered 是正常情况，忽略
            Log.d(TAG, "mDNS listener already unregistered, this is normal")
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping mDNS discovery", e)
        }
        mdnsDiscoveryListener = null
    }
    
    /**
     * 创建mDNS发现监听器
     */
    private fun createMDNSDiscoveryListener(servers: MutableList<DiscoveredServer>): NsdManager.DiscoveryListener {
        return object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.i(TAG, "mDNS discovery started: $serviceType")
            }
            
            override fun onDiscoveryStopped(serviceType: String) {
                Log.i(TAG, "mDNS discovery stopped: $serviceType")
            }
            
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                val errorMsg = when (errorCode) {
                    NsdManager.FAILURE_INTERNAL_ERROR -> "内部错误"
                    NsdManager.FAILURE_ALREADY_ACTIVE -> "发现已在进行中"
                    NsdManager.FAILURE_MAX_LIMIT -> "达到最大限制"
                    else -> "未知错误 ($errorCode)"
                }
                Log.e(TAG, "mDNS discovery start failed: $errorMsg")
            }
            
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "mDNS discovery stop failed: $serviceType, errorCode=$errorCode")
            }
            
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "mDNS service found: ${serviceInfo.serviceName}, type=${serviceInfo.serviceType}")
                
                // 异步解析服务
                discoveryScope.launch {
                    resolveMDNSService(serviceInfo, servers)
                }
            }
            
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "mDNS service lost: ${serviceInfo.serviceName}")
                
                // 从列表中移除服务
                val serviceKey = "${serviceInfo.serviceName}|${serviceInfo.serviceType}"
                servers.removeAll { server -> server.name == serviceInfo.serviceName }
                _discoveredServers.value = servers.toList()
            }
        }
    }
    
    /**
     * 解析mDNS服务
     */
    private suspend fun resolveMDNSService(serviceInfo: NsdServiceInfo, servers: MutableList<DiscoveredServer>) {
        Log.d(TAG, "Resolving mDNS service: ${serviceInfo.serviceName}")
        
        try {
            mdnsManager?.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "mDNS resolve failed: ${serviceInfo.serviceName}, errorCode=$errorCode")
                }
                
                override fun onServiceResolved(resolvedService: NsdServiceInfo) {
                    val host = resolvedService.host
                    val port = resolvedService.port
                    
                    if (host == null || port <= 0) {
                        Log.w(TAG, "Invalid mDNS service: host=$host, port=$port")
                        return
                    }
                    
                    // 创建发现的服务器对象
                    val server = DiscoveredServer(
                        name = resolvedService.serviceName ?: "unknown",
                        ip = host,
                        port = port,
                        proto = "http",
                        rawResponse = "mdns:${resolvedService.serviceType}"
                    )
                    
                    // 验证并添加服务器
                    validateAndAddServer(server, servers)
                    
                    Log.i(TAG, "mDNS service resolved: ${server.name} at ${host.hostAddress}:$port")
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving mDNS service", e)
        }
    }
    
    /**
     * 检查所需的网络权限
     */
    private fun checkRequiredPermissions(): Boolean {
        val requiredPermissions = listOf(
            INTERNET_PERMISSION,
            ACCESS_NETWORK_STATE_PERMISSION,
            ACCESS_WIFI_STATE_PERMISSION,
            CHANGE_WIFI_MULTICAST_STATE_PERMISSION
        )
        
        val missingPermissions = mutableListOf<String>()
        for (permission in requiredPermissions) {
            if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                missingPermissions.add(permission)
            }
        }
        
        if (missingPermissions.isNotEmpty()) {
            Log.e(TAG, "Missing required permissions: ${missingPermissions.joinToString(", ")}")
            return false
        }
        
        Log.d(TAG, "All required permissions granted")
        return true
    }
    
    /**
     * 检查网络是否可用
     */
    private fun isNetworkAvailable(): Boolean {
        return try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (connectivityManager == null) {
                Log.w(TAG, "ConnectivityManager not available")
                return false
            }
            
            val network = connectivityManager.activeNetwork
            if (network == null) {
                Log.w(TAG, "No active network")
                return false
            }
            
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            if (capabilities == null) {
                Log.w(TAG, "No network capabilities")
                return false
            }

            // 服务发现只需要本地连通，不要求 VERIFIED/有互联网（很多路由器或访客网会导致 VALIDATED=false）
            val isConnected = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            
            if (!isConnected) {
                Log.w(TAG, "No NET_CAPABILITY_INTERNET")
            }
            
            isConnected
        } catch (e: Exception) {
            Log.e(TAG, "Error checking network availability", e)
            false
        }
    }

    /**
     * 查找 WiFi 接口的 IPv4 地址（用于 UDP 广播绑定）
     * 优先级：WiFi > 以太网 > 任何其他非回环接口
     */
    private fun findWifiIPv4Address(): InetAddress? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            var wifiAddr: InetAddress? = null
            var ethernetAddr: InetAddress? = null
            var anyAddr: InetAddress? = null

            while (interfaces.hasMoreElements()) {
                val nif = interfaces.nextElement()
                if (!nif.isUp || nif.isLoopback) continue

                // 检测接口类型
                val isWifi = nif.name.contains("wlan", ignoreCase = true) ||
                    nif.displayName.contains("wlan", ignoreCase = true) ||
                    nif.displayName.contains("wifi", ignoreCase = true) ||
                    nif.name.contains("wlan", ignoreCase = true)

                val isEthernet = nif.name.contains("eth", ignoreCase = true) ||
                    nif.displayName.contains("eth", ignoreCase = true)

                val inetAddrs = nif.inetAddresses
                while (inetAddrs.hasMoreElements()) {
                    val addr = inetAddrs.nextElement()
                    val host = addr.hostAddress ?: continue
                    // 只取IPv4地址
                    if (host.contains('.')) {
                        if (isWifi && wifiAddr == null) {
                            wifiAddr = addr
                        } else if (isEthernet && ethernetAddr == null) {
                            ethernetAddr = addr
                        } else if (anyAddr == null) {
                            anyAddr = addr
                        }
                    }
                }
            }

            // 按优先级返回地址
            val result = wifiAddr ?: ethernetAddr ?: anyAddr
            Log.d(TAG, "findWifiIPv4Address result: ${result?.hostAddress ?: "null"} (wifi=$wifiAddr, ethernet=$ethernetAddr, any=$anyAddr)")
            result
        } catch (e: Exception) {
            Log.w(TAG, "Failed to enumerate interfaces for WiFi IPv4", e)
            null
        }
    }
    
    /**
     * 获取当前网络类型信息
     */
    private fun getNetworkType(): String {
        return try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (connectivityManager == null) {
                return "unknown"
            }
            
            val network = connectivityManager.activeNetwork
            if (network == null) {
                return "disconnected"
            }
            
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            if (capabilities == null) {
                return "unknown"
            }
            
            return when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "bluetooth"
                else -> "other"
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error getting network type", e)
            "error"
        }
    }
    
    /**
     * 验证mDNS服务类型格式
     */
    private fun isValidMDNSServiceType(serviceType: String): Boolean {
        // Android NSD 常见格式：_service._protocol.
        // 服务端广播通常是：_service._protocol.local.
        if (serviceType.isBlank()) {
            Log.e(TAG, "Service type is blank")
            return false
        }
        
        if (!serviceType.startsWith("_")) {
            Log.e(TAG, "Service type should start with underscore: $serviceType")
            return false
        }
        
        val normalized = serviceType.trim()
        val ok = normalized.endsWith(".") && (normalized.contains("._tcp") || normalized.contains("._udp"))
        if (!ok) {
            Log.e(TAG, "Service type format invalid: $serviceType")
            return false
        }
        
        Log.d(TAG, "Service type validation passed: $serviceType")
        return true
    }
    
    /**
     * 检查mDNS服务是否可用
     */
    private fun isMDNSServiceAvailable(): Boolean {
        return try {
            // 尝试简单的服务类型验证
            val testServiceType = "_http._tcp.local."
            if (!isValidMDNSServiceType(testServiceType)) {
                Log.w(TAG, "Basic mDNS service type validation failed")
                return false
            }
            
            // 检查NsdManager是否可用
            if (mdnsManager == null) {
                Log.w(TAG, "NsdManager is null")
                return false
            }
            
            // 尝试创建一个临时的监听器来测试服务可用性
            val testListener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {}
                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
                override fun onServiceFound(serviceInfo: NsdServiceInfo) {}
                override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            }
            
            // 如果能够成功执行此操作，说明mDNS服务基本可用
            true
        } catch (e: Exception) {
            Log.w(TAG, "mDNS service not available: ${e.message}")
            false
        }
    }
}