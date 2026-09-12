package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.audio.VoicePlaybackController
import dev.ipf.whitenoise.android.audio.tts.TtsState
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationDictationPlaybackHandoffTest {
    /** Verifies an untouched paused TTS session resumes after dictation. */
    @Test
    fun activeTtsResumesOnlyWhenTheSameSessionRemainsPaused() {
        var tts: TtsState = speakingTts(7L)
        var generation = 0L
        var resumes = 0
        val handoff =
            handoff(
                ttsState = { tts },
                ttsGeneration = { generation },
                pauseTts = {
                    generation += 1
                    tts = pausedTts(7L)
                },
                resumeTts = { resumes += 1 },
            )

        handoff.pauseActivePlayback()
        handoff.resumeInterruptedPlayback()

        assertEquals(1, resumes)
    }

    /** Verifies user-paused and replacement TTS sessions remain paused. */
    @Test
    fun userPausedAndReplacementTtsSessionsAreNotResumed() {
        listOf<TtsState>(pausedTts(7L), pausedTts(8L)).forEach { stateAtResume ->
            var tts: TtsState = speakingTts(7L)
            var generation = 0L
            var resumes = 0
            val handoff =
                handoff(
                    ttsState = { tts },
                    ttsGeneration = { generation },
                    pauseTts = {
                        generation += 1
                        tts = pausedTts(7L)
                    },
                    resumeTts = { resumes += 1 },
                )

            handoff.pauseActivePlayback()
            if (stateAtResume.sessionId == 7L) {
                tts = speakingTts(7L)
                generation += 1
            }
            tts = stateAtResume
            handoff.resumeInterruptedPlayback()

            assertEquals(0, resumes)
        }
    }

    /** Verifies simultaneous TTS and voice playback are restored independently. */
    @Test
    fun simultaneousMixedTtsAndVoicePlaybackAreBothHandedBack() {
        var tts: TtsState = speakingTts(7L)
        val voice = pausedVoice("clip-a")
        var ttsResumes = 0
        val resumed = mutableListOf<VoicePlaybackController.PausedPlayback>()
        val handoff =
            handoff(
                ttsState = { tts },
                pauseTts = { tts = pausedTts(7L) },
                resumeTts = { ttsResumes += 1 },
                pauseVoice = { voice },
                resumeVoice = {
                    resumed += it
                    true
                },
            )

        handoff.pauseActivePlayback()
        handoff.resumeInterruptedPlayback()

        assertEquals(1, ttsResumes)
        assertEquals(listOf(voice), resumed)
    }

    /** Capture close restores both exact players before drain work and cannot restore replacements later. */
    @Test
    fun captureCloseRestoresExactTtsAndVoiceBeforeDrainCompletes() {
        var tts: TtsState = speakingTts(7L)
        var generation = 0L
        val originalVoice = pausedVoice("clip-a")
        val replacementVoice = pausedVoice("clip-a")
        var currentVoiceToken: Any = originalVoice.playerToken
        val events = mutableListOf<String>()
        val handoff =
            handoff(
                ttsState = { tts },
                ttsGeneration = { generation },
                pauseTts = {
                    generation += 1
                    tts = pausedTts(7L)
                },
                resumeTts = { events += "tts-resume" },
                pauseVoice = { originalVoice },
                resumeVoice = { interrupted ->
                    (interrupted.playerToken === currentVoiceToken).also { accepted ->
                        if (accepted) events += "voice-resume"
                    }
                },
            )

        handoff.pauseActivePlayback()
        handoff.resumeInterruptedPlayback()
        events += "drain-complete"

        generation += 1
        tts = pausedTts(8L)
        currentVoiceToken = replacementVoice.playerToken
        handoff.resumeInterruptedPlayback()

        assertEquals(listOf("tts-resume", "voice-resume", "drain-complete"), events)
    }

    /** Verifies a replacement voice player cannot claim an earlier interruption token. */
    @Test
    fun sameKeyReplacementReceivesOnlyTheOriginalPlayerToken() {
        val original = pausedVoice("clip-a")
        val replacement = pausedVoice("clip-a")
        var currentPlayerToken: Any = replacement.playerToken
        var resumes = 0
        val handoff =
            handoff(
                pauseVoice = { original },
                resumeVoice = { interrupted ->
                    (interrupted.playerToken === currentPlayerToken).also { accepted ->
                        if (accepted) resumes += 1
                    }
                },
            )

        handoff.pauseActivePlayback()
        handoff.resumeInterruptedPlayback()

        assertEquals(0, resumes)
    }

    /** Builds a handoff with independently controlled speech sources. */
    private fun handoff(
        ttsState: () -> TtsState = { TtsState.Idle() },
        ttsGeneration: () -> Long = { 0L },
        pauseTts: () -> Unit = {},
        resumeTts: () -> Unit = {},
        pauseVoice: () -> VoicePlaybackController.PausedPlayback? = { null },
        resumeVoice: (VoicePlaybackController.PausedPlayback) -> Boolean = { false },
    ) = ConversationDictationPlaybackHandoff(
        ttsState = ttsState,
        ttsGeneration = ttsGeneration,
        pauseTts = pauseTts,
        resumeTts = resumeTts,
        pauseVoice = pauseVoice,
        resumeVoice = resumeVoice,
    )

    /** Builds a minimal speaking state for [sessionId]. */
    private fun speakingTts(sessionId: Long): TtsState.Speaking =
        TtsState.Speaking(
            sessionId = sessionId,
            chunkIndex = 0,
            chunkCount = 1,
            messageIndex = 0,
            messageCount = 1,
            sentenceIndexWithinMessage = 0,
            sentenceCountWithinMessage = 1,
            messagePreview = "speech",
        )

    /** Builds a minimal paused state for [sessionId]. */
    private fun pausedTts(sessionId: Long): TtsState.Paused =
        TtsState.Paused(
            sessionId = sessionId,
            chunkIndex = 0,
            chunkCount = 1,
            messageIndex = 0,
            messageCount = 1,
            sentenceIndexWithinMessage = 0,
            sentenceCountWithinMessage = 1,
            messagePreview = "speech",
        )

    /** Builds a retained voice interruption for [key]. */
    private fun pausedVoice(key: String) = VoicePlaybackController.PausedPlayback(key, Any(), 0L)
}
