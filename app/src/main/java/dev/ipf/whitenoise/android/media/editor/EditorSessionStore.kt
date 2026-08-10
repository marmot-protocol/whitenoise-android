@file:Suppress("ReturnCount") // Guard returns preserve atomic persistence failure paths.

package dev.ipf.whitenoise.android.media.editor

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

internal enum class EditorSessionPhase {
    Pending,
    Committed,
}

internal data class EditorAttachmentSession(
    val accountRef: String,
    val groupIdHex: String,
    val attachmentId: String,
    val attachmentDigest: String,
    val sourceLeaseId: String,
    val qualityPreference: String,
    val recipe: PhotoEditRecipe,
    val phase: EditorSessionPhase,
    val updatedAtMs: Long,
)

@Suppress("TooManyFunctions") // Cohesive persistence boundary for one editor-session record type.
internal class EditorSessionStore(
    private val persistence: EditorStringStore,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val maxSerializedBytes: Int = MAX_SERIALIZED_BYTES,
) {
    private val lock = Any()
    private var loaded = false
    private var sessions = linkedMapOf<String, EditorAttachmentSession>()

    fun committed(
        accountRef: String,
        groupIdHex: String,
        attachmentId: String,
        attachmentDigest: String,
    ): EditorAttachmentSession? =
        synchronized(lock) {
            if (!ensureLoadedLocked()) return null
            sessions[key(accountRef, groupIdHex, attachmentId, EditorSessionPhase.Committed)]
                ?.takeIf {
                    it.phase == EditorSessionPhase.Committed &&
                        it.attachmentDigest == attachmentDigest
                }
        }

    fun savePending(session: EditorAttachmentSession): Boolean {
        require(session.phase == EditorSessionPhase.Pending)
        return put(session.copy(updatedAtMs = nowMs()))
    }

    fun promote(
        accountRef: String,
        groupIdHex: String,
        attachmentId: String,
        committedDigest: String,
    ): EditorAttachmentSession? =
        synchronized(lock) {
            if (!ensureLoadedLocked()) return null
            val pendingKey = key(accountRef, groupIdHex, attachmentId, EditorSessionPhase.Pending)
            val committedKey = key(accountRef, groupIdHex, attachmentId, EditorSessionPhase.Committed)
            val pending =
                sessions[pendingKey]?.takeIf {
                    it.phase == EditorSessionPhase.Pending &&
                        it.attachmentDigest == committedDigest
                } ?: return null
            val committed =
                pending.copy(
                    phase = EditorSessionPhase.Committed,
                    updatedAtMs = nowMs(),
                )
            val updated = (sessions - pendingKey) + (committedKey to committed)
            if (!persistLocked(updated)) return null
            sessions = LinkedHashMap(updated)
            committed
        }

    fun discardPending(
        accountRef: String,
        groupIdHex: String,
        attachmentId: String,
    ): Boolean =
        synchronized(lock) {
            if (!ensureLoadedLocked()) return false
            val key = key(accountRef, groupIdHex, attachmentId, EditorSessionPhase.Pending)
            val current = sessions[key]?.takeIf { it.phase == EditorSessionPhase.Pending } ?: return false
            removeLocked(key, current)
        }

    fun remove(
        accountRef: String,
        groupIdHex: String,
        attachmentId: String,
    ): EditorAttachmentSession? =
        synchronized(lock) {
            if (!ensureLoadedLocked()) return null
            val pendingKey = key(accountRef, groupIdHex, attachmentId, EditorSessionPhase.Pending)
            val committedKey = key(accountRef, groupIdHex, attachmentId, EditorSessionPhase.Committed)
            val current = sessions[committedKey] ?: sessions[pendingKey] ?: return null
            val updated = sessions - pendingKey - committedKey
            if (!persistLocked(updated)) return null
            sessions = LinkedHashMap(updated)
            current
        }

    fun reconcile(committedDigestsByKey: Map<Triple<String, String, String>, String>): Set<String>? =
        synchronized(lock) {
            if (!ensureLoadedLocked()) return null
            val promotedOrKept = linkedMapOf<String, EditorAttachmentSession>()
            sessions.values
                .groupBy { Triple(it.accountRef, it.groupIdHex, it.attachmentId) }
                .forEach { (attachmentKey, candidates) ->
                    val committedDigest = committedDigestsByKey[attachmentKey] ?: return@forEach
                    val pending =
                        candidates.firstOrNull {
                            it.phase == EditorSessionPhase.Pending && it.attachmentDigest == committedDigest
                        }
                    val committed =
                        candidates.firstOrNull {
                            it.phase == EditorSessionPhase.Committed && it.attachmentDigest == committedDigest
                        }
                    val reconciled =
                        pending?.copy(phase = EditorSessionPhase.Committed, updatedAtMs = nowMs())
                            ?: committed
                            ?: return@forEach
                    promotedOrKept[
                        key(
                            attachmentKey.first,
                            attachmentKey.second,
                            attachmentKey.third,
                            EditorSessionPhase.Committed,
                        ),
                    ] = reconciled
                }
            if (promotedOrKept != sessions && !persistLocked(promotedOrKept)) {
                return sessions.values.mapTo(linkedSetOf()) { it.sourceLeaseId }
            }
            sessions = promotedOrKept
            sessions.values.mapTo(linkedSetOf()) { it.sourceLeaseId }
        }

    fun attachmentKeys(): Set<Triple<String, String, String>>? =
        synchronized(lock) {
            if (!ensureLoadedLocked()) return null
            sessions.values.mapTo(linkedSetOf()) { Triple(it.accountRef, it.groupIdHex, it.attachmentId) }
        }

    fun sourceLeaseReferenceCounts(): Map<String, Int>? =
        synchronized(lock) {
            if (!ensureLoadedLocked()) return null
            sessions.values.groupingBy { it.sourceLeaseId }.eachCount()
        }

    /** Removes only one account's sessions; safe to repeat after a completed account wipe. */
    fun removeAccount(accountRef: String): Boolean =
        synchronized(lock) {
            if (!ensureLoadedLocked()) return false
            val updated = sessions.filterValues { it.accountRef != accountRef }
            if (updated.size == sessions.size) return true
            if (!persistLocked(updated)) return false
            sessions = LinkedHashMap(updated)
            true
        }

    fun clear() {
        synchronized(lock) {
            sessions.clear()
            loaded = true
            persistence.clear()
        }
    }

    private fun put(session: EditorAttachmentSession): Boolean =
        synchronized(lock) {
            if (!ensureLoadedLocked()) return false
            val encoded = encode(session)
            if (encoded.toByteArray(Charsets.UTF_8).size > maxSerializedBytes) return false
            val key = key(session.accountRef, session.groupIdHex, session.attachmentId, session.phase)
            val updated = sessions + (key to session)
            if (!persistLocked(updated)) return false
            sessions[key] = session
            true
        }

    private fun removeLocked(
        key: String,
        expected: EditorAttachmentSession,
    ): Boolean {
        if (sessions[key] != expected) return false
        val updated = sessions - key
        if (!persistLocked(updated)) return false
        sessions.remove(key)
        return true
    }

    private fun ensureLoadedLocked(): Boolean {
        if (loaded) return true
        val persisted = persistence.readAll() ?: return false
        sessions =
            persisted
                .mapNotNull { (key, encoded) -> decode(encoded)?.let { key to it } }
                .toMap(LinkedHashMap())
        loaded = true
        return true
    }

    private fun persistLocked(updated: Map<String, EditorAttachmentSession>): Boolean =
        persistence.replaceAll(
            updated.mapValues { (_, session) -> encode(session) },
        )

    private fun key(
        accountRef: String,
        groupIdHex: String,
        attachmentId: String,
        phase: EditorSessionPhase,
    ): String =
        listOf(accountRef, groupIdHex, attachmentId, phase.name)
            .joinToString(separator = "") { value -> "${value.length}:$value" }

    private fun encode(session: EditorAttachmentSession): String =
        JSONObject()
            .put("account", session.accountRef)
            .put("group", session.groupIdHex)
            .put("attachment", session.attachmentId)
            .put("digest", session.attachmentDigest)
            .put("source", session.sourceLeaseId)
            .put("quality", session.qualityPreference)
            .put("phase", session.phase.name)
            .put("updated_at_ms", session.updatedAtMs)
            .put("recipe", encodeRecipe(session.recipe))
            .toString()

    private fun decode(encoded: String): EditorAttachmentSession? =
        runCatching {
            val json = JSONObject(encoded)
            EditorAttachmentSession(
                accountRef = json.getString("account"),
                groupIdHex = json.getString("group"),
                attachmentId = json.getString("attachment"),
                attachmentDigest = json.getString("digest"),
                sourceLeaseId = json.getString("source"),
                qualityPreference = json.getString("quality"),
                recipe = decodeRecipe(json.getJSONObject("recipe")),
                phase = EditorSessionPhase.valueOf(json.getString("phase")),
                updatedAtMs = json.getLong("updated_at_ms"),
            ).takeIf {
                it.accountRef.isNotBlank() &&
                    it.groupIdHex.isNotBlank() &&
                    it.attachmentId.isNotBlank() &&
                    it.attachmentDigest.length == SHA256_HEX_LENGTH &&
                    it.sourceLeaseId.isNotBlank()
            }
        }.getOrNull()

    companion object {
        const val MAX_SERIALIZED_BYTES = 256 * 1024
        private const val SHA256_HEX_LENGTH = 64
        private const val SESSION_KEY_ALIAS = "whitenoise.photo_editor_sessions.aes_gcm.v1"
        private const val SESSION_FILE = "whitenoise.photo_editor_sessions"

        fun create(context: Context): EditorSessionStore =
            EditorSessionStore(
                KeystoreEditorStringStore(
                    context = context.applicationContext,
                    fileName = SESSION_FILE,
                    keyAlias = SESSION_KEY_ALIAS,
                ),
            )
    }
}

