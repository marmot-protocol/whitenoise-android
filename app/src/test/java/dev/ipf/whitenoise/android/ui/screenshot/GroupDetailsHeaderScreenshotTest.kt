package dev.ipf.whitenoise.android.ui.screenshot

import android.graphics.Bitmap
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.group.GroupDetailsHeader
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pixel baseline for the group-details header leaf (no picture URL, so the
 * seeded palette avatar keeps the shot deterministic).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class GroupDetailsHeaderScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun groupDetailsHeaderLight() {
        render(darkTheme = false)
        composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/group_details_header_light.png")
    }

    @Test
    fun groupDetailsHeaderDark() {
        render(darkTheme = true)
        composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/group_details_header_dark.png")
    }

    @Test
    fun editableGroupDetailsHeaderLight() {
        render(darkTheme = false, editable = true)
        composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/group_details_header_editable_light.png")
    }

    @Test
    fun editableGroupDetailsHeaderDark() {
        render(darkTheme = true, editable = true)
        composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/group_details_header_editable_dark.png")
    }

    @Test
    fun emptyDescriptionGroupDetailsHeaderLight() {
        render(darkTheme = false, description = "", onAddDescription = {})
        composeRule
            .onNodeWithTag(TAG)
            .captureRoboImage("src/test/snapshots/group_details_header_empty_description_light.png")
    }

    @Test
    fun emptyDescriptionGroupDetailsHeaderDark() {
        render(darkTheme = true, description = "", onAddDescription = {})
        composeRule
            .onNodeWithTag(TAG)
            .captureRoboImage("src/test/snapshots/group_details_header_empty_description_dark.png")
    }

    @Test
    fun editableNameIsAnAccessibleMinimumTouchTarget() {
        var editRequested = false
        render(darkTheme = false, editable = true, onEdit = { editRequested = true })

        composeRule
            .onNode(hasText("Weekend hikers") and hasClickAction())
            .assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertHeightIsAtLeast(48.dp)
            .performClick()

        assertTrue(editRequested)
    }

    @Test
    fun readOnlyNameHasNoEditAction() {
        render(darkTheme = false)

        composeRule.onNodeWithText("Weekend hikers").assert(hasClickAction().not())
    }

    @Test
    fun mutationInFlightKeepsTheEditTargetDisabled() {
        render(darkTheme = false, editable = true, editEnabled = false)

        composeRule.onNodeWithText("Weekend hikers").assertIsNotEnabled()
    }

    @Test
    fun addDescriptionUsesSubduedContentColor() {
        var expectedColor = Color.Unspecified
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                expectedColor = MaterialTheme.colorScheme.onSurfaceVariant
                GroupDetailsHeader(
                    title = "Weekend hikers",
                    subtitle = "8 members",
                    description = "",
                    seed = "stable-screenshot-seed",
                    pictureUrl = null,
                    archived = false,
                    onAddDescription = {},
                )
            }
        }

        val textLayouts = mutableListOf<TextLayoutResult>()
        composeRule
            .onNodeWithText("Add group description", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                action(textLayouts)
            }

        assertEquals(
            expectedColor,
            textLayouts
                .single()
                .layoutInput.style.color,
        )
    }

    @Test
    fun encryptedOnlyAvatarOpensTheViewer() {
        val picture = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).asImageBitmap()
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                GroupDetailsHeader(
                    title = "Weekend hikers",
                    subtitle = "8 members",
                    description = "",
                    seed = "stable-screenshot-seed",
                    pictureUrl = null,
                    picture = picture,
                    archived = false,
                )
            }
        }

        composeRule.onNode(hasClickAction()).performClick()

        composeRule.onNodeWithContentDescription("Close").assertExists()
    }

    private fun render(
        darkTheme: Boolean,
        editable: Boolean = false,
        editEnabled: Boolean = true,
        onEdit: () -> Unit = {},
        description: String = "Trail plans and photos.",
        onAddDescription: (() -> Unit)? = null,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = darkTheme) {
                Surface(modifier = Modifier.width(360.dp).testTag(TAG)) {
                    GroupDetailsHeader(
                        title = "Weekend hikers",
                        subtitle = "8 members",
                        description = description,
                        seed = "stable-screenshot-seed",
                        pictureUrl = null,
                        archived = false,
                        onEdit = onEdit.takeIf { editable },
                        editEnabled = editEnabled,
                        onAddDescription = onAddDescription,
                    )
                }
            }
        }
    }

    private companion object {
        const val TAG = "group-details-header"
    }
}
