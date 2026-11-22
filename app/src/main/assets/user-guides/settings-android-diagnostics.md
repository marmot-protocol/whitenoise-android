# Android diagnostics and local logs

White Noise for Android has two optional sharing switches, and both apply to every profile on the device. Uploads start only after you turn sharing on. You can review, export or clear the diagnostic logs stored on the device at any time. See the [privacy policy](/privacy).

## Open the controls

1. Open **Settings > Privacy & Security**.
2. Tap **Diagnostics & Improvements**. The row value shows On, Usage, Logs or Off. The screen opens with **Share usage and telemetry** and **Share group diagnostic logs**, then **Audit log delivery** and **Stored Diagnostic Logs**.

If the screen shows **Couldn't load or save settings**, tap **Retry**. If it shows **Usage sharing now includes feature activity. Review and choose again.**, review the disclosure and choose again.

## Share usage and telemetry

**Share usage and telemetry** shares feature use and performance for all profiles on this device. The data includes a temporary session ID and an installation ID you can reset, and it leaves out account and group IDs.

## Share group diagnostic logs

When you turn this on, White Noise records group diagnostic logs for every profile on this device and uploads them to the White Noise audit collector, operated by the Internet Privacy Foundation.

> **Before you turn this on:** Logs already stored on this device may also be uploaded. Logs can include group and message IDs, relay addresses, timestamps, pseudonymous account references, an installation ID, your device model and the app version, with members referred to by hashed references. Message contents, profile names, group names and private keys are excluded.

1. Turn on **Share group diagnostic logs**.
2. Read the confirmation, then tap **Enable automatic sharing**, or tap **Cancel** to leave sharing off.

If the sharing details change, the app shows **Sharing details changed. Review them before turning logs back on.**

## Retention and turning sharing off

White Noise servers delete group diagnostic logs after 30 days, telemetry after 90 days and product analytics after 180 days. When you turn sharing off, new recording and new uploads stop, though an upload already in progress may finish. Logs on this device remain until you clear them, and uploaded copies follow the retention periods above.

## Status messages

| Status | Meaning |
|---|---|
| Ready to share | Sharing is configured. |
| Permission needed | A permission is required first. |
| Not configured, Unavailable in this build | Sharing is disabled in this build. |
| Configuration rejected | The configuration was refused. |
| Not loaded | The status hasn't loaded yet. |
| Couldn't load or save settings | Tap **Retry**. |

## Audit log delivery

**Audit log delivery** shows whether group logs are reaching the collector: Off, Not configured, Active, Retrying automatically, Paused, Status unavailable or Loading. It adds the HTTP code when there is one.

- **Last successful check** shows a date and time, or **Never**. It is hidden while delivery is Off or Not configured.
- **Waiting to upload** shows how much stored data is waiting, when there is any.
- **Resume delivery** appears only while delivery is Paused. If it fails, the app shows **Couldn't resume delivery**.
- **Refresh** checks the status again, unless delivery is Off.

**Keep audit logs for** sets how long this device keeps audit logs: 1, 3, 7, 14 or 30 days, or the value already stored. It works whether or not sharing is on. A shorter period permanently deletes older logs on this device, including ones still waiting to upload, so the app asks you to confirm with **Delete older logs** under **Delete older audit logs?**.

## Export stored logs

To export only some files, tap **Choose Audit Files**, select the files and tap **Export**. If there is nothing to export, the app says **No diagnostic logs to export**.

To export everything:

1. Under **Stored Diagnostic Logs**, tap **Export Diagnostic Logs**.
2. Read the warning that logs contain technical identifiers and activity details, then tap **Continue**.
3. Choose **Save to device** or **Share**.

The logs are packed into one .zip file. Share it only with someone you trust. If preparing or saving fails, the app shows an error and you can try again.

## Clear stored logs

1. Tap **Clear Diagnostic Logs**.
2. Confirm with **Clear Logs**.

The dialog **Clear diagnostic logs?** warns that this permanently deletes the diagnostic logs for every profile from this device. Your sharing settings stay as they are, and copies already uploaded remain on the servers.

## Developer diagnostics

Turn on **Developer mode** in **Settings > Help & support > Developer Tools**, then open **Diagnostics**. Its **Actions** menu has **Health & Performance** (Relay Health, Runtime and Performance logs), **Send to self** and **Clear**, and the screen lists Events. **Send to self** runs a delivery test. It creates a temporary group, sends a test message there and archives it.

**Performance logs** collect privacy-safe timing events on this device for a limited time, stopping after 30 minutes or when White Noise restarts. **Copy performance logs** copies the events from the current or most recent session, and that session is also included in your next export of stored logs.

## Report a problem

**Settings > Help & support > Help > Report a bug** opens a review screen that says nothing is attached, then opens an empty public GitHub form. Everything you add there becomes public, so if you attach logs, export them first and remove anything you want to keep private. You can also use **Chat with support**. See [Report a problem](/docs/troubleshooting/report-problem).
