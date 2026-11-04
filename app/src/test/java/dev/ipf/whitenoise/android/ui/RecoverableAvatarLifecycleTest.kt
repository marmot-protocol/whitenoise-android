package dev.ipf.whitenoise.android.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.ipf.whitenoise.android.core.AvatarLoadRecovery
import dev.ipf.whitenoise.android.ui.common.rememberRecoverableAvatar
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.atomic.AtomicInteger

/** Exercises the production waiter independently of the loaders' negative caches. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class RecoverableAvatarLifecycleTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Only a new recovery revision, not elapsed time or STOP/START, retries a completed failure. */
    @Test
    fun failuresWaitForOneRecoveryEventWhileStarted() {
        lateinit var owner: AvatarLifecycleOwner
        val attempts = AtomicInteger()
        composeRule.runOnIdle { owner = AvatarLifecycleOwner() }
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                rememberRecoverableAvatar(null) {
                    attempts.incrementAndGet()
                    null
                }
            }
        }
        composeRule.waitForIdle()
        assertEquals(1, attempts.get())
        composeRule.mainClock.advanceTimeBy(120_000)
        composeRule.runOnIdle { owner.state = Lifecycle.State.CREATED }
        composeRule.runOnIdle { owner.state = Lifecycle.State.STARTED }
        composeRule.waitForIdle()
        assertEquals(1, attempts.get())

        composeRule.runOnIdle { owner.state = Lifecycle.State.CREATED }
        composeRule.runOnIdle { AvatarLoadRecovery.onNetworkRestored() }
        composeRule.waitForIdle()
        assertEquals(1, attempts.get())
        composeRule.runOnIdle { owner.state = Lifecycle.State.STARTED }
        composeRule.waitForIdle()
        assertEquals(2, attempts.get())
        composeRule.runOnIdle { AvatarLoadRecovery.onNetworkRestored() }
        composeRule.waitForIdle()
        assertEquals(3, attempts.get())
    }

    /** A recovery received during a suspended failed attempt is not accidentally consumed by it. */
    @Test
    fun recoveryDuringLoadIsNotLostAndSuccessfulImageStopsRetrying() {
        val attempts = AtomicInteger()
        val first = CompletableDeferred<ImageBitmap?>()
        val recovered = ImageBitmap(2, 2)
        composeRule.setContent {
            val image by rememberRecoverableAvatar(null) {
                if (attempts.incrementAndGet() == 1) first.await() else recovered
            }
            Text(if (image == null) "missing" else "loaded")
        }
        composeRule.waitForIdle()
        assertEquals(1, attempts.get())
        composeRule.runOnIdle {
            AvatarLoadRecovery.onNetworkRestored()
            first.complete(null)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("loaded").assertExists()
        assertEquals(2, attempts.get())
        composeRule.runOnIdle { AvatarLoadRecovery.onNetworkRestored() }
        composeRule.waitForIdle()
        assertEquals(2, attempts.get())
    }

    /** An off-screen composition owns no retry collector, even if its original request completes later. */
    @Test
    fun disposalCancelsTheWaiterAndIgnoresLaterRecovery() {
        val visible = mutableStateOf(true)
        val attempts = AtomicInteger()
        val pending = CompletableDeferred<ImageBitmap?>()
        composeRule.setContent {
            if (visible.value) {
                rememberRecoverableAvatar(null) {
                    attempts.incrementAndGet()
                    pending.await()
                }
            }
        }
        composeRule.waitForIdle()
        assertEquals(1, attempts.get())
        composeRule.runOnIdle { visible.value = false }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            pending.complete(null)
            AvatarLoadRecovery.onNetworkRestored()
        }
        composeRule.waitForIdle()
        assertEquals(1, attempts.get())
    }

    /** An interrupted visible wait is rejoined when the lifecycle becomes STARTED again. */
    @Test
    fun interruptedAttemptCanResumeWithoutANewRecoveryEvent() {
        lateinit var owner: AvatarLifecycleOwner
        val attempts = AtomicInteger()
        val pending = CompletableDeferred<ImageBitmap?>()
        composeRule.runOnIdle { owner = AvatarLifecycleOwner() }
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                val image by rememberRecoverableAvatar(null) {
                    attempts.incrementAndGet()
                    pending.await()
                }
                Text(if (image == null) "missing" else "loaded")
            }
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { owner.state = Lifecycle.State.CREATED }
        composeRule.runOnIdle { pending.complete(ImageBitmap(2, 2)) }
        composeRule.runOnIdle { owner.state = Lifecycle.State.STARTED }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("loaded").assertExists()
        assertEquals(2, attempts.get())
    }

    /** Cached pixels another consumer publishes are adopted while visible, with no new attempt or recovery (#3557). */
    @Test
    fun cachedPublicationIsAdoptedWithoutAnotherAttempt() {
        val attempts = AtomicInteger()
        val published = CompletableDeferred<ImageBitmap?>()
        val recompose = mutableStateOf(0)
        composeRule.setContent {
            val image by rememberRecoverableAvatar(null, awaitCached = { published.await() }) {
                attempts.incrementAndGet()
                null
            }
            Text(if (image == null) "missing ${recompose.value}" else "loaded ${recompose.value}")
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { recompose.value = 1 }
        composeRule.onNodeWithText("missing 1").assertExists()
        composeRule.runOnIdle { published.complete(ImageBitmap(2, 2)) }
        composeRule.waitForIdle()
        composeRule.runOnIdle { recompose.value = 2 }
        composeRule.onNodeWithText("loaded 2").assertExists()
        assertEquals(1, attempts.get())
        composeRule.runOnIdle { AvatarLoadRecovery.onNetworkRestored() }
        composeRule.waitForIdle()
        assertEquals(1, attempts.get())
    }

    /** A wait that ends without pixels (a retired cache lifetime) leaves the recovery policy in charge. */
    @Test
    fun emptyAdoptionKeepsWaitingForRecovery() {
        val attempts = AtomicInteger()
        composeRule.setContent {
            rememberRecoverableAvatar(null, awaitCached = { null }) {
                attempts.incrementAndGet()
                null
            }
        }
        composeRule.waitForIdle()
        assertEquals(1, attempts.get())
        composeRule.runOnIdle { AvatarLoadRecovery.onNetworkRestored() }
        composeRule.waitForIdle()
        assertEquals(2, attempts.get())
    }

    /** The wait is cancelled at STOP and re-registered at START, so a publication made while stopped is adopted. */
    @Test
    fun publicationWhileStoppedIsAdoptedOnStart() {
        lateinit var owner: AvatarLifecycleOwner
        val registrations = AtomicInteger()
        val cancellations = AtomicInteger()
        var cached: ImageBitmap? = null
        composeRule.runOnIdle { owner = AvatarLifecycleOwner() }
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                val image by rememberRecoverableAvatar(
                    null,
                    awaitCached = {
                        registrations.incrementAndGet()
                        cached ?: try {
                            awaitCancellation()
                        } finally {
                            cancellations.incrementAndGet()
                        }
                    },
                ) { null }
                Text(if (image == null) "missing" else "loaded")
            }
        }
        composeRule.waitForIdle()
        assertEquals(1, registrations.get())
        composeRule.runOnIdle { owner.state = Lifecycle.State.CREATED }
        composeRule.waitForIdle()
        assertEquals(1, cancellations.get())
        composeRule.runOnIdle { cached = ImageBitmap(2, 2) }
        composeRule.mainClock.advanceTimeBy(120_000)
        composeRule.onNodeWithText("missing").assertExists()
        composeRule.runOnIdle { owner.state = Lifecycle.State.STARTED }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("loaded").assertExists()
        assertEquals(2, registrations.get())
    }

    /** Disposing a placeholder cancels its cache wait, so no observer outlives the composition. */
    @Test
    fun disposalCancelsTheCacheWait() {
        val visible = mutableStateOf(true)
        val cancelled = CompletableDeferred<Unit>()
        composeRule.setContent {
            if (visible.value) {
                rememberRecoverableAvatar(
                    null,
                    awaitCached = {
                        try {
                            awaitCancellation()
                        } finally {
                            cancelled.complete(Unit)
                        }
                    },
                ) { null }
            }
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { visible.value = false }
        composeRule.waitForIdle()
        assertEquals(true, cancelled.isCompleted)
    }

    /** Gives each test explicit foreground/background control without replacing its composition. */
    private class AvatarLifecycleOwner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.STARTED }
        var state: Lifecycle.State
            get() = lifecycle.currentState
            set(value) {
                lifecycle.currentState = value
            }
    }
}
