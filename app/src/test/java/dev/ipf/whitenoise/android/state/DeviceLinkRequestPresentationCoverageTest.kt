package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.appModuleFile
import dev.ipf.whitenoise.android.appModuleSource
import dev.ipf.whitenoise.android.functionBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * A link request reaches the screen only because the user tapped its chat-list notice, and never while the shell is
 * blocked. Someone holding the key decides when requests arrive, so nothing they send may open a dialog by itself.
 */
class DeviceLinkRequestPresentationCoverageTest {
    /**
     * Whatever MDK lists, and whatever the user puts off or closes, nothing is presented until an unblocked open; and
     * an open while blocked presents nothing either.
     */
    @Test
    fun onlyAnUnblockedOpenPresentsARequest() {
        val random = Random(4111)
        repeat(RUNS) {
            var queue = DeviceLinkRequestQueue()
            repeat(STEPS) {
                val requests = randomRequests(random)
                queue =
                    when (random.nextInt(4)) {
                        0 -> queue.offer(requests)
                        1 -> requests.randomOrNull(random)?.let(queue::dismiss) ?: queue
                        2 -> queue.close()
                        else -> requests.randomOrNull(random)?.let { queue.open(it, blocked = true) } ?: queue
                    }
                assertNull(queue.presented)
            }
            val notice = queue.offer(listOf(DeviceLinkRequest("a", "open", 1))).notice!!
            assertEquals(notice, queue.offer(listOf(notice)).open(notice, blocked = false).presented)
        }
    }

    /** The queue is opened only by the watcher's open, and the watcher's open only from the notice's Review tap. */
    @Test
    fun onlyTheNoticesReviewOpensARequest() {
        val queueOpeners = mainSourcesCalling(Regex("""\bqueue\.open\("""))
        val watcherOpeners = mainSourcesCalling(Regex("""\b(watcher|deviceLinkRequests)\.open\("""))

        assertEquals(setOf(REQUESTS), queueOpeners)
        assertEquals(setOf(NOTICE), watcherOpeners)
        val watcher = appModuleSource(REQUESTS).substringAfter("class DeviceLinkRequestWatcher(", missingDelimiterValue = "")
        assertTrue(watcher.functionBody("open").contains("queue = queue.open(request, blocked)"))
        val notice = appModuleSource(NOTICE).functionBody("DeviceLinkRequestNotice")
        assertTrue(notice.contains("onReview = { watcher.open(request) }"))
    }

    /** The main source files whose text matches [call], relative to the app module. */
    private fun mainSourcesCalling(call: Regex): Set<String> {
        val root = appModuleFile("src/main/java")
        val module = root.parentFile.parentFile.parentFile
        return root
            .walk()
            .filter { it.extension == "kt" && call.containsMatchIn(it.readText()) }
            .map { it.relativeTo(module).invariantSeparatorsPath }
            .toSet()
    }

    /** Up to three requests on two profiles, open or settled, some repeated. */
    private fun randomRequests(random: Random): List<DeviceLinkRequest> =
        List(random.nextInt(4)) { index ->
            DeviceLinkRequest(
                accountRef = if (random.nextBoolean()) "a" else "b",
                promptId = "p$index",
                requestedAtSeconds = index.toLong(),
                open = random.nextInt(4) != 0,
                comparing = random.nextBoolean(),
                repeated = random.nextBoolean(),
            )
        }

    private companion object {
        const val RUNS = 200
        const val STEPS = 20
        const val REQUESTS = "src/main/java/dev/ipf/whitenoise/android/state/DeviceLinkRequests.kt"
        const val NOTICE = "src/main/java/dev/ipf/whitenoise/android/ui/devices/DeviceLinkRequestNotice.kt"
    }
}
