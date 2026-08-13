package com.mind.browser

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class TabListAdapter(
    private val tabs: List<BrowserTab>,
    private val selectedIndex: Int,
    private val onSelect: (Int) -> Unit,
    private val onClose: (Int) -> Unit,
) : RecyclerView.Adapter<TabListAdapter.TabViewHolder>() {

    class TabViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.tab_title)
        val close: ImageButton = view.findViewById(R.id.tab_close)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TabViewHolder =
        TabViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_tab, parent, false)
        )

    override fun getItemCount(): Int = tabs.size

    @SuppressLint("NotifyDataSetChanged")
    override fun onBindViewHolder(holder: TabViewHolder, position: Int) {
        val tab = tabs[position]
        holder.title.text = tab.title.ifBlank { tab.webView.url.orEmpty().ifBlank { "新标签页" } }
        holder.title.setTypeface(
            null,
            if (position == selectedIndex) Typeface.BOLD else Typeface.NORMAL,
        )
        holder.itemView.setOnClickListener {
            val p = holder.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onSelect(p)
        }
        holder.close.setOnClickListener {
            val p = holder.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) {
                onClose(p)
                notifyDataSetChanged()
            }
        }
    }
}
