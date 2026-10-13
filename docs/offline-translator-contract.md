# Translation privacy qualification

Translation is blocked for the reviewed publisher releases 0.8.3–0.8.5. Their exported Binder service has a plaintext-success logging path; current upstream also retains it, and no privacy-qualified signed release is available. Package/certificate and ABI compatibility do not prove absence of private-text logging. The adapter rejects these releases before binding or submitting text. Open engine remains a text-free launcher action.

Before enabling any future release, inspect its exact signed APK, release source and success/error/native logging paths. Run a unique synthetic probe and check the engine process log for payload leakage, alongside catalog readiness, requested-language, model and lifecycle tests. Record artifact hashes, version name/code, signer and actual results. Never treat a guessed future version or an unsigned development build as qualified.

Source evidence: [released Binder service](https://github.com/DavidVentura/offline-translator/blob/bc7cf607450d18ff44c95233c1605f90b6beb078/app/src/main/java/dev/davidv/translator/AidlTranslationService.kt#L110). The following ABI/readiness notes are retained implementation evidence and do not enable the blocked releases.

# Offline Translator Binder contract

The four files in `app/src/main/aidl/dev/davidv/translator/` reproduce only the
interoperability declarations from Offline Translator release 0.8.3, pinned at
[`eb0341f0f0498bb54b11c8cac665e6f740a2a0c1`](https://github.com/DavidVentura/offline-translator/tree/eb0341f0f0498bb54b11c8cac665e6f740a2a0c1/app/src/main/aidl/dev/davidv/translator).
The implementation, translation models and app are separate installations.
These declarations retain GPL-3.0-only attribution; the license is retained at
`third_party/offline-translator/LICENSE`. White Noise uses AGPL-3.0-only.

The client accepts only the documented publisher certificate SHA-256
`2b3806e745d809018a51be58d0635ffc74cc9733439407ab1ed0424a4db3e1fb`
and reviewed versions 0.8.3, 0.8.4 and 0.8.5, resolves an exported enabled service within
`dev.davidv.translator`, and binds the explicit component with action
`dev.davidv.translator.ITranslationService`. Package updates invalidate active
conversation requests. Future publisher/version compatibility needs fresh
contract and signer verification. The declarations, service implementation and
manifest are unchanged between the pinned 0.8.3 source and releases
[0.8.4](https://github.com/DavidVentura/offline-translator/tree/403fa10370bd65d01659b6c9c7ecf6e5b3e98983) and
[0.8.5](https://github.com/DavidVentura/offline-translator/tree/bc7cf607450d18ff44c95233c1605f90b6beb078).
The F-Droid 0.8.5 arm64 APK (version code 302) has SHA256
`e44fc1bf41a284692230a2666ba779808b4e1b810de8a206d933c8832d038000`
and the same verified publisher certificate. The public test certificate in
`app/src/test/resources/translation/offline-translator-public.pem` comes from
the verified 0.8.3 artifact and exercises the real certificate pin.

Open engine launches the installed package's launcher independently of service
compatibility, without message text or language extras. An absent launcher uses
the installation link. Opening the app does not grant it translation trust;
every submission still passes signer, reviewed-version and explicit-service checks.

The reviewed service looks up explicit languages before awaiting its asynchronous
catalog initialization. An unresolved target silently becomes the engine's default.
The client first submits empty text with empty language codes on the verified
binding and waits for its callback. The service emits that callback after its
language-availability wait, so the subsequent message request resolves the selected
source and target against the loaded catalog. The empty result or expected detection
failure is discarded; other failures stop the request before message text is sent.
Both submissions revalidate the package identity and share the overall deadline.
Cancellation, service death and package changes retire the callback and binding.

The picker and adapter share the reviewed catalog codes. Unknown codes are rejected
before either call to prevent another default-target fallback. The API provides no
readiness method or returned-language metadata, so this initialization barrier
relies on the inspected callback ordering of the pinned compatible releases.
Qualification must use a selected target different from the engine default, with
both explicit and automatically detected source languages and a cold service.

The service takes source text, source language, target language and a callback.
Empty source means automatic detection. Its error parcel contains an enum and
nullable language/message strings. Only enum values become app-owned stable
failure codes; engine error text is discarded. The API has no installed-pack
list, model installation or remote cancellation operation. Cancellation retires
our callback and binding; it cannot stop the engine's native work. The synchronous
Binder submission also cannot be forcibly interrupted by coroutine cancellation.
Do not interpret app deadlines as proof of remote cancellation.

An unavailable target uses the generic engine-error path. The app offers model
management without claiming a dedicated target-model diagnostic. Source and
translated text remain conversation-owned transient state and never enter MDK,
preferences, files, diagnostics or logs.

Real-engine execution with installed packs, Binder compatibility and device
lifecycle behavior still require the candidate qualification recorded in the
manual release guide. Source inspection and fake-provider tests do not certify
those results.
