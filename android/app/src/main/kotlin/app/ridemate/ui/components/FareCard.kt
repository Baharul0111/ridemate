package app.ridemate.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccessTime
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.ridemate.R
import app.ridemate.core.model.Provider
import app.ridemate.core.model.QuoteStatus
import app.ridemate.core.model.VehicleClass
import app.ridemate.ui.RideMateTheme
import app.ridemate.ui.RmShapes
import app.ridemate.ui.RmSpacing

/**
 * One fare option as shown on a [FareCard]. Plain display data: the screen fills it from the gate's output.
 *
 * @property label the option's name: OCR/template text or a localised class name, never model text (I5).
 * @property priceText e.g. "₹120" or "₹110–130". Never shown when [status] is [QuoteStatus.UNREADABLE] (I2).
 * @property seenAtText when the price was seen, e.g. "10:30 AM".
 * @property etaMinutes pickup ETA in minutes, shown as a chip; or [etaText] for wording such as "See in app".
 * @property cheapest the gate marked it cheapest. Shown only for a fresh verified price ([showsCheapest]).
 * @property stale older than 60 s: greyed, clock icon, "[ageMinutes] min ago" and a refresh button.
 */
@Immutable
data class FareQuote(
    val provider: Provider,
    val vehicleClass: VehicleClass,
    val label: String,
    val status: QuoteStatus,
    val priceText: String?,
    val seenAtText: String,
    val providerName: String = provider.displayName,
    val etaMinutes: Int? = null,
    val etaText: String? = null,
    val cheapest: Boolean = false,
    val stale: Boolean = false,
    val ageMinutes: Int = 1,
) {
    /** I2: a number appears only for a readable quote. */
    val showsPrice: Boolean get() = status != QuoteStatus.UNREADABLE && !priceText.isNullOrBlank()

    /** "Cheapest" only on a fresh, verified, shown price; never on typed, network, stale or unreadable rows. */
    val showsCheapest: Boolean get() = cheapest && status == QuoteStatus.VERIFIED && !stale && showsPrice
}

/**
 * The fare option card of Compare (price first): provider avatar, vehicle glyph and label, ETA chip, the price as
 * the largest text on the right, and its state as icon + word:
 *
 * * verified + cheapest: marigold "★ Cheapest" badge;
 * * stale: greyed, "🕑 1 min ago · Seen 10:30 AM" and a refresh icon button;
 * * unreadable: dashed border, "⚠ Check in app" and **no number**, optional "✎ Type price";
 * * typed by the user: "✎ You typed" tag; ONDC: "🌐 Network" tag;
 * * always the time it was seen.
 *
 * The card is one TalkBack node with a full sentence; [onClick] (open the app) makes it a button.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FareCard(
    quote: FareQuote,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onRefresh: (() -> Unit)? = null,
    onTypePrice: (() -> Unit)? = null,
) {
    val colors = RideMateTheme.colors
    val unreadable = quote.status == QuoteStatus.UNREADABLE
    val spoken = fareDescription(quote)
    val openLabel = stringResource(R.string.rm_open_app, quote.providerName)
    val container = if (quote.stale) colors.neutralContainer else colors.sheet
    val shape = RmShapes.card
    CompositionLocalProvider(LocalContentColor provides colors.ink) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .then(if (unreadable) Modifier.dashedBorder(colors.warn) else Modifier)
                .clip(shape)
                .background(container)
                .then(if (!unreadable && !colors.isDark) Modifier.border(1.dp, colors.line, shape) else Modifier)
                .then(if (onClick != null) Modifier.clickable(onClickLabel = openLabel, role = Role.Button, onClick = onClick) else Modifier)
                .semantics(mergeDescendants = true) { contentDescription = spoken }
                .testTag(RmTags.fareCard(quote.provider.wireId, quote.vehicleClass.wireId))
                .padding(RmSpacing.card),
            verticalArrangement = Arrangement.spacedBy(RmSpacing.s),
        ) {
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.alpha(if (quote.stale) 0.6f else 1f)) {
                ProviderAvatar(quote.provider, name = quote.providerName, contentDescription = null)
                Spacer(Modifier.width(RmSpacing.m))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(RmSpacing.xs)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        VehicleIcon(quote.vehicleClass, size = 20.dp, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(quote.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        itemVerticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(quote.providerName, style = MaterialTheme.typography.labelMedium, color = colors.muted, maxLines = 1)
                        etaLabel(quote)?.let { MiniTag(it, Icons.Rounded.Schedule, RmTags.FARE_ETA) }
                        when (quote.status) {
                            QuoteStatus.USER_ENTERED -> MiniTag(stringResource(R.string.rm_entered_by_you), Icons.Rounded.Edit, RmTags.FARE_ENTERED)
                            QuoteStatus.NETWORK -> MiniTag(stringResource(R.string.rm_network_quote), Icons.Rounded.Public, RmTags.FARE_NETWORK)
                            else -> Unit
                        }
                    }
                }
                Spacer(Modifier.width(RmSpacing.m))
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (quote.showsPrice) {
                        Text(
                            quote.priceText!!,
                            style = RideMateTheme.type.price,
                            maxLines = 1,
                            modifier = Modifier.testTag(RmTags.FARE_PRICE),
                        )
                    } else if (unreadable) {
                        // Icon + words on a soft warn pill (AA contrast); no number, ever (I2).
                        Surface(
                            color = colors.warnContainer,
                            contentColor = colors.onWarnContainer,
                            shape = RmShapes.pill,
                            modifier = Modifier.widthIn(max = 150.dp).testTag(RmTags.FARE_UNREADABLE),
                        ) {
                            Row(Modifier.padding(start = 8.dp, end = 10.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.WarningAmber, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    stringResource(R.string.rm_check_in_app),
                                    style = MaterialTheme.typography.labelLarge,
                                    textAlign = TextAlign.End,
                                    maxLines = 2,
                                )
                            }
                        }
                    }
                    if (quote.showsCheapest) CheapestBadge()
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 24.dp)) {
                if (quote.stale) {
                    Icon(Icons.Rounded.AccessTime, contentDescription = null, tint = colors.muted, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        pluralStringResource(R.plurals.rm_minutes_ago, quote.ageMinutes, quote.ageMinutes) + " · " +
                            stringResource(R.string.rm_seen_at, quote.seenAtText),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.muted,
                        maxLines = 2,
                        modifier = Modifier.weight(1f).testTag(RmTags.FARE_STALE),
                    )
                } else {
                    Text(
                        stringResource(R.string.rm_seen_at, quote.seenAtText),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.muted,
                        maxLines = 1,
                        modifier = Modifier.weight(1f).testTag(RmTags.FARE_SEEN_AT),
                    )
                }
                if (unreadable && onTypePrice != null) {
                    QuietButton(
                        text = stringResource(R.string.rm_type_price),
                        onClick = onTypePrice,
                        icon = Icons.Rounded.Edit,
                        style = QuietStyle.Text,
                        modifier = Modifier.testTag(RmTags.FARE_TYPE_PRICE),
                    )
                }
                if (quote.stale && onRefresh != null) {
                    RmIconButton(
                        icon = Icons.Rounded.Refresh,
                        contentDescription = stringResource(R.string.rm_refresh_price, quote.providerName),
                        onClick = onRefresh,
                        containerColor = colors.sheet,
                        modifier = Modifier.testTag(RmTags.FARE_REFRESH),
                    )
                }
            }
        }
    }
}

@Composable
private fun etaLabel(quote: FareQuote): String? = when {
    quote.etaMinutes != null -> stringResource(R.string.rm_eta_min, quote.etaMinutes)
    quote.etaText != null -> quote.etaText
    else -> null
}

/** The full sentence TalkBack reads for a fare card. Never contains a number for an unreadable quote (I2). */
@Composable
private fun fareDescription(quote: FareQuote): String {
    val parts = mutableListOf(quote.providerName, quote.label)
    parts += if (quote.showsPrice) quote.priceText!! else stringResource(R.string.rm_unreadable_cd)
    if (quote.showsCheapest) parts += stringResource(R.string.rm_cheapest)
    when (quote.status) {
        QuoteStatus.USER_ENTERED -> parts += stringResource(R.string.rm_entered_by_you)
        QuoteStatus.NETWORK -> parts += stringResource(R.string.rm_network_quote)
        else -> Unit
    }
    when {
        quote.etaMinutes != null -> parts += stringResource(R.string.rm_eta_cd, quote.etaMinutes)
        quote.etaText != null -> parts += quote.etaText
    }
    parts += stringResource(R.string.rm_seen_at, quote.seenAtText)
    if (quote.stale) parts += stringResource(R.string.rm_old_price)
    return parts.joinToString(", ")
}

