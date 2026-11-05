"""Host-only command-format/ownership controls; no radio or device calls."""
import unittest
import subprocess
import sys
import time
from background_receipt_wifi import WifiAssociation, WifiSetupError, open_access_point, saved_networks

HEADER = "    BSSID              Frequency      RSSI           Age(sec)   SSID                             Flags"

def scan(ssid="Fixture AP", flags="[ESS]", api=30):
    layout = "  %17s  %9d  %18s   %7s    %-32s  %s\n"
    return HEADER + "\n" + layout % (
        "02:00:00:00:00:01", 2412, "-45", "0.200", ssid, flags)

def networks(rows):
    if not rows:
        return "No networks\n"
    return "Network Id SSID Security type\n" + "".join(
        "%-12d %-32s %-4s\n" % (key, name, security) for key, (name, security) in rows.items())

class Commands:
    def __init__(self, before=None, enabled=True):
        self.networks = dict(before or {})
        self.enabled = enabled
        self.calls = []
        self.scan = scan()
        self.forget_fails = False
    def __call__(self, *args, timeout=None):
        self.calls.append(args)
        if timeout is None or not 0 < timeout <= 10:
            raise AssertionError("every local command needs its remaining deadline")
        command = args[2]
        if command == "status":
            return "Wifi is enabled\nWifi is not connected\n" if self.enabled else "Wifi is disabled\n"
        if command == "list-networks":
            return networks(self.networks)
        if command == "list-scan-results":
            return self.scan
        if command == "set-wifi-enabled":
            self.enabled = args[3] == "enabled"
        if command == "forget-network":
            if self.forget_fails:
                return "Forget failed\n"
            del self.networks[int(args[3])]
            return "Forget successful\n"
        return ""

class FakeClock:
    def __init__(self): self.now = 0
    def __call__(self): return self.now
    def sleep(self, duration): self.now += duration


class AsyncCommands(Commands):
    def __init__(self):
        super().__init__(enabled=False)
        self.pending = None; self.radio_reads = 0; self.scan_reads = 0
        self.stuck_radio = False; self.forget_pending = None; self.saved_reads = 0
    def __call__(self, *args, timeout=None):
        command = args[2]
        if command == "set-wifi-enabled":
            self.calls.append(args); self.pending = args[3] == "enabled"; self.radio_reads = 0
            return ""
        if command == "status" and self.pending is not None:
            self.radio_reads += 1
            if self.radio_reads >= 3 and not self.stuck_radio:
                self.enabled = self.pending; self.pending = None
        if command == "list-scan-results":
            self.scan_reads += 1
            if self.scan_reads < 3:
                self.calls.append(args)
                return "No scan results\n"
        if command == "forget-network":
            self.calls.append(args); self.forget_pending = int(args[3]); self.saved_reads = 0
            return "Forget successful\n"
        if command == "list-networks" and self.forget_pending is not None:
            self.saved_reads += 1
            if self.saved_reads >= 3:
                del self.networks[self.forget_pending]; self.forget_pending = None
        return super().__call__(*args, timeout=timeout)


