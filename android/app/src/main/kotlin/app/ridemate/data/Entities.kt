package app.ridemate.data

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import app.ridemate.core.model.EtaSource
import app.ridemate.core.model.EvidenceRung
import app.ridemate.core.model.LatLng
import app.ridemate.core.model.Place
import app.ridemate.core.model.Provider
import app.ridemate.core.model.QuoteStatus
import app.ridemate.core.model.RideState
import app.ridemate.core.model.VehicleClass

/*
 * Room entities for architecture §5 ("On the phone"). Times named *_utc / *_at are UTC epoch milliseconds; times
 * named *_elapsed* are SystemClock.elapsedRealtime() milliseconds of the boot they were written in.
 *
 * Every child table hangs off `session` (directly or through `leg`) with ON DELETE CASCADE, so deleting a session
 * row removes all of its ride data in one statement (24 h purge, §5).
 */

/** A labelled point, stored as three columns with a prefix (`pickup_lat`, `pickup_lng`, `pickup_label`). */
data class PlaceColumns(
    val lat: Double,
    val lng: Double,
    val label: String,
) {
    fun toPlace(): Place = Place(label, LatLng(lat, lng))

    companion object {
        fun of(place: Place): PlaceColumns = PlaceColumns(place.point.lat, place.point.lng, place.label)
    }
}

/** One comparison: a trip, the apps and vehicle classes chosen, and its legs. */
@Entity(tableName = "session", indices = [Index("status")])
data class SessionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "created_at_utc") val createdAtUtc: Long,
    @Embedded(prefix = "pickup_") val pickup: PlaceColumns,
    @Embedded(prefix = "drop_") val drop: PlaceColumns,
    @ColumnInfo(name = "selected_apps") val selectedApps: List<Provider>,
    @ColumnInfo(name = "vehicle_classes") val vehicleClasses: List<VehicleClass>,
    @ColumnInfo(name = "include_ondc") val includeOndc: Boolean,
    val status: SessionStatus,
    @ColumnInfo(name = "kept_leg_id") val keptLegId: String?,
    /** When the session was closed; the purge deletes it 24 h after this. Null while ACTIVE. */
    @ColumnInfo(name = "closed_at_utc") val closedAtUtc: Long?,
)

/** One ride app (or ONDC / demo provider) inside a session, with its §4.4 statechart state. */
@Entity(
    tableName = "leg",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("session_id")],
)
data class LegEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    val provider: Provider,
    val state: RideState,
    val generation: Int,
    @ColumnInfo(name = "sbn_key") val sbnKey: String?,
    @ColumnInfo(name = "eta_min") val etaMin: Int?,
    @ColumnInfo(name = "eta_source") val etaSource: EtaSource,
    @ColumnInfo(name = "distance_text") val distanceText: String?,
    @ColumnInfo(name = "plate_hash") val plateHash: String?,
    @ColumnInfo(name = "t_assigned_elapsed") val tAssignedElapsed: Long?,
    @ColumnInfo(name = "fee_rule_id") val feeRuleId: String?,
    val kept: Boolean,
    val dismissed: Boolean,
    /** UTC epoch ms of the last event applied to this leg. */
    @ColumnInfo(name = "last_event_at") val lastEventAt: Long?,
)

