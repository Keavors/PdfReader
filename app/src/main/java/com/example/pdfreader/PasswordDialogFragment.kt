package com.example.pdfreader

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.view.View
import android.widget.EditText
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Запрос пароля к зашифрованному документу. */
class PasswordDialogFragment : DialogFragment() {

    private lateinit var input: EditText

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = layoutInflater.inflate(R.layout.dialog_password, null)
        input = view.findViewById(R.id.etPassword)
        if (arguments?.getBoolean(ARG_RETRY) == true) {
            view.findViewById<View>(R.id.tvWrongPassword).visibility = View.VISIBLE
        }

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.password_title)
            .setView(view)
            .setPositiveButton(R.string.action_open) { _, _ ->
                setFragmentResult(
                    RESULT_KEY,
                    Bundle().apply { putString(EXTRA_PASSWORD, input.text.toString()) },
                )
            }
            .setNegativeButton(R.string.action_cancel) { _, _ -> giveUp() }
            .create()
    }

    /** Отказ вводить пароль: результат без пароля означает "закрываем документ". */
    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        giveUp()
    }

    private fun giveUp() = setFragmentResult(RESULT_KEY, Bundle())

    companion object {
        const val TAG = "password"
        const val RESULT_KEY = "document_password"
        const val EXTRA_PASSWORD = "password"
        private const val ARG_RETRY = "retry"

        /** @param retry предыдущий пароль не подошёл — предупредить об этом. */
        fun newInstance(retry: Boolean) = PasswordDialogFragment().apply {
            arguments = Bundle().apply { putBoolean(ARG_RETRY, retry) }
        }
    }
}
