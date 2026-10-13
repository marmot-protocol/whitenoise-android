package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.CachedIdentityProjectionFfi
import dev.ipf.marmotkit.UserProfileMetadataFfi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountSwitchIdentityBatchTest {
    @Test
    fun pagesUseOneReadPerHundredIdsAndPreserveOrder() =
        runTest {
            for (count in listOf(0, 1, 100, 101)) {
                val ids = (0 until count).map { it.toString(16).padStart(64, '0') }
                val pages = mutableListOf<List<String>>()
                val result =
                    loadAccountSwitchIdentityPages(ids) { page ->
                        pages += page
                        page.map(::missingProfile)
                    }
                assertEquals(ids.chunked(100), pages)
                assertEquals(ids, result.map { it.accountIdHex })
            }
        }

    @Test
    fun duplicateAndUppercaseRequestsKeepTheirOriginalKeys() =
        runTest {
            val ids = listOf(ID.uppercase(), ID, ID)
            val result = loadAccountSwitchIdentityPages(ids) { page -> page.map(::missingProfile) }
            assertEquals(ids, result.map { it.accountIdHex })
            assertTrue(result.all { it.displayName == "Local name" })
        }

    @Test
    fun presentClearedProfileWinsOverResolvedNameAndOldPicture() =
        runTest {
            val profile =
                UserProfileMetadataFfi(
                    name = null,
                    displayName = null,
                    about = null,
                    picture = null,
                    nip05 = null,
                    lud16 = null,
                )
            val result =
                loadAccountSwitchIdentityPages(listOf(ID)) {
                    listOf(missingProfile(ID).copy(profile = profile, resolvedName = "Old name"))
                }.single()
            assertEquals(profile, result.profile)
            assertNull(result.displayName)
            assertNull(result.avatarUrl)
        }

    @Test
    fun malformedIdentitiesAreSkippedWithoutInventingAProfile() =
        runTest {
            val result =
                loadAccountSwitchIdentityPages(listOf("invalid", ID)) {
                    listOf(missingProfile("invalid").copy(accountIdHex = null), missingProfile(ID))
                }
            assertEquals(listOf(ID), result.map { it.accountIdHex })
        }

    @Test
    fun failedOrMismatchedPagesCannotPublishPartialOrForeignIdentityState() =
        runTest {
            val ids = listOf(ID, OTHER)
            assertTrue(loadAccountSwitchIdentityPages(ids) { error("unavailable") }.isEmpty())
            assertTrue(loadAccountSwitchIdentityPages(ids) { listOf(missingProfile(ID)) }.isEmpty())
            assertTrue(loadAccountSwitchIdentityPages(ids) { ids.reversed().map(::missingProfile) }.isEmpty())
            assertTrue(
                loadAccountSwitchIdentityPages(ids) {
                    listOf(missingProfile(ID), missingProfile(OTHER).copy(accountIdHex = ID))
                }.isEmpty(),
            )
        }

    @Test(expected = CancellationException::class)
    fun cancellationCannotContinueReadingLaterPages() =
        runTest {
            loadAccountSwitchIdentityPages(List(101) { ID }) { throw CancellationException("cancelled") }
        }

    private fun missingProfile(id: String) = CachedIdentityProjectionFfi(id, id.lowercase(), null, null, "Local name")

    private companion object {
        val ID = "ab".repeat(32)
        val OTHER = "cd".repeat(32)
    }
}
