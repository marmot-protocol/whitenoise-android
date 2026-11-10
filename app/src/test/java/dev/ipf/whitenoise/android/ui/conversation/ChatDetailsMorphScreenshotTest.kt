package dev.ipf.whitenoise.android.ui.conversation

import android.app.Application
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalCursorBlinkEnabled
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.GroupMemberSnapshot
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerBar
import dev.ipf.whitenoise.android.ui.group.GroupDetailsScreen
import dev.ipf.whitenoise.android.ui.settings.rememberRouteMotionModifier
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ErrorCollector
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Temporal pixels for the details page growing out of the composer and shrinking back into it.
 *
 * The production morph, route slide, composer and details screen are reused for a group and a
 * direct message; only the conversation record and roster are fixtures.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
@OptIn(ExperimentalCoroutinesApi::class)
class ChatDetailsMorphScreenshotTest {
    private val scheduler = TestCoroutineScheduler()

    @get:Rule val rule = createComposeRule(effectContext = StandardTestDispatcher(scheduler))

    @get:Rule val visualErrors = ErrorCollector()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private var previousScale: String? = null
    private var showDetails by mutableStateOf(false)
    private var committedDetails = false
    private var draft = TextFieldValue(DRAFT, selection = TextRange(DRAFT.length))
    private var composer: LayoutCoordinates? = null
    private var morph: ChatDetailsMorph? = null

    /** Enabled system motion makes temporal pixels independent of the runner's animator default. */
    @Before
    fun enableMotion() {
        previousScale = Settings.Global.getString(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE)
        Settings.Global.putFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }

    @After
    fun restoreMotion() {
        rule.mainClock.autoAdvance = true
        Settings.Global.putString(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, previousScale)
    }

    @Test
    fun groupRoundTripLight() = captureRoundTrip("group_light", dm = false, dark = false)

    @Test
    fun groupRoundTripDark() = captureRoundTrip("group_dark", dm = false, dark = true)

    @Test
    fun groupRoundTripAmoled() = captureRoundTrip("group_amoled", dm = false, dark = true, amoled = true)

    @Test
    fun groupRoundTripRtl() = captureRoundTrip("group_rtl", dm = false, dark = false, rtl = true)

    @Test
    fun dmRoundTripLight() = captureRoundTrip("dm_light", dm = true, dark = false)

    @Test
    fun dmRoundTripAmoled() = captureRoundTrip("dm_amoled", dm = true, dark = true, amoled = true)

    @Suppress("LongParameterList")
    private fun captureRoundTrip(
        variant: String,
        dm: Boolean,
        dark: Boolean,
        amoled: Boolean = false,
        rtl: Boolean = false,
    ) {
        renderRoute(dm, dark, amoled, rtl)
        val settledTranscript = pixels()
        captureOpen(variant)
        captureClose(variant)
        assertTrue(
            "The round trip must land on the same transcript pixels",
            settledTranscript.contentEquals(pixels()),
        )
        assertEquals(DRAFT, draft.text)
    }

