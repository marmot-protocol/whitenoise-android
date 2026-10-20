package dev.ipf.whitenoise.android.notifications

import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import dev.ipf.whitenoise.android.state.KeystoreSecureStore
import org.json.JSONArray
import org.json.JSONObject
import java.security.GeneralSecurityException

/** Durable push state is unavailable; callers must defer registration rather than assume empty state. */
internal class PushTokenStorageException : GeneralSecurityException("Push state unavailable")

/** A malformed source is retained; cleanup safety is independent of its other typed values. */
internal class MalformedLegacyPushSource(
    val cleanupRequired: Boolean,
) : IllegalArgumentException("Malformed legacy push state")

/** Push-only preference adapter: each editor batch seals and commits one complete logical map. */
@Suppress("TooManyFunctions") // Implements the existing push-only preferences contract.
internal class PushTokenPreferences(
    private val store: KeystoreSecureStore,
    private val lock: Any,
    private val prepare: () -> Unit = {},
) : SharedPreferences {
    override fun getAll(): Map<String, Any> =
        read {
            prepare()
            values().mapValues { decode(it.value) }
        }

    override fun contains(key: String): Boolean =
        read {
            prepare()
            values().containsKey(key)
        }

    override fun getString(
        key: String,
        defValue: String?,
    ): String? = getAll()[key]?.let { it as String } ?: defValue

    override fun getBoolean(
        key: String,
        defValue: Boolean,
    ): Boolean = getAll()[key]?.let { it as Boolean } ?: defValue

    override fun getInt(
        key: String,
        defValue: Int,
    ): Int = getAll()[key]?.let { it as Int } ?: defValue

    override fun getLong(
        key: String,
        defValue: Long,
    ): Long = getAll()[key]?.let { it as Long } ?: defValue

    override fun getFloat(
        key: String,
        defValue: Float,
    ): Float = error("Push state does not contain floats")

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(
        key: String,
        defValues: MutableSet<String>?,
    ): MutableSet<String>? = (getAll()[key] as? Set<String>)?.toMutableSet() ?: defValues?.toMutableSet()

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(listener: OnSharedPreferenceChangeListener) =
        error(
            "Push state does not use preference listeners",
        )

    override fun unregisterOnSharedPreferenceChangeListener(listener: OnSharedPreferenceChangeListener) =
        error(
            "Push state does not use preference listeners",
        )

    /** Observes receipts without writing; the import owner must retain any failed-commit uncertainty. */
    fun hasImported(source: String): Boolean =
        synchronized(lock) {
            source in stringSet(store.readAll()[IMPORTED])
        }

    /** A source is retired only after values and its import receipt are committed together. */
    fun importLegacy(
        source: String,
        legacy: SharedPreferences,
    ) {
        synchronized(lock) {
            val current = store.readAll()
            val imported = stringSet(current[IMPORTED])
            if (source in imported) {
                check(store.replaceAllDurably(current)) { "Push import receipt could not be persisted" }
                cleanupImportedSource(legacy)
                return
            }
            val touched = stringSet(current[TOUCHED])
            val merged = current.toMutableMap()
            val existingWakeEpisode = WAKE_KEYS.any { it in current || it in touched }
            val legacyValues = runCatching { legacy.all }.getOrElse { throw MalformedLegacyPushSource(true) }
            val malformed =
                legacyValues.any { (key, value) ->
                    canImport(key, value, touched, existingWakeEpisode) && !validValue(key, requireNotNull(value))
                }
            if (malformed) {
                // A nonempty or malformed cleanup queue cannot be discarded with the bad value.
                val cleanupRequired =
                    CLEANUP_KEYS.any { key ->
                        val value = legacyValues[key]
                        key in legacyValues && (value !is Set<*> || value.isNotEmpty())
                    }
                throw MalformedLegacyPushSource(cleanupRequired)
            }
            for ((key, value) in legacyValues) {
                if (canImport(key, value, touched, existingWakeEpisode)) {
                    mergeLegacyValue(key, value, current, merged)
                }
            }
            merged[IMPORTED] = JSONArray((imported + source).sorted()).toString()
            // Always re-commit, including after a previous failed commit changed preferences memory.
            check(store.replaceAllDurably(merged)) { "Push state import could not be persisted" }
            cleanupImportedSource(legacy)
        }
    }

    private fun canImport(
        key: String,
        value: Any?,
        touched: Set<String>,
        existingWakeEpisode: Boolean,
    ): Boolean {
        val recognized = key in PUSH_KEYS && value != null
        val superseded = key in touched && key !in CLEANUP_KEYS
        val oldWakeEpisode = key in WAKE_KEYS && existingWakeEpisode
        return recognized && !superseded && !oldWakeEpisode
    }

    private fun mergeLegacyValue(
        key: String,
        value: Any?,
        current: Map<String, String>,
        merged: MutableMap<String, String>,
    ) {
        requireNotNull(value)
        if (key in CLEANUP_KEYS) {
            require(value is Set<*> && value.all { it is String })
            val prior = current[key]?.let(::decode) as? Set<*> ?: emptySet<String>()
            merged[key] = encode(prior + value)
        } else if (key !in current) {
            require(validValue(key, value))
            merged[key] = encode(value)
        }
    }

    @Suppress("TooGenericExceptionCaught") // Normalize platform/codec failures at this storage boundary.
    private fun <T> read(block: () -> T): T =
        synchronized(lock) {
            try {
                block()
            } catch (_: Exception) {
                throw PushTokenStorageException()
            }
        }

    fun cleanupImportedSource(legacy: SharedPreferences) {
        val editor = legacy.edit()
        PUSH_KEYS.forEach(editor::remove)
        editor.commit()
    }

    private fun values(): Map<String, String> = store.readAll().filterKeys { it in PUSH_KEYS }

    private inner class Editor : SharedPreferences.Editor {
        private val updates = mutableMapOf<String, String?>()
        private var clearing = false

        override fun putString(
            key: String,
            value: String?,
        ): SharedPreferences.Editor = apply { updates[key] = value?.let(::encode) }

        override fun putBoolean(
            key: String,
            value: Boolean,
        ): SharedPreferences.Editor = apply { updates[key] = encode(value) }

        override fun putInt(
            key: String,
            value: Int,
        ): SharedPreferences.Editor = apply { updates[key] = encode(value) }

        override fun putLong(
            key: String,
            value: Long,
        ): SharedPreferences.Editor = apply { updates[key] = encode(value) }

        override fun putFloat(
            key: String,
            value: Float,
        ): SharedPreferences.Editor = error("Push state does not contain floats")

        override fun putStringSet(
            key: String,
            values: MutableSet<String>?,
        ): SharedPreferences.Editor = apply { updates[key] = values?.toSet()?.let(::encode) }

        override fun remove(key: String): SharedPreferences.Editor = apply { updates[key] = null }

        override fun clear(): SharedPreferences.Editor = apply { clearing = true }

        override fun commit(): Boolean =
            runCatching {
                read {
                    prepare()
                    val current = store.readAll().toMutableMap()
                    val touched = stringSet(current[TOUCHED]) + if (clearing) PUSH_KEYS else updates.keys
                    if (clearing) PUSH_KEYS.forEach(current::remove)
                    updates.forEach { (key, value) ->
                        require(key in PUSH_KEYS)
                        if (value == null) current.remove(key) else current[key] = value
                    }
                    current[TOUCHED] = JSONArray(touched.sorted()).toString()
                    store.replaceAllDurably(current)
                }
            }.getOrDefault(false)

        override fun apply() {
            if (!commit()) throw PushTokenStorageException()
        }
    }

    private companion object {
        const val IMPORTED = "_imported_sources"
        const val TOUCHED = "_touched_keys"
        val CLEANUP_KEYS =
            setOf(
                KEY_PENDING_CLEARS,
                KEY_PENDING_DISABLES,
            )
        val WAKE_KEYS =
            setOf(
                KEY_PENDING_PUSH_WAKE_CATCH_UP,
                KEY_PENDING_PUSH_WAKE_CATCH_UP_GENERATION,
                KEY_PUSH_WAKE_CATCH_UP_SEQUENCE,
                KEY_WAKE_ATTEMPTS,
                KEY_WAKE_RETRY_AT,
            )
        val PUSH_KEYS =
            setOf(
                KEY_FCM_TOKEN,
                KEY_PENDING_NATIVE_PUSH_REGISTRATION_SYNC,
                KEY_PENDING_PUSH_WAKE_CATCH_UP,
                KEY_PENDING_PUSH_WAKE_CATCH_UP_GENERATION,
                KEY_PUSH_WAKE_CATCH_UP_SEQUENCE,
                KEY_WAKE_ATTEMPTS,
                KEY_WAKE_RETRY_AT,
                KEY_PENDING_CLEARS,
                KEY_PENDING_DISABLES,
            )

        fun stringSet(value: String?): Set<String> =
            value
                ?.let { text ->
                    val array = JSONArray(text)
                    (0 until array.length()).map { array.getString(it) }.toSet()
                }.orEmpty()

        fun validValue(
            key: String,
            value: Any,
        ): Boolean =
            when (key) {
                KEY_PENDING_CLEARS, KEY_PENDING_DISABLES -> value is Set<*> && value.all { it is String }
                KEY_FCM_TOKEN -> value is String
                KEY_PENDING_NATIVE_PUSH_REGISTRATION_SYNC, KEY_PENDING_PUSH_WAKE_CATCH_UP -> value is Boolean
                KEY_WAKE_ATTEMPTS -> value is Int
                KEY_PENDING_PUSH_WAKE_CATCH_UP_GENERATION,
                KEY_PUSH_WAKE_CATCH_UP_SEQUENCE,
                KEY_WAKE_RETRY_AT,
                -> value is Long
                else -> false
            }

        fun encode(value: Any): String {
            val type =
                when (value) {
                    is String -> "string"
                    is Boolean -> "boolean"
                    is Int -> "int"
                    is Long -> "long"
                    is Set<*> -> "set"
                    else -> error("Unsupported push state type")
                }
            return JSONObject()
                .put("type", type)
                .put("value", if (value is Set<*>) JSONArray(value.sortedBy { it.toString() }) else value)
                .toString()
        }

        fun decode(value: String): Any {
            val entry = JSONObject(value)
            return when (entry.getString("type")) {
                "string" -> entry.getString("value")
                "boolean" -> entry.getBoolean("value")
                "int" -> entry.getInt("value")
                "long" -> entry.getLong("value")
                "set" -> stringSet(entry.getJSONArray("value").toString())
                else -> error("Unsupported push state type")
            }
        }
    }
}
