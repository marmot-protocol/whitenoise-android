# Dictation deadline ownership

The deadline owner implements one tranche of [issue #3251](https://github.com/marmot-protocol/whitenoise-android/issues/3251).
The capture and completion owners extracted by earlier changes remain the production boundaries.

## Responsibilities and lifetimes

| Responsibility | Authority | Lifetime |
| --- | --- | --- |
| Original account/chat and explicit completion intent | Controller, immutable target and completion owner | Logical dictation session |
| Recorder and retained audio | Platform capture owner and caller-audio buffer | Capture through recovery or discard |
| Provider identity and callback fencing | Generation owner | One recognizer generation |
| Accepted chunks and recognition retries | Recognition coordinator and restart owner | Logical session, with per-chunk retry state |
| Draft validation and send dispatch | Existing completion subowners | Completion through known transport outcome |
| Recognition deadlines | Recognition deadlines owner | Session bound plus one generation bound |

The controller supplies identity predicates and timeout diagnostics. The deadline owner holds
cancellation handles and decides whether a generation-processing bound applies during audio drain.
Generation readiness and retirement cancel only the generation bound.

## Correctness rule

An accepted completion with queued caller audio starts one 90-second drain attempt. It cancels
an existing generation-processing timer and suppresses replacement processing timers until that
attempt ends. Provider startup still has its readiness bound. Processing for a provider-owned
recording retains its 20-second bound. Target validation and transport dispatch keep their existing
independent limits.

The drain deadline survives ready callbacks and replacement recognizers. Repeating the same drain
request leaves its deadline unchanged. Terminal cleanup releases it; an explicit Retry starts a
fresh bounded attempt. Once all audio has settled, the drain timer is retired before completion
validation or dispatch begins. Those operations retain their own bounds. Successful completion uses
the captured Paste or Send intent and the original draft revision checks. Timeout or exhausted recognition retries retain the recognized prefix and
unresolved audio for recovery. Late callbacks remain fenced by the existing session and generation
authorities.

## Verification and remaining work

`ConversationDictationRecognitionDeadlinesTest` covers timer ownership and replacement.
`ConversationDictationControllerTest` covers slow completion across generations, backgrounding,
recovery and the original total drain bound. `ConversationDictationDeadlineCoverageTest` keeps
production timer creation behind the shared policy. Manual scenario DIC-010 covers physical usage.

Issue #3251 stays open for its remaining behavior and device qualification. Provider decoding
performance needs separate profiling; the deadline fix changes how long completion is allowed to
wait. Cross-screen control design remains in issue #3073.
