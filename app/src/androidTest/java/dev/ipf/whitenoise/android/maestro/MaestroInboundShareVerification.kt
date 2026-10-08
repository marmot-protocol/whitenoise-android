package dev.ipf.whitenoise.android.maestro

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.marmotkit.Marmot
import dev.ipf.whitenoise.android.MainActivity
import dev.ipf.whitenoise.android.share.ShareImportError
import dev.ipf.whitenoise.android.share.ShareRequest
import dev.ipf.whitenoise.android.share.createPendingShareRequestStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

internal const val MAESTRO_SHARE_TEXT = "  Maestro café 👋\nsecond line  "

/** Enter through Android's real inbound Intent parser and importer, never an injected rendered payload. */
internal fun maestroRuntimeLaunchIntent(
    context: Context,
    fixture: String,
): Intent {
    val intent = Intent(context, MainActivity::class.java)
    if (!fixture.startsWith("share-")) return intent
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
    activity: ActivityScenario<MainActivity>,
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
    val request = awaitMaestroImportedShare(activity)
    val expectedText = if (fixture in setOf("share-partial", "share-text")) MAESTRO_SHARE_TEXT else null
    val expectedErrors = if (fixture == "share-text") emptyList() else listOf(ShareImportError.Scheme)
    check(request.payload.text == expectedText && request.payload.streamUris.isEmpty())
    check(request.payload.importErrors == expectedErrors)
    check(request.payload.importRejectedCount == expectedErrors.size)
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    withContext(Dispatchers.IO) {
        check(createPendingShareRequestStore(context).load(request.requestId) == request)
    }
    return MaestroInboundShareBaseline(
        owner,
        accounts.map { it.accountIdHex }.toSet(),
        group,
        request.requestId,
        messages,
    )
}

private suspend fun awaitMaestroImportedShare(activity: ActivityScenario<MainActivity>): ShareRequest =
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
    activity: ActivityScenario<MainActivity>,
    baseline: MaestroInboundShareBaseline?,
    postcondition: String?,
): Boolean {
    if (postcondition?.startsWith("share-request-") != true) return false
    val before = checkNotNull(baseline)
    val expectedDraft =
        when (postcondition) {
            "share-request-cancelled" -> null
            "share-request-staged" -> MAESTRO_SHARE_TEXT
            else -> error("Unknown inbound-share postcondition")
        }
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val store = createPendingShareRequestStore(context)
    return withTimeoutOrNull(15_000L) {
        var cleared = false
        while (!cleared) {
            activity.onActivity { cleared = it.pendingInboundShareRequestForTest == null }
            cleared = cleared && withContext(Dispatchers.IO) { store.load(before.requestId) == null }
            if (!cleared) delay(100L)
        }
        maestroShareLocalStateMatches(state, before, expectedDraft) && maestroShareHistoriesMatch(native, before)
    } ?: false
}

private suspend fun maestroShareLocalStateMatches(
    state: WhiteNoiseAppState,
    before: MaestroInboundShareBaseline,
    expectedDraft: String?,
): Boolean =
    withContext(Dispatchers.Main.immediate) {
        state.activeAccountRef == before.owner &&
            state.accounts.map { it.accountIdHex }.toSet() == before.accountIds &&
            state.draftStore.get(before.owner, before.group) == expectedDraft &&
            state.accounts.filter { it.label != before.owner }.all {
                state.draftStore.get(it.label, before.group).isNullOrEmpty()
            }
    }

private suspend fun maestroShareHistoriesMatch(
    native: Marmot,
    before: MaestroInboundShareBaseline,
): Boolean =
    withContext(Dispatchers.IO) {
        native.listAccounts().map { it.accountIdHex }.toSet() == before.accountIds &&
            before.messages.all { (account, messages) ->
                maestroSharedMessageSnapshot(native, account, before.group) == messages
            }
    }

private fun maestroSharedMessageSnapshot(
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
