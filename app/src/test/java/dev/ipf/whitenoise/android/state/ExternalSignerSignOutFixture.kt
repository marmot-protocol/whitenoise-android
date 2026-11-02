package dev.ipf.whitenoise.android.state

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.core.content.pm.ShortcutManagerCompat
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.LocalCleanupReportFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.NotificationSettingsFfi
import dev.ipf.marmotkit.SignOutOutcomeFfi
import dev.ipf.marmotkit.UserProfileMetadataFfi
import dev.ipf.marmotkit.WipeOutcomeFfi
import dev.ipf.whitenoise.android.notifications.ProfileNotificationOverridePreferences
import dev.ipf.whitenoise.android.notifications.PushTokenKeyRule
import dev.ipf.whitenoise.android.notifications.PushTokenStorageException
import dev.ipf.whitenoise.android.notifications.PushTokenStore
import dev.ipf.whitenoise.android.share.ShareShortcutTarget
import dev.ipf.whitenoise.android.share.buildShareShortcut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.robolectric.RuntimeEnvironment
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resumeWithException

/** Shared native and Android state for external-signer teardown scenarios. */
internal abstract class ExternalSignerSignOutFixture {
    @get:Rule
    val pushTokenKey = PushTokenKeyRule()

    protected val context: Application = RuntimeEnvironment.getApplication()
    protected val signOutCalls = AtomicInteger(0)
    protected val wipeCalls = AtomicInteger(0)
    protected val listAccountsCalls = AtomicInteger(0)

    protected var signOutOutcome =
        SignOutOutcomeFfi(
            keyPackagesDeleted = 1u,
            keyPackageFailures = emptyList(),
            localCleanup = LocalCleanupReportFfi(completed = true, reason = null),
        )
    protected var signOutFailure: Throwable? = null
    protected var nativeDisableFailure: Throwable? = null
    protected var nativePushEnabled = true
    protected var nativeDisableReturnsEnabled = false
    protected val nativeDisableCalls = AtomicInteger(0)
    protected val nativeCallOrder = mutableListOf<String>()
    protected var listAccountsFailure: Throwable? = null
    protected var engineSignedOut = false
    protected var persistSignedOutBeforePartialFailure = false
    protected var wipeOutcome =
        WipeOutcomeFfi(
            groupsLeft = 1u,
            groupLeaveFailures = emptyList(),
            keyPackagesDeleted = 1u,
            keyPackageFailures = emptyList(),
            localCleanup = LocalCleanupReportFfi(completed = true, reason = null),
        )
    protected var engineWiped = false
    protected var additionalAccounts: List<AccountSummaryFfi> = emptyList()
    protected var beforeListAccounts: () -> Unit = {}
    protected var beforeSignOutReply: () -> Unit = {}

    // Both setup reads are synchronous bindings, so a scenario holds them on their IO thread instead of suspending.
    protected var setupRecoveryReply: (() -> Boolean)? = null
    protected var setupSnapshotReply: (() -> dev.ipf.marmotkit.OnboardingSnapshotFfi?)? = null
    protected val setupRecoverCalls = AtomicInteger(0)
    protected val nativePrivacyConfigCalls = AtomicInteger(0)
    protected var createdIdentity: AccountSummaryFfi? = null
    protected val createdIdentityCalls = AtomicInteger(0)
    protected var createdPolicyFailure: Throwable? = null

    /** Builds a signed-in external-signer identity without private key material for the real teardown path. */
    protected fun externalSignerAccount(
        signedOut: Boolean = false,
        running: Boolean = !signedOut,
    ) = AccountSummaryFfi(
        label = accountRef,
        accountIdHex = accountHex,
        localSigning = false,
        externalSigning = true,
        signedOut = signedOut,
        running = running,
    )

