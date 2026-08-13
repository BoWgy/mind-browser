package com.mind.browser

import android.net.Uri
import android.util.Log
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 一条整理后的搜索结果。 */
data class SearchResult(
    val title: String,
    val url: String,
    val snippet: String,
    val engine: String,
)

/**
 * 后台抓取搜索引擎的结果页，解析成结构化数据。
 *
 * 解析依赖对方页面结构：百度/必应改版或触发验证码时会返回空列表，
 * 调用方必须兜底（回退到加载原始搜索页）。
 * 抓取过程有日志（tag: SearchFetcher），便于真机排查结构变化。
 */
object SearchFetcher {

    private const val TAG = "SearchFetcher"

    private val executor = Executors.newCachedThreadPool()

    private const val MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    /** 一条合并中的结果：原始数据 + 融合得分 + 首次出现序号（同分时保持稳定）。 */
    private data class MergeEntry(val result: SearchResult, var score: Double, val firstSeen: Int)

    /**
     * 并行抓取多个引擎，融合排序后回调。注意：回调在后台线程执行。
     *
     * 排序用名次融合（RRF 的简化版）：每条结果按它在单个引擎内的名次得分
     * （第 1 名 1 分、第 2 名 1/2 分、第 3 名 1/3 分……），同一条结果被多个
     * 引擎收录会累计加分——多引擎互为背书的结果通常更相关，排在前面。
     * 另外标题与搜索词完全一致（如搜"山西大学"命中"山西大学"官网）加 0.5 分，
     * 这是官网/权威站应排顶部的强信号。
     */
    fun search(
        engines: List<SearchEngine>,
        query: String,
        callback: (List<SearchResult>) -> Unit,
    ) {
        executor.execute {
            Log.d(TAG, "开始搜索: $query")
            val futures = engines.map { engine ->
                executor.submit<List<SearchResult>> {
                    try {
                        val r = fetch(engine, query)
                        Log.d(TAG, "${engine.name}: 解析到 ${r.size} 条")
                        r
                    } catch (e: Exception) {
                        Log.e(TAG, "${engine.name} 抓取/解析失败", e)
                        emptyList()
                    }
                }
            }
            val merged = LinkedHashMap<String, MergeEntry>()
            var seq = 0
            futures.forEach { future ->
                runCatching { future.get(20, TimeUnit.SECONDS) }.getOrDefault(emptyList())
                    .forEachIndexed { rank, result ->
                        val key = normalizeUrl(result.url)
                        var score = 1.0 / (rank + 1)
                        if (result.title.trim().equals(query.trim(), ignoreCase = true)) score += 0.5
                        val entry = merged[key]
                        if (entry == null) {
                            merged[key] = MergeEntry(result, score, seq++)
                        } else {
                            entry.score += score
                        }
                    }
            }
            val sortedEntries = merged.values
                .sortedWith(compareByDescending<MergeEntry> { it.score }.thenBy { it.firstSeen })
            Log.d(TAG, "合并后共 ${sortedEntries.size} 条")
            sortedEntries.take(5).forEachIndexed { i, e ->
                Log.d(
                    TAG,
                    "Top${i + 1}: ${"%.2f".format(e.score)} [${e.result.engine}] " +
                        "${e.result.title.take(24)} | ${e.result.url}",
                )
            }
            callback(sortedEntries.map { it.result })
        }
    }

    /** 去重键：网址归一化（去协议、www.、末尾斜杠、锚点，小写），避免同页不同链重复出现。 */
    private fun normalizeUrl(url: String): String {
        val uri = Uri.parse(url)
        val host = (uri.host ?: return url.lowercase()).removePrefix("www.")
        val path = (uri.path ?: "").removeSuffix("/")
        val queryPart = uri.encodedQuery?.let { "?$it" }.orEmpty()
        return (host + path + queryPart).lowercase()
    }

