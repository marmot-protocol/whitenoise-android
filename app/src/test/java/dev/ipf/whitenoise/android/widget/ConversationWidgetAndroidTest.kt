package dev.ipf.whitenoise.android.widget

import android.app.KeyguardManager
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.TextView
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.SecureStoreKeyProvider
import dev.ipf.whitenoise.android.ui.share.emptyAppState
import dev.ipf.whitenoise.android.ui.share.testAccount
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationWidgetAndroidTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before fun reset() {
        ConversationWidgetConfiguration.installKeyProvider(
            object : SecureStoreKeyProvider {
                override fun secretKey(): SecretKey = SecretKeySpec(ByteArray(32) { 7 }, "AES")
            },
        )
        context
            .getSharedPreferences("conversation_widget_choices.keystore", 0)
            .edit()
            .clear()
            .commit()
    }

    @After fun restoreKeystore() {
        ConversationWidgetConfiguration.installKeyProvider(null)
    }

    @Test fun configurationRecreationRetainsOnlyPlatformChoices() {
        val choice = ConversationWidgetChoice("account", unreadOnly = true)
        assertTrue(ConversationWidgetConfiguration(context).save(7, choice))
        assertEquals(choice, ConversationWidgetConfiguration(context).read(7))
        val persisted = context.getSharedPreferences("conversation_widget_choices.keystore", 0).all
        assertTrue(persisted.isNotEmpty())
        assertTrue(persisted.values.none { "account" in it.toString() })
        assertTrue(context.getSharedPreferences("conversation_widget_choices", 0).all.isEmpty())
    }

    @Test fun widgetDeletionRemovesAllConfiguration() {
        ConversationWidgetConfiguration(context).save(7, ConversationWidgetChoice("account", previews = true))
        ConversationWidgetProvider().onDeleted(context, intArrayOf(7))
        assertNull(ConversationWidgetConfiguration(context).read(7))
    }

    @Test fun independentInstancesPreserveConcurrentChoicesAndRemovals() {
        val executor = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        val original = ConversationWidgetConfiguration(context)
        (1..12).forEach { original.save(it, ConversationWidgetChoice("original-$it")) }
        try {
            val futures =
                (1..12).map { id ->
                    executor.submit {
                        check(start.await(10, TimeUnit.SECONDS))
                        val store = ConversationWidgetConfiguration(context)
                        check(store.save(id + 100, ConversationWidgetChoice("new-$id")))
                        store.remove(id)
                    }
                }
            start.countDown()
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
            val fresh = ConversationWidgetConfiguration(context)
            (1..12).forEach { id ->
                assertNull(fresh.read(id))
                assertEquals(ConversationWidgetChoice("new-$id"), fresh.read(id + 100))
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun unavailableWidgetContainsGenericCopyInsteadOfLastSensitiveRows() {
        val views = conversationWidgetViews(context, 7, emptyList(), null).apply(context, null)
        assertEquals(
            context.getString(R.string.widget_unavailable),
            views.findViewById<TextView>(R.id.widget_row_title).text.toString(),
        )
        assertEquals(
            context.getString(R.string.widget_open_app),
            views.findViewById<TextView>(R.id.widget_row_subtitle).text.toString(),
        )
    }

    @Test fun staleAccountSnapshotCannotEraseANewerChoiceOrRenderedRows() =
        runBlocking {
            val manager = AppWidgetManager.getInstance(context)
            val host = shadowOf(manager)
            val id = host.createWidget(AppWidgetProvider::class.java, R.layout.conversation_widget)
            val store = ConversationWidgetConfiguration(context)
            val choice = ConversationWidgetChoice("new-account")
            assertTrue(store.save(id, choice))
            val row = ConversationWidgetRow("new-account", "group", "Current conversation", true)
            manager.updateAppWidget(id, conversationWidgetViews(context, id, listOf(row), choice))
            val state =
                emptyAppState(
                    accounts = listOf(testAccount("new-account", "b".repeat(64))),
                    activeAccountRef = "new-account",
                )
            val keyguard = context.getSystemService(KeyguardManager::class.java)
            shadowOf(keyguard).setIsDeviceLocked(false)
            assertTrue(widgetUnlocked(state, keyguard))
            ConversationWidgets.refreshWidget(
                context,
                id,
                ConversationWidgets.WidgetRefresh(
                    manager,
                    store,
                    keyguard,
                    state,
                    setOf("old-account"),
                    -1L,
                    state.runtimeGeneration,
                ),
            )
            assertEquals(choice, store.read(id))
            assertEquals(
                "Current conversation",
                host
                    .getViewFor(id)
                    .findViewById<TextView>(R.id.widget_row_title)
                    .text
                    .toString(),
            )
        }
}
