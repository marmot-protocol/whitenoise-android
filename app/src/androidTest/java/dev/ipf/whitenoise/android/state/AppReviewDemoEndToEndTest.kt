package dev.ipf.whitenoise.android.state

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.whitenoise.android.MainActivity
import dev.ipf.whitenoise.android.WhiteNoiseApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in relay test on a disposable preview package with one prepared local-signing account. */
@RunWith(AndroidJUnit4::class)
class AppReviewDemoEndToEndTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun realInviteAcceptAndBidirectionalDelivery() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("reviewDemoE2e") == "true")
        val suffix = requireNotNull(arguments.getString("reviewDemoE2ePackageSuffix"))
        check(Regex("\\.preview\\.pr(?:[1-9][0-9]*|local)").matches(suffix))
        val app = (composeRule.activity.application as WhiteNoiseApplication).appState
        check(
            InstrumentationRegistry.getInstrumentation().targetContext.packageName ==
                "dev.ipf.whitenoise.android$suffix",
        )
        composeRule.waitUntil(30_000) { app.phase == AppPhase.Ready && app.appReviewDemo.canBegin }
        val original = requireNotNull(app.activeAccountRef)
        val accountsBefore = runBlocking { AppReviewDemoNative(app).accounts().map { it.ref }.toSet() }
        check(accountsBefore.size == 1 && !app.appReviewDemo.hasSavedSetup) {
            "Use a fresh disposable preview package with one local account"
        }

        composeRule.activity.runOnUiThread { app.appReviewDemo.start() }
        composeRule.waitUntil(360_000) {
            app.appReviewDemo.status is ReviewDemoStatus.Ready || app.appReviewDemo.status is ReviewDemoStatus.Failed
        }
        val ready =
            app.appReviewDemo.status as? ReviewDemoStatus.Ready
                ?: error(
                    "Demo failed: ${app.appReviewDemo.status}\n${app.appReviewDemo.debugFailure?.stackTraceToString()}",
                )
        assertEquals(original, app.activeAccountRef)
        assertEquals(original, ready.accountRef)

        val backend = AppReviewDemoNative(app)
        val accountsAfter = runBlocking { backend.accounts() }
        val newAccounts = accountsAfter.filter { it.ref !in accountsBefore }
        assertEquals(1, newAccounts.size)
        val johnny = newAccounts.single()
        val originalTimeline = runBlocking { backend.timeline(original, ready.groupId) }
        val demoTimeline = runBlocking { backend.timeline(johnny.ref, ready.groupId) }
        // Client tokens are sender-local idempotency metadata; received rows carry the same event ID.
        val tokenizedSends =
            (originalTimeline + demoTimeline).filter { it.token?.startsWith("review-demo:") == true }
        val sentIds = tokenizedSends.map { it.id }.toSet()
        assertEquals(5, sentIds.size)
        assertEquals(5, tokenizedSends.mapNotNull { it.token }.toSet().size)
        assertEquals(sentIds, originalTimeline.map { it.id }.toSet())
        assertEquals(sentIds, demoTimeline.map { it.id }.toSet())
        val originalById = originalTimeline.associateBy { it.id }
        val demoById = demoTimeline.associateBy { it.id }
        assertTrue(originalById.values.any { it.sender == johnny.id && it.replyTo != null })
        assertTru...[truncated]    }
}
