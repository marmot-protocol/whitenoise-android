package dev.ipf.whitenoise.android.benchmark

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue

internal object BenchmarkConfig {
    const val TARGET_PACKAGE = "dev.ipf.whitenoise.android.dev"

    private val arguments
        get() = InstrumentationRegistry.getArguments()

    val groupName: String?
        get() = arguments.getString("groupName")?.trim()?.takeIf(String::isNotEmpty)

    val inviteName: String?
        get() = arguments.getString("inviteName")?.trim()?.takeIf(String::isNotEmpty)

    val createdGroupPrefix: String?
        get() = arguments.getString("createdGroupPrefix")?.trim()?.takeIf(String::isNotEmpty)

    val notificationText: String?
        get() = arguments.getString("notificationText")?.trim()?.takeIf(String::isNotEmpty)

    val notificationSourceAccountRef: String?
        get() = arguments.getString("notificationSourceAccountRef")?.trim()?.takeIf(String::isNotEmpty)

    val notificationTexts: List<String>
        get() = fixtureList("notificationTexts").ifEmpty { listOfNotNull(notificationText) }

    val notificationConversationTitles: List<String>
        get() = fixtureList("notificationConversationTitles")

    val allowNetworkToggle: Boolean
        get() = arguments.getString("allowNetworkToggle") == "true"

    val originalAirplaneMode: BenchmarkAirplaneMode?
        get() = BenchmarkAirplaneMode.fromStatusValue(arguments.getString("originalAirplaneMode"))

    private fun fixtureList(argumentName: String): List<String> =
        arguments
            .getString(argumentName)
            .orEmpty()
            .split(NOTIFICATION_SAMPLE_DELIMITER)
            .map(String::trim)
            .filter(String::isNotEmpty)

    fun requireFixture(
        value: String?,
        argumentName: String,
    ): String {
        assumeTrue(
            "Pass -Pandroid.testInstrumentationRunnerArguments.$argumentName=<value> " +
                "after preparing the authenticated dev fixture.",
            value != null,
        )
        return checkNotNull(value)
    }

    fun requireGeneratorFixture(
        value: String?,
        argumentName: String,
    ): String =
        requireNotNull(value) {
            "Missing required baseline-profile fixture. Pass " +
                "-Pandroid.testInstrumentationRunnerArguments.$argumentName=<value> " +
                "after preparing the authenticated dev fixture."
        }

    /** Requires host authorization plus a captured state that cleanup can restore. */
    fun requireNetworkToggle(): BenchmarkAirplaneMode {
        val original = originalAirplaneMode
        assumeTrue(
            "Run the state-preserving host script with ALLOW_NETWORK_TOGGLE=true.",
            allowNetworkToggle && original != null,
        )
        return checkNotNull(original)
    }

    private const val NOTIFICATION_SAMPLE_DELIMITER = ";;"
}

/** Exact airplane-mode status and setter values accepted by the guarded benchmark. */
internal enum class BenchmarkAirplaneMode(
    val statusValue: String,
    private val commandAction: String,
) {
    Enabled("enabled", "enable"),
    Disabled("disabled", "disable"),
    ;

    /** Builds the connectivity command with an action rather than a query status. */
    fun command(): String = "cmd connectivity airplane-mode $commandAction"

    companion object {
        /** Parses only the two values emitted by Android's connectivity shell. */
        fun fromStatusValue(value: String?): BenchmarkAirplaneMode? = entries.firstOrNull { it.statusValue == value }
    }
}
