package dev.ipf.whitenoise.android.ui.onboarding

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Presentation contracts use synthetic keys and local callbacks; no account, signer, camera or network operation
 * is started.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class WelcomeSignInPresentationTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()

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
        assertEquals(listOf("bob"), resumed)
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
        val key = TextFieldState(SECRET)
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
                    privateKeyState = key,
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
        val key = TextFieldState(SECRET)
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
                    privateKeyState = key,
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
        val key = TextFieldState(SECRET)
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
                    privateKeyState = key,
                )
            }
        }
        composeRule.onNodeWithTag("onboarding.sign_in.action").assertIsEnabled()
        composeRule.runOnIdle { key.setTextAndPlaceCursorAtEnd("") }
        composeRule.onNodeWithTag("onboarding.sign_in.action").assertIsNotEnabled()
    }

    /** Pasting reads the actual local clipboard only after a tap, fills masked text and never starts import. */
    @Test fun pasteFillsOnlyAfterTheUserRequestsIt() {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("synthetic test key", SECRET))
        val key = TextFieldState()
        var imports = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                SignInContent(
                    "",
                    false,
                    null,
                    onIdentityChange = {},
                    onErrorChange = {},
                    onBack = {},
                    onSignIn = { imports++ },
                    privateKeyState = key,
                )
            }
        }
        composeRule.onNodeWithContentDescription(context.getString(R.string.paste)).performClick()
        composeRule.runOnIdle { assertEquals(SECRET, key.text.toString()) }
        assertEquals(0, imports)
        clipboard.clearPrimaryClip()
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

    private fun saved(
        label: String,
        recover: Boolean = false,
    ) = OnboardingSavedAccountUi(label, label, label, "public-$label", null, recover)

    private companion object {
        val SECRET = "nsec1" + "q".repeat(58)
    }
}
