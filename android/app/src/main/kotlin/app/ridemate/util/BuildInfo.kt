package app.ridemate.util

import app.ridemate.BuildConfig

/** The installed build, injected so tests can pretend to be another version. */
data class BuildInfo(
    val versionCode: Int,
    val versionName: String,
    val debug: Boolean,
) {
    companion object {
        val CURRENT: BuildInfo = BuildInfo(BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME, BuildConfig.DEBUG)
    }
}
