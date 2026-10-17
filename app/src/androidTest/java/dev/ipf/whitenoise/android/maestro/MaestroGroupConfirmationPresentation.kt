package dev.ipf.whitenoise.android.maestro

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.ipf.whitenoise.android.core.RecipientSearch
import dev.ipf.whitenoise.android.state.GroupChangeCaution
import dev.ipf.whitenoise.android.state.GroupChangeConfirmation
import dev.ipf.whitenoise.android.state.GroupChangeTarget
import dev.ipf.whitenoise.android.ui.chats.newchat.GroupMemberReviewPair
import dev.ipf.whitenoise.android.ui.chats.newchat.MemberReviewGroup
import dev.ipf.whitenoise.android.ui.conversation.media.GroupPictureFailureDialog
import dev.ipf.whitenoise.android.ui.group.GroupChangeConfirmationDialog
import dev.ipf.whitenoise.android.ui.group.GroupDetailsConfirmation
import dev.ipf.whitenoise.android.ui.group.GroupDetailsConfirmationKind
import dev.ipf.whitenoise.android.ui.group.GroupDetailsConfirmationState
import dev.ipf.whitenoise.android.ui.group.groupActionMemberHex
import kotlinx.coroutines.CompletableDeferred

/** All production group-detail confirmation shapes; bounded callbacks never alter group membership. */
@Composable
@Suppress("FunctionNaming")
internal fun MaestroGroupConfirmationPresentation(fixture: MaestroPresentationFixture) {
    if (fixture.scenario.startsWith("modal-viewer-failure-")) {
        GroupPictureFailurePresentation(fixture)
        return
    }
    if (fixture.scenario.startsWith("modal-group-change-")) {
        GroupChangeQuestionPresentation(fixture)
        return
    }
    val kind =
        remember(fixture) {
            GroupDetailsConfirmationKind.entries.single {
                it.name.lowercase() == fixture.scenario.removePrefix("modal-group-details-")
            }
        }
    var showing by remember { mutableStateOf(true) }
    if (showing) {
        val confirm = {
            check(kind != GroupDetailsConfirmationKind.BlockedLeave)
            fixture.finish("confirm-${kind.name.lowercase()}")
        }
        val model = maestroGroupConfirmationState(kind, confirm)
        val memberHex =
            checkNotNull(
                groupActionMemberHex(
                    kind,
                    checkNotNull(fixture.appState.activeAccount?.accountIdHex),
                    fixture.peerAccountIdHex,
                ),
            )
        GroupDetailsConfirmation(
            identity = {
                GroupMemberReviewPair(
                    appState = fixture.appState,
                    group = MemberReviewGroup("fixture-confirmation-group", "Fixture confirmation group"),
                    member =
                        RecipientSearch.Candidate(
                            memberHex,
                            "",
                            fixture.appState.npubForDisplay(memberHex),
                        ),
                )
            },
            model = model,
            onDismiss = {
                fixture.record("group-confirm-dismiss")
                showing = false
            },
        )
    } else {
        Column {
            Button(onClick = { showing = true }) { Text("Reopen group confirmation") }
            if (kind == GroupDetailsConfirmationKind.BlockedLeave) {
                Button(onClick = { fixture.finish("done") }) { Text("Finish group confirmation check") }
            }
        }
    }
}

@Composable
@Suppress("FunctionNaming")
private fun GroupPictureFailurePresentation(fixture: MaestroPresentationFixture) {
    var showing by remember { mutableStateOf(true) }
    if (showing) {
        GroupPictureFailureDialog(
            onRetry = { fixture.finish("picture-retry") },
            onDismiss = {
                fixture.record("picture-error-dismiss")
                showing = false
            },
        )
    } else {
        Button(onClick = { showing = true }) { Text("Reopen picture failure") }
    }
}

/** The real app-level question before overriding a refused group change; its answer only reaches this fixture. */
@Composable
@Suppress("FunctionNaming")
private fun GroupChangeQuestionPresentation(fixture: MaestroPresentationFixture) {
    check(fixture.scenario == "modal-group-change-catchingup")
    var showing by remember { mutableStateOf(true) }
    if (showing) {
        val decision = remember { CompletableDeferred<Boolean>() }
        val question =
            remember(decision) {
                GroupChangeConfirmation(GroupChangeCaution.CatchingUp, false, GROUP_CHANGE_FIXTURE_TARGET, decision)
            }
        LaunchedEffect(decision) {
            if (decision.await()) {
                fixture.finish("group-change-continue")
            } else {
                fixture.record("group-change-wait")
                showing = false
            }
        }
        GroupChangeConfirmationDialog(question)
    } else {
        Button(onClick = { showing = true }) { Text("Reopen group change question") }
    }
}

/** The production confirmation model for [kind], with [confirm] as its only action. */
private fun maestroGroupConfirmationState(
    kind: GroupDetailsConfirmationKind,
    confirm: () -> Unit,
): GroupDetailsConfirmationState =
    when (kind) {
        GroupDetailsConfirmationKind.RemoveMember ->
            GroupDetailsConfirmationState.RemoveMember("Fixture successor", confirm)
        GroupDetailsConfirmationKind.TransferAdmin ->
            GroupDetailsConfirmationState.TransferAdmin("Fixture successor", confirm)
        GroupDetailsConfirmationKind.StepDown -> GroupDetailsConfirmationState.StepDown(confirm)
        GroupDetailsConfirmationKind.StepDownSoleAdmin -> GroupDetailsConfirmationState.StepDownSoleAdmin(confirm)
        GroupDetailsConfirmationKind.Leave ->
            GroupDetailsConfirmationState.Leave("Fixture confirmation group", confirm)
        GroupDetailsConfirmationKind.LeaveSoleMember ->
            GroupDetailsConfirmationState.LeaveSoleMember("Fixture confirmation group", confirm)
        GroupDetailsConfirmationKind.BlockedLeave ->
            GroupDetailsConfirmationState.BlockedLeave("Fixture confirmation group")
        GroupDetailsConfirmationKind.TransferThenLeave ->
            GroupDetailsConfirmationState.TransferThenLeave("Fixture successor", confirm)
    }

/** The fixture question's chat; its account and runtime never reach the app-level host. */
private val GROUP_CHANGE_FIXTURE_TARGET =
    GroupChangeTarget("fixture", "fixture-group-change", runtimeGeneration = 0, title = "Fixture confirmation group")
