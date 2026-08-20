package dev.ipf.whitenoise.android.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GroupFlowsBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun openGroupMembersNoCompilation() = measureOpenMembers(CompilationMode.None())

    @Test
    fun openGroupMembersBaselineProfile() =
        measureOpenMembers(
            CompilationMode.Partial(
                baselineProfileMode = BaselineProfileMode.Require,
            ),
        )

    /** Re-open after one setup open so profiles, projection, and MDK pages are warm. */
    @Test
    fun openGroupConversationVisible() = measureOpenConversationVisible(warmConversation = true)

    /** First conversation open after a cold process start and local chat-list load. */
    @Test
    fun openGroupConversationVisibleCold() = measureOpenConversationVisible(warmConversation = false)

    @Test
    fun createGroupConversationOpen() {
        // Require an explicit prefix because every iteration creates a real,
        // synced MLS group that remains visible to the fixture account.
        val createdGroupPrefix =
            BenchmarkConfig.requireFixture(BenchmarkConfig.createdGroupPrefix, "createdGroupPrefix")
        val journeys = WhiteNoiseJourneys()
        var iteration = 0
        benchmarkRule.measureRepeated(
            packageName = BenchmarkConfig.TARGET_PACKAGE,
            metrics = journeyMetrics(CREATE_GROUP_TRACE),
            // Group creation is measured independently of profile presence so
            // it can also provision a new physical-device fixture.
            compilationMode = CompilationMode.None(),
            iterations = 10,
            setupBlock = {
                pressHome()
                journeys.run { resumeToChatList() }
            },
            measureBlock = {
                tracedJourney(CREATE_GROUP_TRACE) {
                    journeys.createGroup(createdGroupPrefix, iteration++)
                }
            },
        )
    }

    @Test
    fun acceptInviteConversationReady() {
        val inviteName = BenchmarkConfig.requireFixture(BenchmarkConfig.inviteName, "inviteName")
        val journeys = WhiteNoiseJourneys()
        benchmarkRule.measureRepeated(
            packageName = BenchmarkConfig.TARGET_PACKAGE,
            metrics = journeyMetrics(ACCEPT_INVITE_TRACE),
            compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
            iterations = 1,
            setupBlock = {
                pressHome()
                journeys.run { resumeToChatList() }
            },
            measureBlock = {
                tracedJourney(ACCEPT_INVITE_TRACE) { journeys.acceptInvite(inviteName) }
            },
        )
    }

    private fun measureOpenMembers(compilationMode: CompilationMode) {
        val groupName = BenchmarkConfig.requireFixture(BenchmarkConfig.groupName, "groupName")
        val journeys = WhiteNoiseJourneys()
        benchmarkRule.measureRepeated(
            packageName = BenchmarkConfig.TARGET_PACKAGE,
            metrics = journeyMetrics(OPEN_MEMBERS_TRACE),
            compilationMode = compilationMode,
            iterations = 10,
            setupBlock = {
                pressHome()
                journeys.run { resumeToChatList() }
            },
            measureBlock = {
                tracedJourney(OPEN_MEMBERS_TRACE) { journeys.openMembers(groupName) }
            },
        )
    }

    private fun measureOpenConversationVisible(warmConversation: Boolean) {
        val groupName = BenchmarkConfig.requireFixture(BenchmarkConfig.groupName, "groupName")
        val journeys = WhiteNoiseJourneys()
        benchmarkRule.measureRepeated(
            packageName = BenchmarkConfig.TARGET_PACKAGE,
            metrics = openConversationMetrics(),
            compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
            iterations = 20,
            setupBlock = {
                pressHome()
                if (warmConversation) {
                    journeys.run {
                        resumeToChatList()
                        openConversationVisible(groupName)
                        returnToChatList()
                        waitForConversationControllerReleased()
                    }
                } else {
                    killProcess()
                    journeys.run { launchToChatList() }
                }
            },
            measureBlock = {
                tracedJourney(OPEN_CONVERSATION_SETTLED_TRACE) {
                    tracedJourney(OPEN_CONVERSATION_VISIBLE_TRACE) {
                        journeys.openConversationVisible(groupName)
                    }
                    journeys.waitForConversationRouteSettled()
                }
            },
        )
    }
}
