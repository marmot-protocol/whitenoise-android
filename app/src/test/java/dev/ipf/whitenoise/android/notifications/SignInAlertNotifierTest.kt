package dev.ipf.whitenoise.android.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import dev.ipf.whitenoise.android.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Sign-in notifications keep the account and the sign-in's app off the locked screen and open the right account. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SignInAlertNotifierTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    private val manager: NotificationManager
        get() = context.getSystemService(NotificationManager::class.java)

    @Before
    fun setUp() {
        manager.cancelAll()
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @After
    fun tearDown() {
        manager.cancelAll()
    }

    /** The review notification is private, on the sign-in review channel, with a generic public version. */
    @Test
    fun theReviewNotificationIsPrivateWithAGenericPublicVersion() {
        SignInAlertNotifier(context).postReview(ACCOUNT, count = 2, alert = true)

        val posted = manager.activeNotifications.single()
        val notification = posted.notification
        assertEquals(NotificationChannelSpec.SIGN_IN_REVIEW.id, notification.channelId)
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        val public = notification.publicVersion
        assertNotNull(public)
        assertEquals(Notification.VISIBILITY_PUBLIC, public.visibility)
        assertEquals(context.getString(R.string.sign_in_alert_review_public), public.text())
        assertTrue(posted.tag.endsWith(ACCOUNT))
        assertNamesNothing(notification)
        assertNamesNothing(public)
    }

    /** The compromise notification carries the neutral public text and names nothing either. */
    @Test
    fun theCompromiseNotificationIsNeutralOnTheLockScreen() {
        SignInAlertNotifier(context).postCompromise(ACCOUNT)

        val notification = manager.activeNotifications.single().notification
        val public = notification.publicVersion
        assertEquals(context.getString(R.string.sign_in_alert_compromise_public), public.text())
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        assertNamesNothing(notification)
        assertNamesNothing(public)
    }

    /** Withdrawing takes down only that account's notification of that kind. */
    @Test
    fun withdrawingIsPerAccountAndKind() {
        val notifier = SignInAlertNotifier(context)
        notifier.postReview(ACCOUNT, count = 1, alert = true)
        notifier.postCompromise(ACCOUNT)
        notifier.postReview("other", count = 1, alert = true)

        notifier.withdrawReview(ACCOUNT)

        val left = manager.activeNotifications.map { it.tag }.toSet()
        assertEquals(
            setOf(
                SignInAlertNotifier.tag(SignInAlertNotifier.COMPROMISE_TAG, ACCOUNT),
                SignInAlertNotifier.tag(SignInAlertNotifier.REVIEW_TAG, "other"),
            ),
            left,
        )
    }

    /** Without the notification permission nothing is posted. */
    @Test
    fun nothingIsPostedWithoutPermission() {
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        SignInAlertNotifier(context).postCompromise(ACCOUNT)

        assertTrue(manager.activeNotifications.isEmpty())
    }

    /** A tap names its account; any other intent is not a sign-in tap. */
    @Test
    fun aTapNamesItsAccount() {
        val tokens = NotificationTapTokens.create(context)
        val id = SignInAlertNotifier.REVIEW_ID
        val intent = SignInAlertNavigation.intent(context, ACCOUNT, id)
        assertEquals(ACCOUNT, SignInAlertNavigation.accountRef(intent, tokens))
        assertNull(SignInAlertNavigation.accountRef(Intent(SignInAlertNavigation.ACTION_OPEN), tokens))
        assertNull(SignInAlertNavigation.accountRef(Intent(Intent.ACTION_VIEW), tokens))
        assertNull(SignInAlertNavigation.accountRef(null, tokens))
        tokens.remove(SignInAlertNavigation.tapKey(ACCOUNT, id))
        assertNull(SignInAlertNavigation.accountRef(intent, tokens))
    }

    /** Neither the account nor any sign-in's app name appears anywhere in [notification]'s text. */
    private fun assertNamesNothing(notification: Notification) {
        val extras = notification.extras
        val texts =
            listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT, Notification.EXTRA_SUB_TEXT)
                .mapNotNull { extras.getCharSequence(it)?.toString() }
        assertTrue(texts.isNotEmpty())
        texts.forEach { assertFalse(it, it.contains(ACCOUNT)) }
    }

    private fun Notification.text() = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    private companion object {
        const val ACCOUNT = "alice-account"
    }
}
