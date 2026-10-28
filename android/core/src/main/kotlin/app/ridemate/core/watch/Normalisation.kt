package app.ridemate.core.watch

import java.security.MessageDigest

/**
 * PII spans (phone numbers, OTP/PIN, plates, names) to hide in a piece of notification text. The app wires the real
 * detector from `core.mask`; RideWatch only needs the spans.
 */
fun interface TextMasker {
    fun spans(text: String): List<IntRange>

    companion object {
        val NONE = TextMasker { emptyList() }
    }
}

/** §4.5.9 normalisation and the post fingerprints used for exact-repeat dedupe. */
object Normaliser {
    const val MASK_TOKEN = "<m>"
    const val DIGIT = '#'
    private const val SENTINEL = ''

    /** Lower-cased, whitespace-collapsed, digits replaced by `#`, masked spans replaced by `<m>`. */
    fun normalise(text: String, masked: List<IntRange> = emptyList()): String {
        val withSentinels = replaceSpans(text, masked, SENTINEL.toString())
        val out = StringBuilder(withSentinels.length)
        var pendingSpace = false
        var i = 0
        while (i < withSentinels.length) {
            val cp = withSentinels.codePointAt(i)
            i += Character.charCount(cp)
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                pendingSpace = out.isNotEmpty()
                continue
            }
            if (pendingSpace) {
                out.append(' ')
                pendingSpace = false
            }
            when {
                cp == SENTINEL.code -> out.append(MASK_TOKEN)
                Character.isDigit(cp) -> out.append(DIGIT)
                else -> out.appendCodePoint(Character.toLowerCase(cp))
            }
        }
        return out.toString()
    }

    /** The masked text sent to the classifier: spans replaced by `<m>`, everything else untouched. */
    fun maskForUpload(text: String, masked: List<IntRange>): String = replaceSpans(text, masked, MASK_TOKEN)

    private fun replaceSpans(text: String, spans: List<IntRange>, replacement: String): String {
        val merged = mergeSpans(spans, text.length)
        if (merged.isEmpty()) return text
        val out = StringBuilder(text.length)
        var cursor = 0
        for (span in merged) {
            out.append(text, cursor, span.first)
            out.append(replacement)
            cursor = span.last + 1
        }
        out.append(text, cursor, text.length)
        return out.toString()
    }

    private fun mergeSpans(spans: List<IntRange>, length: Int): List<IntRange> {
        val clipped = spans
            .map { it.first.coerceAtLeast(0)..it.last.coerceAtMost(length - 1) }
            .filter { !it.isEmpty() }
            .sortedBy { it.first }
        val merged = mutableListOf<IntRange>()
        for (span in clipped) {
            val last = merged.lastOrNull()
            if (last != null && span.first <= last.last + 1) {
                merged[merged.lastIndex] = last.first..maxOf(last.last, span.last)
            } else {
                merged += span
            }
        }
        return merged
    }
}

/**
 * Redacted posts carry no text, so they are told apart by structure: (package, channel id, category, isOngoing,
 * notification id/tag, post time bucket of 10 s). A second redacted assignment is never mistaken for the first.
 */
data class StructuralFingerprint(
    val packageName: String,
    val channelId: String?,
    val category: String?,
    val isOngoing: Boolean,
    val id: Int,
    val tag: String?,
    val postTimeBucket: Long,
) {
    fun render(): String = "s|$channelId|$category|$isOngoing|$id|$tag|$postTimeBucket"

    companion object {
        const val BUCKET_MS = 10_000L

        fun of(post: PostSnapshot) = StructuralFingerprint(
            packageName = post.packageName,
            channelId = post.channelId,
            category = post.category,
            isOngoing = post.isOngoing,
            id = post.id,
            tag = post.tag,
            postTimeBucket = Math.floorDiv(post.postTimeMs, BUCKET_MS),
        )
    }
}

/**
 * §4.5.2 dedupe key: (package, sbn.key, matched template id or MISS, redaction flag, normalised text). For a
 * template hit the content also carries the plate hash the template read, because masking turns every plate into
 * `<m>` and a new driver with the same wording is not a repeat.
 */
data class PostFingerprint(
    val packageName: String,
    val sbnKey: String,
    val templateId: String,
    val redacted: Boolean,
    val content: String,
) {
    companion object {
        const val MISS = "MISS"
    }
}

/** Plates are hashed in memory and never kept raw (same driver on two apps is detected by hash). */
object PlateHasher {
    private const val MIN_LENGTH = 4

    fun normalise(raw: String): String {
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val cp = raw.codePointAt(i)
            i += Character.charCount(cp)
            when {
                Character.isDigit(cp) -> out.append(Character.forDigit(Character.digit(cp, 10), 10))
                Character.isLetter(cp) -> out.appendCodePoint(Character.toUpperCase(cp))
            }
        }
        return out.toString()
    }

    /** SHA-256 hex of the normalised plate, or null when too little of a plate was read to identify a driver. */
    fun hash(raw: String): String? {
        val plate = normalise(raw)
        if (plate.length < MIN_LENGTH) return null
        val digest = MessageDigest.getInstance("SHA-256").digest(plate.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