private fun encodeRecipe(recipe: PhotoEditRecipe): JSONObject =
    JSONObject()
        .put(
            "crop",
            JSONArray(
                listOf(
                    recipe.crop.left,
                    recipe.crop.top,
                    recipe.crop.right,
                    recipe.crop.bottom,
                ),
            ),
        ).put("turns", recipe.quarterTurnsClockwise)
        .put(
            "strokes",
            JSONArray().also { strokes ->
                recipe.strokes.forEach { stroke ->
                    strokes.put(
                        JSONObject()
                            .put("id", stroke.id)
                            .put("mode", stroke.mode.name)
                            .put("width", stroke.widthFraction)
                            .put("color", stroke.colorArgb)
                            .put(
                                "points",
                                JSONArray().also { points ->
                                    stroke.points.forEach { point ->
                                        points.put(JSONArray(listOf(point.x, point.y)))
                                    }
                                },
                            ),
                    )
                }
            },
        )

private fun decodeRecipe(json: JSONObject): PhotoEditRecipe {
    val cropValues = json.getJSONArray("crop")
    val crop =
        NormalizedRect(
            left = cropValues.getDouble(0).toFloat(),
            top = cropValues.getDouble(1).toFloat(),
            right = cropValues.getDouble(2).toFloat(),
            bottom = cropValues.getDouble(3).toFloat(),
        )
    val strokesJson = json.getJSONArray("strokes")
    require(strokesJson.length() <= PhotoEditLimits().maxStrokes)
    val strokes =
        List(strokesJson.length()) { strokeIndex ->
            val stroke = strokesJson.getJSONObject(strokeIndex)
            val pointsJson = stroke.getJSONArray("points")
            require(pointsJson.length() in 1..PhotoEditLimits().maxPointsPerStroke)
            PhotoEditStroke(
                id = stroke.getString("id"),
                mode = PhotoStrokeMode.valueOf(stroke.getString("mode")),
                widthFraction = stroke.getDouble("width").toFloat(),
                colorArgb = stroke.getInt("color"),
                points =
                    List(pointsJson.length()) { pointIndex ->
                        val point = pointsJson.getJSONArray(pointIndex)
                        NormalizedPoint(
                            x = point.getDouble(0).toFloat(),
                            y = point.getDouble(1).toFloat(),
                        )
                    },
            )
        }
    val recipe =
        PhotoEditRecipe(
            crop = crop,
            quarterTurnsClockwise = json.getInt("turns"),
            strokes = strokes,
        )
    require(recipe.totalPointCount <= PhotoEditLimits().maxTotalPoints)
    return recipe
}

