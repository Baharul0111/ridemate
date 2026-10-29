package app.ridemate.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.ridemate.R
import app.ridemate.ui.RideMateTheme
import app.ridemate.ui.RmShapes
import app.ridemate.ui.RmSpacing

/**
 * The one main action of a screen, in the thumb zone: full width, 56 dp tall, fully rounded, brand colour.
 * Labels are verbs of at most 3 words ("Compare 3 apps"). [destructive] paints it `danger` for loud alerts and
 * Erase. While [loading] it shows a spinner and ignores taps.
 *
 * @param contentDescription what TalkBack says instead of [text] when the text alone is ambiguous.
 */
@Composable
fun BigPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    loading: Boolean = false,
    destructive: Boolean = false,
    contentDescription: String? = null,
) {
    val colors = RideMateTheme.colors
    val container = if (destructive) colors.danger else colors.brand
    val content = if (destructive) colors.onDanger else colors.onBrand
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        shape = RmShapes.pill,
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
        contentPadding = PaddingValues(horizontal = RmSpacing.xxl, vertical = RmSpacing.m),
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = RmSpacing.bigButton)
            .testTag(RmTags.BIG_BUTTON)
            .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier),
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.5.dp, color = content)
            Spacer(Modifier.width(RmSpacing.m))
        } else if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(RmSpacing.s))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

/** How a [QuietButton] looks: a soft grey pill, an outlined pill, or text only. */
enum class QuietStyle { Tonal, Outline, Text }

/**
 * A secondary action ("Keep all", "Skip", "Read full notice"): quieter than [BigPrimaryButton], fully rounded,
 * at least 48 × 48 dp. [fillWidth] makes it full width (for a stacked pair of buttons).
 */
@Composable
fun QuietButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    contentDescription: String? = null,
    fillWidth: Boolean = false,
    style: QuietStyle = QuietStyle.Tonal,
) {
    val colors = RideMateTheme.colors
    val sized = modifier
        .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
        .defaultMinSize(minHeight = RmSpacing.minTouch, minWidth = RmSpacing.minTouch)
        .testTag(RmTags.QUIET_BUTTON)
        .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier)
    val padding = PaddingValues(horizontal = RmSpacing.xl, vertical = 10.dp)
    val label: @Composable () -> Unit = {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(RmSpacing.s))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
    when (style) {
        QuietStyle.Tonal -> Button(
            onClick = onClick,
            enabled = enabled,
            shape = RmShapes.pill,
            colors = ButtonDefaults.buttonColors(containerColor = colors.neutralContainer, contentColor = colors.ink),
            contentPadding = padding,
            modifier = sized,
        ) { label() }
        QuietStyle.Outline -> OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            shape = RmShapes.pill,
            border = BorderStroke(1.dp, if (enabled) MaterialTheme.colorScheme.outline else colors.line),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.ink),
            contentPadding = padding,
            modifier = sized,
        ) { label() }
        QuietStyle.Text -> TextButton(
            onClick = onClick,
            enabled = enabled,
            shape = RmShapes.pill,
            colors = ButtonDefaults.textButtonColors(contentColor = colors.brand),
            contentPadding = padding,
            modifier = sized,
        ) { label() }
    }
}

/**
 * A 48 dp round icon button ("Show me", refresh, map). [contentDescription] is required: an icon button must
 * always have a spoken label (goal 27). [tonal] gives it a soft grey circle; [containerColor] any other circle.
 */
@Composable
fun RmIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tonal: Boolean = false,
    tint: Color = Color.Unspecified,
    containerColor: Color = Color.Unspecified,
) {
    val colors = RideMateTheme.colors
    IconButton(
        onClick = onClick,
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = when {
                containerColor != Color.Unspecified -> containerColor
                tonal -> colors.neutralContainer
                else -> Color.Transparent
            },
            contentColor = if (tint != Color.Unspecified) tint else colors.ink,
        ),
        modifier = modifier.size(RmSpacing.minTouch).testTag(RmTags.ICON_BUTTON),
    ) {
        Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(24.dp))
    }
}

/** The ⓘ "Why?" button that opens an [InfoSheet] with the explanation, so no paragraph sits on the screen. */
@Composable
fun WhyButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String = stringResource(R.string.rm_why),
) {
    RmIconButton(
        icon = Icons.Rounded.Info,
        contentDescription = contentDescription,
        onClick = onClick,
        tint = RideMateTheme.colors.muted,
        modifier = modifier.testTag(RmTags.WHY_BUTTON),
    )
}

@Preview(name = "Buttons", showBackground = true)
@Composable
private fun ButtonsPreview() {
    RideMateTheme {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BigPrimaryButton("Compare 3 apps", {}, icon = Icons.AutoMirrored.Rounded.ArrowForward)
            BigPrimaryButton("Cancel others", {}, destructive = true)
            BigPrimaryButton("Reading", {}, loading = true)
            QuietButton("Keep all", {}, fillWidth = true)
            QuietButton("Read full notice", {}, style = QuietStyle.Outline)
            QuietButton("Skip", {}, style = QuietStyle.Text)
            RmIconButton(Icons.Rounded.Refresh, "Refresh", {}, tonal = true)
            WhyButton({})
        }
    }
}
