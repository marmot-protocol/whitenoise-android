package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.ui.state.ToggleableState
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatFolderTriStateTest {
    @Test
    fun allTargetsInFolderIsOn() {
        assertEquals(ToggleableState.On, chatFolderTriState(listOf("g1", "g2"), setOf("g1", "g2", "g3")))
    }

    @Test
    fun someTargetsInFolderIsIndeterminate() {
        assertEquals(ToggleableState.Indeterminate, chatFolderTriState(listOf("g1", "g2"), setOf("g1")))
    }

    @Test
    fun noTargetsInFolderIsOff() {
        assertEquals(ToggleableState.Off, chatFolderTriState(listOf("g1", "g2"), setOf("g9")))
        assertEquals(ToggleableState.Off, chatFolderTriState(emptyList(), setOf("g1")))
    }

    @Test
    fun singleTargetTogglesBetweenOnAndOff() {
        assertEquals(ToggleableState.On, chatFolderTriState(listOf("g1"), setOf("g1")))
        assertEquals(ToggleableState.Off, chatFolderTriState(listOf("g1"), emptySet()))
    }
}