internal fun editorAttachmentDigest(
    attachmentId: String,
    fileName: String,
    mediaType: String,
    plaintext: ByteArray,
    dim: String?,
    thumbhash: String?,
    durationSeconds: Double? = null,
    waveformSamples: List<Double> = emptyList(),
): String {
    val digest = MessageDigest.getInstance("SHA-256")

    fun updateLong(value: Long) {
        for (shift in Long.SIZE_BITS - Byte.SIZE_BITS downTo 0 step Byte.SIZE_BITS) {
            digest.update((value ushr shift).toByte())
        }
    }
    listOf(attachmentId, fileName, mediaType, dim.orEmpty(), thumbhash.orEmpty()).forEach { value ->
        val encoded = value.toByteArray(Charsets.UTF_8)
        for (shift in Int.SIZE_BITS - Byte.SIZE_BITS downTo 0 step Byte.SIZE_BITS) {
            digest.update((encoded.size ushr shift).toByte())
        }
        digest.update(encoded)
    }
    digest.update(plaintext)
    digest.update((if (durationSeconds == null) 0 else 1).toByte())
    durationSeconds?.let { updateLong(java.lang.Double.doubleToLongBits(it)) }
    updateLong(waveformSamples.size.toLong())
    waveformSamples.forEach { updateLong(java.lang.Double.doubleToLongBits(it)) }
    return digest.digest().joinToString(separator = "") { "%02x".format(it) }
}
