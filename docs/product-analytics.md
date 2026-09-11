# Android usage and diagnostics

Android uses MDK's product analytics collector and stock self-hosted Aptabase. The
host configures both exporters before native startup, supplies a finite event
catalogue, and never persists its own consent receipt or delivery queue.

## Configuration

Existing OTLP diagnostics use `WHITENOISE_OTLP_ENDPOINT` and the environment-specific
`WHITENOISE_<ENV>_OTLP_AUTH_TOKEN`. The acceptance receipt enables this already
configured path independently of Aptabase readiness.

For product analytics, provide these GitHub Actions secrets (the workflows forward them as environment
variables), or the same property names in ignored `local.properties` for local
builds. Production and staging must have separate Aptabase applications and keys.

| Environment | Required variables |
| --- | --- |
| Production | `WHITENOISE_PRODUCTION_PRODUCT_EVENTS_ENDPOINT`, `WHITENOISE_PRODUCTION_PRODUCT_APP_KEY`, `WHITENOISE_PRODUCTION_PRODUCT_OPERATOR`, `WHITENOISE_PRODUCTION_PRODUCT_RETENTION` |
| Staging | `WHITENOISE_STAGING_PRODUCT_EVENTS_ENDPOINT`, `WHITENOISE_STAGING_PRODUCT_APP_KEY`, `WHITENOISE_STAGING_PRODUCT_OPERATOR`, `WHITENOISE_STAGING_PRODUCT_RETENTION` |
| Optional development | `WHITENOISE_DEV_PRODUCT_EVENTS_ENDPOINT`, `WHITENOISE_DEV_PRODUCT_APP_KEY`, `WHITENOISE_DEV_PRODUCT_OPERATOR`, `WHITENOISE_DEV_PRODUCT_RETENTION` |

Use `https://aptabase.ipf.dev/api/v0/events` after the operator confirms the
Android destination, an `A-SH-…` application key, and the verified operator label
(e.g. `white_noise`). `PRODUCT_RETENTION` is the human-readable disclosure shown
to users. iOS currently discloses “Usage analytics are scheduled for automatic
deletion after 180 days.” Reuse that only if the operator confirms the same policy
for the Android applications. Do not reuse OTLP or audit tokens as Aptabase keys.
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
pending receipt can see it when they launch directly into Chats. Its details scroll while Done stays reachable. Sharing defaults off. Done saves a decline unless the user
has explicitly granted sharing. Technical logging has a separate switch and is
never enabled by a usage choice. Failed reads/writes remain visible with Retry.
The same disclosure and independent exporter status appear in Device privacy.

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

With the configured staging destination, use a synthetic staging account, grant sharing,
exercise the documented actions, background/resume, and confirm persisted events
in the correct Aptabase application with Android/staging metadata. Check decline,
revocation, regrant, relaunch and old-receipt upgrade. Inspect event properties and
confirm production/staging separation, retention policy, and identifying server
logs with the operator. An HTTP success response alone is insufficient evidence.
No production ingestion or deployed retention verification is claimed here.
