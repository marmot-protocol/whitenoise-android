package dev.ipf.whitenoise.android.state

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.DeletionSourceFfi
import dev.ipf.marmotkit.Marmot
import dev.ipf.marmotkit.MarmotAndroid
import dev.ipf.marmotkit.MarmotOptions
import dev.ipf.marmotkit.PublicEventPreviewFfi
import dev.ipf.marmotkit.PublicEventPreviewStateFfi
import dev.ipf.marmotkit.RelayPolicyFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelineReactionSummaryFfi
import dev.ipf.marmotkit.verifyPublicNostrEventJson
import dev.ipf.whitenoise.android.PullRequestDeviceSmoke
import dev.ipf.whitenoise.android.core.EMPTY_MARKDOWN_DOCUMENT
import dev.ipf.whitenoise.android.core.NostrEventReferenceDecoder
import dev.ipf.whitenoise.android.ui.conversation.nostr.NostrEventCardResolver
import dev.ipf.whitenoise.android.ui.conversation.nostr.NostrEventCardSnapshots
import dev.ipf.whitenoise.android.ui.conversation.nostr.NostrEventCardState
import dev.ipf.whitenoise.android.ui.conversation.nostr.PublicEventAuthorRefreshThrottle
import dev.ipf.whitenoise.android.ui.conversation.nostr.readCachedPublicEventCards
import dev.ipf.whitenoise.android.ui.conversation.nostr.refreshPublicEventCard
import dev.ipf.whitenoise.android.ui.conversation.nostr.refreshPublicEventCardAuthor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * #2238 against the real generated MarmotKit runtime: a public event card resolved once renders complete on the
 * first value of a later process, offline, from MDK's account-scoped projection alone.
 *
 * The first process resolves a kind-1 note with its author's kind-0 profile, an `naddr` kind-30023 article in an
 * older and a newer version, an event its author deleted with NIP-09 and an event with a bad signature, all served
 * by an in-process loopback relay and signed by a fixture author who is not a local account. It then closes the
 * runtime and the relay. A second runtime over the same root reopens offline, warms a conversation's card
 * snapshots through the production adapter, and the first value each resolver state yields must already be final.
 *
 * MDK owns addressable monotonicity and its own 30-minute addressable refresh window, so an older version seeded
 * after the newer one is checked as a non-regression of the result, whichever of the two MDK rules answers it.
 */
