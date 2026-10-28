package app.ridemate.crash

import com.google.firebase.crashlytics.FirebaseCrashlytics

/** [CrashBackend] over Crashlytics. Resolved on each call so a missing Firebase app only disables reporting. */
class FirebaseCrashBackend : CrashBackend {
    private val crashlytics: FirebaseCrashlytics get() = FirebaseCrashlytics.getInstance()

    override fun setCollectionEnabled(enabled: Boolean) = crashlytics.setCrashlyticsCollectionEnabled(enabled)
    override fun recordException(throwable: Throwable) = crashlytics.recordException(throwable)
    override fun setCustomKey(key: String, value: String) = crashlytics.setCustomKey(key, value)
    override fun setUserId(id: String) = crashlytics.setUserId(id)
    override fun deleteUnsentReports() = crashlytics.deleteUnsentReports()
    override fun log(message: String) = crashlytics.log(message)
}
