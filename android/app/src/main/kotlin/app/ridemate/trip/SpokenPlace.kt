package app.ridemate.trip

import java.util.Locale

/**
 * Pulls the place out of a spoken sentence before it goes to the Geocoder (goal 1): "take me to the airport please" →
 * "airport", "मुझे गुवाहाटी एयरपोर्ट जाना है" → "गुवाहाटी एयरपोर्ट". Deterministic word lists in English, Hindi
 * (Devanagari and Latin), Bengali and Assamese; no model and no network. Only the words around the place are removed,
 * so the result is always a contiguous part of the transcript; a bare place name comes back unchanged. When nothing
 * would be left, the transcript is kept.
 */
object SpokenPlace {

    /** The place phrase of [transcript] (trimmed); the transcript itself when it holds nothing else. */
    fun phrase(transcript: String): String {
        val words = transcript.trim().split(WHITESPACE).map { it.trim(*PUNCTUATION) }.filter { it.isNotEmpty() }
        if (words.isEmpty()) return transcript.trim()
        var start = 0
        var end = words.size
        var changed = true
        while (changed && start < end) {
            changed = false
            LEADING.firstOrNull { matchesAt(words, start, end, it) }?.let { start += it.size; changed = true }
            if (start >= end) break
            TRAILING.firstOrNull { matchesEndingAt(words, start, end, it) }?.let { end -= it.size; changed = true }
        }
        if (start >= end) return transcript.trim()
        return words.subList(start, end).joinToString(" ")
    }

    /** True when [transcript] is more than a place name (a sentence the Geocoder would likely not find). */
    fun isSentence(transcript: String): Boolean {
        val trimmed = transcript.trim().split(WHITESPACE).map { it.trim(*PUNCTUATION) }.filter { it.isNotEmpty() }.joinToString(" ")
        return phrase(transcript) != trimmed
    }

    private fun matchesAt(words: List<String>, start: Int, end: Int, pattern: List<String>): Boolean =
        start + pattern.size <= end && pattern.indices.all { same(words[start + it], pattern[it]) }

    private fun matchesEndingAt(words: List<String>, start: Int, end: Int, pattern: List<String>): Boolean =
        end - pattern.size >= start && pattern.indices.all { same(words[end - pattern.size + it], pattern[it]) }

    private fun same(word: String, pattern: String): Boolean = word.lowercase(Locale.ROOT) == pattern

    private val WHITESPACE = Regex("\\s+")
    private val PUNCTUATION = charArrayOf('.', ',', '!', '?', '।', '॥', '"', '\'', ';', ':')

    private fun phrases(vararg items: String): List<List<String>> =
        items.map { it.lowercase(Locale.ROOT).split(' ') }.sortedByDescending { it.size }

    /** Words before the place, longest first: requests, pronouns, "to the". */
    private val LEADING: List<List<String>> = phrases(
        // English
        "please", "can you", "could you", "i want to go to", "i wanna go to", "i need to go to", "i have to go to",
        "i want to go", "take me to", "drop me at", "drop me to", "drop me off at", "get me to", "book a ride to",
        "book a cab to", "book an auto to", "book a bike to", "a ride to", "a cab to", "ride to", "cab to", "go to",
        "going to", "to", "the", "at", "for",
        // Hindi (Devanagari)
        "मुझे", "मुझको", "हमें", "हमको", "कृपया", "प्लीज़", "प्लीज",
        // Hindi (Latin)
        "mujhe", "mujhko", "hume", "humein", "hamein", "kripya",
        // Bengali
        "আমি", "আমাকে", "আমায়", "আমরা", "দয়া করে", "প্লিজ",
        // Assamese
        "মই", "মোক", "আমি", "অনুগ্ৰহ কৰি",
    )

    /** Words after the place, longest first: "…जाना है", "…please", postpositions. */
    private val TRAILING: List<List<String>> = phrases(
        // English
        "please", "now", "right now", "asap",
        // Hindi (Devanagari)
        "जाना है", "जाना हैं", "जाना चाहता हूँ", "जाना चाहती हूँ", "जाना चाहता हूं", "जाना चाहती हूं", "जाना",
        "ले चलो", "ले चलिए", "ले चलें", "चलो", "चलिए", "चलना है", "पहुँचा दो", "पहुंचा दो", "पहुँचाओ", "छोड़ दो",
        "छोड़ दीजिए", "को", "तक", "पर", "में", "है",
        // Hindi (Latin)
        "jana hai", "jaana hai", "jana", "jaana", "le chalo", "chalo", "chaliye", "pahucha do", "chhod do", "tak", "ko",
        // Bengali
        "যেতে চাই", "যেতে হবে", "যাব", "যাবো", "নিয়ে চলো", "নিয়ে যাও", "নিয়ে চলুন", "চলো", "চলুন", "পর্যন্ত",
        // Assamese
        "যাব বিচাৰো", "যাব লাগে", "যাম", "লৈ যোৱা", "লৈ ব'লা", "ব'লা", "লৈ", "পৰ্যন্ত",
    )
}
