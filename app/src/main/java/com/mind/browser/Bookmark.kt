package com.mind.browser

/** 一条书签。id 用于删除，addedAt 用于排序（最新在前）。 */
data class Bookmark(
    val id: Long,
    val title: String,
    val url: String,
    val addedAt: Long,
)
