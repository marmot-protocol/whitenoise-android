package dev.ipf.whitenoise.android.ui.chats.newchat

import android.content.Context
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.RecipientSearch
import dev.ipf.whitenoise.android.media.ImageUploadDraft
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual presentation controls and saved form state, independent of native mutation success. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h1000dp-mdpi")
class NewGroupPresentationTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val person =
        GroupCreationPerson(
            RecipientSearch.Candidate("b".repeat(64), "Ada", "npub1ada"),
            "npub1…ada",
            null,
        )

    /** The explicit solo action remains reachable with no selected or discovered people. */
    @Test fun emptyPickerOffersSoloCreate() {
        var confirms = 0
        picker(emptyList(), emptyList(), confirm = { confirms++ })
        composeRule.onNodeWithText(context.getString(R.string.group_create_solo)).assertIsDisplayed().performClick()
        assertEquals(1, confirms)
    }

    /** Tap toggles selection while the native long-press opens profile without also selecting. */
    @Test fun nativePersonTapAndLongPressRemainDistinct() {
        var selections = 0
        var profiles = 0
        picker(listOf(person), emptyList(), toggle = { selections++ }, profile = { profiles++ })
        val row = composeRule.onNodeWithTag("new_group.person.${person.candidate.accountIdHex}")
        row.performTouchInput { longClick() }
        assertEquals(1, profiles)
        assertEquals(0, selections)
        row.performClick()
        assertEquals(1, selections)
    }

    /** The selected chip removes the referenced person, preserving the separate full review capability. */
    @Test fun selectedChipAndReviewDispatchCorrectCallbacks() {
        var removed: RecipientSearch.Candidate? = null
        var reviews = 0
        picker(listOf(person), listOf(person), toggle = { removed = it }, review = { reviews++ })
        composeRule.onNodeWithTag("new_group.person.${person.candidate.accountIdHex}").assertIsSelected()
        composeRule.onNodeWithTag("new_group.selected.${person.candidate.accountIdHex}").performClick()
        assertEquals(person.candidate, removed)
        composeRule.onNodeWithTag("new_group.review").performClick()
        assertEquals(1, reviews)
    }

    /** Partial network discovery keeps real available rows and exposes a retry action. */
    @Test fun partialDiscoveryKeepsPeopleAndRetry() {
        var retries = 0
        picker(listOf(person), emptyList(), incomplete = true, retry = { retries++ })
        composeRule.onNodeWithTag("new_group.person.${person.candidate.accountIdHex}").assertIsDisplayed()
        composeRule.onNodeWithTag("new_group.retry").performClick()
        assertEquals(1, retries)
    }

    /** Native image preparation disables photo and creation while surfacing the real work state. */
    @Test fun preparingPhotoLocksCompetingActions() {
        setup(NewGroupDraft(), presentation(preparing = true, editable = false, enabled = false))
        composeRule.onNodeWithTag("group_setup.photoAction").assertIsNotEnabled()
        composeRule.onNodeWithTag("group_setup.create").assertIsNotEnabled()
        composeRule.onNodeWithText(context.getString(R.string.group_preparing_photo)).assertIsDisplayed()
    }

    /** Accepted canonical creation locks both fields and keeps Open chat rather than a second create action. */
    @Test fun canonicalRecoveryHasLockedFieldsAndOpenAction() {
        val draft = NewGroupDraft(retryGroupIdHex = "canonical")
        setup(draft, presentation(editable = false))
        composeRule.onNodeWithTag("group_setup.name").assertIsNotEnabled()
        composeRule.onNodeWithTag("group_setup.description").assertIsNotEnabled()
        composeRule.onNodeWithTag("group_setup.create").assertIsEnabled()
        composeRule.onNodeWithText(context.getString(R.string.new_message_open_chat)).assertIsDisplayed()
    }

    /** Restoring a draft retains text/selection/timer/canonical identity but never serializes prepared image bytes. */
    @Test fun restorationRetainsAuthoredStateAndRequiresPhotoReselection() {
        val restoration = StateRestorationTester(composeRule)
        lateinit var current: NewGroupDraft
        restoration.setContent {
            current = rememberNewGroupDraft()
            WhiteNoiseTheme {
                NewGroupSetupContent(current, presentation(), NewGroupSetupActions({}, {}, {}, {}, {}))
            }
        }
        composeRule.runOnIdle {
            current.name.edit {
                append("Team 😀")
                selection = TextRange(0, 4)
            }
            current.description.edit {
                append("Private plans")
                selection = TextRange(2, 7)
            }
            current.retentionSecs = 3600
            current.imageDraft = ImageUploadDraft(byteArrayOf(1, 2), "image/png", null, null, null)
            current.createRequestToken = 8
        }
        restoration.emulateSavedInstanceStateRestore()
        composeRule.runOnIdle {
            assertEquals("Team 😀", current.name.text.toString())
            assertEquals(TextRange(0, 4), current.name.selection)
            assertEquals("Private plans", current.description.text.toString())
            assertEquals(TextRange(2, 7), current.description.selection)
            assertEquals(3600L, current.retentionSecs)
            assertNull(current.retryGroupIdHex)
            assertEquals(8L, current.createRequestToken)
            assertNull(current.imageDraft)
            assertTrue(current.imageNeedsReselection)
        }
    }

    /** Large text uses a scrolling form while the primary action retains a full minimum touch target. */
    @Test
    @Config(qualifiers = "en-w320dp-h480dp-mdpi")
    fun largeFontShortViewportKeepsCreateAndFieldsReachable() {
        val draft = NewGroupDraft()
        composeRule.setContent {
            WhiteNoiseTheme(fontScale = 2f) {
                NewGroupSetupContent(draft, presentation(), NewGroupSetupActions({}, {}, {}, {}, {}))
            }
        }
        val button = composeRule.onNodeWithTag("group_setup.create")
        button.assertIsDisplayed()
        val bounds = button.getUnclippedBoundsInRoot()
        assertTrue(bounds.bottom - bounds.top >= 48.dp)
        composeRule.onNodeWithTag("group_setup.name").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("group_setup.description").performScrollTo().assertIsDisplayed()
    }

    /** Photo menu retains every real native acquisition path plus explicit removal. */
    @Test fun photoMenuDispatchesEachNativeEntry() {
        val actions = mutableListOf<String>()
        val expanded = mutableStateOf(true)
        composeRule.setContent {
            WhiteNoiseTheme {
                NewGroupPhotoMenu(
                    expanded.value,
                    true,
                    { expanded.value = false },
                    { actions += "photos" },
                    { actions += "files" },
                    { actions += "web" },
                    { actions += "emoji" },
                    { actions += "remove" },
                )
            }
        }
        listOf(
            R.string.group_photo_photos to "photos",
            R.string.group_photo_files to "files",
            R.string.group_photo_web to "web",
            R.string.group_photo_emoji to "emoji",
            R.string.group_remove_photo to "remove",
        ).forEach { (label, expected) ->
            composeRule.runOnIdle { expanded.value = true }
            composeRule.onNodeWithText(context.getString(label)).performClick()
            assertEquals(expected, actions.last())
            assertFalse(expanded.value)
        }
    }

    /** Renders the production picker with controlled native display-state projections and callback counters. */
    private fun picker(
        people: List<GroupCreationPerson>,
        selected: List<GroupCreationPerson>,
        incomplete: Boolean = false,
        confirm: () -> Unit = {},
        review: () -> Unit = {},
        retry: () -> Unit = {},
        toggle: (RecipientSearch.Candidate) -> Unit = {},
        profile: (RecipientSearch.Candidate) -> Unit = {},
    ) {
        composeRule.setContent {
            WhiteNoiseTheme {
                NewGroupRecipientContent(
                    TextFieldState(),
                    people,
                    selected,
                    false,
                    false,
                    incomplete,
                    NewGroupRecipientActions({}, confirm, review, {}, {}, retry, toggle, profile),
                )
            }
        }
    }

    /** Shared setup fixture; it does not claim native publication/readiness from a rendered state. */
    private fun setup(
        draft: NewGroupDraft,
        state: NewGroupSetupPresentation,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme { NewGroupSetupContent(draft, state, NewGroupSetupActions({}, {}, {}, {}, {})) }
        }
    }

    /** Pure display fixture independent of the FFI operation owner. */
    private fun presentation(
        preparing: Boolean = false,
        editable: Boolean = true,
        enabled: Boolean = true,
    ) = NewGroupSetupPresentation(
        emptyList(),
        null,
        preparing,
        false,
        editable,
        enabled,
        false,
        null,
        null,
        "Off",
        false,
    )
}
