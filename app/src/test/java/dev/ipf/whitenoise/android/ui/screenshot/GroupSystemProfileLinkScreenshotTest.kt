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
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.test.performTouchInput
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
 * The affected member's name in a member-added or member-removed row opens that member's profile (#2957).
 * Covers routing by authenticated id, stale-account taps, the untouched long-press and Wave actions, and the
 * linked rows' rendering across themes, RTL and large text.
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

        composeRule.onNodeWithText("Alice added Bob").performFirstLinkClick()

        assertEquals(listOf(SUBJECT_ID), opened)
    }

    /** A removed member stays reachable from the removal row. */
    @Test
    fun removedSubjectLinkOpensTheRemovedMember() {
        val opened = renderSingle(event("member_removed"))

        composeRule.onNodeWithText("Alice removed Bob").performFirstLinkClick()

        assertEquals(listOf(SUBJECT_ID), opened)
    }

    /** With the actor and subject sharing a name, only the subject's slot is a link and it opens the subject. */
    @Test
    fun identicalNamesLinkOnlyTheSubject() {
        val opened = renderSingle(event("member_added", actorName = "Sam", subjectName = "Sam"))
        val node = composeRule.onNodeWithText("Sam added Sam")
        val text =
            node
                .fetchSemanticsNode()
                .config
                .getOrNull(SemanticsProperties.Text)
                ?.single()
        val links = requireNotNull(text).getLinkAnnotations(0, text.length)

        assertEquals(1, links.size)
        assertEquals(10, links.single().start)
        assertEquals(GROUP_SYSTEM_SUBJECT_LINK_TAG, (links.single().item as LinkAnnotation.Clickable).tag)
        node.performFirstLinkClick()
        assertEquals(listOf(SUBJECT_ID), opened)
    }

    /** A row rendered for another account ignores the tap instead of opening a profile under the wrong account. */
    @Test
    fun staleAccountTapOpensNothing() {
        val opened = renderSingle(event("member_added"), rowAccountRef = "another-account")

        composeRule.onNodeWithText("Alice added Bob").performFirstLinkClick()

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
        rowAccountRef: String = ACCOUNT_REF,
        onDeleteForMe: (() -> Unit)? = null,
        onWave: (suspend (String, () -> Unit) -> Unit)? = null,
    ): List<String> {
        val appState = testAppState()
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
                        waveAccountRef = rowAccountRef,
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
