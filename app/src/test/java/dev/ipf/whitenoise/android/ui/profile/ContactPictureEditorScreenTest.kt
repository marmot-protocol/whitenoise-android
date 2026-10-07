package dev.ipf.whitenoise.android.ui.profile

import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.state.contactPicturePng
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real private editor controls stay reachable with all supported surfaces and large RTL text. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ContactPictureEditorScreenTest {
    @get:Rule val composeRule = createComposeRule()

    /** Records the prepared private-picture editor in the light theme. */
    @Test fun light() = screen("light")

    /** Records editor controls and crop preview with dark-theme contrast. */
    @Test fun dark() = screen("dark", dark = true)

    /** Records the editor against the AMOLED surface tokens. */
    @Test fun amoled() = screen("amoled", dark = true, amoled = true)

    /** Records editor reachability under RTL and twice the default text scale. */
    @Test fun largeRtl() = screen("large_rtl", scale = 2f, rtl = true)

    /** Records the initial editor state without a saved private selection. */
    @Test fun noPrivatePicture() = screen("empty", picture = false)

    /** Crop, Clear and Save expose distinct labeled actions rather than implicit storage mutations. */
    @Test fun cropClearAndSaveAreExplicitAccessibleActions() {
        var picked = 0
        var cropped = 0
        var cleared = 0
        var saved = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                ContactPrivateDetailsDialog(
                    "Maya Chen",
                    "Maya",
                    "Local note",
                    {},
                    { _, _ -> saved++ },
                    pictureState = ContactPictureEditorState(hasPicture = true),
                    onPickPicture = { picked++ },
                    onRepositionPicture = { cropped++ },
                    onClearPicture = { cleared++ },
                )
            }
        }
        composeRule.onNodeWithTag("contact_picture.pick").performScrollTo().performClick()
        composeRule.onNodeWithTag("contact_picture.crop").performScrollTo().performClick()
        composeRule.onNodeWithTag("contact_picture.clear").performScrollTo().performClick()
        composeRule.onNodeWithTag("person_profile.private_save").performClick()
        assertEquals(listOf(1, 1, 1, 1), listOf(picked, cropped, cleared, saved))
    }

    /** Preparation disables conflicting editor mutations and Save until a bounded draft is ready. */
    @Test fun busyPreparationDisablesMutationsAndSave() {
        composeRule.setContent {
            WhiteNoiseTheme {
                ContactPrivateDetailsDialog(
                    "Maya",
                    "",
                    "",
                    {},
                    { _, _ -> },
                    pictureState = ContactPictureEditorState(busy = true),
                )
            }
        }
        composeRule.onNodeWithTag("contact_picture.pick").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag("person_profile.private_save").assertIsNotEnabled()
    }

    /** Mounts the editor with controlled theme and draft state for the committed screenshot fixture. */
    private fun screen(
        name: String,
        dark: Boolean = false,
        amoled: Boolean = false,
        scale: Float = 1f,
        rtl: Boolean = false,
        picture: Boolean = true,
    ) {
        val bytes = contactPicturePng(Color.rgb(20, 120, 110))
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size).asImageBitmap()
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = amoled, fontScale = scale) {
                    ContactPrivateDetailsDialog(
                        "Maya Chen",
                        "Maya",
                        "Met at the Android meetup",
                        {},
                        { _, _ -> },
                        pictureState =
                            ContactPictureEditorState(
                                hasPicture = picture,
                                preview = bitmap.takeIf { picture },
                            ),
                    )
                }
            }
        }
        composeRule.onNodeWithTag("person_profile.private_save").assertIsDisplayed()
        composeRule.onRoot().captureRoboImage("src/test/snapshots/contact_picture_$name.png")
        if (picture) composeRule.onNodeWithTag("contact_picture.clear").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("person_profile.notes").performScrollTo().assertIsDisplayed()
    }
}
