package dev.ipf.whitenoise.android.ui.group

import android.content.Context
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class ChatInfoOverviewTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** A roster mutation in flight outranks the role, so the row narrates the pending change instead. */
    @Test
    fun pendingMutationReplacesTheRoleOnTheSupportingLine() {
        val labels = memberRoleLabels()

        val updating = context.getString(R.string.group_member_updating)
        assertEquals(updating, labels[true to true])
        assertEquals(updating, labels[false to true])
    }

    /** A settled row reports whichever role the roster currently grants the member. */
    @Test
    fun settledRowsReportTheCurrentRole() {
        val labels = memberRoleLabels()

        assertEquals(context.getString(R.string.admin), labels[true to false])
        assertEquals(context.getString(R.string.member), labels[false to false])
    }

    /** The chevron only says "this navigates forward"; it must not become its own accessibility target. */
    @Test
    fun rowChevronContributesNoSemanticsOfItsOwn() {
        composeRule.setContent { WhiteNoiseTheme { ChatInfoRowChevron() } }

        assertTrue(
            composeRule
                .onRoot()
                .fetchSemanticsNode()
                .children
                .isEmpty(),
        )
    }

    /** Resolves every (isAdmin, updating) combination in one composition, keyed by that pair. */
    private fun memberRoleLabels(): Map<Pair<Boolean, Boolean>, String> {
        val resolved = mutableMapOf<Pair<Boolean, Boolean>, String>()
        composeRule.setContent {
            WhiteNoiseTheme {
                listOf(true, false).forEach { isAdmin ->
                    listOf(true, false).forEach { updating ->
                        resolved[isAdmin to updating] = memberRoleLabel(isAdmin, updating)
                    }
                }
            }
        }
        composeRule.waitForIdle()
        return resolved
    }
}
