package app.ridemate.net

import app.ridemate.BuildConfig
import okhttp3.HttpUrl

/**
 * Which hosts may receive RideMate's Firebase ID token, masked screenshots and notification text (§4.11).
 *
 * `api_base_url` arrives through Remote Config, so a mistaken or compromised value must never send any of that to
 * another host. Only `https` and a host *under* one of the allowed suffixes is accepted (`x.modal.run` for
 * "modal.run"; never `modal.run.example.com` or `evilmodal.run`). Anything else makes the client answer
 * [ApiResult.NotConfigured] without a request, which is manual mode: typing prices still works.
 */
class ApiHostPolicy private constructor(suffixes: Collection<String>, private val anyHost: Boolean) {
    private val suffixes: List<String> = suffixes.map { it.trim().trimStart('.').lowercase() }.filter { it.isNotEmpty() }

    /** True when [url] may receive the user's token and data. */
    fun allows(url: HttpUrl): Boolean {
        if (anyHost) return true
        if (!url.isHttps) return false
        val host = url.host.lowercase()
        return suffixes.any { host.endsWith(".$it") }
    }

    companion object {
        /** `https` on a host under one of [suffixes]. */
        fun of(suffixes: Collection<String>): ApiHostPolicy = ApiHostPolicy(suffixes, anyHost = false)

        /** A comma-separated suffix list, as `BuildConfig.API_HOST_SUFFIXES` holds it. */
        fun fromList(csv: String): ApiHostPolicy = of(csv.split(','))

        /** The app's policy: `https` on a host under the suffixes the APK was built with (`API_HOST_SUFFIXES`). */
        val PRODUCTION: ApiHostPolicy by lazy { fromList(BuildConfig.API_HOST_SUFFIXES) }

        /** Only for tests against a local MockWebServer (plain http on localhost). The app never uses it. */
        val ANY_FOR_TESTS: ApiHostPolicy = ApiHostPolicy(emptyList(), anyHost = true)
    }
}
