package app.ridemate.ui.components

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ridemate.R
import app.ridemate.core.model.Provider
import app.ridemate.core.model.VehicleClass
import app.ridemate.ui.RideMateTheme
import app.ridemate.ui.RmShapes

/**
 * The letter on a provider's avatar: U / O / R for the ride apps, N for the open network (ONDC), K / P / T for
 * the demo companies. Letters only: RideMate never shows another company's logo or brand colours.
 */
val Provider.initial: String
    get() = when (this) {
        Provider.UBER -> "U"
        Provider.OLA -> "O"
        Provider.RAPIDO -> "R"
        Provider.ONDC -> "N"
        Provider.DEMO_KESTREL -> "K"
        Provider.DEMO_PEPPER -> "P"
        Provider.DEMO_TAMARIND -> "T"
    }

/**
 * What TalkBack says for this provider: its [name], plus "demo" for the made-up demo companies (goal 19), whose
 * DEMO corner tag is only visual. Use it wherever a provider's avatar is hidden from TalkBack inside a bigger node.
 */
@Composable
fun Provider.spokenName(name: String = displayName): String =
    if (isDemo) stringResource(R.string.rm_provider_demo_cd, name) else name

/** A neutral tonal colour pair for an avatar. Deliberately muted hues, none of them a ride app's brand colour. */
@Immutable
data class AvatarColors(val container: Color, val content: Color)

/** The avatar colours of this provider in a light or dark theme. */
fun Provider.avatarColors(dark: Boolean): AvatarColors {
    val (light, deep) = when (this) {
        Provider.UBER -> Color(0xFFDDE3EA) to Color(0xFF2C3A48) // slate
        Provider.OLA -> Color(0xFFE5E0F3) to Color(0xFF3D3458) // lavender
        Provider.RAPIDO -> Color(0xFFF2E1DC) to Color(0xFF5B3A31) // clay
        Provider.ONDC -> Color(0xFFDAEBF3) to Color(0xFF1F4658) // sky
        Provider.DEMO_KESTREL -> Color(0xFFDFEBDD) to Color(0xFF2F4A2C) // sage
        Provider.DEMO_PEPPER -> Color(0xFFF1E6D6) to Color(0xFF554027) // sand
        Provider.DEMO_TAMARIND -> Color(0xFFEFDFE8) to Color(0xFF553146) // mauve
    }
    return if (dark) AvatarColors(deep, light) else AvatarColors(light, deep)
}

/**
 * A provider's 40 dp round avatar: its [initial] on a neutral tonal colour. Demo providers carry a small "DEMO"
 * corner tag (goal 19). TalkBack reads [contentDescription] (default: the provider's name, plus "demo").
 */
@Composable
fun ProviderAvatar(
    provider: Provider,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    name: String = provider.displayName,
    contentDescription: String? = provider.spokenName(name),
) {
    val colors = provider.avatarColors(RideMateTheme.colors.isDark)
    // The initial is a glyph sized to its circle (like an icon): it doesn't grow with the phone's font size, so it
    // never overflows the 40 dp circle at 200 %. The provider's name is always in words next to it or spoken.
    val letterSize = with(LocalDensity.current) { (size * 0.42f).toSp() }
    Box(
        modifier = modifier
            .size(size)
            .testTag(RmTags.PROVIDER_AVATAR)
            .then(if (contentDescription != null) Modifier.semantics(mergeDescendants = true) { this.contentDescription = contentDescription } else Modifier),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(size).background(colors.container, CircleShape),
        ) {
            Text(
                provider.initial,
                color = colors.content,
                fontWeight = FontWeight.Bold,
                fontSize = letterSize,
                lineHeight = letterSize * 1.2f,
                maxLines = 1,
                softWrap = false,
                // The letter is decoration: TalkBack reads the provider's name from the avatar itself.
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
        if (provider.isDemo) {
            DemoCornerTag(Modifier.align(Alignment.BottomEnd).offset(x = 6.dp, y = 4.dp))
        }
    }
}

/**
 * The small "DEMO" tag on a demo avatar's corner. It may grow past the 40 dp avatar (wrapContentSize unbounded)
 * so the word is never clipped when the phone's text is 200 %; it grows leftwards over the decorative initial.
 */
@Composable
private fun DemoCornerTag(modifier: Modifier = Modifier) {
    val c = RideMateTheme.colors
    Surface(
        color = c.demoContainer,
        contentColor = c.onDemoContainer,
        shape = RmShapes.pill,
        modifier = modifier.wrapContentSize(Alignment.BottomEnd, unbounded = true).testTag(RmTags.DEMO_TAG),
    ) {
        Text(
            stringResource(R.string.ui_demo_badge),
            fontSize = 8.sp,
            lineHeight = 10.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp).clearAndSetSemantics {},
        )
    }
}

