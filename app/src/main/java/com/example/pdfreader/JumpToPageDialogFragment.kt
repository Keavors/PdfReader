package com.example.pdfreader

import android.app.Dialog
import android.os.Bundle
import android.widget.EditText
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale

/** Переход к странице по номеру. */
class JumpToPageDialogFragment : DialogFragment() {

    private lateinit var input: EditText

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val pageCount = requireArguments().getInt(ARG_PAGE_COUNT)
        val view = layoutInflater.inflate(R.layout.dialog_jump_to_page, null)
        input = view.findViewById(R.id.etPage)
        input.hint = getString(R.string.jump_hint, pageCount)
        if (savedInstanceState == null) {
            // Номер страницы человек видит с единицы, внутри они считаются с нуля.
            // Локаль намеренно фиксированная: поле числовое и читается обратно
            // как обычные арабские цифры.
            input.setText(String.format(Locale.US, "%d", requireArguments().getInt(ARG_CURRENT) + 1))
            input.selectAll()
        }

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.jump_title)
            .setView(view)
            .setPositiveButton(R.string.action_go) { _, _ -> jump(pageCount) }
            .setNegativeButton(R.string.action_cancel, null)
            .create()
    }

    private fun jump(pageCount: Int) {
        val entered = input.text.toString().toIntOrNull() ?: return
        val page = entered.coerceIn(1, pageCount) - 1
        setFragmentResult(RESULT_KEY, bundleOf(EXTRA_PAGE to page))
    }

    companion object {
        const val TAG = "jump_to_page"
        const val RESULT_KEY = "jump_to_page"
        const val EXTRA_PAGE = "page"
        private const val ARG_PAGE_COUNT = "page_count"
        private const val ARG_CURRENT = "current"

        fun newInstance(currentPage: Int, pageCount: Int) = JumpToPageDialogFragment().apply {
            arguments = bundleOf(ARG_CURRENT to currentPage, ARG_PAGE_COUNT to pageCount)
        }
    }
}
