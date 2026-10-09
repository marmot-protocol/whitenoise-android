package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.core.ReactionTally
import dev.ipf.whitenoise.android.ui.conversation.reactions.REACTION_PILL_TEST_TAG
import dev.ipf.whitenoise.android.ui.conversation.reactions.ReactionPillFlow
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The centered activity summary stays separate from bubble chrome across themes and accessibility sizes. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h1200dp-mdpi")
class GroupSystemReactionScreenshotTest : GroupSystemReactionTestFixtures() {
    @get:Rule val composeRule = createComposeRule()

    /** Stops the native transport harness after capture. */
    @After fun clearController() {
        pollController.onCleared()
    }

    /** Light theme includes empty, own, others and overflow reaction states. */
    @Test fun light() = capture(dark = false, amoled = false, rtl = false)

    /** Dark surfaces preserve selected-state contrast. */
    @Test fun dark() = capture(dark = true, amoled = false, rtl = false)

    /** AMOLED retains visible pill borders on black. */
    @Test fun amoled() = capture(dark = true, amoled = true, rtl = false)

    /** Long summaries and chips wrap inside a narrow RTL viewport at 200 percent font scale. */
    @Test fun largeRtl() = capture(dark = false, amoled = false, rtl = true)

    /** Captures the production activity row and shared centered reaction flow. */
    private fun capture(
        dark: Boolean,
        amoled: Boolean,
        rtl: Boolean,
    ) {
        val item = activity()
        val tallies =
            listOf(
                emptyList(),
                listOf(ReactionTally("👍", 1, true)),
                listOf(ReactionTally("❤️", 2, false)),
                listOf(
                    ReactionTally("👍", 12, true),
                    ReactionTally("❤️", 5, false),
                    ReactionTally("😂", 3, false),
                    ReactionTally("🎉", 2, true),
                    ReactionTally("🔥", 1, false),
                ),
            )
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f, if (rtl) 2f else 1f),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = amoled) {
                    Surface(Modifier.width(if (rtl) 240.dp else 360.dp).testTag("activity-reactions")) {
                        Column(Modifier.padding(16.dp)) {
                            tallies.forEach { reactions ->
                                GroupSystemRow(
                                    record = item.record,
                                    appState = pollState,
                                    groupSystem =
                                        item.projected!!.groupSystem!!.copy(
                                            actorDisplayName =
                                                if (rtl) "Alice with a very long display name" else "Alice",
                                        ),
                                    reactionContent = {
                                        if (reactions.isNotEmpty()) ReactionPillFlow(reactions, {}, {})
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
        val name =
            when {
                rtl -> "large_rtl"
                amoled -> "amoled"
                dark -> "dark"
                else -> "light"
            }
        composeRule.onNodeWithTag("$REACTION_PILL_TEST_TAG:4").assertIsDisplayed()
        composeRule
            .onNodeWithTag("activity-reactions")
            .captureRoboImage("src/test/snapshots/group_system_reactions_$name.png")
    }
}
