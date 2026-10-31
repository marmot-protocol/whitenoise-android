package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.TimeZone

/** Captures the production transcript across sender-run and group-activity boundaries. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ConversationCompactSpacingScreenshotTest : ConversationRowSpacingFixtures() {
    @get:Rule val composeRule = createComposeRule()
    private val originalTimeZone = TimeZone.getDefault()

    @Before fun pinTimeZone() = TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

    @After fun releaseController() {
        pollController.onCleared()
        TimeZone.setDefault(originalTimeZone)
    }

    @Test fun light() = capture("light", dark = false)

    @Test fun dark() = capture("dark", dark = true)

    @Test fun amoled() = capture("amoled", dark = true, amoled = true)

    @Test fun narrowRtlLargeText() = capture("rtl_large", dark = true, amoled = true, rtl = true)

    private fun capture(
        name: String,
        dark: Boolean,
        amoled: Boolean = false,
        rtl: Boolean = false,
    ) {
        val rows =
            if (rtl) {
                listOf(
                    summary("group_renamed"),
                    message(2, 181uL, text = "The new name looks good."),
                    message(3, 3781uL, mine = true, text = "See you at the next reading."),
                )
            } else {
                listOf(
                    message(1, 1uL, text = "Are we meeting this evening?"),
                    message(2, 181uL, text = "I can bring the book."),
                    message(3, 362uL, text = "Let's meet at seven."),
                    summary("group_renamed").let {
                        it.copy(
                            record = it.record.copy(recordedAt = BASE_TIME + 363uL, receivedAt = BASE_TIME + 363uL),
                            projected =
                                it.projected!!.copy(
                                    timelineAt = BASE_TIME + 363uL,
                                    receivedAt = BASE_TIME + 363uL,
                                ),
                        )
                    },
                    message(4, 364uL, mine = true, text = "Evening readers it is."),
                    message(5, 3964uL, mine = true, text = "See you there!"),
                )
            }
        composeRule.setContent {
            Transcript(
                rows,
                dark = dark,
                amoled = amoled,
                rtl = rtl,
                fontScale = if (rtl) 1.6f else 1f,
                height = if (rtl) 480.dp else 460.dp,
            )
        }
        if (!rtl) {
            val before = composeRule.onNodeWithTag("host:${rows[2].record.messageIdHex}").getUnclippedBoundsInRoot()
            val after = composeRule.onNodeWithTag("host:${rows[4].record.messageIdHex}").getUnclippedBoundsInRoot()
            val rename =
                composeRule
                    .onNode(hasText("Alice renamed the group", substring = true), useUnmergedTree = true)
                    .getUnclippedBoundsInRoot()
            assertEquals("balanced upper rename interval", 10f, rename.top.value - before.bottom.value, 0.5f)
            assertEquals("balanced lower rename interval", 10f, after.top.value - rename.bottom.value, 0.5f)
        }
        composeRule
            .onNodeWithTag("compact-transcript")
            .captureRoboImage("src/test/snapshots/conversation_compact_spacing_$name.png")
    }
}
