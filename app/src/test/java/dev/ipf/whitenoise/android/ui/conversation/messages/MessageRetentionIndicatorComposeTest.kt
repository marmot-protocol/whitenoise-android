package dev.ipf.whitenoise.android.ui.conversation.messages

import android.text.format.DateUtils
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MessageRetentionIndicatorComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun activeCountdownExposesLocalizedRemainingTimeAndExactExpiry() {
        composeRule.setContent {
            WhiteNoiseTheme {
                MessageInlineFooter(
                    timeText = "12:34",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    showStatus = true,
                    status = MessageStatus.Sent,
                    editedLabel = null,
                    onEditedClick = null,
                    retention = input(expiresAtEpochSeconds = 200uL),
                    retentionClockMillis = { 150_000L },
                )
            }
        }

        val stateDescription =
            composeRule
                .onNodeWithContentDescription(context.getString(R.string.disappearing_message))
                .fetchSemanticsNode()
                .config[SemanticsProperties.StateDescription]

        val expiry =
            DateUtils.formatDateTime(
                context,
                200_000L,
                DateUtils.FORMAT_SHOW_DATE or
                    DateUtils.FORMAT_SHOW_TIME or
                    DateUtils.FORMAT_SHOW_YEAR or
                    DateUtils.FORMAT_ABBREV_MONTH,
            )
        assertEquals(
            formatRetentionExpiryState(
                context.resources.configuration.locales[0],
                remainingMillis = 50_000L,
                expiryLabel = expiry,
            ),
            stateDescription,
        )
    }

    @Test
    fun missingExpiryUsesGenericNonProgressSemantics() {
        composeRule.setContent {
            WhiteNoiseTheme {
                MessageInlineFooter(
                    timeText = "12:34",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    showStatus = false,
                    status = MessageStatus.Received,
                    editedLabel = null,
                    onEditedClick = null,
                    retention = input(expiresAtEpochSeconds = null),
                )
            }
        }

        composeRule
            .onNodeWithContentDescription(context.getString(R.string.disappearing_message))
            .assertExists()
    }

    @Test
    fun latestProjectionPromotesWaitingToRunningAndDeletionRemovesTheClock() {
        var retention: RetentionIndicatorInput? by mutableStateOf(input(expiresAtEpochSeconds = null))
        composeRule.setContent {
            WhiteNoiseTheme {
                MessageInlineFooter(
                    timeText = "12:34",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    showStatus = false,
                    status = MessageStatus.Received,
                    editedLabel = null,
                    onEditedClick = null,
                    retention = retention,
                    retentionClockMillis = { 150_000L },
                )
            }
        }

        val label = context.getString(R.string.disappearing_message)
        composeRule.onNodeWithContentDescription(label).assertExists()

        composeRule.runOnIdle {
            retention = retention?.copy(sourceEpoch = 2uL, expiresAtEpochSeconds = 200uL)
        }
        val runningNode = composeRule.onNodeWithContentDescription(label).fetchSemanticsNode()
        assertTrue(runningNode.config.contains(SemanticsProperties.StateDescription))

        composeRule.runOnIdle { retention = null }
        composeRule.onNodeWithContentDescription(label).assertDoesNotExist()
    }

    private fun input(expiresAtEpochSeconds: ULong?): RetentionIndicatorInput =
        RetentionIndicatorInput(
            controllerKey = testControllerKey,
            accountRef = "personal",
            groupIdHex = "group",
            messageIdHex = "message",
            sourceEpoch = 1uL,
            durationSeconds = 100uL,
            expiresAtEpochSeconds = expiresAtEpochSeconds,
        )

    private companion object {
        val testControllerKey = Any()
    }
}
