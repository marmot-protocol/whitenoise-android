"""Pin restoration against personal-user state, absent permissions and ambiguous overrides."""

import json
import os
import re
import subprocess
import tempfile
import unittest
from pathlib import Path

from scripts.background_fixture_state import LISTENER_CLASS, LISTENER_PACKAGE, RECEIVER, delivery_state, listener_granted, protected_users


class DeliveryStateTest(unittest.TestCase):
    """Exercises package-user boundaries rather than assuming permission or receiver defaults."""

    def fixture(self, grant="true", section="", entries=""):
        """Keep personal state opposite to the disposable profile's state."""
        return (
            "    User 0: installed=true\n"
            "      runtime permissions:\n"
            "        android.permission.POST_NOTIFICATIONS: granted=false, flags=[]\n"
            "      disabledComponents:\n"
            f"        {RECEIVER}\n"
            "    User 11: installed=true\n"
            "      runtime permissions:\n"
            f"        android.permission.POST_NOTIFICATIONS: granted={grant}, flags=[]\n"
            f"{section}{entries}"
            "    User 12: installed=true\n"
        )

    def test_personal_override_does_not_leak(self):
        self.assertEqual(delivery_state(self.fixture(), 11), {"permission_granted": True, "receiver_action": "default-state"})

    def test_disabled_receiver_and_revoked_permission_preserved(self):
        text = self.fixture("false", "      disabledComponents:\n", f"        {RECEIVER}\n")
        self.assertEqual(delivery_state(text, 11), {"permission_granted": False, "receiver_action": "disable"})

    def test_explicit_enable_differs_from_default(self):
        text = self.fixture(section="      enabledComponents:\n", entries=f"        {RECEIVER}\n")
        self.assertEqual(delivery_state(text, 11)["receiver_action"], "enable")

    def test_unrelated_disabled_component_is_not_ingress(self):
        text = self.fixture(section="      disabledComponents:\n", entries="        unrelated.receiver\n")
        self.assertEqual(delivery_state(text, 11)["receiver_action"], "default-state")

    def test_absent_permission_fails_closed(self):
        text = self.fixture().replace("POST_NOTIFICATIONS: granted=true", "OTHER_PERMISSION: granted=true")
        with self.assertRaises(ValueError):
            delivery_state(text, 11)

    def test_duplicate_user_block_fails_closed(self):
        with self.assertRaises(ValueError):
            delivery_state(self.fixture() + "    User 11: installed=true\n", 11)

    def test_conflicting_overrides_fail_closed(self):
        entries = f"        {RECEIVER}\n      disabledComponents:\n        {RECEIVER}\n"
        with self.assertRaises(ValueError):
            delivery_state(self.fixture(section="      enabledComponents:\n", entries=entries), 11)

    def test_protected_users_preserve_all_original_override_states(self):
        text = "\n".join(f"    User {user}: installed=true enabled={user}" for user in range(5))
        text += "\n    User 11: installed=true enabled=0\n    User 12: installed=false enabled=0"
        self.assertEqual(
            protected_users(text, 11),
            [{"user": user, "action": action} for user, action in enumerate(
                ("default-state", "enable", "disable", "disable-user", "disable-until-used")
            )],
        )

    def test_missing_protected_override_is_not_assumed_enabled(self):
        with self.assertRaises(ValueError):
            protected_users("    User 0: installed=true\n    User 11: installed=true enabled=0", 11)

    def test_unknown_app_override_fails_closed(self):
        with self.assertRaises(ValueError):
            protected_users("    User 0: installed=true enabled=9\n    User 11: installed=true enabled=0", 11)

    def test_absent_fixture_user_fails_closed(self):
        with self.assertRaises(ValueError):
            protected_users("    User 0: installed=true enabled=0", 11)

    def test_art_diagnostic_user_headings_do_not_shadow_package_state(self):
        text = self.fixture() + "    User 0:\n      dex info\n    User 11:\n      dex info\n"
        self.assertTrue(delivery_state(text, 11)["permission_granted"])
        text = "    User 0: installed=true enabled=0\n    User 11: installed=true enabled=0\n    User 0:\n    User 11:\n"
        self.assertEqual(protected_users(text, 11), [{"user": 0, "action": "default-state"}])

    def test_listener_short_and_full_spelling_preserve_prior_access(self):
        self.assertTrue(listener_granted(f"{LISTENER_PACKAGE}/{LISTENER_CLASS}"))
        self.assertTrue(listener_granted(f"foreign.listener/.Service:{LISTENER_PACKAGE}/.BackgroundDeliveryReceiptListener"))

    def test_unrelated_listener_or_class_substring_is_not_fixture_access(self):
        self.assertFalse(listener_granted(f"foreign.listener/{LISTENER_CLASS}"))
        self.assertFalse(listener_granted(f"{LISTENER_PACKAGE}/{LISTENER_CLASS}Other"))
        self.assertFalse(listener_granted("null"))


