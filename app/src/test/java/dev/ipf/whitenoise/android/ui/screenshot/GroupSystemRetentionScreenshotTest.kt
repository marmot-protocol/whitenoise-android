package dev.ipf.whitenoise.android.ui.screenshot

import android.content.Context
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import dev.ipf.whitenoise.android.ui.conversation.CONVERSATION_TIMELINE_VERTICAL_ARRANGEMENT
import dev.ipf.whitenoise.android.ui.conversation.DaySeparator
import dev.ipf.whitenoise.android.ui.conversation.GroupSystemRow
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class GroupSystemRetentionScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun retentionChangeHistoryRow() {
        val appState = testAppState()
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                Surface(
                    modifier =
                        Modifier
                            .width(360.dp)
                            .padding(16.dp)
                            .testTag(SCREENSHOT_TAG),
                ) {
                    GroupSystemRow(
                        record = retentionChangeRecord(),
                        appState = appState,
                        groupSystem = retentionChangeEvent(),
                    )
                }
            }
        }

        val expected =
            context.getString(
                R.string.group_system_disappearing_set_you,
                context.getString(R.string.disappearing_5_minutes),
            )
        composeRule.onNodeWithText(expected).assertExists()
        composeRule
            .onNodeWithTag(SCREENSHOT_TAG)
            .captureRoboImage("src/test/snapshots/group_system_retention_history_dark.png")
    }

    /** Adjacent real event rows and their following date retain prototype gaps after native slot compensation. */
    @Test
    fun adjacentEventsAndFollowingDayHavePrototypeSpacing() {
        val appState = testAppState()
        composeRule.setContent {
            WhiteNoiseTheme {
                Surface(Modifier.width(360.dp)) {
                    // The real transcript's arrangement, so the measured gaps are
                    // the ones the conversation actually renders.
                    LazyColumn(verticalArrangement = CONVERSATION_TIMELINE_VERTICAL_ARRANGEMENT) {
                        item {
                            GroupSystemRow(
                                record = retentionChangeRecord(),
                                appState = appState,
                                groupSystem = retentionChangeEvent(),
                            )
                        }
                        item {
                            GroupSystemRow(
                                record = retentionChangeRecord(),
                                appState = appState,
                                groupSystem = retentionChangeEvent(),
                            )
                        }
                        item { DaySeparator("September 13, 2026", followsGroupEvent = true) }
                    }
                }
            }
        }
        val expected =
            context.getString(
                R.string.group_system_disappearing_set_you,
                context.getString(R.string.disappearing_5_minutes),
            )
        val summaries = composeRule.onAllNodesWithText(expected)
        val first = summaries[0].fetchSemanticsNode().boundsInRoot
        val second = summaries[1].fetchSemanticsNode().boundsInRoot
        val date = composeRule.onNodeWithTag("conversation.date.inline").fetchSemanticsNode().boundsInRoot
        assertEquals(18f, second.top - first.bottom, 1f)
        assertEquals(26f, date.top - second.bottom, 1f)
    }

    @Test
    fun waveLight() = captureWave(WaveTheme.LIGHT)

    @Test
    fun waveDark() = captureWave(WaveTheme.DARK)

    @Test
    fun waveAmoled() = captureWave(WaveTheme.AMOLED)

    @Test
    fun waveLargeRtl() = captureWave(WaveTheme.LARGE_RTL)

    private fun captureWave(theme: WaveTheme) {
        val appState = testAppState()
        var wavedAt: String? = null
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f, if (theme == WaveTheme.LARGE_RTL) 2f else 1f),
                LocalLayoutDirection provides
                    if (theme == WaveTheme.LARGE_RTL) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = theme != WaveTheme.LIGHT, amoled = theme == WaveTheme.AMOLED) {
                    Surface(Modifier.width(360.dp).padding(16.dp).testTag(SCREENSHOT_TAG)) {
                        GroupSystemRow(
                            record = retentionChangeRecord(),
                            appState = appState,
                            groupSystem =
                                addedMemberEvent().copy(
                                    subjectDisplayName =
                                        if (theme == WaveTheme.LARGE_RTL) {
                                            "Bob with a very long display name"
                                        } else {
                                            "Bob"
                                        },
                                ),
                            onWave = { target, _ -> wavedAt = target },
                        )
                    }
                }
            }
        }
        awaitWaveButton()
        composeRule
            .onNodeWithTag(SCREENSHOT_TAG)
            .captureRoboImage("src/test/snapshots/group_system_wave_${theme.name.lowercase()}.png")
        composeRule.onNodeWithText(context.getString(R.string.wave_hi)).performClick()
        assertEquals(ADDED_ID, wavedAt)
    }

    @Test
    fun waveDismissalFollowsAcceptance() {
        val appState = testAppState()
        var record by mutableStateOf(retentionChangeRecord())
        var accountRef by mutableStateOf(ACCOUNT_REF)
        var visible by mutableStateOf(true)
        var attempts = 0
        val finishSend = CompletableDeferred<Unit>()
        composeRule.setContent {
            WhiteNoiseTheme {
                Surface(Modifier.width(360.dp).padding(16.dp).testTag(SCREENSHOT_TAG)) {
                    if (visible) {
                        GroupSystemRow(
                            record = record,
                            appState = appState,
                            groupSystem = addedMemberEvent(),
                            waveAccountRef = accountRef,
                            onWave = { _, onAccepted ->
                                attempts++
                                if (attempts > 1) {
                                    onAccepted()
                                    finishSend.await()
                                }
                            },
                        )
                    }
                }
            }
        }
        awaitWaveButton()
        val button = composeRule.onNodeWithText(context.getString(R.string.wave_hi))
        button.performClick()
        button.assertExists() // A rejected send remains available.
        button.performClick()
        button.assertDoesNotExist() // Acceptance hides it before delivery settles.
        assertEquals(2, attempts)
        composeRule
            .onNodeWithTag(SCREENSHOT_TAG)
            .captureRoboImage("src/test/snapshots/group_system_wave_accepted_light.png")
        finishSend.completeExceptionally(CancellationException("Delivery interrupted after acceptance"))
        button.assertDoesNotExist()
        val stored = context.getSharedPreferences("whitenoise.wave_dismissals", Context.MODE_PRIVATE)
        assertTrue(stored.getBoolean("${ACCOUNT_REF.length}:$ACCOUNT_REF:$GROUP_ID:$MESSAGE_ID", false))
        composeRule.runOnIdle { visible = false }
        composeRule.runOnIdle { visible = true }
        composeRule.waitForIdle()
        button.assertDoesNotExist() // A newly created row reloads the durable dismissal.

        composeRule.runOnIdle { record = record.copy(messageIdHex = "rejoined-event") }
        awaitWaveButton()
        composeRule.runOnIdle { record = retentionChangeRecord().copy(groupIdHex = "other-group") }
        awaitWaveButton()
        composeRule.runOnIdle {
            record = retentionChangeRecord()
            accountRef = "another-account"
        }
        awaitWaveButton()
        composeRule.runOnIdle { accountRef = ACCOUNT_REF }
        composeRule.waitForIdle()
        button.assertDoesNotExist()
    }

    private fun awaitWaveButton() {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(context.getString(R.string.wave_hi)).fetchSemanticsNodes().size == 1
        }
    }

    @Test
    fun waveOnlyForOtherNewMembers() {
        val appState = testAppState()
        val added = addedMemberEvent()
        composeRule.setContent {
            WhiteNoiseTheme {
                LazyColumn {
                    items(6) { index ->
                        GroupSystemRow(
                            record = retentionChangeRecord().copy(direction = if (index == 5) "received" else "system"),
                            appState = appState,
                            groupSystem =
                                when (index) {
                                    0 -> added.copy(subjectAccountIdHex = ACCOUNT_ID)
                                    1 -> added.copy(systemType = "member_removed")
                                    2 -> added.copy(provenance = GroupSystemEventProvenanceFfi.MEMBER_AUTHORED)
                                    3 -> added.copy(subjectAccountIdHex = null)
                                    else -> added
                                },
                            onWave = if (index == 4) null else { _, _ -> error("Unexpected wave") },
                        )
                    }
                }
            }
        }
        composeRule.onNodeWithText(context.getString(R.string.wave_hi)).assertDoesNotExist()
    }

    private fun addedMemberEvent() =
        retentionChangeEvent().copy(
            systemType = "member_added",
            subjectAccountIdHex = ADDED_ID,
            subjectDisplayName = "Bob",
            oldRetentionSeconds = null,
            newRetentionSeconds = null,
        )

    private enum class WaveTheme { LIGHT, DARK, AMOLED, LARGE_RTL }

    private fun testAppState(): WhiteNoiseAppState =
        WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore(GroupSystemScreenshotDraftPersistence()),
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

    private fun retentionChangeRecord() =
        AppMessageRecordFfi(
            messageIdHex = MESSAGE_ID,
            direction = "system",
            groupIdHex = GROUP_ID,
            sender = ACCOUNT_ID,
            plaintext =
                """{"v":1,"system_type":"disappearing_timer_changed","data":""" +
                    """{"old_retention_seconds":0,"new_retention_seconds":300}}""",
            contentTokens =
                MarkdownDocumentFfi(
                    truncated = false,
                    blocks = emptyList(),
                    blankLinesBefore = ByteArray(0),
                ),
            kind = 1210uL,
            tags = emptyList(),
            sourceEpoch = null,
            retentionSeconds = null,
            retentionExpiresAt = null,
            recordedAt = 100uL,
            receivedAt = 100uL,
        )

    private fun retentionChangeEvent() =
        GroupSystemEventFfi(
            provenance = GroupSystemEventProvenanceFfi.AUTHENTICATED_GROUP_STATE,
            actorDisplayName = null,
            subjectDisplayName = null,
            systemType = "disappearing_timer_changed",
            text = "Messages now disappear after 5 minutes",
            actorAccountIdHex = ACCOUNT_ID,
            subjectAccountIdHex = null,
            name = null,
            oldName = null,
            oldRetentionSeconds = 0uL,
            newRetentionSeconds = 300uL,
        )

    private companion object {
        const val SCREENSHOT_TAG = "group-system-retention-history"
        const val ACCOUNT_REF = "personal"
        val ACCOUNT_ID = "aa".repeat(32)
        val GROUP_ID = "bb".repeat(32)
        val MESSAGE_ID = "cc".repeat(32)
        val ADDED_ID = "dd".repeat(32)
    }
}

private class GroupSystemScreenshotDraftPersistence : DraftPersistence {
    override fun read(): Map<String, String> = emptyMap()

    override fun write(
        key: String,
        value: String?,
    ) = Unit
}
