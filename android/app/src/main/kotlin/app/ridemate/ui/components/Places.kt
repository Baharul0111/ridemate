package app.ridemate.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ridemate.R
import app.ridemate.ui.RideMateTheme
import app.ridemate.ui.RmShapes
import app.ridemate.ui.RmSpacing

/**
 * The big "Where to?" pill on Home: search icon, the question, and an optional mic. The whole pill (except the
 * mic) is one button; TalkBack says "Search where to go".
 *
 * @param onMic shows the mic button when set (voice input of the destination).
 */
@Composable
fun WhereToBar(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    text: String = stringResource(R.string.rm_where_to),
    onMic: (() -> Unit)? = null,
) {
    val colors = RideMateTheme.colors
    val spoken = stringResource(R.string.rm_where_to_cd)
    Surface(
        color = colors.sheet,
        contentColor = colors.ink,
        shape = RmShapes.pill,
        border = if (!colors.isDark) BorderStroke(1.dp, colors.line) else null,
        shadowElevation = if (colors.isDark) 0.dp else 1.dp,
        modifier = modifier.fillMaxWidth().testTag(RmTags.WHERE_TO),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(IntrinsicSize.Min)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 60.dp)
                    .clickable(role = Role.Button, onClick = onClick)
                    .semantics(mergeDescendants = true) { contentDescription = spoken }
                    .padding(horizontal = RmSpacing.xl, vertical = RmSpacing.m),
            ) {
                Icon(Icons.Rounded.Search, contentDescription = null, tint = colors.brand, modifier = Modifier.size(26.dp))
                Spacer(Modifier.width(RmSpacing.m))
                Text(
                    text,
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, lineHeight = 24.sp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (onMic != null) {
                VerticalDivider(color = colors.line, modifier = Modifier.fillMaxHeight().padding(vertical = RmSpacing.m))
                RmIconButton(
                    icon = Icons.Rounded.Mic,
                    contentDescription = stringResource(R.string.rm_voice_search),
                    onClick = onMic,
                    modifier = Modifier.padding(horizontal = RmSpacing.s).testTag(RmTags.WHERE_TO_MIC),
                )
            }
        }
    }
}

/**
 * Pickup ● and drop ■ joined by a dotted line, as on every ride app's trip screen. Tap a line to edit it (when its
 * callback is set). [drop] null shows the "Where to?" placeholder. [dropTrailing] holds e.g. a "View on map" icon
 * button on the drop line. [compact] is the smaller header used on Compare.
 */
@Composable
fun RouteStack(
    pickup: String,
    drop: String?,
    modifier: Modifier = Modifier,
    onPickupClick: (() -> Unit)? = null,
    onDropClick: (() -> Unit)? = null,
    dropTrailing: (@Composable () -> Unit)? = null,
    compact: Boolean = false,
) {
    val colors = RideMateTheme.colors
    Surface(
        color = colors.sheet,
        contentColor = colors.ink,
        shape = RmShapes.card,
        border = if (!colors.isDark) BorderStroke(1.dp, colors.line) else null,
        modifier = modifier.fillMaxWidth().testTag(RmTags.ROUTE_STACK),
    ) {
        Column(Modifier.padding(horizontal = RmSpacing.m, vertical = if (compact) RmSpacing.xs else RmSpacing.s)) {
            RouteLine(
                text = pickup,
                placeholder = false,
                spoken = stringResource(R.string.rm_pickup_cd, pickup),
                marker = RouteMarker.PICKUP,
                onClick = onPickupClick,
                compact = compact,
                trailing = null,
                tag = RmTags.ROUTE_PICKUP,
            )
            val dropText = drop ?: stringResource(R.string.rm_where_to)
            RouteLine(
                text = dropText,
                placeholder = drop == null,
                spoken = stringResource(R.string.rm_drop_cd, dropText),
                marker = RouteMarker.DROP,
                onClick = onDropClick,
                compact = compact,
                trailing = dropTrailing,
                tag = RmTags.ROUTE_DROP,
            )
        }
    }
}

