package dev.ipf.whitenoise.android.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PerformanceDiagnosticSchemaTest {
    @Test
    fun unavailableAndNotStartedEmittersStayInactive() {
        val unavailable = emitter(available = false)
        val available = emitter(available = true)

        assertFalse(unavailable.start().available)
        assertEquals(null, unavailable.begin(PerformanceOperation.APP_START))
        assertFalse(available.status().active)
        assertEquals(null, available.begin(PerformanceOperation.APP_START))
    }

    @Test
    fun schemaUsesOnlyClosedEnumsAndBoundedNumbers() {
        val lines = mutableListOf<String>()
        var now = 10L
        val emitter = emitter(now = { now }, lines = lines)
        emitter.start()
        val trace = assertNotNullTrace(emitter.begin(PerformanceOperation.TEXT_SEND))

        now = 20L
        emitter.record(
            trace = trace,
            phase = PerformancePhase.FFI_RETURN,
            elapsedMs = Long.MAX_VALUE,
            durationMs = Long.MAX_VALUE,
            result = PerformanceResult.SUCCESS,
            layer = PerformanceLayer.FFI,
            attempt = Int.MAX_VALUE,
            queueDepth = Int.MIN_VALUE,
            count = Int.MAX_VALUE,
        )

        assertSchemaAllowlists(lines.single())
    }

    /** Confirms recovery diagnostics serialize only a closed trigger and no caller-provided text. */
    @Test
    fun recoveryTraceSerializesOnlyAClosedTrigger() {
        val lines = mutableListOf<String>()
        val emitter = emitter(lines = lines)
        emitter.start()
        val trace =
            assertNotNullTrace(
                emitter.begin(
                    operation = PerformanceOperation.SYNC_CATCH_UP,
                    trigger = PerformanceTrigger.NETWORK_RECONNECT,
                ),
            )

        emitter.record(
            trace = trace,
            phase = PerformancePhase.ACCOUNT_CATCH_UP_START,
            elapsedMs = 0L,
            result = PerformanceResult.PENDING,
            layer = PerformanceLayer.MDK,
        )

        assertTrue(lines.single().contains(" trigger=network_reconnect "))
        assertEquals(
            setOf("foreground", "network_reconnect", "push_wake", "chat_list_readiness", "explicit"),
            PerformanceTrigger.entries.mapTo(mutableSetOf()) { it.wireName },
        )
    }

    private fun assertSchemaAllowlists(line: String) {
        assertEquals(
            "schema=1 session=p#1 op=text_send phase=ffi_return " +
                "elapsed_ms=1800000 duration_ms=1800000 result=success layer=ffi " +
                "attempt=100 queue_depth=0 count=1000000",
            line,
        )
        assertEquals(
            listOf(
                "schema",
                "session",
                "op",
                "phase",
                "elapsed_ms",
                "duration_ms",
                "result",
                "layer",
                "attempt",
                "queue_depth",
                "count",
            ),
            line.split(' ').map { it.substringBefore('=') },
        )
        assertEquals(
            setOf(
                "app_start",
                "chat_open",
                "chat_list_refresh",
                "text_send",
                "media_send",
                "attachment_fetch",
                "sync_catch_up",
            ),
            PerformanceOperation.entries.mapTo(mutableSetOf()) { it.wireName },
        )
        assertTrue(PerformancePhase.entries.all { it.wireName.matches(Regex("[a-z_]+")) })
        assertEquals(
            setOf("pending", "success", "failure", "dropped"),
            PerformanceResult.entries.mapTo(mutableSetOf()) { it.wireName },
        )
        assertEquals(
            setOf("android", "ffi", "mdk", "storage", "transport"),
            PerformanceLayer.entries.mapTo(mutableSetOf()) { it.wireName },
        )
    }

    @Test
    fun routineSpansBelowThresholdDoNotCreateLogSpam() {
        val lines = mutableListOf<String>()
        val emitter = emitter(lines = lines)
        emitter.start()
        val trace = assertNotNullTrace(emitter.begin(PerformanceOperation.TEXT_SEND))

        emitter.record(trace, PerformancePhase.FFI_RETURN, elapsedMs = 4L, durationMs = 4L)
        emitter.record(trace, PerformancePhase.FFI_RETURN, elapsedMs = 5L, durationMs = 5L)

        assertEquals(1, lines.size)
        assertTrue(lines.single().contains("duration_ms=5"))
    }

    @Test
    fun sessionExpiresAfterThirtyMinutesAndDoesNotPersistAcrossEmitterInstances() {
        var now = 0L
        val first = emitter(now = { now })
        assertTrue(first.start().active)
        assertNotNull(first.begin(PerformanceOperation.APP_START))

        now = PerformanceDiagnosticEmitter.SESSION_DURATION_MS
        assertFalse(first.status().active)
        assertEquals(null, first.begin(PerformanceOperation.APP_START))

        val afterProcessDeath = emitter(now = { now })
        assertFalse(afterProcessDeath.status().active)
        afterProcessDeath.start()
        assertEquals(1L, assertNotNullTrace(afterProcessDeath.begin(PerformanceOperation.APP_START)).operationId)
    }

    @Test
    fun stoppingCollectionImmediatelyRejectsNewEvents() {
        val lines = mutableListOf<String>()
        val emitter = emitter(lines = lines)
        emitter.start()
        val trace = assertNotNullTrace(emitter.begin(PerformanceOperation.TEXT_SEND))
        emitter.record(trace, PerformancePhase.ACCEPTED, elapsedMs = 0L)

        assertFalse(emitter.stop().active)
        emitter.record(trace, PerformancePhase.SEND_COMPLETE, elapsedMs = 1L)

        assertEquals(1, lines.size)
    }

    @Test
    fun eventBudgetEmitsAtMostOneAggregateDroppedEvent() {
        val lines = mutableListOf<String>()
        val emitter = emitter(lines = lines)
        emitter.start()
        val trace = assertNotNullTrace(emitter.begin(PerformanceOperation.TEXT_SEND))

        repeat(300) {
            emitter.record(trace, PerformancePhase.ACCEPTED, elapsedMs = it.toLong())
        }
        val stopped = emitter.stop()
        emitter.stop()

        assertEquals(PerformanceDiagnosticEmitter.SESSION_EVENT_LIMIT, lines.size)
        assertEquals(1, lines.count { "phase=events_dropped" in it })
        assertTrue(lines.last().contains("result=dropped"))
        assertTrue(lines.last().contains("count=45"))
        assertFalse(stopped.active)
        assertEquals(45, stopped.droppedCount)
    }

    @Test
    fun sessionLabelsRemainStableWithinASessionAndAdvanceAcrossOptInSessions() {
        val lines = mutableListOf<String>()
        val emitter = emitter(lines = lines)
        emitter.start()
        val first = assertNotNullTrace(emitter.begin(PerformanceOperation.APP_START))
        emitter.record(first, PerformancePhase.FIRST_LOCAL_FRAME, elapsedMs = 1L)
        val sameSession = assertNotNullTrace(emitter.begin(PerformanceOperation.TEXT_SEND))
        emitter.record(sameSession, PerformancePhase.ACCEPTED, elapsedMs = 2L)
        emitter.stop()

        emitter.start()
        val second = assertNotNullTrace(emitter.begin(PerformanceOperation.CHAT_OPEN))
        emitter.record(second, PerformancePhase.FIRST_LOCAL_FRAME, elapsedMs = 1L)

        assertTrue(lines[0].contains("session=p#1 op=app_start"))
        assertTrue(lines[1].contains("session=p#1 op=text_send"))
        assertTrue(lines[2].contains("session=p#2 op=chat_open"))
    }

    @Test
    fun aTraceFromAnEndedSessionCannotEmitIntoANewSession() {
        val lines = mutableListOf<String>()
        val emitter = emitter(lines = lines)
        emitter.start()
        val staleTrace = assertNotNullTrace(emitter.begin(PerformanceOperation.TEXT_SEND))
        emitter.stop()

        emitter.start()
        val currentTrace = assertNotNullTrace(emitter.begin(PerformanceOperation.CHAT_OPEN))
        emitter.record(staleTrace, PerformancePhase.SEND_COMPLETE, elapsedMs = 1L)
        emitter.record(currentTrace, PerformancePhase.FIRST_LOCAL_FRAME, elapsedMs = 1L)

        assertEquals(1, lines.size)
        assertTrue(lines.single().contains("session=p#2 op=chat_open"))
    }

    @Test
    fun sourceHasNoStringPayloadOrPersistentOrRemoteSink() {
        val source = source("src/main/java/dev/ipf/whitenoise/android/diagnostics/PerformanceDiagnostics.kt")
        val emitterApi = source.substringAfter("internal class PerformanceDiagnosticEmitter(")
        val beginSignature = emitterApi.substringAfter("fun begin(").substringBefore(") {")
        val recordSignature = emitterApi.substringAfter("fun record(").substringBefore(") {")

        assertFalse(beginSignature.contains("String"))
        assertFalse(recordSignature.contains("String"))
        listOf(
            "SharedPreferences",
            "DataStore",
            "FileOutputStream",
            "ClipboardManager",
            "HttpClient",
            ".message",
            "stackTrace",
        ).forEach { denied -> assertFalse("Unexpected sink/input: $denied", source.contains(denied)) }
        assertTrue(source.contains("Log.i(LOG_TAG, line)"))
    }

    @Test
    fun denylistedFuzzValuesHaveNoSchemaInputAndNeverAppear() {
        val lines = mutableListOf<String>()
        val emitter = emitter(lines = lines)
        emitter.start()
        val trace = assertNotNullTrace(emitter.begin(PerformanceOperation.ATTACHMENT_FETCH))
        PerformancePhase.entries.forEachIndexed { index, phase ->
            emitter.record(trace, phase, elapsedMs = index.toLong(), durationMs = 10L)
        }
        val denied =
            listOf(
                "npub1secret",
                "nsec1private",
                "https://relay.example/path",
                "/data/user/0/database.sqlite",
                "photo-of-a-person.jpg",
                "draft plaintext",
                "ciphertext",
                "Bearer token",
                "IllegalStateException: raw error",
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            )

        denied.forEach { value -> assertTrue(lines.none { value in it }) }
    }

    private fun emitter(
        available: Boolean = true,
        now: () -> Long = { 0L },
        lines: MutableList<String> = mutableListOf(),
    ): PerformanceDiagnosticEmitter = PerformanceDiagnosticEmitter(available, now, lines::add)

    private fun assertNotNullTrace(trace: PerformanceTrace?): PerformanceTrace {
        assertNotNull(trace)
        return requireNotNull(trace)
    }

    private fun source(relativePath: String): String =
        sequenceOf(File(relativePath), File("app/$relativePath"))
            .firstOrNull(File::isFile)
            ?.readText()
            ?: error("Missing $relativePath")
}

