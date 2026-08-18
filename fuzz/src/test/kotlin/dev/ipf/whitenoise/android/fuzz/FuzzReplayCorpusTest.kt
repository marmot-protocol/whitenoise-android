package dev.ipf.whitenoise.android.fuzz

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class FuzzReplayCorpusTest {
    @Test
    fun overlayReplayProbeExistsOnlyInRegressionCorpus() {
        val stockDir =
            Path.of(
                "src/test/resources/dev/ipf/whitenoise/android/fuzz/" +
                    "ZapstoreProtocolFuzzTestInputs/fuzzZapstoreProtocol",
            )
        val regressionProbe =
            Path.of("regression-corpus/fuzzZapstoreProtocol/overlay_replay_probe.input")
        assertTrue(
            Files.isRegularFile(regressionProbe),
            "overlay replay probe must live in regression-corpus",
        )
        assertFalse(
            Files.isRegularFile(stockDir.resolve("overlay_replay_probe.input")),
            "overlay replay probe must not be duplicated in stock seeds",
        )
    }

    @Test
    fun mergedReplayCorpusIncludesRegressionOverlayProbe() {
        val mergedProbe =
            Path.of(
                "build/fuzz-replay-corpus/dev/ipf/whitenoise/android/fuzz/" +
                    "ZapstoreProtocolFuzzTestInputs/fuzzZapstoreProtocol/overlay_replay_probe.input",
            )
        assertTrue(
            Files.isRegularFile(mergedProbe),
            "syncFuzzReplayCorpus must merge regression-only overlay_replay_probe.input",
        )
    }

    @Test
    fun mergedReplayResourcesIncludeJunitPlatformProperties() {
        val properties =
            Path.of("build/fuzz-replay-corpus/junit-platform.properties")
        assertTrue(
            Files.isRegularFile(properties),
            "syncFuzzReplayCorpus must preserve non-corpus test resources such as junit-platform.properties",
        )
    }
}
