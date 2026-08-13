package com.mind.browser

import android.webkit.WebView

/**
 * 一个浏览器标签页。WebView 实例由 MainActivity 负责创建和销毁，
 * 这里只保存与该标签页相关的状态。
 */
class BrowserTab(
    val id: Long,
    val webView: WebView,
    val originalUserAgent: String?,
    var title: String = "",
    var desktopMode: Boolean = false,
    var blockImages: Boolean = false,
)
