package com.transdot.transferassistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.transdot.transferassistant.data.BrowserDevice
import com.transdot.transferassistant.data.browserLastSeenLabel
import com.transdot.transferassistant.data.browserAuthorizedLabel
import com.transdot.transferassistant.ui.components.AppEmptyState
import com.transdot.transferassistant.ui.components.AppStatusPanel
import com.transdot.transferassistant.ui.components.StatusTone
import com.transdot.transferassistant.ui.theme.AppSpacing
import java.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BrowserDevicesSheet(
    state: BrowserDevicesUiState,
    onRefresh: () -> Unit,
    onRename: (String, String) -> Unit,
    onRevoke: (String) -> Unit,
    onClearError: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        BrowserDevicesSheetContent(state, onRefresh, onRename, onRevoke, onClearError)
    }
}

@Composable
internal fun BrowserDevicesSheetContent(
    state: BrowserDevicesUiState,
    onRefresh: () -> Unit,
    onRename: (String, String) -> Unit,
    onRevoke: (String) -> Unit,
    onClearError: () -> Unit,
) {
    var renameTarget by remember { mutableStateOf<BrowserDevice?>(null) }
    var renameValue by remember { mutableStateOf("") }
    var revokeTarget by remember { mutableStateOf<BrowserDevice?>(null) }

    Column(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = AppSpacing.extraLarge),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.medium),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("已授权浏览器", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "${state.devices.size} / ${state.maximumCount}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            OutlinedButton(onClick = onRefresh, enabled = !state.refreshing && state.actionDeviceId == null) {
                Text(if (state.refreshing) "刷新中" else "刷新")
            }
        }
        state.errorMessage?.let {
            AppStatusPanel(StatusTone.Error, "设备操作失败", it)
            TextButton(onClick = onClearError) { Text("关闭提示") }
        }
        when {
            state.loading -> Column(
                Modifier.fillMaxWidth().padding(vertical = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(AppSpacing.medium),
            ) {
                CircularProgressIndicator()
                Text("正在加载浏览器设备")
            }
            state.devices.isEmpty() -> AppEmptyState(
                iconRes = com.transdot.transferassistant.R.drawable.ic_shield,
                title = "还没有已授权浏览器",
                message = "在电脑网页生成二维码，再用 Android 扫码添加。",
                modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
            )
            else -> LazyColumn(
                verticalArrangement = Arrangement.spacedBy(AppSpacing.small),
            ) {
                items(state.devices, key = BrowserDevice::id) { device ->
                    BrowserDeviceCard(
                        device = device,
                        busy = state.actionDeviceId == device.id,
                        actionsEnabled = state.actionDeviceId == null,
                        onRename = { renameTarget = device; renameValue = device.displayName },
                        onRevoke = { revokeTarget = device },
                    )
                }
                item { Spacer(Modifier.height(AppSpacing.large)) }
            }
        }
    }

    renameTarget?.let { device ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名浏览器") },
            text = {
                OutlinedTextField(
                    value = renameValue,
                    onValueChange = { renameValue = it },
                    singleLine = true,
                    label = { Text("设备名称") },
                    supportingText = { Text("1–64 个字符") },
                )
            },
            confirmButton = {
                Button(
                    onClick = { onRename(device.id, renameValue.trim()); renameTarget = null },
                    enabled = renameValue.trim().length in 1..64,
                ) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("取消") } },
        )
    }
    revokeTarget?.let { device ->
        AlertDialog(
            onDismissRequest = { revokeTarget = null },
            title = { Text("撤销“${device.displayName}”？") },
            text = { Text("该浏览器会立即退出，之后必须重新扫码授权。其他浏览器不会受影响。") },
            confirmButton = {
                Button(
                    onClick = { onRevoke(device.id); revokeTarget = null },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text("撤销授权") }
            },
            dismissButton = { TextButton(onClick = { revokeTarget = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun BrowserDeviceCard(
    device: BrowserDevice,
    busy: Boolean,
    actionsEnabled: Boolean,
    onRename: () -> Unit,
    onRevoke: () -> Unit,
) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(
            Modifier.fillMaxWidth().padding(AppSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.small),
        ) {
            Text(device.displayName, style = MaterialTheme.typography.titleMedium)
            Text(
                if (busy) "正在处理…" else "最近活动：${browserLastSeenLabel(device.lastSeenAt, Instant.now().toString())}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "授权时间：${browserAuthorizedLabel(device.createdAt)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                TextButton(
                    onClick = onRename,
                    enabled = actionsEnabled,
                    modifier = Modifier.semantics { contentDescription = "重命名 ${device.displayName}" },
                ) { Text("重命名") }
                TextButton(
                    onClick = onRevoke,
                    enabled = actionsEnabled,
                    modifier = Modifier.semantics { contentDescription = "撤销 ${device.displayName}" },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("撤销") }
            }
        }
    }
}
