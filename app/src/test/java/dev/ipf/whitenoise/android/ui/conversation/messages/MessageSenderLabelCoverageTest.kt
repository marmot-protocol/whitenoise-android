@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.ui.EmojiLabel
import dev.ipf.whitenoise.android.ui.chats.AvatarScreenshotFixtures
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import android.graphics.Color as AndroidColor

/** The sender's image may use the username line, never another row or vertical spacer. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w400dp-h1000dp-mdpi")
class MessageSenderLabelCoverageTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun compactLoadedLight() = checkGeometry("sender_identity_loaded_light", loaded = true)

    @Test fun compactFallbackDark() = checkGeometry("sender_identity_fallback_dark", dark = true)

    @Test fun compactLoadedRtlLarge() =
        checkGeometry(
            "sender_identity_loaded_rtl_large",
            loaded = true,
            rtl = true,
            scale = 2f,
        )

    @Test fun compactFallbackAmoledLarge() =
        checkGeometry(
            "sender_identity_fallback_amoled_large",
            dark = true,
            amoled = true,
            scale = 2f,
        )

    @Test fun compactLoadedSystemLarge() =
        checkGeometry(
            "sender_identity_loaded_system_large",
            loaded = true,
            systemScale = 2f,
        )

    @Test fun compactAuthoredEmoji() =
        checkGeometry(
            "sender_identity_authored_emoji",
            loaded = true,
            title = "Maya :marmot: Reed",
        )

    private fun checkGeometry(
        name: String,
        loaded: Boolean = false,
        dark: Boolean = false,
        amoled: Boolean = false,
        rtl: Boolean = false,
        scale: Float = 1f,
        systemScale: Float = 1f,
        title: String = TITLE,
    ) {
        val picture = if (loaded) AvatarScreenshotFixtures.distinctAvatarBitmap(AndroidColor.RED) else null
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                LocalDensity provides Density(LocalDensity.current.density, systemScale),
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = amoled, fontScale = scale) {
                    Surface(Modifier.width(360.dp).testTag("comparison")) {
                        Column(Modifier.padding(16.dp)) {
                            MessageBubbleFrame(messageBubblePresentation(false, false), false, false, false, "") {
                                EmojiLabel(
                                    title,
                                    Modifier.testTag("control"),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text("Control body", Modifier.testTag("control-body"))
                            }
                            MessageBubbleFrame(
                                messageBubblePresentation(false, false),
                                false,
                                false,
                                false,
                                "",
                                contentModifier = Modifier.width(IntrinsicSize.Max),
                            ) {
                                MessageSenderLabel(title, "sender", null, picture, Modifier.testTag("identity"))
                                Text("Message body", Modifier.testTag("body"))
                            }
                        }
                    }
                }
            }
        }
        assertCompactGeometry(loaded, title)
        composeRule.onNodeWithTag("comparison").captureRoboImage("src/test/snapshots/$name.png")
    }

    /** Compares the real inline identity with the unchanged plain-name control and its body gap. */
    private fun assertCompactGeometry(
        loaded: Boolean,
        title: String,
    ) {
        val control = bounds("control")
        val identity = bounds("identity")
        val avatar = bounds(MESSAGE_SENDER_AVATAR_TAG)
        assertEquals(
            "avatar must not increase the name line height",
            (control.bottom - control.top).value,
            (identity.bottom - identity.top).value,
            0.5f,
        )
        assertEquals(
            "the existing gap below the name is unchanged",
            bounds("control-body").top.value - control.bottom.value,
            bounds("body").top.value - identity.bottom.value,
            0.5f,
        )
        assertTrue("avatar fits inside the name line", avatar.top >= identity.top && avatar.bottom <= identity.bottom)
        assertEquals(
            "loaded and fallback circles use the same line centre",
            ((identity.top + identity.bottom) / 2).value,
            ((avatar.top + avatar.bottom) / 2).value,
            1f,
        )
        if (loaded) assertEquals(AndroidColor.RED, avatarPixel())
        val semantics = composeRule.onNodeWithTag("identity").fetchSemanticsNode().config[SemanticsProperties.Text]
        assertEquals(
            "one full accessible name, including visually ellipsized text",
            listOf(title),
            semantics.map { it.text.trim() },
        )
        assertEquals(
            "decorative initials must not be announced",
            0,
            composeRule.onAllNodesWithText("M", useUnmergedTree = false).fetchSemanticsNodes().size,
        )
    }

    @Test fun cachedBitmapReplacementUpdatesTheExistingInlineAvatar() {
        val picture = mutableStateOf<ImageBitmap?>(null)
        composeRule.setContent {
            WhiteNoiseTheme {
                MessageSenderLabel("Maya", "sender", null, picture.value, Modifier.testTag("identity"))
            }
        }
        composeRule.runOnIdle { picture.value = AvatarScreenshotFixtures.distinctAvatarBitmap(AndroidColor.RED) }
        composeRule.waitForIdle()
        assertEquals(AndroidColor.RED, avatarPixel())
        composeRule.runOnIdle { picture.value = AvatarScreenshotFixtures.distinctAvatarBitmap(AndroidColor.BLUE) }
        composeRule.waitForIdle()
        assertEquals("a stable sender name cannot retain an old captured bitmap", AndroidColor.BLUE, avatarPixel())
    }

    @Test fun compactProfileTargetKeepsClickAndLongPressWithoutSizingTheLine() {
        var clicks = 0
        var holds = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                Surface(Modifier.width(320.dp).padding(24.dp)) {
                    MessageSenderLabel(
                        "Maya",
                        "sender",
                        null,
                        null,
                        Modifier.testTag("identity").combinedClickable(
                            onClick = { clicks++ },
                            onLongClick = { holds++ },
                        ),
                    )
                }
            }
        }
        val node = composeRule.onNodeWithTag("identity")
        node.assertHasClickAction()
        val visible = node.getUnclippedBoundsInRoot()
        assertTrue("the visible line stays compact", visible.bottom - visible.top < 48.dp)
        assertTrue(
            "profile still has the platform touch target",
            with(composeRule.density) {
                node
                    .fetchSemanticsNode()
                    .touchBoundsInRoot.height
                    .toDp()
            } >= 48.dp,
        )
        node.performClick()
        node.performTouchInput { longClick() }
        composeRule.runOnIdle {
            assertEquals(1, clicks)
            assertEquals(1, holds)
        }
    }

    @Test fun focusedCaptionIdentityRemainsAboveReplyAndMedia() = checkFocusedOrder(media = true)

    @Test fun focusedTextIdentityRemainsAboveReplyAndBody() = checkFocusedOrder(media = false)

    private fun checkFocusedOrder(media: Boolean) {
        composeRule.setContent {
            WhiteNoiseTheme {
                Surface(Modifier.width(320.dp).testTag("focused-root")) {
                    FocusedTextMessagePreview(
                        presentation = messageBubblePresentation(false, false),
                        mine = false,
                        text = "Message body",
                        document = null,
                        time = "10:42",
                        status = MessageStatus.Received,
                        showStatus = false,
                        senderIdentity = {
                            MessageSenderLabel("Maya", "sender", null, null, Modifier.testTag("focused-identity"))
                        },
                        reply = { Text("Quoted message", Modifier.testTag("focused-reply")) },
                        media =
                            if (media) {
                                (
                                    {
                                        Box(
                                            Modifier
                                                .size(140.dp, 100.dp)
                                                .background(androidx.compose.ui.graphics.Color.Cyan)
                                                .testTag("focused-artwork"),
                                        )
                                    }
                                )
                            } else {
                                null
                            },
                    )
                }
            }
        }
        val identity = composeRule.onNodeWithTag("focused-identity")
        assertFalse(
            "a focused preview cannot activate a profile",
            identity.fetchSemanticsNode().config.contains(SemanticsActions.OnClick),
        )
        assertTrue(
            "identity remains before the quoted reply",
            bounds("focused-identity").bottom <= bounds("focused-reply").top,
        )
        if (media) {
            assertTrue(
                "the media identity is outside and above the frame",
                bounds("focused-reply").bottom <= bounds("focused-artwork").top,
            )
        }
        composeRule.onNodeWithTag("focused-root").captureRoboImage(
            "src/test/snapshots/sender_identity_focused_${if (media) "caption" else "text"}.png",
        )
    }

    private fun bounds(tag: String) = composeRule.onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot()

    private fun avatarPixel(): Int {
        val bitmap =
            composeRule
                .onNodeWithTag(MESSAGE_SENDER_AVATAR_TAG, useUnmergedTree = true)
                .captureToImage()
                .asAndroidBitmap()
        return bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
    }

    private companion object {
        const val TITLE = "Maya Reed · A deliberately long sender name"
    }
}
