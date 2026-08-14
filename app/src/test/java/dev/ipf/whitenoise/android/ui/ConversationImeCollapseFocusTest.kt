package dev.ipf.whitenoise.android.ui

import android.content.Context
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsProperties.EditableText
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.lifecycleOwner
import dev.ipf.whitenoise.android.ui.conversation.ConversationScreen
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screen-level coverage for the rule that an IME closure is not a composer
 * dismissal signal, asserted against a real [ConversationScreen] composition
 * rather than against its source text.
 *
 * The IME insets are driven through [ViewCompat.dispatchApplyWindowInsets],
 * which is what backs both `WindowInsets.ime` and `WindowInsets.imeAnimationTarget`,
 * so a listener keyed on either one sees this transition. What Robolectric
 * cannot reproduce is the platform's real ordering between an IME animation, a
 * keyboard-to-voice handoff and a Back dispatch — confirm that on a device.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ConversationImeCollapseFocusTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /**
     * A keyboard-to-voice handoff collapses the IME insets to zero while the
     * user is still composing. The composer has to keep focus and its draft
     * across that edge, while an explicit Back still dismisses it — the second
     * half is what stops "never clear focus" from passing as a fix.
     */
    @Test
    fun imeCollapseKeepsTheComposerFocusedButExplicitBackStillDismissesIt() {
        val view = showConversation()
        val composer = composeRule.onNode(hasSetTextAction())

        composer.performClick()
        composeRule.waitForIdle()
        composer.performTextInput(DRAFT)
        composeRule.waitForIdle()
        composer.assertIsFocused()

        dispatchImeBottom(view, 300)
        composer.assertIsFocused()

        dispatchImeBottom(view, 0)

        val draftAfterCollapse =
            composer
                .fetchSemanticsNode()
                .config
                .getOrNull(EditableText)
                ?.text
        composer.assertIsFocused()
        assertEquals(DRAFT, draftAfterCollapse)

        composeRule.runOnUiThread {
            (view.context.lifecycleOwner() as ComponentActivity).onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForIdle()

        val draftAfterBack =
            composer
                .fetchSemanticsNode()
                .config
                .getOrNull(EditableText)
                ?.text
        composer.assertIsNotFocused()
        assertEquals("dismissing the composer must not discard the draft", DRAFT, draftAfterBack)
    }

    @Test
    fun explicitBackWaitsForZeroImeInsetBeforeClearingFocusAndReclaimsTheGap() {
        var navigationCount = 0
        val view = showConversation { navigationCount++ }
        val composer = composeRule.onNode(hasSetTextAction())

        composer.performClick()
        composer.performTextInput(DRAFT)
        dispatchImeBottom(view, 300)
        val openImeComposerBottom = composer.fetchSemanticsNode().boundsInRoot.bottom

        composeRule.runOnUiThread {
            (view.context.lifecycleOwner() as ComponentActivity).onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForIdle()

        composer.assertIsFocused()
        assertEquals("the first Back must not leave the conversation", 0, navigationCount)

        dispatchImeBottom(view, 0)

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composer.fetchSemanticsNode().boundsInRoot.bottom > openImeComposerBottom
        }

        val closedImeComposerBottom = composer.fetchSemanticsNode().boundsInRoot.bottom
        val draftAfterDismissal =
            composer
                .fetchSemanticsNode()
                .config
                .getOrNull(EditableText)
                ?.text
        composer.assertIsNotFocused()
        assertTrue(
            "the composer must move into the released IME area",
            closedImeComposerBottom > openImeComposerBottom,
        )
        assertEquals(DRAFT, draftAfterDismissal)
        assertEquals(0, navigationCount)
        composeRule
            .onRoot()
            .captureRoboImage("src/test/snapshots/ime_back_dismissed_composer_light.png")
    }

    private fun showConversation(onBack: () -> Unit = {}): View {
        val appState = appState()
        val group = group()
        val controller = ConversationController(appState = appState, initialGroup = group)
        val chat =
            ChatListItem(
                group = group,
                latest = null,
                otherMemberAccount = null,
                memberCount = 2,
                memberSnapshot = null,
            )
        lateinit var view: View
        composeRule.setContent {
            view = LocalView.current
            WhiteNoiseTheme {
                ConversationScreen(
                    appState = appState,
                    chat = chat,
                    controller = controller,
                    onBack = onBack,
                )
            }
        }
        composeRule.waitForIdle()
        return view
    }

    private fun dispatchImeBottom(
        view: View,
        bottomPx: Int,
    ) {
        composeRule.runOnUiThread {
            val insets =
                WindowInsetsCompat
                    .Builder()
                    .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, bottomPx))
                    .setVisible(WindowInsetsCompat.Type.ime(), bottomPx > 0)
                    .build()
            ViewCompat.dispatchApplyWindowInsets(view.rootView, insets)
        }
        composeRule.waitForIdle()
    }

    private fun appState() =
        WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore(EmptyDraftPersistence()),
            accountIdHexResolver = { ACCOUNT_ID },
            accounts =
                listOf(
                    AccountSummaryFfi(
                        label = ACCOUNT_REF,
                        accountIdHex = ACCOUNT_ID,
                        localSigning = true,
                        externalSigning = false,
                        signedOut = false,
                        running = true,
                    ),
                ),
            activeAccountRef = ACCOUNT_REF,
        )

    private fun group() =
        AppGroupRecordFfi(
            groupIdHex = GROUP_ID,
            protocolProfile = AppProtocolProfileFfi.LEGACY,
            endpoint = "wss://relay.example",
            profilePresent = true,
            name = "Handoff group",
            description = "",
            admins = listOf(ACCOUNT_ID),
            relays = emptyList(),
            nostrGroupIdHex = "03".repeat(32),
            avatarUrl = null,
            avatarDim = null,
            avatarThumbhash = null,
            imageHashHex = null,
            encryptedMedia =
                AppGroupEncryptedMediaComponentFfi(
                    componentId = 0x8008u,
                    component = "marmot.group.encrypted-media.v1",
                    required = true,
                    version = EncryptedMediaVersionFfi.V1,
                    mediaFormat = "encrypted-media-v1",
                    allowedLocatorKinds = listOf("blossom-v1"),
                    defaultBlobEndpoints =
                        listOf(
                            AppBlobEndpointFfi(
                                locatorKind = "blossom-v1",
                                baseUrl = "https://blossom.example",
                            ),
                        ),
                ),
            disappearingMessageSecs = 0uL,
            archived = false,
            pendingConfirmation = false,
            unrecoverable = false,
            selfMembership = SelfMembershipFfi.MEMBER,
            leaveRequestPending = false,
            leaveRequestedAtMs = null,
            disbanding = false,
            disbandRequest = null,
            disbanded = false,
            welcomerAccountIdHex = null,
            viaWelcomeMessageIdHex = null,
        )

    private class EmptyDraftPersistence : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    private companion object {
        const val ACCOUNT_REF = "personal"
        const val DRAFT = "draft text"
        val ACCOUNT_ID = "01" + "00".repeat(31)
        val GROUP_ID = "04" + "00".repeat(31)
    }
}
