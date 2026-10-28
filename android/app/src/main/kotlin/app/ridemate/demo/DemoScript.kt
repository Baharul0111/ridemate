package app.ridemate.demo

import app.ridemate.core.config.NotificationEvent
import app.ridemate.core.config.SupportedSchemas
import app.ridemate.core.model.LatLng
import app.ridemate.core.model.Provider
import app.ridemate.core.model.VehicleClass
import app.ridemate.core.watch.PostRemoval
import app.ridemate.core.watch.PostSnapshot
import app.ridemate.core.watch.RemovalReason
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * `assets/demo/script.json` (format: eval/demo/README.md, schema eval/schemas/demo_script.schema.json): the fictional
 * providers, their bundled screens and the scripted notification timeline DemoSource plays. Parsed with [parse], which
 * validates every cross-reference so a broken script fails loudly at load instead of stalling a demo half-way.
 */
@Serializable
data class DemoScript(
    val schema: String,
    val trip: DemoTrip,
    val providers: Map<String, DemoProviderSpec>,
    val tracks: List<DemoTrack>,
) {
    /** The providers in the order DemoSource walks them (Kestrel, Pepper, Tamarind). */
    val providerOrder: List<Provider> get() = Provider.DEMO_APPS.filter { it.packageName in providers }

    fun provider(provider: Provider): DemoProviderSpec = providers.getValue(provider.packageName)

    fun track(id: String): DemoTrack? = tracks.firstOrNull { it.id == id }

    companion object {
        const val ASSET_PATH = "demo/script.json"

        private val JSON = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

        private val REMOVAL_REASONS: Map<String, RemovalReason> = mapOf(
            "REASON_CLICK" to RemovalReason.REASON_CLICK,
            "REASON_CANCEL" to RemovalReason.REASON_CANCEL,
            "REASON_CANCEL_ALL" to RemovalReason.REASON_CANCEL_ALL,
            "REASON_ERROR" to RemovalReason.REASON_ERROR,
            "REASON_PACKAGE_CHANGED" to RemovalReason.REASON_PACKAGE_CHANGED,
            "REASON_USER_STOPPED" to RemovalReason.REASON_USER_STOPPED,
            "REASON_PACKAGE_BANNED" to RemovalReason.REASON_PACKAGE_BANNED,
            "REASON_APP_CANCEL" to RemovalReason.REASON_APP_CANCEL,
            "REASON_APP_CANCEL_ALL" to RemovalReason.REASON_APP_CANCEL_ALL,
            "REASON_LISTENER_CANCEL" to RemovalReason.REASON_LISTENER_CANCEL,
            "REASON_TIMEOUT" to RemovalReason.REASON_TIMEOUT,
        )

        /** `NotificationListenerService.REASON_*` by name, or null for an unknown name. */
        fun removalReasonOf(name: String?): RemovalReason? = name?.let { REMOVAL_REASONS[it] }

        /** Parses and validates a script; throws [IllegalArgumentException] on anything a demo run could trip over. */
        fun parse(raw: String): DemoScript {
            val script = try {
                JSON.decodeFromString(serializer(), raw)
            } catch (e: Exception) {
                throw IllegalArgumentException("demo script is malformed", e)
            }
            script.validate()
            return script
        }
    }

    private fun validate() {
        require(schema.substringBefore('.').toIntOrNull() == SupportedSchemas.MAJOR) { "unsupported demo script schema $schema" }
        require(providers.isNotEmpty()) { "no demo providers" }
        providers.forEach { (pkg, spec) ->
            val provider = Provider.fromPackage(pkg)
            require(provider != null && provider.isDemo) { "$pkg is not a demo provider" }
            require(spec.wireId == provider.wireId) { "$pkg: wire_id ${spec.wireId} != ${provider.wireId}" }
            require(DemoScreens.FARE_OPTIONS in spec.screens) { "$pkg has no fare_options screen" }
            require(DemoScreens.CANCEL_FLOW in spec.screens) { "$pkg has no cancel_flow screen" }
            require(spec.screens.values.all { it.startsWith("demo/") && it.endsWith(".png") }) { "$pkg: screens must be demo/*.png" }
            spec.cancelFlowFrom?.let { require(it in spec.screens) { "$pkg: cancel_flow_from $it is not a screen" } }
        }
        require(tracks.isNotEmpty()) { "no tracks" }
        require(tracks.map { it.id }.distinct().size == tracks.size) { "duplicate track ids" }
        for (track in tracks) {
            require(track.events.isNotEmpty()) { "track ${track.id} has no events" }
            require(track.events.map { it.id }.distinct().size == track.events.size) { "track ${track.id}: duplicate event ids" }
            for (event in track.events) {
                val where = "${track.id}/${event.id}"
                val spec = providers[event.packageName] ?: throw IllegalArgumentException("$where: unknown package ${event.packageName}")
                val step = DemoUserStep.parse(event.requiresUserStep) ?: throw IllegalArgumentException("$where: bad user step")
                step.provider?.let { require(it.packageName in providers) { "$where: step names an unknown provider" } }
                require(event.tMs >= 0) { "$where: negative t_ms" }
                require(event.sbnKey.isNotBlank()) { "$where: blank sbn_key" }
                when (event.action) {
                    DemoAction.POST -> require(event.removalReason == null) { "$where: a post has no removal_reason" }
                    DemoAction.REMOVE -> require(DemoScript.removalReasonOf(event.removalReason) != null) { "$where: bad removal_reason" }
                }
                event.screen?.let { require(it in spec.screens) { "$where: unknown screen $it" } }
            }
        }
    }
}

