package app.ridemate.data

import android.database.SQLException
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteFullException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the "storage" banner shows. */
enum class StorageWarning {
    /** Writes are landing in Room. */
    NONE,

    /** The disk is full: pending reminders are kept in memory and survive only while RideMate runs. */
    FULL,

    /** Another database write error: same in-memory fallback, different wording. */
    WRITE_FAILED,
}

/**
 * Tracks whether Room writes are failing (ActionOutbox requirement: "Room write failure → in-memory outbox +
 * storage full warning"). Writers call [onWriteFailed] / [onWriteSucceeded]; the UI collects [warning].
 */
class StorageHealth {
    private val _warning = MutableStateFlow(StorageWarning.NONE)
    val warning: StateFlow<StorageWarning> = _warning.asStateFlow()

    fun onWriteFailed(error: Throwable) {
        _warning.value = if (error.isDiskFull()) StorageWarning.FULL else StorageWarning.WRITE_FAILED
    }

    fun onWriteSucceeded() {
        _warning.value = StorageWarning.NONE
    }

    companion object {
        /**
         * True for errors that mean "the database cannot take writes right now" (so the caller should fall back to
         * memory), false for programming errors and constraint violations, which must not be hidden.
         */
        fun isStorageFailure(error: Throwable): Boolean =
            error is SQLException && error !is SQLiteConstraintException

        private fun Throwable.isDiskFull(): Boolean {
            var t: Throwable? = this
            while (t != null) {
                if (t is SQLiteFullException) return true
                if (t.message?.contains("SQLITE_FULL") == true || t.message?.contains("No space left") == true) return true
                t = t.cause
            }
            return false
        }
    }
}