    private fun fetch(engine: SearchEngine, query: String): List<SearchResult> {
        val pageUrl = engine.url + Uri.encode(query)
        val conn = (URL(pageUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6000
            readTimeout = 8000
            setRequestProperty("User-Agent", MOBILE_UA)
            // 百度会对缺少 Accept 头的请求返回反爬空壳页，必须带上
            setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
        }
        val code = conn.responseCode
        if (code != HttpURLConnection.HTTP_OK) {
            Log.w(TAG, "${engine.name}: HTTP $code")
            return emptyList()
        }
        val html = conn.inputStream.bufferedReader().use { it.readText() }
        Log.d(TAG, "${engine.name}: 页面 ${html.length} 字符")
        val doc = Jsoup.parse(html, pageUrl)
        val results = when {
            "bing.com" in engine.url -> parseBing(doc, engine.name)
            "baidu.com" in engine.url -> parseBaidu(doc, engine.name)
            "sm.cn" in engine.url -> parseSm(doc, engine.name)
            else -> emptyList()
        }.take(10)
        if (results.isEmpty()) {
            // 对方改版或返回反爬页面：打出关键特征便于排查
            Log.w(
                TAG,
                "${engine.name}: 解析 0 条 | " +
                    "b_algo=${doc.select("li.b_algo").size} " +
                    "c-result=${doc.select("div.c-result").size} " +
                    "qk-card=${doc.select("div.qk-card").size} | " +
                    "开头: ${html.take(200)}",
            )
        }
        return results
    }

    private fun parseBing(doc: Document, engine: String): List<SearchResult> =
        doc.select("li.b_algo").mapNotNull { el ->
            // 移动版必应：真实链接在 a.tilk 上，h2 只是纯文本标题
            val a = el.selectFirst("a.tilk[href]") ?: el.selectFirst("a[href^=http]")
                ?: return@mapNotNull null
            val url = a.attr("href")
            val title = el.selectFirst("h2")?.text()?.trim().orEmpty()
            if (title.isEmpty() || !url.startsWith("http")) return@mapNotNull null
            if ("bing.com/search" in url) return@mapNotNull null // 站内跳转不算结果
            val snippet = el.selectFirst(".b_caption p, .b_lineclamp2, .b_lineclamp3, .b_algoSlug")
                ?.text()?.trim().orEmpty()
            SearchResult(title, url, snippet, engine)
        }

    /** data-log 里的 mu 字段是真实网址（比跳转链接好）。 */
    private val baiduMuRegex = Regex("\"mu\":\"(.*?)\"")

    private fun parseBaidu(doc: Document, engine: String): List<SearchResult> =
        doc.select("div.c-result, div.result").mapNotNull { el ->
            if (el.className().contains("ec_") || el.hasAttr("data-adid")) return@mapNotNull null
            // 法规要求广告必须标注“广告”：含独立“广告”角标的整条丢掉
            val hasAdBadge = el.allElements.any { node ->
                node.children().isEmpty() && node.text().trim() == "广告"
            }
            if (hasAdBadge) return@mapNotNull null
            val heading = el.selectFirst("h3") ?: return@mapNotNull null
            val title = heading.text().trim()
            val mu = baiduMuRegex.find(el.attr("data-log"))?.groupValues?.get(1).orEmpty()
            val url = if (mu.startsWith("http")) {
                mu
            } else {
                heading.selectFirst("a[href]")?.absUrl("href").orEmpty()
            }
            if (title.isEmpty() || url.isEmpty()) return@mapNotNull null
            SearchResult(title, url, extractBaiduSnippet(el, title), engine)
        }

    /**
     * 神马（m.sm.cn）：结果卡片是 div.qk-card，链接是真实地址；cpc/ad 开头的是广告卡。
     * 注意自然结果卡里混着垂直/聚合模块（分数线、热门院校、头卡等），
     * 它们不是网页结果，靠类名标记剔除。
     */
    private val SM_MODULE_MARKERS = listOf(
        "header-card", "tabs-", "fenshuxian", "hot-school", "undefined",
    )

    private fun parseSm(doc: Document, engine: String): List<SearchResult> =
        doc.select("div.qk-card").mapNotNull { el ->
            val cls = el.className()
            if (cls.contains("cpc") || cls.contains("ad-")) return@mapNotNull null
            if (SM_MODULE_MARKERS.any { it in cls }) return@mapNotNull null
            val a = el.selectFirst("a[href^=http]") ?: return@mapNotNull null
            val url = a.attr("href")
            if ("sm.cn" in url) return@mapNotNull null // 站内模块（相关搜索等）不算结果
            val title = (el.selectFirst("[class*=qk-title]")?.text() ?: a.text()).trim()
            if (title.isEmpty()) return@mapNotNull null
            val snippet = el.selectFirst("[class*=paragraph], [class*=desc]")
                ?.text()?.trim().orEmpty()
            SearchResult(title, url, snippet, engine)
        }

    /** 百度摘要的类名是混淆的，尽量挑，挑不到就用整块文本截断兜底。 */
    private fun extractBaiduSnippet(el: Element, title: String): String {
        el.selectFirst("[class*=abstract], [class*=content-text], [class*=cosc-text]")?.let {
            val t = it.text().trim()
            if (t.length >= 20) return t
        }
        val full = el.text().trim()
        if (full.length <= title.length + 10) return ""
        return (if (full.startsWith(title)) full.removePrefix(title) else full).take(140)
    }
}
