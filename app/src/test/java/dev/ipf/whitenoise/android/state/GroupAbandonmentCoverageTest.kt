package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.functionBody
import dev.ipf.whitenoise.android.kotlinBlockFrom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Invariant gate for #3310: local abandonment is reachable for direct chats as well as groups, and every entry point
 * reaches MDK's forget only through the one journaled boundary, never through a departure, admin mutation, receiving
 * local delete or a second native call site.
 */
class GroupAbandonmentCoverageTest {
    /**
     * Group Details hides the control only by the shared rule, so a member DM always has its escape path and Details
     * hides it exactly where the chat list does.
     */
    @Test
    fun detailsControlStaysReachableForDirectChats() {
        val body = source("ui/group/GroupDetailsResetControl.kt").functionBody("GroupDetailsResetControl")
        assertTrue("the control may hide only by the shared rule", "if (!offered) return" in body)
        assertFalse("a direct chat must not hide the abandonment control", "isDm ||" in body || "|| isDm" in body)
        val screen = source("ui/group/GroupDetailsScreen.kt")
        val call = screen.substringAfter("GroupDetailsResetControl(").substringBefore(")\n")
        assertTrue("Group Details must pass the DM flag instead of gating the call", "isDm = isDm" in call)
        assertTrue("Details uses the shared rule", "offered = controller.group.offersLocalAbandonment()" in call)
        assertTrue("the row opens the reviewed confirmation", "onRequest = ::requestAbandonment" in call)
    }

    /**
     * One rule decides where abandonment is offered on both surfaces: a read-only invite declines instead, and only
     * MDK's confirmed non-membership withdraws it. A queued leave or a converging disband must keep the escape path.
     */
    @Test
    fun bothSurfacesShareTheConfirmedNonMembershipRule() {
        val rule =
            source("state/ConversationMembershipState.kt")
                .substringAfter("fun AppGroupRecordFfi.offersLocalAbandonment()")
                .substringBefore("\n}\n")
        listOf("pendingConfirmation", "selfMembership.isNonMember()", "disbanded").forEach {
            assertTrue("the rule reads $it", it in rule)
        }
        val row =
            source("ui/chats/ChatAbandonment.kt")
                .substringAfter("fun ChatListItem.offersLocalAbandonment()")
                .substringBefore("\n}\n")
        assertTrue("the chat list applies the same group rule", "group.offersLocalAbandonment()" in row)
        assertTrue("the row's own confirmation counts too", "selfMembership?.isNonMember()" in row)
        for ((name, body) in listOf("rule" to rule, "chat list" to row)) {
            listOf("leaveRequestPending", "disbanding", "removedFromGroup").forEach {
                assertFalse("the $name must not withdraw abandonment on $it", it in body)
            }
        }
        val screen = source("ui/group/GroupDetailsScreen.kt")
        assertTrue(
            "the leave-blocked notice follows the same rule",
            "abandonment.leaveBlocked && controller.group.offersLocalAbandonment()" in screen,
        )
    }

    /** The confirmation fixes the acting account and the exact conversation before anything is submitted. */
    @Test
    fun confirmationCapturesTheAccountAndConversation() {
        val request = source("ui/group/GroupDetailsScreen.kt").functionBody("requestAbandonment")
        listOf("accountRef = account", "accountName =", "groupIdHex = controller.group.groupIdHex", "isDm = isDm")
            .forEach { assertTrue("Group Details review must capture $it", it in request) }
        val screen = source("ui/group/GroupDetailsScreen.kt")
        assertTrue(
            "the review opens only for the conversation's own account while it is active",
            "abandonmentAccount(controller.boundAccountRef, appState.activeAccountRef) ?: return" in request,
        )
        assertTrue(
            "the confirm re-checks the conversation's account against the active one",
            "abandonment.targets(actingAccount, controller.group.groupIdHex)" in screen,
        )
        val dialog = source("ui/group/GroupDetailsResetControl.kt")
        assertTrue("the dialog names the acting account", "R.string.abandon_identity_account" in dialog)
        assertTrue("the dialog names the conversation", "target.conversationTitle" in dialog)
        assertTrue("confirm is disabled while the forget runs", "enabled = !running" in dialog)
        val chatHost = source("ui/chats/ChatAbandonment.kt")
        assertEquals("every surface shares one confirmation", 3, sharedDialogUses())
        assertTrue(
            "chat-list confirm closes before submitting and fences its captured owner",
            chatHost.indexOf("onDismiss()") < chatHost.indexOf("request.owner.isCurrent(appState, controller)"),
        )
        assertTrue("a chat already being removed is never submitted twice", "busyChats.add(groupId)" in chatHost)
    }

