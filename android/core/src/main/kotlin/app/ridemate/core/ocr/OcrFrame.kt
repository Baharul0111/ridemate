package app.ridemate.core.ocr

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One recognised text element (ML Kit "element" granularity) and its pixel box. [lineId] groups tokens the OCR
 * engine put on one line and [blockId] its text block; -1 when the engine gave none.
 */
@Serializable
data class OcrToken(
    val text: String,
    val box: Box,
    @SerialName("line") val lineId: Int = -1,
    @SerialName("block") val blockId: Int = -1,
)

/**
 * The OCR of one captured frame, independent of ML Kit so the parser, mask and gate run on the JVM. A token's
 * id everywhere in `core` is its index in [tokens]. JSON shape: contracts §6 (`eval/.../ocr/<sha256>.json`).
 */
@Serializable
data class OcrFrame(
    val width: Int,
    val height: Int,
    val tokens: List<OcrToken>,
    @SerialName("image_sha256") val imageSha256: String? = null,
    val engine: String? = null,
    @SerialName("recorded_at") val recordedAt: String? = null,
) {
    init {
        require(width > 0 && height > 0) { "frame must have a positive size" }
    }

    fun toJson(): String = OcrJson.encodeToString(serializer(), this)

    /** A token's box on the frame's 0–1000 grid. */
    fun gridOf(box: Box): GridBox = box.toGrid(width, height)

    companion object {
        /** Parses the contracts §6 format; throws on malformed input. */
        fun fromJson(raw: String): OcrFrame = OcrJson.decodeFromString(serializer(), raw)
    }
}

private val OcrJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}
