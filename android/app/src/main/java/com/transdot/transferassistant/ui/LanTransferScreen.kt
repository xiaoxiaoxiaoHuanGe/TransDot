package com.transdot.transferassistant.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.transdot.transferassistant.R
import com.transdot.transferassistant.ui.components.AppStatusPanel
import com.transdot.transferassistant.ui.components.AppTopBar
import com.transdot.transferassistant.ui.components.StatusTone
import com.transdot.transferassistant.ui.theme.AppSpacing

@Composable
fun LanTransferScreen(
    state: LanTransferUiState,
    receiveFolderLabel: String?,
    onFilesSelected: (List<Uri>) -> Unit,
    onReceiveFolderSelected: (Uri) -> Unit,
    onRetry: (String) -> Unit,
    onCancel: () -> Unit,
    onReconnect: () -> Unit,
    onClearError: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) onFilesSelected(uris)
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) onReceiveFolderSelected(uri)
    }
    val connected = state.connection == LanConnectionStatus.Connected
    val connectionText = when (state.connection) {
        LanConnectionStatus.Waiting -> "等待电脑进入局域网快传"
        LanConnectionStatus.Connecting -> "正在建立局域网直连（最多 8 秒）"
        LanConnectionStatus.Connected -> "已直连，文件不会经过服务器"
        LanConnectionStatus.Failed -> "局域网直连失败"
        LanConnectionStatus.Closed -> "连接已关闭"
    }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { AppTopBar("局域网快传", subtitle = connectionText, onBack = onBack) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = AppSpacing.large, vertical = AppSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.medium),
        ) {
            item("connection") {
                AppStatusPanel(
                    tone = when (state.connection) {
                        LanConnectionStatus.Connected -> StatusTone.Success
                        LanConnectionStatus.Failed -> StatusTone.Error
                        else -> StatusTone.Info
                    },
                    title = connectionText,
                    message = receiveFolderLabel?.let { "自动接收到：$it" } ?: "接收前需要选择一次保存文件夹",
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            item("actions") {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.small),
                ) {
                    Button(
                        onClick = { filePicker.launch(arrayOf("*/*")) },
                        enabled = connected,
                        modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
                    ) {
                        Icon(painterResource(R.drawable.ic_send), contentDescription = null, Modifier.size(20.dp))
                        Spacer(Modifier.width(AppSpacing.small))
                        Text("选择文件")
                    }
                    OutlinedButton(
                        onClick = { folderPicker.launch(null) },
                        modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
                    ) {
                        Icon(painterResource(R.drawable.ic_download), contentDescription = null, Modifier.size(20.dp))
                        Spacer(Modifier.width(AppSpacing.small))
                        Text(if (receiveFolderLabel == null) "选择接收文件夹" else "更改接收文件夹")
                    }
                }
            }
            state.error?.let { error ->
                item("error") {
                    AppStatusPanel(StatusTone.Error, lanErrorTitle(error), lanErrorMessage(error))
                    if (state.connection == LanConnectionStatus.Failed) {
                        OutlinedButton(onClick = onReconnect, modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)) {
                            Text("重新连接")
                        }
                    }
                    TextButton(onClick = onClearError, modifier = Modifier.sizeIn(minHeight = 48.dp)) { Text("关闭提示") }
                }
            }
            if (state.items.isEmpty()) {
                item("empty") {
                    Column(Modifier.fillMaxWidth().padding(vertical = AppSpacing.huge), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(painterResource(R.drawable.ic_file), contentDescription = null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(AppSpacing.medium))
                        Text("尚无局域网传输", style = MaterialTheme.typography.titleMedium)
                    }
                }
            } else {
                items(state.items, key = LanTransferItem::id) { item ->
                    LanTransferRow(
                        item = item,
                        active = state.currentFileId == item.id,
                        canRetry = connected,
                        onRetry = { onRetry(item.id) },
                        onCancel = onCancel,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun LanTransferRow(
    item: LanTransferItem,
    active: Boolean,
    canRetry: Boolean,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(vertical = AppSpacing.small), verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Icon(
                painterResource(if (item.direction == LanTransferDirection.Sending) R.drawable.ic_upload else R.drawable.ic_download),
                contentDescription = null,
                modifier = Modifier.padding(top = 2.dp).size(20.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(AppSpacing.medium))
            Column(Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "${statusLabel(item.status)} · ${formatBytes(item.transferredBytes)} / ${formatBytes(item.size)}" +
                        if (item.status == LanTransferStatus.Transferring) " · ${formatBytes(item.speedBytesPerSecond)}/s" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when {
                active -> TextButton(onClick = onCancel, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) { Text("取消") }
                canRetry && item.direction == LanTransferDirection.Sending && item.status in setOf(LanTransferStatus.Failed, LanTransferStatus.Cancelled) ->
                    TextButton(onClick = onRetry, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) { Text("重试") }
            }
        }
        if (item.status == LanTransferStatus.Transferring) {
            LinearProgressIndicator(progress = { item.progress }, modifier = Modifier.fillMaxWidth(), gapSize = 0.dp)
        }
    }
}

private fun statusLabel(status: LanTransferStatus) = when (status) {
    LanTransferStatus.Queued -> "等待中"
    LanTransferStatus.Transferring -> "传输中"
    LanTransferStatus.Completed -> "已完成"
    LanTransferStatus.Failed -> "失败"
    LanTransferStatus.Cancelled -> "已取消"
}

private fun lanErrorTitle(code: String) = when (code) {
    "DESTINATION_UNAVAILABLE" -> "接收文件夹不可用"
    "LAN_DIRECT_TIMEOUT" -> "8 秒内未能直连"
    "LAN_PEER_OFFLINE" -> "电脑已离线"
    "FILE_HASH_MISMATCH" -> "文件校验失败"
    "TOO_MANY_FILES" -> "一次最多选择 20 个文件"
    else -> "局域网传输失败"
}

private fun lanErrorMessage(code: String) = when (code) {
    "LAN_DIRECT_TIMEOUT", "LAN_PEER_OFFLINE", "LAN_DATA_CHANNEL_CLOSED" ->
        "不会转为云端传输。请检查两端网络，返回时间线后重新进入快传。"
    else -> "不会转为云端传输，请检查文件或重试。"
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
