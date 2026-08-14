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
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import com.transdot.transferassistant.R
import com.transdot.transferassistant.ui.components.AppEmptyState
import com.transdot.transferassistant.ui.components.AppIconButton
import com.transdot.transferassistant.ui.components.AppStatusPanel
import com.transdot.transferassistant.ui.components.AppTopBar
import com.transdot.transferassistant.ui.components.StatusTone
import com.transdot.transferassistant.ui.theme.AppMotion
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

internal data class ViewerState(val images: List<TimelineMessage>, val initialIndex: Int)
private data class MessageGroup(val key: String, val messages: List<TimelineMessage>)
private const val ACTION_NOTICE_TOTAL_DURATION_MS = 1_500L
private const val ACTION_NOTICE_EXIT_DURATION_MS = AppMotion.fastMillis

private data class ActionNotice(val id: Int, val message: String, val visible: Boolean = true)

@Composable
private fun ActionNoticeOverlay(notice: ActionNotice?, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = notice?.visible == true,
        modifier = modifier,
        enter = fadeIn(animationSpec = tween(durationMillis = AppMotion.fastMillis)),
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
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_down),
                            contentDescription = "跳到最新消息",
                            modifier = Modifier.size(20.dp),
                        )
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
internal fun TimelineTopBar(
    activeServerName: String,
    connection: TimelineConnectionState,
    onPairWindows: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
) {
    val statusLine = activeServerStatusLine(
        activeServerName,
        serverProfileStatus(
            isActive = true,
            isConnected = connection == TimelineConnectionState.Connected,
            isConnecting = connection == TimelineConnectionState.Connecting,
        ),
    )
    AppTopBar(title = "传输助手", subtitle = statusLine) {
        TextButton(onClick = onPairWindows) { Text("配对") }
        AppIconButton(
            iconRes = R.drawable.ic_search,
            contentDescription = "搜索消息",
            onClick = onSearch,
        )
        AppIconButton(
            iconRes = R.drawable.ic_settings,
            contentDescription = "打开设置",
            onClick = onSettings,
        )
    }
}

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
