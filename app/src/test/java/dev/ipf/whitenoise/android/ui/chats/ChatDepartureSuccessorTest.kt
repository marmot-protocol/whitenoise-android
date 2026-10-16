package dev.ipf.whitenoise.android.ui.chats

import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ChatDepartureSuccessorTest {
    private val target = ChatDepartureTarget("group", "Group", false)
    private val first = AppGroupMemberRecordFfi("first", null, false)
    private val second = AppGroupMemberRecordFfi("second", null, false)

    @Test
    fun soleCandidateIsPreselectedAndItsExactAnswerReturns() =
        runTest {
            val ui = ChatDepartureUi()
            val answer =
                async(start = CoroutineStart.UNDISPATCHED) {
                    chooseDepartureSuccessor(ui, target, listOf(first))
                }
            assertSame(first, ui.selected)
            checkNotNull(ui.decision).answer.complete(first)
            assertSame(first, answer.await())
            assertNull(ui.decision)
        }

    @Test
    fun multipleCandidatesRequireAChoiceAndCancelAllowsReopening() =
        runTest {
            val ui = ChatDepartureUi()
            repeat(2) {
                val answer =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        chooseDepartureSuccessor(ui, target, listOf(first, second))
                    }
                assertNull(ui.selected)
                assertEquals(listOf(first, second), checkNotNull(ui.decision).members)
                checkNotNull(ui.decision).answer.complete(null)
                assertNull(answer.await())
                assertNull(ui.decision)
            }
        }

    @Test
    fun inactiveOwnerNeverCreatesAChoice() =
        runTest {
            val ui = ChatDepartureUi().apply { active = false }
            assertNull(chooseDepartureSuccessor(ui, target, listOf(first)))
            assertNull(ui.decision)
            assertNull(ui.selected)
        }

    @Test
    fun cancelledChoiceClearsOnlyItsOwnDecision() =
        runTest {
            val ui = ChatDepartureUi()
            val answer =
                async(start = CoroutineStart.UNDISPATCHED) {
                    chooseDepartureSuccessor(ui, target, listOf(first))
                }
            answer.cancelAndJoin()
            assertNull(ui.decision)
        }
}
