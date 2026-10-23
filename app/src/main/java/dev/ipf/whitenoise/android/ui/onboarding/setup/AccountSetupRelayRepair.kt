@file:Suppress("FunctionNaming") // Compose functions use framework naming.

package dev.ipf.whitenoise.android.ui.onboarding.setup

import android.util.JsonWriter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.OnboardingRelayRepairFfi
import dev.ipf.marmotkit.OnboardingRelayRepairModeFfi
import dev.ipf.marmotkit.OnboardingRelayTagDispositionFfi
import dev.ipf.marmotkit.OnboardingRelayTagRoleFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.Dimens
import java.io.StringWriter

/** Shows the native diff and complete ordered declarations without hiding unknown tag fields. */
@Suppress("LongMethod") // The summary and exact disclosure render one immutable native proposal.
@Composable
internal fun SetupRelayRepairContent(
    repair: OnboardingRelayRepairFfi,
    findings: List<dev.ipf.marmotkit.OnboardingFindingFfi> = emptyList(),
) {
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
    var technical by remember(repair) { mutableStateOf(false) }
    repair.changes.forEach { change ->
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs)) {
            val disposition =
                when (change.disposition) {
                    OnboardingRelayTagDispositionFfi.RETAINED -> R.string.setup_repair_retained
                    OnboardingRelayTagDispositionFfi.REMOVED -> R.string.setup_repair_removed
                    OnboardingRelayTagDispositionFfi.ADDED -> R.string.setup_repair_added
                }
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm)) {
                Icon(
                    painterResource(
                        when (change.disposition) {
                            OnboardingRelayTagDispositionFfi.RETAINED -> R.drawable.ic_check
                            OnboardingRelayTagDispositionFfi.REMOVED -> R.drawable.ic_delete
                            OnboardingRelayTagDispositionFfi.ADDED -> R.drawable.ic_add
                        },
                    ),
                    null,
                    Modifier.size(20.dp),
                )
                Text(
                    stringResource(disposition) + " · " + stringResource(relayRoleLabel(change.role)),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Text(
                change.endpoint ?: relayTagJson(change.fields),
                style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr),
            )
            findings.filter { it.endpoint != null && it.endpoint == change.endpoint }.distinct().forEach {
                Text(stringResource(setupFindingTitle(it.issue)), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    TextButton(onClick = { technical = !technical }, modifier = Modifier.testTag("setup.repair.technical")) {
        Icon(painterResource(R.drawable.ic_info), null)
        Spacer(Modifier.width(Dimens.spaceSm))
        Text(stringResource(R.string.setup_technical_details))
    }
    if (technical) {
        Text(stringResource(R.string.setup_repair_before), style = MaterialTheme.typography.titleMedium)
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            SetupNotice(
                repair.beforeTags
                    .joinToString("\n") {
                        relayTagJson(it.fields)
                    }.ifEmpty { stringResource(R.string.setup_empty_list) },
            )
        }
        Text(stringResource(R.string.setup_repair_content), style = MaterialTheme.typography.labelLarge)
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            SetupNotice(repair.originalContent.ifEmpty { stringResource(R.string.setup_empty_list) })
        }
        if (repair.mode != OnboardingRelayRepairModeFfi.MANUAL_REVIEW) {
            Text(stringResource(R.string.setup_repair_after), style = MaterialTheme.typography.titleMedium)
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                SetupNotice(
                    repair.afterTags
                        .joinToString("\n") {
                            relayTagJson(it.fields)
                        }.ifEmpty { stringResource(R.string.setup_empty_list) },
                )
            }
            Text(stringResource(R.string.setup_repair_content), style = MaterialTheme.typography.labelLarge)
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                SetupNotice(repair.proposedContent.ifEmpty { stringResource(R.string.setup_empty_list) })
            }
        }
    }
}

/** Quotes opaque fields as JSON while keeping relay URL slashes readable. */
internal fun relayTagJson(fields: List<String>): String =
    StringWriter()
        .also { output ->
            JsonWriter(output).use { json ->
                json.beginArray()
                fields.forEach { json.value(it) }
                json.endArray()
            }
        }.toString()

/** Uses native role annotations instead of interpreting NIP-65 fields in Compose. */
private fun relayRoleLabel(role: OnboardingRelayTagRoleFfi): Int =
    when (role) {
        OnboardingRelayTagRoleFfi.READ -> R.string.setup_repair_read
        OnboardingRelayTagRoleFfi.WRITE -> R.string.setup_repair_write
        OnboardingRelayTagRoleFfi.UNMARKED -> R.string.setup_repair_both
        OnboardingRelayTagRoleFfi.INBOX -> R.string.setup_repair_inbox
        OnboardingRelayTagRoleFfi.OTHER -> R.string.setup_repair_other
    }
