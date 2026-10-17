package dev.ipf.whitenoise.android.maestro

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.whitenoise.android.ui.chats.ChatDepartureSuccessor
import dev.ipf.whitenoise.android.ui.chats.ChatDepartureTarget
import dev.ipf.whitenoise.android.ui.chats.ChatDepartureUi
import dev.ipf.whitenoise.android.ui.chats.chooseDepartureSuccessor
import kotlinx.coroutines.launch

/** Real successor picker and confirmation complete a bounded answer, without transferring or leaving. */
@Composable
@Suppress("FunctionNaming")
internal fun MaestroDepartureModalPresentation(fixture: MaestroPresentationFixture) {
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
