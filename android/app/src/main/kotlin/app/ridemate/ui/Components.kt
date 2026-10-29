package app.ridemate.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.RemoveCircle
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.ridemate.ui.components.BigPrimaryButton
import app.ridemate.ui.components.DemoTag
import app.ridemate.ui.components.IconWell
import app.ridemate.ui.components.QuietButton
import app.ridemate.ui.components.QuietStyle
import app.ridemate.ui.components.RmCard
import app.ridemate.ui.components.RmListRow
import app.ridemate.ui.components.RmRadioRow
import app.ridemate.ui.components.RmScaffold
import app.ridemate.ui.components.RmSectionTitle
import app.ridemate.ui.components.RmTags
import app.ridemate.ui.components.RmToggleRow
import app.ridemate.ui.components.RmTone
import app.ridemate.ui.components.colors
import androidx.compose.ui.platform.testTag

/*
 * Legacy UI kit, kept so every existing screen keeps compiling and instantly renders in the new design
 * (docs/design/ui-redesign.md). Each old component is now a thin wrapper over app.ridemate.ui.components and is
 * @Deprecated: the screen redesign replaces the calls one screen at a time. Semantics (spoken labels, roles,
 * touch targets) are unchanged, so existing tests and TalkBack users see no difference.
 */

/** Minimum touch target (goal 27). Same as [RmSpacing.minTouch]. */
val MinTouchTarget = RmSpacing.minTouch

/** How a status reads; each tone has its own icon so state never depends on colour alone. */
enum class ChipTone { GOOD, WARNING, BAD, NEUTRAL, DEMO }

/** The design-system tone of this legacy tone. */
fun ChipTone.toRmTone(): RmTone = when (this) {
    ChipTone.GOOD -> RmTone.GOOD
    ChipTone.WARNING -> RmTone.WARN
    ChipTone.BAD -> RmTone.DANGER
    ChipTone.NEUTRAL -> RmTone.NEUTRAL
    ChipTone.DEMO -> RmTone.DEMO
}

/** The icon shown for [tone] (a distinct shape per tone). */
fun ChipTone.icon(): ImageVector = when (this) {
    ChipTone.GOOD -> Icons.Rounded.CheckCircle
    ChipTone.WARNING -> Icons.Rounded.WarningAmber
    ChipTone.BAD -> Icons.Rounded.Error
    ChipTone.NEUTRAL -> Icons.Rounded.RemoveCircle
    ChipTone.DEMO -> Icons.Rounded.Science
}

/**
 * A status shown with an icon **and** words (e.g. "Allowed", "Off"). Screen readers read [text] once; the icon is
 * decorative. [label] (optional) is read first, e.g. "Notification access: Allowed".
 */
@Deprecated("Use RmPill or StatusPill (app.ridemate.ui.components)")
@Composable
fun StatusChip(text: String, tone: ChipTone, modifier: Modifier = Modifier, label: String? = null) {
    val colors = tone.toRmTone().colors()
    val spoken = if (label != null) "$label: $text" else text
    Surface(
        color = colors.container,
        contentColor = colors.content,
        shape = RmShapes.pill,
        modifier = modifier
            .testTag(RmTags.PILL)
            .semantics(mergeDescendants = true) { contentDescription = spoken },
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(tone.icon(), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            if (label != null) {
                Text("$label: ", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Normal)
            }
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** The main action of a screen: full width, 56 dp tall, fully rounded. */
@Deprecated(
    "Use BigPrimaryButton (app.ridemate.ui.components)",
    ReplaceWith(
        "BigPrimaryButton(text, onClick, modifier, enabled, icon, contentDescription = contentDescription)",
        "app.ridemate.ui.components.BigPrimaryButton",
    ),
)
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    contentDescription: String? = null,
) = BigPrimaryButton(text = text, onClick = onClick, modifier = modifier, enabled = enabled, icon = icon, contentDescription = contentDescription)

/** A secondary action: soft grey pill, full width, at least 48 dp tall. */
@Deprecated(
    "Use QuietButton (app.ridemate.ui.components)",
    ReplaceWith("QuietButton(text, onClick, modifier, enabled, icon, fillWidth = true)", "app.ridemate.ui.components.QuietButton"),
)
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) = QuietButton(text = text, onClick = onClick, modifier = modifier, enabled = enabled, icon = icon, fillWidth = true)

/** A low-emphasis action (e.g. "Not now"), at least 48 dp tall. */
@Deprecated(
    "Use QuietButton(style = QuietStyle.Text) (app.ridemate.ui.components)",
    ReplaceWith("QuietButton(text, onClick, modifier, enabled, style = QuietStyle.Text)", "app.ridemate.ui.components.QuietButton"),
)
@Composable
fun TextAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) =
    QuietButton(text = text, onClick = onClick, modifier = modifier, enabled = enabled, style = QuietStyle.Text)