class PerformanceDiagnosticBuildGateTest {
    @Test
    fun productionReleaseDefaultsOffWhileApprovedBuildsRemainOptInCapable() {
        val gradle = source("build.gradle.kts")
        val defaultConfig = gradle.substringAfter("defaultConfig {").substringBefore("flavorDimensions")
        val production = gradle.substringAfter("create(\"production\")").substringBefore("create(\"staging\")")
        val preview = gradle.substringAfter("create(\"preview\")").substringBefore("create(\"production\")")
        val staging = gradle.substringAfter("create(\"staging\")").substringBefore("create(\"zapstore\")")
        val dev = gradle.substringAfter("create(\"dev\")").substringBefore("create(\"preview\")")
        val debug = gradle.substringAfter("debug {").substringBefore("release {")

        assertTrue(defaultConfig.contains("ENABLE_LOCAL_PERFORMANCE_DIAGNOSTICS\", \"false"))
        assertFalse(production.contains("ENABLE_LOCAL_PERFORMANCE_DIAGNOSTICS\", \"true"))
        assertTrue(dev.contains("ENABLE_LOCAL_PERFORMANCE_DIAGNOSTICS\", \"true"))
        assertTrue(preview.contains("ENABLE_LOCAL_PERFORMANCE_DIAGNOSTICS\", \"true"))
        assertTrue(staging.contains("ENABLE_LOCAL_PERFORMANCE_DIAGNOSTICS\", \"true"))
        assertTrue(debug.contains("ENABLE_LOCAL_PERFORMANCE_DIAGNOSTICS\", \"true"))
    }

