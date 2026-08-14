package com.transdot.transferassistant.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.transdot.transferassistant.R
import com.transdot.transferassistant.data.AppSettings
import com.transdot.transferassistant.data.ServerProfileDisplayStatus
import com.transdot.transferassistant.data.ServerProfileSummary
import com.transdot.transferassistant.data.TimelineMessage
import com.transdot.transferassistant.data.serverProfileStatus
import com.transdot.transferassistant.data.serverProfileStatusLabel
import com.transdot.transferassistant.ui.components.AppEmptyState
import com.transdot.transferassistant.ui.components.AppIconButton
import com.transdot.transferassistant.ui.components.AppStatusPanel
import com.transdot.transferassistant.ui.components.AppTopBar
import com.transdot.transferassistant.ui.components.StatusTone
import com.transdot.transferassistant.ui.theme.AppSpacing
import com.transdot.transferassistant.ui.theme.ThemeMode
import kotlinx.coroutines.launch

@Composable
internal fun SearchScreen(
    state: TimelineUiState,
    onBack: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onLocate: (String) -> Unit,
) {
    BackHandler(onBack = onBack)
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            AppTopBar(title = "搜索消息与文件名", onBack = onBack)
            Row(
                Modifier.padding(horizontal = AppSpacing.large),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.small),
            ) {
                OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = onQueryChange,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("输入关键词") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                    shape = MaterialTheme.shapes.medium,
                )
                Button(
                    onClick = onSearch,
                    enabled = state.searchQuery.isNotBlank() && !state.isSearching,
                    modifier = Modifier.height(56.dp),
                ) {
                    Text(if (state.isSearching) "搜索中" else "搜索")
                }
            }
            HorizontalDivider(Modifier.padding(top = AppSpacing.large))
            if (state.searchResults.isEmpty()) {
                AppEmptyState(
                    iconRes = R.drawable.ic_search,
                    title = if (state.searchQuery.isBlank()) "搜索时间线" else "没有找到结果",
                    message = if (state.searchQuery.isBlank()) "搜索文字内容或原始文件名。" else "换一个关键词再试试。",
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(AppSpacing.large),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.small),
                ) {
                    items(state.searchResults, key = TimelineMessage::id) { message ->
                        Surface(
                            onClick = { onLocate(message.id) },
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                        ) {
                            Column(Modifier.padding(AppSpacing.medium)) {
                                Text(
                                    message.textContent ?: message.file?.originalFilename ?: "文件消息",
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.height(AppSpacing.small))
                                Text(
                                    "${if (message.sourceDeviceType == "android_master") "Android" else "Windows"} · ${formatMessageTime(message.createdAt)}",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AttachmentSheet(onDismiss: () -> Unit, onPhotos: () -> Unit, onFiles: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(AppSpacing.extraLarge),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.medium),
        ) {
            Text("添加内容", style = MaterialTheme.typography.headlineSmall)
            AttachmentOption(
                iconRes = R.drawable.ic_photo,
                title = "照片",
                description = "系统照片选择器 · 最多 20 张 · 原图不压缩",
                onClick = onPhotos,
            )
            AttachmentOption(
                iconRes = R.drawable.ic_file,
                title = "文件",
                description = "单个 300 MB · 单批 500 MB",
                onClick = onFiles,
            )
            Spacer(Modifier.height(AppSpacing.small))
        }
    }
}

@Composable
private fun AttachmentOption(iconRes: Int, title: String, description: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(
            Modifier.fillMaxWidth().padding(AppSpacing.large),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.large),
        ) {
            Icon(painterResource(iconRes), contentDescription = null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsSheet(
    mode: ThemeMode,
    appSettings: AppSettings,
    folderLabel: String?,
    serverProfiles: List<ServerProfileSummary>,
    activeProfileId: String,
    connection: TimelineConnectionState,
    transferBusy: Boolean,
    onChange: (ThemeMode) -> Unit,
    onChooseDefaultFolder: () -> Unit,
    onClearDefaultFolder: () -> Unit,
    onNotificationsChanged: (Boolean) -> Unit,
    onAddServer: suspend (String, String, String) -> Result<Unit>,
    onSwitchServer: suspend (String) -> Result<Unit>,
    onRenameServer: (String, String) -> Boolean,
    onDeleteServer: (String) -> Boolean,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        SettingsSheetContent(
            mode,
            appSettings,
            folderLabel,
            serverProfiles,
            activeProfileId,
            connection,
            transferBusy,
            onChange,
            onChooseDefaultFolder,
            onClearDefaultFolder,
            onNotificationsChanged,
            onAddServer,
            onSwitchServer,
            onRenameServer,
            onDeleteServer,
        )
    }
}

@Composable
internal fun SettingsSheetContent(
    mode: ThemeMode,
    appSettings: AppSettings,
    folderLabel: String?,
    serverProfiles: List<ServerProfileSummary>,
    activeProfileId: String,
    connection: TimelineConnectionState,
    transferBusy: Boolean,
    onChange: (ThemeMode) -> Unit,
    onChooseDefaultFolder: () -> Unit,
    onClearDefaultFolder: () -> Unit,
    onNotificationsChanged: (Boolean) -> Unit,
    onAddServer: suspend (String, String, String) -> Result<Unit>,
    onSwitchServer: suspend (String) -> Result<Unit>,
    onRenameServer: (String, String) -> Boolean,
    onDeleteServer: (String) -> Boolean,
) {
    val scope = rememberCoroutineScope()
    var showAddServer by remember { mutableStateOf(false) }
    var serverName by remember { mutableStateOf("") }
    var serverAddress by remember { mutableStateOf("") }
    var setupToken by remember { mutableStateOf("") }
    var serverActionRunning by remember { mutableStateOf(false) }
    var serverError by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<ServerProfileSummary?>(null) }
    var renameValue by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<ServerProfileSummary?>(null) }

    Column(
            Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(AppSpacing.extraLarge),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.medium),
        ) {
            Text("设置", style = MaterialTheme.typography.headlineSmall)
            Text("外观", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                listOf(ThemeMode.System to "跟随系统", ThemeMode.Light to "浅色", ThemeMode.Dark to "深色").forEach { (value, label) ->
                    if (mode == value) Button({ onChange(value) }) { Text(label) } else OutlinedButton({ onChange(value) }) { Text(label) }
                }
            }
            HorizontalDivider()
            Text("文件保存", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(folderLabel?.let { "默认保存位置：$it" } ?: "未设置默认保存位置，每次下载时询问。")
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                OutlinedButton(onClick = onChooseDefaultFolder) { Text(if (folderLabel == null) "选择文件夹" else "更改文件夹") }
                if (folderLabel != null) TextButton(onClick = onClearDefaultFolder) { Text("清除") }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("传输结果通知", style = MaterialTheme.typography.titleSmall)
                    Text("上传或下载完成、失败时通知", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = appSettings.notificationsEnabled, onCheckedChange = onNotificationsChanged)
            }
            HorizontalDivider()
            Text("服务器", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            if (transferBusy) AppStatusPanel(StatusTone.Info, "传输进行中", "完成后才能切换或删除服务器。")
            serverProfiles.forEach { profile ->
                val status = serverProfileStatus(
                    isActive = profile.id == activeProfileId,
                    isConnected = connection == TimelineConnectionState.Connected,
                    isConnecting = connection == TimelineConnectionState.Connecting,
                )
                ServerProfileCard(
                    profile = profile,
                    activeProfileId = activeProfileId,
                    status = status,
                    transferBusy = transferBusy,
                    serverActionRunning = serverActionRunning,
                    onSwitch = {
                        serverActionRunning = true
                        serverError = null
                        scope.launch {
                            onSwitchServer(profile.id).onFailure { serverError = it.message ?: "无法切换服务器" }
                            serverActionRunning = false
                        }
                    },
                    onRename = { renameTarget = profile; renameValue = profile.name },
                    onDelete = { deleteTarget = profile },
                )
            }
            serverError?.let { AppStatusPanel(StatusTone.Error, "服务器操作失败", it) }
            if (showAddServer) {
                OutlinedTextField(serverName, { serverName = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("档案名称") })
                OutlinedTextField(serverAddress, { serverAddress = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("服务器地址") }, placeholder = { Text("https://transfer.example.com") })
                OutlinedTextField(setupToken, { setupToken = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("初始化密钥") })
                Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                    Button(
                        enabled = serverName.isNotBlank() && serverAddress.isNotBlank() && setupToken.isNotBlank() && !transferBusy && !serverActionRunning,
                        onClick = {
                            serverActionRunning = true
                            serverError = null
                            scope.launch {
                                onAddServer(serverName.trim(), serverAddress.trim(), setupToken.trim())
                                    .onFailure { serverError = it.message ?: "无法添加服务器" }
                                serverActionRunning = false
                            }
                        },
                    ) { Text(if (serverActionRunning) "连接中" else "连接并保存") }
                    TextButton(onClick = { showAddServer = false }) { Text("取消") }
                }
            } else {
                OutlinedButton(onClick = { showAddServer = true }, enabled = !transferBusy) { Text("添加服务器") }
            }
            Text("文件仅在服务器临时保存；原文件最长 24 小时。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(AppSpacing.small))
    }
    renameTarget?.let { profile ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名服务器") },
            text = { OutlinedTextField(renameValue, { renameValue = it }, singleLine = true, label = { Text("档案名称") }) },
            confirmButton = {
                TextButton(
                    onClick = { if (onRenameServer(profile.id, renameValue)) renameTarget = null },
                    enabled = renameValue.isNotBlank(),
                ) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("取消") } },
        )
    }
    deleteTarget?.let { profile ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除服务器档案？") },
            text = { Text("将从本机删除“${profile.name}”的加密凭据，不会删除服务器数据。") },
            confirmButton = { TextButton(onClick = { if (onDeleteServer(profile.id)) deleteTarget = null }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun ServerProfileCard(
    profile: ServerProfileSummary,
    activeProfileId: String,
    status: ServerProfileDisplayStatus,
    transferBusy: Boolean,
    serverActionRunning: Boolean,
    onSwitch: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().padding(AppSpacing.medium), verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
            Text("${profile.name}${if (profile.id == activeProfileId) " · 当前" else ""}", style = MaterialTheme.typography.titleSmall)
            Text(
                profile.serverAddress,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                serverProfileStatusLabel(status),
                color = if (status == ServerProfileDisplayStatus.Connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                if (profile.id != activeProfileId) {
                    TextButton(enabled = !transferBusy && !serverActionRunning, onClick = onSwitch) { Text("切换") }
                }
                TextButton(onClick = onRename) { Text("重命名") }
                TextButton(enabled = !transferBusy && !serverActionRunning, onClick = onDelete) { Text("删除") }
            }
        }
    }
}

@Composable
internal fun ImageViewer(
    state: ViewerState,
    loadImage: suspend (TimelineMessage, Boolean) -> Bitmap?,
    onDownload: (TimelineMessage) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val pager = rememberPagerState(initialPage = state.initialIndex, pageCount = { state.images.size })
    Surface(Modifier.fillMaxSize(), color = Color(0xFF080A0D), contentColor = Color.White) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().height(64.dp).padding(horizontal = AppSpacing.medium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppIconButton(R.drawable.ic_arrow_back, "返回时间线", onBack, tint = Color.White)
                Text(
                    "${pager.currentPage + 1} / ${state.images.size}",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center,
                )
                val current = state.images[pager.currentPage]
                AppIconButton(
                    iconRes = R.drawable.ic_download,
                    contentDescription = if (current.file?.status == "available") "下载当前图片" else "原图已过期",
                    onClick = { onDownload(current) },
                    enabled = current.file?.status == "available",
                    tint = Color.White,
                )
            }
            HorizontalPager(pager, Modifier.weight(1f)) { page ->
                val message = state.images[page]
                val bitmap by produceState<Bitmap?>(null, message.id, message.file?.status) { value = loadImage(message, true) }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (bitmap == null) CircularProgressIndicator(color = Color.White)
                    else Image(
                        requireNotNull(bitmap).asImageBitmap(),
                        message.file?.originalFilename,
                        Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                }
            }
            Text(
                state.images[pager.currentPage].file?.originalFilename.orEmpty(),
                Modifier.fillMaxWidth().navigationBarsPadding().padding(AppSpacing.medium),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = Color.LightGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun LoadingTimeline() = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AppSpacing.medium),
    ) {
        CircularProgressIndicator()
        Text("正在同步时间线", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun EmptyTimeline() {
    AppEmptyState(
        iconRes = R.drawable.ic_send,
        title = "发送第一条内容",
        message = "输入文字，或选择照片和文件。",
        modifier = Modifier.fillMaxSize(),
    )
}