@PullRequestDeviceSmoke
@RunWith(AndroidJUnit4::class)
class NostrEventPreviewProcessRecreationFfiIntegrationTest {
    /** A resolved preview survives process recreation offline and is complete on its first frame. */
    @Test
    @Suppress("LongMethod") // One auditable sequence: resolve online, go offline, reopen, assert, dispose.
    fun processRecreationResolvedNostrPreviewRendersCompleteFirstFrameOffline() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            MarmotAndroid.initialize(context)
            val root = File(context.cacheDir, "nostr-preview-recreation-${UUID.randomUUID()}").apply { mkdirs() }
            val fixture = PreviewFixture()
            fixture.signedEvents.forEach { assertTrue("fixture signature", verifyPublicNostrEventJson(it.toString())) }
            assertFalse(verifyPublicNostrEventJson(fixture.badSignature.toString()))
            val relay = LoopbackNostrRelay()
            val relays = listOf(relay.url)
            val accounts =
                try {
                    withTimeout(ONLINE_TIMEOUT_MS) { resolveOnline(root, relays, relay, fixture) }
                } finally {
                    // From here on no relay answers: the relaunch is offline.
                    relay.close()
                }
            val reopened = Marmot.newWithConfiguration(root.absolutePath, relays, loopbackOptions())
            var receiverJob: Job? = null
            val resolverScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                withTimeout(OFFLINE_TIMEOUT_MS) {
                    val app =
                        WhiteNoiseAppState(
                            context = context,
                            draftStore = DraftStore(EmptyDraftPersistence),
                            accountIdHexResolver = { label ->
                                listOf(accounts.first, accounts.second).firstOrNull { it.label == label }?.accountIdHex
                            },
                            accounts = listOf(accounts.first, accounts.second),
                            activeAccountRef = accounts.first.label,
                            marmotRuntimeFactory = { AppMarmotRuntime(root.absolutePath, reopened) },
                            notificationSubscriber = {
                                receiverJob = currentCoroutineContext()[Job]
                                SilentNotifications
                            },
                            preferences = context.getSharedPreferences(root.name, Context.MODE_PRIVATE),
                        )
                    app.bootstrap()
                    assertOfflineFirstFrame(app, accounts, fixture, resolverScope)
                }
            } finally {
                resolverScope.cancel()
                receiverJob?.cancelAndJoin()
                reopened.shutdownAndClose()
                context.deleteSharedPreferences(root.name)
                root.deleteRecursively()
            }
        }

    /** Resolves every fixture reference as account A while online, and returns accounts A and B. */
    private suspend fun resolveOnline(
        root: File,
        relays: List<String>,
        relay: LoopbackNostrRelay,
        fixture: PreviewFixture,
    ): Pair<AccountSummaryFfi, AccountSummaryFfi> {
        val native = Marmot.newWithConfiguration(root.absolutePath, relays, loopbackOptions())
        try {
            native.start()
            val owner = native.createIdentity(relays, relays)
            val other = native.createIdentity(relays, relays)
            relay.seedEvents(fixture.initialEvents)
            val note = native.resolvePublicEventPreview(owner.label, fixture.noteReference)
            assertEquals(PublicEventPreviewStateFfi.PRESENT, note.state)
            assertEquals(fixture.note.getString("id"), note.eventId())
            val article = native.resolvePublicEventPreview(owner.label, fixture.articleReference)
            assertEquals(PublicEventPreviewStateFfi.PRESENT, article.state)
            assertEquals(fixture.newerArticle.getString("id"), article.eventId())
            // The older version reaches the relay after the newer one: the projection must not move back to it.
            relay.seedEvents(listOf(fixture.olderArticle))
            val again = native.resolvePublicEventPreview(owner.label, fixture.hintedArticleReference(relay.url))
            assertEquals(fixture.newerArticle.getString("id"), again.eventId())
            val deleted = native.resolvePublicEventPreview(owner.label, fixture.deletedReference)
            // An authorized NIP-09 deletion settles the preview.
            assertEquals(PublicEventPreviewStateFfi.DELETED, deleted.state)
            val invalid = native.resolvePublicEventPreview(owner.label, fixture.badSignatureReference)
            assertTrue("a bad signature is never presented", invalid.state != PublicEventPreviewStateFfi.PRESENT)
            native.refreshProfile(fixture.signer.pubkeyHex, relays)
            val profile = native.cachedIdentityProjections(listOf(fixture.signer.pubkeyHex)).single().profile
            assertEquals(AUTHOR_NAME, profile?.displayName ?: profile?.name)
            return owner to other
        } finally {
            native.shutdownAndClose()
        }
    }

    /** Warms snapshots offline through the production path and checks every first value and refresh outcome. */
    private suspend fun assertOfflineFirstFrame(
        app: WhiteNoiseAppState,
        accounts: Pair<AccountSummaryFfi, AccountSummaryFfi>,
        fixture: PreviewFixture,
        scope: CoroutineScope,
    ) {
        val owner = accounts.first.label
        // Account isolation: the other local account never sees what the owner resolved.
        assertEquals(
            List(fixture.cardReferences.size) { null },
            app.readCachedPublicEventCards(accounts.second.label, fixture.cardReferences),
        )
        val snapshots = NostrEventCardSnapshots()
        snapshots.warm(fixture.records(), parseMarkdown = { app.parseMarkdownOrEmpty(it) }) { references ->
            app.readCachedPublicEventCards(owner, references)
        }
        val authorThrottle = PublicEventAuthorRefreshThrottle()
        val resolver =
            NostrEventCardResolver(
                parentScope = scope,
                readCached = { references -> app.readCachedPublicEventCards(owner, references) },
                refresh = { reference -> app.refreshPublicEventCard(owner, reference) },
                refreshAuthor = { loaded -> app.refreshPublicEventCardAuthor(loaded, authorThrottle) },
                snapshots = snapshots,
            )
        try {
            val note = resolver.state(fixture.reference(fixture.noteReference), fixture.noteReference)
            val noteCard = (note.value as? NostrEventCardState.Loaded)?.card
            assertNotNull("the note's first value is its complete cached card", noteCard)
            assertEquals(fixture.note.getString("id"), noteCard?.eventIdHex)
            assertEquals(NOTE_TEXT, noteCard?.summary)
            assertEquals(AUTHOR_NAME, noteCard?.authorMetadata?.displayName)
            // Preview text is prepared before the first frame.
            assertNotNull((note.value as NostrEventCardState.Loaded).preview)

            val article = resolver.state(fixture.reference(fixture.articleReference), fixture.articleReference)
            val articleCard = (article.value as? NostrEventCardState.Loaded)?.card
            assertEquals(fixture.newerArticle.getString("id"), articleCard?.eventIdHex)
            assertEquals(NEWER_TITLE, articleCard?.title)

            val deleted = resolver.state(fixture.reference(fixture.deletedReference), fixture.deletedReference)
            assertEquals(NostrEventCardState.NotFound, deleted.value)

            val invalidReference = fixture.badSignatureReference
            val invalid = resolver.state(fixture.reference(invalidReference), invalidReference)
            val settledInvalid = invalid.first { it != NostrEventCardState.Loading }
            assertFalse("an invalid event never renders as a card", settledInvalid is NostrEventCardState.Loaded)

            // A relay outage keeps the known-good content, both at the adapter and in the presented state.
            val refreshed = app.refreshPublicEventCard(owner, fixture.noteReference)
            assertEquals(fixture.note.getString("id"), (refreshed as? NostrEventCardState.Loaded)?.card?.eventIdHex)
            delay(SETTLE_MS)
            assertEquals(noteCard, (note.value as? NostrEventCardState.Loaded)?.card)
            assertEquals(articleCard, (article.value as? NostrEventCardState.Loaded)?.card)
        } finally {
            resolver.close()
        }
    }

    /** Loopback relays are allowed only in this fixture's native configuration. */
    private fun loopbackOptions() = MarmotOptions(relayPolicy = RelayPolicyFfi.ALLOW_LOOPBACK_RELAYS_AND_BLOBS)

    /** The signed fixture events and the authored references a conversation would contain. */
    private class PreviewFixture {
        val signer = PublicEventFixtureSigner(AUTHOR_SECRET)
        private val profile =
            signer.sign(0, "{\"name\":\"$AUTHOR_NAME\",\"display_name\":\"$AUTHOR_NAME\"}", CREATED_AT)
        val note = signer.sign(1, NOTE_TEXT, CREATED_AT + 1)
        val olderArticle = article(OLDER_TITLE, CREATED_AT + 2)
        val newerArticle = article(NEWER_TITLE, CREATED_AT + 3)
        private val doomed = signer.sign(1, "This note was deleted by its author.", CREATED_AT + 4)
        private val deletion = signer.sign(5, "", CREATED_AT + 5, listOf(listOf("e", doomed.getString("id"))))
        val badSignature =
            signer.sign(1, "A note whose signature does not verify.", CREATED_AT + 6).apply {
                put("sig", "0".repeat(SIGNATURE_HEX_LENGTH))
            }

        /** Everything the relay serves before the first resolve; the older article arrives later. */
        val initialEvents = listOf(profile, note, newerArticle, doomed, deletion, badSignature)
        val signedEvents = listOf(profile, note, olderArticle, newerArticle, doomed, deletion)

        val noteReference = PublicEventFixtureBech32.note(note.getString("id"))
        val articleReference = PublicEventFixtureBech32.naddr(LONG_FORM_KIND, signer.pubkeyHex, ARTICLE_ID)
        val deletedReference = PublicEventFixtureBech32.note(doomed.getString("id"))
        val badSignatureReference = PublicEventFixtureBech32.note(badSignature.getString("id"))
        val cardReferences = listOf(noteReference, articleReference, deletedReference, badSignatureReference)

        /** The same article coordinate with a relay hint, a distinct authored reference for a second resolve. */
        fun hintedArticleReference(relayUrl: String): String =
            PublicEventFixtureBech32.naddr(
                kind = LONG_FORM_KIND,
                authorHex = signer.pubkeyHex,
                identifier = ARTICLE_ID,
                relayHint = relayUrl,
            )

        /** The decoded reference a card keys its state on. */
        fun reference(authored: String) = checkNotNull(NostrEventReferenceDecoder.eventReference(authored))

        /** Two conversation messages that together reference every fixture card. */
        fun records(): List<TimelineMessageRecordFfi> =
            listOf(
                message("first", "nostr:$noteReference nostr:$articleReference nostr:$deletedReference", 2uL),
                message("second", "nostr:$badSignatureReference", 1uL),
            )

        /** A signed long-form article version of the fixture coordinate. */
        private fun article(
            title: String,
            createdAt: Long,
        ): JSONObject =
            signer.sign(
                LONG_FORM_KIND,
                "The body of $title.",
                createdAt,
                listOf(listOf("d", ARTICLE_ID), listOf("title", title)),
            )
    }

    /** Holds the unrelated notification stream open until this test cancels its listener. */
    private object SilentNotifications : AppNotificationSubscription {
        /** Suspends forever, so the stream never yields a notification until cancelled. */
        override suspend fun next(): Nothing = awaitCancellation()

        /** Nothing to release, the stream holds no resources. */
        override fun close() = Unit
    }

    private object EmptyDraftPersistence : DraftPersistence {
        /** Starts every run with no persisted drafts. */
        override fun read(): Map<String, String> = emptyMap()

        /** Discards draft writes, this test never reads them back. */
        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    private companion object {
        const val ONLINE_TIMEOUT_MS = 90_000L
        const val OFFLINE_TIMEOUT_MS = 90_000L
        const val SETTLE_MS = 2_000L
        const val CREATED_AT = 1_765_000_000L
        const val LONG_FORM_KIND = 30_023
        const val SIGNATURE_HEX_LENGTH = 128
        const val ARTICLE_ID = "calmer-conversations"
        const val AUTHOR_NAME = "Fixture Author"
        const val NOTE_TEXT = "A public note referenced from a private conversation."
        const val OLDER_TITLE = "Calmer conversations, first draft"
        const val NEWER_TITLE = "Calmer conversations"

        /** A disposable fixture key; it never signs anything outside this test. */
        const val AUTHOR_SECRET = "3f1c6d0a9b8e7f6a5d4c3b2a19087f6e5d4c3b2a19087f6e5d4c3b2a19087f6e"
    }
}

