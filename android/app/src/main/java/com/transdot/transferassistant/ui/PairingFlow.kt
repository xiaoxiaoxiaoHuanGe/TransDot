package com.transdot.transferassistant.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.transdot.transferassistant.R
import com.transdot.transferassistant.ui.components.AppStatusPanel
import com.transdot.transferassistant.ui.components.AppTopBar
import com.transdot.transferassistant.ui.components.StatusTone
import com.transdot.transferassistant.ui.theme.AppSpacing

@Composable
fun PairingFlow(
    state: PairingUiState,
    onOpenScanner: () -> Unit,
    onOpenManual: () -> Unit,
    onBack: () -> Unit,
    onCodeChange: (String) -> Unit,
    onSubmitCode: () -> Unit,
    onQRCode: (String) -> Unit,
    onScannerError: (String) -> Unit,
    onConfirmReplacement: () -> Unit,
    onCancelReplacement: () -> Unit,
    onConfirmBootstrap: () -> Unit = {},
    onCancelBootstrap: () -> Unit = {},
    onConfirmRebind: () -> Unit = {},
    onCancelRebind: () -> Unit = {},
) {
    when (state.screen) {
        PairingScreen.Home -> ReadyScreen(
            serverAddress = state.serverAddress,
            deviceId = state.deviceId,
            onPairWindows = onOpenScanner,
        )
        PairingScreen.Scanner -> ScannerScreen(
            state = state,
            onQRCode = onQRCode,
            onScannerError = onScannerError,
            onOpenManual = onOpenManual,
            onBack = onBack,
        )
        PairingScreen.Manual -> ManualCodeScreen(
            state = state,
            onCodeChange = onCodeChange,
            onSubmit = onSubmitCode,
            onOpenScanner = onOpenScanner,
            onBack = onBack,
        )
        PairingScreen.Success -> PairingSuccessScreen(onBack = onBack)
    }

    if (state.replacementRequired) {
        AlertDialog(
            onDismissRequest = onCancelReplacement,
            title = { Text("替换当前 Windows？") },
            text = {
                Text("服务器已有一台有效 Windows。继续配对会立即撤销旧浏览器的 REST 与 WebSocket 权限。")
            },
            confirmButton = {
                Button(onClick = onConfirmReplacement) { Text("确认替换") }
            },
            dismissButton = {
                TextButton(onClick = onCancelReplacement) { Text("取消") }
            },
        )
    }
    state.bootstrapPayload?.let { payload ->
        AlertDialog(
            onDismissRequest = onCancelBootstrap,
            title = { Text("绑定新的服务器实例？") },
            text = { Text("${payload.serverAddress}\n实例指纹 ${payload.instanceFingerprint.uppercase()}\n确认后会创建新的服务器档案。") },
            confirmButton = { Button(onClick = onConfirmBootstrap, enabled = !state.isSubmitting) { Text("确认绑定") } },
            dismissButton = { TextButton(onClick = onCancelBootstrap, enabled = !state.isSubmitting) { Text("取消") } },
        )
    }
    state.rebindPayload?.let { payload ->
        AlertDialog(
            onDismissRequest = onCancelRebind,
            title = { Text("重新绑定当前手机？") },
            text = { Text("${payload.serverAddress}\n实例指纹 ${payload.instanceFingerprint.uppercase()}\n确认后服务器会撤销旧手机凭据。") },
            confirmButton = { Button(onClick = onConfirmRebind, enabled = !state.isSubmitting) { Text("确认重绑定") } },
            dismissButton = { TextButton(onClick = onCancelRebind, enabled = !state.isSubmitting) { Text("取消") } },
        )
    }
}

