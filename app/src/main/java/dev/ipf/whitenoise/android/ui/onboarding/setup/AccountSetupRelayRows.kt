@file:Suppress("FunctionNaming") // Compose functions use framework naming.

package dev.ipf.whitenoise.android.ui.onboarding.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.Dimens

/** Exact endpoint selections, not independent raw-tag occurrences or inferred relay health. */
@Suppress("LongMethod") // One endpoint list owns its transient remove/undo controls.
@Composable
internal fun SetupRelayEditorRows(
    editor: SetupEditor,
    fields: SetupEditorFields,
    busy: Boolean,
    findings: List<dev.ipf.marmotkit.OnboardingFindingFfi> = emptyList(),
) {
    val current = fields.current(editor)
    val endpoints =
        (
            current.originalReads.lines() + current.originalWrites.lines() +
                current.reads.lines() + current.writes.lines()
        ).filter(String::isNotBlank).distinct()
    var undo by remember(fields) { mutableStateOf<RelayRemovalUndo?>(null) }
    Text(stringResource(R.string.setup_relay_publication_scope), style = MaterialTheme.typography.bodySmall)
    Text(stringResource(R.string.setup_relay_remove_scope), style = MaterialTheme.typography.bodySmall)
    endpoints.forEachIndexed { index, endpoint ->
        val reads = current.reads.lines().filter(String::isNotBlank)
        val writes = current.writes.lines().filter(String::isNotBlank)
        val selected = endpoint in reads || endpoint in writes
        if (selected) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm)) {
                Column(Modifier.weight(1f)) {
                    Text(
                        endpoint,
                        style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr),
                    )
                    Text(
                        stringResource(
                            when {
                                editor.step == dev.ipf.marmotkit.OnboardingStepFfi.INBOX_RELAYS -> R.string.setup_repair_inbox
                                endpoint in reads && endpoint in writes -> R.string.setup_repair_both
                                endpoint in writes -> R.string.setup_repair_write
                                else -> R.string.setup_repair_read
                            },
                        ),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    findings.filter { it.endpoint == endpoint }.distinct().forEach {
                        Text(stringResource(setupFindingTitle(it.issue)), style = MaterialTheme.typography.bodySmall)
                    }
                }
                IconButton(
                    onClick = {
                        val before = fields.current(editor)
                        val after = before.withoutRelayEndpoint(endpoint)
                        fields.reads.setTextAndPlaceCursorAtEnd(after.reads)
                        fields.writes.setTextAndPlaceCursorAtEnd(after.writes)
                        undo = RelayRemovalUndo(before.reads, before.writes, after.reads, after.writes)
                    },
                    enabled = !busy && editor.canEditRelayDeclaration,
                    modifier = Modifier.size(48.dp).testTag("setup.relay.remove.$index"),
                ) {
                    Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.remove_named_relay, endpoint))
                }
            }
        }
    }
    val saved = undo
    if (saved != null && saved.afterReads == current.reads && saved.afterWrites == current.writes) {
        androidx.compose.material3.TextButton(onClick = {
            fields.reads.setTextAndPlaceCursorAtEnd(saved.beforeReads)
            fields.writes.setTextAndPlaceCursorAtEnd(saved.beforeWrites)
            undo = null
        }, enabled = !busy, modifier = Modifier.testTag("setup.relay.undo")) {
            Text(stringResource(R.string.setup_undo_removal))
        }
    }
}

private data class RelayRemovalUndo(
    val beforeReads: String,
    val beforeWrites: String,
    val afterReads: String,
    val afterWrites: String,
)

/** Stage removal of all exact occurrences in both roles; MDK owns normalization and the resulting tag diff. */
internal fun SetupEditor.withoutRelayEndpoint(endpoint: String): SetupEditor =
    copy(
        reads = reads.lines().filterNot { it == endpoint }.joinToString("\n"),
        writes = writes.lines().filterNot { it == endpoint }.joinToString("\n"),
    )