@Serializable
data class DemoPlace(val label: String, val lat: Double, val lng: Double) {
    val point: LatLng get() = LatLng(lat, lng)
}

@Serializable
data class DemoTrip(
    val pickup: DemoPlace,
    val drop: DemoPlace,
    /** Straight-line (haversine) km: the gate's `tripKm` (core SanityBands applies `road_factor` itself). */
    @SerialName("trip_km") val tripKm: Double,
)

@Serializable
data class DemoBooked(
    @SerialName("vehicle_class") val vehicleClass: VehicleClass,
    val label: String,
    @SerialName("price_min") val priceMin: Int,
    @SerialName("price_max") val priceMax: Int,
)

@Serializable
data class DemoProviderSpec(
    val name: String,
    @SerialName("wire_id") val wireId: String,
    @SerialName("version_code") val versionCode: Long = 1,
    /** Screen key → asset path (`demo/pepper/fare_options.png`). */
    val screens: Map<String, String>,
    /** The screen the cancel flow opens from (searching or driver_assigned). */
    @SerialName("cancel_flow_from") val cancelFlowFrom: String? = null,
    val booked: DemoBooked? = null,
)

/** Screen keys used by DemoSource. Providers may add more (Pepper's `driver_assigned_better`). */
object DemoScreens {
    const val FARE_OPTIONS = "fare_options"
    const val SEARCHING = "searching"
    const val DRIVER_ASSIGNED = "driver_assigned"
    const val CANCEL_FLOW = "cancel_flow"
    const val CANCELLED = "cancelled"
}

@Serializable
data class DemoTrackRequires(@SerialName("keep_searching") val keepSearching: Boolean = false)

@Serializable
data class DemoTrack(
    val id: String,
    val description: String = "",
    val requires: DemoTrackRequires = DemoTrackRequires(),
    val events: List<DemoEvent>,
    /** Expected guardian behaviour (used by tests; DemoSource never reads them). */
    val checkpoints: List<JsonObject> = emptyList(),
) {
    /** The providers this track books, in walk order. */
    val providers: List<Provider>
        get() = Provider.DEMO_APPS.filter { p -> events.any { it.packageName == p.packageName } }

    /** Events anchored on [step], in firing order (by t_ms; ties keep the script's order). */
    fun eventsFor(step: DemoUserStep): List<DemoEvent> =
        events.filter { it.userStep == step }.sortedBy { it.tMs }
}

@Serializable
enum class DemoAction {
    @SerialName("post") POST,
    @SerialName("remove") REMOVE,
}

