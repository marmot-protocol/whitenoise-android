package dev.ipf.whitenoise.android.maestro

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.marmotkit.Marmot
import dev.ipf.whitenoise.android.MainActivity
import dev.ipf.whitenoise.android.share.PRIVATE_SHARE_DIRECTORY
import dev.ipf.whitenoise.android.share.ShareImportError
import dev.ipf.whitenoise.android.share.ShareRequest
import dev.ipf.whitenoise.android.share.createPendingShareRequestStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.conversation.stagedDocumentAttachmentId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

internal const val MAESTRO_SHARE_TEXT = "  Maestro café 👋\nsecond line  "

/** Enter through Android's real inbound Intent parser and importer, never an injected rendered payload. */
internal fun maestroRuntimeLaunchIntent(
    context: Context,
    fixture: String,
): Intent {
    val intent = Intent(context, MainActivity::class.java)
    if (!fixture.startsWith("share-") || fixture.startsWith("share-external-")) return intent
    intent.action = Intent.ACTION_SEND
    intent.type = "text/plain"
    when (fixture) {
        "share-invalid-owned" ->
            intent.putExtra(Intent.EXTRA_STREAM, Uri.parse("content://${context.packageName}.private-share/fixture"))
        "share-invalid-http" -> intent.putExtra(Intent.EXTRA_STREAM, Uri.parse("https://example.invalid/maestro"))
        "share-partial" -> {
            intent.putExtra(Intent.EXTRA_STREAM, Uri.parse("https://example.invalid/maestro"))
            intent.putExtra(Intent.EXTRA_TEXT, MAESTRO_SHARE_TEXT)
        }
        "share-text" -> intent.putExtra(Intent.EXTRA_TEXT, MAESTRO_SHARE_TEXT)
        else -> error("Unknown inbound-share fixture")
    }
    return intent
}

/** Require the actual completed importer result and its encrypted recovery entry before UI handoff. */
internal suspend fun captureMaestroInboundShare(
    native: Marmot,
    state: WhiteNoiseAppState,
    activity: MaestroActivityOwner,
    group: String,
    fixture: String,
): MaestroInboundShareBaseline? {
    if (!fixture.startsWith("share-")) return null
    val accounts = withContext(Dispatchers.Main.immediate) { state.accounts.toList() }
    val owner = withContext(Dispatchers.Main.immediate) { checkNotNull(state.activeAccountRef) }
    val messages =
        withContext(Dispatchers.IO) {
            accounts
                .map { it.label }
                .filter { native.presentedChatListRow(it, group) != null }
                .associateWith { account ->
                    maestroSharedMessageSnapshot(native, account, group)
                }
        }
    check(accounts.size == 3 && messages.size == 2 && owner in messages)
    check(messages.values.all { timeline -> timeline.any { it.text == "Generated fixture message" && !it.deleted } })
    check(withContext(Dispatchers.IO) { messages.keys.all { native.messageDraft(it, group) == null } })
    val request = awaitMaestroImportedShare(activity)
    val expectedText = maestroExpectedShareText(fixture)
    val expectedErrors = maestroExpectedShareErrors(fixture)
    check(request.payload.text == expectedText)
    check(request.payload.importErrors == expectedErrors)
    check(request.payload.importRejectedCount == expectedErrors.size)
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    withContext(Dispatchers.IO) {
        check(createPendingShareRequestStore(context).load(request.requestId) == request)
    }
    val files = captureMaestroShareFiles(context, request, fixture)
    return MaestroInboundShareBaseline(
        owner,
        accounts.associate { it.label to it.accountIdHex },
        group,
        request.requestId,
        messages,
        expectedText,
        files,
    )
}

private suspend fun awaitMaestroImportedShare(activity: MaestroActivityOwner): ShareRequest =
    withTimeout(30_000L) {
        var request: ShareRequest? = null
        while (request?.payload?.importReady != true) {
            activity.onActivity { request = it.pendingInboundShareRequestForTest }
            if (request?.payload?.importReady != true) delay(100L)
        }
        checkNotNull(request)
    }

