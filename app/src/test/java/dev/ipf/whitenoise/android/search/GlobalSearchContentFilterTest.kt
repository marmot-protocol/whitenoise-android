package dev.ipf.whitenoise.android.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalSearchContentFilterTest {
    @Test
    fun emptySelectionMeansNoContentConstraint() {
        val selection = GlobalSearchContentFilterSelection.EMPTY

        assertFalse(selection.isActive)
        assertTrue(selection.selectedKinds.isEmpty())
    }

    @Test
    fun togglingAddsAndRemovesTypedKindsWithoutReclassification() {
        var selection = GlobalSearchContentFilterSelection.EMPTY

        selection = selection.toggle(GlobalSearchContentKind.TEXT)
        selection = selection.toggle(GlobalSearchContentKind.LINKS)

        assertEquals(
            setOf(GlobalSearchContentKind.TEXT, GlobalSearchContentKind.LINKS),
            selection.selectedKinds,
        )

        selection = selection.toggle(GlobalSearchContentKind.TEXT)

        assertEquals(setOf(GlobalSearchContentKind.LINKS), selection.selectedKinds)
    }

    @Test
    fun multipleKindsCombineWithOrSemanticsInProjection() {
        val projection =
            projectGlobalSearchRequest(
                query = "docs",
                dateFilter = GlobalSearchDateFilterSelection.AnyTime,
                contentFilter =
                    GlobalSearchContentFilterSelection(
                        selectedKinds =
                            setOf(
                                GlobalSearchContentKind.FILES_DOCUMENTS,
                                GlobalSearchContentKind.ANY_ATTACHMENT,
                            ),
                    ),
                zoneId = java.time.ZoneId.of("UTC"),
                nowMillis = 1_786_462_800_000L,
            )

        assertTrue(projection.requiresTypedMdkContract)
        assertEquals(
            setOf(
                GlobalSearchContentKind.FILES_DOCUMENTS,
                GlobalSearchContentKind.ANY_ATTACHMENT,
            ),
            projection.contentKinds,
        )
    }

    @Test
    fun contentFilterCodecRoundTripsSelectedKindsInStableOrder() {
        val selection =
            GlobalSearchContentFilterSelection(
                selectedKinds =
                    setOf(
                        GlobalSearchContentKind.VOICE_AUDIO,
                        GlobalSearchContentKind.TEXT,
                        GlobalSearchContentKind.IMAGES_VIDEO,
                    ),
            )

        assertEquals(selection, decodeGlobalSearchContentFilter(encodeGlobalSearchContentFilter(selection)))
    }

    @Test
    fun requestProjectionAndsContentWithOtherFilters() {
        val projection =
            projectGlobalSearchRequest(
                query = "trip",
                dateFilter = GlobalSearchDateFilterSelection.Last7Days,
                contentFilter = GlobalSearchContentFilterSelection(setOf(GlobalSearchContentKind.LINKS)),
                zoneId = java.time.ZoneId.of("America/New_York"),
                nowMillis = 1_786_462_800_000L,
            )

        assertTrue(projection.requiresTypedMdkContract)
        assertEquals(setOf(GlobalSearchContentKind.LINKS), projection.contentKinds)
        assertEquals("trip", projection.query)
        assertEquals(
            GlobalSearchEpochBounds(
                startEpochMillisInclusive = 1_785_902_400_000L,
                endEpochMillisExclusive = 1_786_507_200_000L,
            ),
            projection.dateBounds,
        )
    }

    @Test
    fun selectionAndRequestProjectionDefensivelySnapshotMutableContentKinds() {
        val mutableKinds = mutableSetOf(GlobalSearchContentKind.TEXT)
        val selection = GlobalSearchContentFilterSelection(selectedKinds = mutableKinds)
        val projection =
            projectGlobalSearchRequest(
                query = "hello",
                dateFilter = GlobalSearchDateFilterSelection.AnyTime,
                contentFilter = selection,
                zoneId = java.time.ZoneId.of("UTC"),
                nowMillis = 1_786_462_800_000L,
            )

        mutableKinds.add(GlobalSearchContentKind.LINKS)

        assertEquals(setOf(GlobalSearchContentKind.TEXT), selection.selectedKinds)
        assertEquals(setOf(GlobalSearchContentKind.TEXT), projection.contentKinds)
    }
}
