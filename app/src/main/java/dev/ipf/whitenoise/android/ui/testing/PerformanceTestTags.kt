package dev.ipf.whitenoise.android.ui.testing

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import dev.ipf.whitenoise.android.BuildConfig

private val performanceTestSelectorsEnabled = BuildConfig.ENABLE_PERFORMANCE_TEST_SELECTORS

/** Stable cross-process selectors for release-like Macrobenchmark journeys. */
internal object PerformanceTestTags {
    const val NEW_MESSAGE = "performance.new_message"
    const val NEW_GROUP = "performance.new_group"
    const val CONTACT_PICKER_NEXT = "performance.contact_picker_next"
    const val CREATE_GROUP = "performance.create_group"
    const val OPEN_GROUP_DETAILS = "performance.open_group_details"
    const val MEMBER_LIST = "performance.member_list"
    const val JOIN_INVITE = "performance.join_invite"
}

/** Exposes a stable selector without changing accessibility descriptions. */
internal fun Modifier.performanceTestTag(
    tag: String,
    enabled: Boolean = BuildConfig.ENABLE_PERFORMANCE_TEST_SELECTORS,
): Modifier = if (enabled) testTag(tag) else this

/** Makes descendant test tags visible to cross-process UiAutomator without replacing accessibility labels. */
internal fun Modifier.exposePerformanceTestTags(enabled: Boolean = performanceTestSelectorsEnabled): Modifier =
    if (enabled) semantics { testTagsAsResourceId = true } else this
