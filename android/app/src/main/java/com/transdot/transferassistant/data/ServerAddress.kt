package com.transdot.transferassistant.data

import java.net.URI

internal object ServerAddress {
    fun normalize(rawValue: String, allowCleartext: Boolean): String {
        val value = rawValue.trim()
        require(value.isNotEmpty()) { "请输入服务器地址。" }

        val uri = runCatching { URI(value) }.getOrElse {
            throw IllegalArgumentException("服务器地址格式不正确。")
        }
        val scheme = uri.scheme?.lowercase()
        require(scheme == "https" || scheme == "http") { "服务器地址必须以 https:// 开头。" }
        require(allowCleartext || scheme == "https") { "正式版本只允许 HTTPS 服务器。" }
        require(uri.userInfo == null) { "服务器地址不能包含用户名或密码。" }
        require(!uri.host.isNullOrBlank()) { "服务器地址缺少有效域名或 IP。" }
        require(uri.query == null && uri.fragment == null) { "服务器地址不能包含查询参数或片段。" }
        require(uri.path.isNullOrEmpty() || uri.path == "/") { "服务器地址不能包含额外路径。" }
        require(uri.port == -1 || uri.port in 1..65535) { "服务器端口无效。" }

        return URI(scheme, null, uri.host, uri.port, null, null, null).toASCIIString()
    }
}
