package app.ridemate.launch

import app.ridemate.config.ConfigKeys
import app.ridemate.config.ConfigRepository
import app.ridemate.core.config.LaunchMode
import app.ridemate.core.config.LaunchTemplate
import app.ridemate.core.config.LaunchTemplatesConfig
import app.ridemate.core.config.parseLaunchTemplates
import app.ridemate.core.model.Provider
import app.ridemate.trip.TripPlace

/** How one ride app will be opened. */
sealed interface LaunchPlan {
    val provider: Provider

    /**
     * Open [uri] (the route already filled in) inside the provider's app. If the app cannot handle it, fall back to
     * a plain launch, offering [fallbackPasteDrop] on the clipboard when the fallback mode is `launch_paste`.
     */
    data class DeepLink(override val provider: Provider, val uri: String, val fallbackPasteDrop: String?) : LaunchPlan

    /** Open the app's launcher activity; [pasteDrop] (when not null) goes on the clipboard for the "Paste drop" chip. */
    data class Launch(override val provider: Provider, val pasteDrop: String?) : LaunchPlan
}

/** Where `launch_templates` comes from. */
fun interface LaunchTemplateProvider {
    fun templates(): LaunchTemplatesConfig
}

/**
 * `launch_templates` from Remote Config through core's layered resolution (remote → last-good → bundled asset). If no
 * layer parses (e.g. an APK built without the bundled asset), the contracts §2.6 default below is used, so the
 * launcher always has a template.
 */
class RemoteLaunchTemplates(private val config: ConfigRepository) : LaunchTemplateProvider {
    override fun templates(): LaunchTemplatesConfig =
        runCatching { config.resolve(ConfigKeys.LAUNCH_TEMPLATES, ::parseLaunchTemplates)?.value }.getOrNull()
            ?: LaunchPlanner.DEFAULT_TEMPLATES
}

/** Builds a [LaunchPlan] from a template and the confirmed trip (pure; unit-tested). */
object LaunchPlanner {

    /** Contracts §2.6, the same payload as the bundled `assets/config/launch_templates.json`. */
    const val DEFAULT_TEMPLATES_JSON: String =
        """{"schema": "1.0", "apps": {
 "com.ubercab": {"mode": "uri", "uri": "https://m.uber.com/ul/?action=setPickup&pickup[latitude]={plat}&pickup[longitude]={plng}&pickup[nickname]={pname}&dropoff[latitude]={dlat}&dropoff[longitude]={dlng}&dropoff[nickname]={dname}", "fallback": "launch"},
 "com.olacabs.customer": {"mode": "launch_paste"},
 "com.rapido.passenger": {"mode": "launch_paste"}}}"""

    val DEFAULT_TEMPLATES: LaunchTemplatesConfig by lazy { parseLaunchTemplates(DEFAULT_TEMPLATES_JSON) }

    /**
     * - `uri`: the deep link with both points filled in and any `client_id` removed (Uber's universal link is used
     *   without one). Without coordinates for both points (a typed address), the template's fallback is used.
     * - `launch_paste`: plain launch plus the drop address for the clipboard.
     * - `launch`: plain launch.
     * A provider missing from the config is opened with `launch_paste` (offering the drop address is always safe).
     */
    fun plan(provider: Provider, template: LaunchTemplate?, pickup: TripPlace, drop: TripPlace): LaunchPlan {
        val mode = template?.mode ?: LaunchMode.LAUNCH_PASTE
        return when (mode) {
            LaunchMode.URI -> {
                val fallback = template?.fallback ?: LaunchMode.LAUNCH
                val fallbackPaste = drop.label.takeIf { fallback == LaunchMode.LAUNCH_PASTE }
                val pickupPlace = pickup.toPlaceOrNull()
                val dropPlace = drop.toPlaceOrNull()
                val uri = if (pickupPlace != null && dropPlace != null) template?.buildUri(pickupPlace, dropPlace) else null
                if (uri != null) {
                    LaunchPlan.DeepLink(provider, withoutClientId(uri), fallbackPaste)
                } else {
                    LaunchPlan.Launch(provider, fallbackPaste)
                }
            }
            LaunchMode.LAUNCH_PASTE -> LaunchPlan.Launch(provider, drop.label)
            LaunchMode.LAUNCH -> LaunchPlan.Launch(provider, null)
        }
    }

    fun plan(provider: Provider, templates: LaunchTemplatesConfig, pickup: TripPlace, drop: TripPlace): LaunchPlan =
        plan(provider, templates.templateFor(provider.packageName), pickup, drop)

    /** Removes every `client_id` query parameter (any case), keeping the rest of the link byte for byte. */
    fun withoutClientId(uri: String): String {
        val queryStart = uri.indexOf('?')
        if (queryStart < 0) return uri
        val fragmentStart = uri.indexOf('#', queryStart).takeIf { it >= 0 } ?: uri.length
        val query = uri.substring(queryStart + 1, fragmentStart)
        val kept = query.split('&').filter { part ->
            part.isNotEmpty() && !part.substringBefore('=').equals(CLIENT_ID, ignoreCase = true)
        }
        val rebuilt = uri.substring(0, queryStart) + (if (kept.isEmpty()) "" else "?" + kept.joinToString("&"))
        return rebuilt + uri.substring(fragmentStart)
    }

    private const val CLIENT_ID = "client_id"
}
