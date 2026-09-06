#!/usr/bin/env python3
"""Validate the repository-owned White Noise Android manual test guide."""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GUIDE = ROOT / "docs/manual-release-testing.md"
INVENTORY = ROOT / "docs/manual-release-testing-surfaces.json"
README = ROOT / "README.md"
AGENTS = ROOT / "AGENTS.md"
ID_RE = re.compile(r"[A-Z]{3,4}-\d{3}")
DEFINITION_RE = re.compile(
    r"^(\d+)\. \[ \] \*\*(?P<id>[A-Z]{3,4}-\d{3}) — (?P<title>[^*]+)\*\* — "
    r"(?P<body>.+?) → \*\*Expected:\*\* (?P<expected>.+)$"
)
CANDIDATE_RE = re.compile(r"^(?:\d+\.|[-*])\s*\[[^]]*]\s*\*\*|\*\*[A-Z]{3,4}-\d{3}")
COMPOSABLE_RE = re.compile(
    r"@Composable(?:\s|@[A-Za-z0-9_.()=, ]+)*?\s+"
    r"(?:(?:internal|private|public)\s+)?fun\s+([A-Z][A-Za-z0-9_]*)\s*\(",
    re.S,
)
REQUIRED_HEADINGS = [
    "# White Noise Android manual release testing",
    "## How to use this guide",
    "## Safety and test-data rules",
    "## Test matrix and prerequisites",
    "## Smoke pass",
    "## Full release checklist",
    "## Report a failure",
    "## Maintainer coverage map",
    "## Retired IDs",
]


def finding(path: Path, line: int, key: str, message: str) -> str:
    return f"{path.relative_to(ROOT)}:{line}: {key}: {message}"


def section_bounds(lines: list[str], heading: str) -> tuple[int, int]:
    start = next((i for i, line in enumerate(lines) if line == heading), -1)
    if start < 0:
        return -1, -1
    level = len(heading) - len(heading.lstrip("#"))
    end = len(lines)
    for i in range(start + 1, len(lines)):
        line = lines[i]
        if line.startswith("#") and len(line) - len(line.lstrip("#")) <= level:
            end = i
            break
    return start, end


