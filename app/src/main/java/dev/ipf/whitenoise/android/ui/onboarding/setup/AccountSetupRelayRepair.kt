@file:Suppress("FunctionNaming") // Compose functions use framework naming.

package dev.ipf.whitenoise.android.ui.onboarding.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import dev.ipf.marmotkit.OnboardingRelayRepairFfi
import dev.ipf.marmotkit.OnboardingRelayRepairModeFfi
import dev.ipf.marmotkit.OnboardingRelayTagDispositionFfi
import dev.ipf.marmotkit.OnboardingRelayTagRoleFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.Dimens
import org.json.JSONArray

/** Shows the native diff and complete ordered declarations without hiding unknown tag fields. */
@Composable
internal fun SetupRelayRepairContent(repair: OnboardingRelayRepairFfi) {
    Text(
        stringResource(
            if (repair.mode ==
                OnboardingRelayRepairModeFfi.MANUAL_REVIEW
            ) {
                R.string.setup_repair_manual
            } else {
                R.string.setup_repair_preview
            },
        ),
    )
    repair.changes.forEach { change ->
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs)) {
            val disposition =
                when (change.disposition) {
                    OnboardingRelayTagDispositionFfi.RETAINED -> R.string.setup_repair_retained
                    OnboardingRelayTagDispositionFfi.REMOVED -> R.string.setup_repair_removed
                    OnboardingRelayTagDispositionFfi.ADDED -> R.string.setup_repair_added
                }
            Text(
                stringResource(disposition) + " · " + stringResource(relayRoleLabel(change.role)),
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                change.endpoint ?: JSONArray(change.fields).toString(),
                style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr),
            )
        }
    }
    Text(stringResource(R.string.setup_repair_before), style = MaterialTheme.typography.titleMedium)
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        SetupNotice(
            repair.beforeTags
                .joinToString("\n") {
                    JSONArray(it.fields).toString()
                }.ifEmpty { stringResource(R.string.setup_empty_list) },
        )
    }
    Text(stringResource(R.string.setup_repair_content), style = MaterialTheme.typography.labelLarge)
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        SetupNotice(JSONArray(listOf(repair.originalContent)).toString())
    }
    if (repair.mode != OnboardingRelayRepairModeFfi.MANUAL_REVIEW) {
        Text(stringResource(R.string.setup_repair_after), style = MaterialTheme.typography.titleMedium)
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            SetupNotice(
                repair.afterTags
                    .joinToString("\n") {
                        JSONArray(it.fields).toString()
                    }.ifEmpty { stringResource(R.string.setup_empty_list) },
            )
        }
        Text(stringResource(R.string.setup_repair_content), style = MaterialTheme.typography.labelLarge)
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            SetupNotice(JSONArray(listOf(repair.proposedContent)).toString())
        }
    }
}

/** Uses native role annotations instead of interpreting NIP-65 fields in Compose. */
private fun relayRoleLabel(role: OnboardingRelayTagRoleFfi): Int =
    when (role) {
        OnboardingRelayTagRoleFfi.READ -> R.string.setup_repair_read
        OnboardingRelayTagRoleFfi.WRITE -> R.string.setup_repair_write
        OnboardingRelayTagRoleFfi.UNMARKED -> R.string.setup_repair_both
        OnboardingRelayTagRoleFfi.INBOX -> R.string.setup_repair_inbox
        OnboardingRelayTagRoleFfi.OTHER -> R.string.setup_repair_other
    }
