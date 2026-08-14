package com.transdot.transferassistant.data

import android.content.Context
import android.net.Uri

data class AppSettings(
    val defaultSaveTreeUri: String?,
    val notificationsEnabled: Boolean,
)

class AppPreferences(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load() = AppSettings(
        defaultSaveTreeUri = preferences.getString(KEY_DEFAULT_SAVE_TREE, null),
        notificationsEnabled = preferences.getBoolean(KEY_NOTIFICATIONS_ENABLED, false),
    )

    fun setDefaultSaveTree(uri: Uri?) {
        preferences.edit().apply {
            if (uri == null) remove(KEY_DEFAULT_SAVE_TREE) else putString(KEY_DEFAULT_SAVE_TREE, uri.toString())
        }.apply()
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_NOTIFICATIONS_ENABLED, enabled).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "app_preferences"
        const val KEY_DEFAULT_SAVE_TREE = "default_save_tree_uri"
        const val KEY_NOTIFICATIONS_ENABLED = "notifications_enabled"
    }
}
