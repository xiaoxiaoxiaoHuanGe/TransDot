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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.transdot.transferassistant.data.NetworkSetupRepository
import com.transdot.transferassistant.data.AppPreferences
import com.transdot.transferassistant.data.AppSettings
import com.transdot.transferassistant.data.DownloadDestinationManager
import com.transdot.transferassistant.data.NetworkPairingRepository
import com.transdot.transferassistant.data.NetworkBootstrapRepository
import com.transdot.transferassistant.data.NetworkRebindRepository
import com.transdot.transferassistant.data.NetworkTimelineRepository
import com.transdot.transferassistant.data.SecureSessionStore
import com.transdot.transferassistant.data.SessionStore
import com.transdot.transferassistant.data.SystemTransferNotifier
import com.transdot.transferassistant.data.defaultProfileName
import com.transdot.transferassistant.lan.AndroidLanContentAccess
import com.transdot.transferassistant.lan.AndroidLanForegroundController
import com.transdot.transferassistant.lan.AndroidLanPeerFactory
import com.transdot.transferassistant.lan.LanFileStore
import com.transdot.transferassistant.lan.LanPeerEngine
import com.transdot.transferassistant.lan.LanSignalingClient
import com.transdot.transferassistant.lan.OkHttpLanWebSocketTransport
import com.transdot.transferassistant.ui.LanTransferScreen
import com.transdot.transferassistant.ui.LanTransferViewModel
import com.transdot.transferassistant.ui.StoredLanTransferFiles
import com.transdot.transferassistant.ui.PairingFlow
import com.transdot.transferassistant.ui.PairingViewModel
import com.transdot.transferassistant.ui.SetupScreen
import com.transdot.transferassistant.ui.SetupViewModel
import com.transdot.transferassistant.ui.TimelineScreen
import com.transdot.transferassistant.ui.TimelineViewModel
import com.transdot.transferassistant.ui.theme.TransferAssistantTheme
import com.transdot.transferassistant.ui.theme.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient

class MainActivity : ComponentActivity() {
    private var incomingShare by mutableStateOf<IncomingShare?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        incomingShare = parseIncomingShare(intent)
        enableEdgeToEdge()

