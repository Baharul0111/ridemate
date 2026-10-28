package app.ridemate.integration

import android.content.SharedPreferences
import androidx.core.content.edit
import app.ridemate.core.model.Provider
import app.ridemate.notify.GuardianUiState
import app.ridemate.ondc.OndcView
import app.ridemate.session.GuardianStatus

/**
 * The ONDC transaction of the session's network leg, kept on the phone (ids only) so a booked network ride can still be
 * watched and cancelled after the process restarts: the guardian restores the leg from its snapshot, and
 * [OndcResume.resumeTarget] tells OndcClient which `ondc_txn/{txnId}` to listen to again. Erase wipes it.
 */
class OndcTxnMemory(private val prefs: SharedPreferences) {
    fun remember(legId: String, txnId: String) {
        if (prefs.getString(legId, null) == txnId) return
        prefs.edit { clear().putString(legId, txnId) }
    }

    fun txnFor(legId: String): String? = prefs.getString(legId, null)

    fun clear() = prefs.edit { clear() }

    companion object {
        const val PREFS_NAME = "ridemate_ondc_txn"
    }
}

/** Decides when OndcClient re-attaches to a remembered transaction. */
object OndcResume {
    /** The (legId, txnId) to remember from the client's view, once a search has a transaction. */
    fun toRemember(view: OndcView): Pair<String, String>? {
        val legId = view.legId ?: return null
        val txnId = view.txnId ?: return null
        return legId to txnId
    }

    /**
     * After the guardian's restore: the open (or still-guarded) session's network leg that is not settled yet, with
     * its remembered transaction; null when there is nothing to re-attach to.
     */
    fun resumeTarget(status: GuardianStatus, ui: GuardianUiState, memory: OndcTxnMemory): Pair<String, String>? {
        if (!status.restored || status.demo || !(status.sessionOpen || status.needsForegroundService)) return null
        val leg = ui.legs.firstOrNull { it.provider == Provider.ONDC && !it.state.isTerminal } ?: return null
        val txnId = memory.txnFor(leg.legId) ?: return null
        return leg.legId to txnId
    }
}
