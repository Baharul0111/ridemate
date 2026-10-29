package app.ridemate.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.ridemate.ui.RideMateTheme
import app.ridemate.ui.RmShapes
import app.ridemate.ui.RmSpacing

/**
 * The tone of a pill, banner or card. Each tone has its own icon ([RmTone.icon]) so a state is never shown by
 * colour alone (goal 27); the colour only reinforces the icon and the word.
 */
enum class RmTone { NEUTRAL, BRAND, GOOD, WARN, DANGER, DEMO, ACCENT }

/** A container colour and the content colour that reads on it (WCAG AA). */
@Immutable
data class ToneColors(val container: Color, val content: Color, val solid: Color)

/** The soft container/content pair of this tone in the current theme. */
@Composable
fun RmTone.colors(): ToneColors {
    val c = RideMateTheme.colors
    return when (this) {
        RmTone.NEUTRAL -> ToneColors(c.neutralContainer, c.onNeutralContainer, c.muted)
        RmTone.BRAND -> ToneColors(c.brandContainer, c.onBrandContainer, c.brand)
        RmTone.GOOD -> ToneColors(c.goodContainer, c.onGoodContainer, c.good)
        RmTone.WARN -> ToneColors(c.warnContainer, c.onWarnContainer, c.warn)
        RmTone.DANGER -> ToneColors(c.dangerContainer, c.onDangerContainer, c.danger)
        RmTone.DEMO -> ToneColors(c.demoContainer, c.onDemoContainer, c.onDemoContainer)
        RmTone.ACCENT -> ToneColors(c.accent, c.onAccent, c.accent)
    }
}

/** The default icon of this tone: a distinct shape per tone. */
fun RmTone.icon(): ImageVector = when (this) {
    RmTone.NEUTRAL, RmTone.BRAND -> Icons.Rounded.Info
    RmTone.GOOD -> Icons.Rounded.CheckCircle
    RmTone.WARN -> Icons.Rounded.WarningAmber
    RmTone.DANGER -> Icons.Rounded.Error
    RmTone.DEMO -> Icons.Rounded.Science
    RmTone.ACCENT -> Icons.Rounded.Star
}

/**
 * Stable test tags of the design system (androidTests and Robolectric tests rely on them; never rename).
 * Components accept a `modifier`, so screens can add their own, more specific tags on top.
 */
object RmTags {
    const val SCAFFOLD = "rm_scaffold"
    const val TOP_BAR = "rm_top_bar"
    const val BACK = "rm_back"
    const val BOTTOM_BAR = "rm_bottom_bar"
    const val SHEET_BODY = "rm_sheet_body"
    const val WHERE_TO = "rm_where_to"
    const val WHERE_TO_MIC = "rm_where_to_mic"
    const val ROUTE_STACK = "rm_route_stack"
    const val ROUTE_PICKUP = "rm_route_pickup"
    const val ROUTE_DROP = "rm_route_drop"
    const val PLACE_ROW = "rm_place_row"
    const val PROVIDER_AVATAR = "rm_provider_avatar"
    const val DEMO_TAG = "rm_demo_tag"
    const val VEHICLE_ICON = "rm_vehicle_icon"
    const val CHOICE_TILE = "rm_choice_tile"
    const val CHOICE_CHECK = "rm_choice_check"
    const val FARE_CARD = "rm_fare_card"
    const val FARE_PRICE = "rm_fare_price"
    const val FARE_UNREADABLE = "rm_fare_unreadable"
    const val FARE_CHEAPEST = "rm_fare_cheapest"
    const val FARE_STALE = "rm_fare_stale"
    const val FARE_REFRESH = "rm_fare_refresh"
    const val FARE_ENTERED = "rm_fare_entered_by_you"
    const val FARE_NETWORK = "rm_fare_network"
    const val FARE_SEEN_AT = "rm_fare_seen_at"
    const val FARE_TYPE_PRICE = "rm_fare_type_price"
    const val FARE_ETA = "rm_fare_eta"
    const val PILL = "rm_pill"
    const val PILL_ICON = "rm_pill_icon"
    const val STATUS_PILL = "rm_status_pill"
    const val LEG_TIMELINE = "rm_leg_timeline"
    const val LEG_ROW = "rm_leg_row"
    const val LEG_PROOF = "rm_leg_proof"
    const val BIG_BUTTON = "rm_big_button"
    const val QUIET_BUTTON = "rm_quiet_button"
    const val ICON_BUTTON = "rm_icon_button"
    const val WHY_BUTTON = "rm_why_button"
    const val INFO_SHEET = "rm_info_sheet"
    const val INFO_SHEET_CLOSE = "rm_info_sheet_close"
    const val STEP_DOTS = "rm_step_dots"
    const val HERO = "rm_hero"
    const val BANNER = "rm_inline_banner"
    const val BANNER_ACTION = "rm_inline_banner_action"
    const val CARD = "rm_card"
    const val LIST_GROUP = "rm_list_group"
    const val LIST_ROW = "rm_list_row"
    const val SECTION_TITLE = "rm_section_title"

    /** Tag of one fare card: `rm_fare_card_<provider>_<vehicle>` (wire ids). */
    fun fareCard(providerWireId: String, vehicleWireId: String) = "${FARE_CARD}_${providerWireId}_$vehicleWireId"

    /** Tag of one leg row in a [LegTimeline]: `rm_leg_row_<provider>`. */
    fun legRow(providerWireId: String) = "${LEG_ROW}_$providerWireId"
}

