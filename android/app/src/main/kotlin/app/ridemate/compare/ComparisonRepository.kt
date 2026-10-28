package app.ridemate.compare

import app.ridemate.core.gate.GatedRow
import app.ridemate.core.model.Provider
import app.ridemate.data.LegEntity
import app.ridemate.data.RideMateDb
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** One comparison as the list shows it: its legs (apps) and every stored row as a gate row. */
data class ComparisonData(
    val sessionId: String,
    val legs: List<LegEntity>,
    val rows: List<GatedRow>,
) {
    /** The session's ride apps in leg order (ONDC excluded: it is booked inside RideMate). */
    val rideApps: List<Provider> get() = legs.map { it.provider }.filter { !it.isNetwork }.distinct()

    fun legFor(provider: Provider): LegEntity? = legs.firstOrNull { it.provider == provider }

    companion object {
        fun empty(sessionId: String) = ComparisonData(sessionId, emptyList(), emptyList())
    }
}

/** Room → [ComparisonData], live. Rows that would break I2 are dropped by [QuoteMapping.toGatedRow]. */
class ComparisonRepository(private val db: RideMateDb) {
    fun observe(sessionId: String): Flow<ComparisonData> =
        combine(db.legDao().observeForSession(sessionId), db.quoteDao().observeForSession(sessionId)) { legs, quotes ->
            val byId = legs.associateBy { it.id }
            ComparisonData(
                sessionId = sessionId,
                legs = legs,
                rows = quotes.mapNotNull { q -> byId[q.legId]?.let { leg -> QuoteMapping.toGatedRow(leg.provider, q) } },
            )
        }

    /** The newest active session's id (null when none). */
    fun observeActiveSessionId(): Flow<String?> =
        db.sessionDao().observeActive().map { it.firstOrNull()?.id }.distinctUntilChanged()
}
