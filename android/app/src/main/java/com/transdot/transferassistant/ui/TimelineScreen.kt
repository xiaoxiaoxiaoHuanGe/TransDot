package com.transdot.transferassistant.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.transdot.transferassistant.data.FileAttachment
import com.transdot.transferassistant.data.AppSettings
import com.transdot.transferassistant.data.DownloadDestinationManager
import com.transdot.transferassistant.data.SaveLocationChoice
import com.transdot.transferassistant.data.ServerProfileDisplayStatus
import com.transdot.transferassistant.data.ServerProfileSummary
import com.transdot.transferassistant.data.TimelineMessage
import com.transdot.transferassistant.data.UploadProgress
import com.transdot.transferassistant.data.activeServerStatusLine
import com.transdot.transferassistant.data.availableSaveLocationChoices
import com.transdot.transferassistant.data.serverProfileStatus
import com.transdot.transferassistant.data.serverProfileStatusLabel
import com.transdot.transferassistant.IncomingShare
import com.transdot.transferassistant.ui.theme.AppSpacing
import com.transdot.transferassistant.ui.theme.ThemeMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

private data class ViewerState(val images: List<TimelineMessage>, val initialIndex: Int)
private data class MessageGroup(val key: String, val messages: List<TimelineMessage>)
private const val ACTION_NOTICE_TOTAL_DURATION_MS = 1_500L
private const val ACTION_NOTICE_EXIT_DURATION_MS = 140

private data class ActionNotice(val id: Int, val message: String, val visible: Boolean = true)

