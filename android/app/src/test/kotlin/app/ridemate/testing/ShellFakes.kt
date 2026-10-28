package app.ridemate.testing

import app.ridemate.auth.DefaultAuthGateway
import app.ridemate.dev.OwnerPushTokens
import app.ridemate.net.ApiClient
import app.ridemate.net.ApiResult
import app.ridemate.net.ConfigCheckResponse
import app.ridemate.onboarding.AppPermission
import app.ridemate.onboarding.DeviceProfile
import app.ridemate.onboarding.PermissionChecker
import app.ridemate.onboarding.RideWatchSelfTest
import app.ridemate.onboarding.SelfTestNotifier
import app.ridemate.settings.AppLocaleBackend

/*
 * Fakes for the app-shell tests (onboarding, home, settings, developer mode).
 */

/** Permissions the test grants by hand. */
class FakePermissions(vararg granted: AppPermission) : PermissionChecker {
    val granted: MutableSet<AppPermission> = granted.toMutableSet()

    override fun isGranted(permission: AppPermission): Boolean = permission in granted

    fun grant(vararg permissions: AppPermission) {
        granted += permissions
    }
}

class FakeNotifier(var allowed: Boolean = true) : SelfTestNotifier {
    val posted = mutableListOf<String>()
    val cancelled = mutableListOf<String>()

    override fun post(token: String): Boolean {
        if (!allowed) return false
        posted += token
        return true
    }

    override fun cancel(token: String) {
        cancelled += token
    }
}

/** A RideWatch that "sees" a posted self-test after [seeAfterPolls] polls (never when null). */
class FakeRideWatch(private val notifier: FakeNotifier, var seeAfterPolls: Int? = 0) : RideWatchSelfTest {
    var polls = 0

    override fun hasSeen(token: String): Boolean {
        polls++
        val after = seeAfterPolls ?: return false
        return token in notifier.posted && polls > after
    }
}

/** An [ApiClient] whose config-check and owner-token answers the test sets; everything else as [FakeApiClient]. */
class ShellFakeApi(
    var configCheckResult: ApiResult<ConfigCheckResponse> = ApiResult.NotConfigured,
    var ownerTokenResult: ApiResult<Unit> = ApiResult.Ok(Unit),
) : ApiClient by FakeApiClient() {
    var configChecks = 0
    val registeredTokens = mutableListOf<String>()

    override suspend fun configCheck(): ApiResult<ConfigCheckResponse> {
        configChecks++
        return configCheckResult
    }

    override suspend fun registerOwnerFcmToken(token: String): ApiResult<Unit> {
        registeredTokens += token
        return ownerTokenResult
    }
}

class FakePushTokens(var token: String? = "fcm-token-1") : OwnerPushTokens {
    var deletes = 0

    override suspend fun token(): String? = token

    override suspend fun delete(): Boolean {
        deletes++
        return true
    }
}

class FakeLocaleBackend(override val recreatesActivity: Boolean = true, var tag: String? = null) : AppLocaleBackend {
    val sets = mutableListOf<String?>()

    override fun get(): String? = tag

    override fun set(tag: String?) {
        sets += tag
        this.tag = tag
    }
}

object ShellTestData {
    val SIDELOADED_PIXEL = DeviceProfile(sdkInt = 35, release = "15", manufacturer = "Google", sideloaded = true)
    val XIAOMI = DeviceProfile(sdkInt = 34, release = "14", manufacturer = "Xiaomi", sideloaded = true)

    fun authGateway(backend: FakeAuthBackend = FakeAuthBackend(), google: FakeGoogleCredentialSource = FakeGoogleCredentialSource()) =
        DefaultAuthGateway(backend, google, FakeClock())
}