/** A tiny icon + word tag inside a card (ETA, "You typed", "Network"). */
@Composable
private fun MiniTag(text: String, icon: ImageVector, tag: String) {
    val colors = RideMateTheme.colors
    Surface(
        color = colors.neutralContainer,
        contentColor = colors.onNeutralContainer,
        shape = RmShapes.pill,
        modifier = Modifier.testTag(tag),
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(4.dp))
            Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

/** The marigold "★ Cheapest" badge (icon + word). */
@Composable
fun CheapestBadge(modifier: Modifier = Modifier) {
    val colors = RideMateTheme.colors
    Surface(
        color = colors.accent,
        contentColor = colors.onAccent,
        shape = RmShapes.pill,
        modifier = modifier.testTag(RmTags.FARE_CHEAPEST),
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Star, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(3.dp))
            Text(stringResource(R.string.rm_cheapest), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Preview(name = "Fare cards", showBackground = true, heightDp = 900)
@Composable
private fun FareCardPreview() {
    RideMateTheme {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FareCard(FareQuote(Provider.RAPIDO, VehicleClass.AUTO, "Auto", QuoteStatus.VERIFIED, "₹96", "10:30 AM", etaMinutes = 3, cheapest = true), onClick = {})
            FareCard(FareQuote(Provider.UBER, VehicleClass.AUTO, "Uber Auto", QuoteStatus.VERIFIED, "₹112", "10:30 AM", etaMinutes = 5), onClick = {})
            FareCard(FareQuote(Provider.OLA, VehicleClass.AUTO, "Auto", QuoteStatus.VERIFIED, "₹104", "10:28 AM", stale = true, ageMinutes = 2), onRefresh = {})
            FareCard(FareQuote(Provider.OLA, VehicleClass.CAB_ECONOMY, "Mini", QuoteStatus.UNREADABLE, null, "10:30 AM"), onTypePrice = {})
            FareCard(FareQuote(Provider.UBER, VehicleClass.BIKE, "Moto", QuoteStatus.USER_ENTERED, "₹60", "10:31 AM"))
            FareCard(FareQuote(Provider.ONDC, VehicleClass.AUTO, "Namma Yatri", QuoteStatus.NETWORK, "₹90–110", "10:31 AM", etaMinutes = 4))
            FareCard(FareQuote(Provider.DEMO_KESTREL, VehicleClass.CAB_XL, "XL", QuoteStatus.VERIFIED, "₹310", "10:31 AM"))
        }
    }
}
