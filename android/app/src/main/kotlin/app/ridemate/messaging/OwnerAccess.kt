package app.ridemate.messaging

import app.ridemate.auth.AuthState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest
import java.util.Locale

/**
 * Who the owner is: a Google-signed-in user whose lower-cased email's SHA-256 is in Remote Config
 * `owner_emails_sha256` (contracts §2.7), the list that enables developer mode. Anonymous and signed-out users are
 * never the owner; the email itself never leaves the phone (only its hash is compared, locally).
 */
class OwnerAccess(
    private val authState: () -> AuthState,
    private val ownerEmailHashes: () -> Set<String>,
) {
    /** True when the signed-in user is the owner (developer mode enabled for them). */
    fun isOwner(): Boolean {
        val google = authState() as? AuthState.Google ?: return false
        val email = google.email?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val hashes = ownerEmailHashes()
        return hashes.isNotEmpty() && emailHash(email) in hashes
    }

    companion object {
        private val SHA256_HEX = Regex("^[0-9a-f]{64}$")
        private val JSON = Json { ignoreUnknownKeys = true }

        /** SHA-256 hex of the lower-cased, trimmed email (the format of `owner_emails_sha256`). */
        fun emailHash(email: String): String =
            MessageDigest.getInstance("SHA-256").digest(email.trim().lowercase(Locale.ROOT).toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        /** Parses the bare JSON list of `owner_emails_sha256`; anything malformed yields no owners (fail closed). */
        fun parseHashes(raw: String?): Set<String> {
            val array = runCatching { JSON.parseToJsonElement(raw.orEmpty().trim()) as? JsonArray }.getOrNull() ?: return emptySet()
            return array.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.trim()?.lowercase(Locale.ROOT) }
                .filter { SHA256_HEX.matches(it) }
                .toSet()
        }
    }
}
