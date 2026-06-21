package dev.ipf.darkmatter.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the pure swallow-and-default kernel ([coerceResolvedMime]) behind
 * `safeGetType`, which guards the staged-attachment metadata lookups against a
 * ghost content Uri restored after process death (issue #531).
 *
 * A Photo Picker / SAF Uri carries a session-scoped read grant that does NOT
 * survive process death. After the staging shelf is restored, calling
 * `ContentResolver.getType` on such a ghost throws `SecurityException` (or the
 * backing provider may be gone). The preview composition and send coroutine
 * must treat that as an unknown type and let the already-guarded decode reject
 * it into the existing decode-failure toast — never crash. The resolver call is
 * split out behind a supplier so this contract is exercisable on the JVM
 * without Robolectric, mirroring the `UriListSaver` codec split.
 */
class SafeGetTypeContractTest {
    @Test
    fun resolvedMimePassesThrough() {
        assertEquals("image/jpeg", coerceResolvedMime { "image/jpeg" })
        assertEquals("video/mp4", coerceResolvedMime { "video/mp4" })
    }

    @Test
    fun nullResolvedTypeCollapsesToEmpty() {
        assertEquals("", coerceResolvedMime { null })
    }

    @Test
    fun revokedGrantSecurityExceptionCollapsesToEmpty() {
        assertEquals(
            "",
            coerceResolvedMime { throw SecurityException("Permission Denial: ghost Uri after process death") },
        )
    }

    @Test
    fun deadProviderExceptionsCollapseToEmpty() {
        assertEquals("", coerceResolvedMime { throw IllegalArgumentException("Unknown URI") })
        assertEquals("", coerceResolvedMime { throw NullPointerException("provider released") })
    }

    @Test
    fun emptyResolvedTypeStaysEmpty() {
        // An empty mime is not treated as a video, so the guarded decode runs
        // and degrades gracefully rather than the staging tile crashing.
        assertEquals("", coerceResolvedMime { "" })
        assertEquals(false, coerceResolvedMime { "" }.startsWith("video/", ignoreCase = true))
    }
}
