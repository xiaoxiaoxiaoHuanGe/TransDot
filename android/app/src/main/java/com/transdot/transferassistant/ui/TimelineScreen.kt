package com.transdot.transferassistant.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.transdot.transferassistant.data.TimelineMessage
import com.transdot.transferassistant.ui.theme.AppSpacing
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

@Composable
fun TimelineScreen(
    state: TimelineUiState,
    ownDeviceId: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onLoadOlder: () -> Unit,
    onRequestDelete: (TimelineMessage) -> Unit,
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
    AnimatedContent(
        targetState = state.searchOpen,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "timeline-search",
    ) { searchOpen ->
        if (searchOpen) {
            SearchScreen(
                state = state,
                onBack = onCloseSearch,
                onQueryChange = onSearchQueryChange,
                onSearch = onSearch,
                onLocate = onLocate,
            )
        } else {
            TimelineHome(
                state = state,
                ownDeviceId = ownDeviceId,
                onDraftChange = onDraftChange,
                onSend = onSend,
                onLoadOlder = onLoadOlder,
                onRequestDelete = onRequestDelete,
                onOpenSearch = onOpenSearch,
                onPairWindows = onPairWindows,
                onClearHighlight = onClearHighlight,
            )
        }
    }

    state.deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = onCancelDelete,
            title = { Text("删除这条消息？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.medium)) {
                    Text("删除会同步到 Windows，并同时从全文搜索索引移除。")
                    Text(
                        target.textContent.orEmpty(),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                Button(onClick = onConfirmDelete, enabled = !state.isDeleting) {
                    Text(if (state.isDeleting) "删除中" else "删除")
                }
            },
            dismissButton = {
                TextButton(onClick = onCancelDelete, enabled = !state.isDeleting) { Text("取消") }
            },
        )
    }

    if (state.credentialInvalid) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Android Master 凭据不可用") },
            text = { Text(state.errorMessage ?: "请检查服务器数据是否被重置。") },
            confirmButton = { TextButton(onClick = onClearError) { Text("知道了") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TimelineHome(
    state: TimelineUiState,
    ownDeviceId: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onLoadOlder: () -> Unit,
    onRequestDelete: (TimelineMessage) -> Unit,
    onOpenSearch: () -> Unit,
    onPairWindows: () -> Unit,
    onClearHighlight: () -> Unit,
) {
    val listState = rememberLazyListState()
    val wasNearBottom by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val total = listState.layoutInfo.totalItemsCount
            total == 0 || lastVisible >= total - 3
        }
    }

    LaunchedEffect(state.messages.lastOrNull()?.id) {
        if (wasNearBottom && state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex + if (state.nextBefore != null) 1 else 0)
        }
    }
    LaunchedEffect(state.highlightedMessageId) {
        val targetId = state.highlightedMessageId ?: return@LaunchedEffect
        val index = state.messages.indexOfFirst { it.id == targetId }
        if (index >= 0) {
            listState.animateScrollToItem(index + if (state.nextBefore != null) 1 else 0)
            delay(2_600)
            onClearHighlight()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TimelineTopBar(
                connectionState = state.connectionState,
                onOpenSearch = onOpenSearch,
                onPairWindows = onPairWindows,
            )
        },
        bottomBar = {
            MessageComposer(
                draft = state.draft,
                sending = state.isSending,
                errorMessage = state.errorMessage,
                onDraftChange = onDraftChange,
                onSend = onSend,
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when {
                state.isInitialLoading -> LoadingTimeline()
                state.messages.isEmpty() -> EmptyTimeline()
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = AppSpacing.large,
                        vertical = AppSpacing.large,
                    ),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.large),
                ) {
                    if (state.nextBefore != null) {
                        item(key = "load-older") {
                            TextButton(
                                onClick = onLoadOlder,
                                enabled = !state.isLoadingOlder,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(if (state.isLoadingOlder) "正在加载…" else "加载更早消息")
                            }
                        }
                    }
                    items(state.messages, key = TimelineMessage::id) { message ->
                        val own = message.sourceDeviceId == ownDeviceId
                        MessageBubble(
                            message = message,
                            own = own,
                            highlighted = state.highlightedMessageId == message.id,
                            modifier = Modifier.animateItem(),
                            onDelete = { onRequestDelete(message) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TimelineTopBar(
    connectionState: TimelineConnectionState,
    onOpenSearch: () -> Unit,
    onPairWindows: () -> Unit,
) {
    Surface(tonalElevation = 2.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.large, vertical = AppSpacing.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("传输助手", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(7.dp)
                            .background(
                                if (connectionState == TimelineConnectionState.Connected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline,
                                CircleShape,
                            ),
                    )
                    Text(
                        text = when (connectionState) {
                            TimelineConnectionState.Connected -> "实时连接已建立"
                            TimelineConnectionState.Connecting -> "正在连接服务器"
                            TimelineConnectionState.Offline -> "等待重新连接"
                        },
                        modifier = Modifier.padding(start = AppSpacing.small),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            TextButton(onClick = onPairWindows) { Text("配对") }
            IconButton(onClick = onOpenSearch) {
                Text("⌕", fontSize = 28.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: TimelineMessage,
    own: Boolean,
    highlighted: Boolean,
    modifier: Modifier = Modifier,
    onDelete: () -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (own) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 320.dp),
            horizontalAlignment = if (own) Alignment.End else Alignment.Start,
        ) {
            Text(
                text = "${if (message.sourceDeviceType == "android_master") "Android" else "Windows"} · ${formatMessageTime(message.createdAt)}",
                modifier = Modifier.padding(horizontal = AppSpacing.small, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Surface(
                modifier = Modifier
                    .clip(
                        RoundedCornerShape(
                            topStart = 20.dp,
                            topEnd = 20.dp,
                            bottomStart = if (own) 20.dp else 6.dp,
                            bottomEnd = if (own) 6.dp else 20.dp,
                        ),
                    )
                    .combinedClickable(onClick = {}, onLongClick = onDelete),
                color = when {
                    highlighted -> MaterialTheme.colorScheme.tertiaryContainer
                    own -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.surfaceContainerHigh
                },
                contentColor = when {
                    highlighted -> MaterialTheme.colorScheme.onTertiaryContainer
                    own -> MaterialTheme.colorScheme.onPrimary
                    else -> MaterialTheme.colorScheme.onSurface
                },
                shadowElevation = if (highlighted) 6.dp else 1.dp,
            ) {
                Column(modifier = Modifier.padding(horizontal = AppSpacing.medium, vertical = AppSpacing.small)) {
                    Text(
                        text = message.textContent.orEmpty(),
                        style = MaterialTheme.typography.bodyLarge,
                        lineHeight = 24.sp,
                    )
                    TextButton(
                        onClick = onDelete,
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Text(
                            "删除",
                            color = if (own && !highlighted) MaterialTheme.colorScheme.onPrimary.copy(alpha = .72f)
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
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
) {
    val focusManager = LocalFocusManager.current
    val byteCount = draft.toByteArray().size
    Surface(tonalElevation = 3.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = AppSpacing.medium, vertical = AppSpacing.small),
        ) {
            AnimatedVisibility(visible = errorMessage != null) {
                Text(
                    text = errorMessage.orEmpty(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(12.dp))
                        .padding(AppSpacing.small),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = if (errorMessage != null) AppSpacing.small else 0.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.small),
            ) {
                IconButton(onClick = {}, enabled = false) { Text("＋", fontSize = 24.sp) }
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    modifier = Modifier.weight(1f),
                    minLines = 1,
                    maxLines = 5,
                    placeholder = { Text("输入内容……") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        if (draft.isNotBlank() && byteCount <= MAX_TEXT_BYTES) {
                            onSend()
                            focusManager.clearFocus()
                        }
                    }),
                    supportingText = if (byteCount > 90 * 1024) {
                        { Text("${(byteCount + 1023) / 1024} / 100 KB") }
                    } else null,
                    isError = byteCount > MAX_TEXT_BYTES,
                    shape = MaterialTheme.shapes.large,
                )
                Button(
                    onClick = onSend,
                    enabled = draft.isNotBlank() && !sending && byteCount <= MAX_TEXT_BYTES,
                    modifier = Modifier.height(56.dp),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    if (sending) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Text("发送", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun SearchScreen(
    state: TimelineUiState,
    onBack: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onLocate: (String) -> Unit,
) {
    BackHandler(onBack = onBack)
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(AppSpacing.medium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) { Text("返回") }
                Text("搜索消息", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Row(
                modifier = Modifier.padding(horizontal = AppSpacing.large),
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
            AnimatedVisibility(visible = state.errorMessage != null) {
                Text(
                    text = state.errorMessage.orEmpty(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AppSpacing.large, vertical = AppSpacing.small)
                        .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(12.dp))
                        .padding(AppSpacing.small),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            HorizontalDivider(Modifier.padding(top = AppSpacing.large))
            if (state.searchResults.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("使用 SQLite FTS5 搜索文字消息", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(AppSpacing.large),
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
                                    message.textContent.orEmpty(),
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyLarge,
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

@Composable
private fun LoadingTimeline() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(AppSpacing.medium))
            Text("正在同步时间线", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EmptyTimeline() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(AppSpacing.extraLarge),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(68.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(22.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text("Aa", color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(AppSpacing.large))
            Text("从第一条文字开始", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(AppSpacing.small))
            Text(
                "文字会长期保存在服务器；文件功能将在下一阶段开放。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private fun formatMessageTime(value: String): String = runCatching {
    val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    val parsed = requireNotNull(parser.parse(value.take(19)))
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(parsed)
}.getOrElse { value.take(16).replace('T', ' ') }

private const val MAX_TEXT_BYTES = 100 * 1024
