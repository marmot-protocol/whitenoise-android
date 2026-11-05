"""One discovered open AP on this fixture's disposable emulator; no raw network diagnostics."""
import re
import time

# Raw Android11/14/16 ScanResultUtil sources share this exact printf layout.
SCAN = re.compile(r"^  ([0-9a-fA-F:]{17})  (.{9})  (.{18})   (.{7})    (.{32})  (.*)$")
STATE_BUDGET_SECONDS = 30
OBSERVATION_INTERVAL_SECONDS = 0.05
SAVED = re.compile(r"^([0-9][0-9 ]{11}) (.*)$")
# Android11 shell labels and Android14/16 WifiConfiguration SECURITY_TYPE_NAMES.
SECURITY_NAME = (r"(?:open|wep|wpa2|wpa3|eap|unknown|null|wpa2-psk|wpa2-enterprise|wpa3-sae|"
                 r"wpa3 enterprise 192-bit|owe|wapi-psk|wapi-cert|wpa3 enterprise|"
                 r"passpoint r1/r2|passpoint r3|dpp)")
PRIMARY_SECURITY = re.compile(SECURITY_NAME + r"\^?(?:/" + SECURITY_NAME + r"\^?)*", re.IGNORECASE)


class WifiSetupError(ValueError):
    """Closed reason only; never network names, addresses or command output."""
    def __init__(self, reason, structure=None):
        super().__init__(reason)
        self.reason = reason
        self.structure = structure


def saved_row_structure(lines, index):
    """Bounded format facts only: no network values, addresses, names or name hashes."""
    row = lines[index]
    return {"line_count": min(len(lines), 66), "row_index": min(index, 65),
            "row_width": min(len(row), 65537),
            "has_control": any(ord(char) < 32 or ord(char) == 127 for char in row),
            "id_column_width": min(len(row), 12)}


def java_width(value):
    """Java printf pads by UTF-16 code units, not Python Unicode code points."""
    return len(value.encode("utf-16-le")) // 2


def open_access_point(output):
    lines = output.splitlines()
    if lines == ["No scan results"]:
        raise WifiSetupError("open_ap_missing")
    if len(output) > 65536 or len(lines) > 65:
        raise WifiSetupError("scan_output_unbounded")
    if not lines or " ".join(lines[0].split()) != "BSSID Frequency RSSI Age(sec) SSID Flags":
        raise WifiSetupError("scan_unreadable")
    candidates = []
    for line in lines[1:]:
        row = SCAN.fullmatch(line)
        if row is None or not re.fullmatch(r"(?:[0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}", row[1]):
            raise WifiSetupError("scan_row_unreadable")
        ssid = row[5].rstrip()
        if row[6] == "[ESS]":
            if not ssid or ssid != ssid.strip() or not ssid.isascii() or not ssid.isprintable():
                raise WifiSetupError("ap_name_unrepresentable")
            candidates.append(ssid)
    if not candidates:
        raise WifiSetupError("open_ap_missing")
    if len(candidates) != 1:
        raise WifiSetupError("open_ap_ambiguous")
    return candidates[0]


def saved_networks(output):
    lines = output.splitlines()
    if lines == ["No networks"]:
        return {}
    if len(output) > 65536 or len(lines) > 65:
        raise WifiSetupError("saved_network_output_unbounded")
    if not lines or " ".join(lines[0].split()) != "Network Id SSID Security type":
        raise WifiSetupError("saved_networks_unreadable")
    result = {}
    for index, line in enumerate(lines[1:], 1):
        row = SAVED.fullmatch(line)
        # The ID is a fixed minimum-width column; saved SSIDs may exceed 32 characters.
        if (row is None or len(row[1]) != 12
                or any(ord(char) < 32 or ord(char) == 127 for char in line)):
            raise WifiSetupError("saved_network_row_unreadable", saved_row_structure(lines, index))
        network_id = int(row[1].strip())
        candidates = set()
        # At most 14 primary security names plus separators; never scan an unbounded suffix.
        suffix_start = max(0, len(row[2]) - 1024)
        for separator, char in enumerate(row[2][suffix_start:], suffix_start):
            if char != " ":
                continue
            ssid, security = row[2][:separator].rstrip(), row[2][separator + 1:].strip()
            if not security:
                continue
            canonical = (str(network_id).ljust(12) + " " + ssid
                         + " " * max(0, 32 - java_width(ssid)) + " " + security.ljust(4))
            if canonical == line:
                if PRIMARY_SECURITY.fullmatch(security):
                    candidates.add((ssid, security))
        if len(candidates) != 1 or network_id in result:
            raise WifiSetupError("saved_network_row_unreadable", saved_row_structure(lines, index))
        ssid, security = candidates.pop()
        if security.lower() in ("open", "open/owe^"):
            security = security.upper()
        result[network_id] = (ssid, security)
    return result


