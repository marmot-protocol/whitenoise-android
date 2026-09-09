package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.audio.VoicePlaybackController
import dev.ipf.whitenoise.android.audio.tts.TtsState
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationDictationPlaybackHandoffTest {
    @Test
    fun activeTtsResumesOnlyWhenTheSameSessionRemainsPaused() {
        var tts: TtsState = speakingTts(7L)
        var resumes = 0
        val handoff =
            handoff(
                ttsState = { tts },
                pauseTts = { tts = pausedTts(7L) },
                resumeTts = { resumes += 1 },
            )

        handoff.pauseActivePlayback()
        handoff.resumeInterruptedPlayback()

        assertEquals(1, resumes)
    }

    @Test
    fun userPausedAndReplacementTtsSessionsAreNotResumed() {
        listOf<TtsState>(pausedTts(7L), pausedTts(8L)).forEach { stateAtResume ->
            var tts: TtsState = if (stateAtResume.sessionId == 7L) pausedTts(7L) else speakingTts(7L)
            var resumes = 0
            val handoff =
                handoff(
                    ttsState = { tts },
                    pauseTts = { tts = pausedTts(7L) },
                    resumeTts = { resumes += 1 },
                )

            handoff.pauseActivePlayback()
            tts = stateAtResume
            handoff.resumeInterruptedPlayback()

            assertEquals(0, resumes)
        }
    }

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

    private fun handoff(
        ttsState: () -> TtsState = { TtsState.Idle() },
        pauseTts: () -> Unit = {},
        resumeTts: () -> Unit = {},
        pauseVoice: () -> VoicePlaybackController.PausedPlayback? = { null },
        resumeVoice: (VoicePlaybackController.PausedPlayback) -> Boolean = { false },
    ) = ConversationDictationPlaybackHandoff(
        ttsState = ttsState,
        pauseTts = pauseTts,
        resumeTts = resumeTts,
        pauseVoice = pauseVoice,
        resumeVoice = resumeVoice,
    )

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

    private fun pausedVoice(key: String) = VoicePlaybackController.PausedPlayback(key, Any(), 0L)
}