def parse_guide(text: str) -> tuple[set[str], set[str], list[str]]:
    lines = text.splitlines()
    errors: list[str] = []
    positions = []
    for heading in REQUIRED_HEADINGS:
        matches = [i for i, line in enumerate(lines) if line == heading]
        if len(matches) != 1:
            errors.append(finding(GUIDE, matches[0] + 1 if matches else 0, "structure", f"expected exactly one {heading!r}"))
        positions.append(matches[0] if matches else -1)
    if any(a >= b for a, b in zip(positions, positions[1:]) if a >= 0 and b >= 0):
        errors.append(finding(GUIDE, 0, "structure", "required headings are out of order"))

    full_start, full_end = section_bounds(lines, "## Full release checklist")
    retired_start, retired_end = section_bounds(lines, "## Retired IDs")
    prefixes: set[str] = set()
    if full_start >= 0:
        for line in lines[full_start:full_end]:
            match = re.match(r"\| `([A-Z]{3,4})` \|", line)
            if match:
                prefixes.add(match.group(1))

    active: set[str] = set()
    current_h3 = None
    expected_ordinal = 1
    if full_start >= 0:
        for index in range(full_start + 1, full_end):
            line = lines[index]
            if line.startswith("### "):
                current_h3 = line
                expected_ordinal = 1
                continue
            if not line or line.startswith(" ") or line.startswith("|"):
                continue
            if CANDIDATE_RE.search(line):
                if "[x]" in line.lower():
                    errors.append(finding(GUIDE, index + 1, "checked-box", "canonical test boxes must remain unchecked"))
                match = DEFINITION_RE.fullmatch(line)
                if not match:
                    errors.append(finding(GUIDE, index + 1, "malformed-definition", "test item does not match the required action → expected-result grammar"))
                    continue
                if current_h3 is None:
                    errors.append(finding(GUIDE, index + 1, match.group("id"), "definition must be under an H3 checklist section"))
                ordinal = int(match.group(1))
                if ordinal != expected_ordinal:
                    errors.append(finding(GUIDE, index + 1, match.group("id"), f"ordinal {ordinal} should be {expected_ordinal} in this section"))
                expected_ordinal += 1
                test_id = match.group("id")
                if test_id in active:
                    errors.append(finding(GUIDE, index + 1, test_id, "duplicate active ID"))
                active.add(test_id)
                if test_id.split("-", 1)[0] not in prefixes:
                    errors.append(finding(GUIDE, index + 1, test_id, "prefix is not registered"))

    retired: set[str] = set()
    if retired_start >= 0:
        for index in range(retired_start + 1, retired_end):
            line = lines[index]
            if not line.startswith("|") or line.startswith("|---") or "| ID |" in line:
                continue
            cells = [cell.strip() for cell in line.strip("|").split("|")]
            if len(cells) != 4:
                errors.append(finding(GUIDE, index + 1, "retired", "retired row must have four columns"))
                continue
            test_id, retired_in, reason, superseded = cells
            if not ID_RE.fullmatch(test_id):
                errors.append(finding(GUIDE, index + 1, "retired", "invalid retired ID"))
                continue
            if not (re.fullmatch(r"#\d+", retired_in) or re.fullmatch(r"[0-9a-f]{40}", retired_in)):
                errors.append(finding(GUIDE, index + 1, test_id, "Retired in must be a PR number or 40-character SHA"))
            if not reason:
                errors.append(finding(GUIDE, index + 1, test_id, "retirement reason is empty"))
            if superseded != "—" and superseded not in active:
                errors.append(finding(GUIDE, index + 1, test_id, "Superseded by must be an active ID or —"))
            retired.add(test_id)
    for test_id in sorted(active & retired):
        errors.append(finding(GUIDE, 0, test_id, "ID cannot be both active and retired"))

    for prefix in sorted(prefixes):
        if not any(item.startswith(prefix + "-") for item in active):
            errors.append(finding(GUIDE, 0, prefix, "registered prefix has no active definition"))

    for index, line in enumerate(lines, 1):
        if retired_start >= 0 and retired_start < index - 1 < retired_end:
            continue
        for token in re.findall(r"`([A-Z]{3,4}-\d{3})`", line):
            if token != "XXX-000" and token not in active:
                errors.append(finding(GUIDE, index, token, "reference does not resolve to an active ID"))
    return active, retired, errors


def validate_links(errors: list[str]) -> None:
    target = "](docs/manual-release-testing.md)"
    readme_lines = README.read_text(encoding="utf-8").splitlines()
    start, end = section_bounds(readme_lines, "## Device Testing")
    if start < 0 or not any(target in line for line in readme_lines[start:end]):
        errors.append(finding(README, 0, "link", "Device Testing must link to docs/manual-release-testing.md"))
    if target not in AGENTS.read_text(encoding="utf-8"):
        errors.append(finding(AGENTS, 0, "link", "must link to docs/manual-release-testing.md"))


REQUIRED_INVENTORY_CATEGORIES = {
    "composable_surfaces",
    "settings_controls",
    "manifest_permissions",
    "android_entry_points",
}


def current_composable_surfaces() -> set[tuple[str, str]]:
    source_root = ROOT / "app/src/main/java/dev/ipf/whitenoise/android"
    found: set[tuple[str, str]] = set()
    for source in source_root.rglob("*.kt"):
        text = source.read_text(encoding="utf-8")
        relative = str(source.relative_to(ROOT))
        for name in COMPOSABLE_RE.findall(text):
            found.add((relative, name))
    return found


