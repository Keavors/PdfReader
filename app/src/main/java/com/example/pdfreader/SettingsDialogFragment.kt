package com.example.pdfreader

import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.widget.CheckBox
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
    private lateinit var nightMode: CheckBox
    private lateinit var keepScreenOn: CheckBox
    private lateinit var lockOrientation: CheckBox

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)
        directions = view.findViewById(R.id.rgDirection)
        modes = view.findViewById(R.id.rgMode)
        nightMode = view.findViewById(R.id.cbNightMode)
        keepScreenOn = view.findViewById(R.id.cbKeepScreenOn)
        lockOrientation = view.findViewById(R.id.cbLockOrientation)

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
        nightMode.isChecked =
            savedInstanceState?.getBoolean(STATE_NIGHT) ?: settings.nightMode
        keepScreenOn.isChecked =
            savedInstanceState?.getBoolean(STATE_KEEP_SCREEN_ON) ?: settings.keepScreenOn
        lockOrientation.isChecked =
            savedInstanceState?.getBoolean(STATE_LOCK_ORIENTATION) ?: settings.lockOrientation

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
        outState.putBoolean(STATE_NIGHT, nightMode.isChecked)
        outState.putBoolean(STATE_KEEP_SCREEN_ON, keepScreenOn.isChecked)
        outState.putBoolean(STATE_LOCK_ORIENTATION, lockOrientation.isChecked)
    }

    private fun apply() {
        ReaderSettings(
            horizontal = directions.checkedRadioButtonId == R.id.rbHorizontal,
            singlePage = modes.checkedRadioButtonId == R.id.rbSingle,
            nightMode = nightMode.isChecked,
            keepScreenOn = keepScreenOn.isChecked,
            lockOrientation = lockOrientation.isChecked,
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
        private const val STATE_NIGHT = "night"
        private const val STATE_KEEP_SCREEN_ON = "keep_screen_on"
        private const val STATE_LOCK_ORIENTATION = "lock_orientation"
    }
}