    @Suppress("UNCHECKED_CAST")
    protected val marmot =
        Proxy.newProxyInstance(
            MarmotInterface::class.java.classLoader,
            arrayOf(MarmotInterface::class.java),
        ) { proxy, method, arguments ->
            /** Creates a failed native future so lifecycle tests exercise their production error handling. */
            fun suspendFailure(failure: Throwable): Any {
                (arguments!!.last() as Continuation<Any?>).resumeWithException(failure)
                return COROUTINE_SUSPENDED
            }

            when (method.name) {
                "setRelayTelemetryRuntimeConfig" -> {
                    nativePrivacyConfigCalls.incrementAndGet()
                    Unit
                }
                "createIdentity" -> {
                    createdIdentityCalls.incrementAndGet()
                    checkNotNull(createdIdentity)
                }
                "setNativePushEnabled" -> {
                    nativeDisableCalls.incrementAndGet()
                    nativeCallOrder += "disable"
                    nativeDisableFailure?.let(::suspendFailure) ?: run {
                        nativePushEnabled = nativeDisableReturnsEnabled
                        NotificationSettingsFfi(accountRef, accountHex, true, nativePushEnabled)
                    }
                }
                "signOut" -> {
                    signOutCalls.incrementAndGet()
                    nativeCallOrder += "signOut"
                    beforeSignOutReply()
                    if (signOutFailure != null && persistSignedOutBeforePartialFailure) engineSignedOut = true
                    signOutFailure?.let(::suspendFailure)
                        ?: signOutOutcome.also {
                            engineSignedOut = it.localCleanup.completed || persistSignedOutBeforePartialFailure
                        }
                }
                "signOutAndWipe" -> {
                    wipeCalls.incrementAndGet()
                    wipeOutcome.also { engineWiped = it.localCleanup.completed }
                }
                "listAccounts" -> {
                    beforeListAccounts()
                    listAccountsCalls.incrementAndGet()
                    // The account list is a synchronous binding: a failure is thrown, not resumed.
                    listAccountsFailure?.let { throw it }
                    if (engineWiped) {
                        emptyList()
                    } else {
                        listOf(externalSignerAccount(signedOut = engineSignedOut)) + additionalAccounts
                    }
                }
                // Permit the authoritative refresh instead of silently taking cached-account fallback.
                "attachmentDownloadPolicy" -> {
                    if (arguments?.firstOrNull() == createdIdentity?.label && createdPolicyFailure != null) {
                        suspendFailure(checkNotNull(createdPolicyFailure))
                    } else {
                        dev.ipf.marmotkit.AttachmentDownloadPolicyFfi(true, 2_000uL, 300uL, 40uL)
                    }
                }
                "onboardingRecoveryRequired" -> setupRecoveryReply?.invoke() ?: false
                "onboardingSnapshot" -> setupSnapshotReply?.invoke()
                "recoverOnboarding" -> {
                    setupRecoverCalls.incrementAndGet()
                    error("Stale recovery must never reach native mutation")
                }
                "accountUnreadSummary", "chatList" -> emptyList<Any>()
                "toString" -> "ExternalSignerSignOutMarmotFake"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.firstOrNull()
                else ->
                    if (arguments?.lastOrNull() is Continuation<*>) {
                        suspendFailure(UnsupportedOperationException("Unexpected Marmot call: ${method.name}"))
                    } else {
                        throw UnsupportedOperationException("Unexpected Marmot call: ${method.name}")
                    }
            }
        } as MarmotInterface

    /** Removes prior dynamic launcher inventory so each account-cleanup assertion owns its fixture state. */
    @Before
    fun setUp() {
        ShortcutManagerCompat.removeAllDynamicShortcuts(context)
    }

    /** Seed distinct private records beside the existing lifecycle fixture's account state. */
    protected fun retainContactPictures(state: WhiteNoiseAppState) {
        listOf(accountRef, otherAccountRef).forEach { account ->
            state.contactPictureStore.save(
                account,
                "private-contact",
                "",
                "",
                ContactPictureChange.Replace(contactPicturePng(android.graphics.Color.RED)),
            ) { true }
        }
    }

    /** Recreate the store to prove cleanup is durable and the other account's file remains readable. */
    protected fun assertContactPicturesRemovedOnlyForThisAccount(state: WhiteNoiseAppState) {
        val recreated =
            ContactPictureStore(
                context.getSharedPreferences("whitenoise", android.content.Context.MODE_PRIVATE),
                context.noBackupFilesDir.resolve("contact-pictures"),
            )
        assertNull(recreated.reference(accountRef, "private-contact"))
        val retained = checkNotNull(state.contactPictureStore.reference(otherAccountRef, "private-contact"))
        assertTrue(checkNotNull(recreated.read(retained)).isNotEmpty())
    }

    /**
     * Installs the scripted native runtime into a real app state using the supplied preference-failure context
     * and, when given, a scripted push token store in place of the Keystore-backed default.
     */
    protected fun appState(
        ownerContext: Context = context,
        pushTokenStore: PushTokenStore? = null,
        marmotIoDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO,
        profileReader: (suspend (String) -> UserProfileMetadataFfi?)? = null,
    ): WhiteNoiseAppState =
        WhiteNoiseAppState(
            context = ownerContext,
            draftStore = DraftStore.forContext(ownerContext),
            accountIdHexResolver = { null },
            accounts = listOf(externalSignerAccount()) + additionalAccounts,
            activeAccountRef = accountRef,
            pushTokenStore = pushTokenStore,
            marmotIoDispatcher = marmotIoDispatcher,
            profileReader = profileReader,
        ).also { state ->
            WhiteNoiseAppState::class.java
                .getDeclaredField("marmotRuntime")
                .apply { isAccessible = true }
                .set(state, AppMarmotRuntime(rootPath = "test", marmot = marmot))
        }

    /** Seed a ready screen through the existing private setter without expanding production visibility. */
    protected fun markReady(state: WhiteNoiseAppState) {
        WhiteNoiseAppState::class.java
            .getDeclaredMethod("setPhase", AppPhase::class.java)
            .apply { isAccessible = true }
            .invoke(state, AppPhase.Ready)
    }