    /** Both entry points use the shared journaled forget and none of the departure or receiving-delete paths. */
    @Test
    fun everyEntryPointUsesTheSharedForgetBoundary() {
        val details = source("state/Controllers.kt").functionBody("forgetGroupLocal")
        assertTrue("Group Details abandonment uses the shared owner", "forgetGroupLocalWithClientCleanup" in details)
        val chatList = source("state/ChatListAbandonment.kt").extensionBody("abandonChatFromList")
        assertTrue("chat-list abandonment reuses the local-removal path", "deleteGroupLocalFromChatList" in chatList)
        assertTrue("chat-list abandonment must forget, not delete", "forgetProtocol = true" in chatList)
        val localRemoval = source("state/Controllers.kt").functionBody("deleteGroupLocalFromChatList")
        assertTrue(
            "a failed abandonment's retry notice must keep forgetting instead of deleting",
            "forgetProtocol = forgetProtocol" in localRemoval.substringAfter("localDeleteRetryNotice("),
        )
        val boundary = source("state/GroupLocalRemoval.kt").extensionBody("forgetGroupLocalWithClientCleanup")
        assertTrue("the boundary must stage the durable journal", "deleteChatGroupLocalWithRecovery" in boundary)
        assertTrue("the boundary must forget, not delete", "forgetProtocol = true" in boundary)
        for ((name, body) in listOf("details" to details, "chat list" to chatList, "boundary" to boundary)) {
            DEPARTURES.forEach { assertFalse("$name abandonment must not call $it", it in body) }
            assertFalse("$name abandonment must not use the receiving local delete", "deleteGroupLocal(" in body)
        }
    }

    /**
     * The chat-list bulk action reaches the same journaled forget as the row action: it opens only for a selection
     * that offers abandonment, through the captured departure request and its busy set, reviews every chat in the
     * shared confirmation, and each step calls abandonChatFromList, never a departure or a receiving delete.
     */
    @Test
    fun bulkAbandonmentUsesTheSharedForgetBoundary() {
        val selection =
            source("ui/chats/ChatsScreen.kt")
                .substringAfter("abandonLabel =")
                .substringBefore("onAddToFolder =")
        assertTrue("only a selection that offers it", "offerLocalAbandonment(" in selection)
        assertTrue("the busy set fences duplicate taps", "leavingAndDeleting" in selection)
        assertTrue("the captured request owns the batch", "PendingChatDeparture(" in selection)
        assertTrue("the batch abandons", "abandon = true" in selection)
        assertTrue("no review without an acting account", "controller.accountRef != null" in selection)
        val dialog = source("ui/chats/ChatDepartureBatchDialog.kt")
        assertTrue("the batch uses the abandonment steps", "abandonmentDepartureCallbacks(controller" in dialog)
        assertTrue("an identity-less review closes instead", "abandonmentTargets.isEmpty()" in dialog)
        val steps =
            source("ui/chats/ChatAbandonment.kt")
                .substringAfter("fun abandonmentDepartureCallbacks(")
                .substringBefore("\n\n")
        assertTrue("each step uses the row action's forget", "abandonChatFromList(" in steps)
        assertTrue("the batch alone reports each chat and owns its Retry", "reportedByBatch = true" in steps)
        DEPARTURES.forEach { assertFalse("bulk abandonment must not call $it", it in steps) }
        assertFalse("bulk abandonment must not delete locally", "deleteGroupLocalFromChatList" in steps)
    }

