package com.tvbrowser.app

import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tvbrowser.app.model.Bookmark
import com.tvbrowser.app.model.BookmarkStore
import com.tvbrowser.app.model.DefaultBookmarks
import com.tvbrowser.app.ui.BookmarkAdapter
import com.tvbrowser.app.ui.HomeTile

class MainActivity : AppCompatActivity() {

    private lateinit var store: BookmarkStore
    private lateinit var adapter: BookmarkAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        store = BookmarkStore(this)

        val grid = findViewById<RecyclerView>(R.id.bookmarkGrid)
        grid.layoutManager = GridLayoutManager(this, 4)
        adapter = BookmarkAdapter(
            mutableListOf(),
            onClick = { tile -> handleClick(tile) },
            onLongClick = { tile -> handleLongClick(tile) }
        )
        grid.adapter = adapter
        refreshTiles()
    }

    override fun onResume() {
        super.onResume()
        refreshTiles()
    }

    private fun refreshTiles() {
        val tiles = mutableListOf<HomeTile>(HomeTile.OpenUrl)
        DefaultBookmarks.list.forEach { tiles.add(HomeTile.Site(it)) }
        store.getCustomBookmarks().forEach { tiles.add(HomeTile.Site(it)) }
        tiles.add(HomeTile.AddShortcut)
        adapter.submit(tiles)
    }

    private fun handleClick(tile: HomeTile) {
        when (tile) {
            is HomeTile.Site -> openBrowser(tile.bookmark.url)
            HomeTile.OpenUrl -> showUrlDialog()
            HomeTile.AddShortcut -> showAddBookmarkDialog()
        }
    }

    private fun handleLongClick(tile: HomeTile): Boolean {
        if (tile is HomeTile.Site && tile.bookmark.removable) {
            AlertDialog.Builder(this)
                .setTitle(tile.bookmark.name)
                .setMessage(R.string.action_delete)
                .setPositiveButton(R.string.action_delete) { _, _ ->
                    store.removeBookmark(tile.bookmark)
                    refreshTiles()
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
            return true
        }
        return false
    }

    private fun openBrowser(url: String) {
        val intent = Intent(this, BrowserActivity::class.java)
        intent.putExtra(BrowserActivity.EXTRA_URL, url)
        startActivity(intent)
    }

    private fun showUrlDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.url_hint)
            setSingleLine(true)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.open_url)
            .setView(container)
            .setPositiveButton(R.string.action_go) { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) openBrowser(normalizeInput(text))
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun showAddBookmarkDialog() {
        val nameInput = EditText(this).apply { hint = getString(R.string.new_bookmark_name) }
        val urlInput = EditText(this).apply { hint = getString(R.string.new_bookmark_url) }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 0)
            addView(nameInput)
            addView(urlInput)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.add_bookmark)
            .setView(container)
            .setPositiveButton(R.string.action_go) { _, _ ->
                val name = nameInput.text.toString().trim()
                val url = urlInput.text.toString().trim()
                if (name.isNotEmpty() && url.isNotEmpty()) {
                    store.addBookmark(Bookmark(name, normalizeInput(url)))
                    refreshTiles()
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun normalizeInput(text: String): String {
        val looksLikeUrl = Patterns.WEB_URL.matcher(text).matches() ||
            text.startsWith("http://") || text.startsWith("https://")
        return when {
            text.startsWith("http://") || text.startsWith("https://") -> text
            looksLikeUrl -> "https://$text"
            else -> "https://www.google.com/search?q=" + urlEncode(text)
        }
    }

    private fun urlEncode(text: String): String =
        java.net.URLEncoder.encode(text, "UTF-8")
}
