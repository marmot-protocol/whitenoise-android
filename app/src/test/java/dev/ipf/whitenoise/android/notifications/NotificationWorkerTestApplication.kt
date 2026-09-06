package dev.ipf.whitenoise.android.notifications

import android.content.Context
import android.os.Looper
import dev.ipf.whitenoise.android.WhiteNoiseApplication
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.robolectric.Shadows.shadowOf
import java.time.Duration
import javax.crypto.spec.SecretKeySpec

/**
 * A [WhiteNoiseApplication] whose [appState] is supplied by the test, handed to
 * `TestListenableWorkerBuilder` as the worker context so production workers
 * resolve their real `applicationContext as? WhiteNoiseApplication` boundary
 * against controllable FFI behavior instead of the native runtime.
 */
internal class NotificationWorkerTestApplication(
    base: Context,
    private val fixtureAppState: WhiteNoiseAppState,
) : WhiteNoiseApplication() {
    init {
        attachBaseContext(base)
    }

    override val appState: WhiteNoiseAppState
        get() = fixtureAppState

    override fun getApplicationContext(): Context = this
}

/**
 * Drives the app-lock gate the workers consult: the production setter is
 * private (flipped by the unlock flow), so tests write the backing Compose
 * state directly.
 */
internal fun WhiteNoiseAppState.setAppLockScreenVisibleForTest(visible: Boolean) {
    val delegate = WhiteNoiseAppState::class.java.getDeclaredField("appLockScreenVisible\$delegate")
    delegate.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    (delegate.get(this) as androidx.compose.runtime.MutableState<Boolean>).value = visible
}

/**
 * Replaces [NotificationReplyCipher]'s process-cached AndroidKeyStore-backed
 * cipher with a deterministic in-memory key: Robolectric has no AndroidKeyStore
 * provider, and the workers resolve the cipher through
 * [NotificationReplyCipher.create] internally.
 */
internal fun seedNotificationReplyCipherForTests(): NotificationReplyCipher {
    val cipher = NotificationReplyCipher(SecretKeySpec(ByteArray(32) { it.toByte() }, "AES"))
    val cache = NotificationReplyCipher::class.java.getDeclaredField("cachedCipher")
    cache.isAccessible = true
    cache.set(null, cipher)
    return cipher
}

/**
 * Runs [block] off the Robolectric main thread while pumping the paused main
 * looper so production `Dispatchers.Main` work (bootstrap, worker mutations)
 * can progress; mirrors NotificationBootstrapTestFixture's bootstrap driver.
 */
internal suspend fun <T> pumpingMainLooper(block: suspend () -> T): T =
    coroutineScope {
        val call = async(Dispatchers.Default) { block() }
        try {
            while (!call.isCompleted) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1L))
                delay(1L)
            }
            shadowOf(Looper.getMainLooper()).idle()
            call.await()
        } finally {
            call.cancel()
        }
    }

/** Polls [condition] with bounded patience while pumping the main looper. */
internal suspend fun awaitWorkerCondition(
    description: String,
    condition: () -> Boolean,
) {
    withTimeout(10_000L) {
        while (!condition()) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(5L))
            delay(5L)
        }
    }
    check(condition()) { description }
}

/** Canonical valid message-notification action for worker and receiver tests. */
internal fun testNotificationAction(
    kind: NotificationActionKind,
    accountRef: String = "account-a",
    groupIdHex: String = "cd".repeat(32),
    messageIdHex: String = "ef".repeat(32),
    notificationTag: String = "$accountRef|$groupIdHex",
    notificationId: Int = 41,
): NotificationAction =
    NotificationAction(
        kind = kind,
        target =
            NotificationTarget(
                accountRef = accountRef,
                groupIdHex = groupIdHex,
                messageIdHex = messageIdHex,
                kind = NotificationTargetKind.MESSAGE,
            ),
        notificationTag = notificationTag,
        notificationId = notificationId,
    )
