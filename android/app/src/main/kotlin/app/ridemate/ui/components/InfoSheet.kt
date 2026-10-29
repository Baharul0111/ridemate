package app.ridemate.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.ridemate.R
import app.ridemate.ui.RideMateTheme
import app.ridemate.ui.RmShapes
import app.ridemate.ui.RmSpacing

/**
 * The "ⓘ Why?" bottom sheet: explanations and legally required text (privacy notice, Erase wording, fair-play
 * warning) live here instead of on the main screen. A Material 3 [ModalBottomSheet] with 28 dp top corners that
 * springs up; the sheet is fully expanded straight away (no half state) so long text is reachable.
 *
 * Use [tone] [RmTone.DANGER] with [primaryDestructive] for loud alerts ("red sheet with one clear action").
 *
 * @param content extra content under [body] (lists, a checkbox, an illustration).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoSheet(
    onDismiss: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    icon: ImageVector? = Icons.Rounded.Info,
    tone: RmTone = RmTone.BRAND,
    primaryLabel: String? = null,
    onPrimary: (() -> Unit)? = null,
    primaryDestructive: Boolean = false,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val colors = RideMateTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RmShapes.sheet,
        containerColor = colors.sheet,
        contentColor = colors.ink,
        modifier = modifier.testTag(RmTags.INFO_SHEET),
    ) {
        InfoSheetContent(
            title = title,
            onClose = onDismiss,
            body = body,
            icon = icon,
            tone = tone,
            primaryLabel = primaryLabel,
            onPrimary = onPrimary,
            primaryDestructive = primaryDestructive,
            secondaryLabel = secondaryLabel,
            onSecondary = onSecondary,
            content = content,
        )
    }
}

/**
 * The inside of an [InfoSheet] (icon, title, close button, scrolling body, up to two actions), usable on its own
 * where a sheet window isn't wanted (e.g. a full-screen notice or a test).
 */
@Composable
fun InfoSheetContent(
    title: String,
    onClose: (() -> Unit)?,
    modifier: Modifier = Modifier,
    body: String? = null,
    icon: ImageVector? = Icons.Rounded.Info,
    tone: RmTone = RmTone.BRAND,
    primaryLabel: String? = null,
    onPrimary: (() -> Unit)? = null,
    primaryDestructive: Boolean = false,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val colors = RideMateTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = RmSpacing.xxl, end = RmSpacing.l, bottom = RmSpacing.xxl),
        verticalArrangement = Arrangement.spacedBy(RmSpacing.l),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                IconWell(icon, tone = tone, size = 44.dp)
                Spacer(Modifier.width(RmSpacing.m))
            }
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = colors.ink,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            if (onClose != null) {
                RmIconButton(
                    icon = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.rm_close),
                    onClick = onClose,
                    modifier = Modifier.testTag(RmTags.INFO_SHEET_CLOSE),
                )
            }
        }
        Column(Modifier.padding(end = RmSpacing.s), verticalArrangement = Arrangement.spacedBy(RmSpacing.m)) {
            if (body != null) {
                Text(body, style = MaterialTheme.typography.bodyLarge, color = colors.ink)
            }
            content()
            if (primaryLabel != null && onPrimary != null) {
                Spacer(Modifier.width(RmSpacing.xs))
                BigPrimaryButton(primaryLabel, onPrimary, destructive = primaryDestructive)
            }
            if (secondaryLabel != null && onSecondary != null) {
                QuietButton(secondaryLabel, onSecondary, fillWidth = true, style = QuietStyle.Text)
            }
        }
    }
}

@Preview(name = "Info sheet content", showBackground = true)
@Composable
private fun InfoSheetPreview() {
    RideMateTheme {
        InfoSheetContent(
            title = "Why notifications?",
            onClose = {},
            body = "RideMate reads ride-app alerts to tell you when a driver is found, so you can cancel the others in time.",
            primaryLabel = "Allow",
            onPrimary = {},
            secondaryLabel = "Not now",
            onSecondary = {},
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}
