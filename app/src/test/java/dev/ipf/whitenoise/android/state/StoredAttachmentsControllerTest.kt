package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The largest-first attachment list and its selection. */
@OptIn(ExperimentalCoroutinesApi::class)
class StoredAttachmentsControllerTest {
    /** Pages load in order and the list knows when it is complete. */
    @Test fun pagesLoadInOrderUntilComplete() =
        runTest {
            val pages = listOf(listOf(attachment("a", 9)), listOf(attachment("b", 4)))
            val access = FakeStorageAccess().apply { attachmentPages = pages }
            val controller = StoredAttachmentsController(access, this, groupIdHex = null)
            controller.reload()
            runCurrent()
            assertFalse(controller.state.value.complete)
            controller.loadMore()
            runCurrent()
            assertEquals(
                listOf("a", "b"),
                controller.state.value.items
                    .map { it.messageIdHex },
            )
            assertTrue(controller.state.value.complete)
            controller.close()
        }

    /** Selection tracks keys, sums bytes, and select-all toggles every loaded row. */
    @Test fun selectionCountsBytesAndSelectsAll() =
        runTest {
            val pages = listOf(listOf(attachment("a", 9), attachment("b", 4)))
            val access = FakeStorageAccess().apply { attachmentPages = pages }
            val controller = StoredAttachmentsController(access, this, groupIdHex = "g")
            controller.reload()
            runCurrent()
            val first =
                controller.state.value.items
                    .first()
                    .key
            controller.toggle(first)
            assertEquals(9, controller.state.value.selectedBytes)
            assertTrue(controller.state.value.selecting)
            controller.selectAll(true)
            assertEquals(13, controller.state.value.selectedBytes)
            controller.selectAll(false)
            assertFalse(controller.state.value.selecting)
            controller.close()
        }

    /** Reloading keeps the selection only for rows still listed, so removed rows never stay selected. */
    @Test fun reloadDropsSelectionForRowsThatLeft() =
        runTest {
            val pages = listOf(listOf(attachment("a", 9), attachment("b", 4)))
            val access = FakeStorageAccess().apply { attachmentPages = pages }
            val controller = StoredAttachmentsController(access, this, groupIdHex = null)
            controller.reload()
            runCurrent()
            controller.selectAll(true)
            access.attachmentPages = listOf(listOf(attachment("b", 4)))
            controller.reload()
            runCurrent()
            assertEquals(
                listOf("b"),
                controller.state.value.items
                    .map { it.messageIdHex },
            )
            assertEquals(setOf(attachment("b", 4).key), controller.state.value.selected)
            controller.close()
        }

    /** A list read after the owner changed does nothing. */
    @Test fun retiredOwnerDoesNotLoad() =
        runTest {
            val access = FakeStorageAccess().apply { current = false }
            val controller = StoredAttachmentsController(access, this, groupIdHex = null)
            controller.reload()
            runCurrent()
            assertTrue(
                controller.state.value.items
                    .isEmpty(),
            )
            assertFalse(controller.state.value.loading)
            controller.close()
        }
}
