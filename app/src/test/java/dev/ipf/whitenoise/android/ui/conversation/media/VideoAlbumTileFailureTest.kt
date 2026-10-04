package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.MediaDownloadIntegrationFixture
import dev.ipf.whitenoise.android.state.MediaDownloadIntegrationFixture.Companion.reference
import dev.ipf.whitenoise.android.state.MediaDownloadIntegrationFixture.Companion.request
import dev.ipf.whitenoise.android.state.conversationTimelineTestGroup
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A video album tile whose download failed offers Retry through its transfer control, with nothing drawn over it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w320dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VideoAlbumTileFailureTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var fixture: MediaDownloadIntegrationFixture
    private lateinit var controller: ConversationController

    /** Uses the real controller with only its native download boundary replaced. */
    @Before
    fun setUp() {
        fixture = MediaDownloadIntegrationFixture()
        controller =
            ConversationController(
                appState = fixture.state,
                initialGroup = conversationTimelineTestGroup().copy(groupIdHex = MediaDownloadIntegrationFixture.GROUP),
            )
    }

    /** Cancels fixture transfers and removes its private temporary cache. */
    @After
    fun tearDown() {
        fixture.close()
    }

    /** The warning badge dims a failed tile but must not draw a second glyph over the Retry control. */
    @Test
    fun aFailedAlbumVideoShowsOnlyTheRetryControlAtItsCentre() {
        composeRule.setContent {
            WhiteNoiseTheme {
                MediaVideoGridTile(
                    messageIdHex = request(0).messageIdHex,
                    attachmentIndex = 0,
                    reference = reference(0),
                    controller = controller,
                    appState = fixture.state,
                    mine = false,
                    onTap = {},
                    overflowCount = 0,
                    modifier = Modifier.size(TILE_SIZE).testTag(TILE_TAG),
                )
            }
        }
        composeRule.onNodeWithTag(TILE_TAG).performTouchInput { click() }
        composeRule.waitUntil(WAIT_MILLIS) {
            composeRule.waitForIdle()
            fixture.calls.size == 1
        }

        composeRule.runOnIdle { fixture.calls.single().fail(MarmotKitException.InvalidMediaReference("synthetic")) }
        composeRule.waitUntil(WAIT_MILLIS) {
            composeRule.waitForIdle()
            showsRetry()
        }

        composeRule.onAllNodesWithContentDescription(text(R.string.voice_message_failed)).assertCountEquals(0)
    }

    /** True once a node offers Retry for the failed transfer. */
    private fun showsRetry(): Boolean {
        val nodes = composeRule.onAllNodesWithContentDescription(text(R.string.media_tap_to_retry))
        return nodes.fetchSemanticsNodes().isNotEmpty()
    }

    /** Localized text for a string resource. */
    private fun text(id: Int): String {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        return context.getString(id)
    }

    private companion object {
        const val TILE_TAG = "tile"
        const val WAIT_MILLIS = 10_000L
        val TILE_SIZE = 160.dp
    }
}
