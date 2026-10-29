package app.ridemate.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.ridemate.R
import app.ridemate.core.model.Provider
import app.ridemate.core.model.QuoteStatus
import app.ridemate.core.model.VehicleClass
import app.ridemate.ui.RideMateTheme
import app.ridemate.ui.RmShapes
import app.ridemate.ui.RmSpacing

/**
 * The frame of every RideMate screen (§1.2 bottom-sheet flow): the `canvas` background, a slim top bar (back,
 * title, actions), an optional bold [header] area on the canvas (WhereToBar, RouteStack, a status hero), the
 * body — on a rounded `sheet` when [asSheet] — and an optional [bottomBar] holding the primary action in the
 * thumb zone. Handles system-bar insets (the app is edge-to-edge).
 *
 * @param title shown in the top bar and announced as a heading; null hides the title (e.g. Home).
 * @param scrollable the body scrolls vertically; pass false when the body holds its own lazy list.
 */
@Composable
fun RmScaffold(
    modifier: Modifier = Modifier,
    title: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    header: (@Composable ColumnScope.() -> Unit)? = null,
    bottomBar: (@Composable ColumnScope.() -> Unit)? = null,
    asSheet: Boolean = header != null,
    scrollable: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = RmSpacing.screen, vertical = RmSpacing.m),
    verticalSpacing: Dp = RmSpacing.cardGap,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = RideMateTheme.colors
    Scaffold(
        modifier = modifier.testTag(RmTags.SCAFFOLD),
        containerColor = colors.canvas,
        contentColor = colors.ink,
        topBar = { RmTopBar(title = title, onBack = onBack, actions = actions) },
        bottomBar = {
            if (bottomBar != null) {
                Surface(
                    color = if (asSheet) colors.sheet else colors.canvas,
                    contentColor = colors.ink,
                    modifier = Modifier.testTag(RmTags.BOTTOM_BAR),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.navigationBars)
                            .padding(horizontal = RmSpacing.screen, vertical = RmSpacing.m),
                        verticalArrangement = Arrangement.spacedBy(RmSpacing.s),
                        content = bottomBar,
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (header != null) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = RmSpacing.screen, vertical = RmSpacing.s),
                    verticalArrangement = Arrangement.spacedBy(RmSpacing.m),
                    content = header,
                )
            }
            val body: @Composable () -> Unit = {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                        .testTag(RmTags.SHEET_BODY)
                        .padding(contentPadding),
                    verticalArrangement = Arrangement.spacedBy(verticalSpacing),
                    content = content,
                )
            }
            if (asSheet) {
                Surface(
                    color = colors.sheet,
                    contentColor = colors.ink,
                    shape = RmShapes.sheet,
                    shadowElevation = if (colors.isDark) 0.dp else 6.dp,
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(top = RmSpacing.s),
                ) { body() }
            } else {
                Box(Modifier.fillMaxWidth().weight(1f)) { body() }
            }
        }
    }
}

/**
 * The slim top bar: a 48 dp back button (TalkBack "Back"), the [title] as a heading, and [actions] (48 dp icon
 * buttons). Pads itself below the status bar.
 */
@Composable
fun RmTopBar(
    title: String?,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .heightIn(min = 64.dp)
            .padding(horizontal = RmSpacing.xs)
            .testTag(RmTags.TOP_BAR),
    ) {
        if (onBack != null) {
            RmIconButton(
                icon = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.ui_back),
                onClick = onBack,
                modifier = Modifier.testTag(RmTags.BACK),
            )
        } else {
            Spacer(Modifier.width(RmSpacing.l))
        }
        if (title != null) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = RideMateTheme.colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = if (onBack != null) RmSpacing.xs else 0.dp)
                    .semantics { heading() },
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

@Preview(name = "Scaffold with sheet", showBackground = true, heightDp = 720)
@Composable
private fun RmScaffoldPreview() {
    RideMateTheme {
        RmScaffold(
            title = null,
            actions = { RmIconButton(Icons.Rounded.Settings, "Settings", {}) },
            header = { RouteStack(pickup = "Current location", drop = "LGBI Airport", compact = true) },
            bottomBar = { BigPrimaryButton("Open Rapido", {}) },
        ) {
            RmSectionTitle("Auto")
            FareCard(FareQuote(Provider.RAPIDO, VehicleClass.AUTO, "Auto", QuoteStatus.VERIFIED, "₹96", "10:30 AM", etaMinutes = 3, cheapest = true))
            FareCard(FareQuote(Provider.UBER, VehicleClass.AUTO, "Uber Auto", QuoteStatus.VERIFIED, "₹112", "10:30 AM", etaMinutes = 5))
        }
    }
}
