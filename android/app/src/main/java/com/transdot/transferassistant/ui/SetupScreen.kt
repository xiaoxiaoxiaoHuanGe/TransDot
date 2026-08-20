package com.transdot.transferassistant.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.transdot.transferassistant.ui.components.AppBrandMark
import com.transdot.transferassistant.ui.components.AppStatusPanel
import com.transdot.transferassistant.ui.components.StatusTone
import com.transdot.transferassistant.ui.theme.AppSpacing

@Composable
fun SetupScreen(
    state: SetupUiState,
    allowCleartext: Boolean,
    onServerAddressChange: (String) -> Unit,
    onSetupTokenChange: (String) -> Unit,
    onClaim: () -> Unit,
    onBootstrapQRCode: (String) -> Unit = {},
    onConfirmBootstrap: () -> Unit = {},
    onCancelBootstrap: () -> Unit = {},
) {
    var scanning by remember { mutableStateOf(false) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = AppSpacing.extraLarge, vertical = AppSpacing.huge),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.extraLarge),
            ) {
                AppBrandMark()
                Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                    Text(
                        text = "连接你的私人空间",
                        modifier = Modifier.semantics { heading() },
                        style = MaterialTheme.typography.headlineLarge,
                    )
                    Text(
                        text = "输入服务器地址和部署时生成的初始化密钥。这台手机随后会成为唯一 Android 主设备。",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (scanning) {
                    SetupScanner(onQRCode = { scanning = false; onBootstrapQRCode(it) }, onBack = { scanning = false })
                } else SetupForm(
                    state = state,
                    allowCleartext = allowCleartext,
                    onServerAddressChange = onServerAddressChange,
                    onSetupTokenChange = onSetupTokenChange,
                    onClaim = onClaim,
                    onScan = { scanning = true },
                )
            }
        }
    }
    state.bootstrapPayload?.let { payload ->
        AlertDialog(
            onDismissRequest = onCancelBootstrap,
            title = { Text("绑定这台服务器？") },
            text = { Text("${payload.serverAddress}\n实例指纹 ${payload.instanceFingerprint.uppercase()}") },
            confirmButton = { Button(onClick = onConfirmBootstrap, enabled = !state.isSubmitting) { Text("确认绑定") } },
            dismissButton = { TextButton(onClick = onCancelBootstrap, enabled = !state.isSubmitting) { Text("取消") } },
        )
    }
}

@Composable
private fun SetupForm(
    state: SetupUiState,
    allowCleartext: Boolean,
    onServerAddressChange: (String) -> Unit,
    onSetupTokenChange: (String) -> Unit,
    onClaim: () -> Unit,
    onScan: () -> Unit,
) {
    var showToken by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(AppSpacing.extraLarge),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.large),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.tiny)) {
                Text(text = "服务器与密钥", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "正式部署请使用 HTTPS 域名。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            OutlinedTextField(
                value = state.serverAddress,
                onValueChange = onServerAddressChange,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isSubmitting && !state.needsSecureStorageRetry,
                singleLine = true,
                label = { Text("服务器地址") },
                placeholder = { Text("https://transfer.example.com") },
                supportingText = { Text("将自动检查地址和服务状态") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                shape = MaterialTheme.shapes.medium,
            )

            OutlinedTextField(
                value = state.setupToken,
                onValueChange = onSetupTokenChange,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isSubmitting && !state.needsSecureStorageRetry,
                singleLine = true,
                label = { Text("初始化密钥") },
                supportingText = { Text("成功后将使用 Android Keystore 加密保存") },
                visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { showToken = !showToken }) {
                        Text(if (showToken) "隐藏" else "显示")
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                shape = MaterialTheme.shapes.medium,
            )

            if (allowCleartext) {
                AppStatusPanel(
                    tone = StatusTone.Info,
                    title = "调试连接",
                    message = "调试包允许局域网 HTTP；正式版本仍强制 HTTPS。",
                )
            }

            AnimatedVisibility(visible = state.errorMessage != null) {
                AppStatusPanel(
                    tone = StatusTone.Error,
                    title = "无法完成初始化",
                    message = state.errorMessage.orEmpty(),
                )
            }

            Button(
                onClick = onClaim,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                enabled = !state.isSubmitting,
                shape = MaterialTheme.shapes.medium,
            ) {
                if (state.isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(if (state.needsSecureStorageRetry) "重试安全保存" else "连接并初始化")
                }
            }
            OutlinedButton(onClick = onScan, modifier = Modifier.fillMaxWidth(), enabled = !state.isSubmitting) {
                Text("扫码连接服务器")
            }
        }
    }
}

@Composable
private fun SetupScanner(onQRCode: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AppSpacing.large)) {
        Text("扫描网页绑定二维码", style = MaterialTheme.typography.headlineSmall)
        if (granted) {
            QRCodeScanner(onQRCode = onQRCode, onCameraError = {}, modifier = Modifier.fillMaxWidth().height(360.dp))
        } else {
            Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }, modifier = Modifier.fillMaxWidth()) { Text("允许相机权限") }
        }
        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("返回手动输入") }
    }
}
