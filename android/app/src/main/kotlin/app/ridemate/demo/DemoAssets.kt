package app.ridemate.demo

import android.content.res.AssetManager
import app.ridemate.core.gate.ChannelARead
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.FileNotFoundException
import java.security.MessageDigest

/**
 * The bundled demo files. Screens are returned as the exact asset bytes: `/v1/read-screen` recognises demo images by
 * the SHA-256 of the uploaded bytes (contracts §1.3/§6), so a demo frame is never re-encoded or masked before upload.
 */
interface DemoAssetSource {
    /** The raw text of `assets/demo/script.json`. */
    fun scriptText(): String

    /** The exact bytes of a bundled screen (`demo/pepper/fare_options.png`). */
    fun bytes(path: String): ByteArray

    /**
     * A recorded Muse Spark Open read of the image with this SHA-256 (contracts §6 `eval/recorded/channel_a` format),
     * bundled under `assets/demo/recorded/channel_a/<sha256>.json`, or null when none is bundled.
     */
    fun recordedRead(imageSha256: String): ChannelARead?
}

class AndroidDemoAssets(private val assets: AssetManager) : DemoAssetSource {
    override fun scriptText(): String = assets.open(DemoScript.ASSET_PATH).bufferedReader(Charsets.UTF_8).use { it.readText() }

    override fun bytes(path: String): ByteArray = assets.open(path).use { it.readBytes() }

    override fun recordedRead(imageSha256: String): ChannelARead? {
        if (!SHA256_HEX.matches(imageSha256)) return null
        val text = try {
            assets.open("$RECORDED_DIR/$imageSha256.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: FileNotFoundException) {
            return null
        } catch (_: java.io.IOException) {
            return null
        }
        return RecordedReads.parse(text, imageSha256)
    }

    companion object {
        const val RECORDED_DIR = "demo/recorded/channel_a"
        private val SHA256_HEX = Regex("^[0-9a-f]{64}$")
    }
}

/** Recorded Channel A reads (contracts §6): `{"image_sha256", "model", "recorded_at", "read": <ChannelARead>, …}`. */
object RecordedReads {
    private val JSON = Json { ignoreUnknownKeys = true }

    /** The recorded read, or null when the file is malformed or recorded for another image. */
    fun parse(text: String, expectedSha256: String): ChannelARead? {
        val obj = runCatching { JSON.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        val sha = (obj["image_sha256"] as? kotlinx.serialization.json.JsonPrimitive)?.content
        if (sha != null && sha != expectedSha256) return null
        val read = obj["read"] as? JsonObject ?: return null
        return ChannelARead.fromJsonOrNull(read.toString())
    }
}

object DemoImages {
    /** Lower-case hex SHA-256 of [bytes] (the demo allowlist key). */
    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Width and height from a PNG's IHDR chunk, or null when [bytes] is not a PNG. */
    fun pngSize(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < 24) return null
        for (i in PNG_SIGNATURE.indices) if (bytes[i] != PNG_SIGNATURE[i]) return null
        fun int(at: Int) = ((bytes[at].toInt() and 0xff) shl 24) or ((bytes[at + 1].toInt() and 0xff) shl 16) or
            ((bytes[at + 2].toInt() and 0xff) shl 8) or (bytes[at + 3].toInt() and 0xff)
        val width = int(16)
        val height = int(20)
        return if (width > 0 && height > 0) width to height else null
    }

    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
}
