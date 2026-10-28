package app.ridemate.core.watch

/**
 * The fields RideWatch reads from a `StatusBarNotification`. Title and text live in memory only: they are masked
 * before normalisation and never persisted or logged.
 */
data class PostSnapshot(
    val packageName: String,
    val sbnKey: String,
    val id: Int,
    val tag: String?,
    val channelId: String?,
    val category: String?,
    val isOngoing: Boolean,
    val title: String?,
    val text: String?,
    val postTimeMs: Long,
    /** The ride app's installed versionCode (PackageInfo.longVersionCode); null when unknown. */
    val versionCode: Long?,
    /** Set by the app when the platform marked the content as redacted (Android 15 sensitive-content redaction). */
    val platformRedacted: Boolean = false,
) {
    /** Redacted or empty: there is nothing to match a text template against. */
    val isRedacted: Boolean
        get() = platformRedacted || (title.isNullOrBlank() && text.isNullOrBlank())

    /** The text templates are matched against (contracts §2.1). */
    val matchText: String
        get() = (title ?: "") + "\n" + (text ?: "")
}

/**
 * I7: a post whose package is checked before anything else is read. The app implements [read] over the
 * `StatusBarNotification`; RideWatch calls it only for allowlisted packages.
 */
interface PostSource {
    val packageName: String
    fun read(): PostSnapshot

    companion object {
        fun of(snapshot: PostSnapshot): PostSource = object : PostSource {
            override val packageName: String = snapshot.packageName
            override fun read(): PostSnapshot = snapshot
        }
    }
}

/** A post leaving the shade, with the listener's removal reason. */
data class PostRemoval(val packageName: String, val sbnKey: String, val reason: RemovalReason)

/** Removal counterpart of [PostSource]: the key and reason are read only for allowlisted packages. */
interface RemovalSource {
    val packageName: String
    fun read(): PostRemoval

    companion object {
        fun of(removal: PostRemoval): RemovalSource = object : RemovalSource {
            override val packageName: String = removal.packageName
            override fun read(): PostRemoval = removal
        }
    }
}

/** `NotificationListenerService.REASON_*` codes (stable platform constants). */
@JvmInline
value class RemovalReason(val code: Int) {
    val isAppCancel: Boolean get() = code == REASON_APP_CANCEL.code

    companion object {
        val REASON_CLICK = RemovalReason(1)
        val REASON_CANCEL = RemovalReason(2)
        val REASON_CANCEL_ALL = RemovalReason(3)
        val REASON_ERROR = RemovalReason(4)
        val REASON_PACKAGE_CHANGED = RemovalReason(5)
        val REASON_USER_STOPPED = RemovalReason(6)
        val REASON_PACKAGE_BANNED = RemovalReason(7)
        val REASON_APP_CANCEL = RemovalReason(8)
        val REASON_APP_CANCEL_ALL = RemovalReason(9)
        val REASON_LISTENER_CANCEL = RemovalReason(10)
        val REASON_TIMEOUT = RemovalReason(19)
        val UNKNOWN = RemovalReason(0)
    }
}
