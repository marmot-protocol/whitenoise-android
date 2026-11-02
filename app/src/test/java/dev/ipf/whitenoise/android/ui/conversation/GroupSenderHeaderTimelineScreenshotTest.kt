@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.GroupMemberDetailsFfi
import dev.ipf.marmotkit.UserProfileMetadataFfi
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.core.MessageAttachments
import dev.ipf.whitenoise.android.state.BubbleSide
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.chats.AvatarScreenshotFixtures
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerGate
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerTextState
import dev.ipf.whitenoise.android.ui.conversation.messages.MESSAGE_SENDER_AVATAR_TAG
import dev.ipf.whitenoise.android.ui.conversation.messages.MessageBubbleFileAttachmentFixtures
import dev.ipf.whitenoise.android.ui.conversation.messages.groupAuthorColor
import dev.ipf.whitenoise.android.ui.conversation.messages.messageBubbleColumnTestTag
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale
import java.util.TimeZone
import kotlin.math.ceil
import kotlin.math.floor

/** Real sender-run projection, profile materialization and row gestures, not manually injected headers. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w400dp-h1400dp-mdpi")
@OptIn(ExperimentalCoroutinesApi::class)
class GroupSenderHeaderTimelineScreenshotTest : MessageBubbleFileAttachmentFixtures() {
    @get:Rule val composeRule = createComposeRule(effectContext = UnconfinedTestDispatcher())
    private val controllers = mutableListOf<ConversationController>()
    private val previousZone = TimeZone.getDefault()
    private var pictureUrl: String? = null
    private val focusedMenuOpen = mutableStateOf(false)
    private var expectedAuthorArgb: Int? = null
    private var coloredApp: WhiteNoiseAppState? = null
    private var openedMenus = 0
    private var selectionToggles = 0
    private val composer = ComposerTextState(TextFieldValue())
    private val senderId get() = fileTimelineMessage(1, "", attachments = emptyList()).record.sender
    private val selfId get() = group().admins.single()
    private val otherId = "03" + "00".repeat(31)

    @Before fun prepare() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        AvatarImageLoader.clear()
        AvatarImageLoader.resetProfileImageFetcherForTests()
    }

    @After fun cleanup() {
        controllers.forEach { it.onCleared() }
        AvatarImageLoader.clear()
        AvatarImageLoader.resetProfileImageFetcherForTests()
        TimeZone.setDefault(previousZone)
    }

    @Test fun mixedTextMediaRunUsesOneHeaderPerSenderLight() = captureRun("group_sender_run_light")

    @Test fun mixedTextMediaRunLoadedAmoled() =
        captureRun(
            "group_sender_run_loaded_amoled",
            loaded = true,
            dark = true,
            amoled = true,
        )

    @Test fun mixedTextMediaRunLoadedRtlLarge() =
        captureRun(
            "group_sender_run_loaded_rtl_large",
            loaded = true,
            rtl = true,
            scale = 1.6f,
        )

    private fun captureRun(
        name: String,
        loaded: Boolean = false,
        dark: Boolean = false,
        amoled: Boolean = false,
        rtl: Boolean = false,
        scale: Float = 1f,
    ) {
        if (loaded) {
            pictureUrl = AVATAR_A
            AvatarImageLoader.putCached(AVATAR_A, AvatarScreenshotFixtures.distinctAvatarBitmap(Color.RED))
        }
        val app = appState()
        val controller = controller(app)
        val rows = rows()
        render(rows, app, controller, dark, amoled, rtl, scale)
        composeRule.onAllNodesWithTag(MESSAGE_SENDER_AVATAR_TAG, useUnmergedTree = true).assertCountEquals(3)
        composeRule.onAllNodesWithText("Maya Reed", substring = true, useUnmergedTree = true).assertCountEquals(1)
        composeRule.onAllNodesWithText("Leon Park", substring = true, useUnmergedTree = true).assertCountEquals(2)
        val first =
            composeRule
                .onNodeWithTag(messageBubbleColumnTestTag(rows[0].record.messageIdHex))
                .getUnclippedBoundsInRoot()
        val follow =
            composeRule
                .onNodeWithTag(messageBubbleColumnTestTag(rows[1].record.messageIdHex))
                .getUnclippedBoundsInRoot()
        val media =
            composeRule
                .onNodeWithTag(messageBubbleColumnTestTag(rows[2].record.messageIdHex))
                .getUnclippedBoundsInRoot()
        assertEquals(
            "long group text uses the actual viewport less existing screen insets",
            336f,
            (first.right - first.left).value,
            1f,
        )
        if (rtl) {
            assertEquals(first.right.value, follow.right.value, 1f)
        } else {
            assertEquals(first.left.value, follow.left.value, 1f)
        }
        assertTrue(
            "media keeps its rich canvas rather than inheriting the text maximum",
            (media.right - media.left) <= 248.dp,
        )
        if (loaded) assertEquals(Color.RED, firstAvatarPixel())
        composeRule.onNodeWithTag("timeline").captureRoboImage("src/test/snapshots/$name.png")
    }

    @Test fun portraitCaptionKeepsIdentityAboveTheNaturalMediaFrame() {
        val app = appState()
        val item = portraitMessage("A portrait caption.")
        val controller = controller(app)
        // Seed actual retained image pixels before mounting the production row.
        controller.cacheThumbnail(
            item.record.messageIdHex,
            0,
            Bitmap.createBitmap(128, 256, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.CYAN) },
            1024,
        )
        render(listOf(item), app, controller, preserveSeededMedia = true)
        val header = composeRule.onNodeWithText("Maya Reed", substring = true).getUnclippedBoundsInRoot()
        val image = composeRule.onNodeWithContentDescription("portrait.jpg").getUnclippedBoundsInRoot()
        assertTrue("identity is above the media, outside its frame", header.bottom <= image.top)
        assertEquals(
            "captioned portraits retain the existing capped rich canvas",
            236f,
            (image.right - image.left).value,
            1f,
        )
        composeRule.onNodeWithTag("timeline").captureRoboImage(
            "src/test/snapshots/group_sender_portrait_caption_light.png",
        )
    }

    private fun portraitMessage(caption: String): TimelineMessage {
        val item = fileTimelineMessage(10, "portrait.jpg", mediaType = "image/jpeg", caption = caption)
        val reference = fileReference("portrait.jpg", "image/jpeg").copy(dim = "128x256")
        return item.copy(
            record =
                item.record.copy(
                    tags = item.record.tags.map { tag -> tag.copy(values = tag.values + "dim 128x256") },
                ),
            projected =
                checkNotNull(item.projected).copy(
                    media = MessageAttachments.acceptedOutcomes(listOf(reference)),
                ),
        )
    }

    @Test
    fun uncaptionedPortraitKeepsItsNaturalNarrowFrame() {
        val app = appState()
        val item = portraitMessage("")
        val controller = controller(app)
        controller.cacheThumbnail(
            item.record.messageIdHex,
            0,
            Bitmap.createBitmap(128, 256, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.CYAN) },
            1024,
        )
        render(listOf(item), app, controller, preserveSeededMedia = true)
        val image = composeRule.onNodeWithContentDescription("portrait.jpg").getUnclippedBoundsInRoot()
        assertEquals("uncaptioned portrait retains its original 1:2 sizing", 128f, (image.right - image.left).value, 1f)
        composeRule.onNodeWithTag("timeline").captureRoboImage(
            "src/test/snapshots/group_sender_portrait_uncaptioned_light.png",
        )
    }

    @Test
    fun focusedUncaptionedImageRetainsOutsideIdentity() = checkProductionMediaFocus(caption = "", customColor = false)

    @Test
    fun focusedCaptionOnDarkCustomBubbleKeepsReadableOutsideIdentity() =
        checkProductionMediaFocus(
            caption = "A picture caption.",
            customColor = true,
        )

    private fun checkProductionMediaFocus(
        caption: String,
        customColor: Boolean,
    ) {
        val app = appState()
        if (customColor) {
            coloredApp = app
            app.updateChatBubbleColor(group().groupIdHex, BubbleSide.Other, 0xFF111111L)
        }
        val item = fileTimelineMessage(15, "focus.jpg", mediaType = "image/jpeg", caption = caption)
        val controller = controller(app)
        controller.cacheThumbnail(
            item.record.messageIdHex,
            0,
            Bitmap.createBitmap(256, 160, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.CYAN) },
            1024,
        )
        render(listOf(item), app, controller, preserveSeededMedia = true)
        val sourceView = openRecordedMediaAction()
        val identity = composeRule.onNodeWithTag("message-actions-sender-identity", useUnmergedTree = true)
        val preview = composeRule.onNodeWithTag("message-actions-preview", useUnmergedTree = true)
        val layouts = mutableListOf<TextLayoutResult>()
        identity.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(
            "outside identity uses the author color, including a dark custom caption bubble",
            checkNotNull(expectedAuthorArgb),
            layouts
                .single()
                .layoutInput.style.color
                .toArgb(),
        )
        val previewNode = preview.fetchSemanticsNode()
        val identityNode = identity.fetchSemanticsNode()
        val popupPixels = captureAttachedFocusedPopup(previewNode, identityNode, sourceView)
        val previewBounds = previewNode.boundsInRoot
        val identityBounds = identityNode.boundsInRoot
        verifiedFocusedMediaCrop(popupPixels, previewBounds, identityBounds)
            .captureRoboImage(
                "src/test/snapshots/group_sender_focused_${if (customColor) "custom_caption" else "uncaptioned"}.png",
            )
    }

    /** Opens the genuine row action and submits the source draw that records its retained media layer. */
    private fun openRecordedMediaAction(): View {
        val sourceImage = composeRule.onNodeWithContentDescription("focus.jpg")
        val sourcePixels = sourceImage.captureToImage().asAndroidBitmap()
        assertTrue(
            "retained pixels are visible before opening the actual media action",
            (0 until sourcePixels.height).any { y ->
                (0 until sourcePixels.width).any { x ->
                    sourcePixels.getPixel(x, y) ==
                        Color.CYAN
                }
            },
        )
        val sourceView = checkNotNull(sourceImage.fetchSemanticsNode().root as? View)
        sourceImage.performTouchInput { longClick() }
        composeRule.mainClock.advanceTimeBy(500)
        composeRule.waitForIdle()
        assertTrue("the source owner stays attached while recording the lifted media", sourceView.isAttachedToWindow)
        val sourceFrame = Bitmap.createBitmap(sourceView.width, sourceView.height, Bitmap.Config.ARGB_8888)
        // The host does not submit a hardware window frame: run the real post-open recording draw.
        composeRule.runOnUiThread {
            sourceView.draw(Canvas(sourceFrame))
            Snapshot.sendApplyNotifications()
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        return sourceView
    }

    /** Captures the attached popup owner after verifying that its sender and preview share that owner. */
    private fun captureAttachedFocusedPopup(
        previewNode: SemanticsNode,
        identityNode: SemanticsNode,
        sourceView: View,
    ): Bitmap {
        val composeView = checkNotNull(previewNode.root as? View)
        assertTrue("the lifted preview has its own attached native owner", composeView !== sourceView)
        assertTrue("identity and media must belong to the same native popup", identityNode.root === previewNode.root)
        assertEquals("androidx.compose.ui.window.PopupLayout", composeView.rootView.javaClass.name)
        assertTrue("never detach the production popup to capture it", composeView.isAttachedToWindow)
        // Popup captureToImage uses a process screenshot, which can sample the source window on
        // Robolectric. Draw the actual attached popup owner; pixels and semantics share this root.
        assertTrue("the popup has a measured raster", composeView.width > 0 && composeView.height > 0)
        val popupPixels = Bitmap.createBitmap(composeView.width, composeView.height, Bitmap.Config.ARGB_8888)
        composeRule.runOnUiThread { composeView.draw(Canvas(popupPixels)) }
        return popupPixels
    }

    /** Requires visible retained media and author pixels in the expected order before cropping the preview. */
    private fun verifiedFocusedMediaCrop(
        popupPixels: Bitmap,
        previewBounds: Rect,
        identityBounds: Rect,
    ): Bitmap {
        val left = floor(previewBounds.left).toInt().coerceAtLeast(0)
        val top = floor(previewBounds.top).toInt().coerceAtLeast(0)
        val right = ceil(previewBounds.right).toInt().coerceAtMost(popupPixels.width)
        val bottom = ceil(previewBounds.bottom).toInt().coerceAtMost(popupPixels.height)
        assertTrue("the preview has a nonempty raster crop", right > left && bottom > top)
        assertTrue(
            "sender bounds fit the popup raster",
            identityBounds.left >= 0 &&
                identityBounds.top >= 0 &&
                identityBounds.right <= popupPixels.width &&
                identityBounds.bottom <= popupPixels.height,
        )
        val firstImageY =
            (top until bottom).firstOrNull { y ->
                (left until right).any { x -> popupPixels.getPixel(x, y) == Color.CYAN }
            }
        assertTrue("the production popup contains the retained image", firstImageY != null)
        assertTrue(
            "the outside sender is visibly painted in its author color",
            (ceil(identityBounds.top).toInt() until floor(identityBounds.bottom).toInt()).any { y ->
                (ceil(identityBounds.left).toInt() until floor(identityBounds.right).toInt()).any { x ->
                    popupPixels.getPixel(x, y) == expectedAuthorArgb
                }
            },
        )
        assertTrue(
            "senderBottom=${identityBounds.bottom}, firstImageRootY=$firstImageY; sender must be above retained media",
            identityBounds.bottom <= checkNotNull(firstImageY),
        )
        return Bitmap.createBitmap(popupPixels, left, top, right - left, bottom - top)
    }

    @Test fun captionedMediaFramePaddingKeepsItsOwnTouchOwnership() {
        checkMediaFrameTouchOwnership("A picture caption.")
    }

    @Test fun bareMediaFramePaddingKeepsItsOwnTouchOwnership() = checkMediaFrameTouchOwnership("")

    /** The passive frame inset must beat the header's expanded hit target without taking row holds. */
    private fun checkMediaFrameTouchOwnership(caption: String) {
        val app = appState()
        val item = fileTimelineMessage(18, "frame.jpg", mediaType = "image/jpeg", caption = caption)
        val controller = controller(app)
        controller.cacheThumbnail(item.record.messageIdHex, 0, landscape(), 1024)
        render(listOf(item), app, controller, preserveSeededMedia = true)
        val header = composeRule.onNodeWithText("Maya Reed", substring = true)
        val column = composeRule.onNodeWithTag(messageBubbleColumnTestTag(item.record.messageIdHex))
        val headerTouch = header.fetchSemanticsNode().touchBoundsInRoot
        val columnBounds = column.fetchSemanticsNode().boundsInRoot
        val image = composeRule.onNodeWithContentDescription("frame.jpg").fetchSemanticsNode().boundsInRoot
        val point =
            Offset(
                (
                    maxOf(headerTouch.left, columnBounds.left) +
                        minOf(headerTouch.right, columnBounds.right)
                ) / 2,
                image.top - 3f,
            )
        assertTrue("probe is in the passive six-pixel frame inset", point.y > image.top - 6f && point.y < image.top)
        assertTrue("probe reaches real expanded profile overlap", headerTouch.contains(point))
        assertTrue("probe stays in the message column", columnBounds.contains(point))
        header.performTouchInput { click() }
        composeRule.runOnIdle {
            assertEquals(app.npub(senderId), app.pendingProfileNpub)
            app.clearPresentedProfile()
        }
        val local = point - columnBounds.topLeft
        column.performTouchInput { click(local) }
        composeRule.runOnIdle { assertNull("frame padding cannot activate the sender", app.pendingProfileNpub) }
        column.performTouchInput { longClick(local) }
        composeRule.runOnIdle {
            assertEquals("the row owns a hold on passive frame padding", 1, openedMenus)
            assertNull(app.pendingProfileNpub)
        }
    }

    @Test fun existingHeaderReceivesLateProfilePictureSourceChanges() {
        val app = appState()
        val controller = controller(app)
        render(rows().take(1), app, controller)
        pictureUrl = AVATAR_A
        AvatarImageLoader.putCached(AVATAR_A, AvatarScreenshotFixtures.distinctAvatarBitmap(Color.RED))
        runBlocking { app.refreshProfile(senderId) }
        composeRule.waitForIdle()
        assertEquals(Color.RED, firstAvatarPixel())
        pictureUrl = AVATAR_B
        AvatarImageLoader.putCached(AVATAR_B, AvatarScreenshotFixtures.distinctAvatarBitmap(Color.BLUE))
        runBlocking { app.refreshProfile(senderId) }
        composeRule.waitForIdle()
        assertEquals(
            "materialized source changes must replace the existing sender picture",
            Color.BLUE,
            firstAvatarPixel(),
        )
    }

    @Test fun profileTapAndRowLongPressRetainDistinctOwners() {
        val app = appState()
        render(rows().take(1), app, controller(app))
        val header = composeRule.onNodeWithText("Maya Reed", substring = true)
        header.performClick()
        composeRule.runOnIdle {
            assertEquals(app.npub(senderId), app.pendingProfileNpub)
            assertEquals("a profile tap cannot open message actions", 0, openedMenus)
            app.clearPresentedProfile()
        }
        header.performTouchInput { click() }
        composeRule.runOnIdle {
            assertEquals(
                "the physical header target still opens the profile",
                app.npub(senderId),
                app.pendingProfileNpub,
            )
            assertEquals("a profile tap cannot open message actions", 0, openedMenus)
            app.clearPresentedProfile()
        }
        val body = composeRule.onNodeWithText(LONG_MESSAGE, substring = true, useUnmergedTree = true)
        val bodyBounds = body.fetchSemanticsNode().boundsInRoot
        val profileTouch = header.fetchSemanticsNode().touchBoundsInRoot
        val left = maxOf(bodyBounds.left, profileTouch.left)
        val right = minOf(bodyBounds.right, profileTouch.right)
        val top = maxOf(bodyBounds.top, profileTouch.top)
        val bottom = minOf(bodyBounds.bottom, profileTouch.bottom)
        assertTrue("the probe must reach a genuine body/profile touch overlap", right > left && bottom > top)
        val bodyX = (left + (right - left) / 2) - bodyBounds.left
        val bodyY = (top + (bottom - top) / 2) - bodyBounds.top
        body.performTouchInput { click(Offset(bodyX, bodyY)) }
        composeRule.runOnIdle {
            assertNull("the message body cannot activate the expanded profile target", app.pendingProfileNpub)
            assertEquals("a body tap cannot open message actions", 0, openedMenus)
        }
        header.performTouchInput { longClick() }
        composeRule.runOnIdle {
            assertEquals("a hold opens one message action owner", 1, openedMenus)
            assertNull(app.pendingProfileNpub)
        }
    }

    @Test fun selectionKeepsItsLaneAndOwnsRowTaps() {
        val app = appState()
        val rows = rows().take(1)
        render(rows, app, controller(app), selection = true)
        val column =
            composeRule
                .onNodeWithTag(messageBubbleColumnTestTag(rows.single().record.messageIdHex), useUnmergedTree = true)
                .getUnclippedBoundsInRoot()
        assertEquals(
            "only the actual selection lane is withheld from wide group text",
            296f,
            (column.right - column.left).value,
            1f,
        )
        composeRule.onNodeWithText(LONG_MESSAGE, substring = true, useUnmergedTree = true).performTouchInput { click() }
        composeRule.runOnIdle {
            assertEquals("a physical body tap belongs to row selection", 1, selectionToggles)
            assertNull(app.pendingProfileNpub)
        }
        composeRule.onNodeWithText("Maya Reed", substring = true, useUnmergedTree = true).performTouchInput { click() }
        composeRule.runOnIdle {
            assertEquals("a physical header tap also belongs to row selection", 2, selectionToggles)
            assertNull(app.pendingProfileNpub)
        }
    }

    private fun rows(): List<TimelineMessage> {
        val first = fileTimelineMessage(1, "", caption = LONG_MESSAGE, attachments = emptyList())
        val follow = fileTimelineMessage(2, "", caption = "A short follow-up.", attachments = emptyList())
        val media = fileTimelineMessage(3, "lake.jpg", mediaType = "image/jpeg")
        val other =
            fileTimelineMessage(
                4,
                "",
                caption = "Another sender starts a new header.",
                attachments = emptyList(),
            )
        val own =
            fileTimelineMessage(
                5,
                "",
                mine = true,
                caption = "An outgoing message keeps its usual side.",
                attachments = emptyList(),
            )
        val afterOwn =
            fileTimelineMessage(
                6,
                "",
                caption = "The incoming sender is identified again.",
                attachments = emptyList(),
            )

        fun fromOther(item: TimelineMessage) =
            item.copy(
                record =
                    item.record.copy(
                        sender = otherId,
                    ),
                projected = item.projected?.copy(sender = otherId),
            )
        return listOf(first, follow, media, fromOther(other), own, fromOther(afterOwn))
    }

    private fun appState(): WhiteNoiseAppState {
        val app =
            WhiteNoiseAppState(
                context = ApplicationProvider.getApplicationContext(),
                draftStore =
                    DraftStore(
                        object : DraftPersistence {
                            override fun read(): Map<String, String> = emptyMap()

                            override fun write(
                                key: String,
                                value: String?,
                            ) = Unit
                        },
                    ),
                accountIdHexResolver = { selfId },
                accounts = listOf(AccountSummaryFfi("personal", selfId, true, false, false, true)),
                activeAccountRef = "personal",
                profileReader = { id ->
                    UserProfileMetadataFfi(
                        name = "member",
                        displayName = if (id == otherId) "Leon Park" else "Maya Reed",
                        about = null,
                        picture = pictureUrl.takeIf { id == senderId },
                        nip05 = null,
                        lud16 = null,
                    )
                },
                profileDisplayNameReader = { id -> if (id == otherId) "Leon Park" else "Maya Reed" },
                profileRefreshRequest = {},
            )
        runBlocking { app.warmProfilePresentationsBlocking(listOf(senderId, otherId)) }
        return app
    }

    private fun controller(app: WhiteNoiseAppState): ConversationController {
        val roster = authoritativeRoster()
        val members =
            roster.members +
                listOf(senderId, otherId).map {
                    GroupMemberDetailsFfi(it, null, false, false, false, "npub-$it", null)
                }
        val controller =
            ConversationController(appState = app, initialGroup = group(), groupRosterReader = {
                _,
                _,
                ->
                roster.copy(members = members, memberCount = 3u)
            })
        runBlocking { controller.retryMembers() }
        controllers += controller
        return controller
    }

    private fun render(
        rows: List<TimelineMessage>,
        app: WhiteNoiseAppState,
        controller: ConversationController,
        dark: Boolean = false,
        amoled: Boolean = false,
        rtl: Boolean = false,
        scale: Float = 1f,
        selection: Boolean = false,
        preserveSeededMedia: Boolean = false,
    ) {
        if (!preserveSeededMedia) {
            rows
                .filter { row ->
                    row.record.tags.any { tag -> tag.values.any { it.startsWith("m image/") } }
                }.forEach { row ->
                    controller.cacheThumbnail(row.record.messageIdHex, 0, landscape(), 1024)
                }
        }
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = amoled, fontScale = scale) {
                    expectedAuthorArgb = groupAuthorColor(senderId).toArgb()
                    Surface(color = MaterialTheme.colorScheme.background) {
                        Column(
                            Modifier.width(360.dp).padding(horizontal = 12.dp).testTag("timeline"),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            rows.forEachIndexed { index, item ->
                                Row(
                                    item,
                                    rows.getOrNull(index - 1),
                                    rows.getOrNull(index + 1),
                                    app,
                                    controller,
                                    selection,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    @Suppress("LongParameterList", "LongMethod")
    private fun Row(
        item: TimelineMessage,
        older: TimelineMessage?,
        newer: TimelineMessage?,
        app: WhiteNoiseAppState,
        controller: ConversationController,
        selection: Boolean,
    ) {
        TimelineRow(
            item = item,
            older = older,
            newer = newer,
            transcriptLocale = Locale.US,
            entryUnreadCount = 0,
            entryUnreadDividerRetired = true,
            entryFirstUnreadMessageId = null,
            onMeasured = { _, _ -> },
            appState = app,
            controller = controller,
            composerTextState = composer,
            highlighted = false,
            selectionMode = selection,
            textSelectionMode = false,
            onTextSelectionModeChange = {},
            onTextSelectionBoundsChange = {},
            batchSelectable = selection,
            selected = false,
            onToggleSelection = { selectionToggles++ },
            rangeDragActive = false,
            onDragSelectionStart = {},
            onDragSelection = { false },
            onDragSelectionEnd = {},
            onDragSelectionCancel = {},
            quickReactionEmojis = emptyList(),
            recentEmojis = emptyList(),
            onEmojiUsed = {},
            isActionMenuOpen = focusedMenuOpen.value,
            onActionMenuOpenChange = {
                if (it) openedMenus++
                focusedMenuOpen.value = it
            },
            onQuickReactionsSave = {},
            onReplyPreviewClick = {},
            composerGate = ComposerGate.COMPOSER,
            onBack = {},
            mentionCandidates = emptyList(),
            mentionPickerEnabled = false,
            collapseLongMessages = false,
        )
    }

    private fun landscape(): Bitmap =
        Bitmap.createBitmap(256, 160, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(155, 196, 215))
            val canvas = Canvas(this)
            val paint = Paint().apply { color = Color.rgb(47, 103, 84) }
            canvas.drawRect(0f, 95f, 256f, 160f, paint)
            paint.color = Color.rgb(248, 223, 145)
            canvas.drawCircle(195f, 40f, 20f, paint)
        }

    private fun firstAvatarPixel(): Int {
        val node = composeRule.onAllNodesWithTag(MESSAGE_SENDER_AVATAR_TAG, useUnmergedTree = true)[0]
        val bitmap = node.captureToImage().asAndroidBitmap()
        return bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
    }

    private companion object {
        const val AVATAR_A = "https://profiles.example.test/avatar-a.png"
        const val AVATAR_B = "https://profiles.example.test/avatar-b.png"
        const val LONG_MESSAGE =
            "Moving the picture beside the sender name gives this group message more room. " +
                "The message uses the available width while the name and picture stay on the same compact line."
    }
}
