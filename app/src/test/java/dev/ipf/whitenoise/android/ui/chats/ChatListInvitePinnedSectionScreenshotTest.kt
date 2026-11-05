package dev.ipf.whitenoise.android.ui.chats

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.PresentationTextFfi
import dev.ipf.marmotkit.PresentedChatRowFfi
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.lang.reflect.Proxy
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Visual contract for #3579 on the mounted production chat list: the pinned section comes first, one
 * divider follows the last pin, and a pending invite heads the unpinned rows. The ordering itself is
 * gated by [ChatListInvitePinnedSectionCoverageTest]; these baselines pin how the mixed section reads.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
@OptIn(ExperimentalCoroutinesApi::class)
class ChatListInvitePinnedSectionScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val scope = TestScope()
    private var fixture: InvitePinnedFixture? = null
    private var originalTimeZone: TimeZone? = null

    /** Pins the row timestamps to UTC so the baselines do not depend on the host zone. */
    @Before fun stableTimeZone() {
        originalTimeZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    /** Clears the controller and the test scope, then restores the host time zone. */
    @After fun close() {
        rule.runOnIdle { fixture?.controller?.onCleared() }
        scope.cancel()
        originalTimeZone?.let(TimeZone::setDefault)
    }

    /** Light theme: two pins, the divider, then the invite ahead of the ordinary rows. */
    @Test fun invitePinnedSectionLight() = capture("light")

    /** Dark theme keeps the pins, divider and invite legible against the dark list. */
    @Test fun invitePinnedSectionDark() = capture("dark", dark = true)

    /** AMOLED keeps the same section boundary on the pure-black list. */
    @Test fun invitePinnedSectionAmoled() = capture("amoled", dark = true, amoled = true)

    /** RTL at 200% text mirrors the rows while the divider stays between the pins and the invite. */
    @Test fun invitePinnedSectionLargeFontRtl() = capture("large_font_rtl", rtl = true, fontScale = 2f)

    /** Mounts the list, delivers one invite as a native row update would, and records the settled frame. */
    private fun capture(
        variant: String,
        dark: Boolean = false,
        amoled: Boolean = false,
        rtl: Boolean = false,
        fontScale: Float = 1f,
    ) {
        val model = InvitePinnedFixture(scope).also { fixture = it }
        rule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = amoled, fontScale = fontScale) {
                    ChatsScreen(model.state, model.controller, {}, { _, _, _, _ -> })
                }
            }
        }
        settle()
        val invite = chatRow(INVITE).copy(title = INVITE_TITLE, activitySortAt = NEWEST, pendingConfirmation = true)
        model.rows[INVITE] = invite
        rule.runOnIdle { model.controller.applyChatListRow(invite) }
        settle()
        rule.onAllNodesWithTag(CHAT_LIST_PINNED_BOUNDARY_TAG).assertCountEquals(1)
        listOf(PIN_0, PIN_1, INVITE).forEach { rule.onNodeWithTag("chat.row.$it").assertIsDisplayed() }
        rule.onRoot().captureRoboImage("src/test/snapshots/chat_list_invite_pinned_section_$variant.png")
    }

    /** Runs pending test-scheduler work and Android Main twice, then waits for Compose to idle. */
    private fun settle() {
        repeat(2) {
            scope.testScheduler.runCurrent()
            ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS)
            rule.waitForIdle()
        }
    }

    /** Two manual-order pins and three ordinary chats with readable titles. */
    private class InvitePinnedFixture(
        scope: TestScope,
    ) {
        val rows = ConcurrentHashMap<String, ChatListRowFfi>()
        val state: WhiteNoiseAppState
        val controller: ChatsController

        init {
            rows[PIN_0] =
                chatRow(PIN_0).copy(title = "Family", activitySortAt = 10uL, pinned = true, pinnedPosition = 0u)
            rows[PIN_1] =
                chatRow(PIN_1).copy(
                    title = "Weekend hikers",
                    activitySortAt = 900uL,
                    pinned = true,
                    pinnedPosition = 1u,
                )
            ORDINARY.forEachIndexed { index, (id, title) ->
                rows[id] = chatRow(id).copy(title = title, activitySortAt = 800uL - index.toULong())
            }
            val context = ApplicationProvider.getApplicationContext<Context>()
            state =
                WhiteNoiseAppState(
                    context = context,
                    draftStore = DraftStore.forContext(context),
                    accountIdHexResolver = { ACCOUNT_ID },
                    accounts = listOf(AccountSummaryFfi(ACCOUNT, ACCOUNT_ID, true, false, false, true, false)),
                    activeAccountRef = ACCOUNT,
                    initialMarmotRuntime = AppMarmotRuntime("invite-pinned-screenshot", native()),
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

        /** The presented row for [value], titled by its row title, with the ordinary row actions. */
        private fun presented(value: ChatListRowFfi): PresentedChatRowFfi {
            val base = presentedRow(value.groupIdHex)
            return base.copy(
                row = value,
                presentation = base.presentation.copy(title = PresentationTextFfi.Literal(value.title)),
                actions = noChatRowActions().copy(canPin = true, canUnpin = true, canMarkRead = true),
            )
        }

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
                    "toString" -> "InvitePinnedScreenshotNative"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> error("Unexpected invite-pinned native call: ${method.name}")
                }
            } as MarmotInterface
    }
}

private const val ACCOUNT = "invite-pinned-screenshot-account"
private val ACCOUNT_ID = "c8".repeat(32)
private const val NEWEST = 5_000uL
private const val INVITE_TITLE = "Book club"
private val PIN_0 = "%032x".format(0xf000)
private val PIN_1 = "%032x".format(0xf001)
private val INVITE = "%032x".format(0xf100)
private val ORDINARY =
    listOf("Design review", "Climbing partners", "Neighbours").mapIndexed { index, title ->
        "%032x".format(0xf200 + index) to title
    }
