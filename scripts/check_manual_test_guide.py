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
ANNOTATION_RE = re.compile(r"@[A-Za-z_][A-Za-z0-9_.]*")
IDENTIFIER_RE = re.compile(r"(?:[A-Za-z_][A-Za-z0-9_]*|`[^`\n]+`)")
FUNCTION_MODIFIERS = {
    "abstract",
    "actual",
    "expect",
    "external",
    "final",
    "infix",
    "inline",
    "internal",
    "open",
    "operator",
    "override",
    "private",
    "protected",
    "public",
    "suspend",
    "tailrec",
}
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

# This is deliberately independent of the generated inventory. Every manifest
# permission and Android entry point must match its manually reviewed checklist
# ownership exactly, so regeneration cannot preserve a stale or unrelated ID.
SEMANTIC_OWNER_IDS = {
    "permission:android.permission.ACCESS_COARSE_LOCATION": {"MED-012"},
    "permission:android.permission.ACCESS_FINE_LOCATION": {"MED-012"},
    "permission:android.permission.ACCESS_NETWORK_STATE": {"INT-006", "INT-007"},
    "permission:android.permission.CAMERA": {"MED-004"},
    "permission:android.permission.FOREGROUND_SERVICE": {"NTF-014", "TTS-001", "DIC-001"},
    "permission:android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK": {"TTS-001"},
    "permission:android.permission.FOREGROUND_SERVICE_MICROPHONE": {"DIC-001"},
    "permission:android.permission.FOREGROUND_SERVICE_REMOTE_MESSAGING": {"NTF-006", "NTF-014"},
    "permission:android.permission.FOREGROUND_SERVICE_SPECIAL_USE": {"NTF-006", "NTF-014"},
    "permission:android.permission.INTERNET": {"INT-006", "INT-007"},
    "permission:android.permission.POST_NOTIFICATIONS": {"NTF-001"},
    "permission:android.permission.READ_EXTERNAL_STORAGE": {"MED-002"},
    "permission:android.permission.READ_MEDIA_IMAGES": {"MED-002"},
    "permission:android.permission.READ_MEDIA_VIDEO": {"MED-002"},
    "permission:android.permission.READ_MEDIA_VISUAL_USER_SELECTED": {"MED-002"},
    "permission:android.permission.RECEIVE_BOOT_COMPLETED": {"NTF-014"},
    "permission:android.permission.RECORD_AUDIO": {"MED-008", "DIC-001"},
    "permission:android.permission.REQUEST_INSTALL_PACKAGES": {"MED-018", "SYS-008"},
    "permission:android.permission.VIBRATE": {"NTF-009"},
    "permission:android.permission.WAKE_LOCK": {"NTF-014", "TTS-001"},
    "intent:${deepLinkScheme}": {"SYS-006"},
    "intent:android.intent.action.BOOT_COMPLETED": {"NTF-014"},
    "intent:android.intent.action.MAIN": {"INT-001", "INT-002"},
    "intent:android.intent.action.MY_PACKAGE_REPLACED": {"NTF-014", "SYS-008"},
    "intent:android.intent.action.SEND": {"SYS-004", "SYS-005"},
    "intent:android.intent.action.SEND_MULTIPLE": {"SYS-004", "SYS-005"},
    "intent:android.intent.action.TTS_SERVICE": {"TTS-001", "TTS-002"},
    "intent:android.intent.action.VIEW": {"SYS-006"},
    "intent:android.intent.category.BROWSABLE": {"SYS-006"},
    "intent:android.intent.category.DEFAULT": {"SYS-004", "SYS-006"},
    "intent:android.intent.category.LAUNCHER": {"INT-001", "INT-002"},
    "intent:android.speech.RecognitionService": {"DIC-001", "DIC-002"},
    "intent:android.speech.action.RECOGNIZE_SPEECH": {"DIC-001", "DIC-002"},
    "intent:application/*": {"SYS-004"},
    "intent:audio/*": {"SYS-004"},
    "intent:com.google.firebase.MESSAGING_EVENT": {"NTF-002", "NTF-003", "NTF-006"},
    "intent:image/*": {"SYS-004"},
    "intent:marmot": {"SYS-006"},
    "intent:nostrsigner": {"ONB-010", "ONB-011", "ONB-012"},
    "intent:text/plain": {"SYS-004"},
    "intent:video/*": {"SYS-004"},
    "android-direct-share:conversation-shortcuts": {"SYS-011"},
    "TtsTrustWarningDialog": {"TTS-003"},
}


