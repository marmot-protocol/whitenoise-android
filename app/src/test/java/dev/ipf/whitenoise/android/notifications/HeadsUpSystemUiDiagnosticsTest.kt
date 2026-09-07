package dev.ipf.whitenoise.android.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression coverage for StatusBar's indentation-delimited heads-up subsection. */
class HeadsUpSystemUiDiagnosticsTest {
    /** A failed or unsupported dump must remain distinct from a supported empty heads-up section. */
    @Test
    fun missingHeadsUpBlockIsNotEvidenceOfAnEmptyHeadsUpState() {
        assertNull(HeadsUpSystemUiDiagnostics.headsUpManagerPhoneBlock("Can't find service: activity"))
        assertNull(HeadsUpSystemUiDiagnostics.headsUpManagerPhoneBlock(""))
        assertEquals(
            emptyList<String>(),
            HeadsUpSystemUiDiagnostics.headsUpManagerPhoneBlock("HeadsUpManagerPhone state:"),
        )
    }

    /** Retains exact-key rows from the real nested HeadsUpManagerPhone shape. */
    @Test
    fun extractsExactTargetFromHeadsUpManagerPhoneBlock() {
        val dump =
            """
            StatusBarGoogle state:
                HeadsUpManagerPhone state:
                  mTouchAcceptanceDelay=700
                  mAlertEntries:
                    key=synthetic-key

                StatusBarTouchableRegionManager state:
                  mTouchableRegion=SkRegion()
            """.trimIndent()

        assertEquals(
            listOf("key=synthetic-key"),
            HeadsUpSystemUiDiagnostics.exactTargetLines(dump, "synthetic-key").map(String::trim),
        )
    }

    /** Rejects a different notification whose framework key only extends the target key. */
    @Test
    fun nearCollisionInsideHeadsUpBlockCannotMasqueradeAsTheTarget() {
        val dump =
            """
            StatusBarGoogle state:
                HeadsUpManagerPhone state:
                  mAlertEntries:
                    key=synthetic-key-suffix
            """.trimIndent()

        assertTrue(HeadsUpSystemUiDiagnostics.exactTargetLines(dump, "synthetic-key").isEmpty())
    }

    /** Accepts real event-list and modern key fields while escaping framework-key punctuation. */
    @Test
    fun exactMatcherSupportsFrameworkEventAndModernRows() {
        val key = "0|dev.ipf.whitenoise|42|tag.+(probe)|10001"

        assertTrue(
            HeadsUpSystemUiDiagnostics.lineContainsExactTargetKey(
                "sysui_heads_up_status: [$key,1]",
                key,
            ),
        )
        assertTrue(
            HeadsUpSystemUiDiagnostics.lineContainsExactTargetKey(
                "HeadsUpLog key=$key reason=timeout",
                key,
            ),
        )
        assertTrue(
            HeadsUpSystemUiDiagnostics.lineContainsExactTargetKey(
                "HeadsUpLog key: $key",
                key,
            ),
        )
        assertTrue(
            HeadsUpSystemUiDiagnostics.lineContainsExactTargetKey(
                "HeadsUpManager: show notification $key",
                key,
            ),
        )
        assertTrue(
            HeadsUpSystemUiDiagnostics.lineContainsExactTargetKey(
                "HeadsUpManager: remove notification $key reason: timeout",
                key,
            ),
        )
        assertTrue(!HeadsUpSystemUiDiagnostics.lineContainsExactTargetKey("key=$key-suffix", key))
        assertTrue(!HeadsUpSystemUiDiagnostics.lineContainsExactTargetKey("key=prefix-$key", key))
        assertTrue(!HeadsUpSystemUiDiagnostics.lineContainsExactTargetKey("show notification $key-suffix", key))
        assertTrue(!HeadsUpSystemUiDiagnostics.lineContainsExactTargetKey("key=anything", ""))
    }

    /** Compares only exact-key rows when unrelated and near-collision events surround the baseline. */
    @Test
    fun exactTargetDeltaKeepsAStableFilteredBaseline() {
        val key = "0|dev.ipf.whitenoise|42|tag|10001"
        val targetShow = "sysui_heads_up_status: [$key,1]"
        val targetHide = "HeadsUpLog key=$key reason=timeout"
        val before = listOf("key=other", targetShow, "key=$key-suffix")
        val after = before + listOf("key=unrelated", targetHide)

        assertEquals(
            HeadsUpSystemUiDiagnostics.ExactTargetLineDelta(
                lines = listOf(targetHide),
                baselineStable = true,
            ),
            HeadsUpSystemUiDiagnostics.exactTargetDelta(before, after, key),
        )
    }

    /** Refuses appended evidence when the exact target's rolling-buffer baseline was replaced. */
    @Test
    fun exactTargetDeltaRejectsARotatedBaseline() {
        val key = "0|dev.ipf.whitenoise|42|tag|10001"
        val before = listOf("HeadsUpManager: show notification $key")
        val after = listOf("HeadsUpManager: remove notification $key reason: timeout")

        val delta = HeadsUpSystemUiDiagnostics.exactTargetDelta(before, after, key)

        assertFalse(delta.baselineStable)
        assertTrue(delta.lines.isEmpty())
    }

    /** Rejects a persistent shade-card key found only in a sibling StatusBar section. */
    @Test
    fun siblingShadeSectionCannotMasqueradeAsHeadsUpState() {
        val dump =
            """
            StatusBarGoogle state:
                HeadsUpManagerPhone state:
                  mTouchAcceptanceDelay=700
                  mSnoozeLengthMs=60000
                  now=12345
                  mUser=0
                  snoozed packages: 0
                  mBarState=0
                  mTouchableRegion=SkRegion()

                NotificationEntryManager state:
                  active notification key=synthetic-key
            """.trimIndent()

        assertTrue(HeadsUpSystemUiDiagnostics.exactTargetLines(dump, "synthetic-key").isEmpty())
    }
}
