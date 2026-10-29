package app.ridemate.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.ridemate.R
import app.ridemate.ui.RideMateTheme
import app.ridemate.ui.RmShapes
import app.ridemate.ui.RmSpacing

/**
 * A one-line status strip ("Offline · Retry", "AI back at 1:30 PM"): tone icon + short text + one action. Shown
 * only when something is not normal. Announced politely by TalkBack when it appears. Errors always carry the
 * action that fixes them (goal 27).
 */
@Composable
fun InlineBanner(
    text: String,
    modifier: Modifier = Modifier,
    tone: RmTone = RmTone.NEUTRAL,
    icon: ImageVector = tone.icon(),
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
) {
    val colors = tone.colors()
    Surface(
        color = colors.container,
        contentColor = colors.content,
        shape = RmShapes.banner,
        modifier = modifier.fillMaxWidth().testTag(RmTags.BANNER),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.heightIn(min = RmSpacing.minTouch + 8.dp).padding(start = RmSpacing.l, end = RmSpacing.xs),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = RmSpacing.s)
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(RmSpacing.m))
                Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 3)
            }
            if (actionLabel != null && onAction != null) {
                TextButton(
                    onClick = onAction,
                    shape = RmShapes.pill,
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.content),
                    modifier = Modifier.heightIn(min = RmSpacing.minTouch).testTag(RmTags.BANNER_ACTION),
                ) {
                    Text(actionLabel, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                }
            }
            if (onDismiss != null) {
                RmIconButton(Icons.Rounded.Close, stringResource(R.string.rm_dismiss), onDismiss, tint = colors.content)
            }
        }
    }
}

@Preview(name = "Inline banners", showBackground = true)
@Composable
private fun InlineBannerPreview() {
    RideMateTheme {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            InlineBanner("Offline", tone = RmTone.WARN, icon = Icons.Rounded.WifiOff, actionLabel = "Retry", onAction = {})
            InlineBanner("AI back at 1:30 PM", tone = RmTone.NEUTRAL)
            InlineBanner("Demo: made-up apps", tone = RmTone.DEMO, onDismiss = {})
        }
    }
}
