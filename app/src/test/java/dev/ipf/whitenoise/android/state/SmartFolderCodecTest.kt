package dev.ipf.whitenoise.android.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
