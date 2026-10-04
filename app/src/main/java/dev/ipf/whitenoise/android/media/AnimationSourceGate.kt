package dev.ipf.whitenoise.android.media

/**
 * Chooses which decoder may see chat attachment bytes, from [admitAnimationSource]
 * alone:
 *
 *  - [AnimationSourceAdmission.Refused]: neither decoder runs, so the caller shows
 *    its existing failed placeholder. A refused animation never falls back to a
 *    sampled still, because that would hand the same bytes to a native codec.
 *  - [AnimationSourceAdmission.Admitted]: the native animated decoder runs first;
 *    if it fails, the sampled still decoder may show the first frame of the already
 *    admitted canvas.
 *  - [AnimationSourceAdmission.NotAnimation]: only the existing sampled still path runs.
 */
internal fun <T : Any> decodeAdmittedAttachmentImage(
    bytes: ByteArray,
    decodeAnimated: () -> T?,
    decodeStill: () -> T?,
): T? =
    when (admitAnimationSource(bytes)) {
        is AnimationSourceAdmission.Refused -> null
        is AnimationSourceAdmission.Admitted -> decodeAnimated() ?: decodeStill()
        AnimationSourceAdmission.NotAnimation -> decodeStill()
    }

/**
 * Native animated-decoder chokepoint: [decode] runs only for an admitted GIF or
 * animated WebP. Refused, still and non-GIF/WebP sources return null without
 * reaching the codec, whichever caller or advertised MIME type requested them.
 */
internal fun <T : Any> decodeAdmittedNativeAnimation(
    bytes: ByteArray,
    decode: () -> T?,
): T? = if (admitAnimationSource(bytes) is AnimationSourceAdmission.Admitted) decode() else null
