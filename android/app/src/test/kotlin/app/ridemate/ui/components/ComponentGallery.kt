package app.ridemate.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Policy
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.ridemate.core.model.Provider
import app.ridemate.core.model.QuoteStatus
import app.ridemate.core.model.RideState
import app.ridemate.core.model.VehicleClass

/** Sample fares covering every FareCard state. */
object Samples {
    val cheapest = FareQuote(Provider.RAPIDO, VehicleClass.AUTO, "Auto", QuoteStatus.VERIFIED, "₹96", "10:30 AM", etaMinutes = 3, cheapest = true)
    val stale = FareQuote(Provider.OLA, VehicleClass.AUTO, "Auto", QuoteStatus.VERIFIED, "₹104", "10:28 AM", stale = true, ageMinutes = 2)
    val unreadable = FareQuote(Provider.OLA, VehicleClass.CAB_ECONOMY, "Mini", QuoteStatus.UNREADABLE, "₹120", "10:30 AM", cheapest = true)
    val typed = FareQuote(Provider.UBER, VehicleClass.BIKE, "Moto", QuoteStatus.USER_ENTERED, "₹60", "10:31 AM", cheapest = true)
    val network = FareQuote(Provider.ONDC, VehicleClass.AUTO, "Namma Yatri", QuoteStatus.NETWORK, "₹90–110", "10:31 AM", etaMinutes = 4, cheapest = true)
    val demo = FareQuote(Provider.DEMO_KESTREL, VehicleClass.CAB_XL, "XL", QuoteStatus.VERIFIED, "₹310", "10:31 AM")

    val legs = listOf(
        TimelineLeg(Provider.OLA, RideState.ARRIVING, detail = "4 min · 1.2 km"),
        TimelineLeg(Provider.UBER, RideState.CANCEL_CONFIRMED, proven = true),
        TimelineLeg(Provider.RAPIDO, RideState.CANCEL_GUIDED, evidenceText = "Waiting for proof"),
    )
}

/** Every design-system component on one scrolling page (tests render it in light, dark and at 200 % text). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ComponentGallery(clicks: MutableList<String> = mutableListOf()) {
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RmTopBar(title = "Compare", onBack = { clicks += "back" }, actions = { RmIconButton(Icons.Rounded.Settings, "Settings", { clicks += "settings" }) })
        WhereToBar(onClick = { clicks += "where" }, onMic = { clicks += "mic" })
        RouteStack(
            pickup = "Current location",
            drop = "LGBI Airport",
            onPickupClick = { clicks += "pickup" },
            onDropClick = { clicks += "drop" },
            dropTrailing = { RmIconButton(Icons.Rounded.Map, "View on map", { clicks += "map" }) },
        )
        RouteStack(pickup = "Current location", drop = null, compact = true, onDropClick = { clicks += "drop2" })
        PlaceRow("LGBI Airport", "Borjhar, Guwahati", { clicks += "place" })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Provider.entries.forEach { ProviderAvatar(it) } }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { VehicleClass.entries.forEach { VehicleIcon(it) } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ProviderChoiceTile(Provider.UBER, true, { clicks += "tile_uber" }, Modifier.weight(1f))
            VehicleChoiceTile(VehicleClass.AUTO, false, { clicks += "tile_auto" }, Modifier.weight(1f))
        }
        FareCard(Samples.cheapest, onClick = { clicks += "fare" })
        FareCard(Samples.stale, onRefresh = { clicks += "refresh" })
        FareCard(Samples.unreadable, onTypePrice = { clicks += "type" })
        FareCard(Samples.typed)
        FareCard(Samples.network)
        FareCard(Samples.demo)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { RideStateGroup.entries.forEach { StatusPill(it) } }
        RmPill("Free", tone = RmTone.GOOD)
        LegTimeline(Samples.legs)
        BigPrimaryButton("Compare 3 apps", { clicks += "big" })
        QuietButton("Keep all", { clicks += "quiet" }, fillWidth = true)
        QuietButton("Skip", { clicks += "skip" }, style = QuietStyle.Text)
        WhyButton({ clicks += "why" })
        StepDots(count = 6, current = 1)
        Hero.entries.forEach { HeroIllustration(it, height = 80.dp) }
        InlineBanner("Offline", tone = RmTone.WARN, icon = Icons.Rounded.WifiOff, actionLabel = "Retry", onAction = { clicks += "retry" })
        RmSectionTitle("Privacy")
        RmListGroup {
            RmListRow("Privacy notice", icon = Icons.Rounded.Policy, onClick = { clicks += "row" })
            RmListDivider()
            RmToggleRow("Ride alerts", checked = true, onCheckedChange = { clicks += "toggle" }, icon = Icons.Rounded.Notifications)
            RmListDivider()
            RmRadioRow("English", selected = true, onClick = { clicks += "radio" })
        }
        DemoTag()
        RmCard(tone = RmTone.WARN) {
            // The sheet body scrolls itself, so it needs a bounded height inside this scrolling page.
            Box(Modifier.height(260.dp)) { InfoSheetContent(title = "Why?", onClose = { clicks += "close" }, body = "Because.") }
        }
    }
}

/** Tags every gallery must show (unmerged tree). */
val GalleryTags = listOf(
    RmTags.TOP_BAR, RmTags.BACK, RmTags.WHERE_TO, RmTags.WHERE_TO_MIC, RmTags.ROUTE_STACK, RmTags.ROUTE_PICKUP,
    RmTags.ROUTE_DROP, RmTags.PLACE_ROW, RmTags.PROVIDER_AVATAR, RmTags.DEMO_TAG, RmTags.VEHICLE_ICON,
    RmTags.CHOICE_TILE, RmTags.CHOICE_CHECK, RmTags.FARE_PRICE, RmTags.FARE_UNREADABLE, RmTags.FARE_CHEAPEST,
    RmTags.FARE_STALE, RmTags.FARE_REFRESH, RmTags.FARE_ENTERED, RmTags.FARE_NETWORK, RmTags.FARE_SEEN_AT,
    RmTags.FARE_TYPE_PRICE, RmTags.FARE_ETA, RmTags.STATUS_PILL, RmTags.PILL, RmTags.PILL_ICON, RmTags.LEG_TIMELINE,
    RmTags.LEG_PROOF, RmTags.BIG_BUTTON, RmTags.QUIET_BUTTON, RmTags.WHY_BUTTON, RmTags.STEP_DOTS, RmTags.HERO,
    RmTags.BANNER, RmTags.BANNER_ACTION, RmTags.SECTION_TITLE, RmTags.LIST_GROUP, RmTags.LIST_ROW, RmTags.CARD,
    RmTags.INFO_SHEET_CLOSE, RmTags.legRow("ola"), RmTags.fareCard("rapido", "auto"),
)
