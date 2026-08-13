package com.mind.browser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 书签的本地存储：JSON 文件（filesDir/bookmarks.json）。
 * 书签量级小（几十到几百条），全量读写在内存开销可忽略，不必引入数据库。
 */
class BookmarkStore(context: Context) {

    private val file = File(context.filesDir, "bookmarks.json")
    private val bookmarks = mutableListOf<Bookmark>()
    private var loaded = false

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        runCatching {
            if (!file.exists()) return
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                bookmarks.add(
                    Bookmark(
                        id = o.getLong("id"),
                        title = o.getString("title"),
                        url = o.getString("url"),
                        addedAt = o.getLong("addedAt"),
                    )
                )
            }
        }
    }

    /** 全部书签，最新在前。 */
    @Synchronized
    fun all(): List<Bookmark> {
        ensureLoaded()
        return bookmarks.sortedByDescending { it.addedAt }
    }

    @Synchronized
    fun contains(url: String): Boolean {
        ensureLoaded()
        return bookmarks.any { it.url == url }
    }

    /** 已存在则取消收藏，否则添加；返回操作后是否处于收藏状态。 */
    @Synchronized
    fun toggle(title: String, url: String): Boolean {
        ensureLoaded()
        val existing = bookmarks.firstOrNull { it.url == url }
        return if (existing != null) {
            bookmarks.remove(existing)
            save()
            false
        } else {
            bookmarks.add(Bookmark(System.nanoTime(), title, url, System.currentTimeMillis()))
            save()
            true
        }
    }

    @Synchronized
    fun remove(id: Long) {
        ensureLoaded()
        bookmarks.removeAll { it.id == id }
        save()
    }

    private fun save() {
        val arr = JSONArray()
        bookmarks.forEach { b ->
            arr.put(
                JSONObject().apply {
                    put("id", b.id)
                    put("title", b.title)
                    put("url", b.url)
                    put("addedAt", b.addedAt)
                }
            )
        }
        file.writeText(arr.toString())
    }
}
