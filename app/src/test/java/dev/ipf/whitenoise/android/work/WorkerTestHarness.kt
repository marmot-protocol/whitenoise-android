package dev.ipf.whitenoise.android.work

import android.content.Context
import android.os.Looper
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import dev.ipf.whitenoise.android.WhiteNoiseApplication
import dev.ipf.whitenoise.android.notifications.NotificationReplyCipher
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.WorkerTestHooks
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import org.robolectric.Shadows.shadowOf
import java.time.Duration
import java.util.UUID
import javax.crypto.spec.SecretKeySpec

internal object WorkerHarnessFixtures {
    const val ACCOUNT_REF = "account-a"
    val GROUP_ID_HEX = "ab".repeat(32)
    val MESSAGE_ID_HEX = "cd".repeat(32)

    fun workerAppState(context: Context): WhiteNoiseAppState =
        WhiteNoiseAppState(
            context = context.applicationContext,
            draftStore = DraftStore(EmptyDraftPersistence),
            accountIdHexResolver = { null },
            accounts = emptyList(),
            activeAccountRef = "",
        )

    private object EmptyDraftPersistence : dev.ipf.whitenoise.android.state.DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }
}

internal class WorkerTestHarness(
    private val application: WhiteNoiseApplication,
) {
    val context: Context = application.applicationContext
    val appState: WhiteNoiseAppState = WorkerHarnessFixtures.workerAppState(context)
    val workManager: WorkManager

    init {
        installTestReplyCipher(
            NotificationReplyCipher(SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")),
        )
        application.appStateFactory = { appState }
        appState.workerTestHooks = WorkerTestHooks()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        workManager = WorkManager.getInstance(context)
        clearWorkerPreferences()
        // Bind the injected app state before any production code touches it.
        application.appState
    }

    fun clearWorkerPreferences() {
        context
            .getSharedPreferences("whitenoise.notification_action_retries", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        context
            .getSharedPreferences("whitenoise.notification_replies", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        context
            .getSharedPreferences("whitenoise", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    fun tearDown() {
        WorkManagerTestInitHelper.closeWorkDatabase()
        installTestReplyCipher(null)
        application.appStateFactory = null
        appState.workerTestHooks = null
    }

    /** Robolectric has no AndroidKeyStore; keep the replacement entirely in test code. */
    private fun installTestReplyCipher(cipher: NotificationReplyCipher?) {
        NotificationReplyCipher::class.java
            .getDeclaredField("cachedCipher")
            .apply { isAccessible = true }
            .set(null, cipher)
    }

    suspend fun runWorker(
        workerClass: Class<out ListenableWorker>,
        inputData: Data,
        requestId: UUID = UUID.randomUUID(),
        runAttemptCount: Int = 0,
        workerFactory: WorkerFactory? = null,
    ): ListenableWorker.Result =
        runWithMainLooperPumping {
            val builder =
                TestListenableWorkerBuilder
                    .from(context, workerClass)
                    .setInputData(inputData)
                    .setId(requestId)
                    .setRunAttemptCount(runAttemptCount)
            workerFactory?.let(builder::setWorkerFactory)
            val worker = builder.build()
            worker.startWork().get()
        }

    private suspend fun <T> runWithMainLooperPumping(block: suspend () -> T): T =
        coroutineScope {
            val call = async(kotlinx.coroutines.Dispatchers.Default) { block() }
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
}
