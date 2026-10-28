package app.ridemate.dev

import app.ridemate.core.mask.maskText
import app.ridemate.core.model.Provider
import app.ridemate.crash.CrashReporter
import app.ridemate.crash.CrashSite
import app.ridemate.data.RecorderPostDao
import app.ridemate.data.RecorderPostEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant

/**
 * One notification post (or removal) from Uber, Ola or Rapido as RideWatch saw it. [title] / [text] / [tag] may be
 * raw: the Recorder masks them before anything is stored (core `maskText`), and the raw values are never kept.
 *
 * @property removalReason `NotificationListenerService.REASON_*` for a removal, null for a post.
 */
data class RecordedPost(
    val packageName: String,
    val channelId: String?,
    val category: String?,
    val isOngoing: Boolean,
    val notificationId: Int,
    val tag: String?,
    val title: String?,
    val text: String?,
    val redacted: Boolean,
    val removalReason: Int?,
    val atUtcMs: Long,
    /** The posting app's installed versionCode, when known. */
    val versionCode: Long? = null,
)

/**
 * The owner Recorder (architecture §4.19 a). A3's RideWatchService calls [record] for every allowlisted post and
 * removal while [isRecording] is true. Recording never acts on the post and never uploads anything.
 */
interface RecorderSink {
    /** Developer mode is on and the Recorder switch is on. */
    val isRecording: Boolean

    /** Masks and stores [post] on the phone; packages outside the allowlist are dropped unread (I7). Never blocks. */
    fun record(post: RecordedPost)
}

/** Masking applied to every Recorder text field (stored and exported). */
object RecorderMasking {
    fun mask(value: String?): String? = value?.let { maskText(it) }

    fun toEntity(post: RecordedPost): RecorderPostEntity = RecorderPostEntity(
        packageName = post.packageName,
        channelId = post.channelId,
        category = post.category,
        isOngoing = post.isOngoing,
        notifId = post.notificationId,
        tag = mask(post.tag),
        titleMasked = mask(post.title),
        textMasked = mask(post.text),
        redacted = post.redacted,
        removalReason = post.removalReason,
        atUtc = post.atUtcMs,
        versionCode = post.versionCode,
    )
}

class RoomRecorderSink(
    private val dao: () -> RecorderPostDao,
    private val gate: DeveloperModeGate,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val crash: CrashReporter?,
) : RecorderSink {
    override val isRecording: Boolean get() = gate.isRecording()

    override fun record(post: RecordedPost) {
        if (!isRecording) return
        if (post.packageName !in Provider.RIDE_APP_PACKAGES) return
        val row = RecorderMasking.toEntity(post)
        scope.launch(io) {
            try {
                dao().insert(row)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                crash?.recordNonFatal(e, CrashSite.WATCH)
            }
        }
    }
}

/** The Recorder's JSON export (shared by hand through the share sheet; never uploaded). */
@Serializable
data class RecorderExportFile(
    val schema: String = SCHEMA,
    val kind: String = KIND,
    @SerialName("exported_at_utc") val exportedAtUtc: String,
    @SerialName("app_version_code") val appVersionCode: Int,
    @SerialName("app_version_name") val appVersionName: String,
    val posts: List<RecorderExportPost>,
) {
    companion object {
        const val SCHEMA = "1.0"

        /** The kind Template Doctor reads (tools/template_doctor/doctorlib/samples.py). */
        const val KIND = "recorder_posts"
    }
}

/**
 * One post in the export, in Template Doctor's sample shape (`tools/template_doctor/doctorlib/samples.py`):
 * `package`, `version_code`, `channel_id`, `category`, `is_ongoing`, `id`, `tag`, `title`, `text` (both masked),
 * `redacted`, `removal_reason`, `posted_at`, and the owner's optional `label` (null until the owner labels it).
 */
@Serializable
data class RecorderExportPost(
    @SerialName("package") val packageName: String,
    @SerialName("version_code") val versionCode: Long?,
    @SerialName("channel_id") val channelId: String?,
    val category: String?,
    @SerialName("is_ongoing") val isOngoing: Boolean,
    val id: Int,
    val tag: String?,
    /** Masked on the phone (and again on export). */
    val title: String?,
    /** Masked on the phone (and again on export). */
    val text: String?,
    val redacted: Boolean,
    @SerialName("removal_reason") val removalReason: Int?,
    @SerialName("removal_reason_name") val removalReasonName: String?,
    @SerialName("posted_at") val postedAt: String,
    @SerialName("at_ms") val atMs: Long,
    /** Owner's ground truth (a template event or OTHER); exported empty. */
    val label: String? = null,
)

object RecorderExport {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = true
    }

    /** `NotificationListenerService.REASON_*` names (stable platform constants). */
    private val REASONS: Map<Int, String> = mapOf(
        1 to "REASON_CLICK", 2 to "REASON_CANCEL", 3 to "REASON_CANCEL_ALL", 4 to "REASON_ERROR",
        5 to "REASON_PACKAGE_CHANGED", 6 to "REASON_USER_STOPPED", 7 to "REASON_PACKAGE_BANNED",
        8 to "REASON_APP_CANCEL", 9 to "REASON_APP_CANCEL_ALL", 10 to "REASON_LISTENER_CANCEL",
        11 to "REASON_LISTENER_CANCEL_ALL", 12 to "REASON_GROUP_SUMMARY_CANCELED", 13 to "REASON_GROUP_OPTIMIZATION",
        14 to "REASON_PACKAGE_SUSPENDED", 15 to "REASON_PROFILE_TURNED_OFF", 16 to "REASON_UNAUTOBUNDLED",
        17 to "REASON_CHANNEL_BANNED", 18 to "REASON_SNOOZED", 19 to "REASON_TIMEOUT", 20 to "REASON_CHANNEL_REMOVED",
        21 to "REASON_CLEAR_DATA", 22 to "REASON_ASSISTANT_CANCEL", 23 to "REASON_LOCKDOWN",
    )

    fun reasonName(reason: Int?): String? = reason?.let { REASONS[it] ?: "REASON_$it" }

    /**
     * The export document. Rows are masked again here (defence in depth, e.g. rows written by an older build) and
     * rows from packages outside the allowlist are left out.
     */
    fun build(rows: List<RecorderPostEntity>, exportedAtUtcMs: Long, versionCode: Int, versionName: String): RecorderExportFile =
        RecorderExportFile(
            exportedAtUtc = Instant.ofEpochMilli(exportedAtUtcMs).toString(),
            appVersionCode = versionCode,
            appVersionName = versionName,
            posts = rows.filter { it.packageName in Provider.RIDE_APP_PACKAGES }.map { row ->
                RecorderExportPost(
                    packageName = row.packageName,
                    versionCode = row.versionCode,
                    channelId = row.channelId,
                    category = row.category,
                    isOngoing = row.isOngoing,
                    id = row.notifId,
                    tag = RecorderMasking.mask(row.tag),
                    title = RecorderMasking.mask(row.titleMasked),
                    text = RecorderMasking.mask(row.textMasked),
                    redacted = row.redacted,
                    removalReason = row.removalReason,
                    removalReasonName = reasonName(row.removalReason),
                    postedAt = Instant.ofEpochMilli(row.atUtc).toString(),
                    atMs = row.atUtc,
                )
            },
        )

    fun toJson(file: RecorderExportFile): String = json.encodeToString(RecorderExportFile.serializer(), file)
}
