package com.transdot.transferassistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.transdot.transferassistant.ui.theme.ThemeMode
import com.transdot.transferassistant.ui.theme.TransferAssistantTheme

@PreviewTest
@Preview(name = "Theme switches", widthDp = 280, heightDp = 260)
@Composable
fun ThemeModeSwitchPreview() {
    TransferAssistantTheme(mode = ThemeMode.Dark) {
        Surface {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                listOf(ThemeMode.Dark, ThemeMode.System, ThemeMode.Light).forEach { selected -> ThemeModeSwitch(selected) {} }
            }
        }
    }
}
