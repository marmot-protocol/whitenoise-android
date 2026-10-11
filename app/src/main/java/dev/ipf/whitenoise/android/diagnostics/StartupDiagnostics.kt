package dev.ipf.whitenoise.android.diagnostics

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.core.content.FileProvider
import dev.ipf.whitenoise.android.BuildConfig
import dev.ipf.whitenoise.android.core.DiagnosticFormatter
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Diagnostic-build-only host capture, attached before any AppState or native runtime is created. */
internal object StartupDiagnostics {
    private const val MAX_OS_BUILD_LENGTH = 160

    @Volatile private var recorder: StartupDiagnosticRecorder? = null
    private var header = ""

    val enabled: Boolean get() = BuildConfig.STARTUP_DIAGNOSTICS_ENABLED

    /** Attaches a bounded private writer without waiting for disk or native initialization. */
    fun bind(context: Context) {
        if (!enabled || recorder != null) return
        header =
            "White Noise startup diagnostics\n" +
            "app=${DiagnosticFormatter.redactError("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")}\n" +
            "android_api=${Build.VERSION.SDK_INT}\n" +
            "os_build=${DiagnosticFormatter.redactError(Build.FINGERPRINT.take(MAX_OS_BUILD_LENGTH))}\n" +
            "app_sha=${BuildConfig.APP_SHORT_SHA}\nmdk_sha=${BuildConfig.MARMOT_SOURCE_SHA}\n" +
            "process_started_utc=${Instant.now()}\n"
        val store = StartupDiagnosticFileStore(File(context.filesDir, "startup_diagnostics"))
        recorder = StartupDiagnosticRecorder(SystemClock::elapsedRealtime) { store.offer(header + it) }
    }

    /** Records a genuinely new bootstrap; Retry merely observes the existing attempt. */
    fun beginAttempt() {
        recorder?.beginAttempt()
    }

    /** Adds timeout attribution from host memory even while a native call is stuck. */
    fun timeoutDetail(): String? = recorder?.let { "${it.timeoutDetail()} mdk_sha=${BuildConfig.MDK_SHORT_SHA}" }

    /** Attributes a thrown startup failure after unwind without retaining the throwable. */
    fun failureDetail(): String? = recorder?.let { "${it.failureDetail()} mdk_sha=${BuildConfig.MDK_SHORT_SHA}" }

    /** Snapshots without native queries or a potentially blocked file-writer flush. */
    fun snapshot(): String? = recorder?.let { header + it.snapshot() }

    /** Observes synchronous binding boundaries without altering their thread or cancellation policy. */
    fun <T> measure(
        stage: StartupDiagnosticStage,
        block: () -> T,
    ): T {
        val owner = recorder
        return if (owner == null) block() else owner.measure(stage, block)
    }

    /** Tracks suspendable and nested boundaries using independent tokens rather than one mutable stage. */
    suspend fun <T> stage(
        stage: StartupDiagnosticStage,
        block: suspend () -> T,
    ): T {
        val owner = recorder
        return if (owner == null) block() else owner.stage(stage, block)
    }
}

/** At most one pending snapshot and one active write; disk failures never block startup. */
internal class StartupDiagnosticFileStore(
    private val directory: File,
) {
    private val writer =
        ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(1),
            { task -> Thread(task, "WNStartup-file").apply { isDaemon = true } },
            ThreadPoolExecutor.DiscardOldestPolicy(),
        )

    /** Replaces the bounded current-process receipt off the observed operation's thread. */
    fun offer(snapshot: String) {
        writer.execute {
            runCatching {
                check(snapshot.toByteArray(Charsets.UTF_8).size <= MAX_STARTUP_REPORT_BYTES)
                safeStartupDirectory(directory)
                val target = File(directory, "latest.txt")
                check(!Files.isSymbolicLink(target.toPath()))
                target.writeText(snapshot)
            }
        }
    }
}

internal const val MAX_STARTUP_REPORT_BYTES = 32 * 1024

/** Creates one explicit read-only share grant; it never invokes audit upload or native log enumeration. */
internal fun createStartupDiagnosticShareIntent(
    context: Context,
    snapshot: String,
): Intent {
    require(snapshot.toByteArray(Charsets.UTF_8).size <= MAX_STARTUP_REPORT_BYTES)
    val directory = File(context.cacheDir, "startup_diagnostics")
    safeStartupDirectory(directory)
    // Three immutable copies are sufficient for repeated user shares; skip any unexpected file.
    directory
        .listFiles()
        .orEmpty()
        .filter {
            it.name.startsWith("startup-") &&
                it.extension == "txt" &&
                !Files.isSymbolicLink(it.toPath()) &&
                it.isFile
        }.sortedByDescending(File::lastModified)
        .drop(2)
        .forEach { check(it.delete()) { "Unable to expire startup diagnostic export" } }
    val file = File.createTempFile("startup-", ".txt", directory)
    file.writeText(snapshot)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    return Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri("Startup diagnostics", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

/** Refuses symlinked storage instead of reading or writing outside the private app directory. */
private fun safeStartupDirectory(directory: File) {
    check(!Files.isSymbolicLink(directory.toPath()))
    check(directory.isDirectory || directory.mkdirs())
}
