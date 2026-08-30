package com.example.pdfreader

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

/** Список недавно открытых файлов. */
class RecentFilesAdapter(
    private val onClick: (RecentFile) -> Unit,
) : ListAdapter<RecentFile, RecentFilesAdapter.ViewHolder>(DIFF) {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.tvFileName)
        val path: TextView = view.findViewById(R.id.tvFilePath)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_recent_file, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.name.text = item.name
        val folder = readablePath(item.uri)
        holder.path.text = folder
        // Путь известен не для всех источников - пустую строку не показываем.
        holder.path.visibility = if (folder.isEmpty()) View.GONE else View.VISIBLE
        holder.itemView.setOnClickListener { onClick(item) }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<RecentFile>() {
            override fun areItemsTheSame(old: RecentFile, new: RecentFile) = old.uri == new.uri
            override fun areContentsTheSame(old: RecentFile, new: RecentFile) = old == new
        }
    }
}
