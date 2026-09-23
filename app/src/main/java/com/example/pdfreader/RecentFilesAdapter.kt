package com.example.pdfreader

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

/**
 * Строка списка недавних в готовом к показу виде.
 *
 * Папку и дату разбираем один раз при сборке списка, а не на каждой
 * перерисовке строки.
 *
 * @param openedAt когда открывали в прошлый раз, готовой строкой; пусто,
 *   если время неизвестно — например, запись осталась от старой версии
 * @param available файл ещё можно открыть; недоступные показываем приглушённо,
 *   чтобы было видно, почему по ним ничего не происходит.
 */
data class RecentFileItem(
    val file: RecentFile,
    val folder: String,
    val openedAt: String,
    val available: Boolean,
)

/** Список недавно открытых файлов. */
class RecentFilesAdapter(
    private val onClick: (RecentFileItem) -> Unit,
    private val onLongClick: (RecentFileItem) -> Unit,
) : ListAdapter<RecentFileItem, RecentFilesAdapter.ViewHolder>(DIFF) {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.tvFileName)
        val subtitle: TextView = view.findViewById(R.id.tvFilePath)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_recent_file, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.name.text = item.file.name
        holder.itemView.alpha = if (item.available) 1f else DIMMED_ALPHA

        val subtitle = subtitleFor(holder, item)
        holder.subtitle.text = subtitle
        // Подпись есть не у всех источников - пустую строку не показываем.
        holder.subtitle.visibility = if (subtitle.isEmpty()) View.GONE else View.VISIBLE

        holder.itemView.setOnClickListener { onClick(item) }
        holder.itemView.setOnLongClickListener {
            onLongClick(item)
            true
        }
    }

    /**
     * Подпись под именем: что важнее, то левее — так при нехватке места
     * обрезается менее нужное.
     */
    private fun subtitleFor(holder: ViewHolder, item: RecentFileItem): String {
        val unavailable = holder.itemView.context.getString(R.string.recent_unavailable)
        return listOfNotNull(
            unavailable.takeIf { !item.available },
            item.openedAt.takeIf { it.isNotEmpty() },
            item.folder.takeIf { it.isNotEmpty() },
        ).joinToString(SUBTITLE_SEPARATOR)
    }

    private companion object {
        const val DIMMED_ALPHA = 0.45f
        const val SUBTITLE_SEPARATOR = "  •  "

        val DIFF = object : DiffUtil.ItemCallback<RecentFileItem>() {
            override fun areItemsTheSame(old: RecentFileItem, new: RecentFileItem) =
                old.file.uri == new.file.uri

            override fun areContentsTheSame(old: RecentFileItem, new: RecentFileItem) = old == new
        }
    }
}