@Serializable
data class DemoEvent(
    val id: String,
    @SerialName("t_ms") val tMs: Long,
    @SerialName("requires_user_step") val requiresUserStep: String,
    val action: DemoAction,
    @SerialName("package") val packageName: String,
    @SerialName("sbn_key") val sbnKey: String,
    @SerialName("channel_id") val channelId: String? = null,
    val category: String? = null,
    val ongoing: Boolean = false,
    val title: String? = null,
    val text: String? = null,
    @SerialName("removal_reason") val removalReason: String? = null,
    @SerialName("expect_event") val expectEvent: NotificationEvent? = null,
    @SerialName("expect_groups") val expectGroups: Map<String, String> = emptyMap(),
    /** The screen the provider's "app" shows from now on. */
    val screen: String? = null,
) {
    val provider: Provider get() = Provider.fromPackage(packageName)!!
    val userStep: DemoUserStep? get() = DemoUserStep.parse(requiresUserStep)

    /** Same post/removal as [other] apart from its id (tracks share a common opening). */
    fun sameContentAs(other: DemoEvent): Boolean = copy(id = other.id) == other

    /** The notification as RideWatch reads it. Title and text are exactly the script's. */
    fun toSnapshot(postTimeWallMs: Long, versionCode: Long): PostSnapshot {
        val (id, tag) = parseSbnKey(sbnKey)
        return PostSnapshot(
            packageName = packageName,
            sbnKey = sbnKey,
            id = id,
            tag = tag,
            channelId = channelId,
            category = category,
            isOngoing = ongoing,
            title = title,
            text = text,
            postTimeMs = postTimeWallMs,
            versionCode = versionCode,
        )
    }

    fun toRemoval(): PostRemoval =
        PostRemoval(packageName, sbnKey, DemoScript.removalReasonOf(removalReason) ?: RemovalReason.UNKNOWN)

    companion object {
        /** `StatusBarNotification` keys are `user|package|id|tag|uid`; id falls back to a stable hash. */
        fun parseSbnKey(key: String): Pair<Int, String?> {
            val parts = key.split('|')
            val id = parts.getOrNull(2)?.toIntOrNull() ?: key.hashCode()
            val tag = parts.getOrNull(3)?.takeIf { it.isNotEmpty() && it != "null" }
            return id to tag
        }
    }
}

/** A user action that starts the clock of the events anchored on it (eval/demo/README.md "User steps"). */
sealed interface DemoUserStep {
    val wire: String
    val provider: Provider? get() = null

    /** The user tapped "Booked here" for [provider]. */
    data class Book(override val provider: Provider) : DemoUserStep {
        override val wire: String get() = "book:${provider.packageName}"
    }

    /** "Yes, cancel the others" on the first-driver sheet (or on a MAYBE_ASSIGNED prompt). */
    data object CancelOthersYes : DemoUserStep {
        override val wire: String = "cancel_others:yes"
    }

    /** The user kept searching instead. */
    data object CancelOthersNotNow : DemoUserStep {
        override val wire: String = "cancel_others:not_now"
    }

    /** The user tapped Cancel ride on [provider]'s demo cancel screen (stands in for the tap inside the app). */
    data class TappedCancel(override val provider: Provider) : DemoUserStep {
        override val wire: String get() = "tapped_cancel:${provider.packageName}"
    }

    /** The user accepted the better-ride offer. */
    data object BetterRideYes : DemoUserStep {
        override val wire: String = "better_ride:yes"
    }

    companion object {
        fun parse(wire: String): DemoUserStep? {
            val name = wire.substringBefore(':')
            val arg = wire.substringAfter(':', "")
            fun demoProvider() = Provider.fromPackage(arg)?.takeIf { it.isDemo }
            return when (name) {
                "book" -> demoProvider()?.let(::Book)
                "tapped_cancel" -> demoProvider()?.let(::TappedCancel)
                "cancel_others" -> when (arg) {
                    "yes" -> CancelOthersYes
                    "not_now" -> CancelOthersNotNow
                    else -> null
                }
                "better_ride" -> if (arg == "yes") BetterRideYes else null
                else -> null
            }
        }
    }
}
