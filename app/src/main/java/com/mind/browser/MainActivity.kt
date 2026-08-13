package com.mind.browser

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.util.Log
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.color.DynamicColors
import com.mind.browser.databinding.ActivityMainBinding
import com.mind.browser.databinding.MenuBottomSheetBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adBlocker: AdBlocker
    private val tabs = mutableListOf<BrowserTab>()
    private var currentIndex = -1
    private var nextTabId = 1L

    private val bookmarkStore by lazy { BookmarkStore(this) }
    private val cosmeticJs by lazy {
        assets.open("adblock_cosmetic.js").bufferedReader().use { it.readText() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        adBlocker = AdBlocker(this)

        setupAddressBar()
        setupBottomBar()
        binding.swipeRefresh.setOnRefreshListener { currentTab()?.webView?.reload() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val webView = currentTab()?.webView
                if (webView != null && webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        tabs.forEach { it.webView.destroy() }
        tabs.clear()
        super.onDestroy()
    }

    private fun handleIntent(intent: Intent?) {
        val data = intent?.data
        // 支持外部直接发起搜索：mind://search?q=关键词
        if (data != null && data.scheme == "mind" && data.host == "search") {
            if (tabs.isEmpty()) openNewTab(HOME_URL)
            val q = data.getQueryParameter("q").orEmpty()
            if (q.isNotBlank()) openQueryOrUrl(q)
            return
        }
        val url = intent?.dataString
        openNewTab(if (url.isNullOrBlank()) HOME_URL else url)
    }

    // ---------- 标签页管理 ----------

    private fun currentTab(): BrowserTab? = tabs.getOrNull(currentIndex)

    private fun openNewTab(url: String) {
        val webView = createWebView()
        val tab = BrowserTab(
            id = nextTabId++,
            webView = webView,
            originalUserAgent = webView.settings.userAgentString,
        )
        tabs.add(tab)
        selectTab(tabs.lastIndex)
        webView.loadUrl(url)
    }

    private fun selectTab(index: Int) {
        if (index !in tabs.indices) return
        currentIndex = index
        val tab = tabs[index]
        // WebView 同一时间只挂一个在界面上，切换时重新挂载
        binding.swipeRefresh.removeAllViews()
        (tab.webView.parent as? ViewGroup)?.removeView(tab.webView)
        binding.swipeRefresh.addView(tab.webView)
        binding.addressBar.setText(displayUrl(tab.webView.url))
        updateNavState()
    }

    private fun closeTab(index: Int) {
        if (index !in tabs.indices) return
        val tab = tabs.removeAt(index)
        (tab.webView.parent as? ViewGroup)?.removeView(tab.webView)
        tab.webView.destroy()
        when {
            tabs.isEmpty() -> openNewTab(HOME_URL)
            index == currentIndex -> selectTab(index.coerceAtMost(tabs.lastIndex))
            index < currentIndex -> currentIndex--
            else -> updateNavState()
        }
    }

    private fun showTabsDialog() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_tabs, null)
        dialog.setContentView(view)
        val list = view.findViewById<RecyclerView>(R.id.tab_list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = TabListAdapter(
            tabs = tabs,
            selectedIndex = currentIndex,
            onSelect = { position -> selectTab(position); dialog.dismiss() },
            onClose = { position -> closeTab(position) },
        )
        view.findViewById<View>(R.id.btn_new_tab).setOnClickListener {
            openNewTab(HOME_URL)
            dialog.dismiss()
        }
        dialog.show()
    }

    // ---------- WebView ----------

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val webView = WebView(this)
        webView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
            mediaPlaybackRequiresUserGesture = true
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                // 前进/后退回到我们自己渲染的搜索结果页：从缓存重新渲染
                if (uri.host == SEARCH_PAGE_HOST) {
                    searchPageCache[uri.toString()]?.let { html ->
                        view.loadDataWithBaseURL(uri.toString(), html, "text/html", "utf-8", null)
                    }
                    return true
                }
                if (uri.scheme == "http" || uri.scheme == "https") return false
                // 主页搜索框提交的 mind://search?q=...，像网址直接访问，否则整理后用自己的页面展示
                if (uri.scheme == "mind" && uri.host == "search") {
                    val q = uri.getQueryParameter("q").orEmpty()
                    if (q.isNotBlank()) openQueryOrUrl(q)
                    return true
                }
                // tel:、intent:、App 跳转等交给系统处理
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                    true
                } catch (_: Exception) {
                    true
                }
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? = adBlocker.intercept(request)

            override fun onPageFinished(view: WebView, url: String) {
                // 注入页面广告清理脚本（搜索结果异步加载，脚本内有 MutationObserver 兜底）
                if (url.startsWith("http")) view.evaluateJavascript(cosmeticJs, null)
                binding.swipeRefresh.isRefreshing = false
                binding.progressBar.visibility = View.GONE
                if (view === currentTab()?.webView) {
                    binding.addressBar.setText(displayUrl(url))
                    updateNavState()
                }
                findTabByWebView(view)?.title = view.title.orEmpty()
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (view !== currentTab()?.webView) return
                if (newProgress < 100) {
                    binding.progressBar.visibility = View.VISIBLE
                    binding.progressBar.progress = newProgress
                } else {
                    binding.progressBar.visibility = View.GONE
                }
            }

            override fun onReceivedTitle(view: WebView, title: String) {
                findTabByWebView(view)?.title = title.orEmpty()
            }
        }
        webView.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            downloadWithSystem(url, contentDisposition, mimeType)
        }
        return webView
    }

    private fun findTabByWebView(webView: WebView): BrowserTab? =
        tabs.firstOrNull { it.webView === webView }

    private fun downloadWithSystem(url: String?, contentDisposition: String?, mimeType: String?) {
        if (url.isNullOrBlank()) return
        try {
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(fileName)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            getSystemService(DownloadManager::class.java)?.enqueue(request)
        } catch (_: Exception) {
            // 不支持的链接（如 blob:）直接忽略，后续版本再处理
        }
    }

    // ---------- 界面 ----------

    /** 首页/结果页搜索框与地址栏的统一入口：输入像网址就直接访问，否则走聚合搜索。 */
    private fun openQueryOrUrl(input: String) {
        val tab = currentTab() ?: return
        if (UrlUtils.isUrlLike(input)) {
            tab.webView.loadUrl(UrlUtils.toUrlOrSearch(input, FALLBACK_ENGINE.url))
        } else {
            performSearch(input)
        }
    }

    private fun setupAddressBar() {
        binding.addressBar.setOnEditorActionListener { v, actionId, event ->
            val isGo = actionId == EditorInfo.IME_ACTION_GO ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (isGo) {
                val input = v.text.toString()
                if (input.isNotBlank()) openQueryOrUrl(input)
                v.clearFocus()
                true
            } else {
                false
            }
        }
    }

    private fun setupBottomBar() {
        binding.btnHome.setOnClickListener {
            // 直接回首页；本地页的地址栏显示由 onPageFinished → displayUrl 负责清空
            currentTab()?.webView?.loadUrl(HOME_URL)
        }
        binding.btnBack.setOnClickListener { currentTab()?.webView?.takeIf { it.canGoBack() }?.goBack() }
        binding.btnForward.setOnClickListener { currentTab()?.webView?.takeIf { it.canGoForward() }?.goForward() }
        binding.btnTabs.setOnClickListener { showTabsDialog() }
        binding.btnMenu.setOnClickListener { showMenu() }
    }

    private fun updateNavState() {
        val webView = currentTab()?.webView
        binding.btnBack.isEnabled = webView?.canGoBack() == true
        binding.btnForward.isEnabled = webView?.canGoForward() == true
        binding.btnBack.alpha = if (binding.btnBack.isEnabled) 1f else 0.35f
        binding.btnForward.alpha = if (binding.btnForward.isEnabled) 1f else 0.35f
        binding.btnTabs.text = tabs.size.toString()
    }

    /** 主页和本地页不显示 file:// 路径，搜索结果页显示搜索词，保持地址栏干净 */
    private fun displayUrl(url: String?): String {
        if (url.isNullOrBlank() || url.startsWith("file:///android_asset/")) return ""
        val uri = Uri.parse(url)
        if (uri.host == SEARCH_PAGE_HOST) return uri.getQueryParameter("q") ?: ""
        return url
    }

    /** 底部弹出的主菜单：带图标、分组和开关，比系统 PopupMenu 清晰。 */
    private fun showMenu() {
        val tab = currentTab() ?: return
        val sheet = BottomSheetDialog(this)
        val b = MenuBottomSheetBinding.inflate(layoutInflater)
        sheet.setContentView(b.root)

        b.switchDesktop.isChecked = tab.desktopMode
        b.switchBlockImages.isChecked = tab.blockImages
        val bookmarked = tab.webView.url?.let { bookmarkStore.contains(it) } == true
        b.bookmarkAddIcon.setImageResource(
            if (bookmarked) R.drawable.ic_menu_bookmark else R.drawable.ic_menu_bookmark_border
        )
        b.bookmarkAddLabel.setText(
            if (bookmarked) R.string.menu_bookmark_remove else R.string.menu_bookmark_add
        )

        b.rowNewTab.setOnClickListener { sheet.dismiss(); openNewTab(HOME_URL) }
        b.rowBookmarks.setOnClickListener { sheet.dismiss(); showBookmarksDialog() }
        b.rowBookmarkAdd.setOnClickListener { sheet.dismiss(); toggleBookmark(tab) }
        b.rowRefresh.setOnClickListener { sheet.dismiss(); tab.webView.reload() }
        b.rowShare.setOnClickListener { sheet.dismiss(); shareCurrentPage(tab) }
        b.rowCopy.setOnClickListener { sheet.dismiss(); copyCurrentLink(tab) }
        b.rowDesktop.setOnClickListener { sheet.dismiss(); toggleDesktopMode(tab) }
        b.rowBlockImages.setOnClickListener { sheet.dismiss(); toggleBlockImages(tab) }

        sheet.show()
    }

    /** 收藏/取消收藏当前页；本地页（首页、结果整理页）不支持。 */
    private fun toggleBookmark(tab: BrowserTab) {
        val url = tab.webView.url ?: return
        if (!url.startsWith("http")) {
            Toast.makeText(this, R.string.toast_bookmark_unsupported, Toast.LENGTH_SHORT).show()
            return
        }
        val title = tab.webView.title?.takeIf { it.isNotBlank() } ?: url
        val added = bookmarkStore.toggle(title, url)
        Toast.makeText(
            this,
            if (added) R.string.toast_bookmark_added else R.string.toast_bookmark_removed,
            Toast.LENGTH_SHORT,
        ).show()
    }

    /** 书签列表：点击在当前标签打开，右侧按钮删除。 */
    private fun showBookmarksDialog() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_bookmarks, null)
        dialog.setContentView(view)
        val empty = view.findViewById<View>(R.id.bookmarks_empty)
        val bookmarks = bookmarkStore.all().toMutableList()
        empty.visibility = if (bookmarks.isEmpty()) View.VISIBLE else View.GONE
        val list = view.findViewById<RecyclerView>(R.id.bookmark_list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = BookmarkListAdapter(
            bookmarks = bookmarks,
            onOpen = { bookmark ->
                currentTab()?.webView?.loadUrl(bookmark.url)
                dialog.dismiss()
            },
            onDelete = { bookmark -> bookmarkStore.remove(bookmark.id) },
            onEmpty = { empty.visibility = View.VISIBLE },
        )
        dialog.show()
    }

    private fun toggleDesktopMode(tab: BrowserTab) {
        tab.desktopMode = !tab.desktopMode
        tab.webView.settings.userAgentString =
            if (tab.desktopMode) DESKTOP_UA else tab.originalUserAgent
        tab.webView.reload()
    }

    private fun toggleBlockImages(tab: BrowserTab) {
        tab.blockImages = !tab.blockImages
        tab.webView.settings.loadsImagesAutomatically = !tab.blockImages
        tab.webView.settings.blockNetworkImage = tab.blockImages
        tab.webView.reload()
    }

    private fun shareCurrentPage(tab: BrowserTab) {
        val url = tab.webView.url ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }
        startActivity(Intent.createChooser(intent, null))
    }

    private fun copyCurrentLink(tab: BrowserTab) {
        val url = tab.webView.url ?: return
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("link", url))
        Toast.makeText(this, R.string.toast_link_copied, Toast.LENGTH_SHORT).show()
    }

    // ---------- 搜索结果整理页 ----------

    /** 已渲染的搜索结果页缓存，key 为 search.local 链接，用于前进/后退恢复。 */
    private val searchPageCache = HashMap<String, String>()

    /** 抓取多个引擎并整理成自己的无广告结果页；抓不到就回退到原始搜索页。 */
    private fun performSearch(query: String) {
        val tab = currentTab() ?: return
        Log.d(TAG, "performSearch: $query")
        binding.addressBar.setText(query)
        binding.progressBar.visibility = View.VISIBLE
        SearchFetcher.search(SEARCH_SOURCES, query) { results ->
            runOnUiThread {
                binding.progressBar.visibility = View.GONE
                if (results.isEmpty()) {
                    // 抓取/解析失败（网络、验证码、对方改版）→ 回退原始搜索页
                    Toast.makeText(this, "结果整理失败，回退到原始搜索页", Toast.LENGTH_SHORT).show()
                    tab.webView.loadUrl(UrlUtils.toUrlOrSearch(query, FALLBACK_ENGINE.url))
                } else {
                    val byEngine = results.groupingBy { it.engine }.eachCount()
                        .entries.joinToString(" · ") { "${it.key} ${it.value}" }
                    Toast.makeText(this, "已整理 ${results.size} 条结果（$byEngine）", Toast.LENGTH_SHORT).show()
                    val baseUrl = "https://$SEARCH_PAGE_HOST/?q=" + Uri.encode(query)
                    val html = SearchPageRenderer.render(this, query, results, FALLBACK_ENGINE)
                    searchPageCache[baseUrl] = html
                    tab.webView.loadDataWithBaseURL(baseUrl, html, "text/html", "utf-8", null)
                }
            }
        }
    }

    companion object {
        private const val TAG = "MindBrowser"
        private const val HOME_URL = "file:///android_asset/home.html"

        /** 自渲染搜索结果页使用的虚拟主机名（不会真的发起网络请求）。 */
        private const val SEARCH_PAGE_HOST = "search.local"

        /** 聚合整理的信息源：必应 + 百度 + 神马，并行抓取、合并去重。 */
        private val SEARCH_SOURCES = listOf(
            SearchEngine("必应", "https://cn.bing.com/search?q="),
            SearchEngine("百度", "https://www.baidu.com/s?wd="),
            SearchEngine("神马", "https://m.sm.cn/s?q="),
        )

        /** 结果整理失败时回退的原始搜索页，也用作结果页底部"查看原始结果"链接。必应最稳定。 */
        private val FALLBACK_ENGINE = SearchEngine("必应", "https://cn.bing.com/search?q=")

        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}
