package com.hawky.nascraft

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun NativeAuthScreen(
    repository: DeviceAuthRepository,
    coordinator: NativeAuthCoordinator,
    nativeBusiness: NativeBusinessClient,
    albumUploadManager: AlbumUploadManager,
    discoveryManager: DiscoveryManager,
    initialState: DeviceAuthState,
    initialNotice: String? = null,
    returnSignal: Int,
    onStateChanged: (DeviceAuthState) -> Unit,
    onOpenHome: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as Activity
    val scope = rememberCoroutineScope()
    val scanner = remember(context) { GmsBarcodeScanning.getClient(context) }
    var state by remember { mutableStateOf(initialState) }
    var displayName by remember { mutableStateOf(state.profile()?.displayName.orEmpty()) }
    var rpId by remember { mutableStateOf(state.profile()?.rpId.orEmpty()) }
    var lanWebUrl by remember { mutableStateOf(state.profile()?.lanWebUrl.orEmpty()) }
    var lanApiOrigin by remember { mutableStateOf(state.profile()?.lanApiOrigin.orEmpty()) }
    var remoteApiOrigin by remember { mutableStateOf(state.profile()?.remoteApiOrigin.orEmpty()) }
    var serverId by remember { mutableStateOf(state.profile()?.serverId.orEmpty()) }
    var qrText by remember { mutableStateOf("") }
    var deviceName by remember { mutableStateOf(Build.MODEL.take(80)) }
    var otp by remember { mutableStateOf("") }
    var deviceMode by remember { mutableStateOf("personal") }
    var realm by remember { mutableStateOf(ApiRealm.LAN_NATIVE) }
    var pendingRemoteProfile by remember { mutableStateOf<ServerProfile?>(null) }
    var busy by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf(initialNotice) }
    var discovered by remember { mutableStateOf(discoveryManager.discoveredServers.value) }

    fun syncState() {
        state = runCatching { repository.snapshot() }.getOrDefault(DeviceAuthState())
        state.profile()?.let { profile ->
            displayName = profile.displayName
            rpId = profile.rpId
            lanWebUrl = profile.lanWebUrl
            lanApiOrigin = profile.lanApiOrigin
            remoteApiOrigin = profile.remoteApiOrigin
            serverId = profile.serverId
        }
        onStateChanged(state)
    }

    suspend fun runFlow(action: suspend () -> Unit) {
        val hadSession = runCatching { repository.snapshot().session != null }.getOrDefault(false)
        busy = true
        errorMessage = null
        notice = null
        try {
            action()
            syncState()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            errorMessage = when (failure) {
                is ServerIdentityMismatchException -> failure.message
                is ServerProfileConflictException -> failure.message
                is IllegalArgumentException -> failure.message
                is NativeApiException -> failure.message
                else -> "连接暂时无法完成。请检查当前网络、服务器 HTTPS 地址和证书后重试。"
            }
            syncState()
        } finally {
            if (hadSession && repository.snapshot().session == null) {
                albumUploadManager.stopAlbumUpload()
                albumUploadManager.clearSensitiveLocalCache()
            }
            busy = false
        }
    }

    val autoBackupPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val allGranted = result.isNotEmpty() && result.values.all { it }
        repository.setAutoBackupEnabled(allGranted)
        syncState()
        if (!allGranted) notice = "相册权限未授予；连接保持有效，自动备份仍关闭。"
        else {
            notice = "相册权限已授予。已启动一次安全的相册备份扫描。"
            repository.snapshot().profile()?.let { profile -> albumUploadManager.startAlbumUpload(profile.apiOrigin(realm)) }
        }
    }

    LaunchedEffect(discoveryManager) {
        discoveryManager.discoveredServers.collect { discovered = it }
    }

    LaunchedEffect(realm, nativeBusiness) {
        nativeBusiness.setRealm(realm)
    }

    LaunchedEffect(state.selectedServerId, state.profile()?.remoteApiOrigin) {
        if (state.profile()?.hasRemoteApiOrigin() != true) {
            realm = ApiRealm.LAN_NATIVE
            nativeBusiness.setRealm(ApiRealm.LAN_NATIVE)
        }
    }

    LaunchedEffect(state.session?.sessionId, state.autoBackupEnabled, realm) {
        if (state.session != null && state.autoBackupEnabled && !albumUploadManager.isUploading()) {
            val hasPermissions = albumUploadManager.getRequiredPermissions().all {
                ContextCompat.checkSelfPermission(context, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
            }
            if (hasPermissions) {
                state.profile()?.let { profile -> albumUploadManager.startAlbumUpload(profile.apiOrigin(realm)) }
            } else {
                repository.setAutoBackupEnabled(false)
                syncState()
                notice = "相册权限已失效；自动备份已关闭。可在此重新开启并授权。"
            }
        }
    }

    LaunchedEffect(returnSignal) {
        if (returnSignal > 0) {
            val profile = repository.snapshot().profile()
            val pending = repository.snapshot().pendingFlow
            if (profile != null && pending?.kind == PendingNativeFlow.Kind.PAIRING && pending.requestId != null) {
                runFlow {
                    coordinator.tryClaimPairing(profile, realm)
                    notice = "设备确认已由服务器验证，连接完成。"
                }
            }
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("连接 NASCraft", style = MaterialTheme.typography.headlineSmall)
            Text(
                "服务器 HTTPS 域名、LAN 与 Tailscale API 地址会固定绑定到同一个服务器身份。Tailscale 地址可稍后设置；局域网发现只提供地址提示，不会替换配置域名。",
                style = MaterialTheme.typography.bodyMedium,
            )

            if (state.session != null) {
                val session = state.session!!
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("已连接家庭 NAS", style = MaterialTheme.typography.titleLarge)
                        Text("账户：${session.userDisplayName}")
                        Text("设备：${session.deviceName}")
                        Text("设备类型：${if (session.deviceMode == "personal") "个人设备" else "临时使用"}")
                        Text("服务器：${state.profile()?.displayName.orEmpty()}")
                        Text("当前连接入口：${realmLabel(realm)}。网络不可达不能单独证明 Tailscale 未启动；可手动切换入口后重试。")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = realm == ApiRealm.LAN_NATIVE,
                                onClick = { realm = ApiRealm.LAN_NATIVE },
                                label = { Text("LAN HTTPS") },
                            )
                            FilterChip(
                                selected = realm == ApiRealm.REMOTE_API,
                                onClick = { realm = ApiRealm.REMOTE_API },
                                label = { Text("Tailscale HTTPS") },
                                enabled = state.profile()?.hasRemoteApiOrigin() == true,
                            )
                        }
                        if (state.profile()?.hasRemoteApiOrigin() != true) {
                            Text("此服务器尚未配置 Tailscale API 地址。LAN 登录和文件访问仍可使用；远程入口已停用。", style = MaterialTheme.typography.bodySmall)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onOpenHome, enabled = !busy) { Text("浏览文件和媒体") }
                            Button(onClick = {
                                scope.launch { runFlow { coordinator.refreshSession(state.profile() ?: error("请先配置服务器"), realm) } }
                            }, enabled = !busy) { Text("安全续期") }
                            Button(onClick = {
                                scope.launch {
                                    busy = true
                                    errorMessage = null
                                    try {
                                        coordinator.logout(state.profile() ?: error("请先配置服务器"), realm)
                                        notice = "已退出此设备的 NASCraft 会话。设备授权仍保留。"
                                    } catch (_: Exception) {
                                        coordinator.logoutLocally()
                                        notice = "本机已退出；服务器端登出未确认，远端会话可能保留到自然到期。"
                                    } finally {
                                        albumUploadManager.stopAlbumUpload()
                                        albumUploadManager.clearSensitiveLocalCache()
                                        syncState()
                                        busy = false
                                    }
                                }
                            }, enabled = !busy) { Text("退出此设备") }
                        }
                        Button(onClick = {
                            scope.launch {
                                runFlow {
                                    val profile = state.profile() ?: error("请先配置服务器")
                                    when (coordinator.loginWithPasskey(profile, realm, activity)) {
                                        is NativePasskeyResult.Success -> notice = "通行密钥已验证；服务器会话和安全请求挑战已更新。"
                                        NativePasskeyResult.Cancelled -> notice = "已取消通行密钥验证；当前连接仍保留。"
                                        NativePasskeyResult.Unsupported -> errorMessage = "此设备不支持通行密钥；请切换到 LAN 配对或先升级凭据提供方。"
                                        NativePasskeyResult.NoPasskey -> errorMessage = "此设备没有可用通行密钥；当前会话仍保留。"
                                        is NativePasskeyResult.Failed -> errorMessage = "通行密钥验证未完成。"
                                    }
                                }
                            }
                        }, enabled = !busy && state.installationId != null) { Text("重新验证通行密钥") }
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text("开启自动备份（默认关闭）", modifier = Modifier.weight(1f))
                            Switch(
                                checked = state.autoBackupEnabled,
                                onCheckedChange = { enabled ->
                                    if (!enabled) {
                                        repository.setAutoBackupEnabled(false)
                                        albumUploadManager.stopAlbumUpload()
                                        syncState()
                                    } else {
                                        val permissions = albumUploadManager.getRequiredPermissions()
                                        autoBackupPermissionLauncher.launch(permissions)
                                    }
                                },
                            )
                        }
                    }
                }
                Text("文件浏览、上传和媒体请求会使用所选 HTTPS 入口及设备签名。", style = MaterialTheme.typography.bodySmall)
            } else {
                state.profile()?.let { profile ->
                    Text("当前家庭 NAS：${profile.displayName} · ${profile.serverId}")
                }

                SectionCard("服务器配置") {
                    state.profiles.forEach { profile ->
                        Button(
                            onClick = {
                                repository.selectProfile(profile.serverId)
                                syncState()
                            },
                            enabled = !busy,
                        ) { Text("选择 ${profile.displayName}") }
                    }
                    Field("服务器身份（由 NAS 配置/二维码提供）", serverId, { serverId = it })
                    Field("显示名称", displayName, { displayName = it })
                    Field("固定 Passkey 域名（RP ID）", rpId, { rpId = it })
                    Field("LAN Web HTTPS Origin", lanWebUrl, { lanWebUrl = it })
                    Field("LAN API HTTPS Origin", lanApiOrigin, { lanApiOrigin = it })
                    Field("Tailscale API HTTPS Origin（可稍后配置）", remoteApiOrigin, { remoteApiOrigin = it })
                    if (state.profile()?.serverId == serverId && state.profile()?.hasRemoteApiOrigin() != true) {
                        Text("保存后可以通过当前已固定的 LAN HTTPS 入口确认并添加远程地址。", style = MaterialTheme.typography.bodySmall)
                    }
                    Button(onClick = {
                        scope.launch {
                            runFlow {
                                val profile = ServerProfile.validated(serverId, displayName, rpId, lanWebUrl, lanApiOrigin, remoteApiOrigin)
                                val existing = state.profiles.firstOrNull { it.serverId == profile.serverId }
                                if (existing == null || existing == profile) {
                                    repository.addProfile(profile)
                                    notice = "服务器身份已固定保存。"
                                } else if (existing.copy(remoteApiOrigin = profile.remoteApiOrigin) == profile && existing.remoteApiOrigin != profile.remoteApiOrigin) {
                                    if (state.selectedServerId != existing.serverId) {
                                        repository.selectProfile(existing.serverId)
                                        syncState()
                                    }
                                    pendingRemoteProfile = profile
                                } else {
                                    throw ServerProfileConflictException("服务器身份已绑定其他 HTTPS 配置；只能通过已验证的 LAN 入口确认远程地址变更。")
                                }
                            }
                        }
                    }, enabled = !busy) { Text("验证并保存服务器") }
                }

                SectionCard("导入服务器二维码") {
                    Field("二维码内容（也可扫描）", qrText, { qrText = it })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            scanner.startScan()
                                .addOnSuccessListener { barcode -> qrText = barcode.rawValue.orEmpty() }
                                .addOnCanceledListener { notice = "已取消二维码扫描。" }
                                .addOnFailureListener { errorMessage = "二维码扫描不可用；可以粘贴二维码内容或手动填写。" }
                        }, enabled = !busy) { Text("扫描二维码") }
                        Button(onClick = {
                            scope.launch {
                                runFlow {
                                    val profile = ServerProfile.fromQrPayload(qrText)
                                    val existing = repository.snapshot().profiles.firstOrNull { it.serverId == profile.serverId }
                                    if (existing == null || existing == profile) {
                                        repository.addProfile(profile)
                                        notice = "二维码中的完整服务器身份已验证并保存。"
                                    } else if (existing.copy(remoteApiOrigin = profile.remoteApiOrigin) == profile && existing.remoteApiOrigin != profile.remoteApiOrigin) {
                                        if (repository.snapshot().selectedServerId != existing.serverId) {
                                            repository.selectProfile(existing.serverId)
                                            syncState()
                                        }
                                        pendingRemoteProfile = profile
                                    } else {
                                        throw ServerProfileConflictException("二维码中的服务器身份与已固定配置不符。只能通过已验证的 LAN 入口确认远程地址变更。")
                                    }
                                }
                            }
                        }, enabled = !busy && qrText.isNotBlank()) { Text("导入") }
                    }
                }

                SectionCard("局域网发现（仅地址提示）") {
                    Text("发现结果不含可信 server_id 或 HTTPS 身份，不能直接用于登录、配对或上传。")
                    Button(onClick = {
                        discoveryManager.clearDiscoveredServers()
                        discoveryManager.startDiscovery()
                        discoveryManager.startMDNSDiscovery()
                        discoveryManager.startSSDPDiscovery()
                    }, enabled = !busy) { Text("搜索局域网") }
                    discovered.forEach { candidate ->
                        Text("${candidate.name} · ${candidate.ip.hostAddress}:${candidate.port}（未验证提示）")
                    }
                }

                SectionCard("连接与认证") {
                    Field("此设备名称", deviceName, { deviceName = it })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = deviceMode == "personal", onClick = { deviceMode = "personal" }, label = { Text("个人设备") })
                        FilterChip(selected = deviceMode == "temporary", onClick = { deviceMode = "temporary" }, label = { Text("临时使用") })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = realm == ApiRealm.LAN_NATIVE, onClick = { realm = ApiRealm.LAN_NATIVE }, label = { Text("LAN HTTPS") })
                        FilterChip(
                            selected = realm == ApiRealm.REMOTE_API,
                            onClick = { realm = ApiRealm.REMOTE_API },
                            label = { Text("Tailscale HTTPS") },
                            enabled = state.profile()?.hasRemoteApiOrigin() == true,
                        )
                    }
                    if (state.profile()?.hasRemoteApiOrigin() != true) {
                        Text("尚未配置 Tailscale API 地址。远程登录和注册码兑换已停用；你可以先通过 LAN 使用服务器。", style = MaterialTheme.typography.bodySmall)
                    }
                    Button(onClick = {
                        scope.launch {
                            runFlow {
                                val profile = repository.snapshot().profile() ?: throw IllegalStateException("先验证并保存服务器配置")
                                require(realm == ApiRealm.LAN_NATIVE) { "首次设备配对必须连接配置的 LAN HTTPS API" }
                                coordinator.startPairing(profile, deviceName, deviceMode)
                                notice = "请求已提交。网页确认后回到 NASCraft 领取连接。"
                            }
                        }
                    }, enabled = !busy && state.profile() != null) { Text("在 LAN 发起设备配对") }

                    Field(
                        "一次性注册码",
                        otp,
                        { otp = it },
                        keyboardType = KeyboardType.Ascii,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    if (!NativePasskeySupport.isSupportedPlatform()) {
                        Text("此 Android 版本无法创建原生通行密钥。不会兑换注册码；请连接 LAN 并使用设备配对。", color = MaterialTheme.colorScheme.error)
                    }
                    Button(onClick = {
                        scope.launch {
                            runFlow {
                                val profile = repository.snapshot().profile() ?: throw IllegalStateException("先验证并保存服务器配置")
                                require(realm == ApiRealm.REMOTE_API) { "一次性注册码必须通过 Tailscale HTTPS API 使用" }
                                require(profile.hasRemoteApiOrigin()) { "请先配置 Tailscale API HTTPS Origin；当前服务器仍可通过 LAN 使用。" }
                                val flow = coordinator.redeemRemoteOtp(profile, deviceName, otp)
                                otp = ""
                                notice = "注册码已兑换为服务器授权的${deviceModeLabel(flow.mode)}注册事务；此时尚未登录或获得文件权限。"
                            }
                        }
                    }, enabled = !busy && state.profile()?.hasRemoteApiOrigin() == true && NativePasskeySupport.isSupportedPlatform()) { Text("使用注册码创建通行密钥") }

                    if (state.installationId != null && state.profile() != null) {
                        Button(onClick = {
                            scope.launch {
                                val profile = repository.snapshot().profile() ?: return@launch
                                busy = true
                                errorMessage = null
                                try {
                                    when (coordinator.loginWithPasskey(profile, realm, activity)) {
                                        is NativePasskeyResult.Success -> notice = "通行密钥已由系统验证，服务器已建立设备会话。"
                                        NativePasskeyResult.Cancelled -> notice = "已取消系统通行密钥验证。原连接信息仍保留。"
                                        NativePasskeyResult.Unsupported -> errorMessage = "此 Android 版本或凭据提供方不支持通行密钥；请在 LAN 完成设备配对。"
                                        NativePasskeyResult.NoPasskey -> errorMessage = "此设备没有可用的家庭 NAS 通行密钥；请回到 LAN 配对或使用 LAN 签发的注册码。"
                                        is NativePasskeyResult.Failed -> errorMessage = "通行密钥验证未完成。请检查设备凭据提供方后重试。"
                                    }
                                    syncState()
                                } catch (failure: Exception) {
                                    errorMessage = failure.message ?: "无法完成安全登录。"
                                    syncState()
                                } finally { busy = false }
                            }
                        }, enabled = !busy) { Text("使用此设备的通行密钥登录") }
                    }
                }

                state.pendingFlow?.let { flow ->
                    SectionCard("继续未完成的连接") {
                        Text("${flow.deviceName} · ${deviceModeLabel(flow.mode)}")
                        when (flow.kind) {
                            PendingNativeFlow.Kind.PAIRING -> {
                                Text("同一手机不需要输入配对码。网页链接只定位请求，仍需网页登录、核对账户并明确授权。")
                                if (flow.requestId == null) {
                                    Text("应用恢复时没有收到服务器定位码。重试会创建新的短时请求；服务器已收到的旧请求不会被自动批准，并会自行过期。")
                                    Button(onClick = {
                                        scope.launch {
                                            runFlow {
                                                val profile = repository.snapshot().profile() ?: error("服务器配置不存在")
                                                coordinator.resumePairing(profile)
                                                notice = "新的配对请求已创建。请在 LAN Web 登录并明确批准。"
                                            }
                                        }
                                    }, enabled = !busy) { Text("重试创建配对请求") }
                                }
                                flow.humanCode?.let { Text("跨设备定位码：$it") }
                                flow.webUrl?.let { url ->
                                    Text("LAN Web 地址：$url", style = MaterialTheme.typography.bodySmall)
                                    Button(onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText("NASCraft pairing", url))
                                        notice = "LAN Web 地址已复制。它只定位请求，不代表已授权。"
                                    }) { Text("复制网页地址") }
                                }
                                Button(onClick = {
                                    val url = coordinator.pairingWebUrl(flow)
                                    if (url == null) errorMessage = "连接请求已过期，请重新开始。"
                                    else try {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                    } catch (_: Exception) {
                                        errorMessage = "无法自动打开浏览器。请手动打开 NASCraft LAN Web；请求仍然保留。"
                                    }
                                }, enabled = !busy && flow.webUrl != null) { Text("在浏览器继续") }
                                Button(onClick = {
                                    scope.launch {
                                        runFlow {
                                            val profile = repository.snapshot().profile() ?: error("服务器配置不存在")
                                            coordinator.tryClaimPairing(profile, realm)
                                            notice = "设备确认已由服务器验证，连接完成。"
                                        }
                                    }
                                }, enabled = !busy && flow.requestId != null) { Text("我已完成网页确认，检查连接") }
                                Button(onClick = {
                                    scope.launch {
                                        runFlow {
                                            val profile = repository.snapshot().profile() ?: error("服务器配置不存在")
                                            coordinator.cancelPairing(profile, realm)
                                            notice = if (flow.requestId == null) {
                                                "已停止本地请求。若服务器已收到请求，它会在短时到期后失效；设备尚未获得授权。"
                                            } else {
                                                "待确认请求已取消。"
                                            }
                                        }
                                    }
                                }, enabled = !busy) { Text(if (flow.requestId == null) "停止本地请求" else "取消待确认请求") }
                            }
                            PendingNativeFlow.Kind.REMOTE_OTP -> {
                                val account = runCatching { JSONObject(flow.publicKeyJson ?: "{}").optJSONObject("user")?.optString("displayName") }.getOrNull()
                                Text("${account?.takeIf { !it.isNullOrBlank() }?.let { "目标账户：$it。" }.orEmpty()}注册码不会直接登录；必须由系统真实创建通行密钥并完成服务器验证。")
                                if (flow.registrationId == null) {
                                    Text("正在通过 Tailscale HTTPS 兑换注册码。网络失败时可以继续同一事务，不能把注册码当作登录凭据。")
                                    Button(onClick = {
                                        scope.launch {
                                            runFlow {
                                                coordinator.resumeRemoteRegistration()
                                                notice = "已继续请求服务器的限时通行密钥注册事务。"
                                            }
                                        }
                                    }, enabled = !busy && flow.otp != null) { Text("继续兑换注册码") }
                                    Button(onClick = {
                                        scope.launch {
                                            runFlow {
                                                val profile = repository.snapshot().profile() ?: error("服务器配置不存在")
                                                coordinator.abandonRemoteRegistration(profile)
                                                otp = ""
                                                notice = "已放弃远程注册并清除本机密钥。服务器限时事务会自然过期；现在可以改用 LAN 配对或申请新码。"
                                            }
                                        }
                                    }, enabled = !busy) { Text("放弃本次兑换") }
                                }
                                if (flow.registrationId != null && flow.credentialJson == null) {
                                    Button(onClick = {
                                        scope.launch {
                                            runFlow {
                                                val profile = repository.snapshot().profile() ?: error("服务器配置不存在")
                                                coordinator.abandonRemoteRegistration(profile)
                                                otp = ""
                                                notice = "已放弃限时远程注册。服务器事务会自然过期；现在可以改用 LAN 配对。"
                                            }
                                        }
                                    }, enabled = !busy) { Text("放弃并改用 LAN 配对") }
                                }
                                Button(onClick = {
                                    scope.launch {
                                        busy = true
                                        errorMessage = null
                                        try {
                                            when (coordinator.createAndFinishRemotePasskey(activity)) {
                                                is NativePasskeyResult.Success -> notice = "系统通行密钥创建并经服务器验证，设备现已连接。"
                                                NativePasskeyResult.Cancelled -> notice = "已取消系统通行密钥创建。限时注册事务仍可继续。"
                                                NativePasskeyResult.Unsupported -> errorMessage = "此设备不支持 Credential Manager 通行密钥创建；请使用 LAN 配对。"
                                                is NativePasskeyResult.Failed -> errorMessage = "通行密钥创建未完成。注册码不会被当作登录凭据。"
                                                NativePasskeyResult.NoPasskey -> errorMessage = "当前操作需要创建新通行密钥；请检查 Android 凭据提供方。"
                                            }
                                            syncState()
                                        } catch (failure: Exception) {
                                            errorMessage = failure.message ?: "远程注册未完成。"
                                            syncState()
                                        } finally { busy = false }
                                    }
                                }, enabled = !busy && flow.registrationId != null) { Text("创建通行密钥并连接") }
                                if (flow.credentialJson != null) {
                                    Button(onClick = {
                                        scope.launch {
                                            runFlow {
                                                val profile = repository.snapshot().profile() ?: error("服务器配置不存在")
                                                coordinator.recoverRemoteRegistration(profile, flow)
                                                notice = "已从服务器恢复原注册事务。"
                                            }
                                        }
                                    }, enabled = !busy) { Text("恢复服务器确认结果") }
                                }
                                Button(onClick = {
                                    scope.launch {
                                        runFlow {
                                            val profile = repository.snapshot().profile() ?: error("服务器配置不存在")
                                            coordinator.recoverRemoteRegistration(profile, flow)
                                                ?: throw IllegalStateException("注册尚未完成；有效期内可以继续创建通行密钥")
                                        }
                                    }
                                }, enabled = !busy && flow.registrationId != null) { Text("检查注册状态") }
                            }
                            PendingNativeFlow.Kind.LOGIN -> Text("系统通行密钥流程已保存。重新验证会继续使用当前设备和服务器。")
                        }
                    }
                }
            }

            if (busy) CircularProgressIndicator()
            errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            Spacer(Modifier.height(16.dp))
        }
    }
    pendingRemoteProfile?.let { candidate ->
        AlertDialog(
            onDismissRequest = { if (!busy) pendingRemoteProfile = null },
            title = { Text("确认远程服务器地址") },
            text = {
                Text("将通过当前已固定的 LAN HTTPS 服务器核对 server_id 后，把 Tailscale API 地址固定为：\n${candidate.remoteApiOrigin.ifBlank { "（移除远程入口）" }}\n\n此操作只更新远程地址，LAN HTTPS、LAN Web 和 Passkey 域名保持不变。")
            },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        val profile = pendingRemoteProfile ?: return@launch
                        pendingRemoteProfile = null
                        runFlow {
                            coordinator.confirmRemoteApiOrigin(profile)
                            notice = "已通过可信 LAN 入口确认并固定 Tailscale API 地址。"
                        }
                    }
                }, enabled = !busy && realm == ApiRealm.LAN_NATIVE) { Text("通过 LAN 验证并固定") }
            },
            dismissButton = {
                Button(onClick = { pendingRemoteProfile = null }, enabled = !busy) { Text("取消") }
            },
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        visualTransformation = visualTransformation,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
}

private fun realmLabel(realm: ApiRealm): String = when (realm) {
    ApiRealm.LAN_NATIVE -> "LAN HTTPS"
    ApiRealm.REMOTE_API -> "Tailscale HTTPS"
}

private fun deviceModeLabel(mode: String): String = when (mode) {
    "personal" -> "个人设备"
    "temporary" -> "临时使用"
    else -> "等待服务器授权类型"
}
