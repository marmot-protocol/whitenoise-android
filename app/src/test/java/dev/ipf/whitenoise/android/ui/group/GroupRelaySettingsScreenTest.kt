package dev.ipf.whitenoise.android.ui.group

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Contract of Group relays: configured route only, accurate admin/member copy, full URLs and copy actions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h1200dp-mdpi")
class GroupRelaySettingsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Context>()

    /** A member sees every configured relay, the configuration-only note and the read-only explanation. */
    @Test
    fun memberSeesConfiguredRelaysReadOnly() {
        render(listOf(PRIMARY, SECONDARY), isAdmin = false)
        composeRule.onNodeWithText(app.getString(R.string.group_relays)).assertIsDisplayed()
        composeRule.onNodeWithText(app.getString(R.string.group_relays_intro)).assertIsDisplayed()
        composeRule.onNodeWithText(PRIMARY).assertIsDisplayed()
        composeRule.onNodeWithText(SECONDARY).assertIsDisplayed()
        composeRule.onNodeWithText(app.getString(R.string.relay_number, 2)).assertIsDisplayed()
        composeRule.onNodeWithText(app.getString(R.string.group_relays_configuration_only)).assertIsDisplayed()
        composeRule
            .onNodeWithTag(GROUP_RELAYS_MEMBER_TAG)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText(app.getString(R.string.group_relays_member_detail)).assertIsDisplayed()
        composeRule.onNodeWithTag(GROUP_RELAYS_ADMIN_TAG).assertDoesNotExist()
        assertNoEditOrLiveStatusControls()
    }

    /** An admin sees the same read-only list plus why editing is not available, with no fake edit control. */
    @Test
    fun adminSeesEditingUnavailableWithoutEditControls() {
        render(listOf(PRIMARY), isAdmin = true)
        composeRule.onNodeWithTag(GROUP_RELAYS_ADMIN_TAG).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(app.getString(R.string.group_relays_admin_detail)).assertIsDisplayed()
        composeRule.onNodeWithText(app.getString(R.string.group_relays_admin_control_title)).assertIsDisplayed()
        composeRule.onNodeWithTag(GROUP_RELAYS_MEMBER_TAG).assertDoesNotExist()
        assertNoEditOrLiveStatusControls()
    }

    /** A record without relays shows the empty route instead of an empty group or a relay row. */
    @Test
    fun emptyRouteShowsTheEmptyState() {
        render(emptyList(), isAdmin = false)
        composeRule.onNodeWithTag(GROUP_RELAYS_EMPTY_TAG).assertIsDisplayed()
        composeRule.onNodeWithText(app.getString(R.string.group_relays_empty)).assertIsDisplayed()
        composeRule.onNodeWithText(app.getString(R.string.relay_number, 1)).assertDoesNotExist()
    }

    /** A direct message names its destination Chat relays while keeping the same configured-route copy. */
    @Test
    fun directMessageUsesChatRelaysTitle() {
        render(listOf(PRIMARY), isAdmin = true, isDm = true)
        composeRule.onNodeWithText(app.getString(R.string.chat_relays)).assertIsDisplayed()
        composeRule.onNodeWithText(app.getString(R.string.group_relays)).assertDoesNotExist()
    }

    /** A long URL renders in full, and spellings that differ only by case, port or slash appear once. */
    @Test
    fun longUrlsRenderInFullAndNormalizedDuplicatesCollapse() {
        render(
            listOf(LONG, " $PRIMARY ", "WSS://Relay.Example.com/", "wss://relay.example.com:443", ""),
            isAdmin = false,
        )
        composeRule.onNodeWithText(LONG).assertIsDisplayed()
        composeRule.onAllNodesWithText(PRIMARY).assertCountEquals(1)
        composeRule.onNodeWithText("WSS://Relay.Example.com/").assertDoesNotExist()
        composeRule.onNodeWithText(app.getString(R.string.relay_number, 3)).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(app.getString(R.string.group_relay_copy, 2)).assertIsDisplayed()
    }

    /** Display de-duplication keeps first-seen order, never filters by dial policy and keeps distinct user info. */
    @Test
    fun displayListKeepsOrderAndPolicyRefusedAddresses() {
        assertEquals(
            listOf(
                PRIMARY,
                "ws://plain.example.com",
                "wss://relay.example.com/path",
                "not a url",
                "wss://alice@relay.example.com",
                "wss://ALICE@relay.example.com/",
                "wss://bob@relay.example.com",
            ),
            groupRelayDisplayList(
                listOf(
                    PRIMARY,
                    "ws://plain.example.com",
                    "ws://PLAIN.example.com:80/",
                    "wss://relay.example.com/path",
                    "wss://relay.example.com/path/",
                    "not a url",
                    "  ",
                    "wss://alice@relay.example.com",
                    "wss://alice@Relay.Example.com:443/",
                    "wss://ALICE@relay.example.com/",
                    "wss://bob@relay.example.com",
                ),
            ),
        )
    }

    /** Copy puts the exact URL on the clipboard and marks only that row copied. */
    @Test
    fun copyActionCopiesTheExactUrl() {
        render(listOf(PRIMARY, SECONDARY), isAdmin = false)
        composeRule.onNodeWithTag("$GROUP_RELAYS_COPY_TAG_PREFIX$SECONDARY").performClick()
        val clipboard = app.getSystemService(ClipboardManager::class.java)
        assertEquals(
            SECONDARY,
            clipboard.primaryClip
                ?.getItemAt(0)
                ?.text
                ?.toString(),
        )
        composeRule
            .onNodeWithTag("$GROUP_RELAYS_COPY_TAG_PREFIX$SECONDARY")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, app.getString(R.string.copied)))
        composeRule
            .onNodeWithTag("$GROUP_RELAYS_COPY_TAG_PREFIX$PRIMARY")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
    }

    /** A replaced authoritative list drops the transient copied mark and renders only the new route. */
    @Test
    fun replacedRouteDropsCopiedStateAndShowsNewRelays() {
        val relays = mutableStateOf(listOf(PRIMARY))
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                GroupRelaySettingsScreen(relays = relays.value, isDm = false, isAdmin = false, onBack = {})
            }
        }
        composeRule.onNodeWithTag("$GROUP_RELAYS_COPY_TAG_PREFIX$PRIMARY").performClick()
        composeRule.runOnIdle { relays.value = listOf(SECONDARY, PRIMARY) }
        composeRule.onNodeWithText(SECONDARY).assertIsDisplayed()
        composeRule
            .onNodeWithTag("$GROUP_RELAYS_COPY_TAG_PREFIX$PRIMARY")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
    }

    /** Back reaches the caller exactly once. */
    @Test
    fun backReturnsToCaller() {
        var backs = 0
        render(listOf(PRIMARY), isAdmin = false, onBack = { backs++ })
        composeRule.onNodeWithContentDescription(app.getString(R.string.back)).performClick()
        composeRule.runOnIdle { assertEquals(1, backs) }
    }

    /** No row offers editing or claims a live connection, since neither exists in MarmotKit yet. */
    private fun assertNoEditOrLiveStatusControls() {
        listOf(R.string.add_relay, R.string.remove_relay, R.string.edit).forEach {
            composeRule.onNode(hasText(app.getString(it)) and hasClickAction()).assertDoesNotExist()
        }
        composeRule.onNodeWithText(app.getString(R.string.connected)).assertDoesNotExist()
    }

    /** Renders the destination for one configured route. */
    private fun render(
        relays: List<String>,
        isAdmin: Boolean,
        isDm: Boolean = false,
        onBack: () -> Unit = {},
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                GroupRelaySettingsScreen(relays = relays, isDm = isDm, isAdmin = isAdmin, onBack = onBack)
            }
        }
    }

    private companion object {
        const val PRIMARY = "wss://relay.example.com"
        const val SECONDARY = "wss://second.example.org"
        const val LONG =
            "wss://a-very-long-relay-hostname-that-keeps-going.example-relay-operator.example.com" +
                "/nostr/v1/group-route?region=eu-central&tier=archive"
    }
}
