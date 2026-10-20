package dev.ipf.whitenoise.android.notifications

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.UUID

/** Characterizes execution and reference settlement using the production show/generation authorities. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NotificationEnrichmentRunnerTest {
    /** Ready-picture work completes inline without launching or borrowing another show reference. */
    @Test
    fun readyPicturesRunInlineWithoutRetainingAnotherReference() =
        runBlocking {
            val runner = NotificationEnrichmentRunner { error("ready pictures must stay inline") }
            var completed = false
            withShow { token ->
                runner.runOrLaunch(false, token) {
                    assertEquals(1, token.notificationGeneration.references)
                    completed = true
                }
                assertTrue(completed)
                assertEquals(1, token.notificationGeneration.references)
            }
        }

    /** Inline exceptions remain visible to the original caller. */
    @Test
    fun inlineFailurePropagatesUnchanged() =
        runBlocking {
            val failure = IOException("inline fixture failure")
            assertInlineFailure(failure)
        }

    /** Inline cancellation is neither detached nor converted into successful enrichment. */
    @Test
    fun inlineCancellationPropagatesUnchanged() =
        runBlocking {
            assertInlineFailure(CancellationException("inline fixture cancellation"))
        }

    /** A detached reference keeps the real show current after the initial registration finishes. */
    @Test
    fun detachedWorkOutlivesInitialRegistrationAndReleasesItsReference() =
        runBlocking {
            var pending: (suspend () -> Unit)? = null
            lateinit var retained: ConversationCardShowToken
            var bodySawCurrentShow = false
            val runner = NotificationEnrichmentRunner { pending = it }
            withShow { token ->
                retained = token
                runner.runOrLaunch(true, token) {
                    bodySawCurrentShow = ConversationCardPostSynchronizer.isShowCurrent(token)
                }
                assertEquals(2, token.notificationGeneration.references)
            }
            try {
                assertEquals(1, retained.notificationGeneration.references)
                assertTrue(ConversationCardPostSynchronizer.isShowCurrent(retained))
                checkNotNull(pending).invoke()
                pending = null
                assertTrue(bodySawCurrentShow)
                assertEquals(0, retained.notificationGeneration.references)
                assertFalse(ConversationCardPostSynchronizer.isShowCurrent(retained))
            } finally {
                pending?.invoke()
            }
        }

    /** A dismissal blocks detached admission without taking another reference. */
    @Test
    fun dismissedShowDoesNotLaunchEnrichment() =
        runBlocking {
            val runner = NotificationEnrichmentRunner { error("dismissed show reached the launcher") }
            withShow { token ->
                ConversationCardPostSynchronizer.markDismissed(token.notificationTag, token.notificationId)
                runner.runOrLaunch(true, token) { error("dismissed show ran enrichment") }
                assertEquals(1, token.notificationGeneration.references)
            }
        }

    /** Newest-show fencing applies to enrichment without changing ordinary first-post admission. */
    @Test
    fun supersededShowDoesNotLaunchButRemainsEligibleForItsInitialPost() =
        runBlocking {
            val runner = NotificationEnrichmentRunner { error("old show reached the launcher") }
            withShow { older ->
                ConversationCardPostSynchronizer.withRegisteredShow(
                    older.notificationTag,
                    older.notificationId,
                    older.conversationScope,
                ) { newer ->
                    runner.runOrLaunch(true, older) { error("old show ran enrichment") }
                    assertFalse(ConversationCardPostSynchronizer.isShowCurrent(older))
                    assertTrue(ConversationCardPostSynchronizer.isShowNotDismissed(older))
                    assertTrue(ConversationCardPostSynchronizer.isShowCurrent(newer))
                    assertEquals(1, older.notificationGeneration.references)
                }
            }
        }

    /** A rejected launch is contained and returns only the detached reference. */
    @Test
    fun launchFailureIsContainedAndReleasesItsReference() =
        runBlocking {
            assertLaunchFailure(IllegalStateException("launcher rejected fixture"), propagates = false)
        }

    /** Synchronous launcher cancellation releases its reference before escaping to the caller. */
    @Test
    fun launchCancellationPropagatesAndReleasesItsReference() =
        runBlocking {
            assertLaunchFailure(CancellationException("launcher cancelled fixture"), propagates = true)
        }

    /** Both checked and runtime body failures retain the old detached exception boundary. */
    @Test
    fun detachedFailureIsContainedAndReleasesItsReference() =
        runBlocking {
            listOf(IOException("body IO fixture"), IllegalStateException("body runtime fixture")).forEach { failure ->
                var pending: (suspend () -> Unit)? = null
                val runner = NotificationEnrichmentRunner { pending = it }
                withShow { token ->
                    runner.runOrLaunch(true, token) { throw failure }
                    checkNotNull(pending).invoke()
                    assertEquals(1, token.notificationGeneration.references)
                    assertTrue(ConversationCardPostSynchronizer.isShowCurrent(token))
                }
            }
        }

    /** Detached cancellation propagates from the launched block while still releasing its reference. */
    @Test
    fun detachedCancellationPropagatesAndReleasesItsReference() =
        runBlocking {
            val cancellation = CancellationException("body cancelled fixture")
            var pending: (suspend () -> Unit)? = null
            val runner = NotificationEnrichmentRunner { pending = it }
            withShow { token ->
                runner.runOrLaunch(true, token) { throw cancellation }
                assertSame(cancellation, runCatching { checkNotNull(pending).invoke() }.exceptionOrNull())
                assertEquals(1, token.notificationGeneration.references)
            }
        }

    /** A launcher throwing after inline execution cannot consume another owner's retained reference. */
    @Test
    fun completedBodyThenLaunchFailureReleasesOnlyOnce() =
        runBlocking {
            assertSynchronousLaunchFailure(IllegalStateException("launch failed after body"), propagates = false)
        }

    /** The same release-once protection applies when the synchronous launch reports cancellation. */
    @Test
    fun completedBodyThenLaunchCancellationReleasesOnlyOnce() =
        runBlocking {
            assertSynchronousLaunchFailure(CancellationException("launch cancelled after body"), propagates = true)
        }

    /** Checks the inline branch without replacing the production show registry with a fake counter. */
    private suspend fun assertInlineFailure(failure: Exception) {
        val runner = NotificationEnrichmentRunner { error("inline failure must not launch") }
        withShow { token ->
            val caught = runCatching { runner.runOrLaunch(false, token) { throw failure } }.exceptionOrNull()
            assertSame(failure, caught)
            assertEquals(1, token.notificationGeneration.references)
        }
    }

    /** Exercises the launch-time exception path while the initial owner is still active. */
    private suspend fun assertLaunchFailure(
        failure: RuntimeException,
        propagates: Boolean,
    ) {
        val runner = NotificationEnrichmentRunner { throw failure }
        withShow { token ->
            val caught =
                runCatching {
                    runner.runOrLaunch(true, token) { error("rejected launch ran") }
                }.exceptionOrNull()
            assertSame(if (propagates) failure else null, caught)
            assertEquals(1, token.notificationGeneration.references)
            assertTrue(ConversationCardPostSynchronizer.isShowCurrent(token))
        }
    }

    /** Holds a separate real reference so an erroneous second release is observable before final cleanup. */
    private suspend fun assertSynchronousLaunchFailure(
        failure: RuntimeException,
        propagates: Boolean,
    ) {
        val runner =
            NotificationEnrichmentRunner { block ->
                runBlocking { block() }
                throw failure
            }
        withShow { token ->
            assertTrue(ConversationCardPostSynchronizer.retainShow(token))
            try {
                val caught = runCatching { runner.runOrLaunch(true, token) {} }.exceptionOrNull()
                assertSame(if (propagates) failure else null, caught)
                assertEquals(2, token.notificationGeneration.references)
                assertTrue(ConversationCardPostSynchronizer.isShowCurrent(token))
            } finally {
                ConversationCardPostSynchronizer.releaseShow(token)
            }
            assertEquals(1, token.notificationGeneration.references)
        }
    }

    /** Gives each case an isolated production registration whose initial reference always retires. */
    private suspend fun withShow(block: suspend (ConversationCardShowToken) -> Unit) {
        ConversationCardPostSynchronizer.withRegisteredShow(
            "enrichment-${UUID.randomUUID()}",
            0,
            ConversationCardScope("enrichment-account", "enrichment-group"),
            block,
        )
    }
}