private enum class RouteMarker { PICKUP, DROP }

@Composable
private fun RouteLine(
    text: String,
    placeholder: Boolean,
    spoken: String,
    marker: RouteMarker,
    onClick: (() -> Unit)?,
    compact: Boolean,
    trailing: (@Composable () -> Unit)?,
    tag: String,
) {
    val colors = RideMateTheme.colors
    val brand = colors.brand
    val ink = colors.ink
    val dots = colors.muted
    val line = colors.line
    val hole = colors.sheet
    Row(Modifier.height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
        // The gutter draws this line's marker and its half of the dotted connector, so the route stays joined
        // whatever the text height (font scaling to 200 %).
        Box(
            Modifier
                .width(24.dp)
                .fillMaxHeight()
                .drawBehind {
                    val cx = size.width / 2
                    val cy = size.height / 2
                    val dash = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 4.dp.toPx()))
                    val stroke = 2.dp.toPx()
                    val r = 5.dp.toPx()
                    if (marker == RouteMarker.PICKUP) {
                        drawLine(dots, Offset(cx, cy + r + 3.dp.toPx()), Offset(cx, size.height), stroke, StrokeCap.Round, dash)
                        drawCircle(brand, r, Offset(cx, cy))
                        drawCircle(hole, r * 0.4f, Offset(cx, cy))
                    } else {
                        drawLine(dots, Offset(cx, 0f), Offset(cx, cy - r - 3.dp.toPx()), stroke, StrokeCap.Round, dash)
                        drawRoundRect(ink, Offset(cx - r, cy - r), Size(2 * r, 2 * r), CornerRadius(1.5.dp.toPx()))
                    }
                },
        )
        Spacer(Modifier.width(RmSpacing.m))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .drawBehind {
                    if (marker == RouteMarker.DROP) drawLine(line, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx())
                },
        ) {
            Box(
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = if (compact) RmSpacing.minTouch else RmSpacing.row)
                    .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
                    .semantics(mergeDescendants = true) { contentDescription = spoken }
                    .testTag(tag)
                    .padding(vertical = RmSpacing.s),
            ) {
                Text(
                    text,
                    style = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                    color = if (placeholder) colors.muted else colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            trailing?.invoke()
        }
    }
}

/**
 * One place suggestion or recent place: pin icon, a one-line name and a one-line muted area. The whole row is
 * one button (≥ 56 dp) that TalkBack reads as "name, area".
 */
@Composable
fun PlaceRow(
    name: String,
    area: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Rounded.Place,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = RideMateTheme.colors
    val spoken = if (area.isNullOrBlank()) name else "$name, $area"
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = RmSpacing.row + 8.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = spoken }
            .testTag(RmTags.PLACE_ROW)
            .padding(vertical = RmSpacing.s, horizontal = RmSpacing.xs),
    ) {
        IconWell(icon, tone = RmTone.NEUTRAL)
        Spacer(Modifier.width(RmSpacing.l))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, style = MaterialTheme.typography.titleMedium, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!area.isNullOrBlank()) {
                Text(area, style = MaterialTheme.typography.bodyMedium, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        trailing?.invoke()
    }
}

@Preview(name = "Where to, route, places", showBackground = true)
@Composable
private fun PlacesPreview() {
    RideMateTheme {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            WhereToBar(onClick = {}, onMic = {})
            RouteStack(
                pickup = "Current location",
                drop = "LGBI Airport",
                onPickupClick = {},
                onDropClick = {},
                dropTrailing = { RmIconButton(Icons.Rounded.Map, "View on map", {}) },
            )
            RouteStack(pickup = "Current location", drop = null, compact = true)
            PlaceRow("LGBI Airport", "Borjhar, Guwahati", {}, icon = Icons.Rounded.History)
            PlaceRow("Paltan Bazaar", "Guwahati", {})
        }
    }
}
