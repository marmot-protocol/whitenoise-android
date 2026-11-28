package dev.ipf.whitenoise.android.ui.onboarding.remotesigner

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.Nip46ErrorFfi
import dev.ipf.marmotkit.Nip46StateFfi
import dev.ipf.marmotkit.Nip46StatusFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.RemoteSignerAccountStatus
import dev.ipf.whitenoise.android.state.RemoteSignerHealth
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.onboarding.SignInContent
import dev.ipf.whitenoise.android.ui.settings.RemoteSignerAccountActions
import dev.ipf.whitenoise.android.ui.settings.RemoteSignerAccountDialogs
import dev.ipf.whitenoise.android.ui.settings.SettingsList
import dev.ipf.whitenoise.android.ui.settings.remoteSignerAccountItems
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Remote-signer entry, pairing steps and Account keys status on the default AMOLED theme and stress layouts. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class RemoteSignerScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    /** Sign in gains only the full-width outlined remote-signer button under the key field. */
    @Test fun signInEntryAmoled() {
        capture("remote_signer_sign_in_entry_amoled") {
            SignInContent(
                identity = "",
                busy = false,
                errorRes = null,
                onIdentityChange = {},
                onErrorChange = {},
                onBack = {},
                onSignIn = {},
                onUseRemoteSigner = {},
            )
        }
        composeRule.onNodeWithTag("onboarding.sign_in.remote_signer").assertIsDisplayed()
    }

    /** The two pairing directions. */
    @Test fun chooseAmoled() = captureFlow("remote_signer_choose_amoled") { }

    /** Bunker link entry with its privacy note. */
    @Test fun linkLight() =
        captureFlow("remote_signer_link_light", dark = false) {
            choose(RemoteSignerMethod.BunkerLink)
        }

    /** The scanner follows the input when an inline error wraps at 200% text in RTL. */
    @Test fun linkErrorAmoledRtlLargeText() =
        captureFlow("remote_signer_link_error_amoled_rtl_200", rtl = true, scale = 2f) {
            choose(RemoteSignerMethod.BunkerLink)
            linkErrorRes = R.string.remote_signer_error_invalid_link
        }

    /** Grouped permission review before pairing. */
    @Test fun reviewAmoled() = captureFlow("remote_signer_review_amoled") { reviewed() }

    /** Every exact grant behind Show all permissions. */
    @Test fun reviewDetailsLight() {
        captureFlow("remote_signer_review_details_before", dark = false) { reviewed() }
        composeRule.onNodeWithTag("remote_signer.review.details").performClick()
        composeRule.onRoot().captureRoboImage("src/test/snapshots/remote_signer_review_details_light.png")
    }

    /** Connecting after approval. */
    @Test fun connectingAmoled() = captureFlow("remote_signer_connecting_amoled") { connecting() }

    /** A declined request offers Try again. */
    @Test fun declinedAmoled() =
        captureFlow("remote_signer_declined_amoled") {
            connecting().connectReply.completeExceptionally(Nip46ErrorFfi.Rejected())
        }

    /** Method choice wraps at 200% text in RTL. */
    @Test fun chooseRtlLargeText() = captureFlow("remote_signer_choose_rtl_200", rtl = true, scale = 2f) { }

    /** Account keys for an offline signer: status and the recovery that fits it. */
    @Test fun accountOfflineAmoled() {
        capture("remote_signer_account_offline_amoled") {
            SettingsList { remoteSignerAccountItems(actions(RemoteSignerHealth.Unreachable)) }
        }
    }

    /** A browser check names its host before Open approval page is offered. */
    @Test fun accountApprovalAmoled() {
        val status = RemoteSignerAccountStatus(RemoteSignerHealth.ApprovalNeeded, "https://signer.example/check", "r1")
        capture("remote_signer_account_approval_amoled") {
            val actions = RemoteSignerAccountActions(status, approvalOpened = false, hasSession = true) {}
            SettingsList { remoteSignerAccountItems(actions) }
        }
    }

    /** A healthy signer shows only its status and Disconnect. */
    @Test fun accountConnectedLight() {
        capture("remote_signer_account_connected_light", dark = false) {
            SettingsList { remoteSignerAccountItems(actions(RemoteSignerHealth.Paired)) }
        }
    }

    /** At 200% text, every expanded permission can scroll into view and the approval stays reachable. */
    @Test fun accountPermissionReviewRtlLargeText() {
        val permissions = FakeRemoteSignerSession().reviewPermissions()
        val app = ApplicationProvider.getApplicationContext<Context>()
        val state =
            WhiteNoiseAppState(
                context = app,
                draftStore = DraftStore.forContext(app),
                accountIdHexResolver = { null },
                accounts = emptyList(),
                activeAccountRef = null,
            )
        RuntimeEnvironment.setFontScale(2f)
        try {
            show(dark = true, rtl = true) {
                RemoteSignerAccountDialogs(
                    state,
                    account = "fixture",
                    dialog = null,
                    review = permissions,
                    onDismiss = {},
                )
            }
            composeRule.onNodeWithTag("remote_signer.review.details").performScrollTo().performClick()
            permissions.grants.forEach { grant ->
                composeRule.onNodeWithText(grant, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
            }
            composeRule.onNodeWithTag("remote_signer.account.approve").assertIsDisplayed()
            val baseline = "src/test/snapshots/remote_signer_account_review_amoled_rtl_200.png"
            composeRule.onNode(isDialog()).captureRoboImage(baseline)
        } finally {
            RuntimeEnvironment.setFontScale(1f)
        }
    }

    /** A launch failure is visible in the flow and does not offer the acknowledgement until a launch succeeds. */
    @Test fun approvalLaunchFailureLight() {
        val session = FakeRemoteSignerSession()
        val unconfined = Dispatchers.Unconfined
        val scope = CoroutineScope(unconfined)
        val pairing = RemoteSignerPairing(FakeRemoteSignerHost(session), scope, sessionDispatcher = unconfined)
        try {
            pairing.choose(RemoteSignerMethod.BunkerLink, "bunker://abcdef?relay=wss://relay.example&secret=s")
            session.status = Nip46StatusFfi(Nip46StateFfi.APPROVAL, null, "https://signer.example/check", "r1", 1u)
            pairing.approve()
            var launchSucceeds = false
            show(dark = false) {
                RemoteSignerScreen(pairing, onClose = {}, onOpenApproval = { launchSucceeds })
            }
            composeRule.onNodeWithTag("remote_signer.approval.open").performClick()
            composeRule.onNodeWithTag("remote_signer.approval.done").assertDoesNotExist()
            val app = ApplicationProvider.getApplicationContext<Context>()
            composeRule.onNodeWithText(app.getString(R.string.remote_signer_approval_open_failed)).assertIsDisplayed()
            composeRule.onRoot().captureRoboImage("src/test/snapshots/remote_signer_approval_launch_failed_light.png")
            composeRule.runOnIdle { launchSucceeds = true }
            composeRule.onNodeWithTag("remote_signer.approval.open").performClick()
            composeRule.onNodeWithTag("remote_signer.approval.done").assertIsDisplayed()
        } finally {
            scope.cancel()
        }
    }

    private lateinit var session: FakeRemoteSignerSession

    private fun RemoteSignerPairing.reviewed(): FakeRemoteSignerSession {
        choose(RemoteSignerMethod.BunkerLink, "bunker://abcdef?relay=wss://relay.example&secret=s")
        return session
    }

    private fun RemoteSignerPairing.connecting(): FakeRemoteSignerSession {
        reviewed()
        approve()
        return session
    }

    /** Renders a deterministic fake pairing step using the shared screenshot host. */
    private fun captureFlow(
        name: String,
        dark: Boolean = true,
        rtl: Boolean = false,
        scale: Float = 1f,
        setup: RemoteSignerPairing.() -> Unit,
    ) {
        session = FakeRemoteSignerSession()
        val host = FakeRemoteSignerHost(session)
        // Unconfined throughout, so each step is on screen before the capture.
        val unconfined = Dispatchers.Unconfined
        val pairing = RemoteSignerPairing(host, CoroutineScope(unconfined), sessionDispatcher = unconfined)
        pairing.setup()
        capture(name, dark, rtl, scale) { RemoteSignerScreen(pairing, onClose = {}, onOpenApproval = { true }) }
    }

    /** Captures the single root of a full-screen pairing step. */
    private fun capture(
        name: String,
        dark: Boolean = true,
        rtl: Boolean = false,
        scale: Float = 1f,
        content: @Composable () -> Unit,
    ) {
        show(dark, rtl, scale, content)
        composeRule.onRoot().captureRoboImage("src/test/snapshots/$name.png")
    }

    /** Supplies the same background, direction, and text scale to screen and dialog fixtures. */
    private fun show(
        dark: Boolean = true,
        rtl: Boolean = false,
        scale: Float = 1f,
        content: @Composable () -> Unit,
    ) {
        composeRule.setContent {
            val direction = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                WhiteNoiseTheme(darkTheme = dark, amoled = dark, fontScale = scale) {
                    // Screens draw their own scaffold; bare list sections need the theme background behind them.
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
                }
            }
        }
    }

    private fun actions(health: RemoteSignerHealth) =
        RemoteSignerAccountActions(RemoteSignerAccountStatus(health), approvalOpened = false, hasSession = true) {}
}
