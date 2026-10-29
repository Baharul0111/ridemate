package app.ridemate.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.HourglassBottom
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.ridemate.R
import app.ridemate.core.model.Provider
import app.ridemate.core.model.RideState
import app.ridemate.ui.RideMateTheme
import app.ridemate.ui.RmSpacing

/**
 * One leg (one app's booking) in a [LegTimeline].
 *
 * @property stateText overrides the pill's word (e.g. a captain wording from GuardianText).
 * @property detail a short number line under the name, e.g. "4 min · 1.2 km" or "Free to cancel".
 * @property proven the leg's end state is proven (evidence rungs 1–3): a green tick + [evidenceText].
 * @property evidenceText what proves it, or what is still missing; null shows the default word when proven.
 */
@Immutable
data class TimelineLeg(
    val provider: Provider,
    val state: RideState,
    val providerName: String = provider.displayName,
    val stateText: String? = null,
    val detail: String? = null,
    val proven: Boolean = false,
    val evidenceText: String? = null,
)

/**
 * The legs of a session as a vertical timeline: avatar → state pill → evidence tick. A thin line joins the
 * avatars. Each leg is one TalkBack node ("Ola, Driver coming, Confirmed"); [onLegClick] makes it a button and
 * [trailing] adds a per-leg action (e.g. a "Show me" icon button).
 */
@Composable
fun LegTimeline(
    legs: List<TimelineLeg>,
    modifier: Modifier = Modifier,
    onLegClick: ((TimelineLeg) -> Unit)? = null,
    trailing: (@Composable (TimelineLeg) -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth().testTag(RmTags.LEG_TIMELINE)) {
        legs.forEachIndexed { index, leg ->
            LegRow(leg, isLast = index == legs.lastIndex, onLegClick = onLegClick, trailing = trailing)
        }
    }
}

@Composable
private fun LegRow(
    leg: TimelineLeg,
    isLast: Boolean,
    onLegClick: ((TimelineLeg) -> Unit)?,
    trailing: (@Composable (TimelineLeg) -> Unit)?,
) {
    val colors = RideMateTheme.colors
    val stateWord = leg.stateText ?: stringResource(leg.state.group.label)
    val evidence = when {
        leg.evidenceText != null -> leg.evidenceText
        leg.proven -> stringResource(R.string.rm_leg_proven)
        else -> null
    }
    val spoken = listOfNotNull(leg.providerName, leg.detail, stateWord, evidence).joinToString(", ")
    Row(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .weight(1f)
                .then(if (onLegClick != null) Modifier.clickable(role = Role.Button) { onLegClick(leg) } else Modifier)
                .semantics(mergeDescendants = true) { contentDescription = spoken }
                .testTag(RmTags.legRow(leg.provider.wireId)),
        ) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).heightIn(min = RmSpacing.minTouch)) {
                // Gutter: the avatar and the line down to the next leg.
                Column(Modifier.width(40.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                    ProviderAvatar(leg.provider, name = leg.providerName, contentDescription = null)
                    if (!isLast) {
                        Spacer(Modifier.height(4.dp))
                        Box(Modifier.width(2.dp).weight(1f).background(colors.line))
                    }
                }
                Spacer(Modifier.width(RmSpacing.m))
                Column(
                    Modifier.weight(1f).padding(bottom = if (isLast) 0.dp else RmSpacing.l),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(leg.providerName, style = MaterialTheme.typography.titleMedium, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (leg.detail != null) {
                        Text(leg.detail, style = MaterialTheme.typography.bodyMedium, color = colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    StatusPill(leg.state, text = stateWord)
                    if (evidence != null) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag(RmTags.LEG_PROOF)) {
                            Icon(
                                if (leg.proven) Icons.Rounded.CheckCircle else Icons.Rounded.HourglassBottom,
                                contentDescription = null,
                                tint = if (leg.proven) colors.good else colors.muted,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                evidence,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (leg.proven) colors.onGoodContainer else colors.muted,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
        if (trailing != null) {
            Box(Modifier.padding(start = RmSpacing.s)) { trailing(leg) }
        }
    }
}

@Preview(name = "Leg timeline", showBackground = true)
@Composable
private fun LegTimelinePreview() {
    RideMateTheme {
        LegTimeline(
            legs = listOf(
                TimelineLeg(Provider.OLA, RideState.ARRIVING, detail = "4 min · 1.2 km"),
                TimelineLeg(Provider.UBER, RideState.CANCEL_CONFIRMED, proven = true),
                TimelineLeg(Provider.RAPIDO, RideState.CANCEL_GUIDED, evidenceText = "Waiting for proof"),
            ),
            modifier = Modifier.padding(20.dp),
        )
    }
}
