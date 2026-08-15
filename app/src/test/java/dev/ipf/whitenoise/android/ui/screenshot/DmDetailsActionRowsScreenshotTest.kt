package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.chats.newchat.DangerActionRow
import dev.ipf.whitenoise.android.ui.common.AppDivider
import dev.ipf.whitenoise.android.ui.group.DmDetailsContactActionRows
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Visual and accessibility contract for the peer action rows on DM details. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class DmDetailsActionRowsScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun dmDetailsActionRowsLight() = capture("dm_details_action_rows_light", darkTheme = false)

    @Test
    fun dmDetailsActionRowsDarkWithGroupAddInProgress() =
        capture(
            "dm_details_action_rows_add_in_progress_dark",
            darkTheme = true,
            addingContactToGroups = true,
        )

    @Test
    fun dmDetailsActionRowsAmoled() = capture("dm_details_action_rows_amoled", darkTheme = true, amoled = true)

    @Test
    fun dmDetailsActionRowsLargeFont() =
        capture(
            "dm_details_action_rows_font_scale_2x_light",
            darkTheme = false,
            fontScale = 2f,
        )

    @Test
    fun enabledRowsAreButtonsWithMinimumTouchTargets() {
        val clicks = mutableListOf<String>()
        render(
            darkTheme = false,
            onCreateGroup = { clicks += "create" },
            onAddToGroup = { clicks += "add" },
            onArchive = { clicks += "archive" },
            onLeave = { clicks += "leave" },
        )

        listOf(CREATE_GROUP, ADD_TO_GROUP, ARCHIVE_CHAT, LEAVE_CHAT).forEach { title ->
            actionRow(title)
                .assertHasClickAction()
                .assertIsEnabled()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                .assertHeightIsAtLeast(48.dp)
                .performClick()
        }

        assertEquals(listOf("create", "add", "archive", "leave"), clicks)
    }

    @Test
    fun busyAndDisabledRowsRemainDisabledButtonsWithMinimumTouchTargets() {
        render(
            darkTheme = true,
            addingContactToGroups = true,
            dangerEnabled = false,
            archiveInProgress = true,
        )

        listOf(ADD_TO_GROUP, ARCHIVE_CHAT, LEAVE_CHAT).forEach { title ->
            actionRow(title)
                .assertHasClickAction()
                .assertIsNotEnabled()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                .assertHeightIsAtLeast(48.dp)
        }
    }

    private fun actionRow(title: String) = composeRule.onNode(hasText(title) and hasClickAction())

    private fun capture(
        name: String,
        darkTheme: Boolean,
        amoled: Boolean = false,
        fontScale: Float = 1f,
        addingContactToGroups: Boolean = false,
    ) {
        render(
            darkTheme = darkTheme,
            amoled = amoled,
            fontScale = fontScale,
            addingContactToGroups = addingContactToGroups,
        )
        composeRule.mainClock.advanceTimeBy(500L)
        composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/$name.png")
    }

    private fun render(
        darkTheme: Boolean,
        amoled: Boolean = false,
        fontScale: Float = 1f,
        addingContactToGroups: Boolean = false,
        dangerEnabled: Boolean = true,
        archiveInProgress: Boolean = false,
        onCreateGroup: () -> Unit = {},
        onAddToGroup: () -> Unit = {},
        onArchive: () -> Unit = {},
        onLeave: () -> Unit = {},
    ) {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = darkTheme, amoled = amoled) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    Surface(modifier = Modifier.width(360.dp).testTag(TAG)) {
                        Column {
                            DmDetailsContactActionRows(
                                createGroupTitle = CREATE_GROUP,
                                addToGroupTitle = ADD_TO_GROUP,
                                addingContactToGroups = addingContactToGroups,
                                onCreateGroup = onCreateGroup,
                                onAddToGroup = onAddToGroup,
                            )
                            AppDivider()
                            DangerActionRow(
                                icon = Icons.Default.Archive,
                                title = ARCHIVE_CHAT,
                                enabled = dangerEnabled,
                                inProgress = archiveInProgress,
                                onClick = onArchive,
                            )
                            DangerActionRow(
                                icon = Icons.AutoMirrored.Filled.Logout,
                                title = LEAVE_CHAT,
                                enabled = dangerEnabled,
                                onClick = onLeave,
                            )
                        }
                    }
                }
            }
        }
    }

    private companion object {
        const val TAG = "dm-details-action-rows"
        const val CREATE_GROUP = "Create group with Alice"
        const val ADD_TO_GROUP = "Add to group"
        const val ARCHIVE_CHAT = "Archive chat"
        const val LEAVE_CHAT = "Leave chat"
    }
}
