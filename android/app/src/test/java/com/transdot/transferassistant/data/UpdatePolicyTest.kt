package com.transdot.transferassistant.data

import org.junit.Assert.*
import org.junit.Test

class UpdatePolicyTest {
    private val manifest = """{"versionCode":11,"versionName":"1.3.0","notes":"更新","assets":[{"abi":"arm64-v8a","url":"https://github.com/xiaoxiaoxiaoHuanGe/TransDot/releases/download/v1.3.0/app.apk","size":100,"sha256":"${"a".repeat(64)}","minSdk":23},{"abi":"universal","url":"https://github.com/xiaoxiaoxiaoHuanGe/TransDot/releases/download/v1.3.0/universal.apk","size":200,"sha256":"${"b".repeat(64)}","minSdk":23}]}"""
    @Test fun selectsCompatiblePackageAndComparesNumericVersion() {
        val update = parseUpdateManifest(manifest, listOf("arm64-v8a"), 36, 10)
        assertEquals("arm64-v8a", update?.asset?.abi)
        assertEquals(11L, update?.versionCode)
        assertNull(parseUpdateManifest(manifest, listOf("arm64-v8a"), 36, 11))
        assertEquals("universal", parseUpdateManifest(manifest, listOf("x86_64"), 36, 10)?.asset?.abi)
        assertNull(parseUpdateManifest(manifest, listOf("arm64-v8a"), 22, 10))
    }
    @Test fun rejectsUntrustedDownloadAndInvalidChecksum() {
        assertTrue(runCatching { parseUpdateManifest(manifest.replace("https://github.com/", "http://github.com/"), listOf("arm64-v8a"),36,10) }.isFailure)
        assertTrue(runCatching { parseUpdateManifest(manifest.replace("a".repeat(64), "invalid"), listOf("arm64-v8a"),36,10) }.isFailure)
        assertTrue(runCatching { parseUpdateManifest(manifest.replace("xiaoxiaoxiaoHuanGe/TransDot", "other/repo"), listOf("arm64-v8a"),36,10) }.isFailure)
    }
}
