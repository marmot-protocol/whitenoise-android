package dev.ipf.whitenoise.android.state

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.marmotkit.Marmot
import dev.ipf.marmotkit.MarmotAndroid
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.UserProfileMetadataFfi
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Reopens disposable native state whose only configured relay cannot resolve. */
@RunWith(AndroidJUnit4::class)
class StartupSelfProfilePresentationFfiIntegrationTest {
    /** Reopening retained native state models the data boundary shared by process death and replacement. */
    @Test
    @Suppress("LongMethod") // Keep native setup, reopen, activation assertions, and disposal in one auditable sequence.
    fun packageReplacement_cachedSelfProfileIsReadyBeforeFirstChatFrame() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            MarmotAndroid.initialize(context)
            val root = File(context.cacheDir, "startup-self-profile-${UUID.randomUUID()}").apply { mkdirs() }
            val relay = "wss://startup-self-profile.invalid"
            val native = Marmot(root.absolutePath, listOf(relay))
            try {
                withTimeout(30_000) {
                    native.start()
                    val created = native.createIdentityWithProfile(listOf(relay), listOf(relay))
                    val account = created.account
                    assertEquals(created.profile, native.userProfile(account.accountIdHex))
                    native.shutdownAndClose()
                    // Only fixture preparation touches the closed synthetic database. Production reads through MDK.
                    seedRetainedAvatar(root, account.accountIdHex)
                    val profile = created.profile.copy(picture = AVATAR)
                    val reopened = Marmot(root.absolutePath, emptyList())
                    var receiverJob: Job? = null
                    try {
                        assertEquals(profile, reopened.userProfile(account.accountIdHex))
                        lateinit var app: WhiteNoiseAppState
                        var firstProfileReadPhase: AppPhase? = null
                        val observed =
                            object : MarmotInterface by reopened {
                                /** Observes startup timing while preserving the real generated local-read boundary. */
                                override fun userProfile(accountIdHex: String): UserProfileMetadataFfi? {
                                    if (firstProfileReadPhase == null) firstProfileReadPhase = app.phase
                                    return reopened.userProfile(accountIdHex)
                                }

                                /** Prevents fallback relay requests from satisfying this cached-profile test. */
                                override suspend fun refreshProfile(
                                    accountIdHex: String,
                                    relays: List<String>,
                                ): Unit = throw CancellationException("Profile refresh blocked by fixture")
                            }
                        app =
                            WhiteNoiseAppState(
                                context = context,
                                draftStore = DraftStore(EmptyDraftPersistence),
                                accountIdHexResolver = { account.accountIdHex },
                                accounts = listOf(account),
                                activeAccountRef = account.label,
                                marmotRuntimeFactory = { AppMarmotRuntime(root.absolutePath, observed) },
                                notificationSubscriber = {
                                    receiverJob = currentCoroutineContext()[Job]
                                    SilentNotifications
                                },
                                preferences = context.getSharedPreferences(root.name, Context.MODE_PRIVATE),
                            )
                        app.bootstrap()
                        withContext(Dispatchers.Main.immediate) {
                            assertEquals(AppPhase.Ready, app.phase)
                            assertEquals(AppPhase.Bootstrapping, firstProfileReadPhase)
                            assertEquals(profile.displayName, app.chatMemberTitleCached(account.accountIdHex))
                            assertEquals(AVATAR, app.avatarUrl(account.accountIdHex))
                            assertEquals(profile, app.userProfileCached(account.accountIdHex))
                            val snapshot = app.consumeAccountSwitchLocalSnapshot(account.label)
                            assertTrue(snapshot?.memberIds?.isEmpty() == true)
                        }
                    } finally {
                        receiverJob?.cancelAndJoin()
                        runCatching { reopened.removeAccount(account.label) }
                        reopened.shutdownAndClose()
                    }
                }
            } finally {
                runCatching { native.shutdownAndClose() }
                AvatarImageLoader.resetProfileImageFetcherForTests()
                context.deleteSharedPreferences(root.name)
                root.deleteRecursively()
            }
        }

    /** Seeds a newer public fixture profile so MDK selects it over the generated account-local copy. */
    private fun seedRetainedAvatar(
        root: File,
        accountIdHex: String,
    ) {
        val path = File(root, "shared.sqlite3").absolutePath
        SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READWRITE).use { database ->
            val profile =
                database
                    .rawQuery(
                        "SELECT profile_json FROM directory_users WHERE account_id_hex = ?",
                        arrayOf(accountIdHex),
                    ).use { cursor ->
                        check(cursor.moveToFirst())
                        JSONObject(cursor.getString(0)).apply {
                            put("picture", AVATAR)
                            put("created_at", getLong("created_at") + 1L)
                        }
                    }
            val values = ContentValues().apply { put("profile_json", profile.toString()) }
            assertEquals(1, database.update("directory_users", values, "account_id_hex = ?", arrayOf(accountIdHex)))
        }
    }

    /** Holds the unrelated notification stream open until this test cancels its listener. */
    private object SilentNotifications : AppNotificationSubscription {
        override suspend fun next(): Nothing = awaitCancellation()

        override fun close() = Unit
    }

    private object EmptyDraftPersistence : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    private companion object {
        const val AVATAR = "https://profiles.example/retained.png"
    }
}
