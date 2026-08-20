package com.transdot.transferassistant.data

import java.net.URI

data class ServerProfileSummary(
    val id: String,
    val name: String,
    val serverAddress: String,
    val instanceId: String = "",
)

data class ProfileRemovalResult(
    val profiles: List<ServerProfileSummary>,
    val activeProfileId: String?,
)

enum class ServerProfileDisplayStatus {
    Connected,
    Connecting,
    Offline,
    Saved,
}

fun serverProfileStatus(
    isActive: Boolean,
    isConnected: Boolean,
    isConnecting: Boolean,
): ServerProfileDisplayStatus = when {
    !isActive -> ServerProfileDisplayStatus.Saved
    isConnected -> ServerProfileDisplayStatus.Connected
    isConnecting -> ServerProfileDisplayStatus.Connecting
    else -> ServerProfileDisplayStatus.Offline
}

fun serverProfileStatusLabel(status: ServerProfileDisplayStatus): String = when (status) {
    ServerProfileDisplayStatus.Connected -> "已连接"
    ServerProfileDisplayStatus.Connecting -> "连接中"
    ServerProfileDisplayStatus.Offline -> "离线"
    ServerProfileDisplayStatus.Saved -> "已保存"
}

fun activeServerStatusLine(serverName: String, status: ServerProfileDisplayStatus): String =
    "$serverName · ${serverProfileStatusLabel(status)}"

fun activeServerStatusLines(serverName: String, status: ServerProfileDisplayStatus): List<String> =
    listOf(serverName, serverProfileStatusLabel(status))

fun selectActiveProfile(profiles: List<ServerProfileSummary>, profileId: String): ServerProfileSummary? =
    profiles.firstOrNull { it.id == profileId }

fun removeProfile(
    profiles: List<ServerProfileSummary>,
    activeProfileId: String?,
    removeProfileId: String,
): ProfileRemovalResult {
    val remaining = profiles.filterNot { it.id == removeProfileId }
    val nextActive = when {
        activeProfileId != removeProfileId && remaining.any { it.id == activeProfileId } -> activeProfileId
        else -> remaining.firstOrNull()?.id
    }
    return ProfileRemovalResult(remaining, nextActive)
}

fun defaultProfileName(serverAddress: String): String =
    runCatching { URI(serverAddress).host }.getOrNull()?.takeIf(String::isNotBlank)
        ?: serverAddress.removePrefix("https://").removePrefix("http://").substringBefore('/').substringBefore(':')
            .ifBlank { "服务器" }
