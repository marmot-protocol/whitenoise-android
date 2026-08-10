package dev.ipf.whitenoise.android.ui.share

import android.net.Uri
import android.os.Bundle
import androidx.compose.runtime.saveable.Saver
import dev.ipf.whitenoise.android.share.SharePayload
import dev.ipf.whitenoise.android.share.ShareRequest

private const val REQUEST_PRESENT = "present"
private const val REQUEST_ID = "request_id"
private const val REQUEST_TEXT = "text"
private const val REQUEST_STREAM_URIS = "stream_uris"
private const val REQUEST_MIME_TYPE = "mime_type"
private const val REQUEST_SHORTCUT_ID = "shortcut_id"

/** Saves the shell-owned picker route after MainActivity consumes the one-shot share intent. */
internal val NullableShareRequestSaver: Saver<ShareRequest?, Bundle> =
    Saver(
        save = { request ->
            Bundle().apply {
                putBoolean(REQUEST_PRESENT, request != null)
                request?.let {
                    putString(REQUEST_ID, it.requestId)
                    putString(REQUEST_TEXT, it.payload.text)
                    putStringArrayList(
                        REQUEST_STREAM_URIS,
                        ArrayList(it.payload.streamUris.map(Uri::toString)),
                    )
                    putString(REQUEST_MIME_TYPE, it.payload.intentMimeType)
                    putString(REQUEST_SHORTCUT_ID, it.shortcutId)
                }
            }
        },
        restore = { saved ->
            if (!saved.getBoolean(REQUEST_PRESENT)) {
                null
            } else {
                ShareRequest(
                    payload =
                        SharePayload(
                            text = saved.getString(REQUEST_TEXT),
                            streamUris =
                                saved
                                    .getStringArrayList(REQUEST_STREAM_URIS)
                                    .orEmpty()
                                    .map(Uri::parse),
                            intentMimeType = saved.getString(REQUEST_MIME_TYPE),
                        ),
                    shortcutId = saved.getString(REQUEST_SHORTCUT_ID),
                    requestId = saved.getString(REQUEST_ID).orEmpty(),
                )
            }
        },
    )