/** UI cancellation/staging must clear durable request ownership, retain histories and never auto-send. */
internal suspend fun verifyMaestroInboundShare(
    native: Marmot,
    state: WhiteNoiseAppState,
    activity: MaestroActivityOwner,
    baseline: MaestroInboundShareBaseline?,
    postcondition: String?,
): Boolean {
    if (postcondition?.startsWith("share-request-") != true) return false
    val before = checkNotNull(baseline)
    val expectedDraft =
        when (postcondition) {
            "share-request-cancelled" -> null
            "share-request-staged", "share-request-files-removed" -> before.text
            else -> error("Unknown inbound-share postcondition")
        }
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val store = createPendingShareRequestStore(context)
    var cleared = false
    var localState: Boolean? = null
    var histories: Boolean? = null
    var fileProof: MaestroShareFileProof? = null
    val verified =
        withTimeoutOrNull(15_000L) {
            var matches = false
            while (!matches) {
                activity.onActivity { cleared = it.pendingInboundShareRequestForTest == null }
                cleared = cleared && withContext(Dispatchers.IO) { store.load(before.requestId) == null }
                if (cleared) {
                    localState = maestroShareLocalStateMatches(state, before, expectedDraft)
                    histories = maestroShareHistoriesMatch(native, before, expectedDraft, postcondition)
                    fileProof = verifyMaestroShareFiles(context, before, postcondition)
                }
                matches = cleared && localState == true && histories == true && fileProof?.verified == true
                if (!matches) delay(100L)
            }
            true
        } ?: false
    if (!verified) {
        Log.w(
            "MaestroShareProof",
            "cleared=$cleared local=$localState histories=$histories " +
                "shelves=${fileProof?.shelvesMatch} copies=${fileProof?.copiesMatch} " +
                "removed=${fileProof?.removed} ownership=${fileProof?.ownershipMatches}",
        )
    }
    return verified
}

private suspend fun maestroShareLocalStateMatches(
    state: WhiteNoiseAppState,
    before: MaestroInboundShareBaseline,
    expectedDraft: String?,
): Boolean =
    withContext(Dispatchers.Main.immediate) {
        state.activeAccountRef == before.owner &&
            state.accounts.associate { it.label to it.accountIdHex } == before.accountIds &&
            state.draftStore.get(before.owner, before.group) == expectedDraft &&
            state.accounts.filter { it.label != before.owner }.all {
                state.draftStore.get(it.label, before.group).isNullOrEmpty()
            }
    }

private suspend fun maestroShareHistoriesMatch(
    native: Marmot,
    before: MaestroInboundShareBaseline,
    expectedDraft: String?,
    postcondition: String,
): Boolean =
    withContext(Dispatchers.IO) {
        val retained = if (postcondition == "share-request-staged") before.files else emptyList()
        val expectedAttachments =
            retained.map {
                MaestroExpectedShareAttachment(
                    stagedDocumentAttachmentId(before.owner, before.group, it.uri.toString()),
                    it.name,
                    it.mime,
                )
            }
        native.listAccounts().associate { it.label to it.accountIdHex } == before.accountIds &&
            before.messages.all { (account, messages) ->
                val draft = if (account == before.owner) expectedDraft else null
                val attachments = if (account == before.owner) expectedAttachments else emptyList()
                maestroSharedMessageSnapshot(native, account, before.group) == messages &&
                    maestroShareDraftMatches(
                        native.messageDraft(account, before.group),
                        before.group,
                        draft,
                        attachments,
                    )
            }
    }

internal fun maestroSharedMessageSnapshot(
    native: Marmot,
    account: String,
    group: String,
): List<MaestroSharedMessageSnapshot> =
    readMaestroMessages(native, account, group)
        .map {
            MaestroSharedMessageSnapshot(
                it.messageIdHex,
                it.plaintext,
                it.deleted,
                it.edit != null,
                it.replyToMessageIdHex,
            )
        }.sortedBy { it.id }

private fun maestroExpectedShareErrors(fixture: String): List<ShareImportError> =
    when {
        fixture.contains("denied") -> listOf(ShareImportError.Unreadable)
        fixture.contains("empty") -> listOf(ShareImportError.Empty)
        fixture == "share-text" || fixture.startsWith("share-external-") -> emptyList()
        else -> listOf(ShareImportError.Scheme)
    }

/** Cleanup follows proof and Activity destruction, confined to the disposable lab's private Android intake. */
internal suspend fun clearMaestroInboundShareFixture(context: Context) {
    check(context.packageName == MaestroFixtureRunner.FIXTURE_PACKAGE)
    val store = createPendingShareRequestStore(context)
    withContext(Dispatchers.IO) {
        store.clear()
        val directory = File(context.noBackupFilesDir, PRIVATE_SHARE_DIRECTORY)
        check(!directory.exists() || directory.deleteRecursively()) { "Fixture private share cleanup failed" }
        check(!directory.exists()) { "Fixture private share files remain" }
    }
}
