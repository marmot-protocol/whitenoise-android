package dev.ipf.whitenoise.android.maestro

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.ipf.whitenoise.android.widget.ConversationWidgetConfigurationContent
import dev.ipf.whitenoise.android.widget.WidgetAccountPresentation

/** Production configuration and exact choices; this fixture never installs a launcher widget. */
@Composable
@Suppress("FunctionNaming")
internal fun MaestroWidgetPresentation(fixture: MaestroPresentationFixture) {
    val empty = fixture.scenario == "modal-widget-empty"
    var accounts by remember {
        mutableStateOf(
            if (empty) {
                emptyList()
            } else {
                listOf(
                    WidgetAccountPresentation("a", "a", "Maestro Widget A", active = true),
                    WidgetAccountPresentation("b", "b", "Maestro Widget B"),
                )
            },
        )
    }
    Column(Modifier.fillMaxSize()) {
        if (fixture.scenario == "modal-widget-removed") {
            Button(onClick = { accounts = accounts.filter { it.reference != "a" } }) {
                Text("Remove current fixture account")
            }
        }
        Box(Modifier.weight(1f)) {
            ConversationWidgetConfigurationContent(
                accounts = accounts,
                onOpenApp = {
                    check(empty)
                    fixture.finish("open-app")
                },
                onSave = { choice ->
                    check(!empty && fixture.scenario != "modal-widget-removed")
                    val options = fixture.scenario == "modal-widget-options"
                    check(choice.account == "b" && choice.allAccounts == options && choice.unreadOnly == options)
                    check(!choice.previews) { "Configuration enabled message previews" }
                    fixture.finish("save-verified")
                },
                onCancel = { fixture.finish("dismiss") },
            )
        }
    }
}