class WifiAssociation:
    """Root runner owns cleanup even when the main Android process intentionally dies."""
    def __init__(self, shell, clock=time.monotonic, sleep=time.sleep):
        self.shell, self.clock, self.sleep = shell, clock, sleep
        self.stage = "initial_state"
        self.deadline = clock() + STATE_BUDGET_SECONDS
        status = self.command(self.deadline, "status").splitlines()
        if not status or status[0] not in ("Wifi is enabled", "Wifi is disabled"):
            raise WifiSetupError("initial_radio_unreadable")
        self.enabled = status[0] == "Wifi is enabled"
        self.connected = [line[len("Wifi is connected to "):].strip('"')
                          for line in status if line.startswith("Wifi is connected to ")]
        self.before = saved_networks(self.command(self.deadline, "list-networks"))
        self.ssid = None

    def remaining(self, deadline):
        remaining = deadline - self.clock()
        if remaining <= 0:
            raise WifiSetupError(self.stage + "_deadline_expired")
        return remaining

    def command(self, deadline, *args):
        return self.shell("cmd", "wifi", *args, timeout=min(10, self.remaining(deadline)))

    def observe_radio(self, deadline, enabled):
        expected = "Wifi is enabled" if enabled else "Wifi is disabled"
        while True:
            status = self.command(deadline, "status").splitlines()
            if not status or status[0] not in ("Wifi is enabled", "Wifi is disabled"):
                raise WifiSetupError("radio_observation_unreadable")
            if status[0] == expected:
                return
            self.sleep(min(OBSERVATION_INTERVAL_SECONDS, self.remaining(deadline)))

    def prepare(self):
        self.stage = "radio_enable"
        self.command(self.deadline, "set-wifi-enabled", "enabled")
        self.observe_radio(self.deadline, True)
        self.stage = "scan"
        self.command(self.deadline, "start-scan")
        while True:
            try:
                ssid = open_access_point(self.command(self.deadline, "list-scan-results"))
                break
            except WifiSetupError as error:
                if error.reason != "open_ap_missing":
                    raise
                self.sleep(min(OBSERVATION_INTERVAL_SECONDS, self.remaining(self.deadline)))
        if any(value != ssid for value in self.connected):
            raise WifiSetupError("different_connection_present")
        if any(name == ssid and security not in ("OPEN", "OPEN/OWE^") for name, security in self.before.values()):
            raise WifiSetupError("saved_security_conflict")
        self.ssid = ssid
        return ssid.encode("ascii").hex()

    def cleanup(self):
        deadline = self.clock() + STATE_BUDGET_SECONDS
        self.stage = "config_cleanup"
        try:
            after = saved_networks(self.command(deadline, "list-networks"))
            owned = [(key, value) for key, value in after.items()
                     if key not in self.before and value[0] == self.ssid and value[1] in ("OPEN", "OPEN/OWE^")]
            for key, _ in owned:
                result = self.command(deadline, "forget-network", str(key))
                if "Forget successful" not in result:
                    raise WifiSetupError("owned_config_cleanup_unacknowledged")
            while True:
                remaining = saved_networks(self.command(deadline, "list-networks"))
                if any(key not in remaining or remaining[key] != value for key, value in self.before.items()):
                    raise WifiSetupError("prior_config_changed")
                if not any(key in remaining for key, _ in owned):
                    break
                self.sleep(min(OBSERVATION_INTERVAL_SECONDS, self.remaining(deadline)))
        finally:
            self.stage = "radio_restore"
            restore_deadline = self.clock() + STATE_BUDGET_SECONDS
            self.command(restore_deadline, "set-wifi-enabled", "enabled" if self.enabled else "disabled")
            self.observe_radio(restore_deadline, self.enabled)
