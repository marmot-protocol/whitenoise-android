package dev.ipf.whitenoise.android.state

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.Marmot
import dev.ipf.marmotkit.MarmotAndroid
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.MarmotOptions
import dev.ipf.marmotkit.RelayPolicyFfi
import dev.ipf.marmotkit.TimelineMessageQueryFfi
import dev.ipf.whitenoise.android.ManualDeviceFixture
import dev.ipf.whitenoise.android.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * Device qualification for the MarmotKit snapshot adopted in #3289, against the real generated runtime and an
 * in-process relay that behaves like the deployed ones: it clamps every query to 500 events, refuses NIP-77 history
 * comparison, and can land a commit long after it was created. Each case checks recovered messages, never only an
 * API's return value. Every identity is generated and every root is deleted afterwards.
 *
 * The epoch-gap case must wait out MDK's 120 s replay lookback, so the class takes about five minutes and runs
 * only on request — see "Recovery qualification" in docs/updating-marmotkit.md.
 */
@ManualDeviceFixture
@RunWith(AndroidJUnit4::class)
class MarmotKitRecoveryQualificationTest {
    private lateinit var context: Context
    private lateinit var relay: LoopbackNostrRelay
    private lateinit var relays: List<String>
    private val roots = mutableListOf<File>()
    private val runtimes = mutableListOf<Marmot>()

