package app.ridemate.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Sign-in nonce: a fresh random value per sign-in. Google receives its SHA-256 (hex) and embeds it in the ID
 * token's `nonce` claim; the gateway checks the claim before handing the token to Firebase, so a replayed or
 * substituted token is rejected on the phone.
 */
object Nonce {
    private val random = SecureRandom()

    fun newRaw(): String {
        val bytes = ByteArray(32).also { random.nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun sha256Hex(raw: String): String =
        MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** The `nonce` claim of a JWT (payload only; the signature is verified by Firebase). Null if absent/malformed. */
    fun claimOf(idToken: String): String? {
        val parts = idToken.split('.')
        if (parts.size < 2) return null
        return runCatching {
            val payload = String(Base64.getUrlDecoder().decode(parts[1].trimEnd('=')), Charsets.UTF_8)
            val obj = Json.parseToJsonElement(payload) as JsonObject
            (obj["nonce"] as? JsonPrimitive)?.contentOrNull
        }.getOrNull()
    }
}
