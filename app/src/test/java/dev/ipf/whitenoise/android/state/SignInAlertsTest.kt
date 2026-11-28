package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.UnrecognizedSignInStateFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The app-wide sign-in alerts publish MDK's state, announce each thing once and keep only this device's choices. */
class SignInAlertsTest {
    /** Records every notification call. */
    private class Recorder : SignInAlertNotifications {
        val calls = mutableListOf<String>()
        val activeReviews = mutableMapOf<String, Int>()
        var canPost = true

        override fun shownReviewCount(accountRef: String): Int = activeReviews[accountRef] ?: 0

        override fun postReview(
            accountRef: String,
            count: Int,
            alert: Boolean,
        ): Boolean {
            if (!canPost) return false
            calls += "review:$accountRef:$count:$alert"
            activeReviews[accountRef] = count
            return true
        }

        override fun withdrawReview(accountRef: String) {
            calls += "withdraw-review:$accountRef"
            activeReviews.remove(accountRef)
        }

        override fun postCompromise(accountRef: String): Boolean {
            if (!canPost) return false
            calls += "compromise:$accountRef"
            return true
        }

        override fun withdrawCompromise(accountRef: String) {
            calls += "withdraw-compromise:$accountRef"
        }
    }

    /** Keeps records in memory. */
    private class Store : SignInAlertStore {
        val records = mutableMapOf<String, SignInAlertRecord>()

        override fun read(accountIdHex: String) = records[accountIdHex] ?: SignInAlertRecord()

        override fun write(
            accountIdHex: String,
            record: SignInAlertRecord,
        ) {
            records[accountIdHex] = record
        }
    }

    private val recorder = Recorder()
    private val store = Store()
    private val alerts = SignInAlerts(store, recorder)

    /** A new sign-in posts one alerting notification and shows the review tier; reading it again posts nothing. */
    @Test
    fun aNewSignInNotifiesOnceAndShowsReview() {
        alerts.publish(ACCOUNT, ID, listOf(alertSignIn("a")), emptyList())
        alerts.publish(ACCOUNT, ID, listOf(alertSignIn("a")), emptyList())

        assertEquals(listOf("review:$ACCOUNT:1:true", "withdraw-compromise:$ACCOUNT"), recorder.calls.distinct())
        assertEquals(1, recorder.calls.count { it.startsWith("review:") })
        assertEquals(SignInAlertTier.Review, alerts.snapshot(ACCOUNT)?.tier)
    }

    /** What was announced survives a restart, so the same sign-in is not announced again by a new process. */
    @Test
    fun announcementsSurviveARestart() {
        alerts.publish(ACCOUNT, ID, listOf(alertSignIn("a")), emptyList())
        val restarted = Recorder()

        SignInAlerts(store, restarted).publish(ACCOUNT, ID, listOf(alertSignIn("a")), emptyList())

        assertTrue(restarted.calls.none { it.startsWith("review:") })
    }

    @Test
    fun aRestartUpdatesTheCountOnlyWhileTheReviewIsStillShown() {
        val signIns = listOf(alertSignIn("a"), alertSignIn("b"), alertSignIn("c"))
        alerts.publish(ACCOUNT, ID, signIns, emptyList())
        recorder.calls.clear()

        val restarted = SignInAlerts(store, recorder)
        restarted.publish(ACCOUNT, ID, signIns.take(2), emptyList())
        recorder.activeReviews.clear()
        restarted.publish(ACCOUNT, ID, signIns.take(1), emptyList())

        assertEquals(listOf("review:$ACCOUNT:2:false"), recorder.calls.filter { it.startsWith("review:") })
    }

    @Test
    fun deniedNotificationsAreRetriedAfterPermissionIsGranted() {
        recorder.canPost = false
        val signIns = listOf(alertSignIn("a"))
        val warnings = listOf(alertWarning("b"))
        alerts.publish(ACCOUNT, ID, signIns, warnings)

        recorder.canPost = true
        alerts.publish(ACCOUNT, ID, signIns, warnings)

        assertEquals(listOf("review:$ACCOUNT:1:true", "compromise:$ACCOUNT"), recorder.calls)
    }

    /** Mine, don't link takes the sign-in out of review at once and withdraws its notification; it is kept. */
    @Test
    fun mineEndsTheReviewAndWithdraws() {
        alerts.publish(ACCOUNT, ID, listOf(alertSignIn("a")), emptyList())
        recorder.calls.clear()

        alerts.markMine(ACCOUNT, ID, "a")

        assertEquals(SignInAlertTier.Passive, alerts.snapshot(ACCOUNT)?.tier)
        assertTrue("withdraw-review:$ACCOUNT" in recorder.calls)
        assertEquals(setOf("a"), alerts.mine(ID))
    }

    /** A sign-in marked Mine, don't link before it was read never notifies. */
    @Test
    fun aSignInMarkedMineNeverNotifies() {
        alerts.markMine(ACCOUNT, ID, "a")

        alerts.publish(ACCOUNT, ID, listOf(alertSignIn("a")), emptyList())

        assertTrue(recorder.calls.none { it.startsWith("review:") })
        assertEquals(SignInAlertTier.Passive, alerts.snapshot(ACCOUNT)?.tier)
    }