@Composable
private fun ScannerScreen(
    state: PairingUiState,
    onQRCode: (String) -> Unit,
    onScannerError: (String) -> Unit,
    onOpenManual: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var cameraGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var permissionRequested by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        cameraGranted = it
        permissionRequested = true
    }

    LaunchedEffect(Unit) {
        if (!cameraGranted && !permissionRequested) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    PairingScaffold(onBack = onBack, title = "扫描电脑二维码") {
        Text(
            text = "将电脑网页上的二维码完整放入取景框。扫码包含更完整的安全凭据，建议优先使用。",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(AppSpacing.large))

        if (cameraGranted) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 300.dp, max = 420.dp)
                    .clip(MaterialTheme.shapes.large)
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                QRCodeScanner(
                    onQRCode = onQRCode,
                    onCameraError = onScannerError,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    modifier = Modifier
                        .size(242.dp)
                        .border(3.dp, Color.White.copy(alpha = 0.92f), RoundedCornerShape(28.dp)),
                )
                if (state.isSubmitting) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.55f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = Color.White)
                    }
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.medium)) {
                AppStatusPanel(
                    tone = StatusTone.Info,
                    title = "需要相机权限",
                    message = "相机只用于识别电脑上显示的配对二维码。",
                )
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text("允许相机权限")
                }
            }
        }

        PairingError(state.errorMessage)
        Spacer(Modifier.height(AppSpacing.large))
        OutlinedButton(
            onClick = onOpenManual,
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.isSubmitting,
            shape = MaterialTheme.shapes.medium,
        ) {
            Text("改用 6 位码")
        }
    }
}

@Composable
private fun ManualCodeScreen(
    state: PairingUiState,
    onCodeChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onOpenScanner: () -> Unit,
    onBack: () -> Unit,
) {
    PairingScaffold(onBack = onBack, title = "输入 6 位配对码") {
        Text(
            text = "在电脑配对二维码下方找到 6 位数字。配对码仅在当前会话短时间内有效。",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(AppSpacing.extraLarge))
        OutlinedTextField(
            value = state.manualCode,
            onValueChange = onCodeChange,
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.isSubmitting,
            singleLine = true,
            label = { Text("配对码") },
            placeholder = { Text("538 219") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            textStyle = MaterialTheme.typography.headlineMedium.copy(
                letterSpacing = 4.sp,
            ),
            shape = MaterialTheme.shapes.medium,
        )
        PairingError(state.errorMessage)
        Spacer(Modifier.height(AppSpacing.large))
        Button(
            onClick = onSubmit,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            enabled = !state.isSubmitting && state.manualCode.length == 6,
            shape = MaterialTheme.shapes.medium,
        ) {
            if (state.isSubmitting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp,
                )
            } else {
                Text("确认配对")
            }
        }
        TextButton(
            onClick = onOpenScanner,
            modifier = Modifier.align(Alignment.CenterHorizontally),
            enabled = !state.isSubmitting,
        ) {
            Text("返回扫码")
        }
    }
}

@Composable
private fun PairingSuccessScreen(onBack: () -> Unit) {
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(AppSpacing.large),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 520.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(AppSpacing.large),
            ) {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                ) {
                    Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                        androidx.compose.material3.Icon(
                            painter = painterResource(R.drawable.ic_check),
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                        )
                    }
                }
                Text(
                    "Windows 配对成功",
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    "电脑已获得安全浏览器凭据。服务端同时只保留一台有效 Windows。",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Button(
                    onClick = onBack,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = MaterialTheme.shapes.medium,
                ) { Text("完成") }
            }
        }
    }
}

@Composable
private fun PairingScaffold(
    onBack: () -> Unit,
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding(),
        ) {
            AppTopBar(title = title, onBack = onBack)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .widthIn(max = 520.dp)
                    .align(Alignment.CenterHorizontally)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = AppSpacing.extraLarge, vertical = AppSpacing.large),
                content = content,
            )
        }
    }
}

@Composable
private fun PairingError(message: String?) {
    AnimatedVisibility(visible = message != null) {
        AppStatusPanel(
            tone = StatusTone.Error,
            title = "无法完成配对",
            message = message.orEmpty(),
            modifier = Modifier.padding(top = AppSpacing.medium),
        )
    }
}
