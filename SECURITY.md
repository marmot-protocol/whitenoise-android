# Security policy

## Reporting a vulnerability

Use [GitHub private vulnerability reporting](https://github.com/marmot-protocol/whitenoise-android/security/advisories/new) for security reports. Do not put exploit details, private keys, chat contents or personal diagnostic exports in a public issue.

Include the affected release and installation source, Android version, the relevant setting or media path, and a minimal reproduction. Use synthetic accounts and harmless fixtures where possible. Describe expected and observed behavior and whether user interaction is required. Keep original evidence private until a coordinated disclosure is agreed.

Reports against the latest published stable release receive priority. If you use an older release, also state whether the latest release is affected. Do not assume an upgrade fixes a suspected vulnerability without verification.

## Multimedia boundaries

See [multimedia containment](docs/media-containment.md) for the implemented decoder boundary, resource limits, remaining in-app parsers and validation requirements. End-to-end encryption authenticates media; it does not make an authorized sender's media safe to parse. No client-side sandbox is a guarantee against every decoder, operating-system or kernel vulnerability.
