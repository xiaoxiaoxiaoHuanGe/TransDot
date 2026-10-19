package com.transdot.transferassistant.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import com.transdot.transferassistant.R
import com.transdot.transferassistant.ui.theme.ThemeMode

@Composable
internal fun ThemeModeSwitch(mode: ThemeMode, onChange: (ThemeMode) -> Unit) {
    val options = listOf(Triple(ThemeMode.Dark, R.drawable.ic_moon, "深色"), Triple(ThemeMode.System, R.drawable.ic_monitor, "跟随系统"), Triple(ThemeMode.Light, R.drawable.ic_sun, "浅色"))
    Surface(shape = CircleShape, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.padding(4.dp).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            options.forEach { (value, icon, label) ->
                val selected = mode == value
                Surface(shape = CircleShape, color = if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface,
                    border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null,
                    modifier = Modifier.selectable(selected, role = Role.RadioButton, onClick = { onChange(value) }).semantics { contentDescription = label }) {
                    Box(Modifier.size(width = 60.dp, height = 48.dp), contentAlignment = Alignment.Center) {
                        Icon(painterResource(icon), contentDescription = null, Modifier.size(20.dp), tint = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
