package com.tvbrowser.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.tvbrowser.app.R
import com.tvbrowser.app.model.Bookmark

sealed class HomeTile {
    data class Site(val bookmark: Bookmark) : HomeTile()
    object OpenUrl : HomeTile()
    object AddShortcut : HomeTile()
}

class BookmarkAdapter(
    private val tiles: MutableList<HomeTile>,
    private val onClick: (HomeTile) -> Unit,
    private val onLongClick: (HomeTile) -> Boolean
) : RecyclerView.Adapter<BookmarkAdapter.TileHolder>() {

    class TileHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.tileTitle)
        val subtitle: TextView = view.findViewById(R.id.tileSubtitle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TileHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_bookmark, parent, false)
        return TileHolder(view)
    }

    override fun onBindViewHolder(holder: TileHolder, position: Int) {
        val tile = tiles[position]
        when (tile) {
            is HomeTile.Site -> {
                holder.title.text = tile.bookmark.name
                holder.subtitle.text = tile.bookmark.url
                holder.subtitle.visibility = View.VISIBLE
            }
            HomeTile.OpenUrl -> {
                holder.title.text = "🔎 " + holder.itemView.context.getString(R.string.open_url)
                holder.subtitle.visibility = View.GONE
            }
            HomeTile.AddShortcut -> {
                holder.title.text = "+ " + holder.itemView.context.getString(R.string.add_bookmark)
                holder.subtitle.visibility = View.GONE
            }
        }
        holder.itemView.setOnClickListener { onClick(tile) }
        holder.itemView.setOnLongClickListener { onLongClick(tile) }
    }

    override fun getItemCount(): Int = tiles.size

    fun submit(newTiles: List<HomeTile>) {
        tiles.clear()
        tiles.addAll(newTiles)
        notifyDataSetChanged()
    }
}
