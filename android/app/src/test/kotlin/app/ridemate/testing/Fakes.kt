package app.ridemate.testing

import android.app.Activity
import app.ridemate.auth.AuthBackend
import app.ridemate.auth.AuthBackendException
import app.ridemate.auth.AuthFailure
import app.ridemate.auth.AuthUser
import app.ridemate.auth.BackendToken
import app.ridemate.auth.GoogleCredentialResult
import app.ridemate.auth.GoogleCredentialSource
import app.ridemate.auth.Nonce
import app.ridemate.config.BackendInfo
import app.ridemate.config.FetchStatus
import app.ridemate.config.RemoteConfigBackend
import app.ridemate.crash.CrashBackend
import app.ridemate.util.BootIdProvider
import app.ridemate.util.Clock
import kotlinx.coroutines.CompletableDeferred
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/*
 * Shared fakes for platform tests (no Firebase, no network). Other areas may reuse them.
 */

/** A manual clock: both bases move only through [advance]. */
class FakeClock(var elapsed: Long = 1_000_000L, var wall: Long = 1_793_000_000_000L) : Clock {
    override fun elapsedRealtimeMs(): Long = elapsed
    override fun wallMs(): Long = wall

    fun advance(ms: Long) {
        elapsed += ms
        wall += ms
    }
}

class FakeBootIds(var id: String = "boot-1") : BootIdProvider {
    override fun currentBootId(): String = id
}

/** Records every Crashlytics call. */
class FakeCrashBackend : CrashBackend {
    val events = CopyOnWriteArrayList<String>()
    val recorded = CopyOnWriteArrayList<Throwable>()
    val keys = linkedMapOf<String, String>()
    var collectionEnabled: Boolean? = null
    var failAll = false

    private fun guard() {
        if (failAll) throw IllegalStateException("crashlytics down")
    }

    override fun setCollectionEnabled(enabled: Boolean) {
        guard(); collectionEnabled = enabled; events += "collection:$enabled"
    }

    override fun recordException(throwable: Throwable) {
        guard(); recorded += throwable; events += "record"
    }

    override fun setCustomKey(key: String, value: String) {
        guard(); keys[key] = value; events += "key:$key=$value"
    }

    override fun setUserId(id: String) {
        guard(); events += "userId:$id"
    }

    override fun deleteUnsentReports() {
        guard(); events += "deleteUnsent"
    }

    override fun log(message: String) {
        guard(); events += "log:$message"
    }
}

/** An in-memory Firebase Auth. [log] records calls in order (erase-order tests share it). */
class FakeAuthBackend(
    var user: AuthUser? = null,
    val log: MutableList<String> = CopyOnWriteArrayList(),
) : AuthBackend {
    private val listeners = CopyOnWriteArrayList<(AuthUser?) -> Unit>()
    var tokenCounter = 0
    var tokenLifetimeMs = 60 * 60 * 1000L
    var now: () -> Long = { 1_793_000_000_000L }
    var nextFailure: AuthFailure? = null
    var deleteFailure: AuthFailure? = null
    var googleUser: (String) -> AuthUser = { token ->
        AuthUser("g-uid", isAnonymous = false, isGoogle = true, email = "rider@example.com", displayName = "Rider", photoUrl = null)
            .also { require(token.isNotEmpty()) }
    }

    override fun currentUser(): AuthUser? = user

    override fun addStateListener(listener: (AuthUser?) -> Unit): AutoCloseable {
        listeners += listener
        listener(user)
        return AutoCloseable { listeners -= listener }
    }

    private fun emit() = listeners.forEach { it(user) }

    private fun failIfAsked() {
        nextFailure?.let { nextFailure = null; throw AuthBackendException(it) }
    }

    override suspend fun signInWithGoogleIdToken(idToken: String): AuthUser {
        log += "signInGoogle"
        failIfAsked()
        user = googleUser(idToken)
        emit()
        return user!!
    }

    override suspend fun signInAnonymously(): AuthUser {
        log += "signInAnonymous"
        failIfAsked()
        user = AuthUser("anon-uid", isAnonymous = true, isGoogle = false, email = null, displayName = null, photoUrl = null)
        emit()
        return user!!
    }

    override suspend fun getIdToken(forceRefresh: Boolean): BackendToken {
        log += "getIdToken:$forceRefresh"
        failIfAsked()
        if (user == null) throw AuthBackendException(AuthFailure.USER_GONE)
        tokenCounter++
        return BackendToken("token-$tokenCounter", now() + tokenLifetimeMs)
    }

    override fun signOut() {
        log += "signOut"
        user = null
        emit()
    }

    override suspend fun deleteCurrentUser() {
        log += "deleteUser"
        deleteFailure?.let { throw AuthBackendException(it) }
        if (user == null) throw AuthBackendException(AuthFailure.USER_GONE)
        user = null
        emit()
    }

    companion object {
        fun googleUser(uid: String = "g-uid", email: String = "rider@example.com") =
            AuthUser(uid, isAnonymous = false, isGoogle = true, email = email, displayName = "Rider", photoUrl = "https://example.com/p.png")

        fun anonymousUser(uid: String = "anon-uid") =
            AuthUser(uid, isAnonymous = true, isGoogle = false, email = null, displayName = null, photoUrl = null)
    }
}

