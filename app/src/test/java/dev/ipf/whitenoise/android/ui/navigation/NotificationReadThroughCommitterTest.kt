package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NotificationReadThroughCommitterTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun routeReplacementBeforeProjectionCaptureCommitsOutgoingReadThrough() {
        val route = mutableIntStateOf(1)
        val committed = mutableListOf<NotificationReadThroughTarget>()
        val first = target("a")
        val second = target("b")

        composeRule.setContent {
            val committer =
                remember(route.intValue) {
                    NotificationReadThroughCommitter(if (route.intValue == 1) first else second)
                }
            NotificationReadThroughCommitOnDispose(committer, committed::add)
        }

        composeRule.runOnIdle { route.intValue = 2 }
        composeRule.waitForIdle()

        assertEquals(listOf(first), committed)
    }

    @Test
    fun boundaryCommitAndLaterDisposalCommitExactlyOnce() {
        val route = mutableIntStateOf(1)
        val committed = mutableListOf<NotificationReadThroughTarget>()
        val first = target("a")
        lateinit var current: NotificationReadThroughCommitter

        composeRule.setContent {
            current =
                remember(route.intValue) {
                    NotificationReadThroughCommitter(if (route.intValue == 1) first else target("b"))
                }
            NotificationReadThroughCommitOnDispose(current, committed::add)
        }

        composeRule.runOnIdle { current.commit(committed::add) }
        composeRule.runOnIdle { route.intValue = 2 }
        composeRule.waitForIdle()

        assertEquals(listOf(first), committed)
    }

    @Test
    fun backgroundBeforeProjectionCaptureCommitsExactlyOnce() {
        val route = mutableIntStateOf(1)
        val committed = mutableListOf<NotificationReadThroughTarget>()
        val first = target("a")
        val lifecycleOwner = TestLifecycleOwner()

        composeRule.setContent {
            val committer =
                remember(route.intValue) {
                    NotificationReadThroughCommitter(if (route.intValue == 1) first else target("b"))
                }
            NotificationReadThroughCommitOnDispose(
                committer = committer,
                onCommit = committed::add,
                lifecycleOwner = lifecycleOwner,
            )
        }

        composeRule.runOnIdle {
            lifecycleOwner.handle(Lifecycle.Event.ON_START)
            lifecycleOwner.handle(Lifecycle.Event.ON_RESUME)
            lifecycleOwner.handle(Lifecycle.Event.ON_PAUSE)
            lifecycleOwner.handle(Lifecycle.Event.ON_STOP)
        }
        composeRule.runOnIdle { route.intValue = 2 }
        composeRule.waitForIdle()

        assertEquals(listOf(first), committed)
    }

    private fun target(suffix: String) =
        NotificationReadThroughTarget(
            accountRef = "account-$suffix",
            groupIdHex = "group-$suffix",
            messageIdHex = suffix.repeat(64),
        )

    private class TestLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)

        init {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        }

        override val lifecycle: Lifecycle = registry

        fun handle(event: Lifecycle.Event) {
            registry.handleLifecycleEvent(event)
        }
    }
}
