package com.transdot.transferassistant.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.transdot.transferassistant.BuildConfig
import com.transdot.transferassistant.data.AppUpdateModel

internal val LocalAppUpdate = staticCompositionLocalOf<AppUpdateModel?> { null }

@Composable
internal fun AppUpdateProvider(showEntry: Boolean, content: @Composable () -> Unit) {
    val model: AppUpdateModel = viewModel()
    val state by model.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle, model) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME && !BuildConfig.DEBUG) model.check() }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(model) { if (!BuildConfig.DEBUG) model.check() }
    LaunchedEffect(state.ready) { if (state.ready && state.visible) model.install(context, model.transferBusy) }
    CompositionLocalProvider(LocalAppUpdate provides model) {
        Box(Modifier.fillMaxSize()) {
            content()
            if (showEntry) TextButton(onClick = { model.check(manual = true) }, modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding()) { Text("检查 APP 更新 · ${BuildConfig.VERSION_NAME}") }
        }
    }
    if (state.visible) AlertDialog(
        onDismissRequest = model::dismiss,
        title = { Text(state.update?.let { "发现新版本 ${it.versionName}" } ?: "APP 在线更新") },
        text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("当前版本 ${BuildConfig.VERSION_NAME}")
            if (BuildConfig.DEBUG) Text("调试包不能覆盖安装正式包；请使用正式签名版本体验在线更新。")
            if (state.checking) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在检查 GitHub 正式版本…") }
            state.update?.let { Text(it.notes.ifBlank { "此版本未提供更新说明。" }); Text("安装包 %.1f MB".format(it.asset.size / 1024.0 / 1024)) }
            if (state.downloading) { LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth()); Text("下载中 ${(state.progress * 100).toInt()}%，完成后校验安装包") }
            state.notice?.let { Text(it) }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = {
            when {
                state.ready -> TextButton(onClick = { model.install(context, model.transferBusy) }) { Text("继续安装") }
                state.downloading -> TextButton(onClick = model::cancelDownload) { Text("取消下载") }
                state.update != null -> TextButton(onClick = model::download) { Text(if (state.error != null) "重新下载" else "立即更新") }
                else -> TextButton(onClick = { model.check(manual = true) }, enabled = !state.checking) { Text("重新检查") }
            }
        },
        dismissButton = { TextButton(onClick = model::dismiss) { Text(if (state.downloading) "后台下载" else "稍后") } },
    )
}

@Composable
internal fun AppUpdateEntry() {
    val model = LocalAppUpdate.current ?: return
    TextButton(onClick = { model.check(manual = true) }) { Text("检查 APP 更新 · ${BuildConfig.VERSION_NAME}") }
}
