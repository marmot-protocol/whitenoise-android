package dev.ipf.whitenoise.android.qualification

import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MediaLocatorFfi
import org.json.JSONArray
import org.json.JSONObject

/** Persists only this generated native result privately; no imeta parsing or Android protocol rules are involved. */
internal fun MediaAttachmentReferenceFfi.upgradeReceipt(): JSONObject =
    JSONObject()
        .put("locators", JSONArray(locators.map { JSONObject().put("kind", it.kind).put("value", it.value) }))
        .put("ciphertextSha256", ciphertextSha256)
        .put("plaintextSha256", plaintextSha256)
        .put("nonceHex", nonceHex)
        .put("fileName", fileName)
        .put("mediaType", mediaType)
        .put("version", version.name)
        .put("sourceEpoch", sourceEpoch.toString())
        .put("sourceEpochIdentity", sourceEpochIdentity ?: JSONObject.NULL)
        .put("dim", dim ?: JSONObject.NULL)
        .put("thumbhash", thumbhash ?: JSONObject.NULL)

/** Restores the exact baseline upload, including the branch identity required to send it after an upgrade. */
internal fun JSONObject.upgradeMediaReference(): MediaAttachmentReferenceFfi {
    val values = getJSONArray("locators")
    return MediaAttachmentReferenceFfi(
        locators =
            List(values.length()) { index ->
                values.getJSONObject(index).let { MediaLocatorFfi(it.getString("kind"), it.getString("value")) }
            },
        ciphertextSha256 = getString("ciphertextSha256"),
        plaintextSha256 = getString("plaintextSha256"),
        nonceHex = getString("nonceHex"),
        fileName = getString("fileName"),
        mediaType = getString("mediaType"),
        version = EncryptedMediaVersionFfi.valueOf(getString("version")),
        sourceEpoch = getString("sourceEpoch").toULong(),
        sourceEpochIdentity = if (isNull("sourceEpochIdentity")) null else getString("sourceEpochIdentity"),
        dim = if (isNull("dim")) null else getString("dim"),
        thumbhash = if (isNull("thumbhash")) null else getString("thumbhash"),
    )
}
