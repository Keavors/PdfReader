package com.example.pdfreader

import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.widget.RadioGroup
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Выбор режима просмотра.
 *
 * Именно фрагмент, а не голый AlertDialog: тот при повороте экрана исчезает
 * вместе с несохранённым выбором, а активити вдобавок роняет в лог утечку окна.
 */
class SettingsDialogFragment : DialogFragment() {

    private lateinit var directions: RadioGroup
    private lateinit var modes: RadioGroup

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)
        directions = view.findViewById(R.id.rgDirection)
        modes = view.findViewById(R.id.rgMode)

        // После поворота показываем то, что человек успел выбрать, а не то,
        // что лежит в настройках.
        val settings = ReaderSettings.load(requireContext())
        directions.check(
            savedInstanceState?.checkedId(STATE_DIRECTION)
                ?: if (settings.horizontal) R.id.rbHorizontal else R.id.rbVertical
        )
        modes.check(
            savedInstanceState?.checkedId(STATE_MODE)
                ?: if (settings.singlePage) R.id.rbSingle else R.id.rbContinuous
        )

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_title)
            .setView(view)
            .setPositiveButton(R.string.action_apply) { _, _ -> apply() }
            .setNegativeButton(R.string.action_cancel, null)
            .create()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_DIRECTION, directions.checkedRadioButtonId)
        outState.putInt(STATE_MODE, modes.checkedRadioButtonId)
    }

    private fun apply() {
        ReaderSettings(
            horizontal = directions.checkedRadioButtonId == R.id.rbHorizontal,
            singlePage = modes.checkedRadioButtonId == R.id.rbSingle,
        ).save(requireContext())
        setFragmentResult(RESULT_KEY, Bundle())
    }

    private fun Bundle.checkedId(key: String): Int? =
        getInt(key, View.NO_ID).takeIf { it != View.NO_ID && it != 0 }

    companion object {
        const val TAG = "settings"

        /** Настройки изменились — документ пора перечитать. */
        const val RESULT_KEY = "reader_settings_changed"

        private const val STATE_DIRECTION = "direction"
        private const val STATE_MODE = "mode"
    }
}
