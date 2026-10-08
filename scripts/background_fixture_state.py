"""Read only platform delivery overrides from one dumpsys package user block."""

import json
import re
import sys
from pathlib import Path

RECEIVER = "com.google.firebase.iid.FirebaseInstanceIdReceiver"
COMPONENT_ACTIONS = ("default-state", "enable", "disable", "disable-user", "disable-until-used")
LISTENER_PACKAGE = "dev.ipf.whitenoise.android.benchmark"
LISTENER_CLASS = LISTENER_PACKAGE + ".BackgroundDeliveryReceiptListener"


def listener_granted(setting):
    """Normalize Android's short/full component spellings without changing foreign grants."""
    for entry in setting.strip().split(":"):
        if entry.count("/") != 1:
            continue
        package, name = entry.split("/")
        if name.startswith("."):
            name = package + name
        if package == LISTENER_PACKAGE and name == LISTENER_CLASS:
            return True
    return False


def protected_users(text, fixture_user):
    """Capture exact installed non-fixture app overrides before shared APK replacement."""
    result = []
    seen = set()
    for line in text.splitlines():
        match = re.match(r"^\s+User ([0-9]+):", line)
        if not match:
            continue
        # ART/dex diagnostics repeat bare "User N:" headings outside app state.
        if re.fullmatch(r"\s+User [0-9]+:\s*", line):
            continue
        user = int(match.group(1))
        installed = re.search(r"\binstalled=(true|false)\b", line)
        enabled = re.search(r"\benabled=([0-4])\b", line)
        if user in seen or installed is None or enabled is None:
            raise ValueError("Ambiguous installed app-user state.")
        seen.add(user)
        if user != fixture_user and installed.group(1) == "true":
            result.append({"user": user, "action": COMPONENT_ACTIONS[int(enabled.group(1))]})
    if fixture_user not in seen:
        raise ValueError("Fixture user is absent.")
    return sorted(result, key=lambda item: item["user"])


def delivery_state(text, user):
    """Reject ambiguous user/permission state without exposing unrelated package details."""
    lines = text.splitlines()
    starts = [i for i, line in enumerate(lines) if re.match(rf"^\s+User {user}:.*\binstalled=(true|false)\b", line)]
    if len(starts) != 1:
        raise ValueError("Require one fixture user block.")
    start = starts[0]
    indent = len(lines[start]) - len(lines[start].lstrip())
    block = []
    for line in lines[start + 1:]:
        if line.strip() and len(line) - len(line.lstrip()) <= indent:
            break
        block.append(line)
    grants = [re.search(r"POST_NOTIFICATIONS: granted=(true|false),", line) for line in block]
    grants = [match.group(1) == "true" for match in grants if match]
    if len(grants) != 1:
        raise ValueError("Require an explicit fixture notification permission.")
    state = "default-state"
    section = None
    section_indent = None
    seen = False
    for line in block:
        stripped = line.strip()
        current_indent = len(line) - len(line.lstrip())
        if section_indent is not None and stripped and current_indent <= section_indent:
            section = None
        if stripped in ("enabledComponents:", "disabledComponents:"):
            section = "enable" if stripped == "enabledComponents:" else "disable"
            section_indent = current_indent
        elif stripped == RECEIVER and section:
            if seen:
                raise ValueError("Ambiguous fixture receiver override.")
            seen = True
            state = section
    return {"permission_granted": grants[0], "receiver_action": state}


if __name__ == "__main__":
    try:
        if sys.argv[1:] == ["--listener-grant"]:
            print("true" if listener_granted(sys.stdin.read()) else "false")
            raise SystemExit(0)
        if len(sys.argv) not in (3, 4) or not re.fullmatch(r"[0-9]+", sys.argv[2]):
            raise ValueError("Pass a package dump and numeric fixture user.")
        if len(sys.argv) == 4 and sys.argv[3] != "--protected-users":
            raise ValueError("Unknown state operation.")
        reader = protected_users if len(sys.argv) == 4 else delivery_state
        print(json.dumps(reader(Path(sys.argv[1]).read_text(), int(sys.argv[2]))))
    except (ValueError, OSError):
        raise SystemExit("Cannot capture exact fixture delivery overrides.") from None