        setContent {
            val repository = remember { NetworkSetupRepository(allowCleartext = BuildConfig.DEBUG) }
            val sessionStore = remember { SecureSessionStore(applicationContext) }
            val bootstrapRepository = remember { NetworkBootstrapRepository(allowCleartext = BuildConfig.DEBUG) }
            val rebindRepository = remember { NetworkRebindRepository(allowCleartext = BuildConfig.DEBUG) }
            val validationRepository = remember { NetworkTimelineRepository(allowCleartext = BuildConfig.DEBUG, context = applicationContext) }
            val appPreferences = remember { AppPreferences(applicationContext) }
            val downloadDestinationManager = remember { DownloadDestinationManager(applicationContext, appPreferences) }
            val notifier = remember { SystemTransferNotifier(applicationContext, appPreferences) }
            var appSettings by remember { mutableStateOf(appPreferences.load()) }
            var sessionGeneration by remember { mutableStateOf(0) }
            var pendingServerNotice by remember { mutableStateOf<String?>(null) }
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
                    key(sessionGeneration) {
                        val factory = remember(sessionGeneration) { SetupViewModel.Factory(repository, sessionStore, bootstrapRepository, rebindRepository, BuildConfig.DEBUG) }
                        val setupViewModel: SetupViewModel = viewModel(key = "setup-$sessionGeneration", factory = factory)
                        val uiState by setupViewModel.uiState.collectAsStateWithLifecycle()
                        AnimatedContent(
                            targetState = uiState.isReady,
                            transitionSpec = { fadeIn() togetherWith fadeOut() },
                            label = "setup-state",
                        ) { isReady ->
                            if (isReady) {
                                PairingContent(
                                    sessionStore = sessionStore,
                                    themeMode = themeMode,
                                    appSettings = appSettings,
                                    downloadDestinationManager = downloadDestinationManager,
                                    notifier = notifier,
                                    serverSwitchNotice = pendingServerNotice,
                                    incomingShare = incomingShare,
                                    onShareConsumed = ::consumeIncomingShare,
                                    onServerSwitchNoticeConsumed = { pendingServerNotice = null },
                                    onThemeModeChange = { selected ->
                                        themeMode = selected
                                        themePreferences.edit().putString("theme_mode", selected.name).apply()
                                    },
                                    onDefaultSaveTreeChanged = { uri ->
                                        if (uri == null) downloadDestinationManager.clearDefaultTree()
                                        else downloadDestinationManager.persistDefaultTree(uri)
                                        appSettings = appPreferences.load()
                                    },
                                    onNotificationsChanged = { enabled ->
                                        appPreferences.setNotificationsEnabled(enabled)
                                        appSettings = appPreferences.load()
                                    },
                                    onAddServer = { name, address, setupToken ->
                                        runCatching {
                                            val claimed = repository.claim(address, setupToken)
                                            sessionStore.save(claimed)
                                            sessionStore.activeProfileId()?.let { sessionStore.renameProfile(it, name) }
                                            sessionGeneration += 1
                                        }
                                    },
                                    onSwitchServer = { profileId ->
                                        runCatching {
                                            val profileName = requireNotNull(sessionStore.profiles().firstOrNull { it.id == profileId }) { "服务器档案不存在。" }.name
                                            val candidate = requireNotNull(sessionStore.loadProfile(profileId)) { "服务器档案不存在。" }
                                            validationRepository.list(candidate)
                                            check(sessionStore.switchProfile(profileId)) { "无法切换服务器档案。" }
                                            pendingServerNotice = "已切换到 $profileName"
                                            sessionGeneration += 1
                                        }
                                    },
                                    onRenameServer = { profileId, name ->
                                        val renamed = sessionStore.renameProfile(profileId, name)
                                        if (renamed) sessionGeneration += 1
                                        renamed
                                    },
                                    onDeleteServer = { profileId ->
                                        val deleted = sessionStore.deleteProfile(profileId)
                                        if (deleted) sessionGeneration += 1
                                        deleted
                                    },
                                    onSessionChanged = { sessionGeneration += 1 },
                                )
                            } else {
                                SetupScreen(
                                    state = uiState,
                                    allowCleartext = BuildConfig.DEBUG,
                                    onServerAddressChange = setupViewModel::updateServerAddress,
                                    onSetupTokenChange = setupViewModel::updateSetupToken,
                                    onClaim = setupViewModel::claimServer,
                                    onBootstrapQRCode = setupViewModel::onBootstrapScanned,
                                    onConfirmBootstrap = setupViewModel::confirmBootstrap,
                                    onCancelBootstrap = setupViewModel::cancelBootstrap,
                                    onConfirmRebind = setupViewModel::confirmRebind,
                                    onCancelRebind = setupViewModel::cancelRebind,
                                )
                            }
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
    appSettings: AppSettings,
    downloadDestinationManager: DownloadDestinationManager,
    notifier: SystemTransferNotifier,
    serverSwitchNotice: String?,
    incomingShare: IncomingShare?,
    onShareConsumed: () -> Unit,
    onServerSwitchNoticeConsumed: () -> Unit,
    onThemeModeChange: (ThemeMode) -> Unit,
    onDefaultSaveTreeChanged: (Uri?) -> Unit,
    onNotificationsChanged: (Boolean) -> Unit,
    onAddServer: suspend (String, String, String) -> Result<Unit>,
    onSwitchServer: suspend (String) -> Result<Unit>,
    onRenameServer: (String, String) -> Boolean,
    onDeleteServer: (String) -> Boolean,
    onSessionChanged: () -> Unit,
) {
    val pairingRepository = remember { NetworkPairingRepository(allowCleartext = BuildConfig.DEBUG) }
    val bootstrapRepository = remember { NetworkBootstrapRepository(allowCleartext = BuildConfig.DEBUG) }
    val rebindRepository = remember { NetworkRebindRepository(allowCleartext = BuildConfig.DEBUG) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val timelineRepository = remember { NetworkTimelineRepository(allowCleartext = BuildConfig.DEBUG, context = context.applicationContext) }
    val pairingFactory = remember { PairingViewModel.Factory(pairingRepository, sessionStore, bootstrapRepository, rebindRepository, BuildConfig.DEBUG, onSessionChanged) }
    val timelineFactory = remember { TimelineViewModel.Factory(timelineRepository, sessionStore, notifier) }
    val lanHttpClient = remember { OkHttpClient() }
    val profileId = sessionStore.activeProfileId().orEmpty()
    val pairingViewModel: PairingViewModel = viewModel(key = "pairing-$profileId", factory = pairingFactory)
    val timelineViewModel: TimelineViewModel = viewModel(key = "timeline-$profileId", factory = timelineFactory)
    val pairingUiState by pairingViewModel.uiState.collectAsStateWithLifecycle()
    val timelineUiState by timelineViewModel.uiState.collectAsStateWithLifecycle()
    val serverProfiles = sessionStore.profiles()
    val activeServerName = serverProfiles.firstOrNull { it.id == profileId }?.serverAddress?.let(::defaultProfileName) ?: "当前服务器"
    var lanOpen by rememberSaveable(profileId) { mutableStateOf(false) }
    var lanGeneration by rememberSaveable(profileId) { mutableStateOf(0) }

    LifecycleStartEffect(timelineViewModel) {
        timelineViewModel.start()
        onStopOrDispose { timelineViewModel.stop() }
    }

    if (pairingUiState.screen == com.transdot.transferassistant.ui.PairingScreen.Home) {
        if (lanOpen) {
            val lanFactory = remember(profileId, lanGeneration) {
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T = createLanResources(
                        context = context.applicationContext,
                        session = requireNotNull(sessionStore.load()) { "当前服务器会话不存在。" },
                        preferences = AppPreferences(context.applicationContext),
                        httpClient = lanHttpClient,
                    ) as T
                }
            }
            val resources: LanResources = viewModel(key = "lan-$profileId-$lanGeneration", factory = lanFactory)
            val lanState by resources.model.uiState.collectAsStateWithLifecycle()
            LanTransferScreen(
                state = lanState,
                receiveFolderLabel = downloadDestinationManager.folderLabel(),
                onFilesSelected = { resources.model.enqueue(it.map(Uri::toString)) },
                onReceiveFolderSelected = onDefaultSaveTreeChanged,
                onRetry = resources.model::retry,
                onCancel = resources.model::cancelActive,
                onReconnect = resources.model::reconnect,
                onClearError = resources.model::clearError,
                onBack = {
                    resources.close()
                    lanGeneration += 1
                    lanOpen = false
                },
            )
        } else TimelineScreen(
            state = timelineUiState,
            ownDeviceId = pairingUiState.deviceId,
            themeMode = themeMode,
            appSettings = appSettings,
            downloadDestinationManager = downloadDestinationManager,
            serverProfiles = serverProfiles,
            activeProfileId = profileId,
            activeServerName = activeServerName,
            serverSwitchNotice = serverSwitchNotice,
            incomingShare = incomingShare,
            onShareConsumed = onShareConsumed,
            onServerSwitchNoticeConsumed = onServerSwitchNoticeConsumed,
            onThemeModeChange = onThemeModeChange,
            onDefaultSaveTreeChanged = onDefaultSaveTreeChanged,
            onNotificationsChanged = onNotificationsChanged,
            onAddServer = onAddServer,
            onSwitchServer = onSwitchServer,
            onRenameServer = onRenameServer,
            onDeleteServer = onDeleteServer,
            onDraftChange = timelineViewModel::updateDraft,
            onSend = timelineViewModel::sendText,
            onUpload = timelineViewModel::uploadFiles,
            onRetryUpload = timelineViewModel::retryUpload,
            onDownload = timelineViewModel::download,
            onDownloadResultConsumed = timelineViewModel::clearCompletedDownload,
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
            onOpenLanTransfer = { lanOpen = true },
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
            onConfirmBootstrap = pairingViewModel::confirmBootstrap,
            onCancelBootstrap = pairingViewModel::cancelBootstrap,
            onConfirmRebind = pairingViewModel::confirmRebind,
            onCancelRebind = pairingViewModel::cancelRebind,
        )
    }
}

private class LanResources(
    val model: LanTransferViewModel,
    private val signaling: LanSignalingClient,
    private val scope: CoroutineScope,
) : ViewModel(), AutoCloseable {
    private var closed = false
    override fun close() {
        if (closed) return
        closed = true
        model.close()
        signaling.close()
        scope.cancel()
    }

    override fun onCleared() = close()
}

private fun createLanResources(
    context: android.content.Context,
    session: com.transdot.transferassistant.data.StoredSession,
    preferences: AppPreferences,
    httpClient: OkHttpClient,
): LanResources {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val signaling = LanSignalingClient(session, OkHttpLanWebSocketTransport(httpClient), scope)
    val peer = LanPeerEngine(signaling, AndroidLanPeerFactory(context), scope)
    val files = StoredLanTransferFiles(LanFileStore(AndroidLanContentAccess(context.contentResolver, preferences)))
    val model = LanTransferViewModel(peer, files, AndroidLanForegroundController(context))
    signaling.start()
    return LanResources(model, signaling, scope)
}
