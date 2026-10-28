package app.ridemate.watch

import android.annotation.SuppressLint
import android.app.Notification
import android.content.pm.PackageManager
import android.content.res.Resources
import android.os.Bundle
import android.service.notification.StatusBarNotification
import androidx.core.content.pm.PackageInfoCompat
import app.ridemate.core.watch.PostRemoval
import app.ridemate.core.watch.PostSnapshot
import app.ridemate.core.watch.PostSource
import app.ridemate.core.watch.RemovalReason
import app.ridemate.core.watch.RemovalSource
import app.ridemate.util.Clock
import java.util.concurrent.ConcurrentHashMap

/**
 * One listener callback as RideWatch sees it. Only [packageName] is read eagerly; everything else is read lazily, so a
 * post from any other app is dropped without its contents ever being touched (I7).
 */
interface ListenerPost : PostSource {
    /** The channel id. Read only for RideMate's own posts (the onboarding self-test), never for other apps. */
    fun ownChannelId(): String?

    /** The tag. Read only for RideMate's own self-test post. */
    fun ownTag(): String?
}

/** A [ListenerPost] over a real [StatusBarNotification]. */
class SbnListenerPost(private val sbn: StatusBarNotification, private val reader: SbnPostReader) : ListenerPost {
    override val packageName: String get() = sbn.packageName
    override fun read(): PostSnapshot = reader.read(sbn)
    override fun ownChannelId(): String? = sbn.notification?.channelId
    override fun ownTag(): String? = sbn.tag
}

/** A removal over a real [StatusBarNotification]: the key and reason are read only for allowlisted packages. */
class SbnRemoval(private val sbn: StatusBarNotification, private val reasonCode: Int) : RemovalSource {
    override val packageName: String get() = sbn.packageName
    override fun read(): PostRemoval = PostRemoval(sbn.packageName, sbn.key, RemovalReason(reasonCode))
}

/** The installed versionCode of a ride app (templates and parsers are versioned per app version, §4.5.7). */
fun interface VersionCodeLookup {
    fun versionCode(packageName: String): Long?
}

/** [VersionCodeLookup] over the PackageManager (the three ride apps are visible through `<queries>`). Cached briefly. */
class PackageVersionCodes(
    private val packageManager: PackageManager,
    private val clock: Clock,
    private val ttlMs: Long = 60_000L,
) : VersionCodeLookup {
    private data class Entry(val versionCode: Long?, val atElapsedMs: Long)

    private val cache = ConcurrentHashMap<String, Entry>()

    override fun versionCode(packageName: String): Long? {
        val now = clock.elapsedRealtimeMs()
        cache[packageName]?.takeIf { now - it.atElapsedMs < ttlMs }?.let { return it.versionCode }
        val code = try {
            PackageInfoCompat.getLongVersionCode(packageManager.getPackageInfo(packageName, 0))
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
        cache[packageName] = Entry(code, now)
        return code
    }
}

/**
 * Turns a ride app's [StatusBarNotification] into core's [PostSnapshot]: title, text (the expanded big text when the
 * app provides one), channel, category, ongoing flag, id/tag/key, post time and the posting app's versionCode. The
 * text stays in memory: it is masked by RideWatch before normalisation and is never stored or logged.
 */
class SbnPostReader(
    private val versionCodes: VersionCodeLookup,
    private val redaction: RedactionDetector = RedactionDetector(),
) {

    fun read(sbn: StatusBarNotification): PostSnapshot {
        val notification: Notification? = sbn.notification
        val extras: Bundle = notification?.extras ?: Bundle.EMPTY
        val title = extras.text(Notification.EXTRA_TITLE) ?: extras.text(Notification.EXTRA_TITLE_BIG)
        val text = extras.text(Notification.EXTRA_BIG_TEXT)
            ?: extras.text(Notification.EXTRA_TEXT)
            ?: extras.lines()
        return PostSnapshot(
            packageName = sbn.packageName,
            sbnKey = sbn.key,
            id = sbn.id,
            tag = sbn.tag,
            channelId = notification?.channelId,
            category = notification?.category,
            isOngoing = sbn.isOngoing,
            title = title,
            text = text,
            postTimeMs = sbn.postTime,
            versionCode = versionCodes.versionCode(sbn.packageName),
            platformRedacted = redaction.isRedacted(title, text),
        )
    }

    private fun Bundle.text(key: String): String? = getCharSequence(key)?.toString()?.takeIf { it.isNotBlank() }

    /** InboxStyle posts carry their content as lines. */
    private fun Bundle.lines(): String? =
        getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.mapNotNull { it?.toString()?.takeIf(String::isNotBlank) }
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString("\n")
}

/**
 * Android 15 hides one-time codes from notification listeners by replacing the content with a placeholder
 * ("Sensitive notification content hidden", in the phone's language). Such a post has nothing a text template could
 * match, so it is treated like an empty post (§4.5.4): structural templates or a MAYBE prompt, never silence, and a
 * structural fingerprint, so a second redacted assignment is never mistaken for the first (§4.5.9).
 *
 * [extraPlaceholders] adds the platform's own wording in the phone's language ([systemPlaceholders]); the English
 * forms are always recognised.
 */
class RedactionDetector(extraPlaceholders: Collection<String> = emptyList()) {
    private val placeholders: Set<String> = (ENGLISH + extraPlaceholders.map(::normalise)).filter { it.isNotEmpty() }.toSet()

    fun isRedacted(title: String?, text: String?): Boolean {
        if (title.isNullOrBlank() && text.isNullOrBlank()) return true
        val body = text?.let(::normalise) ?: return false
        return body in placeholders
    }

    companion object {
        private val ENGLISH = listOf(
            "sensitive notification content hidden",
            "notification content hidden",
            "sensitive content hidden",
            "contents hidden",
            "content hidden",
        )

        /** The framework strings Android puts in place of redacted content (absent before Android 15). */
        private val SYSTEM_STRING_NAMES = listOf("redacted_notification_message", "redacted_otp_notification_message")

        private fun normalise(text: String): String = text.trim().trimEnd('.', '…', '।').trim().lowercase()

        /**
         * The redaction placeholders of this phone's framework, in its current language. A resource lookup by name
         * (no reflection); missing names are skipped, so older Android versions simply add nothing.
         */
        @SuppressLint("DiscouragedApi")
        fun systemPlaceholders(resources: Resources = Resources.getSystem()): List<String> = SYSTEM_STRING_NAMES.mapNotNull { name ->
            try {
                resources.getIdentifier(name, "string", "android").takeIf { it != 0 }?.let(resources::getString)
            } catch (_: Resources.NotFoundException) {
                null
            }
        }
    }
}
