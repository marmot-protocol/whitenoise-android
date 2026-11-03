package dev.ipf.whitenoise.android.ui.chats.newchat

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.RecipientSearch
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.icons.Icons
import dev.ipf.whitenoise.android.ui.icons.automirrored.filled.Send
import dev.ipf.whitenoise.android.ui.icons.filled.Check
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class SelectedMembersReviewScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun summaryOpensReviewWithoutChangingTheSelection() {
        var opens = 0
        val state = appState()
        composeRule.setContent {
            WhiteNoiseTheme {
                SelectedMemberSummary(
                    members = members,
                    appState = state,
                    onClick = { opens += 1 },
                )
            }
        }

        composeRule
            .onNodeWithText(members.joinToString { member -> member.displayName })
            .assertIsDisplayed()
        composeRule
            .onNodeWithText(context.resources.getQuantityString(R.plurals.selected_members_count, 2, 2))
            .assertIsDisplayed()
        composeRule
            .onNodeWithContentDescription(context.getString(R.string.review_selected_members))
            .performClick()

        assertEquals(1, opens)
        assertEquals(2, members.size)
    }

    @Test
    fun summaryNameSwitchesOffTheFrozenPlaceholderWhenTheProfileResolves() {
        val state = appState()
        composeRule.setContent {
            WhiteNoiseTheme {
                SelectedMemberSummary(
                    members = listOf(frozenPlaceholderMember),
                    appState = state,
                    onClick = {},
                )
            }
        }

        composeRule.onNodeWithText(frozenPlaceholderMember.displayName).assertIsDisplayed()
        state.setContactNickname(frozenPlaceholderMember.accountIdHex, "Camille Example")
        composeRule.onNodeWithText("Camille Example").assertIsDisplayed()
    }

    @Test
    fun busySummaryCannotOpenReview() {
        var opens = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                SelectedMemberSummary(
                    members = members,
                    appState = appState(),
                    onClick = { opens += 1 },
                    enabled = false,
                )
            }
        }

        composeRule
            .onNodeWithContentDescription(context.getString(R.string.review_selected_members))
            .assertIsNotEnabled()
            .performClick()

        assertEquals(0, opens)
    }

    @Test
    fun reviewShowsFullIdentityRowsAndLargeRemoveActions() {
        val removed = mutableListOf<RecipientSearch.Candidate>()
        var confirmed = false
        val state = appState()
        composeRule.setContent {
            WhiteNoiseTheme {
                SelectedMembersReviewScreen(
                    members = members,
                    appState = state,
                    busy = false,
                    onBack = {},
                    onRemove = { removed += it },
                    onConfirm = { confirmed = true },
                    confirmIcon = Icons.AutoMirrored.Filled.Send,
                    confirmLabel = context.getString(R.string.send),
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.review_selection)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(R.string.back)).assertIsDisplayed()
        members.forEach { member ->
            composeRule
                .onNodeWithContentDescription(
                    context.getString(R.string.remove_member_named, member.displayName),
                ).performClick()
        }
        assertEquals(members, removed)

        composeRule.onNodeWithText(context.getString(R.string.send)).performClick()
        assertEquals(true, confirmed)
    }

    @Test
    fun busyReviewCannotConfirmOrRemoveMembers() {
        var removed = false
        var confirmed = false
        val state = appState()
        composeRule.setContent {
            WhiteNoiseTheme {
                SelectedMembersReviewScreen(
                    members = members,
                    appState = state,
                    busy = true,
                    onBack = {},
                    onRemove = { removed = true },
                    onConfirm = { confirmed = true },
                    confirmIcon = Icons.Default.Check,
                    confirmLabel = context.getString(R.string.add_member),
                )
            }
        }

        composeRule.onNodeWithTag(SELECTED_MEMBERS_CONFIRM_TAG).assertIsNotEnabled().performClick()
        composeRule
            .onNodeWithContentDescription(
                context.getString(R.string.remove_member_named, members.first().displayName),
            ).assertIsNotEnabled()
            .performClick()

        assertEquals(false, confirmed)
        assertEquals(false, removed)
    }

    @Test
    fun inviteRolesDefaultToMemberAndChangeOnlyTheExplicitlyChosenRecipient() {
        val roles = mutableStateListOf<String>()
        var submitted = emptySet<String>()
        var removed = 0
        var confirmed = 0
        val state = appState()
        composeRule.setContent {
            WhiteNoiseTheme {
                SelectedMembersReviewScreen(
                    members = members,
                    appState = state,
                    busy = false,
                    onBack = {},
                    onRemove = {
                        removed += 1
                        roles.remove(it.accountIdHex)
                    },
                    onConfirm = {
                        confirmed += 1
                        submitted = roles.toSet()
                    },
                    confirmIcon = Icons.Default.Check,
                    confirmLabel = context.getString(R.string.add_member),
                    initialAdminIds = roles.toSet(),
                    onInitialAdminChange = { member, admin ->
                        roles.remove(member.accountIdHex)
                        if (admin) roles.add(member.accountIdHex)
                    },
                )
            }
        }
        val adminLabel = context.getString(R.string.admin)
        composeRule.onNodeWithContentDescription("$adminLabel, ${members.first().displayName}").performClick()
        composeRule
            .onNodeWithContentDescription("$adminLabel, ${members.first().displayName}")
            .assertIsDisplayed()
            .assertIsOn()
        composeRule
            .onNodeWithContentDescription("$adminLabel, ${members.last().displayName}")
            .assertIsDisplayed()
            .assertIsOff()
        assertEquals(0, removed)
        assertEquals(0, confirmed)
        composeRule.onNodeWithText(context.getString(R.string.add_member)).performClick()
        assertEquals(setOf(members.first().accountIdHex), submitted)
        val removeLabel = context.getString(R.string.remove_member_named, members.first().displayName)
        composeRule.onNodeWithContentDescription(removeLabel).performClick()
        assertEquals(emptyList<String>(), roles.toList())
    }

    @Test
    fun busyRoleSwitchCannotChangePrivileges() {
        var changes = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                SelectedMembersReviewScreen(
                    members = members,
                    appState = appState(),
                    busy = true,
                    onBack = {},
                    onRemove = {},
                    onConfirm = {},
                    confirmIcon = Icons.Default.Check,
                    confirmLabel = context.getString(R.string.add_member),
                    onInitialAdminChange = { _, _ -> changes += 1 },
                )
            }
        }
        val adminLabel = context.getString(R.string.admin)
        composeRule
            .onNodeWithContentDescription("$adminLabel, ${members.first().displayName}")
            .assertIsNotEnabled()
            .performClick()
        composeRule
            .onNodeWithContentDescription("$adminLabel, ${members.last().displayName}")
            .assertIsNotEnabled()
            .assertIsOff()
        assertEquals(0, changes)
    }

    /** A nameless member's key title copies its key, while a human name that starts with npub1 stays plain (#3565). */
    @Test
    fun onlyAKeyTitleCopiesAndAHumanNpubNameStaysPlain() {
        val keys = mapOf("d".repeat(64) to NAMELESS_NPUB, "e".repeat(64) to HUMAN_NPUB)
        val state = appState(npubs = keys::get)
        val nameless = RecipientSearch.Candidate("d".repeat(64), NAMELESS_SHORT, NAMELESS_NPUB)
        val human = RecipientSearch.Candidate("e".repeat(64), "npub1fan club", HUMAN_NPUB)
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        composeRule.setContent {
            WhiteNoiseTheme {
                SelectedMembersReviewScreen(
                    members = listOf(nameless, human),
                    appState = state,
                    busy = false,
                    onBack = {},
                    onRemove = {},
                    onConfirm = {},
                    confirmIcon = Icons.AutoMirrored.Filled.Send,
                    confirmLabel = context.getString(R.string.send),
                )
            }
        }
        composeRule.waitUntil(5_000) { state.npubForDisplay("d".repeat(64)) == NAMELESS_NPUB }
        composeRule.waitForIdle()

        composeRule
            .onNode(hasText(NAMELESS_SHORT) and SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions), true)
            .performClick()
        assertEquals(
            NAMELESS_NPUB,
            clipboard.primaryClip
                ?.getItemAt(0)
                ?.text
                ?.toString(),
        )
        val humanName = composeRule.onNodeWithText("npub1fan club", useUnmergedTree = true).fetchSemanticsNode()
        assertNull(humanName.config.getOrNull(SemanticsActions.CustomActions))
        assertEquals(listOf("npub1fan club"), humanName.config[SemanticsProperties.Text].map { it.text })
    }

    /** Builds an app state with alice as the active account. */
    private fun appState(npubs: (String) -> String? = { null }) =
        WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore(EmptyDraftPersistence),
            accountIdHexResolver = { null },
            accounts =
                listOf(
                    AccountSummaryFfi(
                        label = "alice",
                        accountIdHex = "alice",
                        localSigning = true,
                        externalSigning = false,
                        signedOut = false,
                        running = true,
                        unlinked = false,
                    ),
                ),
            activeAccountRef = "alice",
            profileReader = { null },
            profileDisplayNameReader = { null },
            npubPresentationReader = { npubs(it) },
        )

    private companion object {
        const val NAMELESS_NPUB = "npub1qqqqqqqqqqqqqqqqqqqqqqqqqppppppppqqqqqqqqqqqqqqqqqqqqqqqqq"
        const val NAMELESS_SHORT = "npub1qqqqq...qqqqqqqq"
        const val HUMAN_NPUB = "npub1pppppppppppppppppppppppppzzzzzzzzppppppppppppppppppppppppp"

        val members =
            listOf(
                RecipientSearch.Candidate("a".repeat(64), "Alexandria Example", "npub1alexandria"),
                RecipientSearch.Candidate("b".repeat(64), "Benjamin Example", "npub1benjamin"),
            )

        // What the picker captures for a pasted npub whose profile hasn't loaded.
        val frozenPlaceholderMember =
            RecipientSearch.Candidate("c".repeat(64), "npub1camil…example", "npub1camilleexample")

        object EmptyDraftPersistence : DraftPersistence {
            override fun read(): Map<String, String> = emptyMap()

            override fun write(
                key: String,
                value: String?,
            ) = Unit
        }
    }
}
