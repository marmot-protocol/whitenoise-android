package dev.ipf.whitenoise.android.ui.chats

import android.content.ClipboardManager
import android.content.Context
import android.os.Looper
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.RelayEndpointClassificationFfi
import dev.ipf.marmotkit.RelayEndpointPolicyFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AppMarmotRuntime
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.ToastMessage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger

/** Real native restore failure must remain visible over the FAB's full-screen child and preserve safe-report policy. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ChatRelayFeedbackTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun failedNativeRestoreShowsPersistentCopyableFeedbackAboveRelays() {
        val writes = AtomicInteger()
        val app = nativeFailureApp(writes)
        composeRule.setContent { WhiteNoiseTheme { ChatsNewMessageFab(app, {}) } }
        composeRule.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            composeRule.onAllNodesWithTag("chat-relay-feedback-dismiss").fetchSemanticsNodes().isEmpty() &&
                runCatching {
                    composeRule
                        .onNodeWithContentDescription(context.getString(R.string.chats_check_relays))
                        .fetchSemanticsNode()
                }.isSuccess
        }
        composeRule.onNodeWithContentDescription(context.getString(R.string.chats_check_relays)).performClick()
        composeRule.onNodeWithTag("settings.list").performScrollToNode(hasTestTag("relays.restore"))
        composeRule.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            composeRule.onAllNodesWithTag("relays.restore").fetchSemanticsNodes().any {
                !it.config.contains(SemanticsProperties.Disabled)
            }
        }
        composeRule.onNodeWithTag("relays.restore").performScrollTo().performClick()
        composeRule
            .onNode(hasText(context.getString(R.string.restore_defaults)) and hasAnyAncestor(isDialog()))
            .performClick()
        composeRule.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            composeRule.onAllNodesWithTag("chat-relay-feedback-copy").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(1, writes.get())
        val notice = app.toast!!
        assertTrue(notice.copyable)
        assertNotNull(notice.diagnosticReport)
        composeRule.onNode(hasText(notice.title.resolve(context)) and hasAnyAncestor(isDialog())).assertIsDisplayed()
        // The activity snackbar may consume its copy; the visible child retains the same native receipt.
        composeRule.runOnIdle { app.clearToast() }
        composeRule.onNodeWithTag("chat-relay-feedback-copy").assertIsDisplayed().performClick()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals(
            notice.diagnosticReport,
            clipboard.primaryClip!!
                .getItemAt(0)
                .coerceToText(context)
                .toString(),
        )
        composeRule.onNodeWithTag("chat-relay-feedback-dismiss").performClick()
        composeRule.onNodeWithTag("relays.restore").assertIsDisplayed()
    }

    @Test fun nonCopyableNativeNoticeNeverExposesItsReport() {
        composeRule.setContent {
            WhiteNoiseTheme {
                ChatRelayFeedbackDialog(
                    ToastMessage(
                        AppText.Plain("Native relay result"),
                        copyable = false,
                        diagnosticReport = "not-authorized-for-copy",
                    ),
                    {},
                )
            }
        }
        composeRule.onNodeWithTag("chat-relay-feedback-copy").assertDoesNotExist()
        composeRule.onNodeWithTag("chat-relay-feedback-dismiss").assertIsDisplayed()
    }

    /**
     * Sync native methods use their actual return types; validation succeeds locally and only the native setter fails.
     */
    private fun nativeFailureApp(writes: AtomicInteger): WhiteNoiseAppState {
        val native =
            Proxy.newProxyInstance(
                MarmotInterface::class.java.classLoader,
                arrayOf(MarmotInterface::class.java),
            ) { proxy, method, args ->
                when (method.name) {
                    "accountRelayLists" ->
                        chatOrganizationRelays(false).copy(
                            defaultRelays = listOf("wss://default.example.com"),
                        )
                    "classifyRelayEndpoints" ->
                        (args!![0] as List<*>).map { raw ->
                            val endpoint = raw as String
                            RelayEndpointClassificationFfi(endpoint, endpoint, RelayEndpointPolicyFfi.ALLOWED)
                        }
                    "setAccountNip65Relays" -> {
                        writes.incrementAndGet()
                        error("Controlled relay setter failure")
                    }
                    "toString" -> "ChatRelayFeedbackNativeFixture"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> throw UnsupportedOperationException("Unexpected native call: ${method.name}")
                }
            } as MarmotInterface
        return WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore.forContext(context),
            accountIdHexResolver = { null },
            accounts =
                listOf(
                    AccountSummaryFfi(
                        label = "alice",
                        accountIdHex = "a".repeat(64),
                        localSigning = true,
                        externalSigning = false,
                        signedOut = false,
                        running = true,
                    ),
                ),
            activeAccountRef = "alice",
            initialMarmotRuntime = AppMarmotRuntime(context.cacheDir.resolve("chat-relay-feedback").path, native),
        )
    }
}