    /** Models encrypted cleanup reads failing before and after native admission. */
    protected fun unavailablePushStore(beforeRead: () -> Unit = {}): PushTokenStore {
        val original = context.getSharedPreferences("sign-out-unavailable-push", Context.MODE_PRIVATE)
        return PushTokenStore(
            object : SharedPreferences by original {
                override fun getStringSet(
                    key: String?,
                    defValues: MutableSet<String>?,
                ): MutableSet<String>? {
                    beforeRead()
                    throw PushTokenStorageException()
                }
            },
        )
    }

    /** Fails only credential commits at the scripted boundary while keeping other stores operational. */
    protected class CredentialFailureContext(
        base: Context,
        private val shouldFail: () -> Boolean,
    ) : ContextWrapper(base) {
        var failedWrites = 0
            private set

        override fun getApplicationContext(): Context = this

        override fun getSharedPreferences(
            name: String,
            mode: Int,
        ): SharedPreferences {
            val original = super.getSharedPreferences(name, mode)
            if (!name.startsWith("pinned_conversation_token")) return original
            return object : SharedPreferences by original {
                override fun edit(): SharedPreferences.Editor {
                    val editor = original.edit()
                    return object : SharedPreferences.Editor by editor {
                        override fun remove(key: String?): SharedPreferences.Editor = apply { editor.remove(key) }

                        override fun clear(): SharedPreferences.Editor = apply { editor.clear() }

                        override fun commit(): Boolean {
                            if (shouldFail()) {
                                failedWrites++
                                return false
                            }
                            return editor.commit()
                        }
                    }
                }
            }
        }
    }

    /** Seeds real Android shortcut extras so account-scoped cleanup can be verified through the platform adapter. */
    protected fun publishConversationShortcut(): String {
        val shortcut =
            checkNotNull(
                buildShareShortcut(
                    context = context,
                    target = ShareShortcutTarget(accountRef, "group-a", "Test conversation"),
                ),
            )
        ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
        return shortcut.id
    }

    /** Seeds the exact account/conversation geometry whose destructive lifecycle is under test. */
    protected fun retainComposerExpansion(appState: WhiteNoiseAppState) {
        appState.composerExpansionStateRetention.update(
            accountRef = accountRef,
            groupIdHex = groupId,
            preference = composerExpansion,
            draftGeneration = 1L,
        )
        appState.composerExpansionStateRetention.update(
            accountRef = otherAccountRef,
            groupIdHex = groupId,
            preference = composerExpansion,
            draftGeneration = 1L,
        )
    }

    /** Commits fail only for the new alert store; native/account persistence keeps its real behavior. */
    protected fun profileCleanupFailureContext(throws: Boolean): android.content.Context =
        object : android.content.ContextWrapper(context) {
            private var commitsToFail = if (throws) 1 else 2

            override fun getApplicationContext(): android.content.Context = this

            override fun getSharedPreferences(
                name: String,
                mode: Int,
            ): android.content.SharedPreferences {
                val delegate = super.getSharedPreferences(name, mode)
                if (name != "whitenoise.profile_notification_overrides") return delegate
                return object : android.content.SharedPreferences by delegate {
                    override fun edit(): android.content.SharedPreferences.Editor {
                        val editor = delegate.edit()
                        return object : android.content.SharedPreferences.Editor by editor {
                            override fun commit(): Boolean {
                                org.junit.Assert.assertNotEquals(
                                    android.os.Looper.getMainLooper(),
                                    android.os.Looper.myLooper(),
                                )
                                if (commitsToFail > 0) {
                                    commitsToFail -= 1
                                    if (throws) throw IllegalStateException("injected preferences failure")
                                    editor.commit()
                                    return false
                                }
                                return editor.commit()
                            }
                        }
                    }
                }
            }
        }

    /** Preloads the real persisted key directly so only teardown encounters the failing commit adapter. */
    protected fun seedProfileAlertChoices(state: WhiteNoiseAppState) {
        val key =
            checkNotNull(
                ProfileNotificationOverridePreferences.key(accountRef, accountHex),
            )
        context
            .getSharedPreferences("whitenoise.profile_notification_overrides", android.content.Context.MODE_PRIVATE)
            .edit()
            .putString(key, "MUTED|SYSTEM_DEFAULT")
            .commit()
        assertEquals(
            dev.ipf.whitenoise.android.notifications.ProfileNotificationMode.MUTED,
            state.profileNotificationOverrides.get(accountRef, accountHex).mode,
        )
    }

    protected val accountRef = "external-account"
    protected val otherAccountRef = "other-account"
    protected val accountHex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    protected val groupId = "group-a"
    protected val composerExpansion = RetainedComposerExpansion(RetainedComposerExpansionMode.Manual, 240f)
}
