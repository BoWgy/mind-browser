package com.mind.browser

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/**
 * 轻量 URL 级广告拦截：按域名后缀匹配拦截网络请求。
 *
 * 规则来自 assets/adblock_hosts.txt，一行一个域名，
 * 兼容 `||example.com^`（EasyList 风格）和 `0.0.0.0 example.com`（hosts 风格）两种写法。
 * 只挡请求，不做页面元素隐藏（化妆过滤）；需要更强的过滤时在系统层面
 * 配合 AdGuard 之类的 VPN 过滤 App 即可，两者不冲突。
 */
class AdBlocker(context: Context) {

    private val blockedDomains = HashSet<String>()

    private val emptyResponse: WebResourceResponse
        get() = WebResourceResponse(
            "text/plain",
            "utf-8",
            ByteArrayInputStream(ByteArray(0)),
        )

    init {
        runCatching {
            context.assets.open("adblock_hosts.txt").bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    parseRule(line)?.let { blockedDomains.add(it) }
                }
            }
        }
    }

    fun intercept(request: WebResourceRequest): WebResourceResponse? =
        if (isBlocked(request.url?.host)) emptyResponse else null

    fun isBlocked(host: String?): Boolean {
        if (host.isNullOrEmpty()) return false
        // 逐级去掉最左子域做后缀匹配：a.b.example.com -> b.example.com -> example.com
        var h = host.lowercase()
        while (true) {
            if (blockedDomains.contains(h)) return true
            val dot = h.indexOf('.')
            if (dot < 0 || dot == h.lastIndex) return false
            h = h.substring(dot + 1)
        }
    }

    private fun parseRule(line: String): String? {
        var s = line.trim()
        if (s.isEmpty() || s.startsWith("#") || s.startsWith("!")) return null
        if (s.startsWith("||")) s = s.removePrefix("||").removeSuffix("^")
        // hosts 格式 "0.0.0.0 domain" / "127.0.0.1 domain"
        if (s.contains(' ')) {
            val parts = s.split(Regex("\\s+"))
            if (parts.size == 2 && (parts[0] == "0.0.0.0" || parts[0] == "127.0.0.1")) {
                s = parts[1]
            } else {
                return null
            }
        }
        return s.lowercase().takeIf { it.contains('.') }
    }
}
