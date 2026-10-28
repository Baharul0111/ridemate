package app.ridemate.launch

import app.ridemate.core.model.Provider
import app.ridemate.core.model.RideState
import app.ridemate.data.LegDao

/**
 * Records that RideMate opened a leg's app (§4.4 `SELECTED → OPENED`). The guardian binds its own implementation
 * at integration (AppGraph.legOpenedSink); until then [RoomLegOpenedSink] moves the stored leg row.
 */
fun interface LegOpenedSink {
    suspend fun legOpened(legId: String, provider: Provider, atWallMs: Long)
}

/**
 * Moves leg [legId] from SELECTED to OPENED in Room. Forward-only and idempotent: a leg that is already OPENED or
 * further along (or belongs to another provider, or does not exist) is left as it is.
 */
class RoomLegOpenedSink(private val legDao: LegDao) : LegOpenedSink {
    override suspend fun legOpened(legId: String, provider: Provider, atWallMs: Long) {
        val leg = legDao.get(legId) ?: return
        if (leg.provider != provider || leg.state != RideState.SELECTED) return
        legDao.upsert(leg.copy(state = RideState.OPENED, lastEventAt = atWallMs))
    }
}