class WifiTest(unittest.TestCase):
    def test_real_fixed_width_format_observes_unseen_name_and_passes_only_hex(self):
        self.assertEqual(open_access_point(scan("Different AP")), "Different AP")
        commands = Commands()
        owner = WifiAssociation(commands)
        self.assertEqual(bytes.fromhex(owner.prepare()).decode(), "Fixture AP")
        self.assertEqual(sum(c[2] == "start-scan" for c in commands.calls), 1)
        self.assertEqual(sum(c[2] == "list-scan-results" for c in commands.calls), 1)
        self.assertFalse(any(c[2] == "connect-network" for c in commands.calls))
    def test_empty_ambiguous_secured_and_unreadable_scan_never_select(self):
        bad = ["No scan results\n", scan() + scan("Other").split("\n", 1)[1],
               scan(flags="[WPA2-PSK-CCMP][ESS]"), scan(flags="[OWE][ESS]"),
               scan().replace("02:00:00:00:00:01", "not-an-addressxx"), "unrecognized header\n"]
        for output in bad:
            with self.subTest(output=output[:30]), self.assertRaises(WifiSetupError):
                open_access_point(output)
    def test_spaces_quotes_and_shell_metacharacters_are_data_not_commands(self):
        for value in ["space name", "quote'name", "$(echo unsafe)", "; echo unsafe"]:
            commands = Commands(); commands.scan = scan(value)
            owner = WifiAssociation(commands)
            self.assertEqual(bytes.fromhex(owner.prepare()).decode(), value)
    def test_saved_format_security_conflict_and_duplicate_ids_fail_closed(self):
        self.assertEqual(saved_networks(networks({1: ("saved space", "OPEN")})), {1: ("saved space", "OPEN")})
        duplicate = networks({1: ("saved", "OPEN")}) + networks({1: ("saved", "OPEN")}).split("\n", 1)[1]
        with self.assertRaises(WifiSetupError): saved_networks(duplicate)
        owner = WifiAssociation(Commands({1: ("Fixture AP", "WPA2_PSK")}))
        with self.assertRaises(WifiSetupError): owner.prepare()
    def test_existing_open_ap_is_reusable_and_other_saved_networks_untouched(self):
        commands = Commands({1: ("Fixture AP", "OPEN"), 9: ("Other", "WPA2_PSK")})
        owner = WifiAssociation(commands); owner.prepare(); owner.cleanup()
        self.assertEqual(commands.networks, owner.before)
        self.assertFalse(any(c[2] == "forget-network" for c in commands.calls))
    def test_only_new_matching_config_is_removed_and_original_radio_restored(self):
        commands = Commands({9: ("Other", "WPA2_PSK")}, enabled=False)
        owner = WifiAssociation(commands); owner.prepare()
        commands.networks.update({10: ("Fixture AP", "OPEN"), 11: ("Unrelated", "OPEN")})
        owner.cleanup()
        self.assertEqual(commands.networks, {9: ("Other", "WPA2_PSK"), 11: ("Unrelated", "OPEN")})
        self.assertFalse(commands.enabled)
    def test_failed_owned_cleanup_still_restores_original_radio(self):
        commands = Commands(enabled=False); owner = WifiAssociation(commands); owner.prepare()
        commands.networks[10] = ("Fixture AP", "OPEN"); commands.forget_fails = True
        with self.assertRaises(WifiSetupError): owner.cleanup()
        self.assertFalse(commands.enabled)
    def test_auto_added_open_security_and_closed_missing_ambiguity_reasons(self):
        commands = Commands(); owner = WifiAssociation(commands); owner.prepare()
        commands.networks[10] = ("Fixture AP", "OPEN/OWE^")
        owner.cleanup()
        self.assertNotIn(10, commands.networks)
        for output, reason in [(scan(flags="[WPA2-PSK-CCMP][ESS]"), "open_ap_missing"),
                               (scan() + scan("Other").split("\n", 1)[1], "open_ap_ambiguous")]:
            with self.assertRaisesRegex(WifiSetupError, reason): open_access_point(output)

    def test_source_bound_android11_14_16_layouts_preserve_ssid_data(self):
        for api in (30, 34, 36):
            self.assertEqual(open_access_point(scan("Observed AP", api=api)), "Observed AP")
        # Raw API30/34/36 share the modern layout; collapsed web-text spacing is not evidence.
        malformed = scan("Observed AP", api=34).replace("  02:00", "   02:00", 1)
        with self.assertRaises(WifiSetupError): open_access_point(malformed)
        synthetic_single = "BSSID Frequency RSSI Age(sec) SSID Flags\n" + " %17s %9d %18s %7s %-32s %s\n" % (
            "02:00:00:00:00:01", 2412, "-45", "0.200", "Observed AP", "[ESS]")
        with self.assertRaises(WifiSetupError): open_access_point(synthetic_single)

    def test_api30_lowercase_open_config_reuse_and_owned_cleanup(self):
        commands = Commands({1: ("Fixture AP", "open"), 9: ("Other", "wpa2")})
        owner = WifiAssociation(commands); owner.prepare(); owner.cleanup()
        self.assertEqual(commands.networks, {1: ("Fixture AP", "open"), 9: ("Other", "wpa2")})
        commands = Commands(); owner = WifiAssociation(commands); owner.prepare()
        commands.networks[10] = ("Fixture AP", "open")
        owner.cleanup()
        self.assertNotIn(10, commands.networks)

    def test_async_enable_scan_and_restore_converge_with_single_intents(self):
        clock = FakeClock()
        commands = AsyncCommands()
        owner = WifiAssociation(commands, clock=clock, sleep=clock.sleep)
        self.assertEqual(bytes.fromhex(owner.prepare()).decode(), "Fixture AP")
        self.assertGreater(sum(c[2] == "list-scan-results" for c in commands.calls), 1)
        self.assertEqual(sum(c[2] == "start-scan" for c in commands.calls), 1)
        owner.cleanup()
        toggles = [c for c in commands.calls if c[2] == "set-wifi-enabled"]
        self.assertEqual([c[3] for c in toggles], ["enabled", "disabled"])
        self.assertFalse(commands.enabled)
        self.assertLess(clock.now, 30)

    def test_absent_scan_and_unconverged_radio_expire_without_reissuing_intent(self):
        for scan_missing in (False, True):
            clock = FakeClock(); commands = AsyncCommands()
            commands.stuck_radio = not scan_missing
            if scan_missing: commands.scan = "No scan results\n"
            owner = WifiAssociation(commands, clock=clock, sleep=clock.sleep)
            with self.assertRaises(WifiSetupError): owner.prepare()
            self.assertLessEqual(clock.now, 30)
            self.assertEqual(sum(c[2] == "set-wifi-enabled" for c in commands.calls), 1)
            self.assertLessEqual(sum(c[2] == "start-scan" for c in commands.calls), 1)

    def test_remaining_budget_caps_each_command_and_expired_budget_sends_nothing(self):
        clock = FakeClock(); commands = Commands()
        calls = []
        def shell(*args, timeout):
            calls.append((args, timeout))
            return commands(*args, timeout=timeout)
        owner = WifiAssociation(shell, clock=clock, sleep=clock.sleep)
        clock.now = 29.75
        owner.command(owner.deadline, "status")
        self.assertEqual(calls[-1][1], 0.25)
        clock.now = 30
        count = len(calls)
        with self.assertRaises(WifiSetupError): owner.command(owner.deadline, "status")
        self.assertEqual(len(calls), count)

    def test_async_forget_is_observed_once_without_repeated_forget(self):
        clock = FakeClock(); commands = AsyncCommands()
        owner = WifiAssociation(commands, clock=clock, sleep=clock.sleep); owner.prepare()
        commands.networks[10] = ("Fixture AP", "open")
        owner.cleanup()
        self.assertNotIn(10, commands.networks)
        self.assertEqual(sum(c[2] == "forget-network" for c in commands.calls), 1)

    def test_async_restore_timeout_is_not_success_and_does_not_repeat_toggle(self):
        clock = FakeClock(); commands = AsyncCommands()
        owner = WifiAssociation(commands, clock=clock, sleep=clock.sleep); owner.prepare()
        commands.stuck_radio = True
        with self.assertRaisesRegex(WifiSetupError, "radio_restore_deadline_expired"):
            owner.cleanup()
        self.assertEqual(sum(c[2] == "set-wifi-enabled" for c in commands.calls), 2)

    def test_blocking_local_command_receives_deadline_and_is_not_retried(self):
        commands = Commands(); calls = []
        def shell(*args, timeout):
            if len(calls) < 2:
                calls.append(args)
                return commands(*args, timeout=timeout)
            calls.append(args)
            return subprocess.run([sys.executable, "-c", "import time; time.sleep(1)"],
                                  timeout=timeout, capture_output=True, text=True).stdout
        owner = WifiAssociation(shell)
        with self.assertRaises(subprocess.TimeoutExpired):
            owner.command(time.monotonic() + 0.02, "status")
        self.assertEqual(len(calls), 3)

    def test_never_disappearing_owned_config_still_restores_radio_once(self):
        clock = FakeClock(); commands = AsyncCommands()
        owner = WifiAssociation(commands, clock=clock, sleep=clock.sleep); owner.prepare()
        commands.networks[10] = ("Fixture AP", "open")
        original = commands.__call__
        def shell(*args, timeout):
            if args[2] == "forget-network":
                commands.calls.append(args)
                return "Forget successful\n"  # ACK without convergence must never become cleanup PASS.
            return original(*args, timeout=timeout)
        owner.shell = shell
        began = clock.now
        with self.assertRaisesRegex(WifiSetupError, "config_cleanup_deadline_expired"):
            owner.cleanup()
        self.assertGreaterEqual(clock.now - began, 30)
        self.assertFalse(commands.enabled)
        self.assertIn(10, commands.networks)
        self.assertEqual(sum(c[2] == "forget-network" for c in commands.calls), 1)
        self.assertEqual([c[3] for c in commands.calls if c[2] == "set-wifi-enabled"], ["enabled", "disabled"])

    def test_prior_config_mutation_is_a_failure_not_success(self):
        commands = Commands({9: ("Other", "WPA2_PSK")}); owner = WifiAssociation(commands); owner.prepare()
        del commands.networks[9]
        with self.assertRaises(WifiSetupError): owner.cleanup()

if __name__ == "__main__": unittest.main()
