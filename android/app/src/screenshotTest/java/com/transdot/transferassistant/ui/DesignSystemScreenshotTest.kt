package com.transdot.transferassistant.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.transdot.transferassistant.R
import com.transdot.transferassistant.ui.components.AppEmptyState
import com.transdot.transferassistant.ui.components.AppStatusPanel
import com.transdot.transferassistant.ui.components.AppTopBar
import com.transdot.transferassistant.ui.components.StatusTone
import com.transdot.transferassistant.ui.theme.AppSpacing
import com.transdot.transferassistant.ui.theme.ThemeMode
import com.transdot.transferassistant.ui.theme.TransferAssistantTheme

@PreviewTest
@Preview(name = "Design system light", widthDp = 393, heightDp = 852)
@Composable
fun DesignSystemLightPreview() {
    DesignSystemPreviewContent(mode = ThemeMode.Light)
}

@PreviewTest
@Preview(
    name = "Design system dark",
    widthDp = 393,
    heightDp = 852,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun DesignSystemDarkPreview() {
    DesignSystemPreviewContent(mode = ThemeMode.Dark)
}

@Composable
private fun DesignSystemPreviewContent(mode: ThemeMode) {
    TransferAssistantTheme(mode = mode) {
        Surface(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.large),
            ) {
                AppTopBar(
                    title = "传输助手",
                    subtitle = "设备已安全连接",
                    onBack = null,
                )
                AppStatusPanel(
                    tone = StatusTone.Success,
                    title = "连接已建立",
                    message = "可以开始在设备之间发送内容。",
                    modifier = Modifier.padding(horizontal = AppSpacing.large),
                )
                AppEmptyState(
                    iconRes = R.drawable.ic_send,
                    title = "发送第一条内容",
                    message = "输入文字，或选择照片和文件。",
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
