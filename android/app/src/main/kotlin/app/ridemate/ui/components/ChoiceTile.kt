package app.ridemate.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.ridemate.core.model.Provider
import app.ridemate.core.model.VehicleClass
import app.ridemate.ui.RideMateTheme
import app.ridemate.ui.RmShapes
import app.ridemate.ui.RmSpacing

/**
 * A big selectable tile: an icon (or avatar) over a 1–2 word label, with a check mark when selected (state is
 * icon + border, never colour alone). [multiSelect] tiles are checkboxes ("Which apps?"); otherwise radio
 * buttons ("Ride type"). At least 48 dp; give tiles in a row `Modifier.weight(1f)`.
 *
 * @param caption optional one-line muted text under the label (e.g. "Not installed").
 */
@Composable
fun ChoiceTile(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    multiSelect: Boolean = true,
    caption: String? = null,
    icon: (@Composable () -> Unit)? = null,
) {
    val colors = RideMateTheme.colors
    val selection = if (multiSelect) {
        Modifier.toggleable(value = selected, enabled = enabled, role = Role.Checkbox, onValueChange = { onClick() })
    } else {
        Modifier.selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
    }
    val shape = RmShapes.tile
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = RmSpacing.minTouch, minHeight = 88.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .clip(shape)
            .background(if (selected) colors.brandContainer else colors.sheet)
            .border(BorderStroke(if (selected) 2.dp else 1.dp, if (selected) colors.brand else colors.line), shape)
            .then(selection)
            .semantics(mergeDescendants = true) {}
            .testTag(RmTags.CHOICE_TILE),
    ) {
        CompositionLocalProvider(LocalContentColor provides if (selected) colors.onBrandContainer else colors.ink) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(RmSpacing.s, Alignment.CenterVertically),
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 88.dp)
                    .padding(horizontal = RmSpacing.s, vertical = RmSpacing.m),
            ) {
                icon?.invoke()
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (caption != null) {
                    Text(
                        caption,
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.muted,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (selected) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(RmSpacing.s)
                    .size(20.dp)
                    .background(colors.brand, CircleShape)
                    .testTag(RmTags.CHOICE_CHECK),
            ) {
                Icon(Icons.Rounded.Check, contentDescription = null, tint = colors.onBrand, modifier = Modifier.size(14.dp))
            }
        }
    }
}

/** A [ChoiceTile] with a plain icon. */
@Composable
fun ChoiceTile(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    multiSelect: Boolean = true,
    caption: String? = null,
) {
    ChoiceTile(label, selected, onClick, modifier, enabled, multiSelect, caption) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp))
    }
}

/** "Which apps?": a provider's avatar and name as a multi-select tile. */
@Composable
fun ProviderChoiceTile(
    provider: Provider,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    name: String = provider.displayName,
    enabled: Boolean = true,
    caption: String? = null,
) {
    ChoiceTile(name, selected, onClick, modifier, enabled, multiSelect = true, caption = caption) {
        ProviderAvatar(provider, name = name, contentDescription = null)
    }
}

/** "Ride type": a vehicle glyph and its name as a single-choice tile. */
@Composable
fun VehicleChoiceTile(
    vehicleClass: VehicleClass,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = stringResource(vehicleClass.labelRes()),
    enabled: Boolean = true,
    multiSelect: Boolean = false,
) {
    ChoiceTile(label, selected, onClick, modifier, enabled, multiSelect = multiSelect) {
        VehicleIcon(vehicleClass, size = 32.dp, contentDescription = null)
    }
}

@Preview(name = "Choice tiles", showBackground = true)
@Composable
private fun ChoiceTilePreview() {
    RideMateTheme {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                var uber by remember { mutableStateOf(true) }
                ProviderChoiceTile(Provider.UBER, uber, { uber = !uber }, Modifier.weight(1f))
                ProviderChoiceTile(Provider.OLA, false, {}, Modifier.weight(1f))
                ProviderChoiceTile(Provider.RAPIDO, true, {}, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                VehicleChoiceTile(VehicleClass.BIKE, false, {}, Modifier.weight(1f))
                VehicleChoiceTile(VehicleClass.AUTO, true, {}, Modifier.weight(1f))
                VehicleChoiceTile(VehicleClass.CAB_ECONOMY, false, {}, Modifier.weight(1f))
            }
            ChoiceTile("Open network", true, {}, Icons.Rounded.Public)
        }
    }
}
