package dev.ipf.whitenoise.android.ui.conversation.messages

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MessageRetentionIndicatorTest {
    @Test
    fun inputRequiresPositiveRetentionAndHidesDeletedRecords() {
        assertNull(input(durationSeconds = null))
        assertNull(input(durationSeconds = 0uL))
        assertNull(input(accountRef = null))
        assertNull(input(deleted = true))

        assertEquals(60uL, input()?.durationSeconds)
    }

    @Test
    fun missingOrInvalidExpiryWaitsWithoutInventingADeadline() {
        assertSame(
            RetentionIndicatorPresentation.Waiting,
            retentionIndicatorPresentation(input(expiresAtEpochSeconds = null), nowEpochMillis = 0L),
        )
        assertSame(
            RetentionIndicatorPresentation.Waiting,
            retentionIndicatorPresentation(input(expiresAtEpochSeconds = 0uL), nowEpochMillis = 0L),
        )
        assertSame(
            RetentionIndicatorPresentation.Waiting,
            retentionIndicatorPresentation(
                input(durationSeconds = 100uL, expiresAtEpochSeconds = 99uL),
                nowEpochMillis = 0L,
            ),
        )
        assertSame(
            RetentionIndicatorPresentation.Waiting,
            retentionIndicatorPresentation(
                input(expiresAtEpochSeconds = ULong.MAX_VALUE),
                nowEpochMillis = 0L,
            ),
        )
        assertSame(
            RetentionIndicatorPresentation.Waiting,
            retentionIndicatorPresentation(
                input()!!.copy(durationSeconds = 0uL),
                nowEpochMillis = 0L,
            ),
        )
    }

    @Test
    fun countdownUsesAuthoritativeIntervalAndClampsAtBothEnds() {
        val input = input(durationSeconds = 100uL, expiresAtEpochSeconds = 200uL)

        assertRunning(input, nowEpochMillis = 50_000L, fraction = 1f, remainingMillis = 100_000L)
        assertRunning(input, nowEpochMillis = 150_000L, fraction = 0.5f, remainingMillis = 50_000L)
        val expired = assertRunning(input, nowEpochMillis = 250_000L, fraction = 0f, remainingMillis = 0L)
        assertNull(expired.refreshAfterMillis)
    }

    @Test
    fun refreshCadenceIsBucketedAndAlignsToTheNextVisibleChange() {
        assertEquals(15L * 60_000L, retentionRefreshAfterMillis(2L * 24L * 60L * 60_000L))
        assertEquals(250L, retentionRefreshAfterMillis(24L * 60L * 60_000L + 1L))
        assertEquals(5L * 60_000L, retentionRefreshAfterMillis(2L * 60L * 60_000L))
        assertEquals(60_000L, retentionRefreshAfterMillis(10L * 60_000L))
        assertEquals(15_000L, retentionRefreshAfterMillis(2L * 60_000L))
        assertEquals(1_000L, retentionRefreshAfterMillis(60_000L))
        assertNull(retentionRefreshAfterMillis(0L))
    }

    @Test
    fun accessibilityDurationRemainsRelativeForLongWindows() {
        assertEquals("in 50 seconds", formatRetentionRemaining(Locale.US, 50_000L))
        assertEquals("in 90 days", formatRetentionRemaining(Locale.US, 90L * MILLIS_PER_DAY))
        assertEquals("in 2 minutes", formatRetentionRemaining(Locale.US, MILLIS_PER_MINUTE + 1L))
    }

    @Test
    fun projectionIdentityRestartsForAccountGroupMessageGenerationOrExpiryChanges() {
        val original = input()!!

        assertNotEquals(original, original.copy(controllerKey = Any()))
        assertNotEquals(original, original.copy(accountRef = "work"))
        assertNotEquals(original, original.copy(groupIdHex = "group-b"))
        assertNotEquals(original, original.copy(messageIdHex = "message-b"))
        assertNotEquals(original, original.copy(sourceEpoch = 8uL))
        assertNotEquals(original, original.copy(expiresAtEpochSeconds = 180uL))
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun tickerStopsWhenItsComposedOwnerIsDisposed() =
        runTest {
            val waitStarted = CompletableDeferred<Long>()
            val emissions = mutableListOf<RetentionIndicatorPresentation>()
            val job =
                launch {
                    runRetentionIndicatorTicker(
                        input = input(),
                        nowEpochMillis = { 150_000L },
                        waitMillis = { delayMillis ->
                            waitStarted.complete(delayMillis)
                            awaitCancellation()
                        },
                        emit = emissions::add,
                    )
                }

            runCurrent()
            assertEquals(1, emissions.size)
            assertTrue(waitStarted.await() > 0L)

            job.cancelAndJoin()
            assertTrue(job.isCancelled)
            assertEquals(1, emissions.size)
        }

    private fun input(
        controllerKey: Any = testControllerKey,
        accountRef: String? = "personal",
        groupIdHex: String = "group-a",
        messageIdHex: String = "message-a",
        sourceEpoch: ULong? = 7uL,
        durationSeconds: ULong? = 60uL,
        expiresAtEpochSeconds: ULong? = 200uL,
        deleted: Boolean = false,
    ): RetentionIndicatorInput? =
        retentionIndicatorInput(
            controllerKey = controllerKey,
            accountRef = accountRef,
            groupIdHex = groupIdHex,
            messageIdHex = messageIdHex,
            sourceEpoch = sourceEpoch,
            durationSeconds = durationSeconds,
            expiresAtEpochSeconds = expiresAtEpochSeconds,
            deleted = deleted,
        )

    private fun assertRunning(
        input: RetentionIndicatorInput?,
        nowEpochMillis: Long,
        fraction: Float,
        remainingMillis: Long,
    ): RetentionIndicatorPresentation.Running {
        val running = retentionIndicatorPresentation(input, nowEpochMillis)
        assertTrue(running is RetentionIndicatorPresentation.Running)
        running as RetentionIndicatorPresentation.Running
        assertEquals(fraction, running.remainingFraction, 0.0001f)
        assertEquals(remainingMillis, running.remainingMillis)
        assertEquals(200_000L, running.expiresAtEpochMillis)
        return running
    }

    private companion object {
        val testControllerKey = Any()
    }
}