/**
 * The "DEMO" label on everything that uses made-up sample screens (goal 19): flask icon and the word, read by
 * TalkBack as "Demo: made-up sample screens".
 */
@Composable
fun DemoTag(modifier: Modifier = Modifier) {
    val spoken = stringResource(R.string.ui_demo_badge_cd)
    val c = RideMateTheme.colors
    Surface(
        color = c.demoContainer,
        contentColor = c.onDemoContainer,
        shape = RmShapes.pill,
        modifier = modifier
            .testTag(RmTags.DEMO_TAG)
            .semantics(mergeDescendants = true) { contentDescription = spoken },
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Science, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.ui_demo_badge), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        }
    }
}

/** The in-house side-view glyph of a vehicle class (bike, auto-rickshaw, cab, cab XL). */
@DrawableRes
fun VehicleClass.iconRes(): Int = when (this) {
    VehicleClass.BIKE -> R.drawable.ic_rm_bike
    VehicleClass.AUTO -> R.drawable.ic_rm_auto
    VehicleClass.CAB_ECONOMY, VehicleClass.CAB_PREMIUM, VehicleClass.OTHER -> R.drawable.ic_rm_cab
    VehicleClass.CAB_XL -> R.drawable.ic_rm_cab_xl
}

/** The short label of a vehicle class ("Bike", "Auto", "Cab", …). */
@StringRes
fun VehicleClass.labelRes(): Int = when (this) {
    VehicleClass.BIKE -> R.string.rm_vehicle_bike
    VehicleClass.AUTO -> R.string.rm_vehicle_auto
    VehicleClass.CAB_ECONOMY -> R.string.rm_vehicle_cab
    VehicleClass.CAB_PREMIUM -> R.string.rm_vehicle_cab_premium
    VehicleClass.CAB_XL -> R.string.rm_vehicle_cab_xl
    VehicleClass.OTHER -> R.string.rm_vehicle_other
}

/**
 * A vehicle glyph (24 dp grid) tinted with the current content colour. TalkBack reads the vehicle name; pass
 * `contentDescription = null` when a text label sits right next to it.
 */
@Composable
fun VehicleIcon(
    vehicleClass: VehicleClass,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    tint: Color = LocalContentColor.current,
    contentDescription: String? = stringResource(vehicleClass.labelRes()),
) {
    Icon(
        painter = painterResource(vehicleClass.iconRes()),
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier.size(size).testTag(RmTags.VEHICLE_ICON),
    )
}

@Preview(name = "Avatars and vehicles", showBackground = true)
@Composable
private fun AvatarsPreview() {
    RideMateTheme {
        androidx.compose.foundation.layout.Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { Provider.entries.forEach { ProviderAvatar(it) } }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(VehicleClass.BIKE, VehicleClass.AUTO, VehicleClass.CAB_ECONOMY, VehicleClass.CAB_XL).forEach { VehicleIcon(it, size = 40.dp) }
            }
            DemoTag()
        }
    }
}
