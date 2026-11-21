package dev.ipf.whitenoise.android.notifications

import dev.ipf.whitenoise.android.appModuleSource
import dev.ipf.whitenoise.android.functionBody
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every message notification path reaches the notification-profile gate (#3601) through one post decision. */
class NotificationProfileGateCoverageTest {
    /** The shared post decision ends in the profile gate, which applies the pure evaluator to the live profile. */
    @Test
    fun thePostDecisionEndsInTheProfileGate() {
        val mutes = appModuleSource("$STATE/AppStateNotificationMutes.kt")
        val decision = mutes.functionBody("shouldPostNotification")
        assertTrue(
            "the post decision starts from the local policy",
            decision.contains("LocalNotificationPolicy.shouldPost("),
        )
        assertTrue(
            "the profile gate can only narrow the local policy",
            decision.contains("return allowedByLocalPolicy && !suppressedByNotificationProfile(update,"),
        )
        val gate = mutes.functionBody("suppressedByNotificationProfile")
        assertTrue("the gate reads the live profile", gate.contains("notificationProfiles.active()"))
        assertTrue(
            "the gate applies the evaluator",
            gate.contains("notificationProfileSuppresses(profile, update,"),
        )
    }

    /** First post, generation fences and late enrichment all re-check that same decision. */
    @Test
    fun everyPostAndEnrichmentFenceUsesThePostDecision() {
        val content = appModuleSource("$STATE/AppStateNotificationContent.kt")
        val gates = appModuleSource("$STATE/AppStateNotificationPostEligibility.kt")
        listOf(
            content to "processNotificationUpdate",
            gates to "isNotificationGenerationPostAllowed",
        ).forEach { (source, name) ->
            assertTrue(
                "$name checks shouldPostNotification",
                source.functionBody(name).contains("shouldPostNotification("),
            )
        }
        val enrichment = "internal fun WhiteNoiseAppState.isNotificationEnrichmentAllowed("
        assertTrue("late enrichment is declared", gates.contains(enrichment))
        assertTrue(
            "late enrichment re-checks the generation fence",
            gates
                .substringAfter(
                    enrichment,
                ).substringBefore("\n/**")
                .contains("isNotificationGenerationPostAllowed("),
        )
    }

    private companion object {
        const val STATE = "src/main/java/dev/ipf/whitenoise/android/state"
    }
}
