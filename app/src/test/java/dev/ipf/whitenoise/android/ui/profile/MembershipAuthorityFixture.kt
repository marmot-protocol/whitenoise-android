package dev.ipf.whitenoise.android.ui.profile

import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.GroupConversationSnapshotFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.whitenoise.android.state.AccountSwitchProfileSeed
import dev.ipf.whitenoise.android.state.AppMarmotRuntime
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ConversationTimelineTestIds
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.groupConversationSnapshotFixture
import dev.ipf.whitenoise.android.state.testGroupMutationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real profile administration entry point with only synchronous native snapshot I/O substituted. */
internal class MembershipAuthorityFixture {
    val failures = ConcurrentHashMap.newKeySet<String>()
    val removedGroups = ConcurrentHashMap.newKeySet<String>()
    val commits = CopyOnWriteArrayList<Pair<String, List<String>>>()
    val actorName = "Local admin"
    val actorNpub = "npub1424242424242424242424242424242424242424242424242424qamrcaj"
    val personNpub = "npub1enxvenxvenxvenxvenxvenxvenxvenxvenxvenxvenxvenxvenxqn2pktz"
    val entered = ConcurrentHashMap<String, CountDownLatch>()
    val releases = ConcurrentHashMap<String, CountDownLatch>()
    val reads = CopyOnWriteArrayList<String>()

    /** Worker threads that ran a native authority read, so a test can tell native I/O from composition work. */
    val nativeThreads: MutableSet<Thread> = ConcurrentHashMap.newKeySet()
    private val authorityEvents = CopyOnWriteArrayList<String>()
    private val authorityTraceStartedAt = System.nanoTime()
    val self = ConversationTimelineTestIds.ACCOUNT_ID
    val person = "cc".repeat(32)
    val account = "membership-authority-owner"
    val fast = personTestGroup("ab".repeat(32), "Fast group", listOf(self), listOf(self, person, "dd".repeat(32)))
    val slow = personTestGroup("ef".repeat(32), "Slow group", listOf(self), listOf(self, person, "dd".repeat(32)))
    val state =
        WhiteNoiseAppState(
            context = ApplicationProvider.getApplicationContext(),
            draftStore = DraftStore.forContext(ApplicationProvider.getApplicationContext()),
            accountIdHexResolver = { self },
            accounts = listOf(AccountSummaryFfi(account, self, true, false, false, true)),
            activeAccountRef = account,
            profileReader = { null },
            profileDisplayNameReader = { if (it == self) actorName else "River" },
            npubPresentationReader = { if (it == self) actorNpub else personNpub },
            profileRefreshRequest = {},
            initialMarmotRuntime = AppMarmotRuntime("membership-authority-test", native()),
            marmotIoDispatcher = Dispatchers.IO,
        )

    init {
        state.applyAccountSwitchProfileSeed(AccountSwitchProfileSeed(self, null, actorName, null))
        state.applyAccountSwitchProfileSeed(AccountSwitchProfileSeed(person, null, "River", null))
    }

    /** An independent authority read can remain pending while another row finishes. */
    fun hold(id: String) {
        entered[id] = CountDownLatch(1)
        releases[id] = CountDownLatch(1)
    }

    /** Release native work before UI disposal and retire only this fixture's mutation jobs. */
    fun close() {
        releases.values.forEach { it.countDown() }
        state.mutationsScope.cancel()
    }

    /** Diagnostic recording cannot change native completion or replace the original failure. */
    fun recordAuthorityEvent(
        id: String,
        event: String,
    ) {
        runCatching {
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - authorityTraceStartedAt)
            val entry =
                "$elapsedMs ms uptime=${SystemClock.uptimeMillis()} id=$id event=$event " +
                    "thread=${Thread.currentThread().name.take(80)} " +
                    "account=${state.activeAccountRef} runtime=${state.runtimeGeneration}"
            synchronized(authorityEvents) {
                if (authorityEvents.size < MAX_AUTHORITY_EVENTS) authorityEvents += entry
            }
        }
    }

    /** Snapshot only fixture-owned observations; no additional native read is requested. */
    fun authorityDiagnostics(): String =
        "reads=${reads.take(MAX_AUTHORITY_EVENTS)} releases=${releases.mapValues { it.value.count }} " +
            "failures=$failures events=${authorityEvents.toList()} main=${Dispatchers.Main}"

    /** Returns a valid joined native frontier for a real admin and non-admin target. */
    private fun snapshot(item: ChatListItem): GroupConversationSnapshotFfi {
        val result = groupConversationSnapshotFixture(item.group.name.orEmpty(), true)
        result.details.group = item.group
        val selfMember =
            result.details.members
                .single()
                .copy(account = account)
        result.details.members =
            listOf(
                selfMember,
                selfMember.copy(memberIdHex = person, account = null, local = false, isAdmin = false, isSelf = false),
                selfMember.copy(
                    memberIdHex = "dd".repeat(32),
                    account = null,
                    local = false,
                    isAdmin = false,
                    isSelf = false,
                ),
            )
        if (item.id in removedGroups) {
            result.details.members = result.details.members.filterNot { it.memberIdHex == person }
        }
        result.details.mlsState =
            result.details.mlsState.copy(
                groupIdHex = item.id,
                memberCount =
                    result.details.members.size
                        .toUInt(),
            )
        return result
    }

    /** Reads the same gated native snapshot on the calling worker, recording that worker as native I/O. */
    private fun readNativeSnapshot(args: Array<out Any?>?): GroupConversationSnapshotFfi {
        check(args!![0] == account)
        val id = args[1] as String
        nativeThreads += Thread.currentThread()
        reads += id
        recordAuthorityEvent(id, "native-enter")
        return try {
            entered[id]?.countDown()
            check(releases[id]?.await(10, TimeUnit.SECONDS) != false) { "Native snapshot gate timed out" }
            check(id !in failures) { "Native authority unavailable" }
            snapshot(listOf(fast, slow).single { it.id == id }).also {
                recordAuthorityEvent(id, "native-return")
            }
        } catch (failure: Throwable) {
            recordAuthorityEvent(id, "native-failure:${failure.javaClass.simpleName}")
            throw failure
        }
    }

    /** Uses the actual MarmotInterface method consumed by WhiteNoiseAppState.profileMembershipController. */
    private fun native(): MarmotInterface =
        Proxy.newProxyInstance(
            MarmotInterface::class.java.classLoader,
            arrayOf(MarmotInterface::class.java),
        ) { proxy, method, args ->
            when (val name = method.name.substringBefore('-')) {
                "groupConversationSnapshot" -> readNativeSnapshot(args)
                "removeMembersDetailed" -> {
                    check(args!![0] == account)
                    val id = args[1] as String

                    @Suppress("UNCHECKED_CAST")
                    val targets = args[2] as List<String>
                    check(targets == listOf(person))
                    commits += id to targets.toList()
                    removedGroups += id
                    val after = snapshot(listOf(fast, slow).single { it.id == id })
                    testGroupMutationResult(
                        after.details.members.map { it.memberIdHex },
                        listOf(self),
                        self,
                        account,
                    ).copy(details = after.details, managementState = after.managementState)
                }
                "npub" ->
                    when (args!![0]) {
                        self -> actorNpub
                        person -> personNpub
                        else -> error("Unexpected identity")
                    }
                "listMedia" -> emptyList<Any>()
                "catchUpAccounts" -> Unit
                "toString" -> "MembershipAuthorityNative"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> error("Unexpected membership authority call: $name")
            }
        } as MarmotInterface

    companion object {
        private const val MAX_AUTHORITY_EVENTS = 32
    }
}
