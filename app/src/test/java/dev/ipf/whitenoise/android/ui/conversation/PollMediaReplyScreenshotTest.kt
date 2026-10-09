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
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.DeletionSourceFfi
import dev.ipf.marmotkit.TimelineReplyPreviewFfi
import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real attachment bubbles keep the safe poll quote in pending and delivered states. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
@OptIn(ExperimentalCoroutinesApi::class)
class PollMediaReplyScreenshotTest : PollMessageTestFixtures() {
    @get:Rule val composeRule = createComposeRule(effectContext = UnconfinedTestDispatcher())

    /** Gives the real timeline dispatcher its authoritative membership. */
    @Before fun bindRoster() = runTest { pollController.retryMembers() }

    /** No native stream or controller survives a screenshot fixture. */
    @After fun clearController() = pollController.onCleared()

    /** Default presentation includes both pending and delivered media discussion. */
    @Test fun pollMediaReplyLight() = render(false)

    /** Large RTL text and AMOLED preserve the same poll identity without raw JSON. */
    @Test fun pollMediaReplyAmoledRtlLarge() = render(true)

    /** Renders the shipping timeline rows with native reply metadata for the original poll. */
    private fun render(largeRtl: Boolean) {
        val poll = pollMessage(closed = true)
        retain(poll)
        val items =
            listOf(MessageStatus.Pending, MessageStatus.Sent).mapIndexed { index, status ->
                val source = fileTimelineMessage(80 + index, "Lunch-menu.pdf", mine = true, status = status)
                source.copy(
                    record =
                        source.record.copy(
                            tags = source.record.tags + MessageProjector.eventTag(poll.record.messageIdHex),
                        ),
                    projected =
                        checkNotNull(source.projected).copy(
                            replyToMessageIdHex = poll.record.messageIdHex,
                            replyPreview =
                                TimelineReplyPreviewFfi(
                                    poll.record.messageIdHex,
                                    poll.record.sender,
                                    poll.record.plaintext,
                                    poll.record.contentTokens,
                                    poll.record.kind,
                                    null,
                                    emptyList(),
                                    null,
                                    false,
                                    DeletionSourceFfi.UNKNOWN,
                                    null,
                                ),
                        ),
                )
            }
        items.forEach(::retain)
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = largeRtl, amoled = largeRtl) {
                CompositionLocalProvider(
                    LocalLayoutDirection provides if (largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                    LocalDensity provides Density(1f, if (largeRtl) 2f else 1f),
                ) {
                    Surface(Modifier.width(360.dp).testTag("poll-media-replies")) {
                        Column(Modifier.padding(vertical = 16.dp)) {
                            items.forEach { RealPollMessage(it, menuOpen = false) {} }
                        }
                    }
                }
            }
        }
        val name = if (largeRtl) "amoled_rtl_large" else "light"
        composeRule
            .onNodeWithTag("poll-media-replies")
            .captureRoboImage("src/test/snapshots/poll_media_replies_$name.png")
    }
}
