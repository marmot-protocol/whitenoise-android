package dev.ipf.whitenoise.android.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression for issue #1697: a freshly composed [ChatsController] must not
 * present an authoritative empty chat list before [ChatsController.bind] has
 * completed the first local snapshot.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class ChatsControllerInitialLoadStateTest {
    @Test
    fun newlyConstructedController_isNotAuthoritativelyEmptyBeforeBind() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val accountRef = ACCOUNT_REF
        val appState =
            WhiteNoiseAppState(
                context = app,
                draftStore = DraftStore(InMemoryDraftPersistence()),
                accountIdHexResolver = { null },
                accounts = listOf(activeAccount(accountRef)),
                activeAccountRef = accountRef,
            )
        val controller = ChatsController(appState)

        assertTrue(
            "A fresh controller must report loading until the first local snapshot",
            controller.isLoading,
        )
        assertFalse(
            "An empty, non-loading controller paints EmptyChats before bind runs",
            controller.items.isEmpty() &&
                controller.archivedItems.isEmpty() &&
                !controller.isLoading &&
                controller.error == null,
        )
    }

    private fun activeAccount(label: String) =
        AccountSummaryFfi(
            label = label,
            accountIdHex = ACCOUNT_HEX,
            localSigning = true,
            externalSigning = false,
            signedOut = false,
            running = true,
        )

    private class InMemoryDraftPersistence : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    private companion object {
        const val ACCOUNT_REF = "acct-a"
        val ACCOUNT_HEX = "a".repeat(64)
    }
}