/** Returns a canned result; by default an unsigned JWT whose `nonce` claim echoes the requested nonce. */
class FakeGoogleCredentialSource(
    var result: (hashedNonce: String) -> GoogleCredentialResult = { GoogleCredentialResult.Success(jwtWithNonce(it)) },
) : GoogleCredentialSource {
    val requestedNonces = mutableListOf<String>()

    override suspend fun requestIdToken(activity: Activity, hashedNonce: String): GoogleCredentialResult {
        requestedNonces += hashedNonce
        return result(hashedNonce)
    }

    companion object {
        fun jwtWithNonce(nonce: String?): String {
            val enc = Base64.getUrlEncoder().withoutPadding()
            val header = enc.encodeToString("""{"alg":"RS256","typ":"JWT"}""".toByteArray())
            val claims = if (nonce == null) """{"sub":"1"}""" else """{"sub":"1","nonce":"$nonce"}"""
            return "$header.${enc.encodeToString(claims.toByteArray())}.sig"
        }

        init {
            // Keep the helper honest with the production parser.
            check(Nonce.claimOf(jwtWithNonce("n")) == "n")
        }
    }
}

/** An in-memory Remote Config. Set [remote] then call [publishUpdate] to simulate a real-time update. */
class FakeRemoteConfigBackend : RemoteConfigBackend {
    /** Fetched (not yet active) server values. */
    val fetched = linkedMapOf<String, String>()

    /** Active server values. */
    val active = linkedMapOf<String, String>()
    var configuredTimeoutS: Long? = null
    var configuredMinIntervalS: Long? = null
    var fetchCount = 0
    var activateCount = 0
    var fetchFails = false

    /** When set, fetchAndActivate suspends until completed (proves nobody awaits it). */
    var fetchGate: CompletableDeferred<Unit>? = null
    private val listeners = CopyOnWriteArrayList<(Set<String>) -> Unit>()
    var status = FetchStatus.NEVER
    var lastFetch: Long? = null

    override suspend fun configure(fetchTimeoutSeconds: Long, minimumFetchIntervalSeconds: Long) {
        configuredTimeoutS = fetchTimeoutSeconds
        configuredMinIntervalS = minimumFetchIntervalSeconds
    }

    override suspend fun fetchAndActivate(): Boolean {
        fetchCount++
        fetchGate?.await()
        if (fetchFails) {
            status = FetchStatus.FAILURE
            throw IllegalStateException("offline")
        }
        status = FetchStatus.SUCCESS
        lastFetch = 1_793_000_000_000L
        return activate()
    }

    override suspend fun activate(): Boolean {
        activateCount++
        val changed = active != fetched
        active.clear()
        active.putAll(fetched)
        return changed
    }

    override fun remoteValue(key: String): String? = active[key]

    override fun remoteKeys(): Set<String> = active.keys.toSet()

    override fun addUpdateListener(onUpdate: (Set<String>) -> Unit): AutoCloseable {
        listeners += onUpdate
        return AutoCloseable { listeners -= onUpdate }
    }

    fun listenerCount(): Int = listeners.size

    /** Simulates the real-time stream announcing [keys] (values must already be in [fetched]). */
    fun publishUpdate(keys: Set<String>) = listeners.forEach { it(keys) }

    override fun info(): BackendInfo = BackendInfo(lastFetch, status)
}
