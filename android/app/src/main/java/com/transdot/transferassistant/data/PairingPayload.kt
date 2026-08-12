package com.transdot.transferassistant.data

import org.json.JSONObject

internal object PairingPayload {
    private val secretPattern = Regex("^[A-Za-z0-9_-]{43}$")
    private val sessionPattern = Regex("^[0-9a-fA-F-]{36}$")

    fun parse(rawValue: String): PairingCredential.QR {
        val json = runCatching { JSONObject(rawValue.trim()) }.getOrElse {
            throw IllegalArgumentException("这不是传输助手配对二维码。")
        }
        require(json.optInt("v", -1) == 1) { "二维码版本不受支持。" }
        val sessionId = json.optString("session_id")
        val secret = json.optString("qr_secret")
        require(sessionPattern.matches(sessionId)) { "二维码缺少有效会话。" }
        require(secretPattern.matches(secret)) { "二维码缺少安全凭据。" }
        return PairingCredential.QR(sessionId, secret)
    }
}
