package dev.ipf.whitenoise.android.ui.screenshot

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotMutableState
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.printToString
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.marmotkit.GroupMemberDetailsFfi
import dev.ipf.marmotkit.GroupRosterFfi
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
import dev.ipf.whitenoise.android.ui.settings.SETTINGS_CARD_MORPH_MILLIS
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.runBlocking
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
import java.util.concurrent.atomic.AtomicBoolean

/** Actual caller navigation: no substitute route or screen, no protocol mutations. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
// Shared native fixture and golden identity bind this route scenario catalog.
@Suppress("LargeClass")
class DetailRouteMotionScreenshotTest {
    @get:Rule val rule = createComposeRule()
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val transitionSources = mutableMapOf<String, Bitmap>()
    private val captureFrames = System.getenv("ANIMATION_CAPTURE_FRAMES") == "1"
    private var activeController: ConversationController? = null
    private var routeBackground: Int? = null
    private var morphBackplate: Int? = null

    private fun fixtureMembers(longRoster: Boolean): List<AppGroupMemberRecordFfi> =
        groupMembers() + if (longRoster) (16..63).map { member(it.toString(16).padStart(64, '0')) } else emptyList()

    private fun detailController(
        state: WhiteNoiseAppState,
        group: AppGroupRecordFfi,
        members: GroupMemberSnapshot,
        fixtureMembers: List<AppGroupMemberRecordFfi>,
    ): ConversationController =
        ConversationController(
            state,
            initialGroup = group,
            initialMemberSnapshot = members,
            groupRosterReader = { _, groupId ->
                GroupRosterFfi(
                    groupIdHex = groupId,
                    members =
                        fixtureMembers.map { member ->
                            GroupMemberDetailsFfi(
                                memberIdHex = member.memberIdHex,
                                account = member.account,
                                local = member.local,
                                isAdmin = member.memberIdHex in group.admins,
                                isSelf = member.memberIdHex == SELF_HEX,
                                npub = "npub-${member.memberIdHex}",
                                displayName = null,
                            )
                        },
                    epoch = 1uL,
                    rosterRevision = 1uL,
                    selfMembership = group.selfMembership,
                    memberCount = fixtureMembers.size.toUInt(),
                    lifecycleState = GroupLifecycleStateFfi.STABLE,
                )
            },
        )

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

    /** The real protected Members route shares the same measured host as other details children. */
    @Test fun membersAmoled() = capture("members_amoled", dark = true, amoled = true, membersRoute = true)

    /** The composer unfolds into composer settings and Back folds them into it, on the AMOLED theme. */
    @Test fun composerAmoled() = captureComposer("composer_amoled", dark = true, amoled = true)

    /** Actual Details children, each opened and closed three times through production controls. */
    @Test fun sharedContentCategoriesAmoled() {
        org.junit.Assume.assumeTrue(captureFrames)
        capture("shared", dark = true, amoled = true, sharedContent = true)
    }

    /** Populated cards use the live timeline projection and the actual category destinations. */
    @Test fun populatedSharedContentCategoriesAmoled() {
        org.junit.Assume.assumeTrue(captureFrames)
        capture("shared_populated", dark = true, amoled = true, sharedContent = true, populatedSharedContent = true)
    }

    /** Long protected roster returns to the retained overview geometry in three real cycles. */
    @Test fun longMembersRetainedScrollAmoled() {
        org.junit.Assume.assumeTrue(captureFrames)
        capture("members_long_amoled", dark = true, amoled = true, membersRoute = true, longRoster = true)
    }

    /** The composer settings card morph on the light theme. */
    @Test fun composerLight() = captureComposer("composer_light")

    /** The conversation title, which offers Details as its click label. */
    private fun opensDetails() =
        SemanticsMatcher("opens details") {
            it.config.getOrNull(SemanticsActions.OnClick)?.label == app.getString(R.string.details)
        }

    // One route matrix keeps capture clocks and causal privacy assertions together.

    /**
     * Drives the real route from its source to the notification destination and back, capturing the first, mid and
     * settled frames of each transition. It ends by showing the app lock mid-flight and asserting the route goes away.
     */
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun capture(
        name: String,
        profile: Boolean = false,
        dark: Boolean = false,
        amoled: Boolean = false,
        rtl: Boolean = false,
        composer: Boolean = false,
        membersRoute: Boolean = false,
        sharedContent: Boolean = false,
        populatedSharedContent: Boolean = false,
        longRoster: Boolean = false,
    ) {
        if (!profile) NotificationChannels.ensureChannels(app)
        val state =
            appState(
                profileHex = PEER_HEX,
                profile = UserProfileMetadataFfi("Robin", "Robin", null, null, null, null, null),
                attachmentRuntime = sharedContent,
                populatedAttachments = populatedSharedContent,
            )
        val group = group(admin = true)
        val fixtureMembers = fixtureMembers(longRoster)
        val members = GroupMemberSnapshot(fixtureMembers)
        val controller = detailController(state, group, members, fixtureMembers)
        // Group management requires the authoritative roster, not the cached opening snapshot.
        if (membersRoute) runBlocking { controller.retryMembers() }
        activeController = controller
        if (sharedContent) {
            // Links are offered only for URLs in the retained timeline, never an empty history.
            val url = "https://example.com/motion-fixture"
            val record =
                dev.ipf.marmotkit.TimelineMessageRecordFfi(
                    clientToken = null,
                    sourceMessageIdHex = "44".repeat(32),
                    messageIdHex = "44".repeat(32),
                    direction = "received",
                    groupIdHex = group.groupIdHex,
                    sender = PEER_HEX,
                    plaintext = url,
                    contentTokens =
                        dev.ipf.marmotkit.MarkdownDocumentFfi(
                            blocks =
                                listOf(
                                    dev.ipf.marmotkit.MarkdownBlockFfi.Paragraph(
                                        listOf(
                                            dev.ipf.marmotkit.MarkdownInlineFfi.Autolink(
                                                url,
                                                dev.ipf.marmotkit.MarkdownAutolinkKindFfi.URI,
                                                dev.ipf.marmotkit.MarkdownLinkDestinationKindFfi.WEB,
                                            ),
                                        ),
                                    ),
                                ),
                            truncated = false,
                            blankLinesBefore = byteArrayOf(0),
                        ),
                    kind = 9uL,
                    tags = emptyList(),
                    sourceEpoch = null,
                    retentionSeconds = null,
                    retentionExpiresAt = null,
                    timelineAt = 1uL,
                    receivedAt = 1uL,
                    sticker = null,
                    replyToMessageIdHex = null,
                    replyPreview = null,
                    mediaJson = null,
                    media =
                        if (populatedSharedContent) {
                            dev.ipf.whitenoise.android.core.MessageAttachments.acceptedOutcomes(
                                listOf(
                                    sharedAttachment("trail.jpg", "image/jpeg"),
                                    sharedAttachment("trail-notes.pdf", "application/pdf"),
                                    sharedAttachment("trail-voice.ogg", "audio/ogg"),
                                ),
                            )
                        } else {
                            emptyList()
                        },
                    agentTextStreamJson = null,
                    poll = null,
                    groupSystem = null,
                    hasReports = false,
                    edit = null,
                    reactions = dev.ipf.marmotkit.TimelineReactionSummaryFfi(emptyList(), emptyList()),
                    deleted = false,
                    deletionSource = dev.ipf.marmotkit.DeletionSourceFfi.UNKNOWN,
                    deletedByMessageIdHex = null,
                    invalidationStatus = null,
                    importedFrom = null,
                    recipientConfirmed = false,
                    historyImportProvenance = null,
                )
            rule.runOnUiThread {
                // Use the same projection/index/publication path as a live received message.
                controller.testApplyLiveTimelineChangesAndRegisterStreams(
                    listOf(
                        dev.ipf.marmotkit.TimelineMessageChangeFfi.Upsert(
                            trigger = dev.ipf.marmotkit.TimelineUpdateTriggerFfi.NEW_MESSAGE,
                            message = record,
                        ),
                    ),
                )
            }
        }
        controller.markAuthoritativeTimelinePublishedForTest()
        val chat = ChatListItem(group, null, null, fixtureMembers.size, members)
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
                    val backplate = MaterialTheme.colorScheme.surfaceContainerLow.toArgb()
                    SideEffect {
                        routeBackground = background
                        morphBackplate = backplate
                    }
                    if (profile) {
                        ProfileSheet(state, PEER_NPUB, { _, _ -> }, {}, {})
                    } else {
                        ConversationScreen(state, chat, controller, {})
                    }
                }
            }
        }
        rule.waitForIdle()
        if (composer) return captureComposerRoute(name)
        if (sharedContent) return captureSharedContentCategories(populatedSharedContent)
        var retainedMemberBounds: androidx.compose.ui.geometry.Rect? = null
        repeat(if (captureFrames || profile) 3 else 1) { cycle ->
            val name = if (captureFrames) "${name}_cycle_$cycle" else name
            if (!profile && (!longRoster || cycle == 0)) {
                captureSource("${name}_entry", profile)
                rule.mainClock.autoAdvance = false
                rule.onNode(opensDetails()).performClick()
                rule.mainClock.advanceTimeByFrame()
                captureTransition("${name}_entry", profile)
                rule.mainClock.autoAdvance = true
            }
            val action =
                if (profile) {
                    rule.onNodeWithTag("person_profile.notifications")
                } else {
                    rule.onNodeWithText(
                        app.getString(
                            if (membersRoute) R.string.see_all_members else R.string.sounds_and_notifications,
                        ),
                    )
                }
            rule.mainClock.autoAdvance = true
            action.performScrollTo()
            if (longRoster) {
                // ScrollTo only reveals the action; it need not move the overview name behind the bar.
                // Establish the collapsed header through the real scroll owner before measuring it.
                repeat(12) {
                    if (rule
                            .onAllNodesWithTag(
                                "chat_info.header_identity",
                                useUnmergedTree = true,
                            ).fetchSemanticsNodes()
                            .isEmpty()
                    ) {
                        rule
                            .onNodeWithTag(
                                "chat_info.list",
                            ).performSemanticsAction(
                                androidx.compose.ui.semantics.SemanticsActions.ScrollBy,
                            ) { scroll -> scroll(0f, 48f) }
                        rule.waitForIdle()
                    }
                }
                action.assertIsDisplayed()
                rule.onNodeWithTag("chat_info.header_identity", useUnmergedTree = true).assertIsDisplayed()
            }
            val retainedActionBounds = action.fetchSemanticsNode().boundsInRoot
            val retainedHeaderBounds =
                if (longRoster) {
                    rule
                        .onNodeWithTag(
                            "chat_info.header_identity",
                            useUnmergedTree = true,
                        ).fetchSemanticsNode()
                        .boundsInRoot
                } else {
                    null
                }
            captureSource(name, profile)
            rule.mainClock.autoAdvance = false
            val preparationLogOffset = ShadowLog.getLogsForTag("ConversationSettings").size
            val rowsPublished = CategoryRowsPublication()
            try {
                action.performClick()
                rule.mainClock.advanceTimeByFrame()
                if (!profile && !membersRoute) awaitNotificationCategories(preparationLogOffset, rowsPublished)
            } finally {
                rowsPublished.close()
            }
            captureTransition(name, profile)
            if (!profile) {
                val destination = rule.onRoot().captureToImage().asAndroidBitmap()
                org.junit.Assert.assertFalse(
                    "Notification destination must replace the group details pixels",
                    destination.sameAs(transitionSources.getValue(name)),
                )
            }
            val destinationTitle =
                app.getString(
                    when {
                        profile -> R.string.profile_notification_scope_detail
                        membersRoute -> R.string.members
                        else -> R.string.sounds_and_notifications
                    },
                )
            if (membersRoute) {
                rule
                    .onNode(
                        androidx.compose.ui.test
                            .hasText(destinationTitle) and
                            androidx.compose.ui.test.SemanticsMatcher.expectValue(
                                androidx.compose.ui.semantics.SemanticsProperties.Heading,
                                Unit,
                            ),
                        useUnmergedTree = true,
                    ).assertExists()
            } else {
                rule.onNodeWithText(destinationTitle).assertExists()
            }
            if (longRoster) {
                // Exercise the real retained Members ScrollState, including a bottom-clipped roster.
                rule.mainClock.autoAdvance = true
                val retainedMember = rule.onNodeWithTag("chat_info.member.${fixtureMembers.last().memberIdHex}")
                retainedMemberBounds?.let { bounds ->
                    assertEquals(
                        "Members child must restore its retained scroll",
                        bounds,
                        retainedMember.fetchSemanticsNode().boundsInRoot,
                    )
                }
                retainedMember.performScrollTo().assertIsDisplayed()
                retainedMemberBounds = retainedMember.fetchSemanticsNode().boundsInRoot
                rule.mainClock.autoAdvance = false
            }
            // Back is routed by the production child, not by a test-only target setter.
            captureSource("${name}_back", profile)
            rule.onNodeWithContentDescription(app.getString(R.string.back)).performClick()
            rule.mainClock.advanceTimeByFrame()
            captureTransition("${name}_back", profile)
            action.assertIsDisplayed()
            if (longRoster) {
                assertEquals(
                    "Overview scroll must survive protected Members return",
                    retainedActionBounds,
                    action.fetchSemanticsNode().boundsInRoot,
                )
                assertEquals(
                    "Overview header must retain its collapsed geometry",
                    retainedHeaderBounds,
                    rule
                        .onNodeWithTag(
                            "chat_info.header_identity",
                            useUnmergedTree = true,
                        ).fetchSemanticsNode()
                        .boundsInRoot,
                )
            }
            if (!profile && (!longRoster || cycle == 2)) {
                captureSource("${name}_entry_back", profile)
                rule.onNodeWithContentDescription(app.getString(R.string.back)).performClick()
                rule.mainClock.advanceTimeByFrame()
                captureTransition("${name}_entry_back", profile)
                // The closing cards cover the chat and hold back taps until they have folded away.
                rule.mainClock.advanceTimeBy(SETTINGS_CARD_MORPH_MILLIS.toLong())
                try {
                    rule.onNode(opensDetails()).assertExists()
                } catch (failure: AssertionError) {
                    File("build/detail-route-motion").mkdirs()
                    File(
                        "build/detail-route-motion/${name}_return_semantics.txt",
                    ).writeText(rule.onRoot(useUnmergedTree = true).printToString())
                    throw failure
                }
            }
        }
        val action =
            if (profile) {
                rule.onNodeWithTag("person_profile.notifications")
            } else {
                rule.onNodeWithText(
                    app.getString(if (membersRoute) R.string.see_all_members else R.string.sounds_and_notifications),
                )
            }
        val destinationTitle =
            app.getString(
                when {
                    profile -> R.string.profile_notification_scope_detail
                    membersRoute -> R.string.members
                    else -> R.string.sounds_and_notifications
                },
            )
        if (!profile) {
            rule.onNode(opensDetails()).performClick()
            rule.mainClock.advanceTimeBy(240)
            // Scrolling is setup; pause again before the next timed navigation/lock assertion.
            rule.mainClock.autoAdvance = true
            action.performScrollTo()
            rule.mainClock.autoAdvance = false
        }
        action.performClick()
        rule.mainClock.advanceTimeByFrame()
        val protectedDestination = rule.onNodeWithText(destinationTitle, useUnmergedTree = profile)
        // Profile content is composed immediately but its presentation hides merged semantics.
        // The unmerged query verifies its removal on lock. Conversation morphs
        // may still hide their child here; lock removal below must hold for either phase.
        if (profile) protectedDestination.assertExists() else protectedDestination.assertDoesNotExist()
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

    private fun captureSharedContentCategories(populated: Boolean) {
        rule.onNode(opensDetails()).performClick()
        rule.waitForIdle()
        // URL projection runs on Dispatchers.Default; freeze the measured clock only after it arrives.
        rule.waitUntil(timeoutMillis = 5_000) {
            rule
                .onAllNodes(
                    androidx.compose.ui.test
                        .hasTestTag("shared.category.Links"),
                ).fetchSemanticsNodes()
                .size == 1
        }
        listOf("Media", "Documents", "Voice", "Links").forEach { category ->
            repeat(3) { cycle ->
                rule.mainClock.autoAdvance = true
                rule.onNodeWithTag("chat_info.shared_media").performScrollTo()
                val action = rule.onNodeWithTag("shared.category.$category")
                val sourceBounds = action.fetchSemanticsNode().boundsInRoot
                val name = "shared_${if (populated) "populated_" else ""}${category.lowercase()}_cycle_$cycle"
                captureSource(name, profile = false)
                rule.mainClock.autoAdvance = false
                action.performClick()
                rule.mainClock.advanceTimeByFrame()
                captureTransition(name, profile = false)
                rule.onNodeWithTag("shared.destination.$category").assertIsDisplayed()
                if (populated) {
                    when (category) {
                        "Media" -> rule.onNodeWithTag("shared.media.grid").assertIsDisplayed()
                        "Documents" -> rule.onNodeWithText("trail-notes.pdf").assertIsDisplayed()
                        "Voice" -> rule.onNodeWithTag("shared.message.${"44".repeat(32)}#2").assertExists()
                        "Links" ->
                            rule
                                .onNodeWithText(
                                    "https://example.com/motion-fixture",
                                    substring = true,
                                ).assertExists()
                    }
                }
                captureSource("${name}_back", profile = false)
                rule.onNodeWithContentDescription(app.getString(R.string.back)).performClick()
                rule.mainClock.advanceTimeByFrame()
                captureTransition("${name}_back", profile = false)
                action.assertIsDisplayed()
                assertEquals(sourceBounds, action.fetchSemanticsNode().boundsInRoot)
            }
        }
        rule.onNodeWithContentDescription(app.getString(R.string.back)).performClick()
        rule.mainClock.advanceTimeBy(SETTINGS_CARD_MORPH_MILLIS.toLong() + 32L)
        rule.onNode(opensDetails(), useUnmergedTree = true).assertExists()
    }

    /** Drives the conversation into composer settings through the composer's Customize action, and back. */
    private fun captureComposer(
        name: String,
        dark: Boolean = false,
        amoled: Boolean = false,
    ) = capture(name, dark = dark, amoled = amoled, composer = true)

    /** Captures the composer pill unfolding into the composer settings cards and folding back on Back. */
    private fun captureComposerRoute(name: String) {
        val customize = app.getString(R.string.composer_customize)
        // Several composer controls carry the customize action; the first one opens the same route.
        val composer =
            rule
                .onAllNodes(
                    SemanticsMatcher("customizes the composer") { node ->
                        node.config
                            .getOrNull(SemanticsActions.CustomActions)
                            .orEmpty()
                            .any { it.label == customize }
                    },
                    useUnmergedTree = true,
                ).onFirst()
        repeat(if (captureFrames) 3 else 1) { cycle ->
            val name = if (captureFrames) "${name}_cycle_$cycle" else name
            captureSource("${name}_entry", profile = false)
            rule.mainClock.autoAdvance = false
            val action =
                composer.fetchSemanticsNode().config[SemanticsActions.CustomActions].first { it.label == customize }
            // Publish the state write the way input dispatch does, so the next frame composes the route.
            rule.runOnUiThread {
                action.action()
                Snapshot.sendApplyNotifications()
            }
            rule.mainClock.advanceTimeByFrame()
            captureTransition("${name}_entry", profile = false)
            captureSource("${name}_entry_back", profile = false)
            rule.onNodeWithContentDescription(app.getString(R.string.back)).performClick()
            rule.mainClock.autoAdvance = false
            rule.mainClock.advanceTimeByFrame()
            captureTransition("${name}_entry_back", profile = false)
            rule.mainClock.autoAdvance = true
            composer.assertExists()
        }
    }

    /** Wait for real platform preparation without advancing the measured animation. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun awaitNotificationCategories(
        logOffset: Int,
        rowsPublished: CategoryRowsPublication,
    ) {
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
        // prepare_total is logged on the worker before it posts the main continuation that assigns the rows, so the
        // post can land after one idle pass. Pump without advancing the clock until the rows are applied; the single
        // frame below then recomposes them in place of the placeholder.
        awaitConversationCondition(timeoutMs = 5_000) {
            rule.runOnUiThread { rule.mainClock.scheduler.runCurrent() }
            rule.waitForIdle()
            rowsPublished.isPublished
        }
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithText(app.getString(R.string.notification_categories_loading)).assertDoesNotExist()
        rule
            .onNodeWithText(
                app.getString(R.string.notification_channel_mentions),
                useUnmergedTree = true,
            ).assertExists()
    }

    /** Records when the screen applies its prepared category rows, which it holds in private snapshot state. */
    private class CategoryRowsPublication : AutoCloseable {
        private val published = AtomicBoolean(false)
        private val observer =
            Snapshot.registerApplyObserver { changed, _ ->
                val rows = changed.any { state -> (state as? SnapshotMutableState<*>)?.value.isPreparedCategoryList() }
                if (rows) published.set(true)
            }

        val isPublished: Boolean get() = published.get()

        override fun close() = observer.dispose()

        private fun Any?.isPreparedCategoryList(): Boolean {
            val first = (this as? List<*>)?.firstOrNull() ?: return false
            return first.javaClass.simpleName == "PreparedNotificationCategorySetting"
        }
    }

    /** Diagnostic frame sets are opt-in; only the three registered goldens participate in CI verification. */
    private fun captureSource(
        name: String,
        profile: Boolean,
    ) {
        val node = if (profile) rule.onNode(isDialog()) else rule.onRoot()
        val bitmap = node.captureToImage().asAndroidBitmap()
        transitionSources[name] = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        if (!captureFrames) return
        val frames = File("build/detail-route-motion/$name").apply { mkdirs() }
        captureScreenRoboImage(File(frames, "source.png").path)
    }

    /**
     * Steps through 21 frames of the running route transition, checking that the first frame exposes the themed
     * backplate at the edge the moving page or growing card vacates, and saves goldens for frames 0, 6 and 20
     * (or every frame).
     */
    private fun exportTransitionFrame(
        frames: File?,
        name: String,
        frame: Int,
        bitmap: Bitmap,
    ) {
        if (frames != null) captureScreenRoboImage(File(frames, "frame-%03d.png".format(frame)).path)
        if (frame == 0) {
            // Keep the current failing image, rather than diagnosing stale tracked goldens.
            val diagnostic = File("build/detail-route-motion/diagnostics").apply { mkdirs() }
            File(diagnostic, "${name}_first.png").outputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        }
    }

    // Keep exact frame sampling and first, intermediate, settled and native-header assertions together.
    @Suppress("CyclomaticComplexMethod")
    private fun captureTransition(
        name: String,
        profile: Boolean,
    ) {
        val frames = if (captureFrames) File("build/detail-route-motion/$name").apply { mkdirs() } else null
        var intermediate: Bitmap? = null
        var settled: Bitmap? = null
        // Ordinary checks retain their timing; private exports include settled frames after preparation.
        for (frame in 0..(if (captureFrames) 31 else 22)) {
            if (captureFrames || frame in listOf(0, 6, 20)) {
                val node = if (profile) rule.onNode(isDialog()) else rule.onRoot()
                val bitmap = node.captureToImage().asAndroidBitmap()
                if ((profile || name.startsWith("composer_")) &&
                    frame == 6
                ) {
                    intermediate = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                }
                if ((profile || name.startsWith("composer_")) &&
                    frame == 20
                ) {
                    settled = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                }
                exportTransitionFrame(frames, name, frame, bitmap)
                if (frame == 0) {
                    assertFirstFrameBackplate(name, bitmap)
                }
                if (!captureFrames && frame in listOf(0, 6, 20)) {
                    bitmap.captureRoboImage(
                        "src/test/snapshots/detail_motion_${name}_$frame.png",
                    )
                }
            }
            rule.mainClock.advanceTimeBy(16)
        }
        if (profile || name.startsWith("composer_")) {
            val middle = checkNotNull(intermediate)
            org.junit.Assert.assertFalse(
                "$name must move beyond its source pixels",
                middle.sameAs(transitionSources.getValue(name)),
            )
            org.junit.Assert.assertFalse(
                "$name must contain intermediate motion before settling",
                middle.sameAs(checkNotNull(settled)),
            )
            if (name.startsWith("composer_") && name.endsWith("_entry")) {
                // The real route header must already occupy its final coordinates at native opacity.
                // Compare raster ink, not only layout bounds, so an alpha/translation regression fails.
                val title =
                    rule
                        .onNodeWithText(app.getString(R.string.composer_settings), useUnmergedTree = true)
                        .fetchSemanticsNode()
                        .boundsInRoot
                val left = title.left.toInt().coerceAtLeast(0)
                val top = title.top.toInt().coerceAtLeast(0)
                val width = title.width.toInt().coerceAtMost(middle.width - left)
                val height = title.height.toInt().coerceAtMost(middle.height - top)
                org.junit.Assert.assertTrue(
                    "$name must preserve opaque native header ink in place",
                    Bitmap.createBitmap(middle, left, top, width, height).sameAs(
                        Bitmap.createBitmap(checkNotNull(settled), left, top, width, height),
                    ),
                )
            }
        }
    }

    /**
     * Checks that the first frame shows the themed backplate: the chat's routes start as card bubbles over the morph
     * backplate, like nested Settings, and the other routes expose it at the edge the moving page vacates.
     */
    private fun assertFirstFrameBackplate(
        name: String,
        bitmap: Bitmap,
    ) {
        val morph = name.endsWith("_entry") || name.endsWith("_entry_back")
        val exposedLeft = name.endsWith("_back") == name.contains("_rtl")
        val edge = if (morph || exposedLeft) 1 else bitmap.width - 2
        val source = transitionSources[name].takeIf { name.endsWith("_back") }
        if (name.endsWith("_back")) checkNotNull(source) { "Back requires its actual settled source frame" }
        val message =
            if (morph) {
                "$name must draw the card morph over its backplate"
            } else {
                "$name must expose its themed backplate, not the window behind the moving page"
            }
        for (y in listOf(bitmap.height / 4, bitmap.height / 2, bitmap.height * 3 / 4)) {
            // A reverse starts with the outgoing page still covering this edge. Its
            // real source pixels, rather than a theme-color guess, define continuity.
            val expected = source?.getPixel(edge, y) ?: checkNotNull(morphBackplate)
            assertEquals(message, expected, bitmap.getPixel(edge, y))
        }
    }

    /** Typed projected attachment; transport failures remain truthful without native storage/network. */
    private fun sharedAttachment(
        fileName: String,
        mediaType: String,
    ) = dev.ipf.marmotkit.MediaAttachmentReferenceFfi(
        locators = listOf(dev.ipf.marmotkit.MediaLocatorFfi("blossom-v1", "https://media.example.com/$fileName")),
        ciphertextSha256 = "aa".repeat(32),
        plaintextSha256 = "bb".repeat(32),
        nonceHex = "cc".repeat(12),
        fileName = fileName,
        mediaType = mediaType,
        version = EncryptedMediaVersionFfi.V1,
        sourceEpoch = 1uL,
        dim = null,
        thumbhash = null,
    )

    /** Supplies typed native history for populated routes; transport stays unavailable without network. */
    private fun unavailableAttachmentRuntime(populated: Boolean): dev.ipf.whitenoise.android.state.AppMarmotRuntime {
        val native =
            java.lang.reflect.Proxy.newProxyInstance(
                dev.ipf.marmotkit.MarmotInterface::class.java.classLoader,
                arrayOf(dev.ipf.marmotkit.MarmotInterface::class.java),
            ) { proxy, method, args ->
                when (method.name.substringBefore('-')) {
                    "equals" -> proxy === args?.firstOrNull()
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "UnavailableAttachmentRuntime"
                    "npub" -> null
                    "recordHostTiming" -> dev.ipf.marmotkit.ProductRecordResultFfi.IGNORED_DISABLED
                    else -> error("Read unavailable in shared-content motion fixture: ${method.name}")
                }
            } as dev.ipf.marmotkit.MarmotInterface
        val runtime =
            if (populated) {
                val entries =
                    listOf(
                        dev.ipf.marmotkit.AttachmentCategoryFfi.IMAGE to sharedAttachment("trail.jpg", "image/jpeg"),
                        dev.ipf.marmotkit.AttachmentCategoryFfi.FILE to
                            sharedAttachment("trail-notes.pdf", "application/pdf"),
                        dev.ipf.marmotkit.AttachmentCategoryFfi.AUDIO to
                            sharedAttachment("trail-voice.ogg", "audio/ogg"),
                    ).mapIndexed { index, (category, reference) ->
                        dev.ipf.marmotkit.AttachmentEntryFfi(
                            "44".repeat(32),
                            "44".repeat(32),
                            PEER_HEX,
                            1uL,
                            1uL,
                            1uL,
                            category,
                            dev.ipf.marmotkit.AttachmentRoleFfi.SHARED,
                            dev.ipf.marmotkit.MediaAttachmentOutcomeFfi
                                .Accepted(index.toUInt(), reference),
                        )
                    }
                val history =
                    dev.ipf.whitenoise.android.ui.medialibrary
                        .GroupAttachmentFixture(entries)
                object : dev.ipf.marmotkit.MarmotInterface by native {
                    override suspend fun attachmentLocalAssets(
                        accountRef: String,
                        groupIdHex: String,
                        targets: List<dev.ipf.marmotkit.AttachmentLocalTargetFfi>,
                    ): List<dev.ipf.marmotkit.AttachmentLocalAssetFfi> {
                        check(accountRef == ACCOUNT_REF && groupIdHex == GROUP_HEX)
                        // Projected fixtures have no retained plaintext; preserve one result per target.
                        return targets.map { dev.ipf.marmotkit.AttachmentLocalAssetFfi(null, 0u) }
                    }

                    override suspend fun attachmentHistoryPage(
                        account: String,
                        groupIdHex: String,
                        limit: UInt,
                        cursor: dev.ipf.marmotkit.AttachmentHistoryCursor?,
                    ): dev.ipf.marmotkit.AttachmentPageReadFfi {
                        check(account == ACCOUNT_REF && groupIdHex == GROUP_HEX)
                        return history.page(cursor)
                    }

                    override suspend fun attachmentHistoryVersion(
                        account: String,
                        groupIdHex: String,
                    ): dev.ipf.marmotkit.AttachmentHistoryVersion {
                        check(account == ACCOUNT_REF && groupIdHex == GROUP_HEX)
                        return history.version()
                    }
                }
            } else {
                native
            }
        return dev.ipf.whitenoise.android.state
            .AppMarmotRuntime("shared-motion-test", runtime)
    }

    /** Builds an app state with one local account that resolves and names the given peer profile, if any. */
    private fun appState(
        profileHex: String? = null,
        profile: UserProfileMetadataFfi? = null,
        attachmentRuntime: Boolean = false,
        populatedAttachments: Boolean = false,
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
            initialMarmotRuntime = if (attachmentRuntime) unavailableAttachmentRuntime(populatedAttachments) else null,
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
            unlinked = false,
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
            leaveBlocked = false,
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
