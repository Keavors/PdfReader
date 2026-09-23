package com.example.pdfreader

import android.app.Dialog
import android.os.Bundle
import androidx.annotation.StringRes
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Что можно сделать с записью из списка недавних.
 *
 * Набор действий зависит от файла: у копии, которую приложение перенесло к
 * себе, нет папки на устройстве, а удалять её отдельно от записи незачем.
 */
class RecentFileActionsDialogFragment : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val args = requireArguments()
        val actions = args.getStringArrayList(ARG_ACTIONS).orEmpty()
        val labels = actions.map { getString(labelFor(it)) }.toTypedArray()

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(args.getString(ARG_NAME))
            .setItems(labels) { _, index ->
                setFragmentResult(
                    RESULT_KEY,
                    Bundle().apply {
                        putString(EXTRA_ACTION, actions[index])
                        putString(EXTRA_URI, args.getString(ARG_URI))
                        putString(EXTRA_NAME, args.getString(ARG_NAME))
                    },
                )
            }
            .setNegativeButton(R.string.action_cancel, null)
            .create()
    }

    @StringRes
    private fun labelFor(action: String): Int = when (action) {
        ACTION_REVEAL -> R.string.recent_action_reveal
        ACTION_DELETE -> R.string.recent_action_delete
        else -> R.string.recent_action_remove
    }

    companion object {
        const val TAG = "recent_actions"
        const val RESULT_KEY = "recent_file_action"

        const val EXTRA_ACTION = "action"
        const val EXTRA_URI = "uri"
        const val EXTRA_NAME = "name"

        /** Показать папку, в которой лежит файл. */
        const val ACTION_REVEAL = "reveal"

        /** Убрать запись, файл на устройстве не трогать. */
        const val ACTION_REMOVE = "remove"

        /** Удалить сам файл. */
        const val ACTION_DELETE = "delete"

        private const val ARG_ACTIONS = "actions"
        private const val ARG_URI = "uri"
        private const val ARG_NAME = "name"

        fun newInstance(
            name: String,
            uri: String,
            canReveal: Boolean,
            canDelete: Boolean,
        ) = RecentFileActionsDialogFragment().apply {
            val actions = buildList {
                if (canReveal) add(ACTION_REVEAL)
                add(ACTION_REMOVE)
                if (canDelete) add(ACTION_DELETE)
            }
            arguments = Bundle().apply {
                putString(ARG_NAME, name)
                putString(ARG_URI, uri)
                putStringArrayList(ARG_ACTIONS, ArrayList(actions))
            }
        }
    }
}
