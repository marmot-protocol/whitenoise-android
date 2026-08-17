package dev.ipf.whitenoise.android.core

internal typealias NostrTlvFields = Map<Int, List<List<Int>>>

/** Strict bounded-field extraction for NIP-19 TLV payloads. */
internal object NostrTlvCodec {
    fun unique(
        payload: List<Int>,
        type: Int,
        size: Int? = null,
    ): List<Int>? = parse(payload)?.let { fields -> unique(fields, type, size) }

    fun parse(payload: List<Int>): NostrTlvFields? {
        var offset = 0
        var valid = true
        val fields = LinkedHashMap<Int, MutableList<List<Int>>>()
        while (offset < payload.size && valid) {
            if (offset + TLV_HEADER_BYTES > payload.size) {
                valid = false
            } else {
                val type = payload[offset]
                val length = payload[offset + 1]
                offset += TLV_HEADER_BYTES
                if (offset + length > payload.size) {
                    valid = false
                } else {
                    val value = payload.subList(offset, offset + length)
                    if (type != TLV_RELAY || value.isNotEmpty()) {
                        fields.getOrPut(type) { mutableListOf() }.add(value)
                    }
                    offset += length
                }
            }
        }
        return fields.takeIf { valid }
    }

    fun unique(
        fields: NostrTlvFields,
        type: Int,
        size: Int? = null,
    ): List<Int>? = fields[type]?.singleOrNull()?.takeIf { size == null || it.size == size }

    fun optionalUnique(
        fields: NostrTlvFields,
        type: Int,
        size: Int,
    ): List<Int>? = fields[type]?.singleOrNull()?.takeIf { it.size == size }

    fun optionalFieldIsValid(
        fields: NostrTlvFields,
        type: Int,
        size: Int,
    ): Boolean = fields[type]?.let { values -> values.size == 1 && values.single().size == size } ?: true

    private const val TLV_RELAY = 1
    private const val TLV_HEADER_BYTES = 2
}
