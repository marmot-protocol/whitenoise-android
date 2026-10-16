package dev.ipf.whitenoise.android.maestro

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.whitenoise.android.ui.chats.ChatDepartureReview
import dev.ipf.whitenoise.android.ui.chats.ChatDepartureSuccessor
import dev.ipf.whitenoise.android.ui.chats.ChatDepartureTarget
import dev.ipf.whitenoise.android.ui.chats.ChatDepartureUi
import dev.ipf.whitenoise.android.ui.chats.chooseDepartureSuccessor
import dev.ipf.whitenoise.android.ui.group.GroupAbandonmentTarget
import dev.ipf.whitenoise.android.ui.group.groupAbandonmentBatchLabel
import kotlinx.coroutines.launch

/** Routes the chat-list departure presentations: the bulk abandonment review, or the successor choice. */
@Composable
@Suppress("FunctionNaming")
internal fun MaestroDepartureModalPresentation(fixture: MaestroPresentationFixture) {
    if (fixture.scenario == "modal-departure-review-abandon") {
        DepartureAbandonReviewPresentation(fixture)
    } else {
        DepartureSuccessorPresentation(fixture)
    }
}

/** The real bulk review names every chat and the acting account; confirming only records it, nothing is forgotten. */
@Composable
@Suppress("FunctionNaming")
private fun DepartureAbandonReviewPresentation(fixture: MaestroPresentationFixture) {
    var reviewing by remember(fixture) { mutableStateOf(true) }
    val targets =
        remember {
            listOf(
                fixtureAbandonmentTarget("1", "Fixture group", isDm = false),
                fixtureAbandonmentTarget("2", "Fixture direct chat", isDm = true),
            )
        }
    if (reviewing) {
        ChatDepartureReview(
            abandonmentTargets = targets,
            targetCount = targets.size,
            actionLabel = stringResource(groupAbandonmentBatchLabel(targets.map { it.isDm })),
            onConfirm = { fixture.finish("departure-abandon-confirm") },
            onDismiss = {
                fixture.record("departure-abandon-dismiss")
                reviewing = false
            },
        )
    } else {
        Button(onClick = { reviewing = true }) { Text("Reopen abandonment review") }
    }
}

/** One reviewed chat of the fixture account, so the review never names a real account or conversation. */
private fun fixtureAbandonmentTarget(
    idDigit: String,
    title: String,
    isDm: Boolean,
) = GroupAbandonmentTarget("fixture-account", "Fixture account", idDigit.repeat(64), title, isDm)

/** Real successor picker and confirmation complete a bounded answer, without transferring or leaving. */
@Composable
@Suppress("FunctionNaming")
private fun DepartureSuccessorPresentation(fixture: MaestroPresentationFixture) {
    val ui = remember(fixture) { ChatDepartureUi() }
    val target = remember { ChatDepartureTarget("fixture-departure-group", "Fixture successor group", false) }
    val member = remember(fixture) { AppGroupMemberRecordFfi(fixture.peerAccountIdHex, null, false) }

    DisposableEffect(ui) { onDispose { ui.active = false } }
    val scope = rememberCoroutineScope()
    val members =
        remember(fixture) {
            listOf(
                member,
                AppGroupMemberRecordFfi(
                    fixture.appState.accounts
                        .first {
                            it.accountIdHex != fixture.peerAccountIdHex &&
                                it.accountIdHex != checkNotNull(fixture.appState.activeAccount).accountIdHex
                        }.accountIdHex,
                    null,
                    false,
                ),
            )
        }

    fun reopen() {
        scope.launch {
            val answer = chooseDepartureSuccessor(ui, target, members)
            if (answer == null) {
                fixture.record("departure-dismiss")
            } else {
                check(answer.memberIdHex == fixture.peerAccountIdHex)
                fixture.finish("departure-successor-selected")
            }
        }
    }
    LaunchedEffect(fixture) { reopen() }
    val decision = ui.decision
    if (decision != null) {
        ChatDepartureSuccessor(ui, fixture.appState)
    } else {
        Button(onClick = ::reopen) { Text("Reopen departure choice") }
    }
}
