package com.transdot.transferassistant.ui

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.transdot.transferassistant.R
import com.transdot.transferassistant.data.FileAttachment
import com.transdot.transferassistant.data.TimelineMessage
import com.transdot.transferassistant.data.UploadProgress
import com.transdot.transferassistant.ui.components.AppIconButton
import com.transdot.transferassistant.ui.theme.AppSpacing
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MessageBubble(
    message: TimelineMessage,
    own: Boolean,
    highlighted: Boolean,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onDownload: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (own) Arrangement.End else Arrangement.Start) {
        Column(Modifier.widthIn(max = 340.dp), horizontalAlignment = if (own) Alignment.End else Alignment.Start) {
            MessageMeta(message)
            if (message.type == "text") {
                Surface(
                    modifier = Modifier.clip(messageShape(own)).combinedClickable(onClick = {}, onLongClick = onDelete),
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
                    shadowElevation = if (highlighted) 3.dp else 0.dp,
                ) {
                    Column(Modifier.padding(horizontal = AppSpacing.medium, vertical = AppSpacing.small)) {
                        Text(message.textContent.orEmpty(), style = MaterialTheme.typography.bodyLarge)
                        MessageActionButtons(
                            onCopy = onCopy,
                            onDelete = onDelete,
                            tint = if (own) MaterialTheme.colorScheme.onPrimary.copy(alpha = .78f) else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.align(Alignment.End),
                        )
                    }
                }
            } else {
                FileCard(requireNotNull(message.file), own, onDownload, onCopy, onDelete)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileCard(
    file: FileAttachment,
    own: Boolean,
    onDownload: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        modifier = Modifier.widthIn(min = 280.dp, max = 340.dp).clip(messageShape(own)).combinedClickable(onClick = {}, onLongClick = onDelete),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(AppSpacing.medium)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(48.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        fileExtension(file.originalFilename),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Column(Modifier.weight(1f).padding(horizontal = AppSpacing.medium)) {
                    Text(file.originalFilename, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${formatBytes(file.sizeBytes)} · ${fileStatus(file)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                if (file.status == "available") {
                    AppIconButton(
                        iconRes = R.drawable.ic_download,
                        contentDescription = "下载 ${file.originalFilename}",
                        onClick = onDownload,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Text("已过期", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            MessageActionButtons(onCopy, onDelete, MaterialTheme.colorScheme.primary, Modifier.align(Alignment.End))
        }
    }
}

@Composable
internal fun ImageGroup(
    messages: List<TimelineMessage>,
    own: Boolean,
    highlighted: Boolean,
    loadImage: suspend (TimelineMessage, Boolean) -> Bitmap?,
    onOpen: (Int) -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (own) Arrangement.End else Arrangement.Start) {
        Column(Modifier.widthIn(max = 350.dp), horizontalAlignment = if (own) Alignment.End else Alignment.Start) {
            MessageMeta(messages.first())
            Surface(shape = messageShape(own), shadowElevation = if (highlighted) 6.dp else 1.dp) {
                Column(Modifier.padding(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    messages.take(6).chunked(2).forEachIndexed { rowIndex, row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            row.forEachIndexed { columnIndex, message ->
                                val index = rowIndex * 2 + columnIndex
                                ImageTile(
                                    message = message,
                                    modifier = Modifier.weight(1f),
                                    loadImage = loadImage,
                                    onOpen = { onOpen(index) },
                                    more = if (index == 5) messages.size - 6 else 0,
                                )
                            }
                            if (row.size == 1 && messages.size > 1) Spacer(Modifier.weight(1f))
                        }
                    }
                    MessageActionButtons(
                        onCopy,
                        onDelete,
                        MaterialTheme.colorScheme.primary,
                        Modifier.align(Alignment.End).padding(end = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ImageTile(
    message: TimelineMessage,
    modifier: Modifier,
    loadImage: suspend (TimelineMessage, Boolean) -> Bitmap?,
    onOpen: () -> Unit,
    more: Int,
) {
    val bitmap by produceState<Bitmap?>(null, message.id, message.file?.thumbnailUrl) { value = loadImage(message, false) }
    Box(modifier.aspectRatio(if (message.batchId == null) 1.3f else 1f).clip(RoundedCornerShape(12.dp)).clickable(onClick = onOpen)) {
        if (bitmap != null) {
            Image(
                requireNotNull(bitmap).asImageBitmap(),
                message.file?.originalFilename,
                Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            }
        }
        if (message.file?.status != "available") {
            Surface(
                Modifier.align(Alignment.BottomCenter).padding(6.dp),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.scrim.copy(alpha = .7f),
            ) {
                Text("原图已过期", Modifier.padding(6.dp), color = Color.White, style = MaterialTheme.typography.labelSmall)
            }
        }
        if (more > 0) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = .58f)),
                contentAlignment = Alignment.Center,
            ) {
                Text("+${more + 1}", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun MessageActionButtons(
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        AppIconButton(R.drawable.ic_copy, "复制", onCopy, tint = tint)
        AppIconButton(R.drawable.ic_delete, "删除", onDelete, tint = tint)
    }
}

@Composable
private fun MessageMeta(message: TimelineMessage) {
    Text(
        "${if (message.sourceDeviceType == "android_master") "Android" else "Windows"} · ${formatMessageTime(message.createdAt)}",
        Modifier.padding(horizontal = AppSpacing.small, vertical = 3.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun UploadCard(upload: UploadProgress, onRetry: () -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(start = 56.dp), shape = MaterialTheme.shapes.medium, tonalElevation = 2.dp) {
        Column(Modifier.padding(AppSpacing.medium), verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(upload.filename, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    Text(
                        when (upload.state) {
                            "preparing" -> "正在生成缩略图"
                            "complete" -> "上传完成"
                            "failed" -> upload.error ?: "上传失败"
                            else -> "上传中 ${(upload.bytesSent * 100 / upload.totalBytes.coerceAtLeast(1)).toInt()}%"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (upload.state == "failed") TextButton(onClick = onRetry) { Text("重试") }
            }
            LinearProgressIndicator(
                progress = { if (upload.totalBytes > 0) upload.bytesSent.toFloat() / upload.totalBytes else 0f },
                modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape),
            )
        }
    }
}

@Composable
internal fun MessageComposer(
    draft: String,
    sending: Boolean,
    errorMessage: String?,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onAttachment: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val byteCount = draft.toByteArray().size
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = AppSpacing.medium, vertical = 9.dp)) {
            AnimatedVisibility(errorMessage != null) {
                Text(
                    errorMessage.orEmpty(),
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(12.dp)).padding(AppSpacing.small),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(top = if (errorMessage != null) AppSpacing.small else 0.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                TextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp, max = 132.dp),
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
                    isError = byteCount > MAX_TEXT_BYTES,
                    shape = RoundedCornerShape(13.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        errorIndicatorColor = Color.Transparent,
                    ),
                )
                AnimatedContent(
                    targetState = draft.isNotBlank(),
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "composer-action",
                ) { hasText ->
                    if (hasText) {
                        Button(
                            onClick = onSend,
                            enabled = !sending && byteCount <= MAX_TEXT_BYTES,
                            modifier = Modifier.height(48.dp).widthIn(min = 68.dp),
                            shape = RoundedCornerShape(13.dp),
                        ) {
                            if (sending) CircularProgressIndicator(Modifier.size(19.dp), strokeWidth = 2.dp) else Text("发送")
                        }
                    } else {
                        Surface(
                            onClick = onAttachment,
                            modifier = Modifier.size(48.dp),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_add),
                                    contentDescription = "添加照片或文件",
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                        }
                    }
                }
            }
            if (byteCount > 90 * 1024) {
                Text(
                    "${(byteCount + 1023) / 1024} / 100 KB",
                    modifier = Modifier.fillMaxWidth().padding(top = 3.dp, end = 59.dp),
                    textAlign = TextAlign.End,
                    color = if (byteCount > MAX_TEXT_BYTES) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

internal fun formatMessageTime(value: String): String = runCatching {
    val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    val parsed = requireNotNull(parser.parse(value.take(19)))
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(parsed)
}.getOrElse { value.take(16).replace('T', ' ') }

private fun messageShape(own: Boolean) = RoundedCornerShape(
    topStart = 20.dp,
    topEnd = 20.dp,
    bottomStart = if (own) 20.dp else 6.dp,
    bottomEnd = if (own) 6.dp else 20.dp,
)

private fun fileExtension(value: String) = value.substringAfterLast('.', "FILE").uppercase().take(5)

private fun formatBytes(bytes: Long) = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
    else -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1024.0 / 1024.0)
}

private fun fileStatus(file: FileAttachment) = if (file.status == "available") "可下载" else "已过期"

private const val MAX_TEXT_BYTES = 100 * 1024
