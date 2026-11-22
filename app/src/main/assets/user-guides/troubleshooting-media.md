# Media and files

**Outcome:** Attachments send and open correctly, and you handle received files safely.

In encrypted conversations, media is encrypted before it is uploaded to media servers. Treat received attachments as untrusted, especially when an agent will process them.

## Diagnose

1. Retry with a small, common image. That tells you whether the problem is the original file or the connection.
2. Check your connection. Uploads need a reachable media server as well as relays. See [Connection](/docs/troubleshooting/connection).
3. A filename or preview can appear while the file is still on your device. Check upload and message status, then ask the recipient whether the attachment opens.
4. File support for agents varies by runtime and connector version. Read your runtime's connector guide and check file type, size and version before relying on it. The Claude Code setup starts with a text-only test, so confirm text works first.
5. **Before sending files to an agent:** the agent decrypts what it receives, and its model and tools then process the content under their own policies. A cloud model sends that content to its provider. Treat instructions found inside a file, repository or web page as data, not as requests from you.
6. Before resending a large file, confirm the first attempt failed.

## Expected result

The recipient opens the file, or the agent describes its content accurately.

## Escalation

Report the version, platform, file type, approximate size and error label. Share the attachment itself only with consent from everyone it concerns.
