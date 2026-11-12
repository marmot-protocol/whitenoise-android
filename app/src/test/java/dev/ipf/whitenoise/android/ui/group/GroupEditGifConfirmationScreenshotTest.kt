package dev.ipf.whitenoise.android.ui.group

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import dev.ipf.whitenoise.android.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h1200dp-mdpi")
class GroupEditGifConfirmationScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()
    private val fixture = GroupEditGifFixture()

    @After
    fun close() {
        composeRule.runOnIdle { fixture.mounted.value = false }
        fixture.close()
    }

    @Test
    fun confirmationLight() = capture("light")

    @Test
    fun confirmationDark() = capture("dark", dark = true)

    @Test
    fun confirmationAmoled() = capture("amoled", dark = true, amoled = true)

    @Test
    fun confirmationLargeRtl() = capture("large_rtl", rtl = true)

    @Test
    fun stillPhotoConfirmationLight() = capture("light", still = true)

    @Test
    fun stillPhotoConfirmationAmoled() = capture("amoled", dark = true, amoled = true, still = true)

    @Test
    fun stillPhotoConfirmationDark() = capture("dark", dark = true, still = true)

    @Test
    fun stillPhotoConfirmationLargeRtl() = capture("large_rtl", rtl = true, still = true)

    private fun capture(
        name: String,
        dark: Boolean = false,
        amoled: Boolean = false,
        rtl: Boolean = false,
        still: Boolean = false,
    ) {
        composeRule.setContent { GifEditorTestContent(fixture, dark, amoled, rtl) }
        composeRule.onNodeWithTag("group_edit.photoAction").performClick()
        composeRule.onNodeWithText(fixture.context.getString(R.string.choose_files)).performClick()
        composeRule.runOnIdle { assertTrue(fixture.registry.deliver(fixture.uri(name, still = still))) }
        val titleRes = if (still) R.string.group_photo_upload_title else R.string.group_gif_upload_title
        val title = fixture.context.getString(titleRes)
        val notice = if (still) R.string.group_photo_upload_notice else R.string.group_gif_upload_notice
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText(title).assertIsDisplayed()
        composeRule.onNodeWithText(fixture.context.getString(notice)).assertIsDisplayed()
        composeRule.onNodeWithTag("confirmation.confirm").assertIsDisplayed()
        composeRule.onNodeWithTag("confirmation.cancel").assertIsDisplayed()
        assertEquals(0, fixture.uploads.get())
        // Android Dialog has a separate window; capture the real composed screen including that window.
        val kind = if (still) "photo" else "gif"
        captureScreenRoboImage("src/test/snapshots/group_${kind}_confirmation_$name.png")
    }
}
