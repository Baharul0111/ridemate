package app.ridemate.compare

import androidx.room.withTransaction
import app.ridemate.core.gate.GatedRow
import app.ridemate.core.gate.VehicleClassNames
import app.ridemate.core.model.EtaSource
import app.ridemate.core.model.Provider
import app.ridemate.core.model.QuoteStatus
import app.ridemate.core.model.RideState
import app.ridemate.data.QuoteEntity
import app.ridemate.data.RideMateDb
import app.ridemate.util.Clock
import kotlinx.coroutines.flow.first

/** Where read and typed prices go (Room `quote` table, §5). */
interface QuoteStore {
    /**
     * Replaces the leg's machine-read quotes with [rows] (VERIFIED / UNREADABLE). A price the user typed stays unless
     * the new read VERIFIED the same vehicle class. The leg moves to QUOTED if it was SELECTED or OPENED. False when
     * the leg doesn't exist.
     */
    suspend fun saveRead(legId: String, rows: List<GatedRow>, sourceVersionCode: Long?, frameHash: String?): Boolean

    /** Stores a typed price (USER_ENTERED, "entered by you"), replacing an earlier typed price of the same class. */
    suspend fun saveUserEntered(legId: String, row: GatedRow): Boolean

    /** How many ride-app legs of [sessionId] still wait for their first fare read. */
    suspend fun pendingReads(sessionId: String): Int

    /** The session leg [legId] belongs to; null when the leg doesn't exist. */
    suspend fun sessionIdOf(legId: String): String?
}

class RoomQuoteStore(private val db: RideMateDb, private val clock: Clock) : QuoteStore {

    override suspend fun saveRead(legId: String, rows: List<GatedRow>, sourceVersionCode: Long?, frameHash: String?): Boolean {
        if (db.legDao().get(legId) == null) return false
        val existing = db.quoteDao().observeForLeg(legId).first()
        val verifiedClasses = rows.filter { it.status == QuoteStatus.VERIFIED }.mapTo(HashSet()) { it.vehicleClass }
        val keep = existing.filter { it.status == QuoteStatus.USER_ENTERED && it.vehicleClass !in verifiedClasses }
        return db.withTransaction {
            val leg = db.legDao().get(legId) ?: return@withTransaction false
            db.quoteDao().deleteForLeg(legId)
            db.quoteDao().insertAll(keep.map { it.copy(id = 0) } + rows.map { QuoteMapping.toEntity(legId, it, sourceVersionCode, frameHash) })
            if (leg.state in QUOTABLE) db.legDao().upsert(leg.copy(state = RideState.QUOTED, lastEventAt = clock.wallMs()))
            true
        }
    }

    override suspend fun saveUserEntered(legId: String, row: GatedRow): Boolean {
        require(row.status == QuoteStatus.USER_ENTERED) { "only typed prices" }
        if (db.legDao().get(legId) == null) return false
        val existing = db.quoteDao().observeForLeg(legId).first()
        val others = existing.filterNot { it.status == QuoteStatus.USER_ENTERED && it.vehicleClass == row.vehicleClass }
        return db.withTransaction {
            val leg = db.legDao().get(legId) ?: return@withTransaction false
            db.quoteDao().deleteForLeg(legId)
            db.quoteDao().insertAll(others.map { it.copy(id = 0) } + QuoteMapping.toEntity(legId, row, null, null))
            if (leg.state in QUOTABLE) db.legDao().upsert(leg.copy(state = RideState.QUOTED, lastEventAt = clock.wallMs()))
            true
        }
    }

    override suspend fun pendingReads(sessionId: String): Int =
        db.legDao().forSession(sessionId).count { !it.provider.isNetwork && it.state in QUOTABLE && !it.dismissed }

    override suspend fun sessionIdOf(legId: String): String? = db.legDao().get(legId)?.sessionId

    companion object {
        /** States a fare read moves forward to QUOTED (never backwards from a booking). */
        val QUOTABLE: Set<RideState> = setOf(RideState.SELECTED, RideState.OPENED)
    }
}

/** Converts between the gate's rows and Room's quotes. Model text never gets here (I5): labels are OCR text. */
object QuoteMapping {
    fun toEntity(legId: String, row: GatedRow, sourceVersionCode: Long?, frameHash: String?): QuoteEntity = QuoteEntity(
        legId = legId,
        vehicleClass = row.vehicleClass,
        labelText = if (row.labelFromOcr || row.status == QuoteStatus.NETWORK) row.displayLabel else null,
        priceMin = row.price?.first,
        priceMax = row.price?.last,
        etaMin = row.eta,
        seenAtUtc = row.seenAtMs,
        status = row.status,
        sourceVersionCode = sourceVersionCode,
        sourceFrameHash = frameHash,
    )

    /** The row a stored quote displays as; null for a row that would break I2 (it is then not shown at all). */
    fun toGatedRow(provider: Provider, quote: QuoteEntity): GatedRow? {
        val unreadable = quote.status == QuoteStatus.UNREADABLE
        val price = if (unreadable) {
            null
        } else {
            val min = quote.priceMin ?: return null
            val max = quote.priceMax ?: return null
            min..max
        }
        val eta = if (unreadable) null else quote.etaMin
        val etaSource = when {
            eta == null -> EtaSource.NONE
            quote.status == QuoteStatus.VERIFIED -> EtaSource.VERIFIED
            quote.status == QuoteStatus.USER_ENTERED -> EtaSource.USER
            else -> EtaSource.NETWORK
        }
        return runCatching {
            GatedRow(
                provider = provider,
                vehicleClass = quote.vehicleClass,
                displayLabel = quote.labelText ?: VehicleClassNames.english(quote.vehicleClass),
                labelFromOcr = quote.labelText != null && (quote.status == QuoteStatus.VERIFIED || unreadable),
                price = price,
                status = quote.status,
                eta = eta,
                etaSource = etaSource,
                seenAtMs = quote.seenAtUtc,
                rowId = "q${quote.id}",
            )
        }.getOrNull()
    }
}
