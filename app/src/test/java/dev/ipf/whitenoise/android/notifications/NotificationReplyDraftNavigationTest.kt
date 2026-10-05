package dev.ipf.whitenoise.android.notifications

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 36])
class NotificationReplyDraftNavigationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val preferences = context.getSharedPreferences(UUID.randomUUID().toString(), Context.MODE_PRIVATE)
    private val tokens = NotificationTapTokens(preferences)
    private val target = NotificationTarget("account-b", "group-b", "message-b", NotificationTargetKind.MESSAGE)

    @Test
    fun systemAddedUnsentTextIsTransferredWithoutTrimmingOrSending() {
        val intent = boundIntent().putExtra(Notification.EXTRA_REMOTE_INPUT_DRAFT, " unfinished\nreply ")
        val parsed = parse(intent)
        assertEquals(target.copy(replyDraft = parsed?.replyDraft), parsed)
        assertEquals(" unfinished\nreply ", parsed?.replyDraft?.text)
        assertTrue(parsed?.replyDraft.toString().contains("redacted"))
    }

    @Test
    fun signatureRejectsReplacedAccountChatMessageAndKind() {
        listOf(
            target.copy(accountRef = "account-a"),
            target.copy(groupIdHex = "group-a"),
            target.copy(messageIdHex = "message-a"),
            target.copy(kind = NotificationTargetKind.INVITE),
        ).forEach { replacement ->
            val intent = boundIntent()
            NotificationNavigation.applyTargetExtras(intent, replacement)
            assertNull(parse(intent))
        }
    }

    @Test
    fun recreatedTokenStoreVerifiesRouteAndRemovedTokensCannotReplay() {
        val intent = boundIntent()
        val recreated = NotificationTapTokens(preferences)
        assertEquals(target, NotificationNavigation.parse(intent, isTrustedTargetSignature = recreated::isValidTarget))
        tokens.remove(TAG)
        assertNull(parse(intent))
    }

    @Test
    fun legacyImmutableTapKeepsNavigatingButCannotImportInjectedText() {
        val intent = Intent(context, MainActivity::class.java)
        NotificationNavigation.applyToIntent(intent, target, TAG, tokens.tokenFor(TAG))
        intent.putExtra(Notification.EXTRA_REMOTE_INPUT_DRAFT, "injected")
        assertEquals(target, parse(intent))
    }

    @Test
    fun blankOversizedAndNonMessageDraftsAreNotImported() {
        assertNull(parse(boundIntent().putExtra(Notification.EXTRA_REMOTE_INPUT_DRAFT, "  "))?.replyDraft)
        assertNull(parse(boundIntent().putExtra(Notification.EXTRA_REMOTE_INPUT_DRAFT, "x".repeat(65_537)))?.replyDraft)
        val invite = target.copy(kind = NotificationTargetKind.INVITE)
        assertNull(parse(boundIntent(invite).putExtra(Notification.EXTRA_REMOTE_INPUT_DRAFT, "reply"))?.replyDraft)
    }

    @Test
    fun mutableExplicitTapAcceptsSystemFillInButCannotChangeItsDestination() {
        val intent = boundIntent()
        val pending =
            PendingIntent.getActivity(
                context,
                42,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
        pending.send(context, 0, Intent().putExtra(Notification.EXTRA_REMOTE_INPUT_DRAFT, "half typed"))
        val launched = shadowOf(context as android.app.Application).nextStartedActivity
        assertEquals(MainActivity::class.java.name, launched.component?.className)
        assertEquals("half typed", parse(launched)?.replyDraft?.text)
        assertNotNull(parse(launched))
    }

    @Test
    fun historyAndSavedStateRestoresCannotReimportAnAlreadyHandledReply() {
        val history =
            boundIntent()
                .putExtra(Notification.EXTRA_REMOTE_INPUT_DRAFT, "already handled")
                .addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
        assertNull(parse(history)?.replyDraft)
        val restore = boundIntent().putExtra(Notification.EXTRA_REMOTE_INPUT_DRAFT, "already handled")
        assertNull(
            NotificationNavigation
                .parse(
                    restore,
                    importReplyDraft = false,
                    isTrustedTargetSignature = tokens::isValidTarget,
                )?.replyDraft,
        )
    }

    private fun boundIntent(destination: NotificationTarget = target): Intent =
        Intent(context, MainActivity::class.java).also {
            NotificationNavigation.applyBoundToIntent(
                it,
                destination,
                TAG,
                checkNotNull(tokens.signatureFor(TAG, destination)),
            )
        }

    private fun parse(intent: Intent): NotificationTarget? =
        NotificationNavigation.parse(
            intent,
            isTrustedTargetSignature = tokens::isValidTarget,
            isTrustedTapToken = tokens::isValid,
        )

    private companion object {
        const val TAG = "message-card"
    }
}
