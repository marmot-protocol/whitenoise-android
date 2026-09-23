@file:Suppress("MatchingDeclarationName") // The dialog owns its small target row type.

package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** One conversation the seed can target, as the dialog lists it. */
internal data class ConversationFixtureTarget(
    val groupIdHex: String,
    val title: String,
)

/** The active account's conversations, titled by group name or a short id for a nameless DM. */
internal fun conversationFixtureTargets(appState: WhiteNoiseAppState): List<ConversationFixtureTarget> =
    disambiguatedFixtureTargets(appState.chatListItems.map { it.group.groupIdHex to it.group.name })

/**
 * Titles for the target list: the group name, or the short id when there is none, and the short id
 * appended whenever two groups share a name — hundreds of real messages must not land in the wrong
 * one of two identically named chats.
 */
internal fun disambiguatedFixtureTargets(groups: List<Pair<String, String>>): List<ConversationFixtureTarget> {
    val nameCounts = groups.groupingBy { (_, name) -> name }.eachCount()
    return groups.map { (groupIdHex, name) ->
        val shortId = groupIdHex.take(SHORT_ID_LENGTH)
        val title =
            when {
                name.isBlank() -> shortId
                nameCounts.getValue(name) > 1 -> "$name · $shortId"
                else -> name
            }
        ConversationFixtureTarget(groupIdHex = groupIdHex, title = title)
    }
}

/** The requested count when it is a whole number within 1..[MAX_SEED_COUNT]; null keeps Start disabled. */
internal fun seedCountOrNull(text: CharSequence): Int? {
    val count = text.toString().toIntOrNull()
    return count?.takeIf { it in 1..MAX_SEED_COUNT }
}

/**
 * Debug-only dialog that sends a chosen number of synthetic messages into one conversation, so a
 * paging benchmark has a fixture deep enough to cross several pages and the bounded window cap.
 * The messages are real sends, which is why the dialog insists on a target being picked explicitly.
 */
@Suppress("FunctionNaming")
@Composable
internal fun ConversationFixtureSeedDialog(
    appState: WhiteNoiseAppState,
    onDismiss: () -> Unit,
) {
    val targets = remember(appState.chatListItems) { conversationFixtureTargets(appState) }
    var selected by remember { mutableStateOf<String?>(null) }
    val count = rememberTextFieldState(DEFAULT_SEED_COUNT.toString())
    var progress by remember { mutableStateOf<ConversationFixtureSeedProgress?>(null) }
    var stopped by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    ConversationFixtureSeedDialogContent(
        targets = targets,
        selected = selected,
        count = count,
        progress = progress,
        stopped = stopped,
        running = job?.isActive == true,
        onSelect = { selected = it },
        onStart = { target, requested ->
            stopped = false
            progress = ConversationFixtureSeedProgress(sent = 0, failed = 0, total = requested)
            job =
                scope.launch {
                    try {
                        appState.seedConversationFixture(target, requested) { progress = it }
                    } catch (cancelled: CancellationException) {
                        // The reader's Cancel, or the engine giving up on a send: either way the
                        // dialog says where the seed stopped instead of falling silent.
                        stopped = true
                        throw cancelled
                    }
                }
        },
        onDismiss = {
            job?.cancel()
            onDismiss()
        },
    )
}

/**
 * The dialog itself, with every input explicit so it can be rendered and screenshotted without an
 * app state: the count field, the target list, the progress line and the Start/Cancel/Close pair.
 */
@Suppress("FunctionNaming", "LongParameterList", "LongMethod")
@Composable
internal fun ConversationFixtureSeedDialogContent(
    targets: List<ConversationFixtureTarget>,
    selected: String?,
    count: TextFieldState,
    progress: ConversationFixtureSeedProgress?,
    stopped: Boolean,
    running: Boolean,
    onSelect: (String) -> Unit,
    onStart: (target: String, requested: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val requested = seedCountOrNull(count.text)
    WhiteNoiseAlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = { Text(stringResource(R.string.seed_fixture_title)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.seed_fixture_detail), style = MaterialTheme.typography.bodyMedium)
                WhiteNoiseTextField(
                    state = count,
                    modifier = Modifier.fillMaxWidth().testTag("developer.seed_fixture.count"),
                    enabled = !running,
                    label = { Text(stringResource(R.string.seed_fixture_count)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                // Above the target list, which can scroll the bottom of the column out of view.
                SeedProgressText(progress, stopped)
                ConversationFixtureTargetList(targets, selected, enabled = !running, onSelect)
            }
        },
        confirmButton = {
            // A run that stopped after admitting messages is not restarted from ordinal one behind the
            // reader's back: closing the dialog is the explicit new-run decision.
            val restartable = progress == null || (!stopped && !progress.finished) || progress.sent == 0
            val canStart = !running && selected != null && requested != null && restartable
            TextButton(
                enabled = canStart && progress?.finished != true,
                onClick = { if (selected != null && requested != null) onStart(selected, requested) },
                modifier = Modifier.testTag("developer.seed_fixture.start"),
            ) {
                Text(stringResource(R.string.seed_fixture_start))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
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

/** Sent, failed and target counts while a seed runs, or where it stopped; nothing before it starts. */
@Suppress("FunctionNaming")
@Composable
private fun SeedProgressText(
    progress: ConversationFixtureSeedProgress?,
    stopped: Boolean,
) {
    if (progress == null) return
    val text =
        if (stopped) {
            stringResource(R.string.seed_fixture_stopped, progress.sent, progress.total)
        } else {
            stringResource(R.string.seed_fixture_progress, progress.sent, progress.total, progress.failed)
        }
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.testTag("developer.seed_fixture.progress"),
    )
}

/** Enough for a deep-fling benchmark to cross the 200-row window cap with margin. */
private const val DEFAULT_SEED_COUNT = 300

/** A hard ceiling so a typo cannot flood a group. */
private const val MAX_SEED_COUNT = 1000

private const val SHORT_ID_LENGTH = 8
