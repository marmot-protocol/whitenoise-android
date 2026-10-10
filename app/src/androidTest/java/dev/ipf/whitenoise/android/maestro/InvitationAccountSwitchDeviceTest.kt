package dev.ipf.whitenoise.android.maestro

import android.content.Context
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.CreateGroupOptionsFfi
import dev.ipf.marmotkit.GroupMutationResultFfi
import dev.ipf.marmotkit.Marmot
import dev.ipf.marmotkit.MarmotAndroid
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.MarmotOptions
import dev.ipf.marmotkit.RelayPolicyFfi
import dev.ipf.whitenoise.android.MainActivity
import dev.ipf.whitenoise.android.ManualDeviceFixture
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AppMarmotRuntime
import dev.ipf.whitenoise.android.state.AppPhase
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.LoopbackNostrRelay
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real MainActivity and native invitation commands, using only isolated accounts and loopback traffic. */
@ManualDeviceFixture
class InvitationAccountSwitchDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    /** Runs one journey per instrumentation process so production process owners cannot outlive a fixture swap. */
    @Test fun accountSwitchDuringDiscoveryThenExplicitRetry() =
        runBlocking {
            val mode = InstrumentationRegistry.getArguments().getString("invitationMode")
            check(mode == "create" || mode == "add") { "Select invitationMode=create or add in a fresh process" }
            journey(mode == "add")
        }

    /** Opens disposable native runtimes and mounts the actual app, without changing the user's Dev package. */
    private suspend fun journey(add: Boolean) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == MaestroFixtureRunner.FIXTURE_PACKAGE)
        check(InstrumentationRegistry.getArguments().getString("invitationSwitchE2e") == "true")
        val root = File(context.filesDir, "invitation-switch-${UUID.randomUUID()}").apply { mkdirs() }
        MarmotAndroid.initialize(context)
        LoopbackNostrRelay().use { discovery ->
            LoopbackNostrRelay().use { advertised ->
                advertised.hiddenKinds = setOf(30443)
                val native = openNative(File(root, "owner"), discovery.url)
                val peer = openNative(File(root, "peer"), discovery.url)
                var app: WhiteNoiseAppState? = null
                var activity: ActivityScenario<MainActivity>? = null
                try {
                    val accounts =
                        listOf(
                            native.createIdentity(listOf(discovery.url), listOf(discovery.url)),
                            native.createIdentity(listOf(discovery.url), listOf(discovery.url)),
                        )
                    val recipient = peer.createIdentity(listOf(discovery.url), listOf(discovery.url))
                    delay(1100)
                    val discoveryRoutes = listOf(discovery.url)
                    peer.publishRelayLists(recipient.label, listOf(advertised.url), discoveryRoutes, discoveryRoutes)
                    val group =
                        if (add) native.createGroup(accounts[0].label, "Pixel invitation", emptyList(), null) else null
                    val command = ObservedInvitationCommands(native)
                    val state = createState(context, root, native, command, accounts)
                    app = state
                    (context.applicationContext as MaestroFixtureApplication).fixtureState = state
                    withTimeout(30_000) { state.bootstrap() }
                    assertEquals(AppPhase.Ready, state.phase)
                    activity = ActivityScenario.launch(MainActivity::class.java)
                    verifySwitchAndRetry(state, native, peer, accounts, recipient, discovery, command, group)
                } finally {
                    discovery.packageReadHold?.close()
                    activity?.close()
                    closeState(app)
                    withContext(NonCancellable) {
                        try {
                            native.shutdownAndClose()
                        } finally {
                            peer.shutdownAndClose()
                        }
                    }
                    context.deleteSharedPreferences(root.name)
                    check(root.deleteRecursively())
                }
            }
        }
    }

    /** Records actual native invocation completion so a delayed response cannot be mistaken for a finished test. */
    private class ObservedInvitationCommands(
        private val native: Marmot,
    ) : MarmotInterface by native {
        var started = CompletableDeferred<Unit>()
        var finished = CompletableDeferred<Unit>()

        override suspend fun createGroupWithOptions(
            accountRef: String,
            name: String,
            memberRefs: List<String>,
            options: CreateGroupOptionsFfi,
        ): String = observe { native.createGroupWithOptions(accountRef, name, memberRefs, options) }

        override suspend fun inviteMembersDetailed(
            accountRef: String,
            groupIdHex: String,
            memberRefs: List<String>,
        ): GroupMutationResultFfi = observe { native.inviteMembersDetailed(accountRef, groupIdHex, memberRefs) }

        /** Delegates unchanged native commands while exposing their start and cancellation/completion boundary. */
        private suspend fun <T> observe(block: suspend () -> T): T {
            started.complete(Unit)
            return try {
                block()
            } finally {
                finished.complete(Unit)
            }
        }

        /** Creates a fresh observation ticket for the explicit second submission. */
        fun reset() {
            started = CompletableDeferred()
            finished = CompletableDeferred()
        }
    }

    /** Uses the real account selection API while discovery responses are held at the relay. */
    private suspend fun verifySwitchAndRetry(
        app: WhiteNoiseAppState,
        native: Marmot,
        peer: Marmot,
        accounts: List<AccountSummaryFfi>,
        recipient: AccountSummaryFfi,
        relay: LoopbackNostrRelay,
        command: ObservedInvitationCommands,
        group: String?,
    ) {
        val owner = accounts[0].label
        val other = accounts[1].label
        val before = native.chatList(owner, true).map { it.groupIdHex }.toSet()
        val welcomeCount = relay.publicationAttempts(1059).size
        openSubmission(requireNotNull(peer.npub(recipient.accountIdHex)), recipient.accountIdHex, group)
        val hold = LoopbackNostrRelay.PackageReadHold(recipient.accountIdHex)
        relay.packageReadHold = hold
        submit(group)
        withTimeout(30_000) { command.started.await() }
        compose.waitUntil(30_000) { hold.observed.count == 0L }
        withContext(Dispatchers.Main.immediate) { assertTrue(app.setActiveAccount(other)) }
        assertEquals(other, app.activeAccountRef)
        relay.authRequiredKinds = setOf(30443)
        hold.close()
        relay.packageReadHold = null
        withTimeout(30_000) {
            command.finished.await()
            app.mutationsScope.coroutineContext[Job]
                ?.children
                ?.toList()
                ?.forEach { it.join() }
        }
        compose.waitForIdle()
        assertNull("Old account failure must not appear on the replacement account", app.toast)
        assertEquals(other, app.activeAccountRef)
        assertTrue(native.chatList(other, true).isEmpty())
        assertEquals(before, native.chatList(owner, true).map { it.groupIdHex }.toSet())
        assertEquals(welcomeCount, relay.publicationAttempts(1059).size)
        if (group != null) assertEquals(1, native.groupMembers(owner, group).size)
        relay.authRequiredKinds = emptySet()
        withContext(Dispatchers.Main.immediate) { assertTrue(app.setActiveAccount(owner)) }
        command.reset()
        openSubmission(requireNotNull(peer.npub(recipient.accountIdHex)), recipient.accountIdHex, group)
        submit(group)
        withTimeout(45_000) {
            command.started.await()
            command.finished.await()
        }
        val acceptedGroup = group ?: native.chatList(owner, true).single().groupIdHex
        assertEquals(2, native.groupMembers(owner, acceptedGroup).size)
        withTimeout(30_000) {
            while (runCatching { peer.acceptGroupInvite(recipient.label, acceptedGroup) }.isFailure) delay(100)
        }
        assertTrue(peer.chatList(recipient.label, true).any { it.groupIdHex == acceptedGroup })
        assertTrue(native.chatList(other, true).isEmpty())
    }

    /** Selects the synthetic recipient using public Create/Add controls in MainActivity. */
    private fun openSubmission(
        npub: String,
        recipient: String,
        group: String?,
    ) {
        if (group == null) {
            val newChat = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.new_chat)
            try {
                compose.waitUntil(10_000) { compose.onAllNodesWithText(newChat).fetchSemanticsNodes().isNotEmpty() }
            } catch (error: Throwable) {
                throw AssertionError(compose.onRoot().printToString(), error)
            }
            compose.onNodeWithText(newChat).performClick()
            compose.onNodeWithTag("performance.new_group").performClick()
            compose.onNodeWithTag("new_group.search").performTextInput(npub)
            waitTag("new_group.person.$recipient")
            compose.onNodeWithTag("new_group.person.$recipient").performClick()
            compose.onNodeWithTag("new_group.continue").performClick()
            compose.onNodeWithTag("group_setup.name").performTextReplacement("Pixel invitation")
        } else {
            waitTag("chat.row.$group")
            compose.onNodeWithTag("chat.row.$group").performClick()
            waitTag("performance.open_group_details")
            compose.onNodeWithTag("performance.open_group_details").performClick()
            waitTag("chat_info.add_people")
            compose.onNodeWithTag("chat_info.add_people").performScrollTo().performClick()
            compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput(npub)
            val selected =
                InstrumentationRegistry
                    .getInstrumentation()
                    .targetContext.resources
                    .getQuantityString(R.plurals.selected_members_count, 1, 1)
            compose.waitUntil(30_000) { compose.onAllNodesWithText(selected).fetchSemanticsNodes().isNotEmpty() }
        }
    }

    /** Commits only after selection, allowing the test to place a real network barrier immediately beforehand. */
    private fun submit(group: String?) {
        compose
            .onNodeWithTag(if (group == null) "group_setup.create" else "performance.contact_picker_next")
            .performClick()
    }

    /** Bounds waiting for a production screen rather than assuming animation or native lookup timing. */
    private fun waitTag(tag: String) {
        compose.waitUntil(30_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    /** Creates a real account-bound state with background push disabled only in the isolated fixture. */
    private suspend fun createState(
        context: Context,
        root: File,
        native: Marmot,
        transport: MarmotInterface,
        accounts: List<AccountSummaryFfi>,
    ) = withContext(Dispatchers.Main.immediate) {
        WhiteNoiseAppState(
            context,
            DraftStore.forContext(context),
            { ref -> accounts.firstOrNull { it.label == ref }?.accountIdHex },
            accounts,
            accounts[0].label,
            profileReader = { id -> withContext(Dispatchers.IO) { native.userProfile(id) } },
            profileRefreshRequest = { id -> withContext(Dispatchers.IO) { native.refreshProfile(id, emptyList()) } },
            marmotRuntimeFactory = { AppMarmotRuntime(File(root, "owner").path, transport) },
            schedulePushWakeRecovery = { false },
            preferences = context.getSharedPreferences(root.name, Context.MODE_PRIVATE),
        )
    }

    /** Starts a real native client whose endpoints are restricted to the loopback fixture. */
    private suspend fun openNative(
        root: File,
        relay: String,
    ): Marmot =
        Marmot
            .newWithConfiguration(
                root.path,
                listOf(relay),
                MarmotOptions(relayPolicy = RelayPolicyFfi.ALLOW_LOOPBACK_RELAYS_AND_BLOBS),
            ).also { it.start() }

    /** Drains all fixture jobs before native storage is released. */
    private suspend fun closeState(app: WhiteNoiseAppState?) =
        withContext(NonCancellable) {
            withTimeout(15_000) {
                withContext(Dispatchers.Main.immediate) { app?.accountSetup?.close() }
                app?.stopNotificationListenerForAccountTeardown()
                app
                    ?.mutationsScope
                    ?.coroutineContext
                    ?.get(Job)
                    ?.cancelAndJoin()
            }
        }
}
