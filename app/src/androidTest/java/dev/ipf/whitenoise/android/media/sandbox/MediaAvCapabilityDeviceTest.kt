package dev.ipf.whitenoise.android.media.sandbox

import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** A complete negative capture is useful evidence; it never implies production A/V containment. */
@RunWith(AndroidJUnit4::class)
@MediaContainmentAcceptance
class MediaAvCapabilityDeviceTest {
    @Test
    fun characterizeSelectedDescriptorPlatformMediaFromAnIsolatedUid() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        checkAvBindingWiring(context)
        val token = UUID.randomUUID().toString().replace("-", "")
        val output = checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir"))
        val directory = File(output)
        check(directory.isDirectory || directory.mkdirs())
        val report = File(directory, "media-av-capability-$token.jsonl")
        check(report.createNewFile())
        val fixture = File(context.cacheDir, "av-capability-$token.bin")
        val canary = File(context.filesDir, "media-containment-canary")
        check(canary.createNewFile()) { "A different fixture owns the private canary" }
        try {
            canary.writeBytes(byteArrayOf(1))
            report.writeText(
                JSONObject()
                    .put("type", "header")
                    .put("capture_schema", 2)
                    .put("run_token", token)
                    .put("api", Build.VERSION.SDK_INT)
                    .put("fingerprint", Build.FINGERPRINT)
                    .put("package", context.packageName)
                    .put("production_route", false)
                    .toString() + "\n",
            )
            AvCapabilityCampaign(context, token, report, canary, fixture).run()
        } finally {
            fixture.delete()
            canary.delete()
        }
    }
}

private class AvCapabilityCampaign(
    context: android.content.Context,
    private val token: String,
    private val report: File,
    private val canary: File,
    private val fixture: File,
) {
    private val binding = MediaAvCapabilityBinding(context)

    fun run() {
        try {
            var failedCases = 0
            cases().forEachIndexed { index, case -> if (record(index, case)) failedCases++ }
            check(failedCases == 0) { "A/V deadline observations remain unqualified" }
        } finally {
            binding.close()
        }
    }

    private fun cases(): List<ProbeCase> {
        val video = verifiedVideoFixture()
        val audio = pcmWaveFixture()
        return listOf(
            ProbeCase("video_construct", "retriever_construct", video),
            ProbeCase("video_metadata", "retriever_source", video),
            ProbeCase("video_poster", "probe", video),
            ProbeCase("video_tracks", "extractor", video),
            ProbeCase("audio_tracks", "extractor", audio),
            ProbeCase("audio_codec", "codec", audio),
            ProbeCase("audio_waveform", "waveform", audio),
            ProbeCase("truncated_probe", "probe", video.copyOf(16)),
            ProbeCase("empty_range", "probe", byteArrayOf()),
            ProbeCase("overflow_range", "extractor", audio),
        )
    }

    private fun record(
        index: Int,
        case: ProbeCase,
    ): Boolean {
        fixture.writeBytes(case.bytes)
        val length = if (case.id == "overflow_range") Long.MAX_VALUE else fixture.length()
        val row =
            JSONObject()
                .put("type", "case")
                .put("case", case.id)
                .put("run_token", token)
                .put("request", index + 1)
                .put("stage", case.stage)
        var failed = false
        var emitted = false
        try {
            if (!validMediaRange(fixture.length(), 0, length)) {
                row.put("outcome", "parent_range_rejected").put("worker_started", false)
            } else {
                val observed = binding.run(fixture, request(index + 1, case.stage, case.id, length))
                val evidence = observed.observation
                row.put("observation", evidence)
                val logs =
                    AvCapabilityLogs.read(
                        token,
                        case.id,
                        index + 1,
                        evidence.getInt("kernel_ready_uid"),
                        evidence.getInt("kernel_ready_pid"),
                    )
                row.put("worker_diagnostics", logs).put("diagnostics_qualified", true)
                when (observed) {
                    is AvCapabilityCase.Terminal -> {
                        row.put("outcome", if (evidence.getBoolean("success")) "success" else "no_result")
                    }
                    is AvCapabilityCase.ReplyDeadline -> {
                        row.put("outcome", "reply_deadline_unqualified")
                        failed = true
                    }
                }
            }
            emitted = true
            emit(row)
        } finally {
            if (!emitted) {
                row.put("outcome", "unqualified").put("diagnostics_qualified", false)
                emit(row)
            }
        }
        return failed
    }

    private fun request(
        id: Int,
        stage: String,
        case: String,
        length: Long,
    ): Bundle =
        Bundle().apply {
            putString("runToken", token)
            putString("caseId", case)
            putString("probeStage", stage)
            putInt("requestId", id)
            putLong("offset", 0)
            putLong("length", length)
            putString("canaryPath", canary.absolutePath)
        }

    private fun emit(row: JSONObject) {
        report.appendText(row.toString() + "\n")
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            Bundle().apply {
                putString("media_av_capability_json", row.toString())
            },
        )
    }
}

