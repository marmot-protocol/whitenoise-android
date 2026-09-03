package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.chats.AvatarScreenshotFixtures
import dev.ipf.whitenoise.android.ui.chats.newchat.ContactRow
import dev.ipf.whitenoise.android.ui.chats.newchat.FOLLOWED_PERSON_BADGE_TEST_TAG
import dev.ipf.whitenoise.android.ui.chats.newchat.SelectionIndicator
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import android.graphics.Color as AndroidColor

/** Visual regression coverage for relationship and discovery copy in recipient search rows. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class RecipientSearchStatusScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** Light mode distinguishes followed, selected, and ordinary discovery states. */
    @Test
    fun relationshipAndSearchResultLabelsLight() {
        render(darkTheme = false, amoled = false, fontScale = 1f, width = 360)
        capture("recipient_search_status_labels_light.png")
    }

    /** Dark mode preserves the badge boundary over both a loaded photo and generated initials. */
    @Test
    fun followedIndicatorDark() {
        render(darkTheme = true, amoled = false, fontScale = 1f, width = 360)
        capture("recipient_follow_indicator_dark.png")
    }

    /** AMOLED mode keeps the followed marker distinct from the black surface and selection control. */
    @Test
    fun followedIndicatorAmoled() {
        render(darkTheme = true, amoled = true, fontScale = 1f, width = 360)
        capture("recipient_follow_indicator_amoled.png")
    }

    /** Narrow 200% text retains both relationship copy and independent selected state. */
    @Test
    @Config(sdk = [36], qualifiers = "w280dp-h780dp-mdpi")
    fun followedIndicatorNarrowLargeFont() {
        render(darkTheme = false, amoled = false, fontScale = 2f, width = 280)
        capture("recipient_follow_indicator_narrow_large_font.png")
    }

    /** Builds the deterministic followed/unfollowed fixture shared by all theme variants. */
    private fun render(
        darkTheme: Boolean,
        amoled: Boolean,
        fontScale: Float,
        width: Int,
    ) {
        val avatarPicture = AvatarScreenshotFixtures.distinctAvatarBitmap(AndroidColor.rgb(191, 54, 87))
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = fontScale)) {
                WhiteNoiseTheme(darkTheme = darkTheme, amoled = amoled) {
                    Surface(color = if (amoled) Color.Black else MaterialTheme.colorScheme.surface) {
                        Column(Modifier.width(width.dp).testTag(ROOT_TAG)) {
                            ContactRow(
                                title = "Ada Lovelace",
                                subtitle = context.getString(R.string.user_search_you_follow),
                                avatarSeed = "ada",
                                avatarUrl = null,
                                avatarImage = avatarPicture,
                                isFollowed = true,
                            )
                            ContactRow(
                                title = "Grace Hopper",
                                subtitle = context.getString(R.string.user_search_you_follow),
                                avatarSeed = "grace",
                                avatarUrl = null,
                                isFollowed = true,
                                selectionState = true,
                                trailing = { SelectionIndicator(selected = true) },
                            )
                            ContactRow(
                                title = "Katherine Johnson",
                                subtitle = context.getString(R.string.user_search_result),
                                avatarSeed = "katherine",
                                avatarUrl = null,
                                selectionState = false,
                                trailing = { SelectionIndicator(selected = false) },
                            )
                        }
                    }
                }
            }
        }

        composeRule
            .onAllNodesWithTag(FOLLOWED_PERSON_BADGE_TEST_TAG, useUnmergedTree = true)
            .assertCountEquals(2)
    }

    /** Captures the rendered fixture after its badge-count invariant is established. */
    private fun capture(fileName: String) {
        composeRule
            .onNodeWithTag(ROOT_TAG)
            .captureRoboImage("src/test/snapshots/$fileName")
    }

    private companion object {
        const val ROOT_TAG = "recipient-search-status-labels"
    }
}
