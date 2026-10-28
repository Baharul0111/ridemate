package app.ridemate.testing

import app.ridemate.core.model.EtaSource
import app.ridemate.core.model.EvidenceRung
import app.ridemate.core.model.Provider
import app.ridemate.core.model.QuoteStatus
import app.ridemate.core.model.RideState
import app.ridemate.core.model.VehicleClass
import app.ridemate.data.ClassifierCacheEntity
import app.ridemate.data.EvidenceEntity
import app.ridemate.data.EvidenceSource
import app.ridemate.data.LegEntity
import app.ridemate.data.PlaceColumns
import app.ridemate.data.PostFingerprintEntity
import app.ridemate.data.QuoteEntity
import app.ridemate.data.SessionEntity
import app.ridemate.data.SessionStatus

/** Row builders for Room tests. */
object TestData {
    fun session(
        id: String = "s1",
        createdAtUtc: Long = 1_793_000_000_000L,
        status: SessionStatus = SessionStatus.ACTIVE,
        closedAtUtc: Long? = null,
    ) = SessionEntity(
        id = id,
        createdAtUtc = createdAtUtc,
        pickup = PlaceColumns(26.32, 91.0, "Barpeta Road"),
        drop = PlaceColumns(26.11, 91.59, "LGBI Airport, Guwahati"),
        selectedApps = listOf(Provider.UBER, Provider.RAPIDO),
        vehicleClasses = listOf(VehicleClass.AUTO, VehicleClass.CAB_ECONOMY),
        includeOndc = true,
        status = status,
        keptLegId = null,
        closedAtUtc = closedAtUtc,
    )

    fun leg(id: String = "l1", sessionId: String = "s1", provider: Provider = Provider.RAPIDO, state: RideState = RideState.SEARCHING) =
        LegEntity(
            id = id,
            sessionId = sessionId,
            provider = provider,
            state = state,
            generation = 2,
            sbnKey = "0|com.rapido.passenger|7|null|10001",
            etaMin = 4,
            etaSource = EtaSource.TEMPLATE,
            distanceText = "1.2 km",
            plateHash = "ab12",
            tAssignedElapsed = 5_000L,
            feeRuleId = "rapido.default",
            kept = false,
            dismissed = false,
            lastEventAt = 1_793_000_000_500L,
        )

    fun quote(legId: String = "l1", seenAt: Long = 1_793_000_000_100L, frameHash: String? = null) = QuoteEntity(
        legId = legId,
        vehicleClass = VehicleClass.AUTO,
        labelText = "Auto",
        priceMin = 110,
        priceMax = 130,
        etaMin = 3,
        seenAtUtc = seenAt,
        status = QuoteStatus.VERIFIED,
        sourceVersionCode = 12345L,
        sourceFrameHash = frameHash,
    )

    fun evidence(legId: String = "l1") = EvidenceEntity(
        legId = legId,
        rung = EvidenceRung.TEMPLATE,
        source = EvidenceSource.TEMPLATE,
        atUtc = 1_793_000_000_200L,
        detailsEnum = "CANCELLED_TEMPLATE",
    )

    fun fingerprint(sessionId: String? = "s1", lastSeen: Long = 1_793_000_000_300L, sbnKey: String = "k1") = PostFingerprintEntity(
        key = PostFingerprintEntity.keyOf("com.rapido.passenger", sbnKey),
        packageName = "com.rapido.passenger",
        sbnKey = sbnKey,
        lastFingerprint = "fp-1",
        lastSeen = lastSeen,
        sessionId = sessionId,
    )

    fun classification(sessionId: String? = "s1", createdAt: Long = 1_793_000_000_400L, text: String = "captain is on the way") =
        ClassifierCacheEntity(
            packageName = "com.rapido.passenger",
            versionCode = 12345L,
            normalisedText = text,
            event = "assigned",
            sessionId = sessionId,
            createdAtUtc = createdAt,
        )
}
