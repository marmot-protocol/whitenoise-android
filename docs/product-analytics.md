# Android usage and diagnostics

Android uses MDK's product analytics collector and stock self-hosted Aptabase. The
host configures both exporters before native startup, supplies a finite event
catalogue, and never persists its own consent receipt or delivery queue.

## Configuration

Existing OTLP diagnostics use `WHITENOISE_OTLP_ENDPOINT` (defaulting to the shared
metrics collector for staging and production) and `WHITENOISE_OTLP_AUTH_TOKEN`.
The deployment environment resource attribute identifies the flavor. The acceptance receipt enables this already
configured path independently of Aptabase readiness.

For product analytics, provide these GitHub Actions secrets (the workflows forward them as environment
variables), or the same property names in ignored `local.properties` for local
builds. Production and staging must have separate Aptabase applications and keys.

| Environment | Required variables |
| --- | --- |
| Production | `WHITENOISE_PRODUCTION_PRODUCT_EVENTS_ENDPOINT`, `WHITENOISE_PRODUCTION_PRODUCT_APP_KEY`, `WHITENOISE_PRODUCTION_PRODUCT_OPERATOR` |
| Staging | `WHITENOISE_STAGING_PRODUCT_EVENTS_ENDPOINT`, `WHITENOISE_STAGING_PRODUCT_APP_KEY`, `WHITENOISE_STAGING_PRODUCT_OPERATOR` |
| Optional development | `WHITENOISE_DEV_PRODUCT_EVENTS_ENDPOINT`, `WHITENOISE_DEV_PRODUCT_APP_KEY`, `WHITENOISE_DEV_PRODUCT_OPERATOR` |

Use `https://aptabase.ipf.dev/api/v0/events` after the operator confirms the
Android destination, an `A-SH-…` application key, and the verified operator label
(e.g. `white_noise`). `PRODUCT_OPERATOR` is native runtime metadata, not UI copy.
The localized resources shared by Diagnostics & Improvements and Help Improve
White Noise define the user-facing policy: group diagnostic logs are deleted
from our servers after 30 days, telemetry after 90 days, and product analytics
after 180 days. The obsolete `PRODUCT_RETENTION` build field is no longer read
or generated; existing secrets/local properties for it can be removed.

Every configured White Noise destination must implement that policy; confirm
server enforcement with the operator before enabling a destination. Configuration
presence does not verify retention. Unconfigured preview/dev builds retain the
same translated disclosure and show exporter readiness separately; an
unconfigured exporter cannot upload data. Do not restore untranslated policy
text from build configuration. A different deployment policy requires an
explicit change to the shared localized copy and consent review.
Do not reuse OTLP or audit tokens as Aptabase keys.
Do not commit credentials. Local properties override environment variables.

Previews always receive empty product configuration. Development builds may run
unconfigured and show “Not configured”. The optional checks below help operators
validate Aptabase configuration. They do not block shipping acceptance for the
already configured OTLP exporter. Each check reads resolved generated BuildConfig,
verifies required fields and the HTTPS ingestion route, and prints field names only.

```
./gradlew :app:verifyStagingProductAnalyticsConfig
./gradlew :app:verifyProductionProductAnalyticsConfig
```

These checks establish configuration presence and shape, not deployed retention
or event persistence. Keys have not been provisioned as part of this change.

## Consent and event scope

Pending first-launch and renewed receipts present a “Help Improve White Noise”
bottom sheet only after signup/login and account setup finish, when Chats is
visible. Welcome and onboarding never present the sheet. Conversation navigation,
account switching, app lock, wiping, and other foreground flows or sheets defer
it until the unobstructed Chats list returns. Existing signed-in users with a
pending receipt can see it when they launch directly into Chats. Its details scroll while Close stays reachable. Sharing defaults off. Closing saves a decline unless the user
has explicitly granted sharing. Technical logging has a separate switch and is
never enabled by a usage choice. Failed reads/writes remain visible with Retry.
The same disclosure and independent exporter status appear in Diagnostics & Improvements.

The registry now includes `app_android_entry` with only a notification/profile/share
source. That meaningful scope expansion invalidates existing MDK receipts,
including grants created by the old relay-only Android toggle. A new native
receipt is required before expanded export; no Android preference bypasses it.

The host reports approved onboarding/inbox/conversation/settings views, privacy
settings visits, compose opens, actual notification permission results and handled
Android entry sources, alongside the existing 26 bridge timings. MDK supplies its
native operation/session measurements. This does not yet claim every iOS host
observation (such as rendered-frame timing or attachment-picker outcomes).

