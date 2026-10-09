package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.marmotkit.GroupSystemEventFfi
import dev.ipf.marmotkit.GroupSystemEventProvenanceFfi
import dev.ipf.whitenoise.android.state.TimelineMessage

/** Reuses the controller transport harness with authenticated native activity projections. */
open class GroupSystemReactionTestFixtures : PollMessageTestFixtures() {
    /** A typed native projection, deliberately paired with an unreadable raw body. */
    protected fun activity(): TimelineMessage {
        val source = fileTimelineMessage(71, "", caption = "private-envelope", attachments = emptyList())
        val event =
            GroupSystemEventFfi(
                provenance = GroupSystemEventProvenanceFfi.AUTHENTICATED_GROUP_STATE,
                actorDisplayName = "Alice",
                subjectDisplayName = null,
                systemType = "group_avatar_changed",
                text = "",
                actorAccountIdHex = "ab".repeat(32),
                subjectAccountIdHex = null,
                name = null,
                oldName = null,
                oldRetentionSeconds = null,
                newRetentionSeconds = null,
            )
        return source.copy(
            record = source.record.copy(kind = 1210uL, direction = "system"),
            projected = source.projected!!.copy(kind = 1210uL, direction = "system", groupSystem = event),
        )
    }
}