private class ProbeCase(
    val id: String,
    val stage: String,
    val bytes: ByteArray,
)

/** Bounded fixed-tag read, scoped to a fresh random token and this exact request and kernel reply UID. */
private object AvCapabilityLogs {
    fun read(
        token: String,
        case: String,
        request: Int,
        uid: Int,
        pid: Int,
    ): JSONArray {
        val prefix = "$token|$case|$request|"
        val records =
            bytes()
                .toString(Charsets.UTF_8)
                .lineSequence()
                .filter { it.startsWith(prefix) }
                .toList()
        check(records.size in 2..32) { "Missing or ambiguous correlated diagnostics" }
        check(records.map { it.split('|').getOrNull(3) }.toSet().size == 1) { "Mixed worker identities" }
        val rows = records.map { parse(it, uid, pid) }
        check(rows.count { it.getString("outcome") != "begin" } == 1) { "Ambiguous terminal diagnostics" }
        return JSONArray(rows)
    }

    private fun bytes(): ByteArray {
        val command = "logcat -d -t 256 -v raw -s MediaAvCapability:I '*:S'"
        val shell = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        val deadline = android.os.SystemClock.elapsedRealtime() + 3000
        return ParcelFileDescriptor.AutoCloseInputStream(shell).use { input ->
            val result = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            val poll =
                android.system.StructPollfd().apply {
                    fd = shell.fileDescriptor
                    events = (android.system.OsConstants.POLLIN or android.system.OsConstants.POLLHUP).toShort()
                }
            while (result.size() <= 65536) {
                val left = (deadline - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0).toInt()
                check(android.system.Os.poll(arrayOf(poll), left) > 0) { "Diagnostic capture deadline" }
                val count = input.read(buffer, 0, minOf(buffer.size, 65537 - result.size()))
                if (count < 0) break
                result.write(buffer, 0, count)
            }
            check(result.size() <= 65536) { "Diagnostic capture exceeded its bound" }
            result.toByteArray()
        }
    }

    private fun parse(
        line: String,
        uid: Int,
        pid: Int,
    ): JSONObject {
        val fields = line.split('|')
        check(fields.size == 10)
        check(fields[3].toInt() > 0 && fields[4].toInt() > 0)
        check(uid > 0 && pid > 0 && fields[4].toInt() == uid && fields[3].toInt() == pid)
        check(
            fields[5] in
                setOf(
                    "retriever_construct",
                    "retriever_source",
                    "retriever_metadata",
                    "probe",
                    "extractor_construct",
                    "extractor_source",
                    "extractor_tracks",
                    "codec_create",
                    "codec_configure",
                    "codec_start",
                    "waveform",
                    "watchdog",
                    "reply",
                ),
        )
        check(fields[6] in setOf("begin", "success", "no_result", "exception", "timeout"))
        check(fields[7].matches(Regex("[A-Za-z0-9_]{1,64}")))
        fields[8].toInt()
        check(fields[9].toLong() > 0)
        return JSONObject()
            .put("worker_reported_pid", fields[3].toInt())
            .put("worker_reported_uid", fields[4].toInt())
            .put("ready_kernel_uid", uid)
            .put("ready_kernel_pid", pid)
            .put("phase", fields[5])
            .put("outcome", fields[6])
            .put("error_class", fields[7])
            .put("error_code", fields[8].toInt())
            .put("worker_elapsed_ns", fields[9].toLong())
    }
}