    @Test
    fun benchmarkSelectorExplicitlyOptsInAndConsumesTheTypedStartupSchema() {
        val emitter = source("src/main/java/dev/ipf/whitenoise/android/diagnostics/PerformanceDiagnostics.kt")
        val runner = rootSource("scripts/run-performance-benchmarks.sh")
        val report = rootSource("scripts/package-replacement-startup-report.sh")

        assertTrue(emitter.contains("if (BuildConfig.ENABLE_PERFORMANCE_TEST_SELECTORS) emitter.start()"))
        assertTrue(runner.contains("WNPerf:I"))
        assertTrue(runner.contains("op=app_start phase=system_splash_handoff"))
        assertFalse(runner.contains("WNStartup"))
        assertTrue(report.contains("read_phase_value system_splash_handoff elapsed_ms"))
        assertTrue(report.contains("read_phase_value first_local_frame elapsed_ms"))
    }

    @Test
    fun benchmarkRouteMarkersUseExistenceRatherThanVisibleBounds() {
        val journeys =
            rootSource(
                "benchmark/src/main/java/dev/ipf/whitenoise/android/benchmark/WhiteNoiseJourneys.kt",
            )

        listOf(
            "MAIN_SHELL_ROUTE_SETTLED",
            "CONVERSATION_ROUTE_SETTLED",
            "CONVERSATION_CONTROLLER_RELEASED",
        ).forEach { marker ->
            assertFalse(journeys.contains("waitForVisibleTag(PerformanceTags.$marker"))
            assertTrue(journeys.contains("waitForTag(PerformanceTags.$marker"))
        }
    }

