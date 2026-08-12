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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.transdot.transferassistant.data.NetworkSetupRepository
import com.transdot.transferassistant.data.SecureSessionStore
import com.transdot.transferassistant.ui.ReadyScreen
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
                            ReadyScreen(
                                serverAddress = uiState.serverAddress,
                                deviceId = uiState.deviceId,
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