class ComposableDiscoveryError(ValueError):
    """A declaration-like @Composable site could not be parsed safely."""


def skip_kotlin_trivia(text: str, offset: int) -> int:
    """Skip whitespace and comments without treating declaration text as trivia."""
    while offset < len(text):
        if text[offset].isspace():
            offset += 1
        elif text.startswith("//", offset):
            newline = text.find("\n", offset + 2)
            offset = len(text) if newline < 0 else newline + 1
        elif text.startswith("/*", offset):
            end = text.find("*/", offset + 2)
            offset = len(text) if end < 0 else end + 2
        else:
            break
    return offset


def consume_kotlin_annotation(text: str, offset: int) -> int | None:
    """Consume one annotation, including balanced multiline argument syntax."""
    match = ANNOTATION_RE.match(text, offset)
    if not match:
        return None
    offset = skip_kotlin_trivia(text, match.end())
    if offset >= len(text) or text[offset] != "(":
        return offset

    depth = 0
    quote: str | None = None
    while offset < len(text):
        if quote:
            if quote == '"""' and text.startswith(quote, offset):
                quote = None
                offset += 3
            elif quote != '"""' and text[offset] == "\\":
                offset += 2
            elif quote != '"""' and text[offset] == quote:
                quote = None
                offset += 1
            else:
                offset += 1
            continue
        if text.startswith('"""', offset):
            quote = '"""'
            offset += 3
        elif text[offset] in {'"', "'"}:
            quote = text[offset]
            offset += 1
        elif text.startswith("//", offset):
            newline = text.find("\n", offset + 2)
            offset = len(text) if newline < 0 else newline + 1
        elif text.startswith("/*", offset):
            end = text.find("*/", offset + 2)
            offset = len(text) if end < 0 else end + 2
        elif text[offset] == "(":
            depth += 1
            offset += 1
        elif text[offset] == ")":
            depth -= 1
            offset += 1
            if depth == 0:
                return offset
        else:
            offset += 1
    return None


def consume_kotlin_type_parameters(text: str, offset: int) -> int | None:
    """Consume a balanced Kotlin type-parameter list beginning at ``<``."""
    if offset >= len(text) or text[offset] != "<":
        return None
    depth = 0
    quote: str | None = None
    while offset < len(text):
        if quote:
            if text[offset] == "\\":
                offset += 2
            elif text[offset] == quote:
                quote = None
                offset += 1
            else:
                offset += 1
            continue
        if text[offset] in {'"', "'"}:
            quote = text[offset]
            offset += 1
        elif text.startswith("//", offset):
            newline = text.find("\n", offset + 2)
            offset = len(text) if newline < 0 else newline + 1
        elif text.startswith("/*", offset):
            end = text.find("*/", offset + 2)
            offset = len(text) if end < 0 else end + 2
        elif text[offset] == "<":
            depth += 1
            offset += 1
        elif text[offset] == ">":
            depth -= 1
            offset += 1
            if depth == 0:
                return offset
        else:
            offset += 1
    return None


def composable_declaration_name(text: str, offset: int) -> str | None:
    """Parse one annotated function and return its inventory surface name."""
    declaration_start = offset
    while True:
        token = IDENTIFIER_RE.match(text, offset)
        if token is None:
            return None
        value = token.group(0).strip("`")
        if value == "fun":
            offset = skip_kotlin_trivia(text, token.end())
            break
        if value not in FUNCTION_MODIFIERS:
            return None
        offset = skip_kotlin_trivia(text, token.end())

    if offset < len(text) and text[offset] == "<":
        type_parameters_end = consume_kotlin_type_parameters(text, offset)
        if type_parameters_end is None:
            line = text.count("\n", 0, declaration_start) + 1
            raise ComposableDiscoveryError(f"line {line}: cannot parse @Composable function type parameters")
        offset = skip_kotlin_trivia(text, type_parameters_end)

    identifier = r"(?:[A-Za-z_][A-Za-z0-9_]*|`[^`\n]+`)"
    receiver_atom = rf"{identifier}(?:\s*<[^<>()\n]+>)?\??"
    qualified_re = re.compile(
        rf"(?P<qualified>{receiver_atom}(?:\s*\.\s*{identifier})*)\s*\("
    )
    match = qualified_re.match(text, offset)
    if match is None:
        line = text.count("\n", 0, declaration_start) + 1
        raise ComposableDiscoveryError(f"line {line}: cannot parse @Composable function declaration")

    qualified = re.sub(r"\s*\.\s*", ".", match.group("qualified")).replace("`", "")
    function_name = qualified.rsplit(".", 1)[-1]
    if "." in qualified or (function_name and function_name[0].isupper()):
        return qualified
    return None


