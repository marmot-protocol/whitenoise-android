package dev.ipf.whitenoise.android.ui.navigation

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
import androidx.compose.ui.geometry.Rect
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
import dev.ipf.whitenoise.android.ui.common.MeasuredControlHandoff
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
 * Temporal pixels for the composer-to-members morph that opens and closes group details.
 *
 * The production shell host, route slide, composer and details screen are reused; only the
 * group record and roster are fixtures.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
@OptIn(ExperimentalCoroutinesApi::class)
class ComposerMembersHandoffScreenshotTest {
    private val scheduler = TestCoroutineScheduler()

    @get:Rule val rule = createComposeRule(effectContext = StandardTestDispatcher(scheduler))

    @get:Rule val visualErrors = ErrorCollector()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private var previousScale: String? = null
    private var showDetails by mutableStateOf(false)
    private var committedDetails = false
    private var draft = TextFieldValue(DRAFT, selection = TextRange(DRAFT.length))
    private var handoff: MeasuredControlHandoff? = null

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
    fun membersRoundTripLight() = captureRoundTrip("light", dark = false)

    @Test
    fun membersRoundTripDark() = captureRoundTrip("dark", dark = true)

    @Test
    fun membersRoundTripAmoled() = captureRoundTrip("amoled", dark = true, amoled = true)

    @Test
    fun membersRoundTripRtl() = captureRoundTrip("rtl", dark = false, rtl = true)

    private fun captureRoundTrip(
        variant: String,
        dark: Boolean,
        amoled: Boolean = false,
        rtl: Boolean = false,
    ) {
        renderRoute(dark, amoled, rtl)
        captureTransition(variant, open = true)
        captureTransition(variant, open = false)
    }

    /** Mirrors ConversationScreen: one route-motion owner swaps the transcript composer for group details. */
    private fun renderRoute(
        dark: Boolean,
        amoled: Boolean,
        rtl: Boolean,
    ) {
        val appState = appState()
        val controller =
            ConversationController(
                appState = appState,
                initialGroup = group(),
                initialMemberSnapshot = GroupMemberSnapshot(members()),
            )
        rule.setContent {
            CompositionLocalProvider(
                LocalCursorBlinkEnabled provides false,
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = amoled) {
                    Surface(Modifier.fillMaxSize().testTag(TAG)) {
                        MainShellNoticeLayout(
                            notice = null,
                            dictationController = appState.conversationDictation,
                            dictationComposerRoute =
                                ConversationDictationComposerRoute(null, null, null, ACCOUNT_REF, true, true),
                            appLockScreenVisible = false,
                            handoffOwner = ACCOUNT_REF,
                            handoffDestination = openChatComposerFabEndpoint(isDm = false, showDetails = showDetails),
                        ) {
                            handoff = LocalComposerFabHandoff.current
                            SideEffect { committedDetails = showDetails }
                            val routeMotion =
                                rememberRouteMotionModifier(
                                    target = showDetails,
                                    depth = if (showDetails) 1 else 0,
                                    ownerKey = controller,
                                    animationsEnabled = true,
                                )
                            Box(Modifier.fillMaxSize().then(routeMotion)) {
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
                                        modifier = Modifier.align(Alignment.BottomCenter).testTag(COMPOSER_TAG),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
    }

    /** First committed frame, measured interpolation and retired decoration on a paused clock. */
    private fun captureTransition(
        variant: String,
        open: Boolean,
    ) {
        val source = rule.runOnIdle { requireNotNull(handoff?.captureOrigin()) }
        val start = rule.mainClock.currentTime
        rule.runOnIdle {
            showDetails = open
            Snapshot.sendApplyNotifications()
        }
        scheduler.runCurrent()
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        assertEquals(open, committedDetails)
        assertEquals(start + 16L, rule.mainClock.currentTime)
        val direction = if (open) "open" else "close"
        capture("${direction}_first_$variant")
        rule.mainClock.advanceTimeBy(80)
        rule.waitForIdle()
        assertEquals(start + 96L, rule.mainClock.currentTime)
        val middle = assertInterpolating(source)
        val middlePixels = pixels()
        capture("${direction}_mid_$variant")
        rule.mainClock.advanceTimeBy(176)
        rule.waitForIdle()
        assertEquals(start + 272L, rule.mainClock.currentTime)
        rule.runOnIdle { assertNull("The morph must retire after the route settles", handoff?.renderedBounds) }
        val landed = endpointBounds(open)
        assertEquals(
            "Opening rises to the members card and closing falls to the composer",
            open,
            landed.top < source.top,
        )
        assertTrue(
            "The in-flight shape must sit strictly between its endpoints",
            middle.top in minOf(source.top, landed.top)..maxOf(source.top, landed.top) &&
                middle.top != source.top &&
                middle.top != landed.top,
        )
        assertFalse(
            "The container morph must change actual pixels before retiring",
            middlePixels.contentEquals(pixels()),
        )
        capture("${direction}_settled_$variant")
        assertEquals(DRAFT, draft.text)
    }

    private fun assertInterpolating(source: Rect): Rect =
        rule.runOnIdle {
            val middle = handoff?.renderedBounds
            assertNotNull("The measured handoff must be active at the captured midpoint", middle)
            requireNotNull(middle).also { assertTrue(it != source) }
        }

    private fun endpointBounds(open: Boolean): Rect =
        rule
            .onNodeWithTag(if (open) MEMBERS_TAG else COMPOSER_TAG, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInWindow

    private fun capture(name: String) {
        visualErrors.checkSucceeds {
            rule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/composer_members_motion_$name.png")
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

    private fun group() =
        AppGroupRecordFfi(
            selfMembership = SelfMembershipFfi.MEMBER,
            groupIdHex = GROUP_HEX,
            protocolProfile = AppProtocolProfileFfi.LEGACY,
            profilePresent = false,
            endpoint = "endpoint",
            name = "Weekend hikers",
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

    private fun members() =
        listOf(SELF_HEX, "a", "b", "c").map { id ->
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
        const val TAG = "composer-members-motion-viewport"
        const val COMPOSER_TAG = "temporal-composer"
        const val MEMBERS_TAG = "chat_info.members"
    }
}