    /** Answering Not now in MDK ends the review and withdraws the notification. */
    @Test
    fun aSignInSetAsideWithdraws() {
        alerts.publish(ACCOUNT, ID, listOf(alertSignIn("a")), emptyList())
        recorder.calls.clear()

        alerts.publish(ACCOUNT, ID, listOf(alertSignIn("a", state = UnrecognizedSignInStateFfi.NOT_NOW)), emptyList())

        assertTrue("withdraw-review:$ACCOUNT" in recorder.calls)
    }

    /** A warning item notifies once and is due for the full-screen alert until the alert has shown it. */
    @Test
    fun aWarningNotifiesOnceAndAlertsOnce() {
        alerts.publish(ACCOUNT, ID, emptyList(), listOf(alertWarning("a")))
        alerts.publish(ACCOUNT, ID, emptyList(), listOf(alertWarning("a")))

        assertEquals(1, recorder.calls.count { it == "compromise:$ACCOUNT" })
        assertEquals(SignInAlertTier.Compromise, alerts.snapshot(ACCOUNT)?.tier)
        assertEquals(1, alerts.snapshot(ACCOUNT)?.alertDue?.size)

        alerts.alertShown(ACCOUNT, listOf(alertWarning("a")))

        assertEquals(0, alerts.snapshot(ACCOUNT)?.alertDue?.size)
        // The red card stays until the warning is hidden.
        assertEquals(SignInAlertTier.Compromise, alerts.snapshot(ACCOUNT)?.tier)
    }

    /** A new reason opens the alert again. */
    @Test
    fun aNewWarningItemIsDueAgain() {
        alerts.publish(ACCOUNT, ID, emptyList(), listOf(alertWarning("a")))
        alerts.publish(ACCOUNT, ID, emptyList(), listOf(alertWarning("a"), alertWarning("b")))
        alerts.alertShown(ACCOUNT, listOf(alertWarning("a")))

        assertEquals(listOf("b"), alerts.snapshot(ACCOUNT)?.alertDue?.map { it.slotIdHex })
        assertEquals(2, recorder.calls.count { it == "compromise:$ACCOUNT" })
    }

    /** Hiding the warning in MDK leaves nothing open, which withdraws the notification. */
    @Test
    fun aHiddenWarningWithdraws() {
        alerts.publish(ACCOUNT, ID, emptyList(), listOf(alertWarning("a")))
        recorder.calls.clear()

        alerts.publish(ACCOUNT, ID, emptyList(), emptyList())

        assertTrue("withdraw-compromise:$ACCOUNT" in recorder.calls)
        assertEquals(SignInAlertTier.Passive, alerts.snapshot(ACCOUNT)?.tier)
    }

    /** Accounts are kept apart: one account's sign-in neither notifies for nor shows on another. */
    @Test
    fun accountsAreKeptApart() {
        alerts.publish(ACCOUNT, ID, listOf(alertSignIn("a")), emptyList())
        alerts.publish("other", "other-id", emptyList(), emptyList())

        assertEquals(SignInAlertTier.Passive, alerts.snapshot("other")?.tier)
        assertTrue(recorder.calls.none { it.startsWith("review:other") })
    }

    /** Forgetting an account drops its snapshot and withdraws both notifications. */
    @Test
    fun forgettingWithdrawsBoth() {
        alerts.publish(ACCOUNT, ID, listOf(alertSignIn("a")), listOf(alertWarning("a")))
        recorder.calls.clear()

        alerts.forget(ACCOUNT)

        assertNull(alerts.snapshot(ACCOUNT))
        assertEquals(listOf("withdraw-review:$ACCOUNT", "withdraw-compromise:$ACCOUNT"), recorder.calls)
    }

    /** A Devices request ends only when that request is opened; a newer one stays. */
    @Test
    fun aDevicesRequestEndsWhenOpened() {
        alerts.requestDevices(ACCOUNT)
        val first = alerts.devicesRequest!!
        alerts.requestDevices(ACCOUNT)
        val second = alerts.devicesRequest!!

        alerts.updateDevicesRequest(first, first.copy(accountOpened = true))
        assertEquals(second, alerts.devicesRequest)

        alerts.updateDevicesRequest(second, second.copy(accountOpened = true))
        val opened = alerts.devicesRequest!!
        assertTrue(opened.accountOpened)
        alerts.updateDevicesRequest(first)
        assertEquals(opened, alerts.devicesRequest)

        alerts.updateDevicesRequest(opened)
        assertNull(alerts.devicesRequest)
    }

    @Test
    fun linkReportCanWaitForUnlockButCannotOverrideNewerNavigation() {
        alerts.requestDevicesAfterLinkDecline(ACCOUNT, runtimeGeneration = 2)
        val queued = checkNotNull(alerts.devicesRequest)
        assertFalse(queued.isStaleLinkReport(ACCOUNT, runtimeGeneration = 2))
        assertTrue(queued.isStaleLinkReport("other", runtimeGeneration = 2))
        assertTrue(queued.isStaleLinkReport(ACCOUNT, runtimeGeneration = 3))

        alerts.updateDevicesRequest(queued, queued.copy(accountOpened = true))
        assertEquals(2, alerts.devicesRequest?.linkReportGeneration)

        alerts.requestDevices("other")
        val explicit = alerts.devicesRequest
        alerts.requestDevicesAfterLinkDecline(ACCOUNT, runtimeGeneration = 2)
        assertEquals(explicit, alerts.devicesRequest)
    }

    private companion object {
        const val ACCOUNT = "account"
        const val ID = "account-id"
    }
}