def composable_names(text: str) -> set[str]:
    """Find visual and receiver-qualified Compose function declarations."""
    found: set[str] = set()
    for composable in re.finditer(r"(?m)^[ \t]*@Composable\b", text):
        offset = skip_kotlin_trivia(text, composable.end())
        while offset < len(text) and text[offset] == "@":
            annotation_end = consume_kotlin_annotation(text, offset)
            if annotation_end is None:
                line = text.count("\n", 0, offset) + 1
                raise ComposableDiscoveryError(f"line {line}: cannot parse annotation after @Composable")
            offset = skip_kotlin_trivia(text, annotation_end)
        name = composable_declaration_name(text, offset)
        if name:
            found.add(name)
    return found


def current_composable_surfaces() -> set[tuple[str, str]]:
    source_root = ROOT / "app/src/main/java/dev/ipf/whitenoise/android"
    found: set[tuple[str, str]] = set()
    for source in source_root.rglob("*.kt"):
        text = source.read_text(encoding="utf-8")
        relative = str(source.relative_to(ROOT))
        for name in composable_names(text):
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
            test_ids = entry.get("test_ids")
            coverage_exception = entry.get("coverage_exception")
            if not isinstance(test_ids, list):
                errors.append(finding(INVENTORY, 0, entry.get("surface", "inventory"), "test_ids must be a list"))
                test_ids = []
            if not test_ids and not (isinstance(coverage_exception, str) and coverage_exception.strip()):
                errors.append(
                    finding(
                        INVENTORY,
                        0,
                        entry.get("surface", "inventory"),
                        "inventory entry must own at least one active test ID or have a reason-bearing coverage_exception",
                    )
                )
            for test_id in test_ids:
                ownership.setdefault(entry.get("surface", ""), set()).add(test_id)
                if test_id not in active:
                    errors.append(finding(INVENTORY, 0, test_id, "inventory reference does not resolve to an active ID"))
    for entry in data.get("discovery_exceptions", []):
        if not entry.get("reason"):
            errors.append(finding(INVENTORY, 0, entry.get("surface", "exception"), "discovery exception needs a reason"))
    semantic_categories = {"manifest_permissions", "android_entry_points"}
    for category, entries in categories.items():
        for entry in entries:
            surface = entry.get("surface", "")
            expected = SEMANTIC_OWNER_IDS.get(surface)
            if category in semantic_categories and expected is None:
                errors.append(finding(INVENTORY, 0, surface, "missing independently reviewed semantic owner mapping"))
                continue
            if expected is not None and ownership.get(surface, set()) != expected:
                errors.append(
                    finding(
                        INVENTORY,
                        0,
                        surface,
                        f"semantic owner mismatch; expected exactly: {', '.join(sorted(expected))}",
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


def is_user_facing_source(path: str) -> bool:
    """Return whether an Android source-set path can affect shipped behavior."""
    if path in USER_FACING_SOURCE_FILES:
        return True
    if not path.startswith(USER_FACING_SOURCE_PREFIXES):
        return False
    source_set = path.removeprefix("app/src/").split("/", 1)[0]
    is_test_source_set = any(
        source_set == prefix
        or (source_set.startswith(prefix) and source_set[len(prefix) : len(prefix) + 1].isupper())
        for prefix in ("test", "androidTest")
    )
    return not is_test_source_set


def maintenance_files_missing(changed: set[str]) -> set[str]:
    has_user_facing_change = any(is_user_facing_source(path) for path in changed)
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
        if not is_user_facing_source(current) or not line.startswith("+") or line.startswith("+++"):
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
