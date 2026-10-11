package dev.ipf.whitenoise.android.maestro

import android.content.Context
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.ChatListMessagePreviewFfi
import dev.ipf.marmotkit.Marmot
import dev.ipf.marmotkit.MarmotAndroid
import dev.ipf.marmotkit.MarmotOptions
import dev.ipf.marmotkit.RelayPolicyFfi
import dev.ipf.marmotkit.UserProfileMetadataFfi
import dev.ipf.whitenoise.android.MainActivity
import dev.ipf.whitenoise.android.ManualDeviceFixture
import dev.ipf.whitenoise.android.state.AppMarmotRuntime
import dev.ipf.whitenoise.android.state.AppPhase
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.LoopbackNostrRelay
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.chatListItemFromProjection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

private val MAESTRO_POSTCONDITIONS =
    setOf(
        "none",
        "presentation-checked",
        "presentation-observed",
        "send",
        "message-reply",
        "message-edit",
        "message-delete-local",
        "message-delete-everyone",
        "reactions-retained",
        "reactions-draft-retained",
        "composer-expanded",
        "composer-automatic",
        "composer-recreated",
        "dark",
        "light",
        "amoled",
        "font-large",
        "language-system",
        "folder-saved",
        "folder-absent",
        "poll-no-vote",
        "poll-single-vote",
        "poll-change-vote",
        "poll-multiple-vote",
        "poll-published",
        "chat-deleted",
        "chat-pinned",
        "chat-unpinned",
        "consent-pending",
        "consent-declined",
        "notification-denied",
        "notification-granted",
        "camera-denied",
        "app-lock-unavailable",
        "app-lock-credential-retry",
        "app-lock-credential-rotation",
        "app-lock-credential-warm-disabled",
        "app-lock-credential-delay",
        "accounts-retained",
        "public-key-copy-owner",
        "public-key-copy-peer",
        "global-library-empty",
        "private-key-copy-owner",
        "private-key-copy-peer",
        "account-action-signed-out",
        "account-action-wiped",
        "contact-private-saved",
        "contact-private-cleared",
        "contact-private-boundary",
        "speech-rate-custom",
        "speech-rate-minimum",
        "speech-rate-maximum",
        "speech-rate-preset",
        "speech-rate-system",
        "public-profile-text-saved",
        "public-profile-about-cleared",
        "public-profile-unchanged",
        "public-profile-text-trimmed",
        "public-profile-name-cleared",
        "smart-rule-read",
        "smart-rule-mentions",
        "smart-rule-any",
        "smart-rule-excluded",
        "smart-rule-unread",
        "smart-rule-defaults",
        "smart-rule-title",
        "smart-rule-absent",
        "relay-lists-unchanged",
        "share-request-cancelled",
        "share-request-staged",
        "share-request-files-removed",
    )

