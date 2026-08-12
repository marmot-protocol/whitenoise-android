package dev.ipf.whitenoise.android.search

import java.time.LocalDate
import java.time.ZoneId

private const val DATE_CODEC_PREFIX = "gsd:"
private const val CONTENT_CODEC_PREFIX = "gsc:"

fun encodeGlobalSearchDateFilter(selection: GlobalSearchDateFilterSelection): String =
    DATE_CODEC_PREFIX +
        when (selection) {
            GlobalSearchDateFilterSelection.AnyTime -> "any"
            GlobalSearchDateFilterSelection.Today -> "today"
            GlobalSearchDateFilterSelection.Last7Days -> "last7"
            GlobalSearchDateFilterSelection.Last30Days -> "last30"
            is GlobalSearchDateFilterSelection.Custom ->
                "custom:${selection.from}:${selection.to}:${selection.zoneId.id}"
        }

fun decodeGlobalSearchDateFilter(encoded: String): GlobalSearchDateFilterSelection {
    if (!encoded.startsWith(DATE_CODEC_PREFIX)) return GlobalSearchDateFilterSelection.AnyTime
    return when (val payload = encoded.removePrefix(DATE_CODEC_PREFIX)) {
        "any" -> GlobalSearchDateFilterSelection.AnyTime
        "today" -> GlobalSearchDateFilterSelection.Today
        "last7" -> GlobalSearchDateFilterSelection.Last7Days
        "last30" -> GlobalSearchDateFilterSelection.Last30Days
        else -> decodeCustomDateFilter(payload)
    }
}

private const val CUSTOM_CODEC_PART_COUNT = 3

private fun decodeCustomDateFilter(payload: String): GlobalSearchDateFilterSelection {
    if (!payload.startsWith("custom:")) return GlobalSearchDateFilterSelection.AnyTime
    val parts = payload.removePrefix("custom:").split(':', limit = CUSTOM_CODEC_PART_COUNT)
    val decoded =
        parts
            .takeIf { it.size == CUSTOM_CODEC_PART_COUNT }
            ?.let { customParts ->
                runCatching {
                    GlobalSearchDateFilterSelection
                        .Custom(
                            from = LocalDate.parse(customParts[0]),
                            to = LocalDate.parse(customParts[1]),
                            zoneId = ZoneId.of(customParts[2]),
                        ).takeUnless { it.from.isAfter(it.to) }
                }.getOrNull()
            }
    return decoded ?: GlobalSearchDateFilterSelection.AnyTime
}

fun encodeGlobalSearchContentFilter(selection: GlobalSearchContentFilterSelection): String {
    if (selection.selectedKinds.isEmpty()) return CONTENT_CODEC_PREFIX
    val orderedKinds =
        GlobalSearchContentKind.entries.filter { it in selection.selectedKinds }
    return CONTENT_CODEC_PREFIX + orderedKinds.joinToString(",") { it.name }
}

fun decodeGlobalSearchContentFilter(encoded: String): GlobalSearchContentFilterSelection {
    if (!encoded.startsWith(CONTENT_CODEC_PREFIX)) return GlobalSearchContentFilterSelection.EMPTY
    val payload = encoded.removePrefix(CONTENT_CODEC_PREFIX)
    val kinds =
        if (payload.isEmpty()) {
            emptySet()
        } else {
            payload
                .split(',')
                .mapNotNull { token -> runCatching { GlobalSearchContentKind.valueOf(token) }.getOrNull() }
                .toSet()
        }
    return GlobalSearchContentFilterSelection(selectedKinds = kinds)
}
