package dev.ipf.whitenoise.android.widget

import dev.ipf.marmotkit.ChatListAnchorOutcomeFfi
import dev.ipf.marmotkit.ChatListPageDirectionFfi
import dev.ipf.marmotkit.ChatListViewFfi
import dev.ipf.marmotkit.ChatListWindowSnapshotFfi
import dev.ipf.marmotkit.PresentedChatRowFfi
import dev.ipf.whitenoise.android.state.ChatListWindowHandle
import dev.ipf.whitenoise.android.state.presentedRow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetWindowRowsTest {
    @Test
    fun unreadBeyondFiftyNewerReadChatsIsFound() =
        runTest {
            val recent = (0 until 50).map { presentedRow("read-$it") }
            val older =
                presentedRow("older-unread").let {
                    it.copy(row = it.row.copy(hasUnread = true, unreadCount = 1uL))
                }
            val window = WidgetTestWindow(listOf(recent, listOf(older)))
            val rows = widgetWindowRows("account", true, window)
            assertEquals(listOf("older-unread"), rows.map { it.group })
            assertEquals(listOf("read-49"), window.anchors)
            assertEquals(1, window.pagesRead)
            assertTrue(rows.all { it.preview == null })
        }

    @Test
    fun manuallyUnreadChatCountsEvenWithoutUnreadMessages() =
        runTest {
            val marked = presentedRow("marked").let { it.copy(row = it.row.copy(manuallyMarkedUnread = true)) }
            val rows = widgetWindowRows("account", true, WidgetTestWindow(listOf(listOf(marked))))
            assertEquals(listOf("marked"), rows.map { it.group })
            assertTrue(rows.single().unread)
        }

    @Test
    fun recentModeDoesNotPageAndRetainsOnlyWidgetCapacity() =
        runTest {
            val recent = (0 until 50).map { presentedRow("recent-$it") }
            val window = WidgetTestWindow(listOf(recent, listOf(presentedRow("older"))))
            assertEquals(MAX_WIDGET_ROWS, widgetWindowRows("account", false, window).size)
            assertEquals(0, window.pagesRead)
        }

    @Test
    fun aStalledNativePageStopsInsteadOfSpinning() =
        runTest {
            val recent = (0 until 50).map { presentedRow("read-$it") }
            val window = WidgetTestWindow(listOf(recent, recent))
            assertTrue(widgetWindowRows("account", true, window).isEmpty())
            assertEquals(1, window.pagesRead)
        }

    @Test
    fun allAccountsIncludesUnreadBeyondTheEighthAccount() =
        runTest {
            val accounts = (1..9).map { "account-$it" }.toSet()
            val queried = mutableListOf<String>()
            val choice = ConversationWidgetChoice("account-1", allAccounts = true, unreadOnly = true)
            val rows =
                widgetAccountRows(choice, accounts) {
                    queried.add(it)
                    if (it == "account-9") listOf(ConversationWidgetRow(it, "older", "Unread", true)) else emptyList()
                }
            assertEquals(accounts.toList(), queried)
            assertEquals(listOf("account-9"), rows.map { it.account })
        }

    @Test
    fun allAccountsRetainsOnlyTheMostRecentWidgetCapacityAcrossAccounts() =
        runTest {
            val accounts = (1..10).map { "account-$it" }.toSet()
            val rows =
                widgetAccountRows(ConversationWidgetChoice("account-1", allAccounts = true), accounts) { account ->
                    val activity = account.substringAfter('-').toULong()
                    listOf(ConversationWidgetRow(account, "same-id", account, false, activity = activity))
                }
            assertEquals(MAX_WIDGET_ROWS, rows.size)
            assertEquals((10 downTo 3).map { "account-$it" }, rows.map { it.account })
        }
}

private class WidgetTestWindow(
    private val pages: List<List<PresentedChatRowFfi>>,
) : ChatListWindowHandle {
    var pagesRead = 0
    val anchors = mutableListOf<String>()
    private var sequence = 1uL

    override fun snapshot(): ChatListWindowSnapshotFfi = frame()

    override suspend fun next(): ChatListWindowSnapshotFfi? = null

    override suspend fun page(
        sequence: ULong,
        direction: ChatListPageDirectionFfi,
        count: UInt,
    ): ChatListWindowSnapshotFfi {
        check(sequence == this.sequence)
        check(direction == ChatListPageDirectionFfi.FORWARD && count == WIDGET_NATIVE_PAGE_SIZE)
        pagesRead++
        this.sequence++
        return frame()
    }

    override suspend fun setVisibleAnchor(
        sequence: ULong,
        groupIdHex: String,
    ): ChatListWindowSnapshotFfi {
        check(sequence == this.sequence)
        anchors.add(groupIdHex)
        this.sequence++
        return frame()
    }

    override suspend fun returnToTop(sequence: ULong): ChatListWindowSnapshotFfi = frame()

    override fun close() = Unit

    private fun frame() =
        ChatListWindowSnapshotFfi(
            subscriptionGeneration = "widget-test",
            sequence = sequence,
            view = ChatListViewFfi.CHATS,
            rows = pages[pagesRead],
            hasMoreBefore = pagesRead > 0,
            hasMoreAfter = pagesRead < pages.lastIndex,
            anchor = ChatListAnchorOutcomeFfi.Top,
        )
}