def validate_inventory(active: set[str], errors: list[str]) -> None:
    try:
        data = json.loads(INVENTORY.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        errors.append(finding(INVENTORY, 0, "inventory", f"cannot read inventory: {exc}"))
        return
    categories = data.get("categories")
    if not isinstance(categories, dict):
        errors.append(finding(INVENTORY, 0, "categories", "inventory must contain a categories object"))
        return
    for category in sorted(REQUIRED_INVENTORY_CATEGORIES - set(categories)):
        errors.append(finding(INVENTORY, 0, category, "required inventory category is missing"))
    listed_composables = {
        (entry.get("source", ""), entry.get("surface", ""))
        for entry in categories.get("composable_surfaces", [])
    }
    for source, surface in sorted(current_composable_surfaces() - listed_composables):
        errors.append(
            finding(
                INVENTORY,
                0,
                surface,
                f"current composable from {source} is missing from the inventory",
            )
        )
    seen: set[tuple[str, str, str]] = set()
    ownership: dict[str, set[str]] = {}
    for category, entries in categories.items():
        if not entries:
            errors.append(finding(INVENTORY, 0, category, "category is empty"))
        for entry in entries:
            key = (category, entry.get("source", ""), entry.get("surface", ""))
            if key in seen:
                errors.append(finding(INVENTORY, 0, entry.get("surface", "inventory"), "duplicate surface entry for the same source"))
            seen.add(key)
            source = ROOT / entry.get("source", "")
            if not source.is_file():
                errors.append(finding(INVENTORY, 0, entry.get("surface", "inventory"), "source file does not exist"))
                continue
            anchor = entry.get("anchor")
            if anchor and anchor not in source.read_text(encoding="utf-8"):
                errors.append(finding(INVENTORY, 0, entry.get("surface", "inventory"), "source anchor no longer exists"))
            for test_id in entry.get("test_ids", []):
                ownership.setdefault(entry.get("surface", ""), set()).add(test_id)
                if test_id not in active:
                    errors.append(finding(INVENTORY, 0, test_id, "inventory reference does not resolve to an active ID"))
    for entry in data.get("discovery_exceptions", []):
        if not entry.get("reason"):
            errors.append(finding(INVENTORY, 0, entry.get("surface", "exception"), "discovery exception needs a reason"))
    if not ({"manifest_permissions", "android_entry_points"} & set(categories)):
        return
    required_owners = {
        "permission:android.permission.ACCESS_FINE_LOCATION": {"MED-012", "SEC-005"},
        "permission:android.permission.CAMERA": {"MED-004", "SEC-005"},
        "permission:android.permission.POST_NOTIFICATIONS": {"NTF-001", "SEC-005"},
        "permission:android.permission.RECORD_AUDIO": {"CON-009", "DIC-001", "SEC-005"},
        "permission:android.permission.REQUEST_INSTALL_PACKAGES": {"MED-018", "SYS-008", "SEC-005"},
        "intent:android.intent.action.SEND": {"SYS-004", "SYS-005"},
        "intent:android.intent.action.SEND_MULTIPLE": {"SYS-004", "SYS-005"},
        "intent:android.intent.action.TTS_SERVICE": {"TTS-001", "TTS-002"},
        "intent:android.speech.RecognitionService": {"DIC-001", "DIC-002"},
        "intent:com.google.firebase.MESSAGING_EVENT": {"NTF-002", "NTF-003", "NTF-006"},
        "intent:marmot": {"SYS-006"},
        "intent:nostrsigner": {"ONB-009", "ONB-010"},
        "android-direct-share:conversation-shortcuts": {"SYS-011"},
        "TtsTrustWarningDialog": {"TTS-003"},
    }
    for surface, expected in required_owners.items():
        missing = expected - ownership.get(surface, set())
        if missing:
            errors.append(
                finding(
                    INVENTORY,
                    0,
                    surface,
                    f"missing owning test IDs: {', '.join(sorted(missing))}",
                )
            )


def parse_revision_guide(revision: str) -> tuple[set[str], set[str]] | None:
    result = subprocess.run(["git", "show", f"{revision}:docs/manual-release-testing.md"], cwd=ROOT, capture_output=True, text=True)
    if result.returncode != 0:
        return None
    active, retired, _ = parse_guide(result.stdout)
    return active, retired


def validate_history(base: str, active: set[str], retired: set[str], errors: list[str]) -> None:
    previous = parse_revision_guide(base)
    if previous is None:
        return
    old_active, old_retired = previous
    for test_id in sorted(old_active - active - retired):
        errors.append(finding(GUIDE, 0, test_id, "prior active ID was removed without retirement"))
    for test_id in sorted(old_retired - retired):
        errors.append(finding(GUIDE, 0, test_id, "prior retired ID must remain retired"))
    maxima: dict[str, int] = {}
    for test_id in old_active | old_retired:
        prefix, suffix = test_id.split("-")
        maxima[prefix] = max(maxima.get(prefix, 0), int(suffix))
    for test_id in sorted(active - old_active):
        prefix, suffix = test_id.split("-")
        if int(suffix) <= maxima.get(prefix, 0):
            errors.append(finding(GUIDE, 0, test_id, "new ID must append above the previous prefix maximum"))


GUIDE_PATH = "docs/manual-release-testing.md"
INVENTORY_PATH = "docs/manual-release-testing-surfaces.json"
USER_FACING_SOURCE_PREFIXES = (
    # Conservatively gate every Android source-set file. User-visible behavior
    # can enter through Kotlin, manifests, resources, or flavor/debug/staging
    # overlays, and a narrower path allow-list silently misses new surfaces.
    "app/src/",
)
USER_FACING_SOURCE_FILES = {
    "app/src/main/AndroidManifest.xml",
    "app/src/main/java/dev/ipf/whitenoise/android/MainActivity.kt",
    "app/src/main/java/dev/ipf/whitenoise/android/WhiteNoiseApplication.kt",
}


def maintenance_files_missing(changed: set[str]) -> set[str]:
    has_user_facing_change = any(
        path in USER_FACING_SOURCE_FILES or path.startswith(USER_FACING_SOURCE_PREFIXES)
        for path in changed
    )
    if not has_user_facing_change:
        return set()
    return {GUIDE_PATH, INVENTORY_PATH} - changed


def added_surface_tokens(diff: str) -> list[tuple[str, str]]:
    """Return new user-facing symbols that must be anchored in the inventory."""
    current = ""
    found: list[tuple[str, str]] = []
    for line in diff.splitlines():
        if line.startswith("+++ b/"):
            current = line[6:]
            continue
        if not current.startswith("app/src/") or not line.startswith("+") or line.startswith("+++"):
            continue
        added = line[1:]
        for name in re.findall(r"fun\s+([A-Z][A-Za-z0-9_]*(?:Screen|Dialog|Sheet|Picker|Viewer|Bar|Pane|Content))\s*\(", added):
            found.append((current, f"fun {name}("))
        for name in re.findall(r"R\.string\.([a-zA-Z0-9_]+)", added):
            found.append((current, f"R.string.{name}"))
        if current.endswith("AndroidManifest.xml"):
            for token in re.findall(r"android\.(?:permission|intent)\.[A-Za-z0-9_.]+", added):
                found.append((current, token))
            for token in re.findall(r'android:(?:scheme|mimeType)="([^"$]+)"', added):
                found.append((current, token))
    return sorted(set(found))


def inventory_anchor_index() -> set[tuple[str, str]]:
    data = json.loads(INVENTORY.read_text(encoding="utf-8"))
    return {
        (entry.get("source", ""), entry.get("anchor", ""))
        for entries in data.get("categories", {}).values()
        for entry in entries
    }


def validate_changed_surface_contract(base: str, errors: list[str]) -> None:
    result = subprocess.run(
        ["git", "diff", "--name-only", "--diff-filter=ACDMRTUXB", base, "--"],
        cwd=ROOT,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        errors.append(finding(GUIDE, 0, "maintenance", f"cannot compare changed files with {base}"))
        return
    missing = maintenance_files_missing(set(result.stdout.splitlines()))
    for path in sorted(missing):
        errors.append(finding(GUIDE, 0, "maintenance", f"user-facing source changed without updating {path}"))
    diff_result = subprocess.run(
        ["git", "diff", "--unified=0", base, "--", "app/src"],
        cwd=ROOT,
        capture_output=True,
        text=True,
    )
    if diff_result.returncode != 0:
        errors.append(finding(GUIDE, 0, "maintenance", f"cannot inspect added surfaces against {base}"))
        return
    anchors = inventory_anchor_index()
    for source, token in added_surface_tokens(diff_result.stdout):
        if (source, token) not in anchors:
            errors.append(
                finding(
                    INVENTORY,
                    0,
                    token,
                    f"new user-facing token from {source} is not anchored in the inventory",
                )
            )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base")
    args = parser.parse_args()
    active, retired, errors = parse_guide(GUIDE.read_text(encoding="utf-8"))
    validate_links(errors)
    validate_inventory(active, errors)
    if args.base:
        validate_history(args.base, active, retired, errors)
        validate_changed_surface_contract(args.base, errors)
    if errors:
        print("\n".join(errors))
        return 1
    print(f"manual test guide OK: {len(active)} active IDs, {len(retired)} retired IDs")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