/** A card with a toned icon, a title and a body; extra content (buttons) goes in [content]. */
@Deprecated("Use RmCard / InlineBanner (app.ridemate.ui.components); long text belongs in an InfoSheet")
@Composable
fun InfoCard(
    title: String,
    body: String?,
    modifier: Modifier = Modifier,
    tone: ChipTone = ChipTone.NEUTRAL,
    icon: ImageVector = if (tone == ChipTone.NEUTRAL) Icons.Rounded.Info else tone.icon(),
    content: @Composable ColumnScope.() -> Unit = {},
) = LegacyInfoCard(title, body, modifier, tone, icon, content)

@Composable
private fun LegacyInfoCard(
    title: String,
    body: String?,
    modifier: Modifier,
    tone: ChipTone,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit,
) {
    val rmTone = tone.toRmTone()
    RmCard(modifier = modifier, tone = rmTone) {
        Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier.semantics(mergeDescendants = true) {},
        ) {
            IconWell(icon, tone = if (rmTone == RmTone.NEUTRAL) RmTone.BRAND else rmTone, size = 36.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (body != null) Text(body, style = MaterialTheme.typography.bodyMedium)
            }
        }
        content()
    }
}

/**
 * A problem with its fix: what is wrong, why it matters, and one button that fixes it (goal 27: errors say what
 * to do next).
 */
@Deprecated("Use InlineBanner with an action, or RmCard + BigPrimaryButton (app.ridemate.ui.components)")
@Composable
fun FixItCard(
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    tone: ChipTone = ChipTone.WARNING,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    LegacyInfoCard(
        title = title,
        body = body,
        modifier = modifier,
        tone = tone,
        icon = if (tone == ChipTone.BAD) Icons.Rounded.Error else Icons.Rounded.Build,
    ) {
        BigPrimaryButton(text = actionLabel, onClick = onAction)
        if (secondaryLabel != null && onSecondary != null) {
            QuietButton(text = secondaryLabel, onClick = onSecondary, style = QuietStyle.Text)
        }
    }
}

/** The "DEMO" label on everything that uses made-up sample screens (goal 19). */
@Deprecated("Use DemoTag (app.ridemate.ui.components)", ReplaceWith("DemoTag(modifier)", "app.ridemate.ui.components.DemoTag"))
@Composable
fun DemoBadge(modifier: Modifier = Modifier) = DemoTag(modifier)

/** A section title, announced as a heading. */
@Deprecated("Use RmSectionTitle (app.ridemate.ui.components)", ReplaceWith("RmSectionTitle(text, modifier)", "app.ridemate.ui.components.RmSectionTitle"))
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) = RmSectionTitle(text, modifier)

/** Rows outside an RmListGroup keep the old flush alignment with the screen's gutter. */
private val LegacyRowPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)

/** A tappable row that opens something (≥ 56 dp, one merged node for screen readers). */
@Deprecated("Use RmListRow inside an RmListGroup (app.ridemate.ui.components)")
@Composable
fun NavRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    icon: ImageVector? = null,
) = RmListRow(title = title, modifier = modifier, icon = icon, summary = summary, onClick = onClick, contentPadding = LegacyRowPadding)

/** A row with a switch; the whole row toggles and reads as one switch with its title. */
@Deprecated("Use RmToggleRow (app.ridemate.ui.components)")
@Composable
fun ToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
) = RmToggleRow(
    title = title,
    checked = checked,
    onCheckedChange = onCheckedChange,
    modifier = modifier,
    summary = summary,
    enabled = enabled,
    contentPadding = LegacyRowPadding,
)

/** One option of a single-choice list; the whole row selects. */
@Deprecated("Use RmRadioRow (app.ridemate.ui.components)")
@Composable
fun RadioRow(title: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) =
    RmRadioRow(title = title, selected = selected, onClick = onClick, modifier = modifier, contentPadding = LegacyRowPadding)

/** A label/value pair (e.g. "Version" / "1.0.0"), read as one node. */
@Deprecated("Use RmListRow(title, value = …) (app.ridemate.ui.components)")
@Composable
fun KeyValueRow(label: String, value: String, modifier: Modifier = Modifier, valueStyle: TextStyle? = null) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp)
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = RideMateTheme.colors.muted)
        Text(value, style = valueStyle ?: MaterialTheme.typography.bodyLarge, color = RideMateTheme.colors.ink)
    }
}

/**
 * A screen with a top bar ([title], back arrow when [onBack] is set) and a scrolling column body with the
 * design's 20 dp gutters, on the `canvas` colour.
 */
@Deprecated("Use RmScaffold (app.ridemate.ui.components)")
@Composable
fun ScreenScaffold(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit = {},
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) = RmScaffold(
    modifier = modifier,
    title = title,
    onBack = onBack,
    actions = { actions() },
    scrollable = scrollable,
    contentPadding = PaddingValues(horizontal = RmSpacing.screen, vertical = RmSpacing.s),
    content = content,
)