@Composable
private fun ActionNoticeOverlay(notice: ActionNotice?, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = notice?.visible == true,
        modifier = modifier,
        enter = fadeIn(animationSpec = tween(durationMillis = 120)),
        exit = fadeOut(animationSpec = tween(durationMillis = ACTION_NOTICE_EXIT_DURATION_MS)),
    ) {
        Surface(
            modifier = Modifier.wrapContentWidth().widthIn(max = 280.dp),
            shape = CircleShape,
            color = Color.Black.copy(alpha = 0.88f),
            contentColor = Color.White,
            tonalElevation = 0.dp,
            shadowElevation = 4.dp,
        ) {
            Text(
                text = notice?.message.orEmpty(),
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                color = Color.White,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun TimelineScreen(
    state: TimelineUiState,
    ownDeviceId: String,
    themeMode: ThemeMode,
    appSettings: AppSettings,
    downloadDestinationManager: DownloadDestinationManager,
    serverProfiles: List<ServerProfileSummary>,
    activeProfileId: String,
    activeServerName: String,
    serverSwitchNotice: String?,
    incomingShare: IncomingShare?,
    onShareConsumed: () -> Unit,
    onServerSwitchNoticeConsumed: () -> Unit,
    onThemeModeChange: (ThemeMode) -> Unit,
    onDefaultSaveTreeChanged: (Uri?) -> Unit,
    onNotificationsChanged: (Boolean) -> Unit,
    onAddServer: suspend (String, String, String) -> Result<Unit>,
    onSwitchServer: suspend (String) -> Result<Unit>,
    onRenameServer: (String, String) -> Boolean,
    onDeleteServer: (String) -> Boolean,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onUpload: (List<Uri>) -> Unit,
    onRetryUpload: () -> Unit,
    onDownload: (TimelineMessage, Uri) -> Unit,
    onDownloadResultConsumed: () -> Unit,
    loadImage: suspend (TimelineMessage, Boolean) -> Bitmap?,
    onLoadOlder: () -> Unit,
    onRequestDelete: (List<TimelineMessage>) -> Unit,
    onCancelDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
    onOpenSearch: () -> Unit,
    onCloseSearch: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onLocate: (String) -> Unit,
    onClearHighlight: () -> Unit,
    onClearError: () -> Unit,
    onPairWindows: () -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var attachmentSheet by remember { mutableStateOf(false) }
    var settingsSheet by remember { mutableStateOf(false) }
    var viewer by remember { mutableStateOf<ViewerState?>(null) }
    var pendingDownload by remember { mutableStateOf<TimelineMessage?>(null) }
    var pendingSaveLocationUri by remember { mutableStateOf<Uri?>(null) }
    var actionNotice by remember { mutableStateOf<ActionNotice?>(null) }
    var nextActionNoticeId by remember { mutableIntStateOf(0) }
    val showActionNotice: (String) -> Unit = { message ->
        nextActionNoticeId += 1
        actionNotice = ActionNotice(nextActionNoticeId, message)
    }

    LaunchedEffect(serverSwitchNotice) {
        val notice = serverSwitchNotice ?: return@LaunchedEffect
        showActionNotice(notice)
        onServerSwitchNoticeConsumed()
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { uris ->
        if (uris.isNotEmpty()) onUpload(uris)
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) onUpload(uris.take(20))
    }
    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val target = pendingDownload
        pendingDownload = null
        if (result.resultCode == Activity.RESULT_OK && target != null) {
            result.data?.data?.let { onDownload(target, it) }
        }
    }
    val chooseDefaultFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { onDefaultSaveTreeChanged(uri) }
                .onSuccess { showActionNotice("默认保存位置已更新") }
                .onFailure { showActionNotice(it.message ?: "无法保存目录权限") }
        }
    }
    val requestNotificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        onNotificationsChanged(granted)
        showActionNotice(if (granted) "传输通知已开启" else "未获得通知权限")
    }
    val changeNotifications: (Boolean) -> Unit = { enabled ->
        if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else onNotificationsChanged(enabled)
    }
    val requestDownload: (TimelineMessage) -> Unit = { message ->
        val attachment = message.file
        if (attachment != null && attachment.status == "available") {
            if (appSettings.defaultSaveTreeUri != null) {
                coroutineScope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            downloadDestinationManager.createInDefaultFolder(attachment.originalFilename, attachment.mimeType)
                        }
                    }.onSuccess { destination -> onDownload(message, destination) }
                        .onFailure {
                            onDefaultSaveTreeChanged(null)
                            showActionNotice("默认目录不可用，请重新选择保存位置")
                            pendingDownload = message
                            createDocument.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                type = attachment.mimeType
                                putExtra(Intent.EXTRA_TITLE, attachment.originalFilename)
                            })
                        }
                }
            } else {
                pendingDownload = message
                createDocument.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = attachment.mimeType
                    putExtra(Intent.EXTRA_TITLE, attachment.originalFilename)
                })
            }
        }
    }

    LaunchedEffect(actionNotice?.id) {
        val currentNotice = actionNotice ?: return@LaunchedEffect
        delay(ACTION_NOTICE_TOTAL_DURATION_MS - ACTION_NOTICE_EXIT_DURATION_MS)
        if (actionNotice?.id != currentNotice.id) return@LaunchedEffect
        actionNotice = currentNotice.copy(visible = false)
        delay(ACTION_NOTICE_EXIT_DURATION_MS.toLong())
        if (actionNotice?.id == currentNotice.id) actionNotice = null
    }

    LaunchedEffect(incomingShare?.id) {
        val shared = incomingShare ?: return@LaunchedEffect
        if (shared.files.isEmpty() && !shared.text.isNullOrBlank()) {
            onDraftChange(shared.text)
            onShareConsumed()
            showActionNotice("分享文字已放入输入框")
        }
    }

    AnimatedContent(
        targetState = Pair(state.searchOpen, viewer != null),
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "timeline-navigation",
    ) { (searchOpen, viewerOpen) ->
        when {
            viewerOpen && viewer != null -> ImageViewer(
                state = requireNotNull(viewer),
                loadImage = loadImage,
                onDownload = requestDownload,
                onBack = { viewer = null },
            )
            searchOpen -> SearchScreen(state, onCloseSearch, onSearchQueryChange, onSearch, onLocate)
            else -> TimelineHome(
                state = state,
                ownDeviceId = ownDeviceId,
                activeServerName = activeServerName,
                onDraftChange = onDraftChange,
                onSend = onSend,
                onLoadOlder = onLoadOlder,
                onRequestDelete = onRequestDelete,
                onOpenSearch = onOpenSearch,
                onPairWindows = onPairWindows,
                onOpenAttachment = { attachmentSheet = true },
                onOpenSettings = { settingsSheet = true },
                onOpenImages = { images, index -> viewer = ViewerState(images, index) },
                onDownload = requestDownload,
                loadImage = loadImage,
                onRetryUpload = onRetryUpload,
                onClearHighlight = onClearHighlight,
                actionNotice = actionNotice,
                onShowActionNotice = showActionNotice,
            )
        }
    }

    state.deleteTargets.takeIf { it.isNotEmpty() }?.let { targets ->
        AlertDialog(
            onDismissRequest = onCancelDelete,
            title = { Text(if (targets.size > 1) "删除这组 ${targets.size} 条消息？" else "删除这条消息？") },
            text = { Text(if (targets.all { it.type == "text" }) "删除会同步到 Windows，并从全文搜索中移除。" else "消息、原文件与缩略图会一起删除，无法撤销。") },
            confirmButton = { TextButton(onClick = onConfirmDelete, enabled = !state.isDeleting) { Text(if (state.isDeleting) "删除中" else "删除") } },
            dismissButton = { TextButton(onClick = onCancelDelete, enabled = !state.isDeleting) { Text("取消") } },
        )
    }
    if (state.credentialInvalid) {
        AlertDialog(
            onDismissRequest = onClearError,
            title = { Text("凭据已失效") },
            text = { Text("${state.errorMessage.orEmpty()}\n\n请切换服务器档案，或删除失效档案后重新连接。") },
            confirmButton = { TextButton(onClick = { onClearError(); settingsSheet = true }) { Text("管理服务器") } },
            dismissButton = { TextButton(onClick = onClearError) { Text("稍后") } },
        )
    }
    if (attachmentSheet) {
        AttachmentSheet(
            onDismiss = { attachmentSheet = false },
            onPhotos = {
                attachmentSheet = false
                photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onFiles = {
                attachmentSheet = false
                filePicker.launch(arrayOf("*/*"))
            },
        )
    }
    if (settingsSheet) {
        SettingsSheet(
            mode = themeMode,
            appSettings = appSettings,
            folderLabel = downloadDestinationManager.folderLabel(),
            serverProfiles = serverProfiles,
            activeProfileId = activeProfileId,
            connection = state.connectionState,
            transferBusy = state.isUploading || state.downloadMessageId != null,
            onChange = onThemeModeChange,
            onChooseDefaultFolder = { chooseDefaultFolder.launch(appSettings.defaultSaveTreeUri?.let(Uri::parse)) },
            onClearDefaultFolder = { onDefaultSaveTreeChanged(null) },
            onNotificationsChanged = changeNotifications,
            onAddServer = onAddServer,
            onSwitchServer = onSwitchServer,
            onRenameServer = onRenameServer,
            onDeleteServer = onDeleteServer,
            onDismiss = { settingsSheet = false },
        )
    }
    state.completedDownload?.let { completed ->
        val attachment = completed.message.file
        AlertDialog(
            onDismissRequest = onDownloadResultConsumed,
            title = { Text("文件已保存") },
            text = { Text(attachment?.originalFilename ?: "文件") },
            confirmButton = {
                TextButton(onClick = {
                    onDownloadResultConsumed()
                    runCatching { context.startActivity(downloadDestinationManager.openFileIntent(completed.destination, attachment?.mimeType.orEmpty())) }
                        .onFailure { showActionNotice("没有可打开此文件的应用") }
                }) { Text("打开文件") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        pendingSaveLocationUri = completed.destination
                        onDownloadResultConsumed()
                    }) { Text("查看保存位置") }
                    TextButton(onClick = onDownloadResultConsumed) { Text("完成") }
                }
            },
        )
    }
    pendingSaveLocationUri?.let { initialUri ->
        val cxIntent = remember(initialUri) { downloadDestinationManager.cxFolderIntent() }
        val choices = remember(cxIntent) {
            availableSaveLocationChoices(cxAvailable = cxIntent != null)
        }
        AlertDialog(
            onDismissRequest = { pendingSaveLocationUri = null },
            title = { Text("打开保存位置") },
            text = { Text("请选择用于查看该目录的文件管理器。") },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = {
                            pendingSaveLocationUri = null
                            runCatching {
                                downloadDestinationManager.systemFolderIntent(initialUri)?.let(context::startActivity)
                                    ?: error("No system document browser")
                            }.onFailure { showActionNotice("当前系统不支持打开保存位置") }
                        }) { Text("系统文件管理器") }
                        if (SaveLocationChoice.CX in choices && cxIntent != null) {
                            TextButton(onClick = {
                                pendingSaveLocationUri = null
                                runCatching { context.startActivity(cxIntent) }
                                    .onFailure { showActionNotice("CX 文件管理器无法打开该目录") }
                            }) { Text("CX 文件管理器") }
                        }
                    }
                    TextButton(onClick = { pendingSaveLocationUri = null }) { Text("取消") }
                }
            },
            dismissButton = {},
        )
    }
    incomingShare?.takeIf { it.files.isNotEmpty() }?.let { shared ->
        val tooMany = shared.files.size > 20
        AlertDialog(
            onDismissRequest = onShareConsumed,
            title = { Text("发送到传输助手？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                    Text("共 ${shared.files.size} 个文件")
                    shared.files.take(5).forEach { file ->
                        Text("• ${file.displayName}", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (shared.files.size > 5) Text("以及其他 ${shared.files.size - 5} 个文件", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!shared.text.isNullOrBlank()) Text("附带文字会放入输入框，可在上传后单独发送。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (tooMany) Text("一次最多发送 20 个文件，请减少选择数量。", color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onUpload(shared.files.map { it.uri })
                        shared.text?.takeIf(String::isNotBlank)?.let(onDraftChange)
                        onShareConsumed()
                        showActionNotice("已加入上传队列")
                    },
                    enabled = !state.isUploading && !tooMany,
                ) { Text(if (state.isUploading) "正在上传" else "发送") }
            },
            dismissButton = { TextButton(onClick = onShareConsumed) { Text("取消") } },
        )
    }
}

@Composable
private fun TimelineHome(
    state: TimelineUiState,
    ownDeviceId: String,
    activeServerName: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onLoadOlder: () -> Unit,
    onRequestDelete: (List<TimelineMessage>) -> Unit,
    onOpenSearch: () -> Unit,
    onPairWindows: () -> Unit,
    onOpenAttachment: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenImages: (List<TimelineMessage>, Int) -> Unit,
    onDownload: (TimelineMessage) -> Unit,
    loadImage: suspend (TimelineMessage, Boolean) -> Bitmap?,
    onRetryUpload: () -> Unit,
    onClearHighlight: () -> Unit,
    actionNotice: ActionNotice?,
    onShowActionNotice: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    val groups = remember(state.messages) { groupMessages(state.messages) }
    var isFollowingLatest by remember { mutableStateOf(true) }
    var programmaticScroll by remember { mutableStateOf(false) }
    var unreadCount by remember { mutableIntStateOf(0) }
    var initialPositioned by remember { mutableStateOf(false) }
    var previousNewestMessageId by remember { mutableStateOf<String?>(null) }
    var knownMessageIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var wasUploading by remember { mutableStateOf(false) }
    var wasDeleting by remember { mutableStateOf(false) }

    fun copyMessages(messages: List<TimelineMessage>) {
        val value = messages.joinToString("\n") { message ->
            if (message.type == "text") message.textContent.orEmpty() else message.file?.originalFilename.orEmpty()
        }.trim()
        if (value.isEmpty()) return
        clipboardManager.setText(AnnotatedString(value))
        onShowActionNotice(if (messages.size > 1) "已复制 ${messages.size} 个文件名" else "已复制")
    }

    LaunchedEffect(state.isUploading, state.errorMessage) {
        if (wasUploading && !state.isUploading && state.errorMessage == null) onShowActionNotice("文件上传完成")
        wasUploading = state.isUploading
    }
    LaunchedEffect(state.isDeleting, state.errorMessage) {
        if (wasDeleting && !state.isDeleting && state.deleteTargets.isEmpty() && state.errorMessage == null) onShowActionNotice("消息已删除")
        wasDeleting = state.isDeleting
    }

    suspend fun scrollToBottom(animated: Boolean) {
        val itemCount = snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > 0 }
        val lastIndex = itemCount - 1
        programmaticScroll = true
        isFollowingLatest = true
        try {
            if (animated) listState.animateScrollToItem(lastIndex) else listState.scrollToItem(lastIndex)
            val lastItem = listState.layoutInfo.visibleItemsInfo.lastOrNull { it.index == lastIndex }
            val remaining = lastItem?.let {
                it.offset + it.size - listState.layoutInfo.viewportEndOffset + listState.layoutInfo.afterContentPadding
            }?.coerceAtLeast(0) ?: 0
            if (remaining > 0) {
                if (animated) listState.animateScrollBy(remaining.toFloat()) else listState.scrollBy(remaining.toFloat())
            }
            unreadCount = 0
        } finally {
            programmaticScroll = false
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow { Triple(listState.isScrollInProgress, !listState.canScrollForward, programmaticScroll) }
            .distinctUntilChanged()
            .collect { (scrolling, atBottom, automatic) ->
                if (scrolling && !automatic) isFollowingLatest = atBottom
                if (atBottom) {
                    isFollowingLatest = true
                    unreadCount = 0
                }
            }
    }
    LaunchedEffect(state.isInitialLoading, state.messages, state.highlightedMessageId) {
        if (state.isInitialLoading) return@LaunchedEffect
        val newest = state.messages.lastOrNull()
        val currentIds = state.messages.mapTo(mutableSetOf()) { it.id }
        val addedMessages = state.messages.filterNot { it.id in knownMessageIds }
        knownMessageIds = currentIds

        if (!initialPositioned) {
            initialPositioned = true
            previousNewestMessageId = newest?.id
            if (newest != null) scrollToBottom(animated = false)
            return@LaunchedEffect
        }
        if (state.highlightedMessageId != null) {
            previousNewestMessageId = newest?.id
            return@LaunchedEffect
        }
        if (newest == null || newest.id == previousNewestMessageId || addedMessages.isEmpty()) return@LaunchedEffect

        val previousIndex = previousNewestMessageId?.let { id -> state.messages.indexOfFirst { it.id == id } } ?: -1
        val newTail = if (previousIndex >= 0) state.messages.drop(previousIndex + 1) else addedMessages
        previousNewestMessageId = newest.id
        if (newTail.isEmpty()) return@LaunchedEffect

        val hasOwnMessage = newTail.any { it.sourceDeviceId == ownDeviceId }
        if (hasOwnMessage || isFollowingLatest) {
            scrollToBottom(animated = true)
        } else {
            unreadCount += newTail.count { it.sourceDeviceId != ownDeviceId }
        }
    }
    LaunchedEffect(state.highlightedMessageId) {
        val id = state.highlightedMessageId ?: return@LaunchedEffect
        val index = groups.indexOfFirst { group -> group.messages.any { it.id == id } }
        if (index >= 0) listState.animateScrollToItem(index + if (state.nextBefore != null) 1 else 0)
        delay(2_600)
        onClearHighlight()
    }
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = { TimelineTopBar(activeServerName, state.connectionState, onPairWindows, onOpenSearch, onOpenSettings) },
        bottomBar = { MessageComposer(state.draft, state.isSending, state.errorMessage, onDraftChange, onSend, onOpenAttachment) },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            when {
                state.isInitialLoading -> LoadingTimeline()
                state.messages.isEmpty() && state.uploads.isEmpty() -> EmptyTimeline()
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = AppSpacing.medium, vertical = AppSpacing.large),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.large),
                ) {
                    if (state.nextBefore != null) {
                        item("older") {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                OutlinedButton(onClick = onLoadOlder, enabled = !state.isLoadingOlder) {
                                    Text(if (state.isLoadingOlder) "加载中…" else "加载更早消息")
                                }
                            }
                        }
                    }
                    items(groups, key = MessageGroup::key) { group ->
                        if (group.messages.first().type == "image") {
                            ImageGroup(
                                messages = group.messages,
                                own = group.messages.first().sourceDeviceId == ownDeviceId,
                                highlighted = group.messages.any { it.id == state.highlightedMessageId },
                                loadImage = loadImage,
                                onOpen = { onOpenImages(group.messages, it) },
                                onCopy = { copyMessages(group.messages) },
                                onDelete = { onRequestDelete(group.messages) },
                            )
                        } else {
                            MessageBubble(
                                message = group.messages.first(),
                                own = group.messages.first().sourceDeviceId == ownDeviceId,
                                highlighted = group.messages.first().id == state.highlightedMessageId,
                                onCopy = { copyMessages(group.messages) },
                                onDelete = { onRequestDelete(group.messages) },
                                onDownload = { onDownload(group.messages.first()) },
                            )
                        }
                    }
                    if (state.uploads.isNotEmpty()) {
                        items(state.uploads, key = UploadProgress::uploadId) { upload -> UploadCard(upload, onRetryUpload) }
                    }
                }
            }
            state.downloadMessageId?.let {
                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(AppSpacing.medium),
                    shape = MaterialTheme.shapes.medium,
                    tonalElevation = 6.dp,
                ) {
                    Row(Modifier.padding(AppSpacing.medium), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(AppSpacing.medium))
                        Text(state.downloadProgress?.let { "正在下载 ${(it * 100).roundToInt()}%" } ?: "正在下载…")
                    }
                }
            }
            AnimatedVisibility(
                visible = !isFollowingLatest,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = if (state.downloadMessageId == null) AppSpacing.medium else 76.dp),
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Surface(
                    onClick = { coroutineScope.launch { scrollToBottom(animated = true) } },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.primary,
                    shadowElevation = 8.dp,
                ) {
                    Row(
                        Modifier.padding(horizontal = if (unreadCount > 0) AppSpacing.medium else 13.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.small),
                    ) {
                        Text("↓", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        if (unreadCount > 0) {
                            Text(
                                "${unreadCount.coerceAtMost(99)}${if (unreadCount > 99) "+" else ""} 条新消息",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
            val noticeBottomPadding = when {
                state.downloadMessageId != null && !isFollowingLatest -> 136.dp
                state.downloadMessageId != null || !isFollowingLatest -> 76.dp
                else -> AppSpacing.medium
            }
            ActionNoticeOverlay(
                notice = actionNotice,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 24.dp, end = 24.dp, bottom = noticeBottomPadding),
            )
        }
    }
}

@Composable
private fun TimelineTopBar(
    activeServerName: String,
    connection: TimelineConnectionState,
    onPairWindows: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
) {
    Surface(tonalElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().height(68.dp).padding(horizontal = AppSpacing.large),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("传输助手", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).background(if (connection == TimelineConnectionState.Connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape))
                    Text(
                        activeServerStatusLine(
                            activeServerName,
                            serverProfileStatus(
                                isActive = true,
                                isConnected = connection == TimelineConnectionState.Connected,
                                isConnecting = connection == TimelineConnectionState.Connecting,
                            ),
                        ),
                        Modifier.padding(start = AppSpacing.small),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            TextButton(onClick = onPairWindows) { Text("配对") }
            IconButton(onClick = onSearch) { Text("⌕", fontSize = 28.sp) }
            IconButton(onClick = onSettings) { Text("⋮", fontSize = 24.sp) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: TimelineMessage,
    own: Boolean,
    highlighted: Boolean,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onDownload: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (own) Arrangement.End else Arrangement.Start) {
        Column(Modifier.widthIn(max = 340.dp), horizontalAlignment = if (own) Alignment.End else Alignment.Start) {
            MessageMeta(message)
            if (message.type == "text") {
                Surface(
                    modifier = Modifier.clip(messageShape(own)).combinedClickable(onClick = {}, onLongClick = onDelete),
                    color = when { highlighted -> MaterialTheme.colorScheme.tertiaryContainer; own -> MaterialTheme.colorScheme.primary; else -> MaterialTheme.colorScheme.surfaceContainerHigh },
                    contentColor = when { highlighted -> MaterialTheme.colorScheme.onTertiaryContainer; own -> MaterialTheme.colorScheme.onPrimary; else -> MaterialTheme.colorScheme.onSurface },
                    shadowElevation = if (highlighted) 6.dp else 1.dp,
                ) {
                    Column(Modifier.padding(horizontal = AppSpacing.medium, vertical = AppSpacing.small)) {
                        Text(message.textContent.orEmpty(), style = MaterialTheme.typography.bodyLarge, lineHeight = 24.sp)
                        MessageActionButtons(
                            onCopy = onCopy,
                            onDelete = onDelete,
                            tint = if (own) MaterialTheme.colorScheme.onPrimary.copy(alpha = .78f) else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.align(Alignment.End),
                        )
                    }
                }
            } else {
                FileCard(requireNotNull(message.file), own, onDownload, onCopy, onDelete)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileCard(file: FileAttachment, own: Boolean, onDownload: () -> Unit, onCopy: () -> Unit, onDelete: () -> Unit) {
    Surface(
        modifier = Modifier.widthIn(min = 280.dp, max = 340.dp).clip(messageShape(own)).combinedClickable(onClick = {}, onLongClick = onDelete),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(AppSpacing.medium)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                    Text(fileExtension(file.originalFilename), color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
                Column(Modifier.weight(1f).padding(horizontal = AppSpacing.medium)) {
                    Text(file.originalFilename, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    Text("${formatBytes(file.sizeBytes)} · ${fileStatus(file)}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                }
                if (file.status == "available") TextButton(onClick = onDownload) { Text("下载") } else Text("已过期", style = MaterialTheme.typography.labelSmall)
            }
            MessageActionButtons(onCopy, onDelete, MaterialTheme.colorScheme.primary, Modifier.align(Alignment.End))
        }
    }
}

@Composable
private fun ImageGroup(
    messages: List<TimelineMessage>,
    own: Boolean,
    highlighted: Boolean,
    loadImage: suspend (TimelineMessage, Boolean) -> Bitmap?,
    onOpen: (Int) -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (own) Arrangement.End else Arrangement.Start) {
        Column(Modifier.widthIn(max = 350.dp), horizontalAlignment = if (own) Alignment.End else Alignment.Start) {
            MessageMeta(messages.first())
            Surface(shape = messageShape(own), shadowElevation = if (highlighted) 6.dp else 1.dp) {
                Column(Modifier.padding(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    messages.take(6).chunked(2).forEachIndexed { rowIndex, row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            row.forEachIndexed { columnIndex, message ->
                                val index = rowIndex * 2 + columnIndex
                                ImageTile(message, Modifier.weight(1f), loadImage, { onOpen(index) }, if (index == 5) messages.size - 6 else 0)
                            }
                            if (row.size == 1 && messages.size > 1) Spacer(Modifier.weight(1f))
                        }
                    }
                    MessageActionButtons(onCopy, onDelete, MaterialTheme.colorScheme.primary, Modifier.align(Alignment.End).padding(end = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun ImageTile(
    message: TimelineMessage,
    modifier: Modifier,
    loadImage: suspend (TimelineMessage, Boolean) -> Bitmap?,
    onOpen: () -> Unit,
    more: Int,
) {
    val bitmap by produceState<Bitmap?>(null, message.id, message.file?.thumbnailUrl) { value = loadImage(message, false) }
    Box(modifier.aspectRatio(if (message.batchId == null) 1.3f else 1f).clip(RoundedCornerShape(12.dp)).clickable(onClick = onOpen)) {
        if (bitmap != null) Image(requireNotNull(bitmap).asImageBitmap(), message.file?.originalFilename, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) }
        if (message.file?.status != "available") Surface(Modifier.align(Alignment.BottomCenter).padding(6.dp), shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.scrim.copy(alpha = .7f)) { Text("原图已过期", Modifier.padding(6.dp), color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.labelSmall) }
        if (more > 0) Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = .58f)), contentAlignment = Alignment.Center) { Text("+${more + 1}", color = androidx.compose.ui.graphics.Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun MessageActionButtons(
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onCopy, modifier = Modifier.size(34.dp)) {
            CopyGlyph(tint)
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) {
            TrashGlyph(tint)
        }
    }
}

@Composable
private fun CopyGlyph(tint: Color) {
    Canvas(Modifier.size(17.dp)) {
        val stroke = 1.55.dp.toPx()
        drawRoundRect(
            color = tint,
            topLeft = Offset(size.width * .32f, size.height * .29f),
            size = Size(size.width * .56f, size.height * .61f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(stroke * 1.7f),
            style = Stroke(stroke),
        )
        drawRoundRect(
            color = tint,
            topLeft = Offset(size.width * .12f, size.height * .09f),
            size = Size(size.width * .56f, size.height * .61f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(stroke * 1.7f),
            style = Stroke(stroke),
        )
    }
}

@Composable
private fun TrashGlyph(tint: Color) {
    Canvas(Modifier.size(17.dp)) {
        val stroke = 1.55.dp.toPx()
        drawLine(tint, Offset(size.width * .16f, size.height * .27f), Offset(size.width * .84f, size.height * .27f), stroke)
        drawLine(tint, Offset(size.width * .38f, size.height * .12f), Offset(size.width * .62f, size.height * .12f), stroke)
        drawLine(tint, Offset(size.width * .38f, size.height * .12f), Offset(size.width * .32f, size.height * .27f), stroke)
        drawLine(tint, Offset(size.width * .62f, size.height * .12f), Offset(size.width * .68f, size.height * .27f), stroke)
        drawRoundRect(
            color = tint,
            topLeft = Offset(size.width * .25f, size.height * .32f),
            size = Size(size.width * .5f, size.height * .57f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(stroke * 1.4f),
            style = Stroke(stroke),
        )
        drawLine(tint, Offset(size.width * .43f, size.height * .45f), Offset(size.width * .43f, size.height * .75f), stroke)
        drawLine(tint, Offset(size.width * .57f, size.height * .45f), Offset(size.width * .57f, size.height * .75f), stroke)
    }
}

@Composable
private fun MessageMeta(message: TimelineMessage) {
    Text(
        "${if (message.sourceDeviceType == "android_master") "Android" else "Windows"} · ${formatMessageTime(message.createdAt)}",
        Modifier.padding(horizontal = AppSpacing.small, vertical = 3.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun UploadCard(upload: UploadProgress, onRetry: () -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(start = 56.dp), shape = MaterialTheme.shapes.medium, tonalElevation = 2.dp) {
        Column(Modifier.padding(AppSpacing.medium), verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(upload.filename, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    Text(when (upload.state) { "preparing" -> "正在生成缩略图"; "complete" -> "上传完成"; "failed" -> upload.error ?: "上传失败"; else -> "上传中 ${(upload.bytesSent * 100 / upload.totalBytes.coerceAtLeast(1)).toInt()}%" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (upload.state == "failed") TextButton(onClick = onRetry) { Text("重试") }
            }
            LinearProgressIndicator(
                progress = { if (upload.totalBytes > 0) upload.bytesSent.toFloat() / upload.totalBytes else 0f },
                modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape),
            )
        }
    }
}

@Composable
private fun MessageComposer(
    draft: String,
    sending: Boolean,
    errorMessage: String?,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onAttachment: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val byteCount = draft.toByteArray().size
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = AppSpacing.medium, vertical = 9.dp)) {
            AnimatedVisibility(errorMessage != null) {
                Text(errorMessage.orEmpty(), Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(12.dp)).padding(AppSpacing.small), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.labelMedium)
            }
            Row(Modifier.fillMaxWidth().padding(top = if (errorMessage != null) AppSpacing.small else 0.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                TextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp, max = 132.dp),
                    minLines = 1,
                    maxLines = 5,
                    placeholder = { Text("输入内容……") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (draft.isNotBlank() && byteCount <= MAX_TEXT_BYTES) { onSend(); focusManager.clearFocus() } }),
                    isError = byteCount > MAX_TEXT_BYTES,
                    shape = RoundedCornerShape(13.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        errorIndicatorColor = Color.Transparent,
                    ),
                )
                AnimatedContent(
                    targetState = draft.isNotBlank(),
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "composer-action",
                ) { hasText ->
                    if (hasText) {
                        Button(
                            onClick = onSend,
                            enabled = !sending && byteCount <= MAX_TEXT_BYTES,
                            modifier = Modifier.height(48.dp).widthIn(min = 68.dp),
                            shape = RoundedCornerShape(13.dp),
                        ) {
                            if (sending) CircularProgressIndicator(Modifier.size(19.dp), strokeWidth = 2.dp) else Text("发送", fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        Surface(
                            onClick = onAttachment,
                            modifier = Modifier.size(48.dp),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ) { Box(contentAlignment = Alignment.Center) { Text("＋", fontSize = 27.sp, fontWeight = FontWeight.Light) } }
                    }
                }
            }
            if (byteCount > 90 * 1024) {
                Text(
                    "${(byteCount + 1023) / 1024} / 100 KB",
                    modifier = Modifier.fillMaxWidth().padding(top = 3.dp, end = 59.dp),
                    textAlign = TextAlign.End,
                    color = if (byteCount > MAX_TEXT_BYTES) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun SearchScreen(state: TimelineUiState, onBack: () -> Unit, onQueryChange: (String) -> Unit, onSearch: () -> Unit, onLocate: (String) -> Unit) {
    BackHandler(onBack = onBack)
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            Row(Modifier.fillMaxWidth().padding(AppSpacing.medium), verticalAlignment = Alignment.CenterVertically) { TextButton(onClick = onBack) { Text("返回") }; Text("搜索消息与文件名", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
            Row(Modifier.padding(horizontal = AppSpacing.large), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                OutlinedTextField(state.searchQuery, onQueryChange, Modifier.weight(1f), singleLine = true, placeholder = { Text("输入关键词") }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { onSearch() }), shape = MaterialTheme.shapes.medium)
                Button(onSearch, enabled = state.searchQuery.isNotBlank() && !state.isSearching, modifier = Modifier.height(56.dp)) { Text(if (state.isSearching) "搜索中" else "搜索") }
            }
            HorizontalDivider(Modifier.padding(top = AppSpacing.large))
            if (state.searchResults.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("搜索文字内容或原始文件名", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(AppSpacing.large), verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                items(state.searchResults, key = TimelineMessage::id) { message ->
                    Surface(onClick = { onLocate(message.id) }, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.padding(AppSpacing.medium)) {
                            Text(message.textContent ?: message.file?.originalFilename ?: "文件消息", maxLines = 3, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(AppSpacing.small)); Text("${if (message.sourceDeviceType == "android_master") "Android" else "Windows"} · ${formatMessageTime(message.createdAt)}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AttachmentSheet(onDismiss: () -> Unit, onPhotos: () -> Unit, onFiles: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(AppSpacing.extraLarge), verticalArrangement = Arrangement.spacedBy(AppSpacing.medium)) {
            Text("添加内容", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Surface(onClick = onPhotos, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) { Column(Modifier.fillMaxWidth().padding(AppSpacing.large)) { Text("照片", fontWeight = FontWeight.Bold); Text("系统 Photo Picker · 最多 20 张 · 原图不压缩", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            Surface(onClick = onFiles, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) { Column(Modifier.fillMaxWidth().padding(AppSpacing.large)) { Text("文件", fontWeight = FontWeight.Bold); Text("单个 300 MB · 单批 500 MB", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            Spacer(Modifier.height(AppSpacing.small))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(
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

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(AppSpacing.extraLarge),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.medium),
        ) {
            Text("简单设置", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("外观", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                listOf(ThemeMode.System to "跟随系统", ThemeMode.Light to "浅色", ThemeMode.Dark to "深色").forEach { (value, label) ->
                    if (mode == value) Button({ onChange(value) }) { Text(label) } else OutlinedButton({ onChange(value) }) { Text(label) }
                }
            }
            HorizontalDivider()
            Text("文件保存", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(folderLabel?.let { "默认保存位置：$it" } ?: "未设置默认保存位置，每次下载时询问。")
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                OutlinedButton(onClick = onChooseDefaultFolder) { Text(if (folderLabel == null) "选择文件夹" else "更改文件夹") }
                if (folderLabel != null) TextButton(onClick = onClearDefaultFolder) { Text("清除") }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("传输结果通知", fontWeight = FontWeight.SemiBold)
                    Text("上传或下载完成、失败时通知", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = appSettings.notificationsEnabled, onCheckedChange = onNotificationsChanged)
            }
            HorizontalDivider()
            Text("服务器", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (transferBusy) Text("传输进行中，完成后才能切换或删除服务器。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            serverProfiles.forEach { profile ->
                val status = serverProfileStatus(
                    isActive = profile.id == activeProfileId,
                    isConnected = connection == TimelineConnectionState.Connected,
                    isConnecting = connection == TimelineConnectionState.Connecting,
                )
                val statusLabel = serverProfileStatusLabel(status)
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Column(Modifier.fillMaxWidth().padding(AppSpacing.medium), verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                        Text("${profile.name}${if (profile.id == activeProfileId) " · 当前" else ""}", fontWeight = FontWeight.Bold)
                        Text(profile.serverAddress, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            statusLabel,
                            color = if (status == ServerProfileDisplayStatus.Connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                            if (profile.id != activeProfileId) {
                                TextButton(
                                    enabled = !transferBusy && !serverActionRunning,
                                    onClick = {
                                        serverActionRunning = true
                                        serverError = null
                                        scope.launch {
                                            onSwitchServer(profile.id)
                                                .onFailure { serverError = it.message ?: "无法切换服务器" }
                                            serverActionRunning = false
                                        }
                                    },
                                ) { Text("切换") }
                            }
                            TextButton(onClick = { renameTarget = profile; renameValue = profile.name }) { Text("重命名") }
                            TextButton(enabled = !transferBusy && !serverActionRunning, onClick = { deleteTarget = profile }) { Text("删除") }
                        }
                    }
                }
            }
            serverError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
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
    }
    renameTarget?.let { profile ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名服务器") },
            text = { OutlinedTextField(renameValue, { renameValue = it }, singleLine = true, label = { Text("档案名称") }) },
            confirmButton = { TextButton(onClick = { if (onRenameServer(profile.id, renameValue)) renameTarget = null }, enabled = renameValue.isNotBlank()) { Text("保存") } },
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
private fun ImageViewer(state: ViewerState, loadImage: suspend (TimelineMessage, Boolean) -> Bitmap?, onDownload: (TimelineMessage) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val pager = rememberPagerState(initialPage = state.initialIndex, pageCount = { state.images.size })
    Surface(Modifier.fillMaxSize(), color = androidx.compose.ui.graphics.Color(0xFF080A0D), contentColor = androidx.compose.ui.graphics.Color.White) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().height(64.dp).padding(horizontal = AppSpacing.medium), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("返回", color = androidx.compose.ui.graphics.Color.White) }
                Text("${pager.currentPage + 1} / ${state.images.size}", Modifier.weight(1f), textAlign = TextAlign.Center)
                val current = state.images[pager.currentPage]
                TextButton(onClick = { onDownload(current) }, enabled = current.file?.status == "available") { Text(if (current.file?.status == "available") "下载" else "已过期", color = androidx.compose.ui.graphics.Color.White) }
            }
            HorizontalPager(pager, Modifier.weight(1f)) { page ->
                val message = state.images[page]
                val bitmap by produceState<Bitmap?>(null, message.id, message.file?.status) { value = loadImage(message, true) }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (bitmap == null) CircularProgressIndicator(color = androidx.compose.ui.graphics.Color.White)
                    else Image(requireNotNull(bitmap).asImageBitmap(), message.file?.originalFilename, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                }
            }
            Text(state.images[pager.currentPage].file?.originalFilename.orEmpty(), Modifier.fillMaxWidth().navigationBarsPadding().padding(AppSpacing.medium), textAlign = TextAlign.Center, color = androidx.compose.ui.graphics.Color.LightGray, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable private fun LoadingTimeline() = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator(); Spacer(Modifier.height(AppSpacing.medium)); Text("正在同步时间线", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
@Composable private fun EmptyTimeline() = Box(Modifier.fillMaxSize().padding(AppSpacing.extraLarge), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Box(Modifier.size(68.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(22.dp)), contentAlignment = Alignment.Center) { Text("↗", color = MaterialTheme.colorScheme.onPrimaryContainer, fontSize = 28.sp, fontWeight = FontWeight.Bold) }; Spacer(Modifier.height(AppSpacing.large)); Text("发送第一条内容", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Spacer(Modifier.height(AppSpacing.small)); Text("输入文字，或点击＋选择照片和文件。", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center) } }

private fun groupMessages(messages: List<TimelineMessage>): List<MessageGroup> {
    val result = mutableListOf<MessageGroup>()
    messages.forEach { message ->
        val last = result.lastOrNull()
        if (message.type == "image" && message.batchId != null && last?.messages?.firstOrNull()?.batchId == message.batchId && last.messages.first().sourceDeviceId == message.sourceDeviceId) {
            result[result.lastIndex] = last.copy(messages = last.messages + message)
        } else result += MessageGroup(if (message.type == "image" && message.batchId != null) "batch-${message.batchId}" else message.id, listOf(message))
    }
    return result
}
private fun messageShape(own: Boolean) = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = if (own) 20.dp else 6.dp, bottomEnd = if (own) 6.dp else 20.dp)
private fun fileExtension(value: String) = value.substringAfterLast('.', "FILE").uppercase().take(5)
private fun formatBytes(bytes: Long) = when { bytes < 1024 -> "$bytes B"; bytes < 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0); else -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1024.0 / 1024.0) }
private fun fileStatus(file: FileAttachment) = if (file.status == "available") "可下载" else "已过期"
private fun formatMessageTime(value: String): String = runCatching { val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }; val parsed = requireNotNull(parser.parse(value.take(19))); SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(parsed) }.getOrElse { value.take(16).replace('T', ' ') }
private const val MAX_TEXT_BYTES = 100 * 1024
