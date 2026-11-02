package dev.ipf.whitenoise.android.ui.screenshot

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
import dev.ipf.marmotkit.UserProfileMetadataFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.notifications.NotificationChannels
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.GroupMemberSnapshot
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.awaitConversationCondition
import dev.ipf.whitenoise.android.ui.conversation.ConversationScreen
import dev.ipf.whitenoise.android.ui.profile.ProfileSheet
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLog
import java.io.File

/** Actual caller navigation: no substitute route or screen, no protocol mutations. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class DetailRouteMotionScreenshotTest {
    @get:Rule val rule = createComposeRule()
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val captureFrames = System.getenv("ANIMATION_CAPTURE_FRAMES") == "1"
    private var activeController: ConversationController? = null
    private var routeBackground: Int? = null

    /** Releases the conversation controller that the test created, if any. */
    @After fun closeController() {
        activeController?.onCleared()
    }

    /** Profile sheet route motion into its notification scope on the light theme. */
    @Test fun profileLight() = capture("profile_light", profile = true)

    /** Profile sheet route motion into its notification scope on the dark theme. */
    @Test fun profileDark() = capture("profile_dark", profile = true, dark = true)

    /** Profile sheet route motion into its notification scope on the AMOLED theme. */
    @Test fun profileAmoled() = capture("profile_amoled", profile = true, dark = true, amoled = true)

    /** Profile sheet route motion under RTL at double font scale on the AMOLED theme. */
    @Test fun profileLargeRtl() = capture("profile_large_rtl", profile = true, dark = true, amoled = true, rtl = true)

    /** Conversation route motion between details and its notification settings on the light theme. */
    @Test fun conversationLight() = capture("conversation_light")

    /** Conversation route motion between details and its notification settings on the dark theme. */
    @Test fun conversationDark() = capture("conversation_dark", dark = true)

    /** Conversation route motion between details and its notification settings on the AMOLED theme. */
    @Test fun conversationAmoled() = capture("conversation_amoled", dark = true, amoled = true)

    /** Conversation route motion under RTL at double font scale on the AMOLED theme. */
    @Test fun conversationLargeRtl() = capture("conversation_large_rtl", dark = true, amoled = true, rtl = true)

    /**
     * Drives the real route from its source to the notification destination and back, capturing the first, mid and
     * settled frames of each transition. It ends by showing the app lock mid-flight and asserting the route goes away.
     */
    @Suppress("LongMethod") // One native fixture binds setup and first/middle/settled capture points.
    private fun capture(
        name: String,
        profile: Boolean = false,
        dark: Boolean = false,
        amoled: Boolean = false,
        rtl: Boolean = false,
    ) {
        if (!profile) NotificationChannels.ensureChannels(app)
        val state =
            appState(
                profileHex = PEER_HEX,
                profile = UserProfileMetadataFfi("Robin", "Robin", null, null, null, null, null),
            )
        val group = group(admin = true)
        val members = GroupMemberSnapshot(groupMembers())
        val controller = ConversationController(state, initialGroup = group, initialMemberSnapshot = members)
        activeController = controller
        controller.markAuthoritativeTimelinePublishedForTest()
        val chat = ChatListItem(group, null, null, 6, members)
        state.presentDiscoveredProfile(
            PEER_NPUB,
            UserProfileMetadataFfi("Robin", "Robin", null, null, null, null, null),
        )
        rule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = amoled, fontScale = if (rtl) 2f else 1f) {
                    val background = MaterialTheme.colorScheme.background.toArgb()
                    SideEffect { routeBackground = background }
                    if (profile) {
                        ProfileSheet(state, PEER_NPUB, { _, _ -> }, {}, {})
                    } else {
                        ConversationScreen(state, chat, controller, {})
                    }
                }
            }
        }
        rule.waitForIdle()
        if (!profile) {
            captureSource("${name}_entry", profile)
            rule.mainClock.autoAdvance = false
            rule.onNodeWithContentDescription(app.getString(R.string.details)).performClick()
            rule.mainClock.advanceTimeByFrame()
            captureTransition("${name}_entry", profile)
            rule.mainClock.autoAdvance = true
        }
        val action =
            if (profile) {
                rule.onNodeWithTag("person_profile.notifications")
            } else {
                rule.onNodeWithText(app.getString(R.string.sounds_and_notifications))
            }
        action.performScrollTo()
        captureSource(name, profile)
        rule.mainClock.autoAdvance = false
        val preparationLogOffset = ShadowLog.getLogsForTag("ConversationSettings").size
        action.performClick()
        rule.mainClock.advanceTimeByFrame()
        if (!profile) awaitNotificationCategories(preparationLogOffset)
        captureTransition(name, profile)
        val destinationTitle =
            app.getString(
                if (profile) R.string.profile_notification_scope_detail else R.string.sounds_and_notifications,
            )
        rule.onNodeWithText(destinationTitle).assertExists()
        // Back is routed by the production child, not by a test-only target setter.
        rule.onNodeWithContentDescription(app.getString(R.string.back)).performClick()
        rule.mainClock.advanceTimeByFrame()
        action.assertExists()
        captureTransition("${name}_back", profile)
        action.assertIsDisplayed()
        if (!profile) {
            rule.onNodeWithContentDescription(app.getString(R.string.back)).performClick()
            rule.mainClock.advanceTimeByFrame()
            captureTransition("${name}_entry_back", profile)
            rule.onNodeWithContentDescription(app.getString(R.string.details)).assertExists()
            rule.onNodeWithContentDescription(app.getString(R.string.details)).performClick()
            rule.mainClock.advanceTimeBy(240)
            // Scrolling is setup; pause again before the next timed navigation/lock assertion.
            rule.mainClock.autoAdvance = true
            action.performScrollTo()
            rule.mainClock.autoAdvance = false
        }
        action.performClick()
        rule.mainClock.advanceTimeByFrame()
        val protectedDestination = rule.onNodeWithText(destinationTitle)
        protectedDestination.assertExists()
        // Real observable lock state removes the protected route immediately, even mid-flight.
        rule.runOnIdle {
            WhiteNoiseAppState::class.java
                .getDeclaredMethod("setAppLockScreenVisible", Boolean::class.javaPrimitiveType!!)
                .apply { isAccessible = true }
                .invoke(state, true)
        }
        rule.mainClock.advanceTimeByFrame()
        protectedDestination.assertDoesNotExist()
    }

    /** Wait for real platform preparation without advancing the measured animation. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun awaitNotificationCategories(logOffset: Int) {
        val animationOrigin = rule.mainClock.currentTime

        /** Returns the log lines since [logOffset] that mark the end of category preparation. */
        fun terminalRecords() =
            ShadowLog
                .getLogsForTag("ConversationSettings")
                .drop(logOffset)
                .map { it.msg }
                .filter { "stage=prepare_total " in it }
        try {
            awaitConversationCondition(timeoutMs = 5_000) {
                rule.runOnUiThread { rule.mainClock.scheduler.runCurrent() }
                rule.waitForIdle()
                terminalRecords().isNotEmpty()
            }
        } catch (failure: AssertionError) {
            val trace =
                ShadowLog.getLogsForTag("ConversationSettings").drop(logOffset).joinToString("\n") { item ->
                    item.msg + (item.throwable?.stackTraceToString()?.let { "\n$it" } ?: "")
                }
            throw AssertionError("category preparation did not finish:\n$trace", failure)
        }
        assertEquals(
            "platform readiness keeps the measured animation origin",
            animationOrigin,
            rule.mainClock.currentTime,
        )
        val records = terminalRecords()
        val diagnostics =
            ShadowLog.getLogsForTag("ConversationSettings").drop(logOffset).joinToString("\n") { item ->
                item.msg + (item.throwable?.stackTraceToString()?.let { "\n$it" } ?: "")
            }
        assertTrue("category preparation must succeed:\n$diagnostics", records.all { "outcome=ok" in it })
        // prepare_total precedes the main continuation that assigns the rows.
        rule.runOnIdle { }
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithText(app.getString(R.string.notification_categories_loading)).assertDoesNotExist()
        rule.onNodeWithText(app.getString(R.string.notification_channel_mentions)).assertExists()
    }

    /** Diagnostic frame sets are opt-in; only the three registered goldens participate in CI verification. */
    private fun captureSource(
        name: String,
        profile: Boolean,
    ) {
        if (!captureFrames) return
        val frames = File("build/detail-route-motion/$name").apply { mkdirs() }
        val node = if (profile) rule.onNode(isDialog()) else rule.onRoot()
        node.captureToImage().asAndroidBitmap().captureRoboImage(File(frames, "source.png").path)
    }

    /**
     * Steps through 21 frames of the running route transition, checking that the first frame exposes the themed
     * backplate at the edge the moving page vacates, and saves goldens for frames 0, 6 and 20 (or every frame).
     */
    private fun captureTransition(
        name: String,
        profile: Boolean,
    ) {
        val frames = if (captureFrames) File("build/detail-route-motion/$name").apply { mkdirs() } else null
        for (frame in 0..20) {
            if (captureFrames || frame in listOf(0, 6, 20)) {
                val node = if (profile) rule.onNode(isDialog()) else rule.onRoot()
                val bitmap = node.captureToImage().asAndroidBitmap()
                if (frame == 0) {
                    val exposedLeft = name.endsWith("_back") == name.contains("_rtl")
                    val edge = if (exposedLeft) 1 else bitmap.width - 2
                    for (y in listOf(bitmap.height / 4, bitmap.height / 2, bitmap.height * 3 / 4)) {
                        assertEquals(
                            "$name must expose its themed backplate, not the window behind the moving page",
                            checkNotNull(routeBackground),
                            bitmap.getPixel(edge, y),
                        )
                    }
                }
                if (frames != null) bitmap.captureRoboImage(File(frames, "frame-%03d.png".format(frame)).path)
                if (frame in listOf(0, 6, 20)) {
                    bitmap.captureRoboImage(
                        "src/test/snapshots/detail_motion_${name}_$frame.png",
                    )
                }
            }
            rule.mainClock.advanceTimeBy(16)
        }
    }

    /** Builds an app state with one local account that resolves and names the given peer profile, if any. */
    private fun appState(
        profileHex: String? = null,
        profile: UserProfileMetadataFfi? = null,
    ): WhiteNoiseAppState =
        WhiteNoiseAppState(
            context = app,
            draftStore = DraftStore(EmptyDraftPersistence),
            accountIdHexResolver = { reference ->
                when {
                    reference.equals(SELF_HEX, ignoreCase = true) || reference == SELF_NPUB -> SELF_HEX
                    profileHex != null &&
                        (reference.equals(profileHex, ignoreCase = true) || reference == PEER_NPUB) -> profileHex
                    else -> null
                }
            },
            accounts = listOf(account()),
            activeAccountRef = ACCOUNT_REF,
            profileReader = { accountIdHex -> profile.takeIf { accountIdHex == profileHex } },
            profileDisplayNameReader = { accountIdHex ->
                if (accountIdHex != profileHex) null else profile?.displayName ?: PEER_SHORT_NPUB
            },
        )

    /** Builds the running, locally signing summary of the active account. */
    private fun account() =
        AccountSummaryFfi(
            label = ACCOUNT_REF,
            accountIdHex = SELF_HEX,
            localSigning = true,
            externalSigning = false,
            signedOut = false,
            running = true,
        )

    /** Builds the Weekend hikers group record, administered by the local account or, if not [admin], the peer. */
    private fun group(admin: Boolean) =
        AppGroupRecordFfi(
            selfMembership = SelfMembershipFfi.MEMBER,
            groupIdHex = GROUP_HEX,
            protocolProfile = AppProtocolProfileFfi.LEGACY,
            profilePresent = false,
            endpoint = "endpoint",
            name = "Weekend hikers",
            description = "Plans, trail conditions, carpools, and photos for the next weekend hike.",
            admins = if (admin) listOf(SELF_HEX) else listOf(PEER_HEX),
            relays = listOf("wss://relay.example.com"),
            nostrGroupIdHex = "nostr-$GROUP_HEX",
            avatarUrl = null,
            avatarDim = null,
            avatarThumbhash = null,
            imageHashHex = null,
            encryptedMedia = encryptedMedia(),
            archived = false,
            pendingConfirmation = false,
            unrecoverable = false,
            welcomerAccountIdHex = null,
            viaWelcomeMessageIdHex = null,
            disappearingMessageSecs = 0uL,
            leaveRequestPending = false,
            leaveRequestedAtMs = null,
            disbanding = false,
            disbanded = false,
            disbandRequest = null,
        )

    /** Lists six members: the local account, the peer profile and four others. */
    private fun groupMembers(): List<AppGroupMemberRecordFfi> =
        listOf(
            member(SELF_HEX, local = true),
            member(PEER_HEX),
            member("b".repeat(64)),
            member("c".repeat(64)),
            member("d".repeat(64)),
            member("e".repeat(64)),
        )

    /** Builds a member record for [memberId], bound to the active account when [local]. */
    private fun member(
        memberId: String,
        local: Boolean = false,
    ) = AppGroupMemberRecordFfi(
        memberIdHex = memberId,
        account = if (local) ACCOUNT_REF else null,
        local = local,
    )

    /** Builds the required encrypted-media component with a single blossom endpoint. */
    private fun encryptedMedia() =
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
                        baseUrl = "https://media.example.com",
                    ),
                ),
        )

    private object EmptyDraftPersistence : DraftPersistence {
        /** Reports no persisted drafts. */
        override fun read(): Map<String, String> = emptyMap()

        /** Discards every draft write. */
        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    private companion object {
        const val ACCOUNT_REF = "account-a"
        const val SELF_HEX = "1111111111111111111111111111111111111111111111111111111111111111"
        const val SELF_NPUB = "npub1zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygse4sl3h"
        const val PEER_HEX = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val PEER_NPUB = "npub1424242424242424242424242424242424242424242424242424qamrcaj"
        const val PEER_SHORT_NPUB = "npub14242424...4qamrcaj"
        const val GROUP_HEX = "group-a"
    }
}
