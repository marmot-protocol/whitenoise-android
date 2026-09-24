package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class NotificationNicknameRefreshCoordinatorTest {
    /** A delayed older edit cannot overwrite the notification label produced by a newer edit. */
    @Test
    fun newestNicknameEditOwnsNotificationRefresh() =
        runTest {
            val firstResolutionStarted = CompletableDeferred<Unit>()
            val allowFirstResolution = CompletableDeferred<Unit>()
            val newerRefreshFinished = CompletableDeferred<Unit>()
            val olderRefreshFinished = CompletableDeferred<Unit>()
            val resolutionCalls = AtomicInteger()
            val publishedNames = mutableListOf<String>()
            val coordinator =
                NotificationNicknameRefreshCoordinator(
                    scope = this,
                    resolveSenderName = { _, _ ->
                        if (resolutionCalls.incrementAndGet() == 1) {
                            firstResolutionStarted.complete(Unit)
                            allowFirstResolution.await()
                            "Older"
                        } else {
                            "Newer"
                        }
                    },
                    refreshSenderName = { _, _, senderName, isCurrent ->
                        if (isCurrent()) publishedNames += senderName
                        if (senderName == "Newer") newerRefreshFinished.complete(Unit)
                        if (senderName == "Older") olderRefreshFinished.complete(Unit)
                    },
                )

            coordinator.refresh("account-a", "contact-a")
            firstResolutionStarted.await()
            coordinator.refresh("account-a", "contact-a")
            newerRefreshFinished.await()
            allowFirstResolution.complete(Unit)
            olderRefreshFinished.await()

            assertEquals(listOf("Newer"), publishedNames)
        }
}