/** One price row shown for a leg (§4.3). Unreadable rows have no price. */
@Entity(
    tableName = "quote",
    foreignKeys = [
        ForeignKey(
            entity = LegEntity::class,
            parentColumns = ["id"],
            childColumns = ["leg_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("leg_id")],
)
data class QuoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "leg_id") val legId: String,
    @ColumnInfo(name = "vehicle_class") val vehicleClass: VehicleClass,
    @ColumnInfo(name = "label_text") val labelText: String?,
    @ColumnInfo(name = "price_min") val priceMin: Int?,
    @ColumnInfo(name = "price_max") val priceMax: Int?,
    @ColumnInfo(name = "eta_min") val etaMin: Int?,
    @ColumnInfo(name = "seen_at_utc") val seenAtUtc: Long,
    val status: QuoteStatus,
    /** versionCode of the ride app the quote was read from (parser/template versioning). */
    @ColumnInfo(name = "source_version_code") val sourceVersionCode: Long?,
    /** v2: SHA-256 (hex) of the masked frame the quote was read from; null for manual / network quotes. */
    @ColumnInfo(name = "source_frame_hash") val sourceFrameHash: String? = null,
)

/** One rung of the §4.6 evidence ladder recorded for a leg. */
@Entity(
    tableName = "evidence",
    foreignKeys = [
        ForeignKey(
            entity = LegEntity::class,
            parentColumns = ["id"],
            childColumns = ["leg_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("leg_id")],
)
data class EvidenceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "leg_id") val legId: String,
    val rung: EvidenceRung,
    val source: EvidenceSource,
    @ColumnInfo(name = "at_utc") val atUtc: Long,
    /** Name of a core enum describing the evidence detail (never free text). */
    @ColumnInfo(name = "details_enum") val detailsEnum: String?,
)

/**
 * A pending reminder / follow-up / next-app opening / server erase (§4.7, §4.11). Survives process death.
 * [dueElapsedMs] is on the boot identified by [bootId]; [dueWallMs] is the same instant on the wall clock and is
 * used to rebase the row after a reboot.
 */
@Entity(
    tableName = "action_outbox",
    foreignKeys = [
        ForeignKey(
            entity = LegEntity::class,
            parentColumns = ["id"],
            childColumns = ["leg_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("leg_id"), Index("status", "due_elapsed_ms")],
)
data class ActionOutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Null for rows not tied to a leg (ERASE_SERVER). */
    @ColumnInfo(name = "leg_id") val legId: String?,
    val kind: OutboxKind,
    @ColumnInfo(name = "due_elapsed_ms") val dueElapsedMs: Long,
    @ColumnInfo(name = "due_wall_ms") val dueWallMs: Long,
    @ColumnInfo(name = "boot_id") val bootId: String,
    val attempts: Int,
    val status: OutboxStatus,
    /** Opaque JSON owned by the handler of [kind] (no PII). */
    val payload: String?,
    @ColumnInfo(name = "created_at_utc") val createdAtUtc: Long,
)

/** Last processed post per (package, sbn.key) for exact-duplicate drops (§4.5.2). Session-scoped. */
@Entity(
    tableName = "post_fingerprint",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("session_id")],
)
data class PostFingerprintEntity(
    /** `package + "|" + sbn_key`. */
    @PrimaryKey val key: String,
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "sbn_key") val sbnKey: String,
    @ColumnInfo(name = "last_fingerprint") val lastFingerprint: String,
    /** UTC epoch ms. */
    @ColumnInfo(name = "last_seen") val lastSeen: Long,
    @ColumnInfo(name = "session_id") val sessionId: String?,
) {
    companion object {
        fun keyOf(packageName: String, sbnKey: String): String = "$packageName|$sbnKey"
    }
}

/** Rule-miss classification cache by (package, versionCode, normalised text) (§4.5.5). Session-scoped. */
@Entity(
    tableName = "classifier_cache",
    primaryKeys = ["package_name", "version_code", "normalised_text"],
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("session_id")],
)
data class ClassifierCacheEntity(
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "version_code") val versionCode: Long,
    @ColumnInfo(name = "normalised_text") val normalisedText: String,
    /** The classifier label / event enum name (decoded by core `watch`). */
    val event: String,
    @ColumnInfo(name = "session_id") val sessionId: String?,
    @ColumnInfo(name = "created_at_utc") val createdAtUtc: Long,
)

/**
 * v2: the latest serialized core `Guardian` snapshot of a session (raw JSON owned by core `guardian`), so the
 * guardian resumes after process death. Deleted with its session (the session row must exist first).
 */
@Entity(
    tableName = "guardian_snapshot",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class GuardianSnapshotEntity(
    @PrimaryKey @ColumnInfo(name = "session_id") val sessionId: String,
    val json: String,
    @ColumnInfo(name = "updated_at_utc") val updatedAtUtc: Long,
)

/**
 * v2: one notification event logged by the owner Recorder (§4.19a, developer mode only). Text is masked before it
 * is stored; [removalReason] is null for a post and the `NotificationListenerService.REASON_*` code for a removal.
 * Kept on the phone until exported or erased.
 */
@Entity(tableName = "recorder_post", indices = [Index("at_utc")])
data class RecorderPostEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "package") val packageName: String,
    @ColumnInfo(name = "channel_id") val channelId: String?,
    val category: String?,
    @ColumnInfo(name = "is_ongoing") val isOngoing: Boolean,
    @ColumnInfo(name = "notif_id") val notifId: Int,
    val tag: String?,
    @ColumnInfo(name = "title_masked") val titleMasked: String?,
    @ColumnInfo(name = "text_masked") val textMasked: String?,
    val redacted: Boolean,
    @ColumnInfo(name = "removal_reason") val removalReason: Int?,
    @ColumnInfo(name = "at_utc") val atUtc: Long,
)
