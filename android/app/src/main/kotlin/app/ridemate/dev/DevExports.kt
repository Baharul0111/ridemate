package app.ridemate.dev

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import app.ridemate.R
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.tasks.await
import java.io.File

/**
 * Serves developer exports to the share sheet (app-private `cache/dev_exports`, read-only, per-share grant).
 * Its own subclass so it never clashes with another area's FileProvider in the merged manifest.
 */
class DevExportFileProvider : FileProvider(R.xml.dev_export_paths)

/**
 * Developer exports leave the phone only when the owner picks a target in the share sheet; nothing is uploaded
 * automatically (architecture §4.19).
 */
class DevExports(context: Context) {
    private val app = context.applicationContext
    val dir: File get() = File(app.cacheDir, DIR)

    fun file(name: String): File = File(dir.apply { mkdirs() }, name)

    fun writeText(name: String, text: String): File = file(name).apply { writeText(text, Charsets.UTF_8) }

    /** A chooser that shares [file] read-only with the target the owner picks. */
    fun shareIntent(file: File, mimeType: String, chooserTitle: String): Intent {
        val uri = FileProvider.getUriForFile(app, authority(app), file)
        val send = Intent(Intent.ACTION_SEND)
            .setType(mimeType)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, file.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(file.name, uri)
        return Intent.createChooser(send, chooserTitle)
    }

    fun clear() {
        dir.deleteRecursively()
    }

    companion object {
        const val DIR = "dev_exports"

        fun authority(context: Context): String = "${context.packageName}.devexports"
    }
}

/** The owner phone's FCM token, for owner alert pushes (`POST /v1/owner/fcm-token`). */
interface OwnerPushTokens {
    /** The current token, or null when Firebase Messaging is unavailable. */
    suspend fun token(): String?

    /** Deletes the token so the server's copy stops working (turning owner alerts off). */
    suspend fun delete(): Boolean
}

class FirebaseOwnerPushTokens : OwnerPushTokens {
    override suspend fun token(): String? = runCatching { FirebaseMessaging.getInstance().token.await() }.getOrNull()

    override suspend fun delete(): Boolean = runCatching { FirebaseMessaging.getInstance().deleteToken().await() }.isSuccess
}
