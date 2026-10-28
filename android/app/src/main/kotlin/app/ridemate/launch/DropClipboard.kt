package app.ridemate.launch

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import app.ridemate.core.model.Provider

/** Puts the drop address on the clipboard so the user can paste it into Ola or Rapido themselves. */
fun interface ClipboardWriter {
    /** Returns false when the clipboard could not be written. */
    fun copyDrop(text: String): Boolean
}

/**
 * The real clipboard. The clip is labelled [LABEL] and explicitly marked **not** sensitive
 * (`ClipDescription.EXTRA_IS_SENSITIVE = false`): an address is meant to be seen in Android 13+'s copy preview.
 */
class AndroidDropClipboard(private val context: Context) : ClipboardWriter {
    override fun copyDrop(text: String): Boolean {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
        return try {
            clipboard.setPrimaryClip(clipOf(text))
            true
        } catch (e: SecurityException) {
            false
        } catch (e: IllegalStateException) {
            false
        }
    }

    companion object {
        /** The clip's label (contract with the bubble's "Paste drop" chip). */
        const val LABEL: String = "RideMate drop"

        /** `ClipDescription.EXTRA_IS_SENSITIVE` (its constant is API 33; the extra is read on every version that uses it). */
        const val EXTRA_IS_SENSITIVE: String = "android.content.extra.IS_SENSITIVE"

        fun clipOf(text: String): ClipData = ClipData.newPlainText(LABEL, text).apply {
            description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, false) }
        }
    }
}

/**
 * What the bubble's "Paste drop" chip shows after RideMate opened Ola or Rapido: the drop address that is on the
 * clipboard. Tapping the chip copies it again ([AppLauncher.copyDropAgain]); the user pastes it in the app.
 */
data class PasteDropChip(val provider: Provider, val dropText: String, val copiedAtWallMs: Long)
