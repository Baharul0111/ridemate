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
 * storage full warning"). Writers report per [source] (their own name): [onWriteFailed] when a write fell back to
 * memory, [onWriteSucceeded] once that writer holds nothing in memory any more. The UI collects [warning], which
 * stays raised while **any** writer still holds data that only lives in memory, so one writer's success never
 * hides another writer's unsaved reminders.
 */
class StorageHealth {
    private val _warning = MutableStateFlow(StorageWarning.NONE)
    val warning: StateFlow<StorageWarning> = _warning.asStateFlow()

    private val failing = HashMap<String, StorageWarning>()

    @Synchronized
    fun onWriteFailed(error: Throwable, source: String = DEFAULT_SOURCE) {
        failing[source] = if (error.isDiskFull()) StorageWarning.FULL else StorageWarning.WRITE_FAILED
        publish()
    }

    @Synchronized
    fun onWriteSucceeded(source: String = DEFAULT_SOURCE) {
        if (failing.remove(source) != null) publish()
    }

    private fun publish() {
        _warning.value = when {
            failing.isEmpty() -> StorageWarning.NONE
            StorageWarning.FULL in failing.values -> StorageWarning.FULL
            else -> StorageWarning.WRITE_FAILED
        }
    }

    companion object {
        const val DEFAULT_SOURCE = "default"

        /** [app.ridemate.outbox.OutboxRepository]. */
        const val SOURCE_OUTBOX = "outbox"

        /** [GuardianSnapshotStore]. */
        const val SOURCE_GUARDIAN_SNAPSHOTS = "guardian_snapshots"

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
