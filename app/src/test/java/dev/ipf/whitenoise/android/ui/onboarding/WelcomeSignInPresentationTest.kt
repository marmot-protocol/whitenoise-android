package dev.ipf.whitenoise.android.ui.onboarding

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.onboarding.remotesigner.BunkerClipboardPaste
import dev.ipf.whitenoise.android.ui.onboarding.remotesigner.FakeRemoteSignerHost
import dev.ipf.whitenoise.android.ui.onboarding.remotesigner.FakeRemoteSignerSession
import dev.ipf.whitenoise.android.ui.onboarding.remotesigner.RemoteSignerEntry
import dev.ipf.whitenoise.android.ui.onboarding.remotesigner.RemoteSignerLinkField
import dev.ipf.whitenoise.android.ui.onboarding.remotesigner.RemoteSignerMethod
import dev.ipf.whitenoise.android.ui.onboarding.remotesigner.RemoteSignerPairing
import dev.ipf.whitenoise.android.ui.onboarding.remotesigner.RemoteSignerPairingHost
import dev.ipf.whitenoise.android.ui.onboarding.remotesigner.clearClipboardIfHolding
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * Presentation contracts use synthetic keys and local callbacks; no account, signer, camera or network operation
 * is started.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class WelcomeSignInPresentationTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** The scanner tracks the input surface center when help and error text wrap. */
    @Test fun scanButtonStaysCenteredOnTheInputSurface() {
        val error = mutableStateOf<Int?>(null)
        composeRule.setContent {
            WhiteNoiseTheme(fontScale = 2f) {
                SignInKeyField(TextFieldState(), false, error.value, {}, {}, {})
            }
        }
        assertScannerAligned("onboarding.sign_in.private_key_container", "onboarding.sign_in.scan")
        composeRule.runOnIdle { error.value = R.string.sign_in_error_public_key }
        assertScannerAligned("onboarding.sign_in.private_key_container", "onboarding.sign_in.scan")
    }

    /** The bunker link shares input alignment with private-key sign-in at large text in RTL. */
    @Test fun bunkerLinkScanButtonTracksTheInputSurface() {
        val pairing =
            RemoteSignerPairing(
                FakeRemoteSignerHost(FakeRemoteSignerSession()),
                CoroutineScope(Dispatchers.Unconfined),
                sessionDispatcher = Dispatchers.Unconfined,
            )
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                WhiteNoiseTheme(fontScale = 2f) { RemoteSignerLinkField(pairing) }
            }
        }
        assertScannerAligned("remote_signer.link.container", "remote_signer.link.scan")
        composeRule.runOnIdle { pairing.linkErrorRes = R.string.remote_signer_error_invalid_link }
        assertScannerAligned("remote_signer.link.container", "remote_signer.link.scan")
    }

    /** Compares the actual secure input container with the sibling QR button. */
    private fun assertScannerAligned(
        fieldTag: String,
        scanTag: String,
    ) {
        val field = composeRule.onNodeWithTag(fieldTag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val scan = composeRule.onNodeWithTag(scanTag).fetchSemanticsNode().boundsInRoot
        assertEquals(field.center.y, scan.center.y, 0.5f)
    }

    /** The native Amber capability moves to Sign In and never appears as an unsolicited Welcome action. */
    @Test fun amberLivesOnSignInAndNeedsItsOwnTap() {
        var imports = 0
        var amber = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                OnboardingContent(
                    "",
                    false,
                    false,
                    {},
                    {},
                    { imports++ },
                    amberSignerAvailable = true,
                    onLoginWithAmber = { amber++ },
                )
            }
        }
        composeRule.onNodeWithTag("onboarding.sign_in.amber").assertDoesNotExist()
        composeRule.onNodeWithTag("onboarding.welcome.sign_in").performScrollTo().performClick()
        composeRule.onNodeWithTag("onboarding.sign_in.amber").performClick()
        assertEquals(1, amber)
        assertEquals(0, imports)
    }

    /** Sign In accurately removes Amber when no signer is installed. */
    @Test fun absentSignerDoesNotOfferAmber() {
        composeRule.setContent {
            WhiteNoiseTheme {
                SignInContent(
                    "",
                    false,
                    null,
                    onIdentityChange = {},
                    onErrorChange = {},
                    onBack = {},
                    onSignIn = {},
                    amberSignerAvailable = false,
                )
            }
        }
        composeRule.onNodeWithTag("onboarding.sign_in.amber").assertDoesNotExist()
    }

    /** The retained-profile picker dispatches only the selected real label to the existing resume callback. */
    @Test fun chooseProfileResumesOnlyTheSelectedLabel() {
        val resumed = mutableListOf<String>()
        composeRule.setContent {
            WhiteNoiseTheme {
                OnboardingSavedAccountActions(listOf(saved("alice"), saved("bob")), null, true, { resumed.add(it) })
            }
        }
        composeRule.onNodeWithText(context.getString(R.string.onboarding_choose_profile)).performClick()
        composeRule.onNodeWithText("bob").performClick()
        composeRule.runOnIdle { assertEquals(listOf("bob"), resumed) }
    }

    /** Recovery remains an explicit acknowledgement, and declining it does not activate or recover an identity. */
    @Test fun retainedRecoveryCancelMakesNoOwnerCall() {
        var calls = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                OnboardingSavedAccountActions(
                    listOf(saved("alice", true)),
                    null,
                    true,
                    { calls++ },
                    { calls++ },
                )
            }
        }
        composeRule.onNodeWithTag(ONBOARDING_SAVED_ACCOUNT_TAG).performClick()
        composeRule.onNodeWithText(context.getString(R.string.cancel)).performClick()
        assertEquals(0, calls)
    }

    /** Submit reads the native text at the tap boundary, even when an older string projection was supplied. */
    @Test fun nativeTextIsTheSubmittedSnapshotAndStaysMasked() {
        val signInField = SignInFieldState(SECRET)
        val imports = mutableListOf<String>()
        composeRule.setContent {
            WhiteNoiseTheme {
                SignInContent(
                    "stale projection",
                    false,
                    null,
                    onIdentityChange = {},
                    onErrorChange = {},
                    onBack = {},
                    onSignIn = {},
                    signInField = signInField,
                    onSignInValue = { imports.add(it) },
                )
            }
        }
        composeRule
            .onNodeWithTag("onboarding.sign_in.private_key")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        composeRule.onNodeWithTag("onboarding.sign_in.action").performClick()
        assertEquals(listOf(SECRET), imports)
    }

    /** Retrying the same key must not report another edit and invalidate a recovery acknowledgement. */
    @Test fun unchangedKeyRetryDoesNotReportAnotherEdit() {
        val signInField = SignInFieldState(SECRET)
        var edits = 0
        var submits = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                SignInContent(
                    SECRET,
                    false,
                    null,
                    onIdentityChange = { edits++ },
                    onErrorChange = {},
                    onBack = {},
                    onSignIn = { submits++ },
                    signInField = signInField,
                )
            }
        }
        composeRule.onNodeWithTag("onboarding.sign_in.action").performClick()
        composeRule.onNodeWithTag("onboarding.sign_in.action").performClick()
        assertEquals(2, submits)
        assertEquals(0, edits)
    }

    /**
     * Accepting a native import clears its text state and immediately prevents a stale key from being submitted
     * again.
     */
    @Test fun ownerClearDisablesTheSignInAction() {
        val signInField = SignInFieldState(SECRET)
        val key = signInField.key
        composeRule.setContent {
            WhiteNoiseTheme {
                SignInContent(
                    SECRET,
                    false,
                    null,
                    onIdentityChange = {},
                    onErrorChange = {},
                    onBack = {},
                    onSignIn = {},
                    signInField = signInField,
                )
            }
        }
        composeRule.onNodeWithTag("onboarding.sign_in.action").assertIsEnabled()
        composeRule.runOnIdle { key.setTextAndPlaceCursorAtEnd("") }
        composeRule.onNodeWithTag("onboarding.sign_in.action").assertIsNotEnabled()
    }

    /** An explicit icon tap fills the masked field once when clipboard access is allowed, without import. */
    @Test fun pasteFillsOnlyAfterTheUserRequestsIt() {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("synthetic test key", SECRET))
        val signInField = SignInFieldState()
        val key = signInField.key
        var imports = 0
        var systemPaste: (() -> Unit)? = null
        val toolbar =
            object : TextToolbar {
                override var status = TextToolbarStatus.Hidden

                override fun showMenu(
                    rect: Rect,
                    onCopyRequested: (() -> Unit)?,
                    onPasteRequested: (() -> Unit)?,
                    onCutRequested: (() -> Unit)?,
                    onSelectAllRequested: (() -> Unit)?,
                ) {
                    systemPaste = onPasteRequested
                    status = TextToolbarStatus.Shown
                }

                override fun hide() {
                    systemPaste = null
                    status = TextToolbarStatus.Hidden
                }
            }
        composeRule.setContent {
            CompositionLocalProvider(LocalTextToolbar provides toolbar) {
                WhiteNoiseTheme {
                    SignInContent(
                        "",
                        false,
                        null,
                        onIdentityChange = {},
                        onErrorChange = {},
                        onBack = {},
                        onSignIn = { imports++ },
                        signInField = signInField,
                    )
                }
            }
        }
        composeRule.runOnIdle { assertEquals("", key.text.toString()) }
        composeRule.onNodeWithContentDescription(context.getString(R.string.paste)).performClick()
        composeRule.runOnIdle {
            assertEquals(SECRET, key.text.toString())
            assertEquals(TextToolbarStatus.Hidden, toolbar.status)
            assertEquals(null, systemPaste)
        }
        assertEquals(0, imports)
        clipboard.clearPrimaryClip()
    }

    /** Resuming a retained Sign In draft after backing out of pairing can retire the original provider copy. */
    @Test fun retainedProviderBunkerLinkCanBeRetiredAfterLeavingAndReopeningSignIn() {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val link = "bunker://test?relay=wss://signer.example"
        var reads = 0
        val item =
            object : ClipData.Item(Uri.parse("content://fixture.signer/link")) {
                override fun coerceToText(context: Context): CharSequence {
                    reads++
                    return link
                }
            }
        clipboard.setPrimaryClip(ClipData("Signer link", arrayOf("text/plain"), item))
        val description = requireNotNull(clipboard.primaryClipDescription)
        ReflectionHelpers.callInstanceMethod<Unit>(
            description,
            "setTimestamp",
            ReflectionHelpers.ClassParameter.from(Long::class.javaPrimitiveType, 1L),
        )
        val signInField = SignInFieldState()
        val entry = mutableStateOf<RemoteSignerEntry?>(null)
        composeRule.setContent {
            WhiteNoiseTheme {
                if (entry.value == null) {
                    OnboardingContent(
                        identity = signInField.key.text.toString(),
                        creatingIdentity = false,
                        signingInBusy = false,
                        onIdentityChange = {},
                        onCreateIdentity = {},
                        onImportIdentity = { error("A bunker link should open remote signer pairing") },
                        signInField = signInField,
                        onUseRemoteSigner = { entry.value = it },
                    )
                }
            }
        }

        composeRule.onNodeWithTag("onboarding.welcome.sign_in").performScrollTo().performClick()
        composeRule.onNodeWithContentDescription(context.getString(R.string.paste)).performClick()
        composeRule.onNodeWithTag("onboarding.sign_in.action").performClick()
        composeRule.onNodeWithTag("onboarding.sign_in.action").assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(link, requireNotNull(entry.value).link)
            entry.value = null
        }
        composeRule.onNodeWithTag("onboarding.welcome.sign_in").performScrollTo().performClick()
        composeRule.onNodeWithTag("onboarding.sign_in.action").performClick()
        composeRule.runOnIdle {
            val request = requireNotNull(entry.value)
            assertEquals(link, request.link)
            completePairing(request)
            assertFalse(clipboard.hasPrimaryClip())
            assertEquals(1, reads)
        }
    }

    /** Completes the real pairing state machine using a synthetic signer and the clipboard cleanup callback. */
    private fun completePairing(request: RemoteSignerEntry) {
        val session = FakeRemoteSignerSession()
        val fakeHost = FakeRemoteSignerHost(session)
        val host =
            object : RemoteSignerPairingHost by fakeHost {
                /** Runs the real clipboard cleanup through the pairing host contract. */
                override fun onBunkerLinkSpent(
                    link: String,
                    paste: BunkerClipboardPaste?,
                ) = clearClipboardIfHolding(context, link, paste)
            }
        val pairing =
            RemoteSignerPairing(
                host,
                CoroutineScope(Dispatchers.Unconfined),
                sessionDispatcher = Dispatchers.Unconfined,
            )
        pairing.choose(RemoteSignerMethod.BunkerLink, request.link, request.clipboardPaste)
        pairing.approve()
        session.connectReply.complete("a1b2c3")
    }

    /** While Amber is awaiting its second prompt both credential import and Back stay gated. */
    @Test fun amberBusyShowsItsActualStageAndBlocksOtherActions() {
        var backs = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                SignInContent(
                    "",
                    true,
                    null,
                    onIdentityChange = {},
                    onErrorChange = {},
                    onBack = { backs++ },
                    onSignIn = {},
                    amberSignerAvailable = true,
                    loggingInWithAmber = true,
                    amberSignInStage = 2,
                )
            }
        }
        composeRule.onNodeWithText(context.getString(R.string.amber_signin_waiting_proof)).assertExists()
        composeRule.onNodeWithTag("onboarding.sign_in.action").assertIsNotEnabled()
        composeRule.onNodeWithTag("onboarding.sign_in.amber").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
        assertEquals(0, backs)
    }

    /** Valid camera output fills the key and waits for the separate import action. */
    @Test fun qrSecretIsFillOnly() = scannerResult(SECRET, busyAtDelivery = false, expected = SECRET)

    /** A public QR is rejected immediately and cannot replace an already entered private key. */
    @Test fun qrPublicKeyDoesNotReplaceSecret() = scannerResult("npub1" + "q".repeat(58), false, SECRET)

    /** A rejected scan shows its error as the key field's supporting text and opens no dialog. */
    @Test fun qrRejectionUsesFieldSupportingTextWithoutDialog() {
        val key = TextFieldState(SECRET)
        val error = mutableStateOf<Int?>(null)
        var deliver: ((String) -> Unit)? = null
        composeRule.setContent {
            WhiteNoiseTheme {
                SignInKeyField(
                    key,
                    false,
                    error.value,
                    {},
                    { error.value = it },
                    {},
                    scannerContent = { _, scan -> deliver = scan },
                )
            }
        }
        composeRule.onNodeWithTag("onboarding.sign_in.scan").performClick()
        composeRule.runOnIdle { checkNotNull(deliver)("npub1" + "q".repeat(58)) }
        composeRule.onNodeWithText(context.getString(R.string.sign_in_error_public_key)).assertIsDisplayed()
        composeRule.onAllNodes(isDialog()).assertCountEquals(0)
        composeRule.runOnIdle { assertEquals(SECRET, key.text.toString()) }
    }

    /** A late camera callback cannot edit credentials once import or an external signer owns the form. */
    @Test fun qrReturnDuringBusyOperationIsIgnored() = scannerResult("nsec1" + "p".repeat(58), true, SECRET)

    /** A disposed sign-in form ignores callbacks from a scanner that was closing with it. */
    @Test fun qrReturnAfterFormDisposalIsIgnored() {
        scannerResult("nsec1" + "p".repeat(58), false, SECRET, disposed = true)
    }

    /** Closing the scanner invalidates both successful and rejected late camera output. */
    @Test fun qrReturnAfterScannerDismissIsIgnored() {
        val key = TextFieldState(SECRET)
        var dismiss: (() -> Unit)? = null
        var deliver: ((String) -> Unit)? = null
        var edits = 0
        var errors = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                SignInKeyField(
                    key,
                    false,
                    null,
                    { edits++ },
                    { errors++ },
                    {},
                    scannerContent = { close, scan ->
                        dismiss = close
                        deliver = scan
                    },
                )
            }
        }
        composeRule.onNodeWithTag("onboarding.sign_in.scan").performClick()
        composeRule.runOnIdle { checkNotNull(dismiss)() }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            checkNotNull(deliver)("nsec1" + "p".repeat(58))
            checkNotNull(deliver)("npub1" + "q".repeat(58))
            assertEquals(SECRET, key.text.toString())
            assertEquals(0, edits)
            assertEquals(0, errors)
        }
    }

    /** A previous camera session cannot dismiss or replace the reopened scanner's result. */
    @Test fun qrOldCallbackAfterReopenIsIgnoredAndCurrentCallbackDeliversOnce() {
        val key = TextFieldState(SECRET)
        var dismiss: (() -> Unit)? = null
        var deliver: ((String) -> Unit)? = null
        var oldDismiss: (() -> Unit)? = null
        var oldDeliver: ((String) -> Unit)? = null
        var edits = 0
        var errors = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                SignInKeyField(
                    key,
                    false,
                    null,
                    { edits++ },
                    { errors++ },
                    {},
                    scannerContent = { close, scan ->
                        dismiss = close
                        deliver = scan
                    },
                )
            }
        }
        composeRule.onNodeWithTag("onboarding.sign_in.scan").performClick()
        composeRule.runOnIdle {
            oldDismiss = checkNotNull(dismiss)
            oldDeliver = checkNotNull(deliver)
            checkNotNull(dismiss)()
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("onboarding.sign_in.scan").performClick()
        composeRule.runOnIdle {
            checkNotNull(oldDismiss)()
            checkNotNull(oldDeliver)("nsec1" + "p".repeat(58))
            checkNotNull(oldDeliver)("npub1" + "q".repeat(58))
            assertEquals(SECRET, key.text.toString())
            assertEquals(0, edits)
            assertEquals(0, errors)
            val currentKey = "nsec1" + "z".repeat(58)
            checkNotNull(deliver)(currentKey)
            checkNotNull(deliver)(SECRET)
            assertEquals(currentKey, key.text.toString())
            assertEquals(1, edits)
            assertEquals(0, errors)
        }
    }

    /** Scanner result. */
    private fun scannerResult(
        payload: String,
        busyAtDelivery: Boolean,
        expected: String,
        disposed: Boolean = false,
    ) {
        val key = TextFieldState(SECRET)
        val busy = mutableStateOf(false)
        val visible = mutableStateOf(true)
        var delivered: ((String) -> Unit)? = null
        var imports = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                if (visible.value) {
                    SignInKeyField(
                        key,
                        busy.value,
                        null,
                        {},
                        {},
                        { imports++ },
                        scannerContent = { _, scan -> delivered = scan },
                    )
                }
            }
        }
        composeRule.onNodeWithTag("onboarding.sign_in.scan").performClick()
        composeRule.runOnIdle {
            busy.value = busyAtDelivery
            visible.value = !disposed
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { checkNotNull(delivered)(payload) }
        composeRule.runOnIdle { assertEquals(expected, key.text.toString()) }
        assertEquals(0, imports)
    }

    /** Builds a saved-account fixture. */
    private fun saved(
        label: String,
        recover: Boolean = false,
    ) = OnboardingSavedAccountUi(label, label, label, "public-$label", null, recover)

    private companion object {
        val SECRET = "nsec1" + "q".repeat(58)
    }
}