Observation tickets are captured before asynchronous work and invalidated on
revocation, account changes, backgrounding, or runtime replacement. Pre-consent
activity is never replayed. Only enumerated event properties are accepted;
identifiers, queries, URLs, filenames and message content are not host properties.
MDK owns aggregation and delivery; Android adds no disk queue or shutdown wait.
“Ready to share” is exporter readiness, not proof of accepted server events.

## Release verification

Use the permanent `SEC-007`, `SEC-011`, `SEC-012`, and `SEC-013` checks. On a
disposable emulator run `HostTimingConsentDeviceTest`: it uses an empty private
store and loopback-only configuration, checks all 26 stages and the new events,
and proves registry expansion requires renewed consent despite an existing grant.
It also verifies the actual pinned JNI capability, rather than the old stale
unsupported-build comment. Never uninstall or clear a personal device's app.

## Aptabase qualification

Qualify each release candidate or shipped build that should support aggregate
setup and group-operation reports. Record the outcome in the release's tracking
issue or GitHub release notes with the receipt below. The Aptabase operator reads
persisted data; release engineers do not need ClickHouse or dashboard access.
An HTTP success response or “Ready to share” status is not evidence of persistence.

1. **Identify the build.** Record the version, version code, commit, flavor,
   the `artifact-id` from `app/src/main/marmotkit/MARMOT_VERSION`, and the
   environment. Pick events from the catalogue bundled in that MDK version.
2. **Configuration.** Run `verifyStagingProductAnalyticsConfig` and
   `verifyProductionProductAnalyticsConfig` against the build's resolved
   configuration. Both must pass before the smoke tests.
3. **Staging smoke.** Install the signed Staging build on a disposable device or
   emulator. Create a synthetic account, finish setup, and grant sharing in the
   Help Improve White Noise sheet on Chats. Create a group, add a synthetic
   member, and send one message. Background the app and resume it so MDK flushes
   its aggregate window. Note the UTC start and end of the test and the synthetic
   group size. MDK stamps summaries with the start of their 15-minute window, so
   a 17:08 action persists as 17:00. Give the operator the build details and the
   test interval widened to the start of every 15-minute window it overlaps; do
   not send the account key. The operator confirms that `mdk_session_started` and at least one of
   `mdk_onboarding_step`, `mdk_account_summary` or `mdk_group_summary` persisted
   in the staging application with Android platform, the expected app version,
   and only catalogue properties.
4. **Consent.** Run each phase in its own 15-minute window, during a period
   with no other staging testing, and record each window in UTC:
   - On the same install, revoke sharing, repeat one group action and relaunch.
     In the next window, regrant, repeat the action, and background the app.
   - On a fresh synthetic install, decline the sheet and perform one group
     action. In the next window, turn on **Share usage and telemetry** in
     Diagnostics & Improvements,
     repeat the action, and background the app.

   The operator confirms that events exist for both regrant windows and that
   none carry the start of a revoked or declined window. Run
   `HostTimingConsentDeviceTest` on a disposable emulator for the
   registry-expansion and renewed-consent cases.
5. **Production smoke.** Agree a short window with the operator first. Repeat
   step 3 once with a synthetic account on the production build, and widen the
   query interval to the window starts as in step 3. The operator
   confirms the events persisted in the production application and that no
   staging events reached it.
6. **Operator receipt.** The operator confirms the deployed Aptabase version,
   180-day retention covering events, derived sessions, exports and backups,
   and that ingest, proxy and container logs do not retain IP addresses or app
   keys beyond what the policy allows.

Record pass, fail or pending for each check, with the missing prerequisite
for any check that could not run. Keep keys, account identifiers, IP addresses
and raw event rows out of the receipt.

```text
Aptabase qualification — <version> (<version code>, <commit>)
MDK: <artifact-id>            Recorded: <UTC date>
Configuration (staging / production):  pass | fail | pending
Staging persisted events:              pass | fail | pending — <events seen>
Consent (decline, revoke, relaunch, regrant, no replay): pass | fail | pending
Production persisted events:           pass | fail | pending
Retention 180 days (events, sessions, exports, backups): pass | fail | pending
Log minimization (ingest, proxy, containers): pass | fail | pending
Coverage: opted-in observations only. Setup abandoned before the consent
prompt and repeat use by a specific group are not reported.
```

Group audit logs and OTLP metrics are separate destinations and are not part
of this qualification.
