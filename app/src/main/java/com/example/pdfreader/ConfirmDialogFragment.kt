package com.example.pdfreader

import android.app.Dialog
import android.os.Bundle
import androidx.annotation.StringRes
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Обычное «точно?» перед необратимым действием.
 *
 * Фрагмент, а не голый AlertDialog: переживает поворот экрана и не оставляет
 * после себя утёкшее окно. Ответ приходит по [requestKey], который передали
 * при создании, — так одним классом обходятся все подтверждения в приложении.
 */
class ConfirmDialogFragment : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val args = requireArguments()
        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(args.getInt(ARG_TITLE))
            .setMessage(args.getString(ARG_MESSAGE))
            .setPositiveButton(args.getInt(ARG_POSITIVE)) { _, _ ->
                setFragmentResult(
                    args.getString(ARG_REQUEST_KEY).orEmpty(),
                    Bundle().apply { putString(EXTRA_PAYLOAD, args.getString(ARG_PAYLOAD)) },
                )
            }
            .setNegativeButton(R.string.action_cancel, null)
            .create()
    }

    companion object {
        const val TAG = "confirm"

        /** То, к чему относится подтверждение: например, ссылка на файл. */
        const val EXTRA_PAYLOAD = "payload"

        private const val ARG_REQUEST_KEY = "request_key"
        private const val ARG_TITLE = "title"
        private const val ARG_MESSAGE = "message"
        private const val ARG_POSITIVE = "positive"
        private const val ARG_PAYLOAD = "payload"

        fun newInstance(
            requestKey: String,
            @StringRes title: Int,
            @StringRes positive: Int,
            message: String? = null,
            payload: String? = null,
        ) = ConfirmDialogFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_REQUEST_KEY, requestKey)
                putInt(ARG_TITLE, title)
                putInt(ARG_POSITIVE, positive)
                putString(ARG_MESSAGE, message)
                putString(ARG_PAYLOAD, payload)
            }
        }
    }
}
