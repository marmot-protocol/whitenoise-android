package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.marmotkit.AttachmentLocalAssetFfi
import dev.ipf.marmotkit.AttachmentTransferSnapshotFfi
import dev.ipf.marmotkit.AttachmentTransferStateFfi
import dev.ipf.marmotkit.AttachmentTransferStatusFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.ProductRecordResultFfi
import dev.ipf.whitenoise.android.state.AppMarmotRuntime
import dev.ipf.whitenoise.android.state.ConversationTimelineTestIds
import dev.ipf.whitenoise.android.state.NotificationSuppression
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import java.lang.reflect.Proxy

/** Supplies admission only; the existing voice runtime still owns controlled materialization and failures. */
internal fun installVoiceRetryAdmissionFixture(state: WhiteNoiseAppState) {
    val loader = MarmotInterface::class.java.classLoader
    val interfaces = arrayOf(MarmotInterface::class.java)
    val native =
        Proxy.newProxyInstance(loader, interfaces) { _, method, _ ->
            when (method.name.substringBefore('-')) {
                "recordHostTiming" -> ProductRecordResultFfi.IGNORED_DISABLED
                "attachmentLocalAssets" -> listOf(AttachmentLocalAssetFfi(null, 0u))
                "attachmentTransferSnapshot" ->
                    AttachmentTransferSnapshotFfi(
                        listOf(
                            AttachmentTransferStatusFfi(
                                "voice-fixture",
                                AttachmentTransferStateFfi.QUEUED,
                                0u,
                                0u,
                                null,
                                null,
                            ),
                        ),
                    )
                "requestExplicitAttachment" -> "voice-fixture"
                "downloadAttachmentAgain" -> error("A host codec failure must not reset native acquisition")
                else -> error("Unexpected native voice fixture call: ${method.name}")
            }
        } as MarmotInterface
    WhiteNoiseAppState::class.java.getDeclaredField("suppression").apply { isAccessible = true }.set(
        state,
        NotificationSuppression().onForeground().onActiveConversation(
            ConversationTimelineTestIds.GROUP_ID,
            accountRef = state.activeAccountRef,
        ),
    )
    WhiteNoiseAppState::class.java
        .getDeclaredField("marmotRuntime")
        .apply { isAccessible = true }
        .set(state, AppMarmotRuntime("voice-retry-fixture", native))
}