class ListenerCrashCleanupTest(unittest.TestCase):
    """Run the actual host exit trap after a failed child, without Android or Kotlin cleanup."""

    def test_host_exit_restores_granted_and_ungranted_listener(self):
        root = Path(__file__).resolve().parents[1]
        script = (root / "scripts/run-performance-benchmarks.sh").read_text()
        functions = "\n".join(
            re.search(rf"^{name}\(\) \{{\n.*?^\}}", script, re.M | re.S).group(0)
            for name in ("fixture_listener_granted", "restore_fixture_listener", "cleanup")
        )
        foreign = "unrelated.listener/.Service"
        fixture = f"{LISTENER_PACKAGE}/{LISTENER_CLASS}"
        for granted in (False, True):
            with self.subTest(granted=granted), tempfile.TemporaryDirectory() as directory:
                state = Path(directory) / "listeners"
                original = foreign + (":" + fixture if granted else "")
                state.write_text(original + "\n")
                environment = dict(os.environ, FIXTURE_LISTENER_STATE=str(state))
                mock = r'''
set -euo pipefail
adb_cmd() {
  if [[ "$*" == "shell settings --user 11 get secure enabled_notification_listeners" ]]; then
    cat "$FIXTURE_LISTENER_STATE"
  elif [[ "$*" == "shell cmd notification allow_listener $fixture_listener 11" ]]; then
    printf '%s\n' "unrelated.listener/.Service:$fixture_listener" >"$FIXTURE_LISTENER_STATE"
  elif [[ "$*" == "shell cmd notification disallow_listener $fixture_listener 11" ]]; then
    printf '%s\n' "unrelated.listener/.Service" >"$FIXTURE_LISTENER_STATE"
  else
    return 99
  fi
}
benchmark_user=11
fixture_listener=dev.ipf.whitenoise.android.benchmark/dev.ipf.whitenoise.android.benchmark.BackgroundDeliveryReceiptListener
airplane_mode_captured=false
wifi_state_captured=false
delivery_state_captured=false
listener_state_captured=true
heads_up_setting_captured=false
device_output_pulled=false
target_replaced=false
protected_users_changed=false
result_file="$FIXTURE_LISTENER_STATE-unused"
'''
                run = mock + "\n" + functions + "\n" + r'''
original_listener_granted="$(fixture_listener_granted)"
trap cleanup EXIT
# Simulate a changed grant followed by instrumentation death: no Kotlin finally.
if [[ "$original_listener_granted" == true ]]; then action=disallow_listener; else action=allow_listener; fi
adb_cmd shell cmd notification "$action" "$fixture_listener" 11
exit 37
'''
                result = subprocess.run(["bash", "-c", run], cwd=root, env=environment, capture_output=True, text=True)
                self.assertEqual(result.returncode, 37, result.stderr)
                self.assertEqual(state.read_text(), original + "\n")


