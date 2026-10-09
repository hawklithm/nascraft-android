package com.hawky.nascraft

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Refresh
import coil.compose.AsyncImage
import com.google.accompanist.pager.ExperimentalPagerApi
import com.google.accompanist.pager.HorizontalPager
import com.google.accompanist.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.widget.Toast
import kotlinx.coroutines.flow.distinctUntilChanged
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
    val gridState = rememberLazyGridState()
    val baseUrl = "${server.proto}://${server.ip.hostAddress}:${server.port}"

    var isLoading by remember { mutableStateOf(true) }
    var uploadedFiles by remember { mutableStateOf<List<UploadedFile>>(emptyList()) }
    var totalFiles by remember { mutableIntStateOf(0) }
    var totalSize by remember { mutableStateOf(0L) }
    var currentPage by remember { mutableIntStateOf(1) }
    var hasMore by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Image preview state - for swipeable gallery（仅图片可全屏预览，视频不进图片预览）
    var showImagePreview by remember { mutableStateOf(false) }
    var initialPreviewIndex by remember { mutableIntStateOf(0) }
    val previewableFiles = remember(uploadedFiles) {
        uploadedFiles.filter { isImageFile(it.filename) && !it.thumbnailUrl.isNullOrEmpty() }
    }

    // DLNA cast state
    var showCastDeviceSelection by remember { mutableStateOf(false) }
    var selectedFileForCast by remember { mutableStateOf<UploadedFile?>(null) }
    var castDevices by remember { mutableStateOf<List<Pair<DlnaRenderer, PlaybackInfo>>>(emptyList()) }
    var castLoading by remember { mutableStateOf(false) }

    // 展示方式与详情浮层状态
    var viewMode by remember { mutableStateOf(ViewMode.LIST) }
    var selectedFileForDetail by remember { mutableStateOf<UploadedFile?>(null) }

    // 排序状态：sortBy = "id"(上传时间) | "taken_at"(拍摄时间)
    var sortBy by remember { mutableStateOf("id") }
    var order by remember { mutableStateOf("asc") }
    var showSortMenu by remember { mutableStateOf(false) }

    // 来源筛选状态
    var sourceFilter by remember { mutableStateOf<String?>(null) }
    var availableSources by remember { mutableStateOf<List<String>>(emptyList()) }
    var showSourceFilter by remember { mutableStateOf(false) }

    // 媒体类型筛选状态：null=全部, "image"/"video"/"other"
    var mediaTypeFilter by remember { mutableStateOf<String?>(null) }
    var showMediaTypeFilter by remember { mutableStateOf(false) }

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
        val response = fileUploadManager.getUploadedFiles(
            baseUrl,
            page = currentPage,
            pageSize = 20,
            sortBy = sortBy,
            order = order,
            sourceDevice = sourceFilter,
            mediaType = mediaTypeFilter
        )

        if (response != null) {
            if (refresh) {
                uploadedFiles = response.files
            } else {
                // 按 fileId 去重：排序字段（拍摄时间 taken_at）在后台异步解析期间会持续变化，
                // 分页结果可能发生漂移，后端返回的下一页里可能包含已在列表中的文件，
                // 去重避免 LazyColumn/LazyGrid 的 item key 冲突崩溃（"Key was already used"）
                val existingIds = uploadedFiles.mapTo(HashSet()) { it.fileId }
                val newFiles = response.files.filter { it.fileId !in existingIds }
                uploadedFiles = uploadedFiles + newFiles
            }
            totalFiles = response.totalFiles
            totalSize = response.totalSize
            hasMore = uploadedFiles.size < totalFiles && response.files.isNotEmpty()
            currentPage++
        } else {
            errorMessage = "加载失败，请重试"
        }

        isLoading = false
    }

    // 自动检测滚动到底部：用 snapshotFlow 持续观察滚动位置。
    // 不能用 LaunchedEffect(shouldLoadMore)（布尔为 key）：isLoading 置 true 后
    // 列表底部会多出 loading item，totalItemsCount 变化会让 shouldLoadMore 从 true
    // 掉回 false，从而取消正在执行的 loadFiles 协程，isLoading 卡死在 true。
    LaunchedEffect(Unit) {
        snapshotFlow {
            val totalCount: Int
            val lastIndex: Int
            if (viewMode == ViewMode.LIST) {
                val info = listState.layoutInfo
                totalCount = info.totalItemsCount
                lastIndex = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            } else {
                val info = gridState.layoutInfo
                totalCount = info.totalItemsCount
                lastIndex = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            }
            Triple(viewMode, totalCount, lastIndex)
        }
            .distinctUntilChanged()
            .collect { (_, totalCount, lastIndex) ->
                if (totalCount > 0 && lastIndex >= totalCount - 1 && !isLoading && hasMore) {
                    loadFiles(refresh = false)
                }
            }
    }

    // 初始加载
    LaunchedEffect(Unit) {
        loadFiles(refresh = true)
        fileUploadManager.getSources(baseUrl)?.let { availableSources = it }
    }

    // 打开投屏设备选择
    val openCastSelection: (UploadedFile) -> Unit = { file ->
        coroutineScope.launch {
            castLoading = true
            val devices = dlnaManager.listRenderers(baseUrl)
            if (devices != null) {
                castDevices = devices
                selectedFileForCast = file
                showCastDeviceSelection = true
            } else {
                Toast.makeText(context, "获取设备列表失败", Toast.LENGTH_SHORT).show()
            }
            castLoading = false
        }
    }

    // 打开全屏图片预览
    val openPreview: (UploadedFile) -> Unit = { file ->
        val index = previewableFiles.indexOfFirst { it.fileId == file.fileId }
        if (index >= 0) {
            initialPreviewIndex = index
            showImagePreview = true
        }
    }

    // 应用排序方式并刷新
    val applySort: (String, String) -> Unit = { by, ord ->
        showSortMenu = false
        if (sortBy != by || order != ord) {
            sortBy = by
            order = ord
            coroutineScope.launch { loadFiles(refresh = true) }
        }
    }

    // 应用来源筛选并刷新（source == null 表示全部）
    val applySourceFilter: (String?) -> Unit = { source ->
        showSourceFilter = false
        if (sourceFilter != source) {
            sourceFilter = source
            coroutineScope.launch { loadFiles(refresh = true) }
        }
    }

    // 应用媒体类型筛选并刷新（type == null 表示全部）
    val applyMediaTypeFilter: (String?) -> Unit = { type ->
        showMediaTypeFilter = false
        if (mediaTypeFilter != type) {
            mediaTypeFilter = type
            coroutineScope.launch { loadFiles(refresh = true) }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
    ) {
        // 统计信息与过滤工具栏
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
                // 第一行：统计信息 + 视图切换/刷新
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatBlock(
                            label = "总文件数",
                            value = "$totalFiles",
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(modifier = Modifier.width(32.dp))
                        StatBlock(
                            label = "总大小",
                            value = fileUploadManager.formatFileSize(totalSize),
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(modifier = Modifier.width(32.dp))
                        StatBlock(
                            label = "当前显示",
                            value = "${uploadedFiles.size}",
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                viewMode = if (viewMode == ViewMode.LIST) ViewMode.GRID else ViewMode.LIST
                            }
                        ) {
                            Icon(
                                imageVector = if (viewMode == ViewMode.LIST) Icons.Default.GridView else Icons.AutoMirrored.Filled.List,
                                contentDescription = if (viewMode == ViewMode.LIST) "切换为网格视图" else "切换为列表视图"
                            )
                        }
                        IconButton(
                            onClick = {
                                coroutineScope.launch { loadFiles(refresh = true) }
                            },
                            enabled = !isLoading
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 第二行：过滤条件（横向滚动，避免拥挤）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterDropdownButton(
                        label = sourceFilter?.let { "来源 · $it" } ?: "来源 · 全部",
                        icon = Icons.Default.FilterAlt,
                        expanded = showSourceFilter,
                        onToggle = { showSourceFilter = true },
                        onDismiss = { showSourceFilter = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("全部来源") },
                            onClick = { applySourceFilter(null) },
                            trailingIcon = {
                                if (sourceFilter == null) {
                                    Icon(Icons.Default.Check, contentDescription = "已选中")
                                }
                            }
                        )
                        availableSources.forEach { source ->
                            DropdownMenuItem(
                                text = { Text(source) },
                                onClick = { applySourceFilter(source) },
                                trailingIcon = {
                                    if (sourceFilter == source) {
                                        Icon(Icons.Default.Check, contentDescription = "已选中")
                                    }
                                }
                            )
                        }
                    }
                    FilterDropdownButton(
                        label = "类型 · ${mediaTypeLabel(mediaTypeFilter)}",
                        icon = Icons.Default.Movie,
                        expanded = showMediaTypeFilter,
                        onToggle = { showMediaTypeFilter = true },
                        onDismiss = { showMediaTypeFilter = false }
                    ) {
                        MediaTypeFilterItem("全部", null, mediaTypeFilter, applyMediaTypeFilter)
                        MediaTypeFilterItem("图片", "image", mediaTypeFilter, applyMediaTypeFilter)
                        MediaTypeFilterItem("视频", "video", mediaTypeFilter, applyMediaTypeFilter)
                        MediaTypeFilterItem("其他", "other", mediaTypeFilter, applyMediaTypeFilter)
                    }
                    FilterDropdownButton(
                        label = "排序 · ${sortLabel(sortBy, order)}",
                        icon = Icons.AutoMirrored.Filled.Sort,
                        expanded = showSortMenu,
                        onToggle = { showSortMenu = true },
                        onDismiss = { showSortMenu = false }
                    ) {
                        SortOptionItem("上传时间 · 最新在前", "id", "desc", sortBy, order, applySort)
                        SortOptionItem("上传时间 · 最早在前", "id", "asc", sortBy, order, applySort)
                        SortOptionItem("拍摄时间 · 最新在前", "taken_at", "desc", sortBy, order, applySort)
                        SortOptionItem("拍摄时间 · 最早在前", "taken_at", "asc", sortBy, order, applySort)
                    }
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
            when (viewMode) {
                ViewMode.LIST -> {
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
                                onPreviewClick = { openPreview(file) },
                                onCastClick = { openCastSelection(it) }
                            )
                        }

                        // 加载更多指示器
                        if (isLoading && uploadedFiles.isNotEmpty()) {
                            item {
                                LoadingMoreIndicator()
                            }
                        }
                    }
                }

                ViewMode.GRID -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        state = gridState,
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 16.dp,
                            vertical = 16.dp
                        )
                    ) {
                        gridItems(uploadedFiles, key = { it.fileId }) { file ->
                            GridImageCell(
                                file = file,
                                baseUrl = baseUrl,
                                onClick = { selectedFileForDetail = file }
                            )
                        }

                        // 加载更多指示器（跨整行）
                        if (isLoading && uploadedFiles.isNotEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                LoadingMoreIndicator()
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

        // 详情浮层：网格视图点击缩略图后弹出
        selectedFileForDetail?.let { detailFile ->
            FileDetailSheet(
                file = detailFile,
                baseUrl = baseUrl,
                fileUploadManager = fileUploadManager,
                onCastClick = {
                    selectedFileForDetail = null
                    openCastSelection(detailFile)
                },
                onPreviewClick = { openPreview(detailFile) },
                onDismiss = { selectedFileForDetail = null }
            )
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

/**
 * 已上传文件的展示方式
 */
enum class ViewMode { LIST, GRID }

/**
 * 判断文件名是否为图片
 */
fun isImageFile(filename: String): Boolean {
    val lower = filename.lowercase()
    return lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
            lower.endsWith(".png") || lower.endsWith(".gif") ||
            lower.endsWith(".webp") || lower.endsWith(".bmp")
}

/**
 * 网格视图的缩略图格子
 */
@Composable
fun GridImageCell(
    file: UploadedFile,
    baseUrl: String,
    onClick: () -> Unit
) {
    val thumbnailFullUrl = if (file.thumbnailUrl != null) "$baseUrl${file.thumbnailUrl}" else null
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        if (!thumbnailFullUrl.isNullOrEmpty()) {
            AsyncImage(
                model = thumbnailFullUrl,
                contentDescription = file.filename,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Icon(
                imageVector = if (isImageFile(file.filename)) Icons.Default.Image else Icons.Default.Description,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 详情浮层：点击缩略图后弹出，展示大图、详细信息与投屏按钮
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileDetailSheet(
    file: UploadedFile,
    baseUrl: String,
    fileUploadManager: FileUploadManager,
    onCastClick: () -> Unit,
    onPreviewClick: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // 大图：图片用原图（高清预览），视频用第一帧缩略图
            val isImage = isImageFile(file.filename)
            val previewImageUrl = if (isImage) {
                "$baseUrl/api/download/${file.fileId}"
            } else {
                file.thumbnailUrl?.let { "$baseUrl$it" }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (previewImageUrl != null) {
                    AsyncImage(
                        model = previewImageUrl,
                        contentDescription = file.filename,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Description,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = file.filename,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(12.dp))

            DetailRow("大小", fileUploadManager.formatFileSize(file.totalSize))
            DetailRow("状态", fileUploadManager.getStatusText(file.status))
            DetailRow("MD5", file.checksum)
            DetailRow("上传时间", fileUploadManager.formatTimestamp(file.lastUpdated))
            if (file.takenAt > 0) {
                DetailRow("拍摄时间", fileUploadManager.formatTimestamp(file.takenAt))
            }
            if (!file.sourceDevice.isNullOrEmpty()) {
                DetailRow("来源", file.sourceDevice)
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 操作按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (file.status == 2) {
                    Button(
                        onClick = onCastClick,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("投屏到电视")
                    }
                }
                if (onPreviewClick != null && isImageFile(file.filename) && file.thumbnailUrl != null) {
                    Button(
                        onClick = onPreviewClick,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Text("预览")
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * 详情信息行
 */
@Composable
fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            textAlign = androidx.compose.ui.text.style.TextAlign.End
        )
    }
}

/**
 * 加载更多指示器
 */
@Composable
fun LoadingMoreIndicator() {
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

/**
 * 媒体类型筛选菜单项（带当前选中高亮）
 */
@Composable
fun MediaTypeFilterItem(
    label: String,
    type: String?,
    currentType: String?,
    onClick: (String?) -> Unit
) {
    val selected = currentType == type
    DropdownMenuItem(
        text = {
            Text(
                text = label,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
            )
        },
        onClick = { onClick(type) },
        trailingIcon = {
            if (selected) {
                Icon(Icons.Default.Check, contentDescription = "已选中")
            }
        }
    )
}

/**
 * 排序菜单项（带当前选中高亮）
 */
@Composable
fun SortOptionItem(
    label: String,
    by: String,
    ord: String,
    currentBy: String,
    currentOrder: String,
    onClick: (String, String) -> Unit
) {
    val selected = currentBy == by && currentOrder == ord
    DropdownMenuItem(
        text = {
            Text(
                text = label,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
            )
        },
        onClick = { onClick(by, ord) },
        trailingIcon = {
            if (selected) {
                Icon(Icons.Default.Check, contentDescription = "已选中")
            }
        }
    )
}

/**
 * 统计信息块（标签 + 数值）
 */
@Composable
private fun StatBlock(label: String, value: String, color: Color) {
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
 * 过滤条件下拉按钮：带图标 + 当前值文字 + 下拉箭头，点击弹出菜单
 */
@Composable
private fun FilterDropdownButton(
    label: String,
    icon: ImageVector,
    expanded: Boolean,
    onToggle: () -> Unit,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Box {
        OutlinedButton(
            onClick = onToggle,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 12.dp,
                vertical = 8.dp
            )
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Icon(
                Icons.Default.ArrowDropDown,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
            content()
        }
    }
}

/**
 * 媒体类型筛选值 → 中文标签
 */
private fun mediaTypeLabel(type: String?): String = when (type) {
    "image" -> "图片"
    "video" -> "视频"
    "other" -> "其他"
    else -> "全部"
}

/**
 * 排序键 → 简短中文标签
 */
private fun sortLabel(by: String, order: String): String = when {
    by == "id" && order == "desc" -> "最新上传"
    by == "id" && order == "asc" -> "最早上传"
    by == "taken_at" && order == "desc" -> "最新拍摄"
    by == "taken_at" && order == "asc" -> "最早拍摄"
    else -> "默认"
}
