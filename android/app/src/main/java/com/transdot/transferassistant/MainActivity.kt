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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.transdot.transferassistant.data.NetworkSetupRepository
import com.transdot.transferassistant.data.NetworkPairingRepository
import com.transdot.transferassistant.data.SecureSessionStore
import com.transdot.transferassistant.data.SessionStore
import com.transdot.transferassistant.ui.PairingFlow
import com.transdot.transferassistant.ui.PairingViewModel
import com.transdot.transferassistant.ui.SetupScreen
import com.transdot.transferassistant.ui.SetupViewModel
import com.transdot.transferassistant.ui.theme.TransferAssistantTheme

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

            TransferAssistantTheme {
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
                            PairingContent(sessionStore)
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
private fun PairingContent(sessionStore: SessionStore) {
    val repository = remember { NetworkPairingRepository(allowCleartext = BuildConfig.DEBUG) }
    val factory = remember { PairingViewModel.Factory(repository, sessionStore) }
    val pairingViewModel: PairingViewModel = viewModel(factory = factory)
    val pairingUiState by pairingViewModel.uiState.collectAsStateWithLifecycle()

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