    /**
     * The native forget has exactly one call site, so no surface can bypass the journal or its cleanup. Any call that
     * passes an argument counts, whatever it is named, while declarations and the controller's argument-free
     * `forgetGroupLocal()` do not.
     */
    @Test
    fun nativeForgetHasOneJournaledCallSite() {
        val callSites =
            mainRoot()
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .flatMap { file -> nativeForgetCalls(file.readText()).map { line -> "${file.name}:$line" } }
                .toList()
        assertEquals("exactly one native forget call site, found $callSites", 1, callSites.size)
        assertTrue("the call site is the journaled boundary", callSites.single().startsWith("GroupLocalRemoval.kt:"))
    }

    /** The detector matches calls with any argument, including a multi-line one, and skips declarations. */
    @Test
    fun nativeForgetDetectorMatchesAnyArgumentAndSkipsDeclarations() {
        val source =
            """
            fun MarmotInterface.forgetGroupLocal(account: String, group: String) = Unit
            suspend fun forgetGroupLocal(): Boolean = true
            val a = forgetGroupLocal(accountRef, groupIdHex)
            val b = marmot.forgetGroupLocal(
                account = other,
            )
            val c = controller.forgetGroupLocal()
            """.trimIndent()
        assertEquals(listOf(3, 4), nativeForgetCalls(source))
    }

    /** The chat list offers the row action only through its captured, reviewed request. */
    @Test
    fun chatListRowActionOpensTheReviewedRequest() {
        val screen = source("ui/chats/ChatsScreen.kt")
        val menu = screen.substringAfter("onAbandon =").substringBefore("onDismiss =")
        assertTrue("the menu captures its row and owner", "PendingChatAbandonment.capture(item" in menu)
        assertTrue("the menu offers the action only where it applies", "item.offersLocalAbandonment()" in menu)
        assertTrue("the host shares the departure busy set", "busyChats = leavingAndDeleting" in screen)
    }

    /** One-based lines of every `forgetGroupLocal` call that passes an argument, skipping `fun` declarations. */
    private fun nativeForgetCalls(source: String): List<Int> =
        NATIVE_FORGET_CALL
            .findAll(source)
            .filterNot { call ->
                val lineStart = source.lastIndexOf('\n', call.range.first) + 1
                FORGET_DECLARATION_PREFIX.containsMatchIn(source.substring(lineStart, call.range.first))
            }.map { call -> source.substring(0, call.range.first).count { it == '\n' } + 1 }
            .toList()

    /** Body of an extension function such as `fun Receiver.name(...)`, which [functionBody] does not match. */
    private fun String.extensionBody(name: String): String {
        val start =
            Regex("""\bfun\s+[\w.]+\.${Regex.escape(name)}\s*\(""").find(this)?.range?.last
                ?: error("Missing extension function $name")
        val parameters = kotlinBlockFrom(start, "parameters for $name", opening = '(', closing = ')')
        return kotlinBlockFrom(start + parameters.length, "function $name", searchForOpening = true)
    }

    /** How many entry-point sources compose the one shared confirmation. */
    private fun sharedDialogUses(): Int =
        listOf(
            "ui/group/GroupDetailsScreen.kt",
            "ui/chats/ChatAbandonment.kt",
            "ui/chats/ChatDepartureBatchDialog.kt",
        ).count {
            "GroupAbandonmentDialog(" in source(it)
        }

    /** Reads one production source relative to the app package root. */
    private fun source(relative: String): String = File(mainRoot(), relative).readText()

    /** The production package root, whether Gradle runs from the module or the repository. */
    private fun mainRoot(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android"),
            File("app/src/main/java/dev/ipf/whitenoise/android"),
        ).firstOrNull { it.exists() } ?: error("Missing main source root")

    private companion object {
        val NATIVE_FORGET_CALL = Regex("""\bforgetGroupLocal\(\s*[^)\s]""")
        val FORGET_DECLARATION_PREFIX = Regex("""\bfun\s+(?:[\w.<>]+\.)?$""")
        val DEPARTURES =
            listOf("leaveGroup(", "transferAdmin", "promoteAdmin", "selfDemoteAdmin", "stepDownAsAdmin", "disbandGroup")
    }
}
