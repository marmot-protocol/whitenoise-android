package dev.ipf.whitenoise.android.state

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.ChatNotificationSettingsFfi
import dev.ipf.marmotkit.NotificationUserFfi
import dev.ipf.whitenoise.android.notifications.LocalNotificationFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Exercises typed updates through AppState's cold, non-active-account production path. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MutedMentionNotificationPathTest {
    /** A cold non-active account resolves MDK mute and posts only the typed mention card. */
    @Test
    fun unloadedMutedConversationPostsDirectMentionButNotOrdinaryMessage() =
        runBlocking {
            val context: Application = RuntimeEnvironment.getApplication()
            shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.cancelAll()
            val queried = mutableListOf<Pair<String, String>>()
            val fixture = mutedFixture(context, queried)
            val ordinaryKey = LocalNotificationFormatter.conversationDismissalKey("account-b", "group-a")
            val mentionKey = LocalNotificationFormatter.mentionDismissalKey("account-b", "group-a")
            try {
                fixture.bootstrap()
                val ordinary =
                    fixture.update.copy(
                        notificationKey = "message:account-b:ordinary",
                        conversationKey = "conversation:account-b:group-a",
                        accountRef = "account-b",
                        accountIdHex = "account-b",
                        receiver = NotificationUserFfi("account-b", "Bob", null),
                        messageIdHex = "ordinary",
                        isMention = false,
                    )
                fixture.runWithMainLooperPumping {
                    fixture.appState.processNotificationUpdateForTest(ordinary)
                }
                assertEquals(
                    0,
                    manager.activeNotifications.count { it.tag == ordinaryKey.tag || it.tag == mentionKey.tag },
                )

                val mention =
                    ordinary.copy(
                        notificationKey = "message:account-b:mention",
                        messageIdHex = "mention",
                        isMention = true,
                    )
                fixture.runWithMainLooperPumping {
                    fixture.appState.processNotificationUpdateForTest(mention)
                }
                val posted =
                    fixture.runWithMainLooperPumping {
                        withTimeoutOrNull(3_000L) {
                            while (manager.activeNotifications.none { it.tag == mentionKey.tag }) delay(10L)
                            true
                        }
                    }
                assertTrue(
                    "queries=$queried active=${manager.activeNotifications.map { it.tag }} expected=${mentionKey.tag}",
                    posted == true,
                )
                assertEquals(1, manager.activeNotifications.count { it.tag == mentionKey.tag })
                assertEquals(0, manager.activeNotifications.count { it.tag == ordinaryKey.tag })
                assertTrue(queried.contains("account-b" to "group-a"))
            } finally {
                fixture.close()
                manager.cancelAll()
            }
        }

    /** Keeps account B unloaded while returning MDK-owned durable mute for its chat. */
    private fun mutedFixture(
        context: Application,
        queried: MutableList<Pair<String, String>>,
    ) = NotificationBootstrapTestFixture(
        context = context,
        emitStartupNotification = false,
        accounts = listOf(account("account-a"), account("account-b")),
        onChatNotificationSettings = { accountRef, groupIdHex ->
            queried += accountRef to groupIdHex
            ChatNotificationSettingsFfi(
                accountRef = accountRef,
                accountIdHex = accountRef,
                groupIdHex = groupIdHex,
                muted = true,
                mutedUntilMs = null,
                updatedAtMs = 0L,
            )
        },
    )

    /** Builds one local account summary without activating the second account in the UI. */
    private fun account(label: String) =
        AccountSummaryFfi(
            label = label,
            accountIdHex = label,
            localSigning = true,
            externalSigning = false,
            signedOut = false,
            running = true,
        )
}
