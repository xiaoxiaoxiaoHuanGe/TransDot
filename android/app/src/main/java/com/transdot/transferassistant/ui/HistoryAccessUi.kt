package com.transdot.transferassistant.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.transdot.transferassistant.data.HistoryAccess
import com.transdot.transferassistant.data.HistoryRepository
import com.transdot.transferassistant.data.StoredSession
import kotlinx.coroutines.launch
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

internal val LocalHistorySession = staticCompositionLocalOf<StoredSession?> { null }
internal val LocalHistoryRevision = staticCompositionLocalOf { 0 }

@Composable
internal fun HistoryAccessSection() {
    val session = LocalHistorySession.current ?: return
    val revision = LocalHistoryRevision.current
    val repository = remember { HistoryRepository() }
    val scope = rememberCoroutineScope()
    var devices by remember(session.deviceId) { mutableStateOf<List<HistoryAccess>>(emptyList()) }
    var error by remember(session.deviceId) { mutableStateOf<String?>(null) }
    var loading by remember(session.deviceId) { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var resumeTick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumeTick += 1 }
    suspend fun refresh() {
        loading = true
        runCatching { repository.list(session) }.onSuccess { devices = it; error = null }.onFailure { error = it.message }
        loading = false
    }
    LaunchedEffect(session.deviceId, revision, resumeTick) { refresh() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("浏览器历史权限", style = MaterialTheme.typography.titleMedium)
        Text("新浏览器只看得到授权后的消息。允许后可查看全部历史。", style = MaterialTheme.typography.bodySmall)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        devices.forEach { device ->
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(device.displayName, style = MaterialTheme.typography.titleSmall)
                    Text("授权时间：${formatMessageTime(device.createdAt)}", style = MaterialTheme.typography.bodySmall)
                    device.requestedAt?.let { Text("申请时间：${formatMessageTime(it)}", style = MaterialTheme.typography.bodySmall) }
                    Text(if (device.allowed) "已允许查看全部历史" else if (device.status == "pending") "等待审批" else "仅可查看授权后的消息", style = MaterialTheme.typography.bodySmall)
                    Row {
                        val actions = if (device.allowed) listOf("revoke" to "撤销历史权限") else if (device.status == "pending") listOf("approve" to "允许全部历史", "reject" to "拒绝") else emptyList()
                        actions.forEach { (decision, label) -> TextButton(enabled = !busy, onClick = {
                            busy = true
                            scope.launch {
                                runCatching { repository.decide(session,device.deviceId,decision) }
                                    .onSuccess { refresh() }.onFailure { error = it.message }
                                busy = false
                            }
                        }) { Text(label) } }
                    }
                }
            }
        }
        TextButton(onClick = { scope.launch { refresh() } }, enabled = !loading && !busy) { Text("刷新历史权限") }
    }
}

@Composable
internal fun PendingHistoryNotice() {
    val session = LocalHistorySession.current ?: return
    val revision = LocalHistoryRevision.current
    val repository = remember { HistoryRepository() }
    var count by remember(session.deviceId) { mutableIntStateOf(0) }
    var open by remember { mutableStateOf(false) }
    var resumeTick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumeTick += 1 }
    LaunchedEffect(session.deviceId, revision, resumeTick) { count = runCatching { repository.list(session).count { it.status == "pending" } }.getOrDefault(0) }
    if (count > 0) TextButton(onClick = { open = true }) { Text("$count 个浏览器申请查看历史 · 处理") }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text("浏览器历史授权") },
        text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) { HistoryAccessSection() } },
        confirmButton = { TextButton(onClick = { open = false }) { Text("关闭") } })
}
