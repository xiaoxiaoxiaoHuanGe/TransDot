package com.transdot.transferassistant.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.transdot.transferassistant.ui.theme.AppSpacing

@Composable
fun SetupScreen(
    state: SetupUiState,
    allowCleartext: Boolean,
    onServerAddressChange: (String) -> Unit,
    onSetupTokenChange: (String) -> Unit,
    onClaim: () -> Unit,
) {
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { innerPadding ->
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
                    .widthIn(max = 560.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = AppSpacing.large, vertical = AppSpacing.extraLarge),
            ) {
                BrandMark()
                Spacer(Modifier.height(AppSpacing.extraLarge))
                Text(
                    text = "连接你的私人空间",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(AppSpacing.medium))
                Text(
                    text = "首次使用需要服务器地址和部署时生成的初始化密钥。Claim 成功后，这台手机将成为唯一 Android Master。",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(AppSpacing.extraLarge))

                SetupForm(
                    state = state,
                    allowCleartext = allowCleartext,
                    onServerAddressChange = onServerAddressChange,
                    onSetupTokenChange = onSetupTokenChange,
                    onClaim = onClaim,
                )
            }
        }
    }
}

@Composable
private fun SetupForm(
    state: SetupUiState,
    allowCleartext: Boolean,
    onServerAddressChange: (String) -> Unit,
    onSetupTokenChange: (String) -> Unit,
    onClaim: () -> Unit,
) {
    var showToken by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.large,
            )
            .padding(AppSpacing.large),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.large),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
            Text(
                text = "服务器",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "正式部署请填写 HTTPS 域名，例如 https://transfer.example.com",
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
            placeholder = { Text("https://你的域名") },
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
            Text(
                text = "调试包允许局域网 HTTP，便于真机连接电脑；正式版本仍强制 HTTPS。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }

        AnimatedVisibility(visible = state.errorMessage != null) {
            Text(
                text = state.errorMessage.orEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.errorContainer,
                        RoundedCornerShape(12.dp),
                    )
                    .padding(AppSpacing.medium),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Button(
            onClick = onClaim,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            enabled = !state.isSubmitting,
            shape = MaterialTheme.shapes.medium,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        ) {
            if (state.isSubmitting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp,
                )
            } else {
                Text(
                    if (state.needsSecureStorageRetry) "重试安全保存" else "连接并初始化",
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun BrandMark() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "↑",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onPrimary,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(
            text = "传输助手",
            modifier = Modifier.padding(start = AppSpacing.medium),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}
