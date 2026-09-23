@file:Suppress("MatchingDeclarationName") // The dialog owns its small target row type.

package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ConversationFixtureSeedProgress
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.seedConversationFixture
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseAlertDialog
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseDialogChoiceRow
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseTextField
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** One conversation the seed can target, as the dialog lists it. */
internal data class ConversationFixtureTarget(
    val groupIdHex: String,
    val title: String,
)

/** The active account's conversations, titled by group name or a short id for a nameless DM. */
internal fun conversationFixtureTargets(appState: WhiteNoiseAppState): List<ConversationFixtureTarget> =
    appState.chatListItems.map { item ->
        val group = item.group
        ConversationFixtureTarget(
            groupIdHex = group.groupIdHex,
            title = group.name.ifBlank { group.groupIdHex.take(SHORT_ID_LENGTH) },
        )
    }

/**
 * Debug-only dialog that sends a chosen number of synthetic messages into one conversation, so a
 * paging benchmark has a fixture deep enough to cross several pages and the bounded window cap.
 * The messages are real sends, which is why the dialog insists on a target being picked explicitly.
 */
@Suppress("FunctionNaming", "LongMethod")
@Composable
internal fun ConversationFixtureSeedDialog(
    appState: WhiteNoiseAppState,
    onDismiss: () -> Unit,
) {
    val targets = remember(appState.chatListItems) { conversationFixtureTargets(appState) }
    var selected by remember { mutableStateOf<String?>(null) }
    val count = rememberTextFieldState(DEFAULT_SEED_COUNT.toString())
    var progress by remember { mutableStateOf<ConversationFixtureSeedProgress?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val running = job?.isActive == true
    val requested =
        count.text
            .toString()
            .toIntOrNull()
            ?.coerceIn(1, MAX_SEED_COUNT)
    WhiteNoiseAlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = { Text(stringResource(R.string.seed_fixture_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).heightIn(max = 420.dp)) {
                Text(stringResource(R.string.seed_fixture_detail), style = MaterialTheme.typography.bodyMedium)
                WhiteNoiseTextField(
                    state = count,
                    modifier = Modifier.fillMaxWidth().testTag("developer.seed_fixture.count"),
                    enabled = !running,
                    label = { Text(stringResource(R.string.seed_fixture_count)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                ConversationFixtureTargetList(targets, selected, enabled = !running) { selected = it }
                SeedProgressText(progress)
            }
        },
        confirmButton = {
            val target = selected
            TextButton(
                enabled = !running && target != null && requested != null && progress?.finished != true,
                onClick = {
                    if (target == null || requested == null) return@TextButton
                    job =
                        scope.launch {
                            appState.seedConversationFixture(target, requested) { progress = it }
                        }
                },
                modifier = Modifier.testTag("developer.seed_fixture.start"),
            ) {
                Text(stringResource(R.string.seed_fixture_start))
            }
        },
        dismissButton = {
            TextButton(onClick = {
                job?.cancel()
                onDismiss()
            }) {
                Text(stringResource(if (running) R.string.cancel else R.string.close))
            }
        },
    )
}

/** The selectable list of conversations, or a note when the account has none. */
@Suppress("FunctionNaming")
@Composable
private fun ConversationFixtureTargetList(
    targets: List<ConversationFixtureTarget>,
    selected: String?,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    if (targets.isEmpty()) {
        Text(stringResource(R.string.seed_fixture_no_groups), style = MaterialTheme.typography.bodyMedium)
        return
    }
    targets.forEach { target ->
        WhiteNoiseDialogChoiceRow(
            title = target.title,
            selected = target.groupIdHex == selected,
            onClick = { onSelect(target.groupIdHex) },
            enabled = enabled,
        )
    }
}

/** Sent, failed and target counts while a seed runs; nothing before it starts. */
@Suppress("FunctionNaming")
@Composable
private fun SeedProgressText(progress: ConversationFixtureSeedProgress?) {
    if (progress == null) return
    Text(
        stringResource(R.string.seed_fixture_progress, progress.sent, progress.total, progress.failed),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.testTag("developer.seed_fixture.progress"),
    )
}

/** Enough for a deep-fling benchmark to cross the 200-row window cap with margin. */
private const val DEFAULT_SEED_COUNT = 300

/** A hard ceiling so a typo cannot flood a group. */
private const val MAX_SEED_COUNT = 1000

private const val SHORT_ID_LENGTH = 8
