package dev.ipf.whitenoise.android.ui.chats

import android.content.Context
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Global message results compose before the native runtime is installed without throwing (#3686 review). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GlobalSearchResultsSessionStartupTest {
    @get:Rule val composeRule = createComposeRule()

    /** The session and its recheck effects compose while no runtime exists, and simply wait for one. */
    @Test
    fun resultsSessionComposesBeforeTheRuntimeExists() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appState = WhiteNoiseAppState(context, DraftStore(NoDrafts), { null }, emptyList(), ACCOUNT)
        assertEquals(null, appState.marmotOrNull())
        var session: GlobalSearchResultsController? = null

        composeRule.setContent {
            session =
                rememberGlobalSearchResults(
                    appState = appState,
                    account = ACCOUNT,
                    query = GlobalSearchResultsQuery(needle = "gm"),
                    enabled = true,
                )
        }
        composeRule.waitForIdle()

        assertNotNull("the session exists even without a runtime", session)
    }

    private object NoDrafts : DraftPersistence {
        /** Returns no saved drafts. */
        override fun read(): Map<String, String> = emptyMap()

        /** Discards draft writes. */
        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    private companion object {
        const val ACCOUNT = "startup-account"
    }
}
