package dev.ipf.whitenoise.android.ui.settings

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AppMarmotRuntime
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.WipeFailureItem
import dev.ipf.whitenoise.android.state.WipeReport
import dev.ipf.whitenoise.android.state.WipeStage
import dev.ipf.whitenoise.android.state.WipeStageReport
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.lang.reflect.Proxy

/** Synthetic-only real-screen captures for the retained backup and raw-share actions. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class AccountKeysBackupScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** The temporary encrypted result uses the common light dialog surface. */
    @Test
    fun encryptedBackupLight() = capture("profile_keys_backup_light", Stage.Preview)

    /** Native-result text is legible on the dark dialog surface. */
    @Test
    fun encryptedBackupDark() = capture("profile_keys_backup_dark", Stage.Preview, dark = true)

    /** AMOLED keeps the shared outline and backup actions. */
    @Test
    fun encryptedBackupAmoled() = capture("profile_keys_backup_amoled", Stage.Preview, dark = true, amoled = true)

    /** At large RTL scale the backup body scrolls and Copy, Export and Hide remain reachable. */
    @Test
    fun encryptedBackupLargeRtl() =
        capture(
            "profile_keys_backup_large_rtl",
            Stage.Preview,
            dark = true,
            largeRtl = true,
        )

    /** The consequence dialog keeps Share alongside the original explicit raw file export. */
    @Test
    fun rawShareConfirmationLight() = capture("profile_keys_raw_share_light", Stage.Raw)

    /** Raw actions wrap under translated or enlarged text without hiding Cancel. */
    @Test
    fun rawShareConfirmationLargeRtl() =
        capture(
            "profile_keys_raw_share_large_rtl",
            Stage.Raw,
            dark = true,
            largeRtl = true,
        )

    /** Both encrypted destinations require matching passphrase inputs in the existing focused dialog. */
    @Test
    fun encryptedDestinationChoicesLight() = capture("profile_keys_backup_choices_light", Stage.Password)

    /** Large secure inputs and both destination choices fit in an RTL viewport. */
    @Test
    fun encryptedDestinationChoicesLargeRtl() =
        capture(
            "profile_keys_backup_choices_large_rtl",
            Stage.Password,
            dark = true,
            largeRtl = true,
        )

    /** The session sheet uses a logout glyph and retains its labelled action. */
    @Test
    fun signOutActionsLight() = capture("profile_keys_signout_actions_light", Stage.SignOut)

    /** The wipe sheet retains all review text and its destructive action. */
    @Test
    fun wipeSheetLight() = capture("profile_keys_wipe_sheet_light", Stage.WipeSheet)

    /** At 200% RTL the real sheet scrolls to its displayed action. */
    @Test
    fun wipeSheetLargeRtl() =
        capture(
            "profile_keys_wipe_sheet_large_rtl",
            Stage.WipeSheet,
            dark = true,
            amoled = true,
            largeRtl = true,
        )

    /** Feedback dismissal and post-wipe dismissal carry matching close/acknowledgment glyphs. */
    @Test
    fun feedbackActionsLight() = captureFeedback(largeRtl = false, outcome = false)

    @Test
    fun feedbackActionsLargeRtl() = captureFeedback(largeRtl = true, outcome = false)

    @Test
    fun wipeOutcomeActionsLight() = captureFeedback(largeRtl = false, outcome = true)

    @Test
    fun wipeOutcomeActionsLargeRtl() = captureFeedback(largeRtl = true, outcome = true)

    private fun captureFeedback(
        largeRtl: Boolean,
        outcome: Boolean,
    ) {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = largeRtl, amoled = largeRtl, fontScale = if (largeRtl) 2f else 1f) {
                    if (outcome) {
                        WipeOutcomeSheet(
                            report =
                                WipeReport(
                                    listOf(
                                        WipeStageReport(
                                            WipeStage.WipingLocalData,
                                            null,
                                            listOf(WipeFailureItem(null, "Synthetic cleanup failure")),
                                        ),
                                    ),
                                ),
                            onDismiss = {},
                        )
                    } else {
                        ProfileKeyFeedbackDialogs(
                            nativeExportError = null,
                            shareErrorDialog = false,
                            saveErrorDialog = true,
                            expiredExportDialog = false,
                            onNativeErrorDismiss = {},
                            onShareErrorDismiss = {},
                            onSaveErrorDismiss = {},
                            onExpiryDismiss = {},
                        )
                    }
                }
            }
        }
        val surface = if (outcome) "wipe_outcome" else "feedback"
        val scale = if (largeRtl) "large_rtl" else "light"
        captureScreenRoboImage("src/test/snapshots/profile_keys_${surface}_actions_$scale.png")
    }

    /** Drives real rows and native output without invoking any external picker, clipboard or chooser. */
    private fun capture(
        name: String,
        stage: Stage,
        dark: Boolean = false,
        amoled: Boolean = false,
        largeRtl: Boolean = false,
    ) {
        val appState = fixtureState()
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = amoled, fontScale = if (largeRtl) 2f else 1f) {
                    AccountKeysScreen(appState = appState, onBack = {})
                }
            }
        }
        if (stage == Stage.SignOut || stage == Stage.WipeSheet) {
            openSessionStage(stage)
            captureScreenRoboImage("src/test/snapshots/$name.png")
            return
        } else if (stage == Stage.Raw) {
            composeRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("profile_keys.export_raw"))
            composeRule.onNodeWithTag("profile_keys.export_raw").performClick()
        } else {
            val exportLabel = context.getString(R.string.export_encrypted_private_key)
            composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(exportLabel))
            composeRule.onNodeWithText(exportLabel).performClick()
            if (stage == Stage.Preview) {
                composeRule.onNodeWithTag("profile_keys.export_password").performTextInput("Synthetic passphrase 48!")
                composeRule
                    .onNodeWithTag("profile_keys.export_confirmation")
                    .performTextInput("Synthetic passphrase 48!")
                composeRule
                    .onNode(
                        hasText(context.getString(R.string.key_export_view_backup)) and
                            hasClickAction() and hasAnyAncestor(isDialog()),
                    ).performClick()
                composeRule.waitUntil(5_000L) {
                    composeRule
                        .onAllNodes(hasText(context.getString(R.string.encrypted_backup_result_title)))
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
            }
        }
        composeRule.onNode(isDialog()).captureRoboImage("src/test/snapshots/$name.png")
    }

    /** Uses displayed controls through the scrollable sheet; keeping the destructive review action reachable. */
    private fun openSessionStage(stage: Stage) {
        val tag = if (stage == Stage.SignOut) "profile_keys.sign_out" else WIPE_ACTION_TAG
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag(tag))
        composeRule.onNodeWithTag(tag).performClick()
        if (stage == Stage.SignOut) {
            composeRule.onNodeWithTag("profile_keys.signout_invitation_keys").assertIsDisplayed()
            return
        }
        composeRule
            .onNode(hasText(context.getString(R.string.sign_out_and_wipe_sheet_title)) and !hasClickAction())
            .assertIsDisplayed()
        val confirmSheetAction =
            hasText(context.getString(R.string.sign_out_and_wipe)) and
                hasClickAction() and !hasTestTag(WIPE_ACTION_TAG)
        composeRule.onNode(confirmSheetAction).performScrollTo().assertIsDisplayed()
    }

    /** Returns only intentionally synthetic public identity and encrypted backup text. */
    private fun fixtureState(): WhiteNoiseAppState {
        val native =
            Proxy.newProxyInstance(
                MarmotInterface::class.java.classLoader,
                arrayOf(MarmotInterface::class.java),
            ) { proxy, method, arguments ->
                when (method.name) {
                    "npub" -> "npub1syntheticpublicidentity"
                    "exportEncryptedSecretKey" -> "ncryptsec1" + "synthetic".repeat(12)
                    "toString" -> "BackupScreenshotProxy"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === arguments?.firstOrNull()
                    else -> error("Unexpected screenshot native call: ${method.name}")
                }
            } as MarmotInterface
        return WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore.forContext(context),
            accountIdHexResolver = { null },
            accounts =
                listOf(
                    AccountSummaryFfi(
                        label = "backup-screenshot",
                        accountIdHex = "a".repeat(64),
                        localSigning = true,
                        externalSigning = false,
                        signedOut = false,
                        running = true,
                        unlinked = false,
                    ),
                ),
            activeAccountRef = "backup-screenshot",
            initialMarmotRuntime = AppMarmotRuntime(rootPath = "test", marmot = native),
        )
    }

    private enum class Stage { Preview, Raw, Password, SignOut, WipeSheet }
}
