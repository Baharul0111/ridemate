package app.ridemate.ui.components

import androidx.annotation.StringRes
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.PendingActions
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.ridemate.R
import app.ridemate.core.model.RideState
import app.ridemate.ui.RideMateTheme

/**
 * The ten things a leg's [RideState] can mean to a rider. Each group has its own icon, word and tone, so a
 * [StatusPill] is readable without colour (goal 27).
 */
enum class RideStateGroup(
    @get:StringRes val label: Int,
    val icon: ImageVector,
    val tone: RmTone,
) {
    /** Selected, opened or quoted; nothing booked yet. */
    NOT_BOOKED(R.string.rm_state_not_booked, Icons.Rounded.RadioButtonUnchecked, RmTone.NEUTRAL),

    /** Booking requested or searching for a driver (incl. the open network's select/init/confirmed steps). */
    SEARCHING(R.string.rm_state_searching, Icons.Rounded.HourglassTop, RmTone.BRAND),

    /** The app's screen is ambiguous (maybe a driver, maybe changed) or a cancel couldn't be confirmed. */
    CHECK_APP(R.string.rm_state_check_app, Icons.Rounded.WarningAmber, RmTone.WARN),

    /** A driver is assigned or arriving: the only "your ride" states (I10). */
    DRIVER_COMING(R.string.rm_state_driver_coming, Icons.Rounded.DirectionsCar, RmTone.GOOD),

    /** The trip is under way. */
    ON_TRIP(R.string.rm_state_on_trip, Icons.Rounded.Navigation, RmTone.BRAND),

    /** A cancel is requested, guided or claimed but not yet proven. */
    CANCELLING(R.string.rm_state_cancelling, Icons.Rounded.PendingActions, RmTone.WARN),

    /** The cancel is proven (evidence rungs 1–3). */
    CANCELLED(R.string.rm_state_cancelled, Icons.Rounded.CheckCircle, RmTone.GOOD),

    /** The driver cancelled on the rider. */
    DRIVER_CANCELLED(R.string.rm_state_driver_cancelled, Icons.Rounded.Cancel, RmTone.DANGER),

    /** The trip ended or moved to payment. */
    ENDED(R.string.rm_state_ended, Icons.Rounded.Flag, RmTone.NEUTRAL),

    /** The search expired or the app cancelled it: no ride here. */
    CLOSED(R.string.rm_state_closed, Icons.Rounded.Block, RmTone.NEUTRAL),
}

/** The rider-facing group of this state. */
val RideState.group: RideStateGroup
    get() = when (this) {
        RideState.SELECTED, RideState.OPENED, RideState.QUOTED -> RideStateGroup.NOT_BOOKED
        RideState.BOOK_REQUESTED, RideState.SEARCHING, RideState.SEARCHING_NET, RideState.ON_SELECT, RideState.INIT,
        RideState.CONFIRMED,
        -> RideStateGroup.SEARCHING
        RideState.MAYBE_ASSIGNED, RideState.MAYBE_CHANGED, RideState.MAYBE_NEW_DRIVER, RideState.CANCEL_UNCONFIRMED ->
            RideStateGroup.CHECK_APP
        RideState.ASSIGNED, RideState.ARRIVING -> RideStateGroup.DRIVER_COMING
        RideState.ON_TRIP -> RideStateGroup.ON_TRIP
        RideState.CANCEL_REQUESTED, RideState.CANCEL_GUIDED, RideState.USER_SAYS_CANCELLED, RideState.SOFT_CANCEL,
        RideState.CONFIRM_CANCEL,
        -> RideStateGroup.CANCELLING
        RideState.CANCEL_CONFIRMED -> RideStateGroup.CANCELLED
        RideState.DRIVER_CANCELLED -> RideStateGroup.DRIVER_CANCELLED
        RideState.ENDED, RideState.PAY_HANDOFF -> RideStateGroup.ENDED
        RideState.SEARCH_EXPIRED, RideState.PROVIDER_CANCELLED -> RideStateGroup.CLOSED
    }

/**
 * A leg's state at a glance: coloured pill **with icon and word** ("⏳ Searching", "🚗 Driver coming",
 * "✓ Cancelled", "⚠ Check app"). [text] overrides the group's word (e.g. "Captain coming" for apps that say
 * captain). [pulsing] gently breathes (reminders "2 still open"), unless animations are off.
 */
@Composable
fun StatusPill(
    group: RideStateGroup,
    modifier: Modifier = Modifier,
    text: String = stringResource(group.label),
    contentDescription: String = text,
    pulsing: Boolean = false,
) {
    val motion = RideMateTheme.motion
    val pulseModifier = if (pulsing && motion.enabled) {
        val transition = rememberInfiniteTransition(label = "pill-pulse")
        val alpha by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.55f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "pill-alpha",
        )
        Modifier.graphicsLayer { this.alpha = alpha }
    } else {
        Modifier
    }
    RmPill(
        text = text,
        tone = group.tone,
        icon = group.icon,
        contentDescription = contentDescription,
        modifier = modifier.then(pulseModifier).testTag(RmTags.STATUS_PILL),
    )
}

/** A [StatusPill] for a [RideState] (grouped with [RideState.group]). */
@Composable
fun StatusPill(
    state: RideState,
    modifier: Modifier = Modifier,
    text: String = stringResource(state.group.label),
    contentDescription: String = text,
    pulsing: Boolean = false,
) = StatusPill(state.group, modifier, text, contentDescription, pulsing)

@Preview(name = "Status pills", showBackground = true)
@Composable
private fun StatusPillPreview() {
    RideMateTheme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RideStateGroup.entries.forEach { StatusPill(it) }
            StatusPill(RideStateGroup.CANCELLING, text = "2 still open", pulsing = true)
        }
    }
}
