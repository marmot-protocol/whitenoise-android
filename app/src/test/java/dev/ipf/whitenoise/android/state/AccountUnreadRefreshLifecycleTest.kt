package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.functionBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AccountUnreadRefreshLifecycleTest {
    @Test
    fun bulkRefreshPublishesFreshnessBeforeExactFoldsAndGenerationFencesBothWrites() {
        val body = appStateSource().functionBody("refreshAccountUnreadCounts")
        val interim = body.indexOf("val interimPublication")
        val exactFolds = body.indexOf("val refreshedPairs")
        val firstGuard = body.indexOf("if (!refreshIsCurrent()) return")
        val finalGuard = body.lastIndexOf("if (!refreshIsCurrent()) return")
        val finalPublication = body.lastIndexOf("publishAccountUnreadRefresh(")

        assertTrue(firstGuard in 0 until interim)
        assertTrue(interim in 0 until exactFolds)
        assertTrue(finalGuard > exactFolds)
        assertTrue(finalPublication > finalGuard)
        assertTrue(
            "atomic publisher must receive the generation fence",
            "refreshGeneration = refreshGeneration" in body,
        )
    }

    @Test
    fun unreadRefreshFailuresLogNoAccountGroupOrThrowablePayload() {
        val source = appStateSource()
        val bulk = source.functionBody("refreshAccountUnreadCounts")
        val exact = source.functionBody("refreshEffectiveAccountUnreadCount")

        assertFalse("bulk failure logs must not serialize throwable text", "readableMessage()" in bulk)
        assertFalse("exact failure logs must not serialize throwable text", "readableMessage()" in exact)
        assertFalse("exact failure logs must not include account labels", "ref.take" in exact)
        assertFalse("exact failure logs must not include group ids", "groupIdHex.take" in exact)
        assertFalse("throwables may contain account or message payload", "appStateDebug(error)" in exact)
        assertFalse("throwables may contain account or message payload", "appStateDebug(it)" in exact)
    }

    @Test
    fun equalAuthoritativeEvidenceFencesStaleWorkWithoutMutatingComposeState() {
        val source = appStateSource()
        val directUpdate = source.functionBody("updateAccountUnreadValue")
        val bulkPublication = source.functionBody("publishAccountUnreadRefresh")

        assertTrue(
            "direct writers must revision-fence every authoritative update",
            "accountUnreadRevision += 1L" in directUpdate,
        )
        assertTrue(
            "equal direct values must not invalidate Compose",
            "if (previous != next)" in directUpdate,
        )
        assertTrue(
            "bulk writers must keep race revisions outside render state",
            "accountUnreadRevisions" in bulkPublication,
        )
        assertTrue(
            "equal bulk values must not invalidate Compose",
            "if (accountUnreadValues != mergedValues)" in bulkPublication,
        )
    }

    @Test
    fun previousPositive_partialSummary_successfulZeroFoldConvergesWithoutFalseZeroState() {
        val previous = confirmed(unreadCount = 5uL)

        val partial =
            accountUnreadValueAfterRefresh(
                rawCount = null,
                previous = previous,
                exactUnreadCount = null,
                exactHasManualUnread = null,
            )

        assertEquals("retained evidence must not be destroyed", 5uL, partial.unreadCount)
        assertEquals(AccountUnreadFreshness.UNKNOWN, partial.freshness)
        assertEquals("unknown is not a confirmed zero or positive badge", 0uL, partial.confirmedUnreadCount())
        assertFalse(partial.showsUnreadDot())

        val reconciled =
            accountUnreadValueAfterRefresh(
                rawCount = null,
                previous = partial,
                exactUnreadCount = 0uL,
                exactHasManualUnread = false,
            )

        assertEquals(0uL, reconciled.unreadCount)
        assertEquals(AccountUnreadFreshness.CONFIRMED, reconciled.freshness)
        assertEquals(0uL, reconciled.confirmedUnreadCount())
        assertFalse(reconciled.showsUnreadDot())
    }

    @Test
    fun failedThenSuccessfulRefreshDeterministicallyReplacesUnknownValue() {
        val failed =
            accountUnreadValueAfterRefresh(
                rawCount = null,
                previous = confirmed(unreadCount = 2uL),
                exactUnreadCount = null,
                exactHasManualUnread = null,
            )
        val recovered =
            accountUnreadValueAfterRefresh(
                rawCount = 7uL,
                previous = failed,
                exactUnreadCount = null,
                exactHasManualUnread = null,
            )

        assertEquals(AccountUnreadFreshness.UNKNOWN, failed.freshness)
        assertEquals(7uL, recovered.confirmedUnreadCount())
        assertTrue(recovered.showsUnreadDot())
    }

    @Test
    fun genuineUnreadSummaryRemainsConfirmedWhenAnotherAccountIsOmitted() {
        val values =
            rawAccountUnreadValues(
                accounts = listOf(account("active", "aa"), account("background", "bb")),
                rawCountsByAccountId = mapOf("aa" to 3uL),
                previous =
                    mapOf(
                        "active" to confirmed(2uL),
                        "background" to confirmed(4uL),
                    ),
            )

        assertEquals(3uL, values.getValue("active").confirmedUnreadCount())
        assertTrue(values.getValue("active").showsUnreadDot())
        assertEquals(AccountUnreadFreshness.UNKNOWN, values.getValue("background").freshness)
        assertEquals(4uL, values.getValue("background").unreadCount)
        assertEquals(0uL, values.getValue("background").confirmedUnreadCount())
    }

    @Test
    fun coldStateDoesNotResurrectAnUnreadBadgeBeforeSuccessfulLocalEvidence() {
        val cold =
            accountUnreadValueAfterRefresh(
                rawCount = null,
                previous = null,
                exactUnreadCount = null,
                exactHasManualUnread = null,
            )
        val localRows =
            accountUnreadValueAfterRefresh(
                rawCount = null,
                previous = cold,
                exactUnreadCount = 0uL,
                exactHasManualUnread = false,
            )

        assertEquals(AccountUnreadFreshness.UNKNOWN, cold.freshness)
        assertFalse(cold.showsUnreadDot())
        assertEquals(AccountUnreadFreshness.CONFIRMED, localRows.freshness)
        assertFalse(localRows.showsUnreadDot())
    }

    private fun confirmed(unreadCount: ULong) =
        AccountUnreadValue(
            unreadCount = unreadCount,
            freshness = AccountUnreadFreshness.CONFIRMED,
            hasManualUnread = false,
        )

    private fun account(
        label: String,
        accountIdHex: String,
    ) = dev.ipf.marmotkit.AccountSummaryFfi(
        label = label,
        accountIdHex = accountIdHex,
        localSigning = true,
        externalSigning = false,
        signedOut = false,
        running = true,
    )

    private fun appStateSource(): String =
        sequenceOf(
            File("src/main/java/dev/ipf/whitenoise/android/state/AppState.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/state/AppState.kt"),
        ).firstOrNull(File::isFile)?.readText() ?: error("Missing AppState.kt")
}
