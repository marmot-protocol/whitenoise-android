package dev.ipf.whitenoise.android.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

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
                openMembers(groupName)
                returnToChatList()
            }
        }
    }
}
