package com.mind.browser

import android.content.Context
import android.net.Uri

/** 把结构化搜索结果渲染成我们自己的 HTML 页面（模板见 assets/search_page.html）。 */
object SearchPageRenderer {

    fun render(
        context: Context,
        query: String,
        results: List<SearchResult>,
        rawEngine: SearchEngine,
    ): String {
        val template = context.assets.open("search_page.html").bufferedReader().use { it.readText() }
        val terms = highlightTerms(query)
        return template
            .replace("__QUERY_ATTR__", escapeAttr(query))
            .replace("__QUERY__", escape(query))
            .replace("__COUNT__", results.size.toString())
            .replace("__ITEMS__", results.mapIndexed { i, r -> itemHtml(r, terms, i) }.joinToString("\n"))
            .replace("__ENGINE_NAME__", escape(rawEngine.name))
            .replace("__RAW_URL__", escapeAttr(rawEngine.url + Uri.encode(query)))
    }

    private fun itemHtml(r: SearchResult, terms: List<String>, index: Int): String {
        val host = Uri.parse(r.url).host.orEmpty().removePrefix("www.")
        return buildString {
            // --i 是入场动画的延迟序号，封顶 12 避免长列表尾部等太久
            append("<a class=\"item\" style=\"--i:").append(index.coerceAtMost(12))
            append("\" href=\"").append(escapeAttr(r.url)).append("\">")
            append("<div class=\"head\">")
            append("<span class=\"host\">").append(escape(host)).append("</span>")
            append("<span class=\"engine\">").append(escape(r.engine)).append("</span></div>")
            append("<div class=\"title\">").append(highlight(escape(r.title), terms)).append("</div>")
            if (r.snippet.isNotBlank()) {
                append("<div class=\"snippet\">").append(highlight(escape(r.snippet), terms)).append("</div>")
            }
            append("</a>")
        }
    }

    /** 参与高亮的查询词：长度 ≥ 2，或含中日韩文字（单字也有意义）。 */
    private fun highlightTerms(query: String): List<String> =
        query.split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.length >= 2 || it.any { c -> c in '一'..'鿿' } }
            .distinct()

    /**
     * 在已转义的文本里把查询词包上 <em class="hl">（不区分大小写）。
     * 所有词合并成一个正则单次扫描，避免后替换的词命中先前生成的标签。
     */
    private fun highlight(escaped: String, terms: List<String>): String {
        if (terms.isEmpty()) return escaped
        val pattern = terms.sortedByDescending { it.length }
            .joinToString("|") { Regex.escape(escape(it)) }
        return Regex(pattern, RegexOption.IGNORE_CASE).replace(escaped) { m ->
            "<em class=\"hl\">${m.value}</em>"
        }
    }

    private fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private fun escapeAttr(s: String): String = escape(s).replace("\"", "&quot;")
}
