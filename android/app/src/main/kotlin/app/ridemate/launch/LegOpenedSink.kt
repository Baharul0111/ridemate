package app.ridemate.launch

import app.ridemate.core.model.Provider

/**
 * Records that RideMate opened a leg's app (§4.4 `SELECTED → OPENED`). Bound in AppGraph to the guardian, which owns
 * the leg's state and persists its row.
 */
fun interface LegOpenedSink {
    suspend fun legOpened(legId: String, provider: Provider, atWallMs: Long)
}
