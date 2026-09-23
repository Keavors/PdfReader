package com.example.pdfreader

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.shockwave.pdfium.PdfDocument

/** Отступ вложенных пунктов оглавления, dp на уровень. */
private const val LEVEL_INDENT_DP = 16

/**
 * Оглавление документа.
 *
 * Дерево закладок приезжает уже разложенным в плоский список: так его можно
 * положить в аргументы фрагмента, и после поворота экрана оглавление не
 * приходится заново выпрашивать у документа.
 */
class OutlineDialogFragment : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val args = requireArguments()
        val titles = args.getStringArrayList(ARG_TITLES).orEmpty()
        val pages = args.getIntArray(ARG_PAGES) ?: IntArray(0)
        val levels = args.getIntArray(ARG_LEVELS) ?: IntArray(0)

        val list = RecyclerView(requireContext()).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = OutlineAdapter(titles, pages, levels) { page ->
                setFragmentResult(RESULT_KEY, Bundle().apply { putInt(EXTRA_PAGE, page) })
                dismiss()
            }
        }

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.outline_title)
            .setView(list)
            .setNegativeButton(R.string.action_cancel, null)
            .create()
    }

    private class OutlineAdapter(
        private val titles: List<String>,
        private val pages: IntArray,
        private val levels: IntArray,
        private val onClick: (Int) -> Unit,
    ) : RecyclerView.Adapter<OutlineAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.tvOutlineTitle)
            val page: TextView = view.findViewById(R.id.tvOutlinePage)

            /** Отступ из вёрстки: вложенность добавляется к нему, а не к прошлому значению. */
            val basePadding: Int = view.paddingStart
        }

        override fun getItemCount() = titles.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_outline_entry, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val density = holder.itemView.resources.displayMetrics.density
            val indent = (levels[position] * LEVEL_INDENT_DP * density).toInt()
            holder.title.text = titles[position]
            holder.page.text =
                holder.page.context.getString(R.string.outline_page, pages[position] + 1)
            holder.itemView.setPaddingRelative(
                holder.basePadding + indent,
                holder.itemView.paddingTop,
                holder.itemView.paddingEnd,
                holder.itemView.paddingBottom,
            )
            holder.itemView.setOnClickListener { onClick(pages[position]) }
        }
    }

    companion object {
        const val TAG = "outline"
        const val RESULT_KEY = "outline_page"
        const val EXTRA_PAGE = "page"
        private const val ARG_TITLES = "titles"
        private const val ARG_PAGES = "pages"
        private const val ARG_LEVELS = "levels"

        /** @return null, если в документе нет оглавления. */
        fun newInstance(bookmarks: List<PdfDocument.Bookmark>): OutlineDialogFragment? {
            val flat = flatten(bookmarks)
            if (flat.isEmpty()) return null
            return OutlineDialogFragment().apply {
                arguments = Bundle().apply {
                    putStringArrayList(ARG_TITLES, ArrayList(flat.map { it.title }))
                    putIntArray(ARG_PAGES, flat.map { it.page }.toIntArray())
                    putIntArray(ARG_LEVELS, flat.map { it.level }.toIntArray())
                }
            }
        }

        private class Entry(val title: String, val page: Int, val level: Int)

        private fun flatten(
            bookmarks: List<PdfDocument.Bookmark>,
            level: Int = 0,
            into: MutableList<Entry> = mutableListOf(),
        ): List<Entry> {
            bookmarks.forEach { bookmark ->
                val title = bookmark.title?.takeIf { it.isNotBlank() } ?: return@forEach
                into += Entry(title, bookmark.pageIdx.toInt().coerceAtLeast(0), level)
                if (bookmark.hasChildren()) flatten(bookmark.children, level + 1, into)
            }
            return into
        }
    }
}
