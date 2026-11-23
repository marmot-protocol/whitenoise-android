package dev.ipf.whitenoise.android.ui.onboarding.remotesigner

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import dev.ipf.marmotkit.Nip46PermissionsFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing

/** A plain-language group of signer grants; every grant MDK requests belongs to exactly one group. */
internal enum class RemoteSignerGrantGroup(
    @StringRes val titleRes: Int,
    @StringRes val detailRes: Int?,
) {
    Messages(R.string.remote_signer_group_messages, R.string.remote_signer_group_messages_detail),
    Profile(R.string.remote_signer_group_profile, R.string.remote_signer_group_profile_detail),
    Access(R.string.remote_signer_group_access, R.string.remote_signer_group_access_detail),
    Backup(R.string.remote_signer_group_backup, R.string.remote_signer_group_backup_detail),
    Other(R.string.remote_signer_group_other, null),
}

/** One grant as shown to the user; [labelRes] is null for a grant this build does not know, shown verbatim. */
internal data class RemoteSignerGrant(
    val id: String,
    val group: RemoteSignerGrantGroup,
    @StringRes val labelRes: Int?,
)

private val knownGrants: Map<String, Pair<RemoteSignerGrantGroup, Int>> =
    mapOf(
        "sign_event:13" to (RemoteSignerGrantGroup.Messages to R.string.remote_signer_grant_seal),
        "sign_event:450" to (RemoteSignerGrantGroup.Messages to R.string.remote_signer_grant_group_proof),
        "sign_event:30443" to (RemoteSignerGrantGroup.Messages to R.string.remote_signer_grant_key_package),
        "nip44_encrypt" to (RemoteSignerGrantGroup.Messages to R.string.remote_signer_grant_encrypt),
        "nip44_decrypt" to (RemoteSignerGrantGroup.Messages to R.string.remote_signer_grant_decrypt),
        "sign_event:0" to (RemoteSignerGrantGroup.Profile to R.string.remote_signer_grant_profile),
        "sign_event:3" to (RemoteSignerGrantGroup.Profile to R.string.remote_signer_grant_follows),
        "sign_event:10000" to (RemoteSignerGrantGroup.Profile to R.string.remote_signer_grant_mute_list),
        "sign_event:10002" to (RemoteSignerGrantGroup.Profile to R.string.remote_signer_grant_relay_list),
        "sign_event:10050" to (RemoteSignerGrantGroup.Profile to R.string.remote_signer_grant_inbox_relays),
        "sign_event:22242" to (RemoteSignerGrantGroup.Access to R.string.remote_signer_grant_relay_auth),
        "sign_event:24242" to (RemoteSignerGrantGroup.Access to R.string.remote_signer_grant_upload),
        "sign_event:451" to (RemoteSignerGrantGroup.Access to R.string.remote_signer_grant_push),
        "sign_event:30078" to (RemoteSignerGrantGroup.Backup to R.string.remote_signer_grant_backup),
        "sign_event:5" to (RemoteSignerGrantGroup.Backup to R.string.remote_signer_grant_delete),
    )

/** Every grant in MDK's exact order, grouped for display; unknown grants are kept, never dropped. */
internal fun remoteSignerGrantsByGroup(grants: List<String>): Map<RemoteSignerGrantGroup, List<RemoteSignerGrant>> =
    grants
        .map { id ->
            val known = knownGrants[id]
            RemoteSignerGrant(id, known?.first ?: RemoteSignerGrantGroup.Other, known?.second)
        }.groupBy { it.group }
        .toSortedMap(compareBy { it.ordinal })

/** The exact grant set MDK will request, grouped in plain language with every identifier one tap away. */
@Suppress("FunctionNaming")
@Composable
internal fun RemoteSignerPermissionReview(
    permissions: Nip46PermissionsFfi,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable(permissions.version) { mutableStateOf(false) }
    val groups = remoteSignerGrantsByGroup(permissions.grants)
    Column(
        modifier.fillMaxWidth().testTag("remote_signer.review"),
        verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
    ) {
        Text(stringResource(R.string.remote_signer_review_body), style = MaterialTheme.typography.bodyLarge)
        groups.forEach { (group, grants) ->
            Column(Modifier.fillMaxWidth()) {
                Text(
                    stringResource(group.titleRes),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.semantics { heading() },
                )
                val detail = group.detailRes?.let { stringResource(it) } ?: grants.joinToString { it.id }
                if (!expanded) RemoteSignerReviewSupporting(detail)
                if (expanded) grants.forEach { RemoteSignerGrantRow(it) }
            }
        }
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("remote_signer.review.details")) {
            val label = if (expanded) R.string.remote_signer_review_hide_all else R.string.remote_signer_review_show_all
            Text(stringResource(label))
        }
        if (expanded) {
            val version = permissions.version.toInt()
            RemoteSignerReviewSupporting(stringResource(R.string.remote_signer_review_version, version))
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun RemoteSignerGrantRow(grant: RemoteSignerGrant) {
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        Text(grant.labelRes?.let { stringResource(it) } ?: grant.id, style = MaterialTheme.typography.bodyMedium)
        Text(
            grant.id,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Suppress("FunctionNaming")
@Composable
internal fun RemoteSignerReviewSupporting(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
