package dev.ipf.whitenoise.android.media

/**
 * Whole-array read for device probes that compare the exact bytes of a retained asset against what was sent. The
 * presentation budget is a production rule for previews, a probe deliberately reads the complete payload, so it
 * uses the largest array the JVM can hold through the same checked primitive.
 */
internal fun AttachmentPlaintext.toByteArray(): ByteArray = toByteArrayWithin(Int.MAX_VALUE.toLong())
