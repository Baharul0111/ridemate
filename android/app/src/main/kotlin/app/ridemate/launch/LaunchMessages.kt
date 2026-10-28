package app.ridemate.launch

import android.content.Context
import app.ridemate.R

/** User-facing wording for launches (strings_launch.xml). Every failure says what to do next. */
object LaunchMessages {

    /** The chip's visible label ("Paste drop"). */
    fun pasteChipLabel(context: Context): String = context.getString(R.string.launch_paste_chip)

    /** The chip's spoken description, with the address and where to paste it. */
    fun pasteChipDescription(context: Context, chip: PasteDropChip): String =
        context.getString(R.string.launch_paste_chip_description, chip.dropText, chip.provider.displayName)

    /** Shown after the chip copies the address again. */
    fun pasteChipCopied(context: Context, chip: PasteDropChip): String =
        context.getString(R.string.launch_paste_copied, chip.provider.displayName)

    /** One line for a launch result, or null when the app simply opened. */
    fun resultText(context: Context, result: LaunchResult): String? {
        val app = result.provider.displayName
        return when (result.outcome) {
            LaunchOutcome.OPENED -> if (result.dropCopied) context.getString(R.string.launch_opened_paste_hint, app) else null
            LaunchOutcome.NOTIFICATION_POSTED -> context.getString(R.string.launch_tap_notification, app)
            LaunchOutcome.BLOCKED -> context.getString(R.string.launch_blocked, app)
            LaunchOutcome.NOT_INSTALLED -> context.getString(R.string.launch_not_installed, app)
            LaunchOutcome.NOT_A_RIDE_APP -> null
            LaunchOutcome.FAILED -> context.getString(R.string.launch_open_failed, app)
        }
    }
}
