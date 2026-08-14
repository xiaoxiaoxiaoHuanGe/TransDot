package com.transdot.transferassistant.ui

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.transdot.transferassistant.data.AppSettings
import com.transdot.transferassistant.data.FileAttachment
import com.transdot.transferassistant.data.ServerProfileSummary
import com.transdot.transferassistant.data.TimelineMessage
import com.transdot.transferassistant.data.UploadProgress
import com.transdot.transferassistant.ui.theme.AppSpacing
import com.transdot.transferassistant.ui.theme.ThemeMode
import com.transdot.transferassistant.ui.theme.TransferAssistantTheme

private val PreviewNoOp = {}

@PreviewTest
@Preview(name = "Empty timeline light", widthDp = 393, heightDp = 852)
@Composable
fun EmptyTimelineLightPreview() {
    TransferAssistantTheme(mode = ThemeMode.Light) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            TimelineTopBar(
                activeServerName = "个人服务器",
                connection = TimelineConnectionState.Connected,
                onPairWindows = PreviewNoOp,
                onSearch = PreviewNoOp,
                onSettings = PreviewNoOp,
            )
            Box(Modifier.weight(1f)) { EmptyTimeline() }
            MessageComposer(
                draft = "",
                sending = false,
                errorMessage = null,
                onDraftChange = {},
                onSend = PreviewNoOp,
                onAttachment = PreviewNoOp,
            )
        }
    }
}

@PreviewTest
@Preview(
    name = "Timeline messages dark",
    widthDp = 393,
    heightDp = 852,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun TimelineMessagesDarkPreview() {
    val textMessage = TimelineMessage(
        id = "message-1",
        type = "text",
        batchId = null,
        sourceDeviceId = "windows-1",
        sourceDeviceType = "windows_browser",
        textContent = "这是一条从 Windows 发送的测试消息。",
        createdAt = "2026-08-15T08:30:00",
        metadataExpiresAt = null,
    )
    val fileMessage = TimelineMessage(
        id = "message-2",
        type = "file",
        batchId = null,
        sourceDeviceId = "android-1",
        sourceDeviceType = "android_master",
        textContent = null,
        createdAt = "2026-08-15T08:32:00",
        metadataExpiresAt = null,
        file = FileAttachment(
            id = "file-1",
            originalFilename = "产品设计方案.pdf",
            mimeType = "application/pdf",
            sizeBytes = 2_480_000,
            status = "available",
            expiresAt = null,
            expiredReason = null,
            downloadUrl = "/download/file-1",
            thumbnailUrl = null,
        ),
    )
    TransferAssistantTheme(mode = ThemeMode.Dark) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(AppSpacing.large),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.large),
        ) {
            MessageBubble(
                message = textMessage,
                own = false,
                highlighted = false,
                onCopy = PreviewNoOp,
                onDelete = PreviewNoOp,
                onDownload = PreviewNoOp,
            )
            MessageBubble(
                message = fileMessage,
                own = true,
                highlighted = false,
                onCopy = PreviewNoOp,
                onDelete = PreviewNoOp,
                onDownload = PreviewNoOp,
            )
            UploadCard(
                upload = UploadProgress("upload-1", "旅行照片.zip", 65, 100, "uploading"),
                onRetry = PreviewNoOp,
            )
            Spacer(Modifier.height(AppSpacing.small))
        }
    }
}

@PreviewTest
@Preview(name = "Settings light", widthDp = 393, heightDp = 852)
@Composable
fun SettingsLightPreview() {
    TransferAssistantTheme(mode = ThemeMode.Light) {
        SettingsSheetContent(
            mode = ThemeMode.System,
            appSettings = AppSettings(defaultSaveTreeUri = null, notificationsEnabled = true),
            folderLabel = null,
            serverProfiles = listOf(ServerProfileSummary("server-1", "个人服务器", "https://transfer.example.com")),
            activeProfileId = "server-1",
            connection = TimelineConnectionState.Connected,
            transferBusy = false,
            onChange = {},
            onChooseDefaultFolder = PreviewNoOp,
            onClearDefaultFolder = PreviewNoOp,
            onNotificationsChanged = {},
            onAddServer = { _, _, _ -> Result.success(Unit) },
            onSwitchServer = { _ -> Result.success(Unit) },
            onRenameServer = { _, _ -> true },
            onDeleteServer = { true },
        )
    }
}

@PreviewTest
@Preview(
    name = "Image viewer dark",
    widthDp = 393,
    heightDp = 852,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun ImageViewerDarkPreview() {
    val image = TimelineMessage(
        id = "image-1",
        type = "image",
        batchId = null,
        sourceDeviceId = "windows-1",
        sourceDeviceType = "windows_browser",
        textContent = null,
        createdAt = "2026-08-15T08:35:00",
        metadataExpiresAt = null,
        file = FileAttachment(
            id = "file-image-1",
            originalFilename = "会议白板.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 1_280_000,
            status = "available",
            expiresAt = null,
            expiredReason = null,
            downloadUrl = "/download/file-image-1",
            thumbnailUrl = "/thumbnail/file-image-1",
        ),
    )
    TransferAssistantTheme(mode = ThemeMode.Dark) {
        ImageViewer(
            state = ViewerState(listOf(image), initialIndex = 0),
            loadImage = { _, _ -> null },
            onDownload = {},
            onBack = PreviewNoOp,
        )
    }
}