class ProtectedUserLoopTest(unittest.TestCase):
    """Exercise all profiles when an adb-like child consumes its standard input."""

    def test_protection_and_restoration_reach_every_installed_profile(self):
        """Preserve every original override even when a child drains the loop input."""
        root = Path(__file__).resolve().parents[1]
        script = (root / "scripts/run-performance-benchmarks.sh").read_text()
        functions = "\n".join(
            re.search(rf"^{name}\(\) \{{\n.*?^\}}", script, re.M | re.S).group(0)
            for name in ("protect_other_users", "restore_other_users")
        )
        original = [
            {"user": user, "action": action}
            for user, action in ((0, "default-state"), (10, "enable"), (12, "disable"),
                                 (13, "disable-user"), (14, "disable-until-used"))
        ]
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory) / "state.json"
            calls = Path(directory) / "calls"
            state.write_text(json.dumps(original))
            environment = dict(os.environ, FIXTURE_APP_STATE=str(state), FIXTURE_CALLS=str(calls),
                               FIXTURE_OUTPUT=directory)
            mock = r'''
set -euo pipefail
benchmark_user=11
target_package=dev.ipf.whitenoise.android.dev
local_output="$FIXTURE_OUTPUT"
dev_app_apk=preserved-original.apk
# Model a restored shared APK without accessing any installed device code.
installed_code_hash() { printf '%s\n' original-hash; }
# Match the restoration fence to the preserved original artifact.
sha256_file() { printf '%s\n' original-hash; }
# Read each simulated profile override after the package-manager mutation.
fixture_delivery_state() { jq -c . "$FIXTURE_APP_STATE"; }
# Apply one profile mutation and reproduce adb consuming inherited loop input.
adb_cmd() {
  [[ "$1 $2 $4 $6" == "shell pm --user $target_package" ]] || return 99
  printf '%s %s\n' "$5" "$3" >>"$FIXTURE_CALLS"
  jq -c --argjson user "$5" --arg action "$3" \
    'map(if .user == $user then .action = $action else . end)' \
    "$FIXTURE_APP_STATE" >"$FIXTURE_APP_STATE-next"
  mv "$FIXTURE_APP_STATE-next" "$FIXTURE_APP_STATE"
  # adb shell can drain the enclosing read loop, even for a noninteractive command.
  cat >/dev/null
}
'''
            run = mock + "\n" + functions + "\nprotect_other_users\nrestore_other_users\n"
            result = subprocess.run(["bash", "-c", run], cwd=root, env=environment, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(json.loads(state.read_text()), original)
            self.assertEqual(calls.read_text().splitlines(), [
                "0 disable-user", "10 disable-user", "0 default-state", "10 enable", "12 disable",
                "13 disable-user", "14 disable-until-used",
            ])


class TraceEvidenceTest(unittest.TestCase):
    """Reject missing and empty collected evidence before a runner can count it."""

    def test_collected_trace_requires_actual_bytes(self):
        """Exercise the actual Bash gate against missing, empty and populated output."""
        root = Path(__file__).resolve().parents[1]
        script = (root / "scripts/run-performance-benchmarks.sh").read_text()
        function = re.search(r"^require_nonempty_trace\(\) \{\n.*?^\}", script, re.M | re.S).group(0)
        with tempfile.TemporaryDirectory() as directory:
            missing = Path(directory) / "missing.perfetto-trace"
            empty = Path(directory) / "empty.perfetto-trace"
            populated = Path(directory) / "populated.perfetto-trace"
            empty.touch()
            populated.write_bytes(b"recorded-measurement-bytes")
            for path, expected in ((missing, 1), (empty, 1), (populated, 0)):
                with self.subTest(path=path.name):
                    run = function + '\nrequire_nonempty_trace "$TRACE_EVIDENCE"\n'
                    result = subprocess.run(["bash", "-c", run], env=dict(os.environ, TRACE_EVIDENCE=str(path)),
                                            capture_output=True, text=True)
                    self.assertEqual(result.returncode, expected, result.stderr)


if __name__ == "__main__":
    unittest.main()