    @Test
    fun benchmarkRunnerForwardsNotificationFixtureArguments() {
        val runner = rootSource("scripts/run-performance-benchmarks.sh")

        assertTrue(runner.contains("-e notificationTexts"))
        assertTrue(runner.contains("-e notificationConversationTitles"))
        assertTrue(runner.contains("-e notificationSourceAccountRef"))
    }

    /** Guards state restoration, command semantics, and resource cleanup in the device runner. */
    @Test
    fun recoveryBenchmarkIsExplicitStatePreservingAndResourceComplete() {
        val metrics = rootSource("benchmark/src/main/java/dev/ipf/whitenoise/android/benchmark/BenchmarkMetrics.kt")
        val benchmark =
            rootSource(
                "benchmark/src/main/java/dev/ipf/whitenoise/android/benchmark/NetworkRecoveryBenchmark.kt",
            )
        val config = rootSource("benchmark/src/main/java/dev/ipf/whitenoise/android/benchmark/BenchmarkConfig.kt")
        val runner = rootSource("scripts/run-performance-benchmarks.sh")

        listOf("PowerCategory.CPU", "PowerCategory.NETWORK", "PowerCategory.MEMORY").forEach {
            assertTrue("Missing energy metric $it", it in metrics)
        }
        assertTrue("PowerMetric.Type.Energy" in metrics)
        assertTrue("MemoryUsageMetric.SubMetric.HeapSize" in metrics)
        assertTrue("MemoryUsageMetric.SubMetric.RssAnon" in metrics)
        assertTrue("FrameTimingMetric()" in metrics)
        assertTrue("recoveryMetrics()" in benchmark)
        assertTrue("BenchmarkConfig.requireNetworkToggle()" in benchmark)
        assertTrue("cmd connectivity airplane-mode" in benchmark)
        assertTrue("BenchmarkAirplaneMode.Enabled" in benchmark)
        assertTrue("BenchmarkAirplaneMode.Disabled" in benchmark)
        assertTrue(
            "captured status must be parsed through the matching enum",
            "BenchmarkAirplaneMode.fromStatusValue(arguments.getString(\"originalAirplaneMode\"))" in config &&
                "entries.firstOrNull { it.statusValue == value }" in config,
        )
        assertTrue("Enabled(\"enabled\", \"enable\")" in config)
        assertTrue("Disabled(\"disabled\", \"disable\")" in config)
        assertTrue("device.executeShellCommand(mode.command())" in benchmark)
        assertFalse("executeShellCommand(\"cmd connectivity airplane-mode " in benchmark)
        assertTrue("allowNetworkToggle" in config)
        assertTrue("ALLOW_NETWORK_TOGGLE" in runner)
        assertTrue("restore_airplane_mode" in runner)
        assertTrue("airplane_mode_action_for_status" in runner)
        assertTrue("enabled) printf '%s\\n' enable" in runner)
        assertTrue("disabled) printf '%s\\n' disable" in runner)
        assertTrue("airplane-mode \"\$restore_action\"" in runner)
        assertFalse("airplane-mode \"\$original_airplane_mode\"" in runner)
        assertTrue("original_airplane_mode=\"\$(adb_cmd shell cmd connectivity airplane-mode" in runner)
        assertTrue("airplane_mode_captured=true" in runner)
        assertTrue("[[ \"\$airplane_mode_captured\" == true ]] && ! restore_airplane_mode" in runner)
        assertTrue("-e allowNetworkToggle true" in runner)
        assertTrue("\"\${BENCHMARK_CLASS_FILTER:-}\" != *\"StartupBenchmark\"*" in runner)
        assertTrue("continuing the recovery-only run with the UI fixture preflight" in runner)
        assertTrue("resource-id=\"performance.new_message\"" in runner)
        assertFalse("uninstall" in benchmark.lowercase())
    }

    private fun source(relativePath: String): String =
        sequenceOf(File(relativePath), File("app/$relativePath"))
            .firstOrNull(File::isFile)
            ?.readText()
            ?: error("Missing $relativePath")

    private fun rootSource(relativePath: String): String =
        sequenceOf(File(relativePath), File("../$relativePath"))
            .firstOrNull(File::isFile)
            ?.readText()
            ?: error("Missing $relativePath")
}
