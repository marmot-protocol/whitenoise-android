package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppGroupMemberIdsFfi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupMemberIdsPageTest {
    @Test
    fun oneHundredGroupsUseOneBoundedWorkerCall() =
        runTest {
            val requested = (0 until 100).map(::groupId)
            val pages = mutableListOf<List<String>>()

            val loaded =
                loadGroupMemberIdsPages(requested) { page ->
                    pages += page
                    page.map { id -> AppGroupMemberIdsFfi(id, listOf(SELF, PEER)) }
                }

            assertEquals(listOf(requested), pages)
            assertEquals(requested, loaded.map { it.groupIdHex })
        }

    @Test
    fun largerAccountsUseBoundedPagesInsteadOfPerGroupCalls() =
        runTest {
            val requested = (0 until 205).map(::groupId)
            val pageSizes = mutableListOf<Int>()

            loadGroupMemberIdsPages(requested) { page ->
                pageSizes += page.size
                page.map { id -> AppGroupMemberIdsFfi(id, listOf(SELF)) }
            }

            assertEquals(listOf(100, 100, 5), pageSizes)
        }

    @Test
    fun identifierProjectionBuildsDistinctRecordsAndMarksTheActiveAccountLocal() {
        val records = memberRecordsFromIds(listOf(SELF.uppercase(), SELF, PEER, ""), SELF)

        assertEquals(listOf(SELF.uppercase(), PEER), records.map { it.memberIdHex })
        assertTrue(records.first().local)
        assertFalse(records.last().local)
        assertTrue(records.all { it.account == null })
    }

    @Test
    fun firstFrameProfileWarmSelectsOnlyDistinctDirectConversationPeers() {
        val directGroup = groupId(1)
        val duplicatePeerGroup = groupId(2)
        val namedGroup = groupId(3)
        val selfOnlyGroup = groupId(4)
        val projections =
            listOf(
                AppGroupMemberIdsFfi(directGroup, listOf(SELF.uppercase(), PEER)),
                AppGroupMemberIdsFfi(duplicatePeerGroup, listOf(SELF, PEER.uppercase(), PEER)),
                AppGroupMemberIdsFfi(namedGroup, listOf(SELF, OTHER)),
                AppGroupMemberIdsFfi(selfOnlyGroup, listOf(SELF)),
            )

        val peers =
            initialDirectPeerProfileIds(projections, SELF) { groupIdHex, _ ->
                groupIdHex != namedGroup
            }

        assertEquals(listOf(PEER), peers.map(String::lowercase))
    }

    @Test
    fun firstFrameProfileWarmRequiresAnActiveAccount() {
        val projections = listOf(AppGroupMemberIdsFfi(groupId(1), listOf(SELF, PEER)))

        assertTrue(initialDirectPeerProfileIds(projections, null) { _, _ -> true }.isEmpty())
    }

    @Test(expected = IllegalStateException::class)
    fun mismatchedProjectionOrderFailsTheWholePage() =
        runTest {
            loadGroupMemberIdsPages(listOf(groupId(1), groupId(2))) { page ->
                page.reversed().map { id -> AppGroupMemberIdsFfi(id, listOf(SELF)) }
            }
        }

    private fun groupId(index: Int): String = index.toString(16).padStart(64, '0')

    private companion object {
        const val SELF = "11aa"
        const val PEER = "22bb"
        const val OTHER = "33cc"
    }
}
