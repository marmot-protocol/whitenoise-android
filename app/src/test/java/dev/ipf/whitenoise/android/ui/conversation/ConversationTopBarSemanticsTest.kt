package dev.ipf.whitenoise.android.ui.conversation

import android.content.Context
import androidx.compose.material3.Surface
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.core.GroupTitleCopy
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.MarmotWindowTestFakes
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.testing.PerformanceTestTags
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ConversationTopBarSemanticsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** The full title is one accessible 48dp action and preserves its performance selector. */
    @Test
    fun performanceSelectorIsClickableWithoutReplacingAccessibilityDescription() {
        val appState = appState()
        val controller = ConversationController(appState = appState, initialGroup = group())
        var openDetailsCalls = 0
        render(appState, controller, onOpenDetails = { openDetailsCalls += 1 })

        composeRule
            .onNodeWithTag(PerformanceTestTags.OPEN_GROUP_DETAILS)
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertContentDescriptionEquals(OPEN_DETAILS_DESCRIPTION)
            .performClick()
        composeRule.runOnIdle { assertEquals(1, openDetailsCalls) }
        composeRule
            .onNodeWithTag("conversation.header.avatar", useUnmergedTree = true)
            .assertWidthIsEqualTo(40.dp)
            .assertHeightIsEqualTo(40.dp)
        composeRule
            .onNodeWithTag(CONVERSATION_TOP_BAR_TAG)
            .captureRoboImage("src/test/snapshots/conversation_header_regular_light.png")
    }

    /** Compact windows retain the native smaller avatar and 48dp details target; timer follows the 12dp design. */
    @Test
    fun compactHeaderKeepsAccessibleDetailsAndNativeTimer() {
        val appState = appState()
        appState.markDisappearingTooltipShown()
        val controller = ConversationController(appState, group().copy(disappearingMessageSecs = 60uL))
        render(appState, controller, compactHeight = true)
        composeRule.onNodeWithTag(PerformanceTestTags.OPEN_GROUP_DETAILS).assertHeightIsAtLeast(48.dp)
        composeRule
            .onNodeWithTag("conversation.header.avatar", useUnmergedTree = true)
            .assertWidthIsEqualTo(28.dp)
            .assertHeightIsEqualTo(28.dp)
        composeRule
            .onNodeWithTag("conversation.header.timer", useUnmergedTree = true)
            .assertWidthIsEqualTo(12.dp)
            .assertHeightIsEqualTo(12.dp)
        composeRule
            .onNodeWithTag(CONVERSATION_TOP_BAR_TAG)
            .captureRoboImage("src/test/snapshots/conversation_header_compact_timer_light.png")
    }

    /** MDK's prepared title replaces the app's own projection once the window installs its first sidecar. */
    @Test
    fun preparedWindowTitleReplacesFallback() {
        val appState = appState()
        val controller = ConversationController(appState = appState, initialGroup = group())
        controller.window.install(MarmotWindowTestFakes.conversationFrame("Prepared by MDK"))
        render(appState, controller)

        composeRule.onNodeWithText("Prepared by MDK").assertExists()
        composeRule.onNodeWithText("Benchmark group").assertDoesNotExist()
        composeRule
            .onNodeWithTag(CONVERSATION_TOP_BAR_TAG)
            .captureRoboImage("src/test/snapshots/conversation_header_prepared_title_light.png")
    }

    /** Hydration cannot replace the opening route title until the existing route owner releases its freeze. */
    @Test
    fun frozenRouteRetainsTitleUntilTransitionCompletes() {
        val appState = appState()
        val controller = ConversationController(appState, group())
        val frozen = mutableStateOf(true)
        render(appState, controller, frozen = frozen)
        composeRule.runOnIdle { controller.applyGroupStateForTest(group().copy(name = "Hydrated group")) }
        composeRule.onNodeWithText("Benchmark group").assertExists()
        composeRule.onNodeWithText("Hydrated group").assertDoesNotExist()
        composeRule.runOnIdle { frozen.value = false }
        composeRule.onNodeWithText("Hydrated group").assertExists()
        composeRule.onNodeWithText("Benchmark group").assertDoesNotExist()
    }

    /** Real controller presentation with inert navigation; no native reads or mutations are supplied. */
    private fun render(
        appState: WhiteNoiseAppState,
        controller: ConversationController,
        compactHeight: Boolean = false,
        frozen: State<Boolean> = mutableStateOf(false),
        onOpenDetails: () -> Unit = {},
    ) {
        val searchFocusRequester = FocusRequester()
        composeRule.setContent {
            WhiteNoiseTheme {
                Surface {
                    ConversationTopBar(
                        selectionMode = false,
                        selectedCount = 0,
                        onCloseSelection = {},
                        searchOpen = false,
                        searchQuery = "",
                        onSearchQueryChange = {},
                        onClearSearch = {},
                        onCloseSearch = {},
                        onSearchAction = {},
                        searchFocusRequester = searchFocusRequester,
                        appState = appState,
                        controller = controller,
                        groupTitleCopy = GroupTitleCopy.Default,
                        openedAsDmHint = false,
                        freezeRoutePresentation = frozen.value,
                        openDetailsDescription = OPEN_DETAILS_DESCRIPTION,
                        onOpenDetails = onOpenDetails,
                        onBack = {},
                        compactHeight = compactHeight,
                        performanceSelectorsEnabled = true,
                    )
                }
            }
        }
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
            name = "Benchmark group",
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
        const val OPEN_DETAILS_DESCRIPTION = "Open group details"
        val ACCOUNT_ID = "01" + "00".repeat(31)
        val GROUP_ID = "04" + "00".repeat(31)
    }
}
