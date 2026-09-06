package dev.ipf.whitenoise.android.ui

import android.content.Context
import android.view.View
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.conversation.CONVERSATION_TOP_BAR_TAG
import dev.ipf.whitenoise.android.ui.conversation.CompactConversationTopBarHeight
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
 * Landscape-with-IME geometry for the real [ConversationScreen]: the measured
 * post-inset viewport drives compact-height chrome, the composer's active
 * draft line and primary actions stay reachable, and closing the IME restores
 * the regular chrome without a blank or zero-height frame.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w780dp-h360dp-land-mdpi")
class ConversationCompactHeightLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun imeCompressionSwitchesToCompactChromeAndKeepsTheComposerOperable() {
        val view = showConversation()
        val regularTopBarHeight = topBarHeight()
        assertTrue(
            "landscape without the IME keeps the regular top bar",
            regularTopBarHeight > with(composeRule.density) { CompactConversationTopBarHeight.toPx() } + 1f,
        )

        val composer = composeRule.onNode(hasSetTextAction())
        composer.performClick()
        composer.performTextInput("multiline draft line one\nline two")
        dispatchImeBottom(view, 200)

        val compactTopBarHeight = topBarHeight()
        assertEquals(
            "the IME-compressed viewport must shrink the top bar",
            with(composeRule.density) { CompactConversationTopBarHeight.toPx() },
            compactTopBarHeight,
            1f,
        )
        // The active draft and every primary composer action stay reachable.
        composer.assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(R.string.send)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(R.string.open_emoji_picker)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(R.string.attach_options)).assertIsDisplayed()
        val postImeViewportBottom = with(composeRule.density) { 360.dp.toPx() } - 200f
        assertTrue(
            "the composer must sit fully inside the post-IME viewport",
            composer.fetchSemanticsNode().boundsInRoot.bottom <= postImeViewportBottom + 1f,
        )

        dispatchImeBottom(view, 0)
        assertEquals(
            "closing the IME restores the regular chrome",
            regularTopBarHeight,
            topBarHeight(),
            1f,
        )
        composer.assertIsDisplayed()
    }

    /**
     * Large-font compact chrome: at 2x font scale the compact viewport must
     * still grow a wrapped draft's editor and keep the primary actions
     * reachable instead of clipping them out of the post-IME remainder.
     */
    @Test
    fun compactViewportStaysOperableAtLargeFontScale() {
        val view = showConversation(fontScale = 2f)
        val composer = composeRule.onNode(hasSetTextAction())
        composer.performClick()
        composer.performTextInput((0 until 30).joinToString(" ") { index -> "large_font_word_$index" })
        dispatchImeBottom(view, 200)

        composer.assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(R.string.send)).assertIsDisplayed()
        val editorHeight = composer.fetchSemanticsNode().boundsInRoot.height
        // One line measured through sp so the threshold scales with the font,
        // not just the display density. The 2x compact viewport is a shared
        // fixed budget: the contract is one viable scaled line for the editor
        // alongside the grown (capped) bar, not multi-line growth.
        val oneScaledLinePx = with(composeRule.density) { 16.sp.toPx() * 2f * 1.2f }
        assertTrue(
            "a 2x-font draft must keep at least one scaled editor line viable, got ${editorHeight}px",
            editorHeight >= oneScaledLinePx,
        )
        val cappedCompactBarPx = with(composeRule.density) { 72.dp.toPx() }
        assertEquals(
            "the compact top bar must grow to its capped 2x-font height so the title line cannot clip",
            cappedCompactBarPx,
            topBarHeight(),
            1f,
        )
    }

    @Test
    fun compactViewportGrowsAndRendersALongWrappedDraft() {
        val view = showConversation()
        val composer = composeRule.onNode(hasSetTextAction())
        composer.performClick()
        val longDraft = (0 until 60).joinToString(" ") { index -> "filler_word_" + "%02d".format(index) }
        composer.performTextInput(longDraft)
        dispatchImeBottom(view, 200)

        val editorHeight = composer.fetchSemanticsNode().boundsInRoot.height
        val oneLinePx = with(composeRule.density) { 24.dp.toPx() }
        assertTrue(
            "a long draft must keep a multi-line editor viewport in the compact chrome, got ${editorHeight}px",
            editorHeight > oneLinePx * 1.8f,
        )
        composer.assertIsDisplayed()
    }

    private fun topBarHeight(): Float =
        composeRule
            .onNodeWithTag(CONVERSATION_TOP_BAR_TAG)
            .fetchSemanticsNode()
            .boundsInRoot.height

    private fun showConversation(fontScale: Float = 1f): View {
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
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                WhiteNoiseTheme {
                    ConversationScreen(
                        appState = appState,
                        chat = chat,
                        controller = controller,
                        onBack = {},
                    )
                }
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
            draftStore = DraftStore(EmptyCompactDraftPersistence()),
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
            name = "Compact group",
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

    private class EmptyCompactDraftPersistence : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    private companion object {
        const val ACCOUNT_REF = "alice"
        val ACCOUNT_ID = "a1".repeat(32)
        val GROUP_ID = "b2".repeat(32)
    }
}