/** Real MDK state and production Compose screens; never installed-account or public-relay data. */
@ManualDeviceFixture
@RunWith(AndroidJUnit4::class)
class MaestroRuntimeHostTest {
    /** Prepare the real native runtime and activity, hand UI observation to Maestro, then verify and dispose. */
    @Test
    @Suppress("LongMethod") // One fixture generation owns setup, UI handoff and teardown.
    fun hostGeneratedAccounts() =
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            check(context.packageName == MaestroFixtureRunner.FIXTURE_PACKAGE)
            requireMaestroEmulator()
            val generation = checkNotNull(InstrumentationRegistry.getArguments().getString("fixtureGeneration"))
            require(generation.matches(Regex("[a-f0-9]{32}")))
            val directory = File(context.filesDir, "maestro-$generation")
            check(directory.mkdir()) { "Fresh fixture generation required" }
            val root = File(directory, "runtime").apply { mkdirs() }
            val relay = LoopbackNostrRelay()
            val relays = listOf(relay.url)
            MarmotAndroid.initialize(context)
            var native =
                Marmot.newWithConfiguration(
                    root.absolutePath,
                    relays,
                    MarmotOptions(relayPolicy = RelayPolicyFfi.ALLOW_LOOPBACK_RELAYS_AND_BLOBS),
                )
            var state: WhiteNoiseAppState? = null
            var activity: MaestroActivityOwner? = null
            var originalActivity: MainActivity? = null
            var peerLabel: String? = null
            var groupId: String? = null
            var messageBaseline: MaestroMessageBaseline? = null
            var expectedAccountIds: Set<String> = emptySet()
            var externalContact: MaestroExternalContact? = null
            var editorBaselines: MaestroEditorBaselines? = null
            var inboundShareBaseline: MaestroInboundShareBaseline? = null
            var accountActionBaseline: MaestroAccountActionBaseline? = null
            var credentialJournal: MaestroCredentialJournal? = null
            var presentation: MaestroPresentationFixture? = null
            try {
                withTimeout(90_000L) {
                    native.start()
                    val fixture = InstrumentationRegistry.getArguments().getString("fixtureScenario", "basic")
                    val profileNames = maestroProfileNames(fixture)
                    val accounts =
                        profileNames.map { name ->
                            native.createIdentity(relays, relays).also {
                                native.publishUserProfile(
                                    it.label,
                                    UserProfileMetadataFfi(
                                        name,
                                        name,
                                        "Disposable test profile",
                                        null,
                                        null,
                                        null,
                                        null,
                                    ),
                                    relays,
                                    relays,
                                )
                            }
                        }
                    val owner = accounts.first()
                    externalContact = createMaestroExternalContact(root, relays, owner.label, fixture)
                    externalContact?.prepare()
                    val invited = if (fixture == "large-roster") accounts.drop(1) else listOf(accounts[1])
                    val members = invited.map { it.accountIdHex } + listOfNotNull(externalContact?.accountIdHex)
                    val group = native.createGroup(owner.label, "Maestro group", members, null)
                    for (member in invited) acceptMaestroInvite(native, member.label, group)
                    externalContact?.acceptGroup(group)
                    seedMaestroFixtureMessages(native, owner.label, accounts[1].label, group, fixture)
                    if (fixture == "departed") prepareMaestroDepartedGroup(native, owner, accounts[1], group)
                    // Let app bootstrap own start/subscription ordering on a freshly opened runtime.
                    native.shutdownAndClose()
                    native =
                        Marmot.newWithConfiguration(
                            root.absolutePath,
                            relays,
                            MarmotOptions(relayPolicy = RelayPolicyFfi.ALLOW_LOOPBACK_RELAYS_AND_BLOBS),
                        )
                    peerLabel = accounts[1].label
                    groupId = group
                    val app =
                        withContext(Dispatchers.Main.immediate) {
                            WhiteNoiseAppState(
                                context = context,
                                draftStore = DraftStore.forContext(context),
                                accountIdHexResolver = { ref ->
                                    accounts.firstOrNull { it.label == ref }?.accountIdHex
                                },
                                accounts = accounts,
                                activeAccountRef = owner.label,
                                profileReader = { id -> withContext(Dispatchers.IO) { native.userProfile(id) } },
                                profileRefreshRequest = { id ->
                                    withContext(Dispatchers.IO) { native.refreshProfile(id, relays) }
                                },
                                marmotRuntimeFactory = { AppMarmotRuntime(root.absolutePath, native) },
                                schedulePushWakeRecovery = { false },
                                preferences = context.getSharedPreferences(directory.name, Context.MODE_PRIVATE),
                            ).also { state = it }
                        }
                    (context.applicationContext as MaestroFixtureApplication).fixtureState = app
                    app.bootstrap()
                    externalContact?.captureProfiles(native, relays)
                    check(app.phase == AppPhase.Ready) { "Generated app bootstrap did not reach Ready: ${app.phase}" }
                    val nativeRow =
                        checkNotNull(native.presentedChatListRow(owner.label, group)) {
                            "Generated group missing from native presentation"
                        }
                    val postcondition = InstrumentationRegistry.getArguments().getString("postcondition", "none")
                    val appLockFixtureNoCredential =
                        verifyMaestroNoAppLockCredential(
                            context,
                            app,
                            context.getSharedPreferences(directory.name, Context.MODE_PRIVATE),
                            postcondition,
                        )
                    val appLockFixtureCredential = requireMaestroSyntheticCredential(context, app, postcondition)
                    if (appLockFixtureCredential) {
                        credentialJournal =
                            MaestroCredentialJournal(context.getSharedPreferences(directory.name, Context.MODE_PRIVATE))
                    }
                    editorBaselines = captureMaestroEditorBaselines(native, app, postcondition)
                    accountActionBaseline = captureMaestroAccountAction(native, app, group, postcondition)
                    expectedAccountIds = accounts.map { it.accountIdHex }.toSet()
                    messageBaseline =
                        captureMaestroMessageBaseline(
                            nativeRow.row.lastMessage,
                            postcondition,
                            owner.label,
                            group,
                            accounts[1].label,
                        )
                    File(directory, "setup.json").writeText(
                        JSONObject()
                            .put("generation", generation)
                            .put("phase", app.phase.toString())
                            .put("kind", nativeRow.row.conversationKind.toString())
                            .put("title", nativeRow.presentation.title.toString())
                            .toString(),
                    )
                    activity = MaestroActivityOwner(context.applicationContext as MaestroFixtureApplication)
                    launchMaestroRuntimeActivity(context, fixture, checkNotNull(activity))
                    checkNotNull(activity).onActivity { originalActivity = it }
                    inboundShareBaseline =
                        captureMaestroInboundShare(native, app, checkNotNull(activity), group, fixture)
                    presentation =
                        loadMaestroPresentation(
                            app,
                            group,
                            chatListItemFromProjection(nativeRow.row).latest,
                            accounts[1].accountIdHex,
                        )
                    presentation?.let { fixturePresentation ->
                        checkNotNull(activity).onActivity { fixturePresentation.install(it) }
                    }
                    // Maestro alone owns Android accessibility; this receipt certifies native handoff only.
                    File(directory, "ready.json").writeText(
                        JSONObject()
                            .put("generation", generation)
                            .put("accounts", accounts.size)
                            .put("fixture", fixture)
                            .put("uiObserver", "maestro")
                            .put("appLockFixtureNoCredential", appLockFixtureNoCredential)
                            .put("appLockFixtureCredential", appLockFixtureCredential)
                            .put("nativePid", Process.myPid())
                            .put("ready", true)
                            .toString(),
                    )
                }
                // The controller writes only this generation's finish file; no arbitrary commands.
                withTimeout(300_000L) {
                    while (!File(directory, "finish").exists()) {
                        credentialJournal?.observe(checkNotNull(state), checkNotNull(originalActivity))
                        delay(100L)
                    }
                }
                verifyNativeState(native, state, checkNotNull(peerLabel), checkNotNull(groupId), messageBaseline)
                val postcondition = InstrumentationRegistry.getArguments().getString("postcondition")
                verifyMaestroAccountsRetained(native, state, messageBaseline, expectedAccountIds, postcondition)
                verifyMaestroReactionKeyboard(
                    checkNotNull(state),
                    checkNotNull(groupId),
                    checkNotNull(activity),
                    postcondition,
                )
                val activityRecreated =
                    verifyMaestroComposerRecreated(
                        checkNotNull(state),
                        checkNotNull(groupId),
                        checkNotNull(activity),
                        originalActivity,
                        postcondition,
                    )
                val privateContactVerified =
                    verifyMaestroContactPrivateDetails(
                        native,
                        state,
                        externalContact,
                        context.getSharedPreferences(directory.name, Context.MODE_PRIVATE),
                        postcondition,
                    )
                val publicKeyCopyVerified =
                    verifyMaestroPublicKeyCopy(
                        checkNotNull(originalActivity),
                        native,
                        checkNotNull(state),
                        messageBaseline,
                        postcondition,
                    )
                val appLockVerification =
                    verifyMaestroAppLock(
                        credentialJournal,
                        checkNotNull(originalActivity),
                        checkNotNull(state),
                        context.getSharedPreferences(directory.name, Context.MODE_PRIVATE),
                        postcondition,
                    )
                val presentationVerification =
                    withContext(Dispatchers.Main) { presentation?.verify() ?: JSONObject.NULL }
                File(directory, "verified.json").writeText(
                    JSONObject()
                        .put("generation", generation)
                        .put("presentation", presentationVerification)
                        .put("verified", true)
                        .put("activityRecreated", activityRecreated)
                        .put("privateContactVerified", privateContactVerified)
                        .put("publicKeyCopyVerified", publicKeyCopyVerified)
                        .put("globalLibraryVerified", verifyMaestroEmptyLibrary(native, messageBaseline, postcondition))
                        .put(
                            "privateKeyCopyVerified",
                            verifyMaestroPrivateKeyCopy(
                                checkNotNull(originalActivity),
                                native,
                                checkNotNull(state),
                                messageBaseline,
                                postcondition,
                            ),
                        ).put("credentialEvidence", appLockVerification.credentialJsonValue)
                        .put("appLockVerified", appLockVerification.verified)
                        .put(
                            "accountActionVerified",
                            verifyMaestroAccountAction(
                                native,
                                checkNotNull(state),
                                accountActionBaseline,
                                postcondition,
                            ),
                        ).put(
                            "speechRateVerified",
                            verifyMaestroSpeechRate(context, checkNotNull(state), postcondition),
                        ).put(
                            "smartFolderRuleVerified",
                            verifyMaestroFolderRules(
                                context,
                                checkNotNull(state),
                                checkNotNull(editorBaselines).folderRules,
                                postcondition,
                            ),
                        ).put(
                            "shareImportVerified",
                            verifyMaestroInboundShare(
                                native,
                                checkNotNull(state),
                                checkNotNull(activity),
                                inboundShareBaseline,
                                postcondition,
                            ),
                        ).put(
                            "relayListsVerified",
                            verifyMaestroRelayLists(
                                native,
                                checkNotNull(state),
                                checkNotNull(editorBaselines).relayLists,
                                postcondition,
                            ),
                        ).put(
                            "publicProfileVerified",
                            verifyMaestroPublicProfile(
                                native,
                                checkNotNull(state),
                                checkNotNull(editorBaselines).publicProfiles,
                                checkNotNull(editorBaselines).profileOwner,
                                postcondition,
                            ),
                        ).toString(),
                )
            } finally {
                val clipboardClosed =
                    runCatching { withContext(NonCancellable + Dispatchers.Main) { presentation?.closeClipboard() } }
                val activityClosed = runCatching { activity?.close() }
                val presentationClosed =
                    runCatching { withContext(NonCancellable + Dispatchers.Main) { presentation?.close() } }
                val listenerStopped =
                    runCatching { withTimeout(10_000L) { state?.stopNotificationListenerForAccountTeardown() } }
                state?.mutationsScope?.cancel()
                val nativeClosed =
                    runCatching {
                        withTimeout(15_000L) {
                            try {
                                externalContact?.close()
                            } finally {
                                native.shutdownAndClose()
                            }
                        }
                    }
                val relayClosed = runCatching { relay.close() }
                val shareStorageCleared =
                    runCatching { withTimeout(15_000L) { clearMaestroInboundShareFixture(context) } }
                val preferencesRemoved = runCatching { context.deleteSharedPreferences(directory.name) }
                val rootRemoved = nativeClosed.isSuccess && root.deleteRecursively()
                check(activityClosed.isSuccess) { "Fixture Activity teardown failed" }
                check(clipboardClosed.isSuccess) { "Fixture presentation clipboard cleanup failed" }
                check(presentationClosed.isSuccess) { "Fixture presentation teardown failed" }
                check(listenerStopped.isSuccess) { "Fixture notification listener teardown failed" }
                check(nativeClosed.isSuccess) { "Fixture native runtime teardown failed" }
                check(shareStorageCleared.isSuccess) { "Fixture Android share storage cleanup failed" }
                check(relayClosed.isSuccess && preferencesRemoved.isSuccess && rootRemoved) {
                    "Fixture storage cleanup failed"
                }
                File(directory, "closed.json").writeText(
                    JSONObject().put("generation", generation).put("closed", true).toString(),
                )
            }
        }

    /** Validate the actual native peer result and preference projection independently of UI text. */
    private suspend fun verifyNativeState(
        native: Marmot,
        state: WhiteNoiseAppState?,
        peerLabel: String,
        group: String,
        messageBaseline: MaestroMessageBaseline?,
    ) {
        val postcondition = InstrumentationRegistry.getArguments().getString("postcondition", "none")
        require(postcondition in MAESTRO_POSTCONDITIONS)
        if (postcondition.startsWith("poll-")) {
            verifyMaestroPoll(native, checkNotNull(state?.activeAccountRef), peerLabel, group, postcondition)
        }
        if (postcondition.startsWith("chat-")) {
            verifyMaestroChatList(native, checkNotNull(state?.activeAccountRef), peerLabel, group, postcondition)
        }
        if (postcondition.startsWith("notification-")) verifyMaestroNotificationPermission(postcondition)
        if (postcondition == "camera-denied") verifyMaestroCameraDenied()
        if (postcondition.startsWith("consent-")) {
            verifyMaestroConsent(native, checkNotNull(state), postcondition)
        }
        if (postcondition.startsWith("folder-")) verifyMaestroFolder(checkNotNull(state), postcondition)
        if (postcondition.startsWith("message-")) {
            verifyMaestroMessageMutation(native, checkNotNull(state), postcondition, checkNotNull(messageBaseline))
        }
        when (postcondition) {
            "light", "dark", "amoled", "font-large", "language-system" ->
                verifyMaestroPreferences(checkNotNull(state), postcondition)
            "send" -> verifyMaestroSend(native, checkNotNull(state), peerLabel, group)
            "reactions-retained", "reactions-draft-retained" ->
                verifyMaestroReactions(native, checkNotNull(state?.activeAccountRef), peerLabel, group)
            "composer-expanded", "composer-automatic" ->
                verifyMaestroComposer(checkNotNull(state), group, postcondition)
        }
    }
}

