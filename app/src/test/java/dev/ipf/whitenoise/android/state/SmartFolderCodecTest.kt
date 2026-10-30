package dev.ipf.whitenoise.android.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.ui.settings.quickFolderFields
import dev.ipf.whitenoise.android.ui.settings.quickMode
import dev.ipf.whitenoise.android.ui.settings.resetQuickFilters
import dev.ipf.whitenoise.android.ui.settings.sameQuickFilterPayload
import dev.ipf.whitenoise.android.ui.settings.supportsQuickFilters
import dev.ipf.whitenoise.android.ui.settings.toggleQuickFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SmartFolderCodecTest {
    private val tree =
        SmartFolderFilter.Group(
            children =
                listOf(
                    SmartFolderFilter.Condition(FolderField.UNREAD, FolderMode.NONE),
                    SmartFolderFilter.Group(
                        all = false,
                        not = true,
                        children =
                            listOf(
                                SmartFolderFilter.Condition(
                                    FolderField.PARTICIPANTS,
                                    FolderMode.EXCLUDES,
                                    setOf("a".repeat(64)),
                                ),
                                SmartFolderFilter.Condition(
                                    FolderField.TITLE,
                                    FolderMode.CONTAINS,
                                    setOf("Release"),
                                ),
                            ),
                    ),
                ),
        )

    @Test fun versionedRoundTripAndUnknownVersionsFailClosed() {
        val raw = SmartFolderCodec.encode(tree)
        assertEquals(tree, SmartFolderCodec.decode(raw))
        assertNull(SmartFolderCodec.decode(raw.replace("\"version\":1", "\"version\":99")))
        assertNull(SmartFolderCodec.decode("{"))
    }

    @Test fun boundsAndInvalidCombinationsAreRejectedWithoutTruncation() {
        val leaf = SmartFolderFilter.Condition(FolderField.UNREAD)
        assertFalse(SmartFolderCodec.valid(SmartFolderFilter.Group(children = List(64) { leaf })))
        var nested = SmartFolderFilter.Group(children = listOf(leaf))
        repeat(5) { nested = SmartFolderFilter.Group(children = listOf(nested)) }
        assertFalse(SmartFolderCodec.valid(nested))
        assertFalse(
            SmartFolderCodec.valid(
                SmartFolderFilter.Group(
                    children =
                        listOf(
                            SmartFolderFilter.Condition(
                                FolderField.UNREAD,
                                FolderMode.CONTAINS,
                            ),
                        ),
                ),
            ),
        )
        assertFalse(
            SmartFolderCodec.valid(
                SmartFolderFilter.Group(
                    children =
                        listOf(
                            SmartFolderFilter.Condition(
                                FolderField.TITLE,
                                FolderMode.CONTAINS,
                                setOf("x".repeat(257)),
                            ),
                        ),
                ),
            ),
        )
        assertTrue(SmartFolderCodec.valid(SmartFolderFilter.Group()))
        assertFalse(SmartFolderCodec.valid(SmartFolderFilter.Group(children = listOf(SmartFolderFilter.Group()))))
    }

    /** Every paired mode round-trips through the codec, and clearing a choice leaves the other conditions in order. */
    @Test fun pairedPredicatesRoundTripEveryModeAndRetainUneditedOrder() {
        val accepted = SmartFolderFilter.Condition(FolderField.ACCEPTED)
        val archived = SmartFolderFilter.Condition(FolderField.ARCHIVED, FolderMode.NONE)
        var root = SmartFolderFilter.Group(children = listOf(archived, accepted))
        quickFolderFields.forEach { field ->
            val positive = if (field == FolderField.TYPE) FolderMode.DIRECT else FolderMode.PRESENT
            val negative = if (field == FolderField.TYPE) FolderMode.GROUP else FolderMode.NONE
            root = root.toggleQuickFilter(field, positive)
            assertEquals(root, SmartFolderCodec.decode(SmartFolderCodec.encode(root)))
            assertEquals(positive, root.quickMode(field))
            root = root.toggleQuickFilter(field, negative)
            assertEquals(root, SmartFolderCodec.decode(SmartFolderCodec.encode(root)))
            root = root.toggleQuickFilter(field, negative)
            assertNull(root.quickMode(field))
        }
        assertEquals(listOf(accepted), root.children)
        val withUnread =
            SmartFolderFilter
                .Group(children = listOf(archived, accepted))
                .toggleQuickFilter(FolderField.UNREAD, FolderMode.PRESENT)
        assertEquals(listOf(archived, accepted), withUnread.children.take(2))
        assertEquals(listOf(accepted), withUnread.resetQuickFilters().children)
    }

    /** Rules the paired controls cannot represent ignore quick edits and keep their exact encoding. */
    @Test fun unsupportedAndRepeatedTreesRejectQuickEditsWithoutChangingTheirEncoding() {
        val unread = SmartFolderFilter.Condition(FolderField.UNREAD)
        val roots =
            listOf(
                tree,
                SmartFolderFilter.Group(all = false, children = listOf(unread)),
                SmartFolderFilter.Group(not = true, children = listOf(unread)),
                SmartFolderFilter.Group(children = listOf(unread, unread.copy(mode = FolderMode.NONE))),
                SmartFolderFilter.Group(children = listOf(unread.copy(not = true))),
                SmartFolderFilter.Group(children = listOf(unread.copy(mode = FolderMode.DIRECT))),
                SmartFolderFilter.Group(
                    children = listOf(SmartFolderFilter.Condition(FolderField.TYPE, FolderMode.NONE)),
                ),
                SmartFolderFilter.Group(
                    children = listOf(SmartFolderFilter.Condition(FolderField.ACCEPTED, FolderMode.GROUP)),
                ),
                SmartFolderFilter.Group(
                    children =
                        listOf(
                            SmartFolderFilter.Condition(
                                FolderField.TITLE,
                                FolderMode.CONTAINS,
                                setOf("team"),
                            ),
                        ),
                ),
            )
        roots.forEach { root ->
            val encoded = SmartFolderCodec.encode(root)
            assertFalse(root.supportsQuickFilters())
            assertEquals(encoded, SmartFolderCodec.encode(root.toggleQuickFilter(FolderField.UNREAD, FolderMode.NONE)))
            assertEquals(encoded, SmartFolderCodec.encode(root.resetQuickFilters()))
        }
        val empty = SmartFolderFilter.Group()
        assertEquals(empty, empty.toggleQuickFilter(FolderField.UNREAD, FolderMode.DIRECT))
        assertEquals(empty, empty.toggleQuickFilter(FolderField.ACCEPTED, FolderMode.PRESENT))
    }

    /** Quick payloads that differ only in condition order are one rule; any other difference still counts. */
    @Test fun quickPayloadsCompareWithoutConditionOrder() {
        val archived = SmartFolderFilter.Condition(FolderField.ARCHIVED, FolderMode.NONE)
        val accepted = SmartFolderFilter.Condition(FolderField.ACCEPTED)
        val saved = SmartFolderCodec.encode(SmartFolderFilter.Group(children = listOf(archived, accepted)))
        val reordered = SmartFolderCodec.encode(SmartFolderFilter.Group(children = listOf(accepted, archived)))
        assertTrue(sameQuickFilterPayload(saved, reordered))
        assertTrue(sameQuickFilterPayload(null, null))
        assertFalse(sameQuickFilterPayload(saved, null))
        val other = archived.copy(mode = FolderMode.PRESENT)
        val changed = SmartFolderCodec.encode(SmartFolderFilter.Group(children = listOf(accepted, other)))
        assertFalse(sameQuickFilterPayload(saved, changed))
        val any = SmartFolderFilter.Group(all = false, children = listOf(archived, accepted))
        val anyReordered = any.copy(children = listOf(accepted, archived))
        assertFalse(sameQuickFilterPayload(SmartFolderCodec.encode(any), SmartFolderCodec.encode(anyReordered)))
    }

    @Test fun atomicCommitReloadAccountIsolationAndUnsupportedPayloadPreservation() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val prefs = app.getSharedPreferences("smart-folder-codec-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val store = ChatFolderPreferences(app, prefs)
        store.foldersFor("account-a")
        val raw = SmartFolderCodec.encode(tree)
        val rule = ChatFolderRule(smartFilter = raw)
        val folder = store.commitFolderDraft("account-a", null, "Agents", "", setOf("manual"), rule)!!
        assertEquals(rule, ChatFolderPreferences(app, prefs).folderRule("account-a", folder.id))
        assertNull(store.folderRule("account-b", folder.id))
        val future = raw.replace("\"version\":1", "\"version\":99")
        assertNotNull(
            store.commitFolderDraft(
                "account-a",
                folder.id,
                "Agents renamed",
                "",
                setOf("manual"),
                rule.copy(smartFilter = future),
            ),
        )
        assertEquals(
            future,
            ChatFolderPreferences(
                app,
                prefs,
            ).folderRule(
                "account-a",
                folder.id,
            )?.smartFilter,
        )
    }
}
