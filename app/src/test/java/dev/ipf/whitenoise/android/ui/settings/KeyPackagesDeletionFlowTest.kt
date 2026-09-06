package dev.ipf.whitenoise.android.ui.settings

import android.content.Context
import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountKeyPackageFfi
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h800dp-mdpi")
class KeyPackagesDeletionFlowTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** Drives confirmation through acknowledgement and verifies the refreshed inventory removes the event. */
    @Test
    fun acknowledgedDeletionReloadsAndRemovesRelayRecord() {
        val target = relayPackage()
        var inventory = listOf(target)
        val loads = AtomicInteger()
        var deletedEvent: String? = null
        var deletedRelays: List<String>? = null

        render(
            load = {
                loads.incrementAndGet()
                inventory
            },
            delete = { accountRef, eventIdHex, sourceRelays ->
                assertEquals(ACCOUNT_REF, accountRef)
                deletedEvent = eventIdHex
                deletedRelays = sourceRelays
                inventory = emptyList()
                true
            },
        )
        waitForLoads(1, loads)

        confirmDeletion()
        waitForLoads(2, loads)

        composeRule.onNodeWithText(IdentityFormatter.short(target.keyPackageId)).assertDoesNotExist()
        assertEquals(target.eventIdHex, deletedEvent)
        assertEquals(target.sourceRelays, deletedRelays)
    }

    /** Keeps the existing inventory when deletion is cancelled before acknowledgement. */
    @Test
    fun cancelledDeletionDoesNotReportSuccessOrReloadStaleInventory() {
        val target = relayPackage()
        val loads = AtomicInteger()

        render(
            load = {
                loads.incrementAndGet()
                listOf(target)
            },
            delete = { _, _, _ -> throw CancellationException("cancel deletion") },
        )
        waitForLoads(1, loads)

        confirmDeletion()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(IdentityFormatter.short(target.keyPackageId)).assertExists()
        assertEquals(1, loads.get())
    }

    /** Renders the production account-scoped screen with controllable MDK load and delete boundaries. */
    private fun render(
        load: suspend (Boolean) -> List<AccountKeyPackageFfi>,
        delete: suspend (String, String, List<String>) -> Boolean,
    ) {
        val appState =
            WhiteNoiseAppState(
                context = context,
                draftStore = DraftStore.forContext(context),
                accountIdHexResolver = { null },
                accounts = listOf(activeAccount()),
                activeAccountRef = ACCOUNT_REF,
            )
        composeRule.setContent {
            WhiteNoiseTheme {
                Surface {
                    KeyPackagesScreen(
                        appState = appState,
                        onBack = {},
                        loadKeyPackages = load,
                        deleteKeyPackage = delete,
                    )
                }
            }
        }
    }

    /** Opens the selected package's confirmation and accepts the destructive action. */
    private fun confirmDeletion() {
        composeRule
            .onNodeWithContentDescription(context.getString(R.string.delete_key_package))
            .performClick()
        composeRule.onNodeWithText(context.getString(R.string.delete)).performClick()
    }

    /** Waits for the injected inventory read without depending on a fixed coroutine delay. */
    private fun waitForLoads(
        expected: Int,
        loadCount: AtomicInteger,
    ) {
        composeRule.waitUntil(timeoutMillis = 5_000) { expected <= loadCount.get() }
    }

    /** Supplies the signed-in owner used by both inventory and deletion callbacks. */
    private fun activeAccount() =
        AccountSummaryFfi(
            label = ACCOUNT_REF,
            accountIdHex = "12".repeat(32),
            localSigning = true,
            externalSigning = false,
            signedOut = false,
            running = true,
        )

    /** Models a relay-discovered package whose provenance is bound by the screen's account generation. */
    private fun relayPackage() =
        AccountKeyPackageFfi(
            // Relay-fetched inventory has protocol provenance but no local
            // account label; the screen binds it to the account generation
            // that loaded the inventory.
            accountRef = null,
            accountIdHex = "12".repeat(32),
            keyPackageId = "published-key-package",
            keyPackageRefHex = "34".repeat(32),
            eventIdHex = "ab".repeat(32),
            publishedAt = 1_700_000_000uL,
            keyPackageBytes = 128uL,
            sourceRelays = listOf("wss://relay.external.example"),
            local = true,
            relay = true,
        )

    private companion object {
        const val ACCOUNT_REF = "personal"
    }
}
