package dev.ipf.whitenoise.android.state

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.core.content.pm.ShortcutManagerCompat
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.LocalCleanupReportFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.SignOutOutcomeFfi
import dev.ipf.marmotkit.WipeOutcomeFfi
import dev.ipf.whitenoise.android.notifications.ProfileNotificationOverridePreferences
import dev.ipf.whitenoise.android.notifications.PinnedConversationTokens
import dev.ipf.whitenoise.android.notifications.PushTokenStore
import dev.ipf.whitenoise.android.share.ShareShortcutTarget
import dev.ipf.whitenoise.android.share.buildShareShortcut
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resumeWithException

/**
 * Android-side coverage for #2132 after MarmotKit 0.9.15 made external-signer
 * accounts first-class sign-out participants. Android must follow MDK's
 * structured local-cleanup verdict instead of inferring support from signing
 * mode or clearing the active session after an unfinished engine teardown.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ExternalSignerSignOutLifecycleTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val signOutCalls = AtomicInteger(0)
    private val wipeCalls = AtomicInteger(0)
    private val listAccountsCalls = AtomicInteger(0)

    private var signOutOutcome =
        SignOutOutcomeFfi(
            keyPackagesDeleted = 1u,
            keyPackageFailures = emptyList(),
            localCleanup = LocalCleanupReportFfi(completed = true, reason = null),
        )
    private var signOutFailure: Throwable? = null
    private var listAccountsFailure: Throwable? = null
    private var engineSignedOut = false
    private var wipeOutcome =
        WipeOutcomeFfi(
            groupsLeft = 1u,
            groupLeaveFailures = emptyList(),
            keyPackagesDeleted = 1u,
            keyPackageFailures = emptyList(),
            localCleanup = LocalCleanupReportFfi(completed = true, reason = null),
        )
    private var engineWiped = false
    private var beforeListAccounts: () -> Unit = {}

    /** Builds a signed-in external-signer identity without private key material for the real teardown path. */
    private fun externalSignerAccount(
        signedOut: Boolean = false,
        running: Boolean = !signedOut,
    ) = AccountSummaryFfi(
        label = ACCOUNT_REF,
        accountIdHex = ACCOUNT_HEX,
        localSigning = false,
        externalSigning = true,
        signedOut = signedOut,
        running = running,
    )

    @Suppress("UNCHECKED_CAST")
    private val marmot =
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
                "signOut" -> {
                    signOutCalls.incrementAndGet()
                    signOutFailure?.let(::suspendFailure)
                        ?: signOutOutcome.also { engineSignedOut = it.localCleanup.completed }
                }
                "signOutAndWipe" -> {
                    wipeCalls.incrementAndGet()
                    wipeOutcome.also { engineWiped = it.localCleanup.completed }
                }
                "listAccounts" -> {
                    beforeListAccounts()
                    listAccountsCalls.incrementAndGet()
                    listAccountsFailure?.let(::suspendFailure)
                    if (engineWiped) emptyList() else listOf(externalSignerAccount(signedOut = engineSignedOut))
                }
                // Permit the authoritative refresh instead of silently taking cached-account fallback.
                "attachmentDownloadPolicy" -> dev.ipf.marmotkit.AttachmentDownloadPolicyFfi(true, 2_000uL, 300uL, 40uL)
                "onboardingRecoveryRequired" -> false
                "onboardingSnapshot" -> null
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
    private fun retainContactPictures(state: WhiteNoiseAppState) {
        listOf(ACCOUNT_REF, OTHER_ACCOUNT_REF).forEach { account ->
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
    private fun assertContactPicturesRemovedOnlyForThisAccount(state: WhiteNoiseAppState) {
        val recreated =
            ContactPictureStore(
                context.getSharedPreferences("whitenoise", android.content.Context.MODE_PRIVATE),
                context.noBackupFilesDir.resolve("contact-pictures"),
            )
        assertNull(recreated.reference(ACCOUNT_REF, "private-contact"))
        val retained = checkNotNull(state.contactPictureStore.reference(OTHER_ACCOUNT_REF, "private-contact"))
        assertTrue(checkNotNull(recreated.read(retained)).isNotEmpty())
    }

    /** Installs the scripted native runtime into a real app state using the supplied preference-failure context. */
    private fun appState(ownerContext: Context = context): WhiteNoiseAppState =
        WhiteNoiseAppState(
            context = ownerContext,
            draftStore = DraftStore.forContext(ownerContext),
            accountIdHexResolver = { null },
            accounts = listOf(externalSignerAccount()),
            activeAccountRef = ACCOUNT_REF,
        ).also { state ->
            WhiteNoiseAppState::class.java
                .getDeclaredField("marmotRuntime")
                .apply { isAccessible = true }
                .set(state, AppMarmotRuntime(rootPath = "test", marmot = marmot))
        }

    /** Completed non-destructive sign-out clears geometry only after engine-owned teardown completes. */
    @Test
    fun successfulExternalSignerSignOutUsesTheNormalCompletionPath() =
        runBlocking {
            val appState = appState()
            retainContactPictures(appState)
            retainComposerExpansion(appState)

            val completion = appState.signOutActiveAccount(deleteKeyPackages = true)

            assertEquals(SignOutCompletion.Complete, completion)
            assertEquals(1, signOutCalls.get())
            assertTrue(listAccountsCalls.get() > 0)
            assertNull(appState.composerExpansionStateRetention.preferenceFor(ACCOUNT_REF, GROUP_ID))
            assertEquals(
                COMPOSER_EXPANSION,
                appState.composerExpansionStateRetention.preferenceFor(OTHER_ACCOUNT_REF, GROUP_ID),
            )
            assertNull(appState.activeAccountRef)
            assertContactPicturesRemovedOnlyForThisAccount(appState)
            assertTrue(appState.phase is AppPhase.Onboarding)
        }

    /** A failed local alert write cannot leave an account active after native sign-out already completed. */
    @Test
    fun failedProfileAlertCleanupStillCompletesSignOutAndRetriesOnRefresh() =
        runBlocking {
            val state = appState(profileCleanupFailureContext(throws = false))
            seedProfileAlertChoices(state)
            assertEquals(SignOutCompletion.Complete, state.signOutActiveAccount())
            assertNull(state.activeAccountRef)
            assertTrue(state.accounts.single().signedOut)
            assertTrue(state.phase is AppPhase.Onboarding)
            assertEquals(
                dev.ipf.whitenoise.android.notifications.ProfileNotificationMode.DEFAULT,
                state.profileNotificationOverrides.get(ACCOUNT_REF, ACCOUNT_HEX).mode,
            )
        }

    /** Even an exceptional preferences failure must not bypass post-wipe account refresh and safe routing. */
    @Test
    fun exceptionalProfileAlertCleanupStillCompletesWipeAndRefresh() =
        runBlocking {
            val state = appState(profileCleanupFailureContext(throws = true))
            seedProfileAlertChoices(state)
            assertTrue(checkNotNull(state.signOutAndWipeActiveAccount()).localCleanup.completed)
            assertEquals(1, wipeCalls.get())
            assertTrue(listAccountsCalls.get() > 0)
            assertNull(state.activeAccountRef)
            assertTrue(state.accounts.isEmpty())
            assertTrue(state.phase is AppPhase.Onboarding)
            assertEquals(
                dev.ipf.whitenoise.android.notifications.ProfileNotificationMode.DEFAULT,
                state.profileNotificationOverrides.get(ACCOUNT_REF, ACCOUNT_HEX).mode,
            )
        }

    /** Commits fail only for the new alert store; all native/account persistence keeps its real behavior. */
    private fun profileCleanupFailureContext(throws: Boolean): android.content.Context =
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
    private fun seedProfileAlertChoices(state: WhiteNoiseAppState) {
        val key =
            checkNotNull(
                ProfileNotificationOverridePreferences.key(ACCOUNT_REF, ACCOUNT_HEX),
            )
        context
            .getSharedPreferences("whitenoise.profile_notification_overrides", android.content.Context.MODE_PRIVATE)
            .edit()
            .putString(key, "MUTED|SYSTEM_DEFAULT")
            .commit()
        assertEquals(
            dev.ipf.whitenoise.android.notifications.ProfileNotificationMode.MUTED,
            state.profileNotificationOverrides.get(ACCOUNT_REF, ACCOUNT_HEX).mode,
        )
    }

    /** An unfinished engine teardown retains both the active session and its composer geometry. */
    @Test
    fun unfinishedEngineTeardownKeepsTheExternalSignerSessionActive() =
        runBlocking {
            signOutOutcome =
                signOutOutcome.copy(
                    localCleanup =
                        LocalCleanupReportFfi(
                            completed = false,
                            reason = "account worker still active",
                        ),
                )
            val appState = appState()
            retainComposerExpansion(appState)
            val phaseBefore = appState.phase

            val completion = appState.signOutActiveAccount(deleteKeyPackages = true)

            assertEquals(SignOutCompletion.AccountCleanupIncomplete, completion)
            assertEquals(1, signOutCalls.get())
            assertEquals(0, listAccountsCalls.get())
            assertEquals(ACCOUNT_REF, appState.activeAccountRef)
            assertEquals(listOf(ACCOUNT_REF), appState.accounts.map { it.label })
            assertEquals(
                COMPOSER_EXPANSION,
                appState.composerExpansionStateRetention.preferenceFor(ACCOUNT_REF, GROUP_ID),
            )
            assertEquals(phaseBefore, appState.phase)
        }

    /** Verifies thrown native sign-out retains the established local fallback without changing the completion contract. */
    @Test
    fun transientEngineFailureRetainsTheExistingLocalSignOutFallback() =
        runBlocking {
            signOutFailure = RuntimeException("relay unavailable")
            val appState = appState()

            val completion = appState.signOutActiveAccount(deleteKeyPackages = true)

            assertEquals(SignOutCompletion.RelayCleanupIncomplete, completion)
            assertEquals(1, signOutCalls.get())
            assertNull(appState.activeAccountRef)
            assertTrue(appState.phase is AppPhase.Onboarding)
        }

    /** A failed account refresh after native completion must not revive the removed active session. */
    @Test
    fun successfulSignOutRefreshFailureStillClearsTheActiveSession() =
        runBlocking {
            listAccountsFailure = RuntimeException("account refresh unavailable")
            val appState = appState()

            val completion = appState.signOutActiveAccount(deleteKeyPackages = true)

            assertEquals(SignOutCompletion.Complete, completion)
            assertEquals(1, signOutCalls.get())
            assertEquals(1, listAccountsCalls.get())
            assertTrue(appState.accounts.single().signedOut)
            assertFalse(appState.accounts.single().running)
            assertNull(appState.activeAccountRef)
            assertTrue(appState.phase is AppPhase.Onboarding)
        }

    /** Completed destructive wipe removes the account-scoped composer geometry with the session. */
    @Test
    fun successfulExternalSignerWipeUsesTheNormalRemovalPath() =
        runBlocking {
            val shortcutId = publishConversationShortcut()
            val appState = appState()
            retainContactPictures(appState)
            retainComposerExpansion(appState)

            val outcome = appState.signOutAndWipeActiveAccount()

            assertEquals(wipeOutcome, outcome)
            assertEquals(1, wipeCalls.get())
            assertTrue(listAccountsCalls.get() > 0)
            assertTrue(appState.accounts.isEmpty())
            assertNull(appState.composerExpansionStateRetention.preferenceFor(ACCOUNT_REF, GROUP_ID))
            assertEquals(
                COMPOSER_EXPANSION,
                appState.composerExpansionStateRetention.preferenceFor(OTHER_ACCOUNT_REF, GROUP_ID),
            )
            assertNull(appState.activeAccountRef)
            assertContactPicturesRemovedOnlyForThisAccount(appState)
            assertTrue(appState.phase is AppPhase.Onboarding)
            assertTrue(ShortcutManagerCompat.getDynamicShortcuts(context).none { it.id == shortcutId })
        }

    /** A completed native sign-out cannot be interrupted by a second credential write during platform cleanup. */
    @Test
    fun signOutPublishesCompletionWhenPostNativeCredentialWritesWouldFail() =
        runBlocking {
            val owner = PostNativeCredentialFailureContext(context) { engineSignedOut }
            val state = appState(owner)
            beforeListAccounts = {
                assertTrue(state.accounts.single().signedOut)
                assertNull(PinnedConversationTokens.create(owner).issue(ACCOUNT_REF, "ab".repeat(32)))
            }
            assertEquals(SignOutCompletion.Complete, state.signOutActiveAccount())
            assertTrue(listAccountsCalls.get() > 0)
            assertTrue(state.accounts.single().signedOut)
            assertNull(state.activeAccountRef)
            assertTrue(state.phase is AppPhase.Onboarding)
            assertEquals(0, owner.postNativeWrites)
        }

    /** Wipe removes Android ownership and launcher presentation without another fallible credential flush. */
    @Test
    fun wipePublishesCompletionWhenPostNativeCredentialWritesWouldFail() =
        runBlocking {
            val owner = PostNativeCredentialFailureContext(context) { engineWiped }
            val state = appState(owner)
            beforeListAccounts = {
                assertTrue(state.accounts.isEmpty())
                assertNull(PinnedConversationTokens.create(owner).issue(ACCOUNT_REF, "ab".repeat(32)))
            }
            assertEquals(wipeOutcome, state.signOutAndWipeActiveAccount())
            assertTrue(listAccountsCalls.get() > 0)
            assertTrue(state.accounts.isEmpty())
            assertNull(state.activeAccountRef)
            assertTrue(state.phase is AppPhase.Onboarding)
            assertEquals(0, owner.postNativeWrites)
        }

    /** Corrupt push values after a successful native wipe cannot restore the removed account's pin eligibility. */
    @Test
    fun completedWipePublishesRemovedOwnershipBeforePushCleanupCanThrow() =
        runBlocking {
            val state = appState()
            val original = context.getSharedPreferences("wipe-push-corruption", Context.MODE_PRIVATE)
            val corruptAfterWipe =
                object : SharedPreferences by original {
                    override fun getStringSet(
                        key: String?,
                        defValues: MutableSet<String>?,
                    ): MutableSet<String>? {
                        check(!engineWiped) { "scripted encrypted push value corruption" }
                        return original.getStringSet(key, defValues)
                    }
                }
            WhiteNoiseAppState::class.java
                .getDeclaredField("pushTokenStore")
                .apply { isAccessible = true }
                .set(state, PushTokenStore(corruptAfterWipe))
            assertTrue(runCatching { state.signOutAndWipeActiveAccount() }.isFailure)
            assertTrue(engineWiped)
            assertTrue(state.accounts.isEmpty())
        }

    /** Injects failure only after native removal, preserving the required pre-native durable revocation. */
    private class PostNativeCredentialFailureContext(
        base: Context,
        private val nativeCompleted: () -> Boolean,
    ) : ContextWrapper(base) {
        var postNativeWrites = 0
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
                            if (nativeCompleted()) {
                                postNativeWrites++
                                return false
                            }
                            return editor.commit()
                        }
                    }
                }
            }
        }
    }

    /** An unfinished destructive wipe restores the active session without dropping its geometry. */
    @Test
    fun unfinishedExternalSignerWipeRestoresTheActiveSession() =
        runBlocking {
            val shortcutId = publishConversationShortcut()
            wipeOutcome =
                wipeOutcome.copy(
                    localCleanup =
                        LocalCleanupReportFfi(
                            completed = false,
                            reason = "account worker still active",
                        ),
                )
            val appState = appState()
            retainComposerExpansion(appState)
            val phaseBefore = appState.phase

            val outcome = appState.signOutAndWipeActiveAccount()

            assertEquals(wipeOutcome, outcome)
            assertEquals(1, wipeCalls.get())
            assertEquals(0, listAccountsCalls.get())
            assertEquals(listOf(ACCOUNT_REF), appState.accounts.map { it.label })
            assertEquals(ACCOUNT_REF, appState.activeAccountRef)
            assertEquals(
                COMPOSER_EXPANSION,
                appState.composerExpansionStateRetention.preferenceFor(ACCOUNT_REF, GROUP_ID),
            )
            assertEquals(phaseBefore, appState.phase)
            assertTrue(ShortcutManagerCompat.getDynamicShortcuts(context).any { it.id == shortcutId })
        }

    /** Seeds real Android shortcut extras so account-scoped cleanup can be verified through the platform adapter. */
    private fun publishConversationShortcut(): String {
        val shortcut =
            checkNotNull(
                buildShareShortcut(
                    context = context,
                    target = ShareShortcutTarget(ACCOUNT_REF, "group-a", "Test conversation"),
                ),
            )
        ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
        return shortcut.id
    }

    /** Seeds the exact account/conversation geometry whose destructive lifecycle is under test. */
    private fun retainComposerExpansion(appState: WhiteNoiseAppState) {
        appState.composerExpansionStateRetention.update(
            accountRef = ACCOUNT_REF,
            groupIdHex = GROUP_ID,
            preference = COMPOSER_EXPANSION,
            draftGeneration = 1L,
        )
        appState.composerExpansionStateRetention.update(
            accountRef = OTHER_ACCOUNT_REF,
            groupIdHex = GROUP_ID,
            preference = COMPOSER_EXPANSION,
            draftGeneration = 1L,
        )
    }

    private companion object {
        const val ACCOUNT_REF = "external-account"
        const val OTHER_ACCOUNT_REF = "other-account"
        const val ACCOUNT_HEX = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        const val GROUP_ID = "group-a"
        val COMPOSER_EXPANSION = RetainedComposerExpansion(RetainedComposerExpansionMode.Manual, 240f)
    }
}