// ------------------------------------------------------------------------------------------------------- pill

/**
 * A small fully-rounded pill: icon **and** word on a soft tone colour (e.g. "Free", "2 still open").
 * Screen readers read [contentDescription] (default: [text]) once; the icon is decorative.
 */
@Composable
fun RmPill(
    text: String,
    modifier: Modifier = Modifier,
    tone: RmTone = RmTone.NEUTRAL,
    icon: ImageVector? = tone.icon(),
    contentDescription: String = text,
) {
    val colors = tone.colors()
    Surface(
        color = colors.container,
        contentColor = colors.content,
        shape = RmShapes.pill,
        modifier = modifier
            .testTag(RmTags.PILL)
            .semantics(mergeDescendants = true) { this.contentDescription = contentDescription },
    ) {
        Row(
            modifier = Modifier.padding(start = if (icon != null) 8.dp else 12.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp).testTag(RmTags.PILL_ICON))
                Spacer(Modifier.width(6.dp))
            }
            Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ------------------------------------------------------------------------------------------------------- card

/**
 * A flat card: 20 dp corners, 16 dp padding. [RmTone.NEUTRAL] is the `card` colour: white with a 1 dp `line`
 * border in light, a tonal surface one step above the sheet in dark (so it stays visible on a sheet); other tones
 * use their soft container. Clickable when [onClick] is set (then ≥ 48 dp tall and announced as a button).
 */
@Composable
fun RmCard(
    modifier: Modifier = Modifier,
    tone: RmTone = RmTone.NEUTRAL,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    padding: Dp = RmSpacing.card,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = RideMateTheme.colors
    val (container, contentColor) = if (tone == RmTone.NEUTRAL) colors.card to colors.ink else tone.colors().let { it.container to it.content }
    val border = if (tone == RmTone.NEUTRAL && !colors.isDark) BorderStroke(1.dp, colors.line) else null
    Surface(
        color = container,
        contentColor = contentColor,
        shape = RmShapes.card,
        border = border,
        modifier = modifier.fillMaxWidth().testTag(RmTags.CARD),
    ) {
        Column(
            modifier = Modifier
                .then(
                    if (onClick != null) {
                        Modifier.heightIn(min = RmSpacing.minTouch).clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick)
                    } else {
                        Modifier
                    },
                )
                .padding(padding),
            verticalArrangement = Arrangement.spacedBy(RmSpacing.s),
            content = content,
        )
    }
}

/**
 * A section title above a group of rows or cards, announced as a heading. Short: 1–3 words. [leading] puts a
 * decorative glyph before the words, e.g. the Compare sections' `VehicleIcon` + "Auto" (pass the icon with
 * `contentDescription = null`; the heading already says the word).
 */
@Composable
fun RmSectionTitle(text: String, modifier: Modifier = Modifier, leading: (@Composable () -> Unit)? = null) {
    val colors = RideMateTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = RmSpacing.xs, top = RmSpacing.l, bottom = RmSpacing.xs)
            .testTag(RmTags.SECTION_TITLE)
            .semantics(mergeDescendants = true) { heading() },
    ) {
        if (leading != null) {
            CompositionLocalProvider(LocalContentColor provides colors.ink) { leading() }
            Spacer(Modifier.width(RmSpacing.s))
        }
        Text(
            text,
            style = if (leading != null) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
            color = if (leading != null) colors.ink else colors.muted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A round tonal "icon well" (leading icon of rows, banners and cards). Decorative. */
@Composable
fun IconWell(icon: ImageVector, modifier: Modifier = Modifier, tone: RmTone = RmTone.NEUTRAL, size: Dp = 40.dp) {
    val colors = tone.colors()
    Box(
        modifier = modifier.size(size).background(colors.container, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = colors.content, modifier = Modifier.size(size * 0.55f))
    }
}

/**
 * A grouped list: rows on one `sheet` card (Settings / My data). Put [RmListRow]s inside and separate them with
 * [RmListDivider].
 */
@Composable
fun RmListGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val colors = RideMateTheme.colors
    Surface(
        color = colors.card,
        contentColor = colors.ink,
        shape = RmShapes.card,
        border = if (!colors.isDark) BorderStroke(1.dp, colors.line) else null,
        modifier = modifier.fillMaxWidth().testTag(RmTags.LIST_GROUP),
    ) {
        Column(content = content)
    }
}

/** The inset divider between two rows of an [RmListGroup]. */
@Composable
fun RmListDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(color = RideMateTheme.colors.line, modifier = modifier.padding(start = 68.dp))
}

/** A dashed rounded border (unreadable fare cards). Drawn over the content, so it never changes layout. */
fun Modifier.dashedBorder(color: Color, width: Dp = 1.5.dp, cornerRadius: Dp = 20.dp): Modifier =
    drawWithContent {
        drawContent()
        val stroke = width.toPx()
        val radius = cornerRadius.toPx()
        drawRoundRect(
            color = color,
            topLeft = Offset(stroke / 2, stroke / 2),
            size = Size(size.width - stroke, size.height - stroke),
            cornerRadius = CornerRadius(radius, radius),
            style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
        )
    }

@Preview(name = "Pills and cards", showBackground = true)
@Composable
private fun FoundationPreview() {
    RideMateTheme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RmTone.entries.forEach { RmPill(it.name.lowercase(), tone = it) }
            RmCard { Text("Card") }
            RmCard(tone = RmTone.WARN) { Text("Warn card") }
        }
    }
}
