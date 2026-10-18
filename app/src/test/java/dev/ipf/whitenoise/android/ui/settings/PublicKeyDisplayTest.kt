package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A full-screen identity view shows the whole public key so its middle can be compared (#3321). The grouping is
 * display only: it keeps every character in order, and assistive technologies read the canonical contiguous value.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PublicKeyDisplayTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Joining the groups gives back the key; every group but the last holds five characters, the last the rest. */
    @Test
    fun groupsKeepEveryCharacterInOrder() {
        val groups = publicKeyDisplayGroups(NPUB)

        assertEquals(NPUB, groups.joinToString(""))
        assertTrue(groups.dropLast(1).all { it.length == PUBLIC_KEY_GROUP_LENGTH })
        val tail = NPUB.length % PUBLIC_KEY_GROUP_LENGTH
        val lastLength = if (tail == 0) PUBLIC_KEY_GROUP_LENGTH else tail
        assertEquals(lastLength, groups.last().length)
        assertEquals("the prefix stays whole", "npub1", groups.first())
        assertEquals("the last group ends the key", NPUB.takeLast(lastLength), groups.last())
    }

    /** Keys that share their beginning and end differ visibly in the middle, where an abbreviation hides them. */
    @Test
    fun keysThatDifferOnlyInTheMiddleDisplayDifferently() {
        val first = NPUB.replaceRange(MIDDLE, MIDDLE + 1, "x")
        val second = NPUB.replaceRange(MIDDLE, MIDDLE + 1, "y")

        assertEquals(IdentityFormatter.short(first, 12, 8), IdentityFormatter.short(second, 12, 8))
        assertNotEquals(publicKeyDisplayGroups(first), publicKeyDisplayGroups(second))
        assertEquals(publicKeyDisplayGroups(first).first(), publicKeyDisplayGroups(second).first())
        assertEquals(publicKeyDisplayGroups(first).last(), publicKeyDisplayGroups(second).last())
    }

    /** The drawn key is grouped, but assistive technologies and tests read the canonical contiguous value. */
    @Test
    fun theKeyIsReadAsItsContiguousCanonicalValue() {
        composeRule.setContent {
            WhiteNoiseTheme { FullPublicKey(NPUB, testTag = KEY_TAG, label = "Public key") }
        }

        composeRule.onNodeWithTag(KEY_TAG, useUnmergedTree = true).assertTextEquals(NPUB)
        composeRule.onNodeWithText(NPUB).assertExists()
        // What accessibility reads, the merged tree, never carries the drawn grouping.
        composeRule.onNodeWithText(publicKeyDisplayGroups(NPUB).joinToString(" ")).assertDoesNotExist()
    }

    private companion object {
        const val KEY_TAG = "full_public_key"
        const val MIDDLE = 31
        val NPUB = "npub1" + "qpzry9x8gf2tvdw0s3jn54khce6mua7l".repeat(2).take(58)
    }
}
