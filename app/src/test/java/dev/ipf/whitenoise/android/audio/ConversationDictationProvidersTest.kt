package dev.ipf.whitenoise.android.audio

import android.content.ComponentName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ConversationDictationProvidersTest {
    private val first = surface("one", ConversationDictationSurfaceKind.Service)
    private val second = surface("two", ConversationDictationSurfaceKind.Service)

    @Test
    fun groupsSurfacesByPackageButRetainsEngineChoices() {
        val activity =
            first.copy(
                kind = ConversationDictationSurfaceKind.Activity,
                component = ComponentName("one", "one.Window"),
            )
        val otherEngine = first.copy(component = ComponentName("one", "one.OtherEngine"))
        val providers = conversationDictationProviders(listOf(first, otherEngine, activity))
        assertEquals(1, providers.size)
        assertEquals(2, providers.single().choices.size)
        assertTrue(providers.single().choices.all { it.activity == activity.component })
    }

    @Test
    fun androidThenExactSavedThenSoleProviderOtherwiseChooser() {
        val choices = conversationDictationProviders(listOf(first, second)).flatMap { it.choices }
        assertEquals(choices[0], resolveConversationDictationProvider(first.component, choices[1], choices))
        assertEquals(choices[1], resolveConversationDictationProvider(null, choices[1], choices))
        assertEquals(
            choices[1],
            resolveConversationDictationProvider(ComponentName("gone", "gone.Engine"), choices[1], choices),
        )
        assertNull(resolveConversationDictationProvider(null, null, choices))
        assertEquals(choices[0], resolveConversationDictationProvider(null, null, choices.take(1)))
    }

    @Test
    fun staleVersionOrRemovedComponentIsNotAnExactSavedChoice() {
        val choices = conversationDictationProviders(listOf(first, second)).flatMap { it.choices }
        assertNull(resolveConversationDictationProvider(null, choices[0].copy(versionCode = 99), choices))
        assertNull(
            resolveConversationDictationProvider(
                null,
                choices[0].copy(service = ComponentName("one", "one.Gone")),
                choices,
            ),
        )
    }

    @Test
    fun excludesOnlyExactInternalFutoComponentAndPreservesItsActivity() {
        val dummy = first.copy(component = ComponentName("org.futo.voiceinput", "org.futo.voiceinput.DummyService"))
        val window =
            dummy.copy(
                kind = ConversationDictationSurfaceKind.Activity,
                component = ComponentName("org.futo.voiceinput", "org.futo.voiceinput.Window"),
            )
        val legitimate = first.copy(component = ComponentName("other", "other.DummyService"))
        val providers = conversationDictationProviders(listOf(dummy, window, legitimate))
        assertNull(
            providers
                .first { it.packageName == "org.futo.voiceinput" }
                .choices
                .single()
                .service,
        )
        assertEquals(
            legitimate.component,
            providers
                .first { it.packageName == "other" }
                .choices
                .single()
                .service,
        )
    }

    @Test
    fun unknownServiceIsNeverLabeledCompatibleAndKeyboardIsNotAnActivity() {
        val unknown = conversationDictationProviders(listOf(first)).single().choices.single()
        assertEquals(ConversationDictationProviderCapability.Unknown, unknown.capability)
        assertEquals(
            ConversationDictationProviderCapability.InApp,
            unknown.copy(callerAudio = ConversationDictationCallerAudioRequirement.Supported).capability,
        )
        assertEquals(
            ConversationDictationProviderCapability.Activity,
            unknown.copy(activity = ComponentName("one", "one.Window")).capability,
        )
        val keyboard =
            conversationDictationProviders(listOf(first.copy(kind = ConversationDictationSurfaceKind.Keyboard)))
                .single()
                .choices
                .single()
        assertEquals(ConversationDictationProviderCapability.KeyboardOnly, keyboard.capability)
        assertTrue(conversationDictationProviders(emptyList()).isEmpty())
    }

    @Test
    fun activityIntentIsExplicit() {
        val component = ComponentName("one", "one.Window")
        val intent = conversationDictationRecognitionActivityIntent(component)
        assertEquals(component, intent.component)
        assertEquals("one", intent.`package`)
    }

    private fun surface(
        pkg: String,
        kind: ConversationDictationSurfaceKind,
    ) = ConversationDictationSurface(ComponentName(pkg, "$pkg.Engine"), 7, pkg, "Engine", kind)
}
