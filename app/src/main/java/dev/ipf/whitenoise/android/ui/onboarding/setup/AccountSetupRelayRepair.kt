@file:Suppress("FunctionNaming") // Compose functions use framework naming.

package dev.ipf.whitenoise.android.ui.onboarding.setup

import android.util.JsonWriter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.OnboardingFindingFfi
import dev.ipf.marmotkit.OnboardingRelayRepairFfi
import dev.ipf.marmotkit.OnboardingRelayRepairModeFfi
import dev.ipf.marmotkit.OnboardingRelayTagChangeFfi
import dev.ipf.marmotkit.OnboardingRelayTagDispositionFfi
import dev.ipf.marmotkit.OnboardingRelayTagRoleFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.Dimens
import java.io.StringWriter

/** The approval surface shows every changed native entry and its exact endpoint or opaque fields. */
@Composable
internal fun SetupRelayRepairContent(
    repair: OnboardingRelayRepairFfi,
    findings: List<OnboardingFindingFfi> = emptyList(),
) {
    if (repair.mode == OnboardingRelayRepairModeFfi.MANUAL_REVIEW) {
        Text(stringResource(R.string.setup_repair_manual))
    }
    repair.changes
        .filter { it.disposition != OnboardingRelayTagDispositionFfi.RETAINED }
        .forEach { SetupRelayChange(it, findings) }
}

/** The shared Details region holds unchanged entries and exact ordered native record data. */
@Composable
internal fun SetupRelayRepairDetails(
    repair: OnboardingRelayRepairFfi,
    findings: List<OnboardingFindingFfi> = emptyList(),
) {
    val retained = repair.changes.filter { it.disposition == OnboardingRelayTagDispositionFfi.RETAINED }
    if (retained.isNotEmpty()) {
        Text(stringResource(R.string.setup_unchanged_relays), style = MaterialTheme.typography.titleMedium)
        retained.forEach { SetupRelayChange(it, findings) }
    }
    Text(stringResource(R.string.setup_technical_details), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.setup_repair_before), style = MaterialTheme.typography.titleSmall)
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        SetupNotice(
            repair.beforeTags
                .joinToString("\n") { relayTagJson(it.fields) }
                .ifEmpty { stringResource(R.string.setup_empty_list) },
        )
    }
    Text(stringResource(R.string.setup_repair_content), style = MaterialTheme.typography.labelLarge)
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        SetupNotice(repair.originalContent.ifEmpty { stringResource(R.string.setup_empty_list) })
    }
    if (repair.mode != OnboardingRelayRepairModeFfi.MANUAL_REVIEW) {
        Text(stringResource(R.string.setup_repair_after), style = MaterialTheme.typography.titleSmall)
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            SetupNotice(
                repair.afterTags
                    .joinToString("\n") { relayTagJson(it.fields) }
                    .ifEmpty { stringResource(R.string.setup_empty_list) },
            )
        }
        Text(stringResource(R.string.setup_repair_content), style = MaterialTheme.typography.labelLarge)
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            SetupNotice(repair.proposedContent.ifEmpty { stringResource(R.string.setup_empty_list) })
        }
    }
}

/** A native change keeps its endpoint, role, disposition and relevant finding together. */
@Composable
private fun SetupRelayChange(
    change: OnboardingRelayTagChangeFfi,
    findings: List<OnboardingFindingFfi>,
) {
    val heading =
        when (change.disposition) {
            OnboardingRelayTagDispositionFfi.REMOVED ->
                if (change.endpoint == null) R.string.setup_detail_to_remove else R.string.setup_relay_to_remove
            OnboardingRelayTagDispositionFfi.ADDED ->
                if (change.endpoint == null) R.string.setup_detail_to_add else R.string.setup_relay_to_add
            OnboardingRelayTagDispositionFfi.RETAINED -> R.string.setup_relay_unchanged
        }
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs)) {
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
            Text(stringResource(heading), style = MaterialTheme.typography.titleSmall)
        }
        Text(
            change.endpoint ?: relayTagJson(change.fields),
            style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr),
        )
        Text(
            stringResource(relayReviewRoleLabel(change.role)),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        findings.filter { it.endpoint != null && it.endpoint == change.endpoint }.distinct().forEach {
            Text(stringResource(setupFindingTitle(it.issue)), style = MaterialTheme.typography.bodySmall)
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

/** Plain role names keep the action and capability separate. */
private fun relayReviewRoleLabel(role: OnboardingRelayTagRoleFfi): Int =
    when (role) {
        OnboardingRelayTagRoleFfi.READ -> R.string.setup_review_role_read
        OnboardingRelayTagRoleFfi.WRITE -> R.string.setup_review_role_write
        OnboardingRelayTagRoleFfi.UNMARKED -> R.string.setup_review_role_both
        OnboardingRelayTagRoleFfi.INBOX -> R.string.setup_review_role_inbox
        OnboardingRelayTagRoleFfi.OTHER -> R.string.setup_review_role_other
    }
