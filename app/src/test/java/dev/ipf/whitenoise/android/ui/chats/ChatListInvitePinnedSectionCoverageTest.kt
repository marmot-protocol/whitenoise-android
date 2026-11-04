package dev.ipf.whitenoise.android.ui.chats

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.whitenoise.android.state.AppMarmotRuntime
import dev.ipf.whitenoise.android.state.ChatListLiveSubscriptions
import dev.ipf.whitenoise.android.state.ChatsController
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.chatRow
import dev.ipf.whitenoise.android.state.noChatRowActions
import dev.ipf.whitenoise.android.state.presentedRow
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Invariant gate for #3579: on the mounted production chat list, incoming chat invites land below the
 * pinned section. The pins stay first in their saved manual order, exactly one divider follows the last
 * pin, invites head the unpinned rows, and accepting an invite leaves the pin set and positions intact.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
@OptIn(ExperimentalCoroutinesApi::class)
class ChatListInvitePinnedSectionCoverageTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val scope = TestScope()
    private var fixture: InviteFixture? = null

    /** Clears the controller and the test scope so no projection work outlives the test. */
    @After fun close() {
        rule.runOnIdle { fixture?.controller?.onCleared() }
        scope.cancel()
    }

    /** One arriving invite lands after the pins, which keep their manual order and their single divider. */
    @Test fun anIncomingInviteLandsBelowThePinnedSection() {
        val model = mount()
        model.deliver(invite(INVITE_A, activity = NEWEST))
        assertSections(pins = listOf(PIN_0, PIN_1), invites = listOf(INVITE_A))
    }

    /** Several invites stay grouped below the pins, newest first, ahead of the ordinary rows. */
    @Test fun severalIncomingInvitesKeepThePinnedPrefix() {
        val model = mount()
        model.deliver(invite(INVITE_A, activity = NEWEST - 1uL))
        model.deliver(invite(INVITE_B, activity = NEWEST))
        assertSections(pins = listOf(PIN_0, PIN_1), invites = listOf(INVITE_B, INVITE_A))
    }

    /** Accepting an invite turns it into an ordinary row and leaves the pins and the divider unchanged. */
    @Test fun acceptingAnInviteRetainsThePinsAndTheirPositions() {
        val model = mount()
        model.deliver(invite(INVITE_A, activity = NEWEST))
        assertSections(pins = listOf(PIN_0, PIN_1), invites = listOf(INVITE_A))
        model.deliver(model.rows.getValue(INVITE_A).copy(pendingConfirmation = false))
        assertSections(pins = listOf(PIN_0, PIN_1), invites = emptyList(), ordinary = ORDINARY.toSet() + INVITE_A)
        assertEquals(INVITE_A, model.controller.items[2].id)
    }

    /**
     * Asserts the controller order and the rendered geometry: [pins] first in manual order, then
     * [invites], then only [ordinary] rows, with exactly one divider drawn between the last pin and the
     * first unpinned row.
     */
    private fun assertSections(
        pins: List<String>,
        invites: List<String>,
        ordinary: Set<String> = ORDINARY.toSet(),
    ) {
        val order = fixture!!.controller.items.map { it.id }
        assertEquals(pins + invites, order.take(pins.size + invites.size))
        assertTrue("ordinary rows follow the invites", order.drop(pins.size + invites.size).all { it in ordinary })
        rule.onAllNodesWithTag(CHAT_LIST_PINNED_BOUNDARY_TAG).assertCountEquals(1)
        val divider = rule.onNodeWithTag(CHAT_LIST_PINNED_BOUNDARY_TAG).fetchSemanticsNode().boundsInRoot
        val lastPin = rule.onNodeWithTag("chat.row.${pins.last()}").fetchSemanticsNode().boundsInRoot
        val firstUnpinned = rule.onNodeWithTag("chat.row.${order[pins.size]}").fetchSemanticsNode().boundsInRoot
        assertTrue("the divider sits below the last pin", divider.top >= lastPin.bottom)
        assertTrue("the divider sits above the first unpinned row", divider.bottom <= firstUnpinned.top)
        (pins + invites).forEach { rule.onNodeWithTag("chat.row.$it").assertIsDisplayed() }
    }

    /** Mounts the production chat list over two manual-order pins and a few ordinary rows. */
    private fun mount(): InviteFixture {
        val model = InviteFixture(scope)
        fixture = model
        rule.setContent {
            WhiteNoiseTheme {
                ChatsScreen(model.state, model.controller, {}, { _, _, _, _ -> })
            }
        }
        settle()
        assertSections(pins = listOf(PIN_0, PIN_1), invites = emptyList())
        return model
    }

    /** Applies [row] the way a native row update does, then settles the list. */
    private fun InviteFixture.deliver(row: ChatListRowFfi) {
        rows[row.groupIdHex] = row
        rule.runOnIdle { controller.applyChatListRow(row) }
        settle()
    }

    /** Runs pending test-scheduler work and Android Main twice, then waits for Compose to idle. */
    private fun settle() {
        repeat(2) {
            scope.testScheduler.runCurrent()
            ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS)
            rule.waitForIdle()
        }
    }

    /** A populated account: pin 1 is the most active pin, so only manual order puts pin 0 first. */
    private class InviteFixture(
        scope: TestScope,
    ) {
        val rows = ConcurrentHashMap<String, ChatListRowFfi>()
        val state: WhiteNoiseAppState
        val controller: ChatsController

        init {
            rows[PIN_0] = chatRow(PIN_0).copy(activitySortAt = 10uL, pinned = true, pinnedPosition = 0u)
            rows[PIN_1] = chatRow(PIN_1).copy(activitySortAt = 900uL, pinned = true, pinnedPosition = 1u)
            ORDINARY.forEachIndexed { index, id ->
                rows[id] = chatRow(id).copy(activitySortAt = 800uL - index.toULong())
            }
            val context = ApplicationProvider.getApplicationContext<Context>()
            state =
                WhiteNoiseAppState(
                    context = context,
                    draftStore = DraftStore.forContext(context),
                    accountIdHexResolver = { ACCOUNT_ID },
                    accounts = listOf(AccountSummaryFfi(ACCOUNT, ACCOUNT_ID, true, false, false, true, false)),
                    activeAccountRef = ACCOUNT,
                    initialMarmotRuntime = AppMarmotRuntime("invite-pinned-test", native()),
                    marmotIoDispatcher = StandardTestDispatcher(scope.testScheduler),
                )
            state.liveSubscriptionOverrides.chatList =
                ChatListLiveSubscriptions(
                    openChatListWindow = { _, _ -> error("unused window") },
                    openChats = { _, _ -> error("unused groups") },
                    presentedChatList = { _, _ -> rows.values.map(::presented) },
                    presentedRowByGroup = { _, id -> rows[id.lowercase()]?.let(::presented) },
                )
            controller =
                ChatsController(
                    appState = state,
                    initialAccountRef = ACCOUNT,
                    memberSnapshotLoader = { _, _ -> emptyList() },
                )
            controller.setChatListVisible(false)
            rows.values.forEach(controller::applyChatListRow)
            controller.setChatListVisible(true)
        }

        /** The presented row for [value] with the ordinary row actions. */
        private fun presented(value: ChatListRowFfi) =
            presentedRow(value.groupIdHex).copy(
                row = value,
                actions = noChatRowActions().copy(canPin = true, canUnpin = true, canMarkRead = true),
            )

        /** The native runtime: row reads only; any other call fails loudly. */
        private fun native(): MarmotInterface =
            Proxy.newProxyInstance(
                MarmotInterface::class.java.classLoader,
                arrayOf(MarmotInterface::class.java),
            ) { proxy, method, args ->
                when (method.name.substringBefore('-')) {
                    "chatListRow" -> rows[(args!![1] as String).lowercase()]
                    "listMedia" -> emptyList<Any>()
                    "catchUpAccounts" -> Unit
                    "toString" -> "InvitePinnedNative"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> error("Unexpected invite-pinned native call: ${method.name}")
                }
            } as MarmotInterface
    }
}

private const val ACCOUNT = "invite-pinned-account"
private val ACCOUNT_ID = "b7".repeat(32)
private const val NEWEST = 5_000uL
private val PIN_0 = "%032x".format(0xe000)
private val PIN_1 = "%032x".format(0xe001)
private val INVITE_A = "%032x".format(0xe100)
private val INVITE_B = "%032x".format(0xe101)
private val ORDINARY = (0 until 3).map { "%032x".format(0xe200 + it) }

/** An unpinned pending invite for [id] whose last activity is [activity]. */
private fun invite(
    id: String,
    activity: ULong,
): ChatListRowFfi = chatRow(id).copy(activitySortAt = activity, pendingConfirmation = true)