private fun maestroProfileNames(fixture: String): List<String> =
    if (fixture == "large-roster") {
        listOf("Maestro Alice", "Maestro Bob", "Maestro Carol", "Maestro Erin", "Maestro Frank", "Maestro Grace")
    } else {
        listOf("Maestro Alice", "Maestro Bob", "Maestro Carol")
    }

private fun captureMaestroMessageBaseline(
    message: ChatListMessagePreviewFfi?,
    postcondition: String,
    owner: String,
    group: String,
    peer: String,
): MaestroMessageBaseline? {
    if (!requiresMaestroMessageBaseline(postcondition)) return null
    val original = checkNotNull(message)
    check(original.plaintext == "Generated fixture message")
    return MaestroMessageBaseline(owner, original.messageIdHex, group, peer)
}

private suspend fun loadMaestroPresentation(
    app: WhiteNoiseAppState,
    group: String,
    lastMessage: AppMessageRecordFfi?,
    peerAccountIdHex: String,
): MaestroPresentationFixture? {
    val arguments = InstrumentationRegistry.getArguments()
    val scenario = arguments.getString("presentationScenario") ?: return null
    val postcondition = arguments.getString("postcondition", "none")
    check(postcondition in setOf("presentation-checked", "presentation-observed"))
    val expected = checkNotNull(arguments.getString("presentationActions"))
    val actions =
        if (postcondition == "presentation-observed") {
            check(expected == "none")
            emptyList()
        } else {
            expected.split(",")
        }
    val nativeChat =
        if (scenario.startsWith("extra-native-")) {
            app.loadCreatedChatListItem(group).copy(latest = checkNotNull(lastMessage))
        } else {
            null
        }
    return MaestroPresentationFixture(scenario, actions, peerAccountIdHex, nativeChat)
}

/** The outer setup deadline remains authoritative; each member also has a bounded diagnostic retry. */
private suspend fun acceptMaestroInvite(
    native: Marmot,
    member: String,
    group: String,
) {
    var lastFailure: Exception? = null
    repeat(100) { attempt ->
        try {
            native.acceptGroupInvite(member, group)
            return
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            lastFailure = failure
        }
        if (attempt < 99) delay(100L)
    }
    throw IllegalStateException("Fixture invite acceptance failed for $member", lastFailure)
}
