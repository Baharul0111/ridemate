package app.ridemate.precheck

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.ridemate.R

/**
 * One fix-it card: an icon *and* a severity word (never colour alone), a title, what to do next, and one button per
 * fix. The comparison area reuses it for "no fares found" / "no ONDC results".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PrecheckCard(issue: PrecheckIssue, onFix: (FixAction) -> Unit, modifier: Modifier = Modifier) {
    val severityText = stringResource(PrecheckMessages.severityLabel(issue.severity))
    val title = stringResource(PrecheckMessages.title(issue.kind))
    val colors = when (issue.severity) {
        IssueSeverity.BLOCKING -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        IssueSeverity.WARNING -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        IssueSeverity.INFO -> CardDefaults.cardColors()
    }
    Card(modifier = modifier.fillMaxWidth(), colors = colors) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    imageVector = when (issue.severity) {
                        IssueSeverity.BLOCKING -> Icons.Filled.Error
                        IssueSeverity.WARNING -> Icons.Filled.Warning
                        IssueSeverity.INFO -> Icons.Filled.Info
                    },
                    contentDescription = null,
                )
                Text(severityText, style = MaterialTheme.typography.labelLarge)
            }
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(stringResource(PrecheckMessages.body(issue.kind)), style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                issue.fixes.forEachIndexed { index, fix ->
                    val label = stringResource(PrecheckMessages.fixLabel(fix))
                    val description = stringResource(R.string.precheck_fix_description, label, title)
                    val buttonModifier = Modifier
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = description }
                    if (index == 0) {
                        FilledTonalButton(onClick = { onFix(fix) }, modifier = buttonModifier) { Text(label) }
                    } else {
                        OutlinedButton(onClick = { onFix(fix) }, modifier = buttonModifier) { Text(label) }
                    }
                }
            }
        }
    }
}

