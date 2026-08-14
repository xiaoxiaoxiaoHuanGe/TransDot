package com.transdot.transferassistant.ui

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.transdot.transferassistant.ui.theme.ThemeMode
import com.transdot.transferassistant.ui.theme.TransferAssistantTheme

private val NoOp = {}

@PreviewTest
@Preview(name = "Setup light", widthDp = 393, heightDp = 852)
@Composable
fun SetupLightPreview() {
    TransferAssistantTheme(mode = ThemeMode.Light) {
        SetupScreen(
            state = SetupUiState(),
            allowCleartext = false,
            onServerAddressChange = {},
            onSetupTokenChange = {},
            onClaim = NoOp,
        )
    }
}

@PreviewTest
@Preview(
    name = "Ready dark",
    widthDp = 393,
    heightDp = 852,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun ReadyDarkPreview() {
    TransferAssistantTheme(mode = ThemeMode.Dark) {
        ReadyScreen(
            serverAddress = "https://transfer.example.com",
            deviceId = "android-master-7F2A",
            onPairWindows = NoOp,
        )
    }
}

@PreviewTest
@Preview(name = "Manual pairing light", widthDp = 393, heightDp = 852)
@Composable
fun ManualPairingLightPreview() {
    TransferAssistantTheme(mode = ThemeMode.Light) {
        PairingFlow(
            state = PairingUiState(
                screen = PairingScreen.Manual,
                serverAddress = "https://transfer.example.com",
                deviceId = "android-master-7F2A",
                manualCode = "538219",
            ),
            onOpenScanner = NoOp,
            onOpenManual = NoOp,
            onBack = NoOp,
            onCodeChange = {},
            onSubmitCode = NoOp,
            onQRCode = {},
            onScannerError = {},
            onConfirmReplacement = NoOp,
            onCancelReplacement = NoOp,
        )
    }
}
