package dev.ipf.whitenoise.android.ui.profile

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Presentation never turns unknown membership into a confirmed empty result or an implied add operation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class PersonGroupsInCommonContentTest {
    @get:Rule val composeRule = createComposeRule()

    /** Each row opens only the given native identity; Add is a separate explicit callback. */
    @Test fun openingAGroupDoesNotInvokeAdd() {
        val opened = mutableListOf<String>()
        var adds = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                PersonGroupsInCommonContent(
                    listOf(PersonSharedGroupRow("native-group", "Friends", 3)),
                    false,
                    {},
                    opened::add,
                    { adds++ },
                    {},
                )
            }
        }
        composeRule.onNodeWithTag("groups_in_common.group.native-group").performClick()
        assertEquals(listOf("native-group"), opened)
        assertEquals(0, adds)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        composeRule.onNodeWithText(context.getString(R.string.profile_add_to_another_group)).performClick()
        assertEquals(1, adds)
    }

    /** Failed or pending roster evidence has Retry and cannot claim there are no shared groups. */
    @Test fun unresolvedRosterRetainsRetryWithoutClaimingEmpty() {
        var retries = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                PersonGroupsInCommonContent(emptyList(), true, {}, {}, {}, { retries++ })
            }
        }
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        composeRule.onNodeWithText(context.getString(R.string.person_no_groups_in_common)).assertDoesNotExist()
        composeRule.onNodeWithTag("groups_in_common.retry").performClick()
        assertEquals(1, retries)
    }
}
