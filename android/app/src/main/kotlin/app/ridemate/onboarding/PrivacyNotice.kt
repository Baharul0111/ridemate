package app.ridemate.onboarding

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.ridemate.BuildConfig
import app.ridemate.R
import app.ridemate.ui.components.IconWell
import app.ridemate.ui.components.RmCard
import app.ridemate.ui.components.RmTone

/**
 * The DPDP privacy notice in plain language (docs/privacy-notice.md, version [OnboardingFlow.NOTICE_VERSION]),
 * in English and Hindi. Shown at onboarding and from Settings → Privacy notice.
 */
object PrivacyNotice {
    /** (heading, body) of each section, in order. Bodies that take the privacy email have [EMAIL_SECTIONS]. */
    val SECTIONS: List<Pair<Int, Int>> = listOf(
        R.string.onb_notice_who_title to R.string.onb_notice_who_body,
        R.string.onb_notice_adults_title to R.string.onb_notice_adults_body,
        R.string.onb_notice_does_title to R.string.onb_notice_does_body,
        R.string.onb_notice_data_title to R.string.onb_notice_data_body,
        R.string.onb_notice_masking_title to R.string.onb_notice_masking_body,
        R.string.onb_notice_where_title to R.string.onb_notice_where_body,
        R.string.onb_notice_consent_title to R.string.onb_notice_consent_body,
        R.string.onb_notice_rights_title to R.string.onb_notice_rights_body,
        R.string.onb_notice_erase_title to R.string.onb_notice_erase_body,
        R.string.onb_notice_contact_title to R.string.onb_notice_contact_body,
    )

    /** Bodies formatted with the privacy email (%1$s). */
    val EMAIL_SECTIONS: Set<Int> = setOf(
        R.string.onb_notice_who_body,
        R.string.onb_notice_consent_body,
        R.string.onb_notice_rights_body,
        R.string.onb_notice_contact_body,
    )
}

@Composable
private fun noticeText(@StringRes res: Int): String =
    if (res in PrivacyNotice.EMAIL_SECTIONS) stringResource(res, BuildConfig.PRIVACY_EMAIL) else stringResource(res)

/** The notice body: version line, the "processed in the USA" call-out, then every section. */
@Composable
fun PrivacyNoticeContent(modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.onb_notice_version), style = MaterialTheme.typography.labelMedium)
        RmCard(tone = RmTone.WARN) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconWell(Icons.Rounded.Cloud, tone = RmTone.WARN)
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.onb_notice_usa_callout),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(stringResource(R.string.onb_notice_usa_callout_body), style = MaterialTheme.typography.bodyMedium)
        }
        PrivacyNotice.SECTIONS.forEach { (title, body) ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() },
                )
                Text(noticeText(body), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
