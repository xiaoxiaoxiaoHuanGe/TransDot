package com.transdot.transferassistant

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.transdot.transferassistant.data.NetworkSetupRepository
import com.transdot.transferassistant.data.NetworkPairingRepository
import com.transdot.transferassistant.data.NetworkTimelineRepository
import com.transdot.transferassistant.data.SecureSessionStore
import com.transdot.transferassistant.data.SessionStore
import com.transdot.transferassistant.ui.PairingFlow
import com.transdot.transferassistant.ui.PairingViewModel
import com.transdot.transferassistant.ui.SetupScreen
import com.transdot.transferassistant.ui.SetupViewModel
import com.transdot.transferassistant.ui.TimelineScreen
import com.transdot.transferassistant.ui.TimelineViewModel
import com.transdot.transferassistant.ui.theme.TransferAssistantTheme
import com.transdot.transferassistant.ui.theme.ThemeMode

class MainActivity : ComponentActivity() {
    private var incomingShare by mutableStateOf<IncomingShare?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        incomingShare = parseIncomingShare(intent)
        enableEdgeToEdge()

        setContent {
            val repository = remember { NetworkSetupRepository(allowCleartext = BuildConfig.DEBUG) }
            val sessionStore = remember { SecureSessionStore(applicationContext) }
            val factory = remember { SetupViewModel.Factory(repository, sessionStore) }
            val viewModel: SetupViewModel = viewModel(factory = factory)
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val themePreferences = remember { getSharedPreferences("appearance", MODE_PRIVATE) }
            var themeMode by remember {
                mutableStateOf(runCatching {
                    ThemeMode.valueOf(themePreferences.getString("theme_mode", ThemeMode.System.name).orEmpty())
                }.getOrDefault(ThemeMode.System))
            }

            TransferAssistantTheme(mode = themeMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AnimatedContent(
                        targetState = uiState.isReady,
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "setup-state",
                    ) { isReady ->
                        if (isReady) {
                            PairingContent(
                                sessionStore = sessionStore,
                                themeMode = themeMode,
                                incomingShare = incomingShare,
                                onShareConsumed = ::consumeIncomingShare,
                                onThemeModeChange = { selected ->
                                    themeMode = selected
                                    themePreferences.edit().putString("theme_mode", selected.name).apply()
                                },
                            )
                        } else {
                            SetupScreen(
                                state = uiState,
                                allowCleartext = BuildConfig.DEBUG,
                                onServerAddressChange = viewModel::updateServerAddress,
                                onSetupTokenChange = viewModel::updateSetupToken,
                                onClaim = viewModel::claimServer,
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingShare = parseIncomingShare(intent)
    }

    private fun consumeIncomingShare() {
        incomingShare = null
        intent?.action = Intent.ACTION_MAIN
    }

    private fun parseIncomingShare(intent: Intent?): IncomingShare? {
        val source = intent ?: return null
        if (source.action != Intent.ACTION_SEND && source.action != Intent.ACTION_SEND_MULTIPLE) return null
        val uris = linkedSetOf<Uri>()
        if (source.action == Intent.ACTION_SEND_MULTIPLE) {
            uris += parcelableUriList(source, Intent.EXTRA_STREAM)
        } else {
            parcelableUri(source, Intent.EXTRA_STREAM)?.let(uris::add)
        }
        source.clipData?.let { clipData ->
            repeat(clipData.itemCount) { index -> clipData.getItemAt(index).uri?.let(uris::add) }
        }
        val text = source.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf(String::isNotBlank)
        if (uris.isEmpty() && text == null) return null
        return IncomingShare(
            id = System.nanoTime(),
            text = text,
            files = uris.map { uri -> IncomingSharedFile(uri, sharedDisplayName(uri)) },
        )
    }

    private fun sharedDisplayName(uri: Uri): String {
        if (uri.scheme == "content") {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) return cursor.getString(0)?.takeIf(String::isNotBlank) ?: "共享文件"
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf(String::isNotBlank) ?: "共享文件"
    }

    @Suppress("DEPRECATION")
    private fun parcelableUri(intent: Intent, name: String): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) intent.getParcelableExtra(name, Uri::class.java)
        else intent.getParcelableExtra(name)

    @Suppress("DEPRECATION")
    private fun parcelableUriList(intent: Intent, name: String): List<Uri> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) intent.getParcelableArrayListExtra(name, Uri::class.java).orEmpty()
        else intent.getParcelableArrayListExtra<Uri>(name).orEmpty()
}

data class IncomingSharedFile(val uri: Uri, val displayName: String)
data class IncomingShare(val id: Long, val text: String?, val files: List<IncomingSharedFile>)

@Composable
private fun PairingContent(
    sessionStore: SessionStore,
    themeMode: ThemeMode,
    incomingShare: IncomingShare?,
    onShareConsumed: () -> Unit,
    onThemeModeChange: (ThemeMode) -> Unit,
) {
    val pairingRepository = remember { NetworkPairingRepository(allowCleartext = BuildConfig.DEBUG) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val timelineRepository = remember { NetworkTimelineRepository(allowCleartext = BuildConfig.DEBUG, context = context.applicationContext) }
    val pairingFactory = remember { PairingViewModel.Factory(pairingRepository, sessionStore) }
    val timelineFactory = remember { TimelineViewModel.Factory(timelineRepository, sessionStore) }
    val pairingViewModel: PairingViewModel = viewModel(factory = pairingFactory)
    val timelineViewModel: TimelineViewModel = viewModel(factory = timelineFactory)
    val pairingUiState by pairingViewModel.uiState.collectAsStateWithLifecycle()
    val timelineUiState by timelineViewModel.uiState.collectAsStateWithLifecycle()

    LifecycleStartEffect(timelineViewModel) {
        timelineViewModel.start()
        onStopOrDispose { timelineViewModel.stop() }
    }

    if (pairingUiState.screen == com.transdot.transferassistant.ui.PairingScreen.Home) {
        TimelineScreen(
            state = timelineUiState,
            ownDeviceId = pairingUiState.deviceId,
            themeMode = themeMode,
            incomingShare = incomingShare,
            onShareConsumed = onShareConsumed,
            onThemeModeChange = onThemeModeChange,
            onDraftChange = timelineViewModel::updateDraft,
            onSend = timelineViewModel::sendText,
            onUpload = timelineViewModel::uploadFiles,
            onRetryUpload = timelineViewModel::retryUpload,
            onDownload = timelineViewModel::download,
            loadImage = timelineViewModel::loadImage,
            onLoadOlder = timelineViewModel::loadOlder,
            onRequestDelete = timelineViewModel::requestDelete,
            onCancelDelete = timelineViewModel::cancelDelete,
            onConfirmDelete = timelineViewModel::confirmDelete,
            onOpenSearch = timelineViewModel::openSearch,
            onCloseSearch = timelineViewModel::closeSearch,
            onSearchQueryChange = timelineViewModel::updateSearchQuery,
            onSearch = timelineViewModel::search,
            onLocate = timelineViewModel::locate,
            onClearHighlight = timelineViewModel::clearHighlight,
            onClearError = timelineViewModel::clearError,
            onPairWindows = pairingViewModel::openScanner,
        )
    } else {
        PairingFlow(
            state = pairingUiState,
            onOpenScanner = pairingViewModel::openScanner,
            onOpenManual = pairingViewModel::openManual,
            onBack = pairingViewModel::returnHome,
            onCodeChange = pairingViewModel::updateManualCode,
            onSubmitCode = pairingViewModel::submitManualCode,
            onQRCode = pairingViewModel::onQRCodeScanned,
            onScannerError = pairingViewModel::reportScannerError,
            onConfirmReplacement = pairingViewModel::confirmReplacement,
            onCancelReplacement = pairingViewModel::cancelReplacement,
        )
    }
}
