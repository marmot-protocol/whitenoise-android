package dev.ipf.whitenoise.android.ui

import dev.ipf.whitenoise.android.functionBody
import dev.ipf.whitenoise.android.kotlinBlockFrom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Invariant gate for temporary-banner placement and routing (#3312).
 *
 * Temporary in-app notices, the app snackbar and the global confirmation strip, render only in a top slot below
 * the owning screen's bars, never in a bottom host that competes with a composer or a form bar. One host chooses
 * the single owning slot, gated by app lock, and every surface that opts out of its scaffold slot provides its own.
 * Conversation-scoped notices are scoped to their conversation's subtree, and no bottom-specific inset or height
 * compensation for banners remains anywhere in production.
 */
class TopNoticePlacementCoverageTest {
    /** No production snackbar host is a scaffold's bottom slot or aligned to the bottom of its surface. */
    @Test
    fun noProductionSnackbarHostSitsAtTheBottom() {
        mainSources().forEach { (path, source) ->
            assertFalse("$path passes a snackbar host to a bottom scaffold slot", "snackbarHost =" in source)
            hostCalls(source).forEach { call ->
                assertFalse("$path aligns a snackbar host to the bottom", "BottomCenter" in call)
                assertFalse("$path pads a snackbar host above the keyboard", "imePadding" in call)
            }
        }
    }

    /** Bottom-specific banner compensation is gone and cannot return under another call site. */
    @Test
    fun noBottomBannerCompensationRemains() {
        val retired = listOf("LocalSnackbarBottomInset", "LocalSnackbarContentInset", "reserveSnackbarSpace")
        mainSources().forEach { (path, source) ->
            retired.forEach { name -> assertFalse("$path still uses $name", name in source) }
        }
        val group = mainSource("ui/group/GroupDetailsScreen.kt")
        assertFalse("group notices must not use a bottom bar", "ConversationTransientNotice(" in group)
    }

    /** The root installs exactly one host, gated by app lock, with its fallback slot composed before every screen. */
    @Test
    fun theRootOwnsOneLockGatedHostAndClaimsFirst() {
        val root = mainSource("ui/WhiteNoiseApp.kt").functionBody("WhiteNoiseApp")
        assertEquals(1, Regex("""TopNoticeHost\(""").findAll(root).count())
        val lockGate = "presentationAllowed = { !appState.appLockScreenVisible }"
        assertTrue("app lock must hide every banner", lockGate in root)
        assertTrue("the host must be provided to the whole app", "LocalTopNoticeHost provides topNoticeHost" in root)
        val fallback = root.indexOf("TopNoticeSlot(Modifier.zIndex(1f)")
        val lockBranch = root.indexOf("if (appState.appLockScreenVisible) {", fallback)
        assertTrue("the root fallback must claim before any screen composes", fallback in 0 until lockBranch)
    }

    /** The shell's slot follows its persistent top strips and precedes the destination and durable bottom controls. */
    @Test
    fun theShellSlotSitsBetweenItsTopStripsAndTheDestination() {
        val layout = mainSource("ui/WhiteNoiseApp.kt").functionBody("ShellTransientNoticeLayout")
        val strips = layout.indexOf("persistentTopContent()")
        val region = layout.indexOf("TopNoticeRegion(")
        val content = layout.indexOf("content()")
        val bottom = layout.indexOf("persistentBottomContent()")
        assertTrue(strips in 0 until region && region < content && content < bottom)
        assertFalse("nothing temporary may follow the destination", "Notice" in layout.substring(content))
        assertTrue("the shell never renders conversation-scoped notices", "scope = null" in layout)
    }

    /** Scaffold slots sit directly below the top bar, and every opt-out names the slot that replaces it. */
    @Test
    fun everyScaffoldOptOutHasItsOwnSlot() {
        val scaffoldSource = mainSource("ui/common/WhiteNoiseScaffold.kt")
        val scaffold = scaffoldSource.functionBody("WhiteNoiseScaffold")
        assertTrue("the scaffold's bar slot hosts the banner slot", "ScaffoldTopBarWithNoticeSlot(" in scaffold)
        val barSlot = scaffoldSource.functionBody("ScaffoldTopBarWithNoticeSlot")
        val topBar = barSlot.indexOf("topBar()")
        assertTrue("the slot follows the bar", barSlot.indexOf("TopNoticeSlot()") > topBar && topBar >= 0)
        val insetGuard = "if (bar.height == 0) contentWindowInsets.getTop(this) else 0"
        assertTrue("a bar-less scaffold must keep its top inset under the slot", insetGuard in barSlot)

        val conversation = mainSource("ui/conversation/ConversationScreen.kt")
        assertTrue("topNoticeSlot = false" in conversation)
        val row =
            mainSource("ui/conversation/ConversationTransientNotice.kt")
                .functionBody("ConversationTransientNoticeLayout")
        assertTrue("the conversation row is its slot", "rememberTopNoticeSlot()" in row && "TopNoticeRegion(" in row)
        val rowInUse = "ConversationTransientNoticeLayout(" in conversation
        assertTrue("the conversation's notice layout must stay in use", rowInUse)

        val chats = mainSource("ui/chats/ChatsScreen.kt")
        assertTrue("topNoticeSlot = false" in chats)
        val frame = chats.functionBody("ChatListBodyFrame")
        val playback = frame.indexOf("ttsTransport()")
        assertTrue("the list slot follows its playback strip", frame.indexOf("TopNoticeSlot()") > playback)

        val startup = mainSource("ui/common/StartupScreens.kt")
        assertTrue("a bar-less startup surface relies on the root slot", "topNoticeSlot = false" in startup)
        val optOuts = mainSources().count { (_, source) -> "topNoticeSlot = false" in source }
        assertEquals("a new opt-out needs a reviewed replacement slot", 3, optOuts)
    }

    /** A modal claims the banners below its playback strip, so nothing is rendered behind it in the window below. */
    @Test
    fun modalsClaimTheBannersBelowTheirPlaybackStrip() {
        val dialog = mainSource("ui/conversation/PlaybackDialog.kt").functionBody("PlaybackDialog")
        val strip = dialog.indexOf("PlaybackTransportBar(")
        val region = dialog.indexOf("TopNoticeRegion(")
        val content = dialog.indexOf("content()", region)
        assertTrue("rememberTopNoticeSlot()" in dialog)
        assertTrue(strip in 0 until region && region < content)
    }

    /** Conversation-scoped banners are scoped to the routed conversation's whole subtree, modals included. */
    @Test
    fun theRoutedConversationScopesItsBanners() {
        val shell = mainSource("ui/navigation/MainShell.kt")
        val provide = shell.indexOf("LocalTopNoticeScope provides")
        val screen = shell.indexOf("ConversationScreen(", provide)
        assertTrue("the scope must wrap the conversation", provide >= 0 && screen > provide)
        val scope = shell.substring(provide, screen)
        assertTrue("ConversationNoticeDestination(content.accountRef, chat.group.groupIdHex)" in scope)
    }

    /** Banner placement reads one host decision: the owning slot, its scope, and the lock gate. */
    @Test
    fun slotsRenderOnlyThroughTheHostDecision() {
        val notice = mainSource("ui/common/TopNotice.kt")
        val region = notice.functionBody("TopNoticeRegion")
        assertTrue("if (!slot.owns) return" in region)
        assertTrue("topNoticeFor(notice, owner = true, scope = scope)" in region)
        val owns = notice.substringAfter("fun owns(").substringBefore("\n")
        assertTrue("presentationAllowed() && claims.lastOrNull() === claim" in owns)
    }

    /** Snackbar calls in one source, as their balanced argument lists. */
    private fun hostCalls(source: String): List<String> =
        Regex("""\b(?:WhiteNoiseSnackbarHost|SnackbarHost)\(""")
            .findAll(source)
            .map { match ->
                source.kotlinBlockFrom(
                    openDelimiter = match.range.last,
                    description = "snackbar host call",
                    opening = '(',
                    closing = ')',
                )
            }.toList()

    /** Every production Kotlin source with its path relative to the source root. */
    private fun mainSources(): List<Pair<String, String>> =
        sourceRoot()
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(sourceRoot()).path to it.readText() }
            .toList()

    /** One production source by its path below the app package. */
    private fun mainSource(path: String): String = File(sourceRoot(), path).readText()

    /** The app package's source root, from either the module or the repository working directory. */
    private fun sourceRoot(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android"),
            File("app/src/main/java/dev/ipf/whitenoise/android"),
        ).first(File::isDirectory)
}