/** The id of the event MDK attached to a present preview. */
private fun PublicEventPreviewFfi.eventId(): String = JSONObject(checkNotNull(eventJson)).getString("id")

/** A received chat message with [plaintext] whose Markdown has not been hydrated yet. */
private fun message(
    messageIdHex: String,
    plaintext: String,
    timelineAt: ULong,
) = TimelineMessageRecordFfi(
    clientToken = null,
    messageIdHex = messageIdHex,
    sourceMessageIdHex = null,
    direction = "received",
    groupIdHex = "g",
    sender = "s",
    plaintext = plaintext,
    contentTokens = EMPTY_MARKDOWN_DOCUMENT,
    kind = 9uL,
    tags = emptyList(),
    timelineAt = timelineAt,
    receivedAt = timelineAt,
    replyToMessageIdHex = null,
    replyPreview = null,
    mediaJson = null,
    media = emptyList(),
    agentTextStreamJson = null,
    poll = null,
    groupSystem = null,
    hasReports = false,
    edit = null,
    reactions = TimelineReactionSummaryFfi(byEmoji = emptyList(), userReactions = emptyList()),
    deleted = false,
    deletionSource = DeletionSourceFfi.UNKNOWN,
    deletedByMessageIdHex = null,
    invalidationStatus = null,
    sourceEpoch = null,
    retentionSeconds = null,
    retentionExpiresAt = null,
)
