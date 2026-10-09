package com.hawky.nascraft

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import kotlinx.coroutines.launch

/** 本地相册：按上传状态筛选 */
enum class UploadFilter { ALL, UPLOADED, NOT_UPLOADED }

/** 本地相册：按媒体类型筛选 */
enum class LocalTypeFilter { ALL, IMAGE, VIDEO }

private fun PhotoInfo.isVideo() = mimeType.startsWith("video/", ignoreCase = true)
private fun PhotoInfo.isImage() = mimeType.startsWith("image/", ignoreCase = true)

/**
 * 本地相册页面：展示手机本地所有图片与视频。
 * 已上传（MD5 命中云端 status=2 文件）的项右下角打勾；
 * 支持按上传状态（全部/已上传/未上传）与类型（全部/图片/视频）筛选，并显示总文件数。
 */
@Composable
fun LocalAlbumScreen(
    albumUploadManager: AlbumUploadManager,
    fileUploadManager: FileUploadManager,
    server: DiscoveredServer
) {
    val baseUrl = "${server.proto}://${server.ip.hostAddress}:${server.port}"
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    // 视频首帧缩略图专用 ImageLoader（注册 VideoFrameDecoder，用 MediaMetadataRetriever 提取视频帧）
    val videoImageLoader = remember(context) {
        ImageLoader.Builder(context)
            .components { add(VideoFrameDecoder.Factory()) }
            .build()
    }

    var localPhotos by remember { mutableStateOf<List<PhotoInfo>>(emptyList()) }
    var uploadedUris by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isLoading by remember { mutableStateOf(false) }
    var hashingCount by remember { mutableIntStateOf(0) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var uploadFilter by remember { mutableStateOf(UploadFilter.ALL) }
    var typeFilter by remember { mutableStateOf(LocalTypeFilter.ALL) }

    // 相册权限状态与请求
    var permissionGranted by remember { mutableStateOf(albumUploadManager.hasRequiredPermissions()) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result.values.all { it }
        permissionGranted = granted
        if (!granted) {
            errorMessage = "需要相册访问权限才能查看本地相册"
        }
    }

    // 加载本地列表 + 拉云端 checksum + 逐个比对 MD5 判定已上传
    suspend fun load() {
        isLoading = true
        errorMessage = null
        hashingCount = 0
        uploadedUris = emptySet()

        // 1. 本地相册（图片 + 视频，按加入时间倒序）
        val photos = albumUploadManager.getAlbumPhotos(UploadMediaType.ALL)
        localPhotos = photos
        if (photos.isEmpty()) {
            isLoading = false
            errorMessage = if (permissionGranted) "本地相册暂无图片或视频" else "需要相册访问权限"
            return
        }

        // 2. 云端已完成（status=2）文件的 checksum 集合，一次性拉全量
        val checksums = fileUploadManager.getUploadedFiles(
            baseUrl,
            page = 1,
            pageSize = 10000,
            sortBy = "id",
            order = "asc"
        )?.files?.map { it.checksum }?.toSet() ?: emptySet()

        // 3. 逐个计算本地文件 MD5（带缓存），命中云端 checksum 即视为已上传。
        //    分批刷新 state（每 20 个），避免 3500+ 次逐条重组拖慢 UI。
        val uploaded = mutableSetOf<String>()
        var processed = 0
        for (photo in photos) {
            val digest = albumUploadManager.getFileDigest(photo)
            if (digest != null && digest.md5 in checksums) {
                uploaded.add(photo.uri)
            }
            processed++
            if (processed % 20 == 0 || processed == photos.size) {
                uploadedUris = uploaded.toSet()
                hashingCount = processed
            }
        }

        isLoading = false
    }

    LaunchedEffect(permissionGranted) {
        if (permissionGranted) {
            load()
        }
    }

    if (!permissionGranted) {
        // 未授权：提示并给授权入口
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Image,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                )
                Text(
                    text = "需要相册访问权限才能查看本地相册",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = {
                        permissionLauncher.launch(albumUploadManager.getRequiredPermissions())
                    }
                ) {
                    Text("授权")
                }
            }
        }
        return
    }

    if (isLoading && localPhotos.isEmpty()) {
        // 首屏加载
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text("正在加载本地相册...")
            }
        }
        return
    }

    if (errorMessage != null && localPhotos.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = errorMessage ?: "",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error
            )
        }
        return
    }

    val uploadedCount = uploadedUris.size
    val notUploadedCount = localPhotos.size - uploadedCount
    val filteredPhotos = localPhotos.filter { photo ->
        val typeOk = when (typeFilter) {
            LocalTypeFilter.ALL -> true
            LocalTypeFilter.IMAGE -> photo.isImage()
            LocalTypeFilter.VIDEO -> photo.isVideo()
        }
        val uploadOk = when (uploadFilter) {
            UploadFilter.ALL -> true
            UploadFilter.UPLOADED -> photo.uri in uploadedUris
            UploadFilter.NOT_UPLOADED -> photo.uri !in uploadedUris
        }
        typeOk && uploadOk
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 统计 + 筛选工具栏
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                // 第一行：统计信息 + 刷新
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LocalStat(
                            label = "总文件数",
                            value = "${localPhotos.size}",
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(modifier = Modifier.width(24.dp))
                        LocalStat(
                            label = "已上传",
                            value = "$uploadedCount",
                            color = Color(0xFF2E7D32)
                        )
                        Spacer(modifier = Modifier.width(24.dp))
                        LocalStat(
                            label = "未上传",
                            value = "$notUploadedCount",
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                    IconButton(
                        onClick = { coroutineScope.launch { load() } },
                        enabled = !isLoading
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                }

                // 比对进度提示
                if (isLoading && localPhotos.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "正在比对已上传状态 $hashingCount/${localPhotos.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 第二行：上传状态筛选
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LocalFilterChip("全部", uploadFilter == UploadFilter.ALL) {
                        uploadFilter = UploadFilter.ALL
                    }
                    LocalFilterChip("已上传", uploadFilter == UploadFilter.UPLOADED) {
                        uploadFilter = UploadFilter.UPLOADED
                    }
                    LocalFilterChip("未上传", uploadFilter == UploadFilter.NOT_UPLOADED) {
                        uploadFilter = UploadFilter.NOT_UPLOADED
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 第三行：类型筛选
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LocalFilterChip("全部类型", typeFilter == LocalTypeFilter.ALL) {
                        typeFilter = LocalTypeFilter.ALL
                    }
                    LocalFilterChip("图片", typeFilter == LocalTypeFilter.IMAGE) {
                        typeFilter = LocalTypeFilter.IMAGE
                    }
                    LocalFilterChip("视频", typeFilter == LocalTypeFilter.VIDEO) {
                        typeFilter = LocalTypeFilter.VIDEO
                    }
                }
            }
        }

        // 网格
        if (filteredPhotos.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "没有符合条件的文件",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp)
            ) {
                items(filteredPhotos, key = { it.uri }) { photo ->
                    LocalPhotoCell(
                        photo = photo,
                        isUploaded = photo.uri in uploadedUris,
                        videoImageLoader = videoImageLoader
                    )
                }
            }
        }
    }
}

/**
 * 本地相册单个网格项：图片/视频均显示缩略图（视频取首帧），已上传则右下角打勾。
 */
@Composable
private fun LocalPhotoCell(photo: PhotoInfo, isUploaded: Boolean, videoImageLoader: ImageLoader) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (photo.isImage()) {
            AsyncImage(
                model = photo.uri,
                contentDescription = photo.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            // 视频：首帧缩略图 + 左上角播放标识
            AsyncImage(
                model = photo.uri,
                contentDescription = photo.name,
                imageLoader = videoImageLoader,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            Icon(
                imageVector = Icons.Default.PlayCircle,
                contentDescription = "视频",
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .size(20.dp)
            )
        }

        // 已上传：右下角绿勾
        if (isUploaded) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = "已上传",
                tint = Color(0xFF2E7D32),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .size(22.dp)
                    .background(Color.White.copy(alpha = 0.9f), CircleShape)
            )
        }
    }
}

/**
 * 统计信息块（标签 + 数值）
 */
@Composable
private fun LocalStat(label: String, value: String, color: Color) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = color.copy(alpha = 0.7f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

/**
 * 本地相册筛选单选 chip
 */
@Composable
private fun LocalFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer
        )
    )
}
