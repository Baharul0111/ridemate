package app.ridemate.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Policy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.ridemate.ui.RideMateTheme
import app.ridemate.ui.RmSpacing

/** Default padding of a list row inside an [RmListGroup]. */
val RmRowPadding = PaddingValues(horizontal = RmSpacing.l, vertical = 10.dp)

/**
 * A list row (Settings, My data, permission checklist): leading icon in a tonal circle, a 1–3 word [title], an
 * optional muted [summary] and [value], and a chevron when it opens something. [destructive] paints the title
 * and icon `danger` (Erase). One TalkBack node; ≥ 56 dp.
 *
 * @param trailing replaces the chevron/value with custom content (e.g. a "✓ Allowed" pill or an Allow button).
 */
@Composable
fun RmListRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconTone: RmTone = RmTone.NEUTRAL,
    summary: String? = null,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    destructive: Boolean = false,
    showChevron: Boolean = onClick != null,
    contentPadding: PaddingValues = RmRowPadding,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = RideMateTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = RmSpacing.row)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) {}
            .testTag(RmTags.LIST_ROW)
            .padding(contentPadding),
    ) {
        if (icon != null) {
            IconWell(icon, tone = if (destructive) RmTone.DANGER else iconTone, size = 36.dp)
            Spacer(Modifier.width(RmSpacing.l))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (destructive) colors.danger else colors.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (summary != null) {
                Text(summary, style = MaterialTheme.typography.bodyMedium, color = colors.muted)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(RmSpacing.m))
            trailing()
        } else {
            if (value != null) {
                Spacer(Modifier.width(RmSpacing.m))
                Text(
                    value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.muted,
                    textAlign = TextAlign.End,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 160.dp),
                )
            }
            if (showChevron) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = colors.muted)
            }
        }
    }
}

/** A list row with a switch; the whole row toggles and reads as one switch with its title. */
@Composable
fun RmToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    summary: String? = null,
    enabled: Boolean = true,
    contentPadding: PaddingValues = RmRowPadding,
) {
    val colors = RideMateTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = RmSpacing.row)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .testTag(RmTags.LIST_ROW)
            .padding(contentPadding),
    ) {
        if (icon != null) {
            IconWell(icon, size = 36.dp)
            Spacer(Modifier.width(RmSpacing.l))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (summary != null) Text(summary, style = MaterialTheme.typography.bodyMedium, color = colors.muted)
        }
        Spacer(Modifier.width(RmSpacing.m))
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = colors.brand, checkedThumbColor = colors.onBrand),
        )
    }
}

/** One option of a single-choice list; the whole row selects. */
@Composable
fun RmRadioRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    summary: String? = null,
    contentPadding: PaddingValues = RmRowPadding,
) {
    val colors = RideMateTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = RmSpacing.row)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .testTag(RmTags.LIST_ROW)
            .padding(contentPadding),
    ) {
        if (icon != null) {
            IconWell(icon, size = 36.dp)
            Spacer(Modifier.width(RmSpacing.l))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (summary != null) Text(summary, style = MaterialTheme.typography.bodyMedium, color = colors.muted)
        }
        Spacer(Modifier.width(RmSpacing.m))
        RadioButton(selected = selected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = colors.brand))
    }
}

@Preview(name = "List rows", showBackground = true)
@Composable
private fun ListRowsPreview() {
    RideMateTheme {
        Column(Modifier.padding(20.dp)) {
            RmSectionTitle("Privacy")
            RmListGroup {
                RmListRow("Privacy notice", icon = Icons.Rounded.Policy, onClick = {})
                RmListDivider()
                RmToggleRow("Ride alerts", checked = true, onCheckedChange = {}, icon = Icons.Rounded.Notifications)
                RmListDivider()
                RmListRow("Language", icon = Icons.Rounded.Language, value = "हिन्दी", onClick = {})
                RmListDivider()
                RmListRow("Erase", icon = Icons.Rounded.DeleteForever, destructive = true, onClick = {})
            }
            RmSectionTitle("Language")
            RmListGroup {
                RmRadioRow("English", selected = true, onClick = {})
                RmListDivider()
                RmRadioRow("हिन्दी", selected = false, onClick = {})
            }
        }
    }
}
