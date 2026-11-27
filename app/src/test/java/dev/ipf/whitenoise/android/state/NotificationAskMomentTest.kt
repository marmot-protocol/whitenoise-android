package dev.ipf.whitenoise.android.state

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.appModuleFile
import dev.ipf.whitenoise.android.notifications.BackgroundConnectionPreferences
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The notification permission is asked for only when a reply is expected (#4278), and never once it was attempted or
 * granted. Each fixture is a fresh process-scoped app state over the same persisted preferences.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NotificationAskMomentTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val fixtures = mutableListOf<NotificationBootstrapTestFixture>()

    /** A new install: no preferences, Android permission not yet granted, background connection on. */
    @Before
    fun freshInstall() {
        app
            .getSharedPreferences("whitenoise", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        BackgroundConnectionPreferences.setEnabled(app, true)
    }

    @After
    fun closeFixtures() {
        fixtures.forEach(NotificationBootstrapTestFixture::close)
    }

    /** Signing up and reaching Chats in the first process does not ask. */
    @Test
    fun firstReadyWithoutTriggerDoesNotAsk() {
        val state = readyProcess()
        state.notificationAskMoment.noteReady()

        assertFalse(state.notificationAskMoment.reached)
        assertFalse(state.shouldRequestDefaultNotificationPermission())
    }

    /** Opening or creating a conversation asks in the same process. */
    @Test
    fun openingAConversationAsks() {
        val state = readyProcess()
        state.notificationAskMoment.noteReady()

        state.noteNotificationMoment()

        assertTrue(state.shouldRequestDefaultNotificationPermission())
    }

    /** Sharing an invite or the profile asks in the same process. */
    @Test
    fun sharingAnInviteOrProfileAsks() {
        val state = readyProcess()

        state.noteNotificationMoment()

        assertTrue(state.shouldRequestDefaultNotificationPermission())
    }

    /** Someone who only waits for a message is asked on the next process start. */
    @Test
    fun laterProcessStartAsksWithoutTrigger() {
        readyProcess().notificationAskMoment.noteReady()

        val later = readyProcess()

        assertTrue(later.notificationAskMoment.reached)
        assertTrue(later.shouldRequestDefaultNotificationPermission())
    }

    /** A process whose shell never got past its startup guards does not make the next start a moment. */
    @Test
    fun unrecordedReadyDoesNotMakeTheNextStartAMoment() {
        readyProcess()

        assertFalse(readyProcess().shouldRequestDefaultNotificationPermission())
    }

    /** Not now and a system denial both record the attempt, which no later trigger or process start overrides. */
    @Test
    fun attemptedIsNeverAskedAgain() {
        val state = readyProcess()
        state.notificationAskMoment.noteReady()
        state.noteNotificationMoment()
        state.markDefaultNotificationPermissionPromptLaunched()
        assertFalse("an explanation in flight is not shown twice", state.shouldRequestDefaultNotificationPermission())

        state.markDefaultNotificationsEnableAttempted()
        state.noteNotificationMoment()

        assertFalse(state.shouldRequestDefaultNotificationPermission())
        assertFalse(readyProcess().shouldRequestDefaultNotificationPermission())
    }

    /** A granted permission (Android 12 and below, or granted elsewhere) never asks. */
    @Test
    fun grantedIsNeverAsked() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val state = readyProcess()
        state.notificationAskMoment.noteReady()
        state.refreshLocalNotificationPermission()

        state.noteNotificationMoment()

        assertFalse(state.shouldRequestDefaultNotificationPermission())
        assertFalse(readyProcess().shouldRequestDefaultNotificationPermission())
    }

    /** Each reply-expected moment in the UI reports itself; the shell turns it into the explanation. */
    @Test
    fun replyExpectedMomentsReportThemselves() {
        val sites =
            mapOf(
                "ui/conversation/ConversationScope.kt" to 1,
                "ui/chats/newchat/NewChatFlow.kt" to 1,
                "ui/profile/ProfileSheet.kt" to 1,
                "ui/settings/ShareConnectScreen.kt" to 3,
            )
        sites.forEach { (path, expected) ->
            val source = appModuleFile("src/main/java/dev/ipf/whitenoise/android/$path").readText()
            val calls = Regex("""appState\.noteNotificationMoment\(\)""").findAll(source).count()
            assertTrue("$path must report $expected reply-expected moment(s), found $calls", calls == expected)
        }
    }

    /** Starts a new process-scoped app state with one ready account. */
    private fun readyProcess(): WhiteNoiseAppState {
        val fixture =
            NotificationBootstrapTestFixture(
                context = app,
                accounts = listOf(AccountSummaryFfi(ACCOUNT, "a".repeat(64), true, false, false, true, false)),
                emitStartupNotification = false,
            )
        fixtures += fixture
        runBlocking { fixture.bootstrap() }
        fixture.appState.refreshLocalNotificationPermission()
        assertTrue(fixture.appState.activeAccountRef != null)
        return fixture.appState
    }

    private companion object {
        const val ACCOUNT = "notification-ask"
    }
}
