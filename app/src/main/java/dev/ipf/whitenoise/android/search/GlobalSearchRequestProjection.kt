package dev.ipf.whitenoise.android.search

import java.time.ZoneId

data class GlobalSearchRequestProjection(
    val query: String,
    val dateBounds: GlobalSearchEpochBounds?,
    val contentKinds: Set<GlobalSearchContentKind>,
) {
    val requiresTypedMdkContract: Boolean
        get() = dateBounds != null || contentKinds.isNotEmpty()
}

fun projectGlobalSearchRequest(
    query: String,
    dateFilter: GlobalSearchDateFilterSelection,
    contentFilter: GlobalSearchContentFilterSelection,
    zoneId: ZoneId,
    nowMillis: Long,
): GlobalSearchRequestProjection =
    GlobalSearchRequestProjection(
        query = query.trim(),
        dateBounds = dateFilter.resolveEpochBounds(nowMillis = nowMillis, zoneId = zoneId),
        contentKinds = contentFilter.selectedKinds.toSet(),
    )