    /** Mirrors ConversationScreen: the morph owns the details change and the route slide stays still. */
    private fun renderRoute(
        dm: Boolean,
        dark: Boolean,
        amoled: Boolean,
        rtl: Boolean,
    ) {
        val appState = appState()
        val controller =
            ConversationController(
                appState = appState,
                initialGroup = group(dm),
                initialMemberSnapshot = GroupMemberSnapshot(members(dm)),
            )
        rule.setContent {
            CompositionLocalProvider(
                LocalCursorBlinkEnabled provides false,
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = amoled) {
                    Surface(Modifier.fillMaxSize().testTag(TAG)) {
                        SideEffect { committedDetails = showDetails }
                        val detailsMorph =
                            rememberChatDetailsMorph(
                                showDetails = showDetails,
                                ownerKey = controller,
                                motionEnabled = true,
                                composerBounds = { composer?.takeIf { it.isAttached }?.boundsInWindow() },
                            )
                        morph = detailsMorph
                        val routeMotion =
                            rememberRouteMotionModifier(
                                target = showDetails,
                                depth = if (showDetails) 1 else 0,
                                ownerKey = controller,
                                animationsEnabled = true,
                                suppressMotion = detailsMorph.active,
                            )
                        Box(Modifier.fillMaxSize().then(detailsMorph.modifier).then(routeMotion)) {
                            if (showDetails) {
                                GroupDetailsScreen(appState, controller, onBack = {}, onLeft = {})
                            } else {
                                ComposerBar(
                                    replyingTo = null,
                                    messageTextCopy = MessageTextCopy.Default,
                                    onCancelReply = {},
                                    onSend = { _, _ -> error("The temporal fixture must never send") },
                                    initialDraft = draft,
                                    onDraftChange = { value, _ -> draft = value },
                                    modifier =
                                        Modifier
                                            .align(Alignment.BottomCenter)
                                            .onGloballyPositioned { composer = it },
                                )
                            }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
    }

    private fun captureOpen(variant: String) {
        val start = rule.mainClock.currentTime
        rule.runOnIdle {
            showDetails = true
            Snapshot.sendApplyNotifications()
        }
        captureFrames("open_$variant", start, expectDetails = true)
    }

    /** Back asks the morph first; the page records one inert frame before the route really changes. */
    private fun captureClose(variant: String) {
        rule.runOnIdle { requireNotNull(morph).close { showDetails = false } }
        var frames = 0
        while (committedDetails) {
            assertTrue("The close must commit within the capture frame limit", frames++ < MAX_CAPTURE_FRAMES)
            // The runner draws only on capture; a device draws every frame, which is when the page records.
            pixels()
            scheduler.runCurrent()
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
        }
        // The first close frame has already committed; time the remaining frames from it.
        captureFrames(
            "close_$variant",
            rule.mainClock.currentTime - FRAME_MILLIS,
            expectDetails = false,
            committed = true,
        )
    }

    /** First committed frame, measured interpolation and the settled route on a paused clock. */
    private fun captureFrames(
        name: String,
        start: Long,
        expectDetails: Boolean,
        committed: Boolean = false,
    ) {
        if (!committed) {
            scheduler.runCurrent()
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
        }
        assertEquals(expectDetails, committedDetails)
        rule.runOnIdle { assertTrue("The morph must own this change", requireNotNull(morph).active) }
        capture("first_$name")
        val first = pixels()
        rule.mainClock.advanceTimeBy(start + MID_MILLIS - rule.mainClock.currentTime)
        rule.waitForIdle()
        val middle = pixels()
        capture("mid_$name")
        rule.mainClock.advanceTimeBy(start + SETTLED_MILLIS - rule.mainClock.currentTime)
        rule.waitForIdle()
        val settled = pixels()
        capture("settled_$name")
        rule.runOnIdle {
            assertFalse("A settled morph must release the route slide and touches", requireNotNull(morph).active)
        }
        assertFalse("The card must move between the first frame and the midpoint", first.contentEquals(middle))
        assertFalse("The midpoint must still be in flight", middle.contentEquals(settled))
    }

    private fun capture(name: String) {
        visualErrors.checkSucceeds {
            rule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/chat_details_morph_$name.png")
        }
    }

    private fun pixels(): IntArray {
        val image = rule.onNodeWithTag(TAG).captureToImage()
        return IntArray(image.width * image.height).also { image.readPixels(it) }
    }

    private fun appState() =
        WhiteNoiseAppState(
            context = app,
            draftStore = DraftStore(EmptyDraftPersistence),
            accountIdHexResolver = { reference -> SELF_HEX.takeIf { reference.equals(SELF_HEX, ignoreCase = true) } },
            accounts =
                listOf(
                    AccountSummaryFfi(
                        label = ACCOUNT_REF,
                        accountIdHex = SELF_HEX,
                        localSigning = true,
                        externalSigning = false,
                        signedOut = false,
                        running = true,
                        unlinked = false,
                    ),
                ),
            activeAccountRef = ACCOUNT_REF,
        )

    private fun group(dm: Boolean) =
        AppGroupRecordFfi(
            selfMembership = SelfMembershipFfi.MEMBER,
            groupIdHex = GROUP_HEX,
            protocolProfile = AppProtocolProfileFfi.LEGACY,
            profilePresent = false,
            endpoint = "endpoint",
            name = if (dm) "" else "Weekend hikers",
            description = "Plans, trail conditions and carpools.",
            admins = listOf(SELF_HEX),
            relays = listOf("wss://relay.example.com"),
            nostrGroupIdHex = "nostr-$GROUP_HEX",
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
                    defaultBlobEndpoints = listOf(AppBlobEndpointFfi("blossom-v1", "https://blossom.example.com")),
                ),
            archived = false,
            pendingConfirmation = false,
            unrecoverable = false,
            welcomerAccountIdHex = null,
            viaWelcomeMessageIdHex = null,
            disappearingMessageSecs = 0uL,
            leaveRequestPending = false,
            leaveRequestedAtMs = null,
            leaveBlocked = false,
            disbanding = false,
            disbanded = false,
            disbandRequest = null,
        )

    private fun members(dm: Boolean) =
        (if (dm) listOf(SELF_HEX, "a") else listOf(SELF_HEX, "a", "b", "c")).map { id ->
            val local = id == SELF_HEX
            AppGroupMemberRecordFfi(
                memberIdHex = if (local) id else id.repeat(64),
                account = if (local) ACCOUNT_REF else null,
                local = local,
            )
        }

    private object EmptyDraftPersistence : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    private companion object {
        const val ACCOUNT_REF = "account-a"
        const val SELF_HEX = "1111111111111111111111111111111111111111111111111111111111111111"
        const val GROUP_HEX = "group-a"
        const val DRAFT = "Trail plan for Saturday"
        const val TAG = "chat-details-morph-viewport"
        const val FRAME_MILLIS = 16L
        const val MID_MILLIS = 112L
        const val SETTLED_MILLIS = CHAT_DETAILS_MORPH_MILLIS + 64L
        const val MAX_CAPTURE_FRAMES = 6
    }
}
