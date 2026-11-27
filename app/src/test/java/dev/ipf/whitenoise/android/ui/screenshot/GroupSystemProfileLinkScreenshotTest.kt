package dev.ipf.whitenoise.android.ui.screenshot

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.GroupSystemEventFfi
import dev.ipf.marmotkit.GroupSystemEventProvenanceFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.conversation.GROUP_SYSTEM_ACTOR_LINK_TAG
import dev.ipf.whitenoise.android.ui.conversation.GROUP_SYSTEM_SUBJECT_LINK_TAG
import dev.ipf.whitenoise.android.ui.conversation.GroupSystemRow
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
 * Names in member-added and member-removed rows open their profiles: the affected member (#2957) and, for
 * additions, the member who added them (#4209). Covers routing by authenticated id and slot, stale-account
 * taps, the untouched long-press and Wave actions, and the linked rows' rendering across themes, RTL and
 * large text.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class GroupSystemProfileLinkScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Tapping the added member's name opens that member, not the admin who added them. */
    @Test
    fun addedSubjectLinkOpensTheSubject() {
        val opened = renderSingle(event("member_added"))

        composeRule.onNodeWithText("Alice added Bob").performFirstLinkClick(::isSubjectLink)

        assertEquals(listOf(SUBJECT_ID), opened)
    }

    /** Tapping the admin's name in the same row opens the admin (#4209). */
    @Test
    fun addedActorLinkOpensTheActor() {
        val opened = renderSingle(event("member_added"))

        composeRule.onNodeWithText("Alice added Bob").performFirstLinkClick(::isActorLink)

        assertEquals(listOf(ACTOR_ID), opened)
    }

    /** A removed member stays reachable from the removal row, whose actor stays plain text. */
    @Test
    fun removedSubjectLinkOpensTheRemovedMember() {
        val opened = renderSingle(event("member_removed"))
        val node = composeRule.onNodeWithText("Alice removed Bob")

        assertEquals(listOf(GROUP_SYSTEM_SUBJECT_LINK_TAG), linkTags(node))
        node.performFirstLinkClick()
        assertEquals(listOf(SUBJECT_ID), opened)
    }

    /**
     * With the actor and subject sharing a name, each slot is its own link — the separate link TalkBack
     * offers for each — and each opens its own person.
     */
    @Test
    fun identicalNamesLinkEachSlot() {
        val opened = renderSingle(event("member_added", actorName = "Sam", subjectName = "Sam"))
        val node = composeRule.onNodeWithText("Sam added Sam")
        val links = linkRanges(node)

        assertEquals(listOf(0 to 3, 10 to 13), links.map { it.start to it.end })
        assertEquals(listOf(GROUP_SYSTEM_ACTOR_LINK_TAG, GROUP_SYSTEM_SUBJECT_LINK_TAG), linkTags(node))
        node.performFirstLinkClick(::isActorLink)
        node.performFirstLinkClick(::isSubjectLink)
        assertEquals(listOf(ACTOR_ID, SUBJECT_ID), opened)
    }

    /** "You added Bob" leaves "You" as plain text and links only Bob. */
    @Test
    fun readerAsActorStaysPlain() {
        renderSingle(event("member_added", actorId = ACCOUNT_ID))

        assertEquals(listOf(GROUP_SYSTEM_SUBJECT_LINK_TAG), linkTags(composeRule.onNodeWithText("You added Bob")))
    }

    /** After switching accounts, taps on either name in a row rendered for the old account open nothing. */
    @Test
    fun staleAccountTapOpensNothing() {
        val appState = testAppState()
        val opened = renderSingle(event("member_added"), appState = appState)
        val node = composeRule.onNodeWithText("Alice added Bob")

        composeRule.runOnIdle { appState.switchMediaSessionForTest("another-account") }
        node.performFirstLinkClick(::isActorLink)
        node.performFirstLinkClick(::isSubjectLink)

        assertEquals(emptyList<String>(), opened)
    }

    /** Long press still offers Delete for me, and Wave hi still greets, alongside the new link. */
    @Test
    fun longPressAndWaveStayIndependent() {
        val waved = mutableListOf<String>()
        val opened =
            renderSingle(
                event("member_added", actorId = ACCOUNT_ID),
                onDeleteForMe = {},
                onWave = { target, _ -> waved += target },
            )

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(context.getString(R.string.wave_hi)).fetchSemanticsNodes().size == 1
        }
        composeRule.onNodeWithText(context.getString(R.string.wave_hi)).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { waved.isNotEmpty() }
        assertEquals(listOf(SUBJECT_ID), waved)
        assertEquals(emptyList<String>(), opened)
        composeRule.onNodeWithText("You added Bob").performTouchInput { longClick(centerLeft + Offset(4f, 0f)) }
        composeRule.onNodeWithText(context.getString(R.string.delete_for_me)).assertExists()
        assertTrue(opened.isEmpty())
    }

    /** Linked added and removed rows in the light theme. */
    @Test
    fun linkedRowsLight() = captureLinkedRows(LinkTheme.LIGHT)

    /** Linked added and removed rows in the dark theme. */
    @Test
    fun linkedRowsDark() = captureLinkedRows(LinkTheme.DARK)

    /** Linked added and removed rows in the AMOLED theme. */
    @Test
    fun linkedRowsAmoled() = captureLinkedRows(LinkTheme.AMOLED)

    /** Linked added and removed rows right-to-left at double font scale with a long name. */
    @Test
    fun linkedRowsLargeRtl() = captureLinkedRows(LinkTheme.LARGE_RTL)

    /** Renders an added and a removed row in [theme] and records the baseline. */
    private fun captureLinkedRows(theme: LinkTheme) {
        val appState = testAppState()
        val name = if (theme == LinkTheme.LARGE_RTL) "Bob with a very long display name" else "Bob"
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f, if (theme == LinkTheme.LARGE_RTL) 2f else 1f),
                LocalLayoutDirection provides
                    if (theme == LinkTheme.LARGE_RTL) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = theme != LinkTheme.LIGHT, amoled = theme == LinkTheme.AMOLED) {
                    Surface(Modifier.width(360.dp).padding(16.dp).testTag(SCREENSHOT_TAG)) {
                        Column {
                            LinkedRow(appState, event("member_added", subjectName = name))
                            LinkedRow(appState, event("member_removed", subjectName = name))
                        }
                    }
                }
            }
        }
        composeRule
            .onNodeWithTag(SCREENSHOT_TAG)
            .captureRoboImage("src/test/snapshots/group_system_profile_link_${theme.name.lowercase()}.png")
    }

    /** The link annotations on [node]'s text, in order. */
    private fun linkRanges(node: SemanticsNodeInteraction): List<LinkRange> {
        val text =
            node
                .fetchSemanticsNode()
                .config
                .getOrNull(SemanticsProperties.Text)
                ?.single()
        return requireNotNull(text).getLinkAnnotations(0, text.length)
    }

    /** The tags of [node]'s links, in order. */
    private fun linkTags(node: SemanticsNodeInteraction): List<String?> = linkRanges(node).map(::linkTag)

    private fun linkTag(link: LinkRange) = (link.item as? LinkAnnotation.Clickable)?.tag

    private fun isActorLink(link: LinkRange) = linkTag(link) == GROUP_SYSTEM_ACTOR_LINK_TAG

    private fun isSubjectLink(link: LinkRange) = linkTag(link) == GROUP_SYSTEM_SUBJECT_LINK_TAG

    /** One linked row whose taps are discarded, for the rendering baselines. */
    @Composable
    @Suppress("FunctionNaming")
    private fun LinkedRow(
        appState: WhiteNoiseAppState,
        event: GroupSystemEventFfi,
    ) {
        GroupSystemRow(record = record(), appState = appState, groupSystem = event, onOpenProfile = {})
    }

    /** Renders one row with a profile callback and returns the list of accounts it opened. */
    private fun renderSingle(
        event: GroupSystemEventFfi,
        appState: WhiteNoiseAppState = testAppState(),
        onDeleteForMe: (() -> Unit)? = null,
        onWave: (suspend (String, () -> Unit) -> Unit)? = null,
    ): List<String> {
        val opened = mutableListOf<String>()
        composeRule.setContent {
            WhiteNoiseTheme {
                Surface(Modifier.width(360.dp).padding(16.dp)) {
                    GroupSystemRow(
                        record = record(),
                        appState = appState,
                        groupSystem = event,
                        onDeleteForMe = onDeleteForMe,
                        onWave = onWave,
                        waveAccountRef = ACCOUNT_REF,
                        onOpenProfile = { opened += it },
                    )
                }
            }
        }
        return opened
    }

    /** An authenticated membership event; the actor is another member unless [actorId] says otherwise. */
    private fun event(
        systemType: String,
        actorId: String = ACTOR_ID,
        actorName: String = "Alice",
        subjectName: String = "Bob",
    ) = GroupSystemEventFfi(
        provenance = GroupSystemEventProvenanceFfi.AUTHENTICATED_GROUP_STATE,
        actorDisplayName = actorName,
        subjectDisplayName = subjectName,
        systemType = systemType,
        text = "",
        actorAccountIdHex = actorId,
        subjectAccountIdHex = SUBJECT_ID,
        name = null,
        oldName = null,
        oldRetentionSeconds = null,
        newRetentionSeconds = null,
    )

    /** The engine-synthesized system record carrying the event. */
    private fun record() =
        AppMessageRecordFfi(
            invalidated = false,
            messageIdHex = "cc".repeat(32),
            direction = "system",
            groupIdHex = "bb".repeat(32),
            sender = ACTOR_ID,
            plaintext = "",
            contentTokens =
                MarkdownDocumentFfi(truncated = false, blankLinesBefore = byteArrayOf(), blocks = emptyList()),
            kind = 1210uL,
            tags = emptyList(),
            sourceEpoch = null,
            retentionSeconds = null,
            retentionExpiresAt = null,
            recordedAt = 100uL,
            receivedAt = 100uL,
            sticker = null,
        )

    /** App state with one active local account and no stored drafts. */
    private fun testAppState(): WhiteNoiseAppState =
        WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore(ProfileLinkDraftPersistence()),
            accountIdHexResolver = { ACCOUNT_ID },
            accounts =
                listOf(
                    AccountSummaryFfi(
                        label = ACCOUNT_REF,
                        accountIdHex = ACCOUNT_ID,
                        localSigning = true,
                        externalSigning = false,
                        signedOut = false,
                        running = true,
                        unlinked = false,
                    ),
                ),
            activeAccountRef = ACCOUNT_REF,
        )

    private enum class LinkTheme { LIGHT, DARK, AMOLED, LARGE_RTL }

    private companion object {
        const val SCREENSHOT_TAG = "group-system-profile-link"
        const val ACCOUNT_REF = "personal"
        val ACCOUNT_ID = "aa".repeat(32)
        val ACTOR_ID = "ee".repeat(32)
        val SUBJECT_ID = "dd".repeat(32)
    }
}

private typealias LinkRange = AnnotatedString.Range<LinkAnnotation>

/** Draft persistence that stores nothing. */
private class ProfileLinkDraftPersistence : DraftPersistence {
    /** No drafts are ever stored. */
    override fun read(): Map<String, String> = emptyMap()

    /** Writes are discarded. */
    override fun write(
        key: String,
        value: String?,
    ) = Unit
}