    /** Starts a relay with the deployed limits for each case. */
    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        MarmotAndroid.initialize(context)
        relay =
            LoopbackNostrRelay().apply {
                requestLimitClamp = RELAY_CLAMP
                refuseComparison = InstrumentationRegistry.getArguments().getString("comparison") != "ignore"
            }
        relays = listOf(relay.url)
    }

    /** Closes every runtime this case opened, then the relay, then deletes the generated roots. */
    @After
    fun tearDown() {
        runBlocking { runtimes.forEach { runCatching { it.shutdownAndClose() } } }
        relay.close()
        roots.forEach(File::deleteRecursively)
    }

    /**
     * A receiver whose runtime is closed while more events accumulate than the relay returns for one query recovers
     * every message once it reopens, because MDK pages backwards past the clamp (MDK #2274). Six senders send 100
     * messages each, so each sender stays within MLS's 100-message reorder tolerance. A device too slow to send them
     * within MDK's two-minute replay lookback meets mdk#2381 instead, as the next case does on purpose.
     */
    @Test
    fun offlineReplayBeyondTheRelayClampRecoversEveryMessage() =
        runBlocking<Unit> {
            assertReplayed(replay = sixSendersOffline(), offlineMillis = argument("offlineMillis")?.toLong() ?: 0L)
        }

    /**
     * The same replay after the receiver stayed offline longer than MDK's two-minute replay lookback. With a relay
     * that cannot compare history, the clamped replay is not paged and the oldest messages never arrive.
     */
    @Ignore("mdk#2381: catch-up after more than two minutes offline skips events beyond the relay's 500 clamp")
    @Test
    fun offlineReplayAfterTheLookbackRecoversEveryMessage() =
        runBlocking<Unit> {
            assertReplayed(replay = sixSendersOffline(), offlineMillis = LONG_OFFLINE_MILLIS)
        }

    /** Closes the receiver, then has six senders send 100 messages each, returning the group and what was sent. */
    private suspend fun sixSendersOffline(): Pair<EstablishedGroup, List<String>> {
        val group = establishedGroup(extraSenders = REPLAY_SENDERS - 1)
        close(group.receiver)
        val perSender = argument("replayMessagesPerSender")?.toInt() ?: REPLAY_MESSAGES_PER_SENDER
        val sent =
            group.senders.flatMapIndexed { index, (account, runtime) ->
                (1..perSender).map { "replay-$index-%03d".format(it) }.onEach {
                    runtime.sendText(account.label, group.id, it)
                }
            }
        return group to sent
    }

    /**
     * One sender's messages beyond MLS's 100-message reorder tolerance are lost on catch-up, because the relay's
     * newest-first results are decrypted newest first. Fails on 0.13.0 and on this snapshot alike.
     */
    @Ignore("mdk#2375: offline catch-up keeps only each sender's newest 100 messages")
    @Test
    fun oneSenderBeyondTheReorderToleranceRecoversEveryMessage() =
        runBlocking<Unit> {
            val group = establishedGroup()
            close(group.receiver)
            val count = argument("replayMessages")?.toInt() ?: SINGLE_SENDER_MESSAGES
            val sent = (1..count).map { "replay-%04d".format(it) }
            sent.forEach { group.sender.runtime.sendText(group.sender.account.label, group.id, it) }
            assertReplayed(group to sent)
        }

    /**
     * After [offlineMillis] more offline, reopens the receiver of [replay]'s group and requires every message sent,
     * having needed to page past the relay clamp. A device offline longer than MDK's replay lookback catches up
     * differently.
     */
    private suspend fun assertReplayed(
        replay: Pair<EstablishedGroup, List<String>>,
        offlineMillis: Long = 0L,
    ) {
        val (group, sent) = replay
        delay(offlineMillis)
        // Only the reopened receiver's own group reads count, not setup traffic or the sender's runtime.
        val requestsBefore = relay.recordedRequests().size
        val receiver = reopen(group.receiver)
        val recoverStarted = System.nanoTime()
        val recovered = awaitTexts(receiver, group.id, sent.toSet(), REPLAY_TIMEOUT_MILLIS)
        val recoverMillis = (System.nanoTime() - recoverStarted) / NANOS_PER_MILLI
        val missing = sent.filterNot { it in recovered }
        assertTrue(
            "recovered ${recovered.size} of ${sent.size}; missing ${missing.size} " +
                "(${missing.firstOrNull()}..${missing.lastOrNull()}); ${diagnose(receiver, group.id)}",
            missing.isEmpty(),
        )
        assertTrue(
            "the receiver never had to page past the relay clamp, so this run proves nothing",
            relay.recordedRequests().drop(requestsBefore).any { it.has("until") && it.queriesKind(GROUP_MESSAGE_KIND) },
        )
        Log.i(TAG, "replay_qualified messages=${sent.size} recover_ms=$recoverMillis")
    }

    /**
     * A commit that reaches the relay only after the receiver's cursor moved past its timestamp is recovered from
     * the relay's newest events when the relay cannot compare history, and the messages after it decrypt (MDK #2298).
     */
    @Test
    fun epochGapIsRecoveredFromTheNewestEventsWhenComparisonFails() =
        runBlocking<Unit> {
            val group = establishedGroup()
            val sender = group.sender
            relay.withholdNextPublicationOfKind = GROUP_MESSAGE_KIND
            sender.runtime.updateGroupProfile(sender.account.label, group.id, RENAMED, null)
            val commit = checkNotNull(relay.withheldEventId()) { "the rename commit was not withheld" }
            val after = (1..POST_COMMIT_MESSAGES).map { "after-commit-$it" }
            after.forEach { sender.runtime.sendText(sender.account.label, group.id, it) }
            // MDK replays the last two minutes on reconnect. Move the receiver's cursor further than that past the
            // commit with traffic it cannot decrypt yet, so only the comparison or its fallback can find the commit.
            delay(argument("lookbackMillis")?.toLong() ?: REPLAY_LOOKBACK_MILLIS)
            val tail = "after-commit-tail"
            sender.runtime.sendText(sender.account.label, group.id, tail)
            delay(UNDECRYPTABLE_SETTLE_MILLIS)
            close(group.receiver)
            relay.releaseWithheld()
            val receiver = reopen(group.receiver)

            val expected = after.toSet() + tail
            val gapTimeout = argument("gapTimeoutMillis")?.toLong() ?: GAP_TIMEOUT_MILLIS
            val recovered = awaitTexts(receiver, group.id, expected, gapTimeout)
            val status =
                runCatching { receiver.runtime.groupRecoveryStatus(receiver.account.label, group.id) }.getOrNull()
            val notices = runCatching { receiver.runtime.historyNotices(receiver.account.label).size }.getOrNull()
            val gapFacts =
                "recovery_failed=${status?.automaticRecoveryFailed} incomplete=${status?.historyMayBeIncomplete} " +
                    "notices=$notices live_group_deliveries=${relay.liveDeliveries[GROUP_MESSAGE_KIND]?.get()}"
            assertEquals(
                "the messages after the late commit must decrypt; $gapFacts; ${diagnose(receiver, group.id)}",
                expected,
                recovered,
            )
            withTimeout(GAP_TIMEOUT_MILLIS) {
                while (receiver.runtime
                        .groupDetails(receiver.account.label, group.id)
                        .group.name != RENAMED
                ) {
                    delay(POLL_MILLIS)
                }
            }
            val failedComparisons = relay.refusedComparisons() + relay.ignoredComparisons()
            assertTrue("the relay never failed a comparison", failedComparisons > 0)
            val windows = relay.queriesThatReturned(commit).filter { filters -> filters.any { it.has("limit") } }
            assertTrue("the late commit did not come from a newest-events window", windows.isNotEmpty())
            Log.i(TAG, "gap_qualified refused_comparisons=${relay.refusedComparisons()} windows=${windows.size}")
        }

    /**
     * Once an admin's device knows its group moved on without it, MDK refuses that admin's group change as catching up,
     * and after one acknowledgement the same change goes through (MDK #2273). This is the refusal runGroupChange turns
     * into the app-level question. The receiver's newest-events query is the signal that MDK recorded the gap, so no
     * change is attempted before it; the acknowledged change then commits on the device's own epoch, which is fine for
     * a disposable group.
     */
    @Test
    fun catchingUpRefusalIsAcknowledgedForOneChange() =
        runBlocking<Unit> {
            val group = establishedGroup()
            val sender = group.sender
            val receiver = group.receiver
            sender.runtime.promoteAdminDetailed(sender.account.label, group.id, receiver.account.accountIdHex)
            awaitAdmin(receiver, group.id)
            val queriesBefore = relay.answeredQueries(GROUP_MESSAGE_KIND).size

            relay.withholdNextPublicationOfKind = GROUP_MESSAGE_KIND
            sender.runtime.updateGroupProfile(sender.account.label, group.id, RENAMED, null)
            checkNotNull(relay.withheldEventId()) { "the rename commit was not withheld" }
            (1..POST_COMMIT_MESSAGES).forEach { sender.runtime.sendText(sender.account.label, group.id, "ahead-$it") }
            val gapFetched =
                withTimeoutOrNull(CATCHING_UP_TIMEOUT_MILLIS) {
                    while (relay.answeredQueries(GROUP_MESSAGE_KIND).drop(queriesBefore).none { (filters, _) ->
                            filters.any { it.has("limit") && it.has("until") }
                        }
                    ) {
                        delay(POLL_MILLIS)
                    }
                    true
                }
            assertTrue(
                "the receiver never looked for the missing commit: ${diagnose(receiver, group.id)}",
                gapFetched == true,
            )

            val label = receiver.account.label
            val rename = suspend { receiver.runtime.updateGroupProfile(label, group.id, BEHIND_NAME, null) }
            val refusal = runCatching { rename() }.exceptionOrNull()
            assertTrue("a change while behind was not refused as catching up: $refusal", refusal.isCatchingUp())
            assertTrue(
                "acknowledging must find the group catching up",
                receiver.runtime.acknowledgeGroupCatchingUp(receiver.account.label, group.id),
            )
            rename()
            assertEquals(
                BEHIND_NAME,
                receiver.runtime
                    .groupDetails(receiver.account.label, group.id)
                    .group.name,
            )
            Log.i(TAG, "catching_up_qualified")
        }

    /**
     * A recipient lookup that cannot finish is reported as incomplete, never as missing setup, and retrying the same
     * creation afterwards makes exactly one group (MDK #2308).
     */
    @Test
    fun retryAfterAnIncompleteLookupCreatesExactlyOneGroup() =
        runBlocking<Unit> {
            val sender = participant("lookup-sender")
            val recipient = participant("lookup-recipient")
            relay.authRequiredKinds = KEY_PACKAGE_KINDS
            val failure =
                runCatching {
                    createLookupGroup(sender, recipient)
                }.exceptionOrNull()
            assertTrue("$failure", failure is MarmotKitException.DirectoryLookupIncomplete)
            assertEquals(
                AppText.Resource(R.string.error_directory_lookup_incomplete_for, listOf("Recipient")),
                inviteFailureDetail(checkNotNull(failure), { "Recipient" }),
            )
            assertTrue(sender.runtime.chatList(sender.account.label, true).isEmpty())

            relay.authRequiredKinds = emptySet()
            val created = createLookupGroup(sender, recipient)
            withTimeout(INVITE_TIMEOUT_MILLIS) {
                while (recipient.runtime.chatList(recipient.account.label, true).isEmpty()) delay(POLL_MILLIS)
            }
            delay(DUPLICATE_SETTLE_MILLIS)
            assertEquals(listOf(created), sender.runtime.chatList(sender.account.label, true).map { it.groupIdHex })
            assertEquals(
                listOf(created),
                recipient.runtime.chatList(recipient.account.label, true).map { it.groupIdHex },
            )
        }

    /** An optional instrumentation argument, so a debugging run can use smaller counts and waits. */
    private fun argument(name: String): String? = InstrumentationRegistry.getArguments().getString(name)

    /** One generated account in its own runtime and root, so it can go offline independently. */
    private class Participant(
        val root: File,
        val runtime: Marmot,
        val account: AccountSummaryFfi,
    )

    /** A sender and a receiver who share one group, with the receiver's first message already decrypted. */
    private class EstablishedGroup(
        val id: String,
        val sender: Participant,
        val receiver: Participant,
        val extraSenders: List<AccountSummaryFfi>,
    ) {
        /** Every sending account with the runtime that holds it. */
        val senders: List<Pair<AccountSummaryFfi, Marmot>>
            get() = listOf(sender.account to sender.runtime) + extraSenders.map { it to sender.runtime }
    }

    /**
     * Creates the sender, the receiver and [extraSenders] further members in the sender's runtime, and waits until
     * the receiver reads the first message.
     */
    private suspend fun establishedGroup(extraSenders: Int = 0): EstablishedGroup {
        val sender = participant("sender")
        val receiver = participant("receiver")
        val others = List(extraSenders) { sender.runtime.createIdentity(relays, relays) }
        val members = listOf(receiver.account.accountIdHex) + others.map { it.accountIdHex }
        val id = sender.runtime.createGroup(sender.account.label, "Recovery", members, null)
        withTimeout(INVITE_TIMEOUT_MILLIS) {
            while (runCatching { receiver.runtime.acceptGroupInvite(receiver.account.label, id) }.isFailure) {
                delay(POLL_MILLIS)
            }
            others.forEach { other ->
                while (runCatching { sender.runtime.acceptGroupInvite(other.label, id) }.isFailure) delay(POLL_MILLIS)
            }
        }
        sender.runtime.sendText(sender.account.label, id, FIRST_MESSAGE)
        val first = awaitTexts(receiver, id, setOf(FIRST_MESSAGE), INVITE_TIMEOUT_MILLIS)
        check(first == setOf(FIRST_MESSAGE)) { "the receiver never read the first message: ${diagnose(receiver, id)}" }
        return EstablishedGroup(id, sender, receiver, others)
    }

    /** Waits until [participant]'s own projection lists it as an admin of [groupId]. */
    private suspend fun awaitAdmin(
        participant: Participant,
        groupId: String,
    ) {
        withTimeout(INVITE_TIMEOUT_MILLIS) {
            val self = participant.account.accountIdHex
            while (participant.runtime
                    .groupDetails(participant.account.label, groupId)
                    .group.admins
                    .none { it.equals(self, ignoreCase = true) }
            ) {
                delay(POLL_MILLIS)
            }
        }
    }

    /** Asks [sender]'s runtime to create the lookup case's group with [recipient]. */
    private suspend fun createLookupGroup(
        sender: Participant,
        recipient: Participant,
    ): String {
        val members = listOf(recipient.account.accountIdHex)
        return sender.runtime.createGroup(sender.account.label, "Lookup", members, null)
    }

    /** True for MDK's refusal of a group change while the device may be behind its group. */
    private fun Throwable?.isCatchingUp(): Boolean = this is MarmotKitException.GroupCatchingUp

    /** True when this request filter asks for [kind]. */
    private fun JSONObject.queriesKind(kind: Int): Boolean {
        val kinds = optJSONArray("kinds") ?: return false
        return (0 until kinds.length()).any { kinds.getInt(it) == kind }
    }

    /** Opens a fresh root and runtime with one generated identity. */
    private suspend fun participant(role: String): Participant {
        val root = File(context.cacheDir, "recovery-qualification-$role-${UUID.randomUUID()}").apply { mkdirs() }
        roots += root
        val runtime = open(root)
        return Participant(root, runtime, runtime.createIdentity(relays, relays))
    }

    /** Opens and starts a loopback-only runtime on [root]. */
    private suspend fun open(root: File): Marmot =
        Marmot.newWithConfiguration(root.absolutePath, relays, OPTIONS).also {
            runtimes += it
            it.start()
        }

    /** Takes [participant] offline, the way a stopped process is. */
    private suspend fun close(participant: Participant) {
        runtimes -= participant.runtime
        participant.runtime.shutdownAndClose()
    }

    /** Reopens [participant]'s root, the way a relaunched process does. */
    private suspend fun reopen(participant: Participant): Participant {
        val runtime = open(participant.root)
        return Participant(participant.root, runtime, participant.account)
    }

    /**
     * Waits until [participant]'s timeline holds every text in [expected], and returns the expected texts it holds
     * when the time is up, so a partial recovery fails with what was missing.
     */
    private suspend fun awaitTexts(
        participant: Participant,
        group: String,
        expected: Set<String>,
        timeoutMillis: Long,
    ): Set<String> {
        var seen = emptySet<String>()
        withTimeoutOrNull(timeoutMillis) {
            while (true) {
                seen = timelineTexts(participant, group).intersect(expected)
                if (seen == expected) break
                delay(POLL_MILLIS)
            }
        }
        return seen
    }

    /** Closed facts about what [participant] and the relay hold for [group], for a failure message. */
    private fun diagnose(
        participant: Participant,
        group: String,
    ): String {
        val page =
            runCatching {
                participant.runtime.timelineMessages(
                    participant.account.label,
                    TimelineMessageQueryFfi(group, null, null, null, null, null, PAGE),
                )
            }
        val requests = relay.recordedRequests()
        return "timeline=${page.map { it.messages.size }.getOrElse { it.javaClass.simpleName }} " +
            "more=${page.map { it.hasMoreBefore }.getOrNull()} " +
            "kinds=${page.map { p -> p.messages.map { it.kind }.distinct() }.getOrNull()} " +
            "group_published=${relay.publicationAttempts(GROUP_MESSAGE_KIND).size} " +
            "group_stored=${relay.recordedEvents(GROUP_MESSAGE_KIND).size} " +
            "requests=${requests.size} until=${requests.count { it.has("until") }} " +
            "limited=${requests.count { it.has("limit") }} refused=${relay.refusedComparisons()} " +
            "ignored=${relay.ignoredComparisons()} " +
            "group_queries=${relay.answeredQueries(GROUP_MESSAGE_KIND).joinToString(";") { (filters, returned) ->
                filters.joinToString("|") { f ->
                    "since=${f.optLong("since")},until=${f.optLong("until")},limit=${f.optInt("limit")}"
                } + "->$returned"
            }}"
    }

    /** Every decrypted text in [group]'s timeline, read page by page from the newest. */
    private fun timelineTexts(
        participant: Participant,
        group: String,
    ): Set<String> {
        val texts = mutableSetOf<String>()
        var before: ULong? = null
        var beforeId: String? = null
        var more = true
        while (more) {
            val page =
                participant.runtime.timelineMessages(
                    participant.account.label,
                    TimelineMessageQueryFfi(group, null, before, beforeId, null, null, PAGE),
                )
            page.messages.forEach { texts += it.plaintext }
            // MDK's cursor orders by time and then id, as the transcript export pages it; many sends share a second.
            val oldest = page.messages.minWithOrNull(compareBy({ it.timelineAt }, { it.messageIdHex }))
            val progressed = oldest != null && (oldest.timelineAt != before || oldest.messageIdHex != beforeId)
            more = page.hasMoreBefore && progressed
            before = oldest?.timelineAt
            beforeId = oldest?.messageIdHex
        }
        return texts
    }

    private companion object {
        const val TAG = "MarmotKitQualification"
        const val RELAY_CLAMP = 500
        const val REPLAY_SENDERS = 6
        const val REPLAY_MESSAGES_PER_SENDER = 100
        const val BEHIND_NAME = "Renamed while behind"
        const val LONG_OFFLINE_MILLIS = 150_000L
        const val CATCHING_UP_TIMEOUT_MILLIS = 60_000L
        const val SINGLE_SENDER_MESSAGES = 600

        /** More than the 8 distinct undecryptable envelopes MDK's epoch-stall detector needs to arm recovery. */
        const val POST_COMMIT_MESSAGES = 10
        const val GROUP_MESSAGE_KIND = 445
        val KEY_PACKAGE_KINDS = setOf(443, 30443)
        const val RENAMED = "Recovered name"
        const val FIRST_MESSAGE = "first"
        const val PAGE = 200u
        const val INVITE_TIMEOUT_MILLIS = 60_000L
        const val REPLAY_TIMEOUT_MILLIS = 300_000L
        const val GAP_TIMEOUT_MILLIS = 180_000L
        const val UNDECRYPTABLE_SETTLE_MILLIS = 3_000L

        /** Longer than MDK's 120-second relay rebuild lookback. */
        const val REPLAY_LOOKBACK_MILLIS = 125_000L
        const val DUPLICATE_SETTLE_MILLIS = 3_000L
        const val POLL_MILLIS = 250L
        const val NANOS_PER_MILLI = 1_000_000L
        val OPTIONS = MarmotOptions(relayPolicy = RelayPolicyFfi.ALLOW_LOOPBACK_RELAYS_AND_BLOBS)
    }
}
