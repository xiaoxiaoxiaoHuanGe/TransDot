package com.transdot.transferassistant.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.transdot.transferassistant.R
import com.transdot.transferassistant.ui.components.AppInfoRow
import com.transdot.transferassistant.ui.components.AppStatusPanel
import com.transdot.transferassistant.ui.components.StatusTone
import com.transdot.transferassistant.ui.theme.AppSpacing

@Composable
fun ReadyScreen(serverAddress: String, deviceId: String, onPairWindows: () -> Unit) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = AppSpacing.extraLarge, vertical = AppSpacing.huge),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 520.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(AppSpacing.extraLarge),
            ) {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                ) {
                    Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(R.drawable.ic_check),
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                        )
                    }
                }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.small),
                ) {
                    Text(
                        text = "Android 主设备已就绪",
                        modifier = Modifier.semantics { heading() },
                        style = MaterialTheme.typography.headlineMedium,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = "凭据已加密保存在本机。现在可以授权一台 Windows 浏览器。",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column(Modifier.padding(horizontal = AppSpacing.large)) {
                        AppInfoRow(label = "服务器", value = serverAddress, showDivider = true)
                        AppInfoRow(label = "设备 ID", value = deviceId)
                    }
                }

                AppStatusPanel(
                    tone = StatusTone.Success,
                    title = "安全保存完成",
                    message = "Master Token 由 Android Keystore 保护，不会显示在界面中。",
                )

                Button(
                    onClick = onPairWindows,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text("配对 Windows")
                }
            }
        }
    }
}
