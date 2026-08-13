package com.mind.browser

import android.net.Uri

/** 一个搜索引擎：名字用于展示，url 为前缀，后面直接拼 URL 编码后的关键词。 */
data class SearchEngine(val name: String, val url: String)

object UrlUtils {

    /** 域名 / localhost，可带端口和路径。 */
    private val DOMAIN_REGEX =
        Regex("^(localhost|([a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,})(:[0-9]{1,5})?(/\\S*)?$")

    /** 输入看着像网址吗（不含空格且匹配域名/localhost）。 */
    fun isUrlLike(input: String): Boolean {
        val q = input.trim()
        return q.startsWith("http://") || q.startsWith("https://") ||
            (!q.contains(' ') && DOMAIN_REGEX.matches(q))
    }

    /** 地址栏输入统一走这里：看着像网址就当网址，否则用所选搜索引擎。 */
    fun toUrlOrSearch(input: String, searchUrl: String): String {
        val q = input.trim()
        if (q.isEmpty()) return "about:blank"
        if (q.startsWith("http://") || q.startsWith("https://")) return q
        return if (!q.contains(' ') && DOMAIN_REGEX.matches(q)) {
            "https://$q"
        } else {
            searchUrl + Uri.encode(q)
        }
    }
}
