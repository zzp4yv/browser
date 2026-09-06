package com.tvbrowser.app.model

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Tiny JSON-in-SharedPreferences store for user-added shortcuts. No database needed for a handful of tiles. */
class BookmarkStore(context: Context) {

    private val prefs = context.getSharedPreferences("bookmarks", Context.MODE_PRIVATE)

    fun getCustomBookmarks(): List<Bookmark> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        val array = JSONArray(raw)
        return (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            Bookmark(obj.getString("name"), obj.getString("url"), removable = true)
        }
    }

    fun addBookmark(bookmark: Bookmark) {
        val current = getCustomBookmarks().toMutableList()
        current.add(bookmark)
        save(current)
    }

    fun removeBookmark(bookmark: Bookmark) {
        val current = getCustomBookmarks().filterNot { it.url == bookmark.url && it.name == bookmark.name }
        save(current)
    }

    private fun save(bookmarks: List<Bookmark>) {
        val array = JSONArray()
        bookmarks.forEach { b ->
            array.put(JSONObject().apply {
                put("name", b.name)
                put("url", b.url)
            })
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    companion object {
        private const val KEY = "custom_bookmarks"
    }
}
