package com.transdot.transferassistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerProfilesTest {
    private val profiles = listOf(
        ServerProfileSummary("one", "家中服务器", "https://home.example.com"),
        ServerProfileSummary("two", "云服务器", "https://cloud.example.com"),
    )

    @Test
    fun unknownProfileCannotBecomeActive() {
        assertNull(selectActiveProfile(profiles, "missing"))
        assertEquals("two", selectActiveProfile(profiles, "two")?.id)
    }

    @Test
    fun removingActiveProfileSelectsTheFirstRemainingProfile() {
        val result = removeProfile(profiles, activeProfileId = "one", removeProfileId = "one")

        assertEquals(listOf("two"), result.profiles.map(ServerProfileSummary::id))
        assertEquals("two", result.activeProfileId)
    }

    @Test
    fun defaultNameUsesServerHost() {
        assertEquals("home.example.com", defaultProfileName("https://home.example.com:8443/path"))
    }

    @Test
    fun activeProfileReflectsConnectionAndInactiveProfileIsSaved() {
        assertEquals(ServerProfileDisplayStatus.Connected, serverProfileStatus(isActive = true, isConnected = true, isConnecting = false))
        assertEquals(ServerProfileDisplayStatus.Connecting, serverProfileStatus(isActive = true, isConnected = false, isConnecting = true))
        assertEquals(ServerProfileDisplayStatus.Offline, serverProfileStatus(isActive = true, isConnected = false, isConnecting = false))
        assertEquals(ServerProfileDisplayStatus.Saved, serverProfileStatus(isActive = false, isConnected = true, isConnecting = false))
    }

    @Test
    fun activeServerStatusLineKeepsTheProfileNameVisible() {
        assertEquals("家中服务器 · 已连接", activeServerStatusLine("家中服务器", ServerProfileDisplayStatus.Connected))
        assertEquals("云服务器 · 连接中", activeServerStatusLine("云服务器", ServerProfileDisplayStatus.Connecting))
    }
}
