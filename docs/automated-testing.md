# White Noise testing campaigns

Use the smallest campaign that exercises a change, and keep all campaign evidence tied to its source revision. The full release checklist remains the acceptance specification: [permanent cases](manual-release-testing.md), [source and surface inventory](manual-release-testing-surfaces.json), and [scenario/surface fragments](manual-release-testing/README.md). Nested dialogs, Android entry points, permission denial, recovery and lifecycle subcases are part of that specification.

## Coverage accounting

```sh
python3 scripts/maestro_coverage.py --output build/test-coverage-inventory.json
python3 scripts/check_manual_test_guide.py
```

The inventory assembles every maintained case and every surface fragment. It lists the exact UI journeys associated with each permanent ID. Mappings are partial assertions: opening Appearance does not certify its theme, language, typography, persistence and accessibility cases. Discovered files, test names, screenshots, passing setup and a green aggregate are not proof that every case passed. `--require-full` deliberately fails while only partial UI coverage is available.

Keep the original checklist and its subcases available during a release campaign. Report unmapped requirements and fixtures explicitly. Never automatically check its release boxes after a Maestro run.

## Layers and execution

| Layer | Purpose | Execution and evidence |
|---|---|---|
| Core CI | Unit, state, screenshots, both distributions, lint, contracts, packaging and security | Existing exact-head Android CI jobs and their reports |
| Offline Maestro | Onboarding validation, cancellation, warm resume, rotation, denied QR, name generation and photo-picker cancellation | Retained dev APK, disposable offline emulator; selected manifest reconciled with actual JUnit |
| Generated-account Maestro | Real activity, account switching, search, settings pages, profile editing cancellation, composer drafts, actions, rotation and peer-verified sending | Matching isolated app/test APK pair; fresh loopback-native fixture per journey; UI JUnit plus generation-bound setup, postcondition and cleanup receipts |
| Native/controlled integration | Durable state, account isolation, cold start, relay behavior, media transfer/recovery, sharing, attachment acquisition and installer boundaries | Existing connected Android tests and controlled attachment fixtures; use their exact case manifests/reports |
| Physical/human | Signers, biometrics, push/background delivery, actual microphone/speakers, camera scanning, TalkBack traversal and release identity | Focused disposable-account device campaigns with recorded artifact identity and results |

Run supported compilation and tests on GitHub as described in [CI request policy](ci-request-policy.md). The optional UI campaigns have no automatic Maestro trigger and add no required Maestro PR check. They do not build locally or use the shared phone. Normal unit, native, platform and release gates continue to apply.

## Offline suites

Follow [onboarding setup and provenance](maestro-pilot.md). The original suites retain their original membership and deadlines:

- `onboarding`: one journey; 1–20 repetitions.
- `offline`: original six journeys; 1–3 repetitions.
- `offline-signin`: nine journeys; one repetition.
- `offline-signup`: seven journeys; one repetition.
- `offline-edge`: six additional journeys; one repetition. Tests two different generated names, photo-menu dismissal, Android photo-picker cancellation, Sign In rotation, keyboard Back, and long malformed input.

A failure-control run still fails its GitHub job. Read `suite-results.json` to distinguish the deliberate assertion from a product, harness, setup or timeout failure.

## Generated-account suites

Request the existing Android Instrumented Tests workflow manually against the intended flow revision:

```sh
gh workflow run android-instrumented.yml --repo marmot-protocol/whitenoise-android \
  --ref FLOW_BRANCH -f maestro_pilot=true -f maestro_suite=runtime-all \
  -f maestro_repetitions=1 -f maestro_negative_control=false \
  -f review_demo_e2e=false -f document_provider_matrix=false
```

For a focused run select `runtime-navigation`, `runtime-settings`, `runtime-conversation`, `runtime-preferences`, `runtime-advanced`, `runtime-connectors`, `runtime-groups`, `runtime-creation` or `runtime-actions`. Runtime mode rejects repetitions, the offline negative-control option and mixed demo/document requests. APK artifact inputs are used only in offline mode.

The build produces one checksummed Zapstore debug app/test pair at the exact selected revision. Each shard reuses those bytes. The isolated application ID is `dev.ipf.whitenoise.android.maestrolab`; fixture classes and the custom runner reside only in the test APK. No fixture classes enter normal app APKs. The controller accepts only the disposable GitHub emulator, checks a fresh random fixture generation, and runs at most eight journeys per shard. At most two shards run concurrently.

Each journey generates Alice, Bob and Carol, publishes synthetic profiles to an in-process loopback relay, creates a group and a stored message, reopens the native runtime and launches the real `MainActivity`. Native drafts use the normal MDK repository. Maestro acts on actual accessible controls; its flows never launch, stop or clear the live host. The fixture controller cannot declare a UI result successful.

The send case requires exactly one matching message in Bob's native timeline. Preference cases verify the resulting state separately. After UI work, the host closes the activity, notification listener and native runtime, removes its generated runtime and isolated preferences, and publishes a matching cleanup receipt. Missing or stale receipts, wrong case names, skipped tests, an incomplete suite, timeout or failed cleanup fail the campaign. Inspect each case's JUnit and instrumentation output, plus the shard's `results.json` and APK `pair.json`.

## Required edge-case matrix

For every applicable surface, assess initial/empty/loading/populated/error states; normal action; cancellation and Back; permission denial; invalid and boundary input; repeated taps; retry and interrupted work; account/target changes; offline/reconnect; warm resume, recreation and process death; portrait/landscape, large text, RTL and accessibility actions. Use the actual permanent case's detailed subcases rather than marking this generic list as execution proof.

Some states require dedicated fixtures. External signer success/cancellation, biometric enrollment, notification permission and background delivery, microphone/camera behavior, controlled attachment corruption/recovery, low storage, process death, stale membership and release packaging must retain their native or physical campaigns. A hidden-key navigation case does not prove secret export. Rotation does not prove process death. Emoji/menu cancellation does not prove media upload. An emulator UI playback control does not prove sound quality.

A release result must distinguish passed assertions, failed assertions, unexecuted requirements and fixture-dependent requirements. One PR can deliver the suite and guide while these execution layers remain independently runnable. Keep performance measurements and failures alongside the exact flow, app, test APK, runtime, device configuration and source identities.

## Campaign failures and continuation

The controller records each requested case explicitly. A UI assertion failure with certified native teardown allows the remaining cases to run, while the overall campaign fails. Uncertified teardown stops the shard; all remaining cases are listed as unexecuted. Inspect `result.json` and instrumentation output in the failed case directory. A missing UI report after failed fixture readiness is a setup failure, never a UI pass.
