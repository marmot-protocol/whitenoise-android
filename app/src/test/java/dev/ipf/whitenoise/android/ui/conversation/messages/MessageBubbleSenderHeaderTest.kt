package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class MessageBubbleSenderHeaderTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun profileTapAndAccessibleLabel() {
        val profileOpened = AtomicBoolean(false)
        composeRule.setContent {
            WhiteNoiseTheme {
                MessageBubbleSenderHeader(
                    name = "Alice",
                    seed = "alice",
                    avatarUrl = null,
                    profileLabel = PROFILE_LABEL,
                    contentColor = androidx.compose.ui.graphics.Color.Gray,
                    onProfileClick = { profileOpened.set(true) },
                    onLongPress = {},
                    enabled = true,
                    modifier = Modifier.testTag(HEADER_TAG),
                )
            }
        }

        composeRule.onNodeWithContentDescription("Alice").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Alice").assertHasClickAction()
        composeRule.runOnIdle {
            val clickAction =
                composeRule
                    .onNodeWithContentDescription("Alice")
                    .fetchSemanticsNode()
                    .config[SemanticsActions.OnClick]
            assertEquals(PROFILE_LABEL, clickAction.label)
        }
        composeRule.onNodeWithTag(HEADER_TAG).assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag(HEADER_TAG).assertWidthIsAtLeast(48.dp)
        composeRule.onNodeWithContentDescription("Alice").performClick()
        assertTrue(profileOpened.get())
    }

    @Test
    fun longPressCallbackWhenEnabled() {
        val longPressed = AtomicBoolean(false)
        composeRule.setContent {
            WhiteNoiseTheme {
                MessageBubbleSenderHeader(
                    name = "Bob",
                    seed = "bob",
                    avatarUrl = null,
                    profileLabel = PROFILE_LABEL,
                    contentColor = androidx.compose.ui.graphics.Color.Gray,
                    onProfileClick = {},
                    onLongPress = { longPressed.set(true) },
                    enabled = true,
                )
            }
        }

        composeRule.onNodeWithContentDescription("Bob").performTouchInput { longClick() }
        assertTrue(longPressed.get())
    }

    @Test
    fun longNameEllipsizesWithinBoundedWidth() {
        composeRule.setContent {
            WhiteNoiseTheme {
                Box(Modifier.width(120.dp).testTag(BOUND_TAG)) {
                    MessageBubbleSenderHeader(
                        name = "Very Long Sender Display Name That Should Ellipsize",
                        seed = "long",
                        avatarUrl = null,
                        profileLabel = PROFILE_LABEL,
                        contentColor = androidx.compose.ui.graphics.Color.Gray,
                        onProfileClick = {},
                        onLongPress = {},
                        enabled = true,
                        modifier = Modifier.testTag(HEADER_TAG),
                    )
                }
            }
        }

        composeRule.runOnIdle {
            val bound = composeRule.onNodeWithTag(BOUND_TAG).fetchSemanticsNode().boundsInRoot
            val header = composeRule.onNodeWithTag(HEADER_TAG).fetchSemanticsNode().boundsInRoot
            assertTrue(header.width <= bound.width)
            assertTrue(header.height >= 48f)
        }
    }

    @Test
    fun missingAvatarShowsInitialsAndRecompositionUpdatesName() {
        var name by mutableStateOf("Carol")
        var avatarUrl by mutableStateOf<String?>(null)
        composeRule.setContent {
            WhiteNoiseTheme {
                MessageBubbleSenderHeader(
                    name = name,
                    seed = "carol",
                    avatarUrl = avatarUrl,
                    profileLabel = PROFILE_LABEL,
                    contentColor = androidx.compose.ui.graphics.Color.Gray,
                    onProfileClick = {},
                    onLongPress = {},
                    enabled = true,
                )
            }
        }

        composeRule.onNodeWithText("Carol").assertIsDisplayed()
        name = "Caroline"
        avatarUrl = "https://example.com/avatar.png"
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Caroline").assertIsDisplayed()
    }

    @Test
    fun rtlAndLargeFontKeepMinimumTouchTarget() {
        composeRule.setContent {
            WhiteNoiseTheme {
                CompositionLocalProvider(
                    LocalLayoutDirection provides LayoutDirection.Rtl,
                    androidx.compose.ui.platform.LocalDensity provides Density(density = 1f, fontScale = 2f),
                ) {
                    MessageBubbleSenderHeader(
                        name = "Dana",
                        seed = "dana",
                        avatarUrl = null,
                        profileLabel = PROFILE_LABEL,
                        contentColor = androidx.compose.ui.graphics.Color.Gray,
                        onProfileClick = {},
                        onLongPress = {},
                        enabled = true,
                        modifier = Modifier.testTag(HEADER_TAG),
                    )
                }
            }
        }

        composeRule.onNodeWithText("Dana").assertIsDisplayed()
        composeRule.onNodeWithTag(HEADER_TAG).assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag(HEADER_TAG).assertWidthIsAtLeast(48.dp)
    }

    private companion object {
        const val PROFILE_LABEL = "Open profile"
        const val HEADER_TAG = "sender-header"
        const val BOUND_TAG = "sender-header-bound"
    }
}
