package com.transdot.transferassistant

import android.os.Bundle
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
}

@Composable
private fun PairingContent(
    sessionStore: SessionStore,
    themeMode: ThemeMode,
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
