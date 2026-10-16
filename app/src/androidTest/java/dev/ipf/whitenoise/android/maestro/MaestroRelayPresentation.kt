package dev.ipf.whitenoise.android.maestro

import androidx.compose.runtime.Composable
import dev.ipf.whitenoise.android.ui.settings.AccountRelay
import dev.ipf.whitenoise.android.ui.settings.AccountRelayRole
import dev.ipf.whitenoise.android.ui.settings.RelayDetailsScreen

/** Read-only synthetic relay state exercises the real details editor without publishing relay lists. */
@Composable
@Suppress("FunctionNaming")
internal fun MaestroRelayPresentation(fixture: MaestroPresentationFixture) {
    RelayDetailsScreen(
        relay = AccountRelay("wss://fixture.example.invalid", setOf(AccountRelayRole.Profile)),
        lists = null,
        busy = false,
        onBack = { fixture.finish("dismiss") },
        onSetRole = { role, enabled ->
            check(role == AccountRelayRole.Profile && !enabled)
            fixture.finish("role-handoff")
        },
        onRemove = { error("Cancellation must not remove a relay") },
    )
}
