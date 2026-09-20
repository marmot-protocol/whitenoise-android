package dev.ipf.whitenoise.android.state

/** Shareable result of choosing one network acquisition path for an attachment. */
internal sealed interface AttachmentAcquisitionOutcome {
    /** Plaintext produced by the legacy download path. */
    data class LegacyBytes(
        val bytes: ByteArray,
    ) : AttachmentAcquisitionOutcome

    /** MarmotKit now retains the plaintext; each consumer must open its own lease. */
    data class NativeRetained(
        val request: AttachmentTransferRequest,
    ) : AttachmentAcquisitionOutcome
}
