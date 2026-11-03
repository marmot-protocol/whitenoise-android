package dev.ipf.whitenoise.android.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Collects the packaged profiles from the prepared, populated dev fixture.
 *
 * Only cold launch-to-surface journeys enter the Startup Profile, which is what
 * R8 lays out in the primary DEX. Chat open, conversation scrolling, group
 * details and warm share delivery stay in the broader Baseline Profile so they
 * cannot crowd startup code out of the primary DEX. Their rules may still carry
 * the `S` flag, since every journey begins with a cold launch — that flag only
 * guides ART compilation, not R8 layout. Regenerate when any covered hot path
 * changes — see `docs/performance.md`.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    /** Includes cold populated startup through the authenticated chat list in the startup profile. */
    @Test
    fun generateStartup() {
        val journeys = WhiteNoiseJourneys()
        journeys.prepareAuthenticatedChatList()
        baselineProfileRule.collect(
            packageName = BenchmarkConfig.TARGET_PACKAGE,
            includeInStartupProfile = true,
        ) {
            journeys.run { launchToChatList() }
        }
    }

    /** Includes cold share parsing and picker composition in the startup profile. */
    @Test
    fun generateInboundShareStartup() {
        val journeys = WhiteNoiseJourneys()
        journeys.prepareAuthenticatedChatList()
        baselineProfileRule.collect(
            packageName = BenchmarkConfig.TARGET_PACKAGE,
            includeInStartupProfile = true,
        ) {
            journeys.run { launchSharePickerForStartupMeasurement() }
        }
    }

    /**
     * Records chat open to a visible transcript, a representative scroll
     * through older history and back, and the member roster, for the
     * Baseline Profile only.
     */
    @Test
    fun generateCriticalUserJourneys() {
        val groupName = BenchmarkConfig.requireGeneratorFixture(BenchmarkConfig.groupName, "groupName")
        val journeys = WhiteNoiseJourneys()
        journeys.prepareAuthenticatedChatList()
        baselineProfileRule.collect(
            packageName = BenchmarkConfig.TARGET_PACKAGE,
            includeInStartupProfile = false,
        ) {
            journeys.run {
                launchToChatList()
                openLocalTranscript(groupName)
                scrollConversation()
                returnToChatList()
                openMembers(groupName)
                returnToChatList()
            }
        }
    }

    /**
     * Records a share delivered to the already-running app (`onNewIntent`)
     * through the visible recipient picker, for the Baseline Profile only.
     */
    @Test
    fun generateWarmInboundShare() {
        val journeys = WhiteNoiseJourneys()
        journeys.prepareAuthenticatedChatList()
        baselineProfileRule.collect(
            packageName = BenchmarkConfig.TARGET_PACKAGE,
            includeInStartupProfile = false,
        ) {
            journeys.run {
                launchToChatList()
                check(device.pressHome()) { "Failed to return home before the warm share." }
                deliverShareToRunningApp()
                returnToChatList()
            }
        }
    }
}
