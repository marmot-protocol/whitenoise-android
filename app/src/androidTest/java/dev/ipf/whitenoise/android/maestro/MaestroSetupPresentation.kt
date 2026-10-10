package dev.ipf.whitenoise.android.maestro

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import dev.ipf.marmotkit.OnboardingActionFfi
import dev.ipf.marmotkit.OnboardingSnapshotFfi
import dev.ipf.marmotkit.OnboardingStatusFfi
import dev.ipf.marmotkit.OnboardingStepFfi
import dev.ipf.marmotkit.OnboardingStepStateFfi
import dev.ipf.marmotkit.UserProfileMetadataFfi
import dev.ipf.whitenoise.android.ui.onboarding.setup.AccountSetupClient
import dev.ipf.whitenoise.android.ui.onboarding.setup.AccountSetupController
import dev.ipf.whitenoise.android.ui.onboarding.setup.AccountSetupScreen
import dev.ipf.whitenoise.android.ui.onboarding.setup.AccountSetupSubscription
import dev.ipf.whitenoise.android.ui.onboarding.setup.SetupRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel

/** A bounded presentation client drives the production controller; this is not MDK onboarding proof. */
@Composable
@Suppress("FunctionNaming")
internal fun MaestroSetupPresentation(fixture: MaestroPresentationFixture) {
    val lifetime = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    val client = remember { PresentationSetupClient(fixture) }
    val controller =
        remember {
            AccountSetupController(client.account, client, lifetime, { true }, {}, { fixture.finish("cancelled") })
        }
    DisposableEffect(controller) {
        onDispose { lifetime.cancel() }
    }
    LaunchedEffect(controller) { controller.reconnect() }
    AccountSetupScreen(controller, randomName = { "Maestro setup name" }, onLater = { fixture.finish("dismiss") })
}

private class PresentationSetupClient(private val fixture: MaestroPresentationFixture) : AccountSetupClient {
    val account = "ab".repeat(32)
    private val step =
        when (fixture.scenario) {
            "setup-relays" -> OnboardingStepFfi.RELAYS
            "setup-discovery" -> OnboardingStepFfi.RELAYS
            "setup-follows" -> OnboardingStepFfi.FOLLOWS
            "setup-inbox" -> OnboardingStepFfi.INBOX_RELAYS
            "setup-device" -> OnboardingStepFfi.SINGLE_DEVICE
            else -> OnboardingStepFfi.PROFILE
        }
    private val action =
        when (step) {
            OnboardingStepFfi.PROFILE -> OnboardingActionFfi.EDIT_PROFILE
            OnboardingStepFfi.RELAYS -> OnboardingActionFfi.EDIT_RELAYS
            OnboardingStepFfi.INBOX_RELAYS -> OnboardingActionFfi.EDIT_DISCOVERY_RELAYS
            else -> OnboardingActionFfi.CONTINUE_WITHOUT
        }
    private val current =
        OnboardingSnapshotFfi(
            account, null, 3uL, false,
            OnboardingStepFfi.entries.map { item ->
                OnboardingStepStateFfi(
                    item,
                    if (item.ordinal < step.ordinal) OnboardingStatusFfi.PASSED else if (item == step) OnboardingStatusFfi.NEEDS_INPUT else OnboardingStatusFfi.PENDING,
                    emptyList(), if (item == step) listOf(action, OnboardingActionFfi.CONTINUE_WITHOUT) else emptyList(), null,
                )
            },
            null, null, false,
        )

    override suspend fun snapshot() = current

    override suspend fun subscribe(): AccountSetupSubscription =
        object : AccountSetupSubscription {
            override fun snapshot() = current
            override suspend fun next(): OnboardingSnapshotFfi? = awaitCancellation()
            override suspend fun close() = Unit
        }

    override suspend fun run(): OnboardingSnapshotFfi {
        if (fixture.scenario == "setup-error") error("Synthetic preflight failure")
        return current
    }

    override suspend fun execute(request: SetupRequest): OnboardingSnapshotFfi {
        check(request.revision == 3uL && request.step == step && request.action == OnboardingActionFfi.CONTINUE_WITHOUT)
        fixture.finish("continue-without")
        return current
    }

    override suspend fun profile() = UserProfileMetadataFfi("Maestro", "Maestro setup", "Setup detail", null, null, null, null)
}
