package dev.ipf.whitenoise.android.ui.common

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.chats.newchat.ContactRow
import dev.ipf.whitenoise.android.ui.conversation.share.SharedUser
import dev.ipf.whitenoise.android.ui.conversation.share.UserMessageBubble
import dev.ipf.whitenoise.android.ui.search.GlobalPersonRow
import dev.ipf.whitenoise.android.ui.settings.CopyableFullPublicKey
import dev.ipf.whitenoise.android.ui.settings.publicKeyDisplayGroups
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Invariant gate for #3565: a person profile shows its public key once, completely and copyably, and every compact
 * public-key label on a person route copies the complete canonical key on its own tap, never a shortened label. The
 * row keeps its own action and its spoken label, and gains a Copy public key action beside it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class PublicKeyCopyPresentationCoverageTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val clipboard = context.getSystemService(ClipboardManager::class.java)

    /** Starts every case from an empty clipboard. */
    @Before
    fun clearClipboard() = clipboard.setPrimaryClip(ClipData.newPlainText("", ""))

    /** A key subtitle copies the whole key from its own text only, inside the row, while the row keeps its action. */
    @Test
    fun keySubtitleCopiesFromItsOwnTextAndTheRowKeepsItsAction() {
        var rowTaps = 0
        composeRule.setContent {
            Themed {
                ContactRow(
                    title = "Alice",
                    subtitle = SHORT_KEY,
                    avatarSeed = "alice",
                    avatarUrl = null,
                    onClick = { rowTaps += 1 },
                    publicKey = FIRST_KEY,
                    modifier = Modifier.testTag(ROW),
                )
            }
        }

        keyText(ROW).performClick()
        assertEquals(FIRST_KEY, clipText())
        assertEquals(0, rowTaps)
        composeRule.onNodeWithTag(ROW).performTouchInput { click(Offset(width - 8f, height / 2f)) }
        assertEquals(1, rowTaps)

        val row = composeRule.onNodeWithTag(ROW).getUnclippedBoundsInRoot()
        val key = keyText(ROW).getUnclippedBoundsInRoot()
        assertTrue("the key target leaves its row", key.left >= row.left && key.right <= row.right)
        assertTrue("the key target spans the whole row", key.right - key.left < row.right - row.left)
        assertRowKeepsLabelAndActions(ROW, FIRST_KEY, "Alice", SHORT_KEY)
    }

    /** A nameless title keeps its text in the row label; each row's copy action and tap copy that row's own key. */
    @Test
    fun keyTitlesStayInTheRowLabelAndCopyTheirOwnKeys() {
        var rowTaps = 0
        composeRule.setContent {
            Themed {
                Column {
                    listOf(ROW to FIRST_KEY, SECOND_ROW to SECOND_KEY).forEach { (tag, key) ->
                        ContactRow(
                            title = SHORT_KEY,
                            subtitle = "Member",
                            avatarSeed = tag,
                            avatarUrl = null,
                            onClick = { rowTaps += 1 },
                            publicKey = key,
                            publicKeyInTitle = true,
                            modifier = Modifier.testTag(tag),
                        )
                    }
                }
            }
        }

        assertRowKeepsLabelAndActions(ROW, FIRST_KEY, SHORT_KEY, "Member")
        copyAction(ROW, FIRST_KEY).invoke()
        assertEquals(FIRST_KEY, clipText())
        keyText(SECOND_ROW).performClick()
        assertEquals(SECOND_KEY, clipText())
        assertEquals(0, rowTaps)
    }

    /** A long press on the key reaches the row's long-press action and copies nothing; a short tap only copies. */
    @Test
    fun aLongPressOnTheKeyIsLeftToTheRow() {
        var taps = 0
        var longPresses = 0
        composeRule.setContent {
            Themed {
                ContactRow(
                    title = "Alice",
                    subtitle = SHORT_KEY,
                    avatarSeed = "alice",
                    avatarUrl = null,
                    onClick = { taps += 1 },
                    onLongClick = { longPresses += 1 },
                    publicKey = FIRST_KEY,
                    modifier = Modifier.testTag(ROW),
                )
            }
        }

        keyText(ROW).performTouchInput { longClick() }
        composeRule.waitForIdle()
        assertEquals("", clipText())
        assertEquals(1, longPresses)
        assertEquals(0, taps)
        keyText(ROW).performClick()
        assertEquals(FIRST_KEY, clipText())
        assertEquals(1, longPresses)
        assertEquals(0, taps)
    }

    /** A shortened or missing key is plain text: no copy action, and a tap on it belongs to the row. */
    @Test
    fun aNonCanonicalKeyLeavesTheLabelInert() {
        var rowTaps = 0
        composeRule.setContent {
            Themed {
                ContactRow(
                    title = "Alice",
                    subtitle = SHORT_KEY,
                    avatarSeed = "alice",
                    avatarUrl = null,
                    onClick = { rowTaps += 1 },
                    publicKey = SHORT_KEY,
                    modifier = Modifier.testTag(ROW),
                )
            }
        }

        keyText(ROW).performClick()
        assertEquals("", clipText())
        assertEquals(1, rowTaps)
        assertNull(composeRule.onNodeWithTag(ROW).fetchSemanticsNode().customActions())
    }

    /** Global search copies from the short key alone and still opens the profile from the rest of the row. */
    @Test
    fun globalPersonRowCopiesWithoutOpening() {
        val opened = mutableListOf<String>()
        composeRule.setContent { Themed { GlobalPersonRow(FIRST_KEY) { opened += it } } }
        val tag = "global.person.$FIRST_KEY"

        keyText(tag).performClick()
        assertEquals(FIRST_KEY, clipText())
        assertEquals(emptyList<String>(), opened)
        composeRule.onNode(hasText(context.getString(R.string.chat_list_search_open_profile)), true).performClick()
        assertEquals(listOf(FIRST_KEY), opened)
        assertTrue(copyActionLabels(composeRule.onNodeWithTag(tag)).contains(copyLabel(FIRST_KEY)))
    }

    /** A nameless shared-user card keeps the key in its spoken label, copies it, and still opens the profile. */
    @Test
    fun namelessSharedUserCardKeepsItsKeyInTheLabel() {
        var opens = 0
        composeRule.setContent {
            Themed { UserMessageBubble(SharedUser(FIRST_KEY, null), onOpen = { opens += 1 }, Modifier.testTag(ROW)) }
        }

        assertRowKeepsLabelAndActions(ROW, FIRST_KEY, "npub1qqqq", context.getString(R.string.share_user_view))
        keyText(ROW).performClick()
        assertEquals(FIRST_KEY, clipText())
        assertEquals(0, opens)
        composeRule.onNode(hasText(context.getString(R.string.share_user_view)), true).performClick()
        assertEquals(1, opens)
    }

    /** The profile's full key block groups every character inside one copy button at least 48 dp tall. */
    @Test
    fun fullKeyBlockGroupsTheWholeKeyInsideOneCopyButton() {
        var copies = 0
        composeRule.setContent {
            Themed {
                CopyableFullPublicKey(
                    npub = FIRST_KEY,
                    copied = false,
                    onCopy = { copies += 1 },
                    modifier = Modifier.width(280.dp),
                    targetTestTag = ROW,
                    textTestTag = KEY_TEXT,
                )
            }
        }

        val target = composeRule.onNodeWithTag(ROW).getUnclippedBoundsInRoot()
        val text = composeRule.onNodeWithTag(KEY_TEXT, true).getUnclippedBoundsInRoot()
        assertTrue("target shorter than 48 dp", target.bottom - target.top >= 48.dp)
        assertTrue("key text escapes its target", text.top >= target.top && text.bottom <= target.bottom)
        assertEquals(
            publicKeyDisplayGroups(FIRST_KEY).joinToString(" "),
            composeRule.onNodeWithTag(KEY_TEXT, true).fetchSemanticsNode().spoken(),
        )
        assertEquals(FIRST_KEY, composeRule.onNodeWithTag(ROW).fetchSemanticsNode().spoken())
        composeRule.onNodeWithTag(ROW).performClick()
        assertEquals(1, copies)
    }

    /** An unresolved reference is shown but never offered for copy. */
    @Test
    fun fullKeyBlockNeverCopiesAnUnresolvedReference() {
        var copies = 0
        composeRule.setContent {
            Themed { CopyableFullPublicKey(UNRESOLVED, false, { copies += 1 }, targetTestTag = ROW) }
        }

        val node = composeRule.onNodeWithTag(ROW).fetchSemanticsNode()
        assertNull(node.config.getOrNull(SemanticsActions.OnClick))
        assertEquals(UNRESOLVED, node.spoken())
        composeRule.onNodeWithTag(ROW).performClick()
        assertEquals(0, copies)
    }

    /** Only a complete npub is copyable; shortened, grouped, raw-hex and blank values never are. */
    @Test
    fun onlyACompleteNpubIsCopyable() {
        assertTrue(isCanonicalNpub(FIRST_KEY))
        assertFalse(isCanonicalNpub(SHORT_KEY))
        assertFalse(isCanonicalNpub(FIRST_KEY.chunked(5).joinToString(" ")))
        assertFalse(isCanonicalNpub("ab".repeat(32)))
        assertFalse(isCanonicalNpub(FIRST_KEY.dropLast(1) + "b"))
        assertFalse(isCanonicalNpub(""))
    }

    /** Only this key's own abbreviation counts as the key fallback; a human name starting with npub1 stays a name. */
    @Test
    fun onlyThisKeysAbbreviationIsTheKeyFallback() {
        val npub = "npub1424242424242424242424242424242424242424242424242424qamrcaj"

        assertTrue(abbreviatesPublicKey("npub14242424...4qamrcaj", npub))
        assertTrue(abbreviatesPublicKey("npub1424242…qamrcaj", npub))
        assertTrue(abbreviatesPublicKey(npub, npub))
        assertFalse(abbreviatesPublicKey("npub1fan club", npub))
        assertFalse(abbreviatesPublicKey("npub1424242…zzzzzz", npub))
        assertFalse(abbreviatesPublicKey("npub14242424...4qamrcaj", FIRST_KEY))
        assertFalse(abbreviatesPublicKey("Alice", npub))
    }

    /** Supplement: each route call site still hands its canonical key to the shared presentation. */
    @Test
    fun routeCallSitesPassTheirCanonicalKey() {
        CALL_SITES.forEach { (path, call) ->
            assertTrue("$path no longer passes its key: $call", Regex(call).containsMatchIn(source(path)))
        }
    }

    /** Theme and surface for each case. */
    @Suppress("FunctionNaming")
    @Composable
    private fun Themed(content: @Composable () -> Unit) {
        WhiteNoiseTheme { Surface { content() } }
    }

    /**
     * The row's merged label keeps [labels], its own click action and a Copy public key action whose label carries
     * the full [npub], while the short label stays the row's spoken text.
     */
    private fun assertRowKeepsLabelAndActions(
        tag: String,
        npub: String,
        vararg labels: String,
    ) {
        val row = composeRule.onNodeWithTag(tag)
        val spoken = row.fetchSemanticsNode().spoken()
        labels.forEach { assertTrue("row label lost '$it': $spoken", spoken.contains(it)) }
        val ownAction = row.fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick)
        assertTrue("row lost its own action", ownAction != null)
        val copyLabels = copyActionLabels(row)
        assertTrue("row has no Copy public key action: $copyLabels", copyLabels.contains(copyLabel(npub)))
        assertTrue("the copy action does not speak the full key", copyLabels.single().contains(npub))
        assertFalse("the row label speaks the full key", spoken.contains(npub))
    }

    /** The unmerged key text inside the node tagged [tag]. */
    private fun keyText(tag: String): SemanticsNodeInteraction =
        composeRule.onNode(hasText("npub1qqqq", substring = true) and hasAnyAncestor(hasTestTag(tag)), true)

    /** The Copy public key custom action merged into the row tagged [tag]. */
    private fun copyAction(
        tag: String,
        npub: String,
    ): () -> Unit {
        val action =
            composeRule
                .onNodeWithTag(tag)
                .fetchSemanticsNode()
                .customActions()
                ?.single { it.label == copyLabel(npub) }
        return { composeRule.runOnIdle { action!!.action() } }
    }

    /** Labels of a node's merged custom actions. */
    private fun copyActionLabels(node: SemanticsNodeInteraction): List<String> =
        node
            .fetchSemanticsNode()
            .customActions()
            ?.map { it.label }
            .orEmpty()

    /** Merged custom actions of a node. */
    private fun SemanticsNode.customActions() = config.getOrNull(SemanticsActions.CustomActions)

    /** Merged visible text and descriptions of a node. */
    private fun SemanticsNode.spoken(): String {
        val texts = config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString(" ") { it.text }
        val descriptions = config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString(" ")
        return "$texts $descriptions".trim()
    }

    /** Current primary clip text. */
    private fun clipText(): String? =
        clipboard.primaryClip
            ?.getItemAt(0)
            ?.text
            ?.toString()

    /** Localized Copy public key action label carrying the full [npub]. */
    private fun copyLabel(npub: String): String = context.getString(R.string.public_key_copy_action_with_value, npub)

    /** One main source file by its path below the app package. */
    private fun source(path: String): String =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/$path"),
            File("app/src/main/java/dev/ipf/whitenoise/android/$path"),
        ).firstOrNull(File::exists)?.readText() ?: error("Missing $path")

    private companion object {
        const val FIRST_KEY = "npub1qqqqqqqqqqqqqqqqqqqqqqqqqppppppppqqqqqqqqqqqqqqqqqqqqqqqqq"
        const val SECOND_KEY = "npub1qqqqqqqqqqqqqqqqqqqqqqqqqzzzzzzzzqqqqqqqqqqqqqqqqqqqqqqqqq"
        const val SHORT_KEY = "npub1qqqqqqqqqq...qqqqqqqq"
        const val UNRESOLVED = "npub1unresolvedprofile"
        const val ROW = "row"
        const val SECOND_ROW = "second_row"
        const val KEY_TEXT = "key_text"

        /** Each classified route and the call that hands the shared presentation its canonical key. */
        val CALL_SITES =
            listOf(
                "ui/profile/PersonProfileContent.kt" to """CopyableFullPublicKey\(\s*npub = person\.publicKey,""",
                "ui/search/GlobalPersonRow.kt" to """PublicKeyCopyText\(\s*IdentityFormatter\.short\(npub""",
                "ui/account/ProfileSwitcherSheet.kt" to
                    """PublicKeyCopyText\(\s*shortNpub\(account\.accountIdHex\),\s*npub\(account\.accountIdHex\)""",
                "ui/settings/SettingsScreen.kt" to """PublicKeyCopyText\(\s*account\.subtitle,\s*account\.npub""",
                "ui/conversation/share/UserShare.kt" to
                    """UserShareIdentity\(title, shortened, resolvedName, user\.npub\)""",
                "ui/group/GroupMembers.kt" to
                    """PublicKeyCopyText\(\s*controller\.memberSubtitle\(member\),""" +
                    """\s*appState\.npubForDisplay\(member\.memberIdHex\)""",
                "ui/group/GroupDetailsScreen.kt" to """publicKey = profileNpub\.takeIf \{ titleIsKey \}""",
                "ui/chats/newchat/SelectedMembersComponents.kt" to """publicKey = memberNpub,""",
                "ui/chats/newchat/ContactPickerScreen.kt" to
                    """publicKey = candidateNpub\.takeIf \{ subtitleIsKey \|\| titleIsKey \}""",
            )
    }
}
