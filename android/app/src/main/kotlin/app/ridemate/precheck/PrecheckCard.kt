package app.ridemate.precheck

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import app.ridemate.R
import app.ridemate.ui.RmSpacing
import app.ridemate.ui.components.IconWell
import app.ridemate.ui.components.InfoSheet
import app.ridemate.ui.components.QuietButton
import app.ridemate.ui.components.QuietStyle
import app.ridemate.ui.components.RmCard
import app.ridemate.ui.components.RmTone
import app.ridemate.ui.components.WhyButton
import app.ridemate.ui.components.colors

/** Stable test tags of the compact fix-it card. */
object PrecheckTags {
    const val CARD = "precheck_card"
    const val WHY = "precheck_why"
}

/**
 * One compact fix-it card: a severity icon *and* word (never colour alone), a short title and one button per fix.
 * What it means and what to do sits behind the ⓘ (InfoSheet), never as a paragraph on the screen. The comparison
 * area reuses it for "no fares found" / "no ONDC results". With [restrictedSettings] (sideloaded on Android 13+) the
 * notification-access and overlay cards also say how to "Allow restricted settings" and offer "Open App info",
 * because their switch is greyed out until then.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PrecheckCard(issue: PrecheckIssue, onFix: (FixAction) -> Unit, modifier: Modifier = Modifier, restrictedSettings: Boolean = false) {
    val severityText = stringResource(PrecheckMessages.severityLabel(issue.severity))
    val title = stringResource(PrecheckMessages.title(issue.kind))
    val restricted = restrictedSettings && issue.kind in PrecheckMessages.RESTRICTED_SWITCHES
    val fixes = if (restricted) (issue.fixes + FixAction.OPEN_APP_INFO).distinct() else issue.fixes
    val tone = issue.severity.tone()
    val icon = issue.severity.icon()
    var showWhy by rememberSaveable(issue.kind) { mutableStateOf(false) }
    RmCard(modifier = modifier.testTag(PrecheckTags.CARD), padding = RmSpacing.m) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RmSpacing.m)) {
            IconWell(icon, tone = tone)
            Column(Modifier.weight(1f)) {
                Text(
                    severityText,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (tone == RmTone.NEUTRAL) MaterialTheme.colorScheme.onSurfaceVariant else tone.colors().solid,
                )
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
            }
            WhyButton(
                { showWhy = true },
                modifier = Modifier.testTag(PrecheckTags.WHY),
                contentDescription = stringResource(R.string.precheck_why_cd, title),
            )
        }
        if (restricted) {
            Text(
                stringResource(R.string.precheck_restricted_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(RmSpacing.s), verticalArrangement = Arrangement.spacedBy(RmSpacing.s)) {
            fixes.forEachIndexed { index, fix ->
                val label = stringResource(PrecheckMessages.fixLabel(fix))
                QuietButton(
                    text = label,
                    onClick = { onFix(fix) },
                    contentDescription = stringResource(R.string.precheck_fix_description, label, title),
                    style = if (index == 0) QuietStyle.Tonal else QuietStyle.Outline,
                )
            }
        }
    }
    if (showWhy) {
        val first = issue.fixes.firstOrNull()
        InfoSheet(
            onDismiss = { showWhy = false },
            title = title,
            body = stringResource(PrecheckMessages.body(issue.kind)) +
                if (restricted) "\n\n" + stringResource(R.string.onb_restricted_steps) else "",
            icon = icon,
            tone = tone,
            primaryLabel = first?.let { stringResource(PrecheckMessages.fixLabel(it)) },
            onPrimary = first?.let {
                {
                    showWhy = false
                    onFix(it)
                }
            },
        )
    }
}

private fun IssueSeverity.tone(): RmTone = when (this) {
    IssueSeverity.BLOCKING -> RmTone.DANGER
    IssueSeverity.WARNING -> RmTone.WARN
    IssueSeverity.INFO -> RmTone.NEUTRAL
}

private fun IssueSeverity.icon(): ImageVector = when (this) {
    IssueSeverity.BLOCKING -> Icons.Rounded.Error
    IssueSeverity.WARNING -> Icons.Rounded.Warning
    IssueSeverity.INFO -> Icons.Rounded.Info
}
