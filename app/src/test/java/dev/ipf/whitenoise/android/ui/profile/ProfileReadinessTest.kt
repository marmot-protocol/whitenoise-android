package dev.ipf.whitenoise.android.ui.profile

import dev.ipf.whitenoise.android.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Readiness depends on native names, never optional public disclosures or unsaved drafts. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ProfileReadinessTest {
    /** Empty and optional-only records need a name; whitespace display names fall back to name. */
    @Test fun namesAloneControlReadiness() {
        val empty = profileEditMetadata("", "", "", "", "", "")
        assertEquals(R.string.profile_readiness_setup, profileReadiness(empty).summary)
        assertEquals(R.string.profile_readiness_setup, profileReadiness(empty.copy(about = "Bio")).summary)
        val fallback = empty.copy(name = "Alice", displayName = "  ")
        assertEquals(R.string.profile_readiness_optional, profileReadiness(fallback).summary)
        assertEquals(true, profileReadiness(fallback).fields.first())
        val full = profileEditMetadata("Alice", "Bio", "picture", "banner", "address", "lightning")
        assertEquals(R.string.profile_readiness_ready, profileReadiness(full).summary)
        assertEquals(6, profileReadiness(full).fields.size)
        assertEquals(R.string.profile_readiness_optional, profileReadiness(full.copy(about = " ")).summary)
    }

    /** Missing native data is unavailable, while a successfully read empty record is setup. */
    @Test fun nullIsNotAnEmptyProfile() =
        runTest {
            val state = ProfileReadinessState(null)
            assertEquals(ProfileReadiness.Loading, state.value)
            state.refresh(null) { null }
            assertEquals(ProfileReadiness.Unavailable, state.value)
            state.refresh(null) { profileEditMetadata("", "", "", "", "", "") }
            assertEquals(R.string.profile_readiness_setup, state.value.summary)
        }

    /** Cached records paint immediately and survive offline/read failures until an authoritative read succeeds. */
    @Test fun failedReadRetainsLastAuthoritativePresentation() =
        runTest {
            val alice = profileEditMetadata("Alice", "", "", "", "", "")
            val state = ProfileReadinessState(alice)
            val before = state.value
            state.refresh(null) { error("offline") }
            assertEquals(before, state.value)
            state.refresh(null) { null }
            assertEquals(before, state.value)
            state.refresh(null) { alice.copy(displayName = null, name = null) }
            assertEquals(R.string.profile_readiness_setup, state.value.summary)
        }

    /** A fresh successful read stays authoritative over an unchanged stale cache after unrelated invalidation. */
    @Test fun failedRefreshCannotRestoreOlderCachedReadiness() =
        runTest {
            val empty = profileEditMetadata("", "", "", "", "", "")
            val state = ProfileReadinessState(empty)
            state.refresh(empty) { empty.copy(name = "Alice") }
            state.refresh(empty) { null }
            assertEquals(R.string.profile_readiness_optional, state.value.summary)
        }

    /** An older in-flight read cannot roll back a later read after publication. */
    @Test fun supersededReadCannotUndoReadback() =
        runTest {
            val old = CompletableDeferred<dev.ipf.marmotkit.UserProfileMetadataFfi?>()
            val state = ProfileReadinessState(null)
            val job = launch { state.refresh(null) { old.await() } }
            runCurrent()
            state.refresh(null) { profileEditMetadata("Alice", "", "", "", "", "") }
            old.complete(profileEditMetadata("", "", "", "", "", ""))
            job.join()
            assertEquals(R.string.profile_readiness_optional, state.value.summary)
            assertFalse(
                state.value.fields
                    .drop(1)
                    .any { it },
            )
        }
}
