package com.mind.browser

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class BookmarkListAdapter(
    private val bookmarks: MutableList<Bookmark>,
    private val onOpen: (Bookmark) -> Unit,
    private val onDelete: (Bookmark) -> Unit,
    private val onEmpty: () -> Unit,
) : RecyclerView.Adapter<BookmarkListAdapter.BookmarkViewHolder>() {

    class BookmarkViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.bookmark_title)
        val host: TextView = view.findViewById(R.id.bookmark_host)
        val delete: ImageButton = view.findViewById(R.id.bookmark_delete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BookmarkViewHolder =
        BookmarkViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_bookmark, parent, false)
        )

    override fun getItemCount(): Int = bookmarks.size

    override fun onBindViewHolder(holder: BookmarkViewHolder, position: Int) {
        val bookmark = bookmarks[position]
        holder.title.text = bookmark.title.ifBlank { bookmark.url }
        holder.host.text = Uri.parse(bookmark.url).host.orEmpty().removePrefix("www.")
        holder.itemView.setOnClickListener {
            val p = holder.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onOpen(bookmarks[p])
        }
        holder.delete.setOnClickListener {
            val p = holder.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) {
                val removed = bookmarks.removeAt(p)
                onDelete(removed)
                notifyItemRemoved(p)
                if (bookmarks.isEmpty()) onEmpty()
            }
        }
    }
}
