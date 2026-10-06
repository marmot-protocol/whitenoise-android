"""Assemble small, source-owned manual-test inputs over the legacy guide."""
from __future__ import annotations

import hashlib
import json
import re
import subprocess
from pathlib import Path, PurePosixPath

CASE_DIR = "docs/manual-release-testing/cases"
SURFACE_DIR = "docs/manual-release-testing/surfaces"
GUIDE_PATH = "docs/manual-release-testing.md"
INVENTORY_PATH = "docs/manual-release-testing-surfaces.json"
ID_RE = re.compile(r"[A-Z]{3,4}-\d{3}")
DEFINITION_RE = re.compile(
    r"^(\d+)\. \[ \] \*\*(?P<id>[A-Z]{3,4}-\d{3}) — (?P<title>[^*]+)\*\* — "
    r"(?P<body>.+?) → \*\*Expected:\*\* (?P<expected>.+)$"
)
HEADER_RE = re.compile(r"<!-- legacy-sha256: ([0-9a-f]{64}|none) -->")


class FragmentError(ValueError):
    """An input is malformed, ambiguous, or would mask a legacy update."""


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise FragmentError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def decode_inventory(text: str) -> dict:
    data = json.loads(text, object_pairs_hook=unique_object)
    if not isinstance(data, dict) or not isinstance(data.get("categories"), dict):
        raise FragmentError("inventory must contain a categories object")
    seen = set()
    for category, entries in data["categories"].items():
        if not isinstance(entries, list) or any(not isinstance(e, dict) for e in entries):
            raise FragmentError(f"{category}: entries must be objects in a list")
        for entry in entries:
            key = (category, entry.get("source", ""), entry.get("surface", ""))
            if any(not isinstance(v, str) for v in key):
                raise FragmentError("source and surface must be strings")
            if key in seen:
                raise FragmentError(f"duplicate surface entry for the same source: {key}")
            seen.add(key)
            if "test_ids" in entry and (
                not isinstance(entry["test_ids"], list)
                or any(not isinstance(test_id, str) for test_id in entry["test_ids"])
            ):
                raise FragmentError("test_ids must be a list of strings")
    exceptions = data.get("discovery_exceptions", [])
    if not isinstance(exceptions, list) or any(not isinstance(e, dict) for e in exceptions):
        raise FragmentError("discovery_exceptions must be a list of objects")
    return data


def digest(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def canonical_definition(line: str) -> str:
    return re.sub(r"^\d+\.", "1.", line)


def definitions(text: str) -> dict[str, str]:
    result = {}
    for line in text.splitlines():
        if match := DEFINITION_RE.fullmatch(line):
            test_id = match["id"]
            if test_id in result:
                raise FragmentError(f"duplicate active ID: {test_id}")
            result[test_id] = canonical_definition(line)
    return result


def source_inventory(data: dict, source: str) -> dict:
    return {
        "categories": {
            category: [e for e in entries if e.get("source") == source]
            for category, entries in sorted(data["categories"].items())
            if any(e.get("source") == source for e in entries)
        },
        "discovery_exceptions": [e for e in data.get("discovery_exceptions", []) if e.get("source") == source],
    }


def source_digest(data: dict, source: str) -> str:
    snapshot = source_inventory(data, source)
    for group in [*snapshot["categories"].values(), snapshot["discovery_exceptions"]]:
        group.sort(key=lambda entry: json.dumps(entry, sort_keys=True))
    return digest(json.dumps(snapshot, sort_keys=True, ensure_ascii=False))


def valid_source(source) -> bool:
    return (
        isinstance(source, str)
        and bool(source)
        and str(PurePosixPath(source)) == source
        and not source.startswith("/")
        and ".." not in PurePosixPath(source).parts
        and "\\" not in source
        and not any(ord(c) < 32 for c in source)
    )


def read_tree(root: Path, revision: str | None, path: str) -> str:
    if revision is None:
        file = root / path
        if file.is_symlink() or file.stat().st_mode & 0o111:
            raise FragmentError(f"{path}: inputs must be non-executable regular files")
        return file.read_text(encoding="utf-8")
    result = subprocess.run(
        ["git", "show", f"{revision}:{path}"], cwd=root, capture_output=True, text=True, timeout=30,
    )
    if result.returncode:
        raise FragmentError(f"cannot read {path} at {revision}")
    return result.stdout


def fragment_paths(root: Path, directory: str, revision: str | None = None) -> list[str]:
    extension = ".md" if directory == CASE_DIR else ".json"
    if revision is None:
        folder = root / directory
        if folder.is_symlink():
            raise FragmentError(f"{directory}: symlink inputs are not allowed")
        return sorted(str(p.relative_to(root)) for p in folder.rglob(f"*{extension}") if p.is_file() or p.is_symlink())
    result = subprocess.run(
        ["git", "ls-tree", "-rz", "--full-tree", revision, "--", directory],
        cwd=root, capture_output=True, timeout=30,
    )
    if result.returncode:
        raise FragmentError(f"cannot read fragment tree at {revision}")
    paths = []
    for record in result.stdout.split(b"\0"):
        if not record:
            continue
        metadata, path = record.split(b"\t", 1)
        if not path.decode("utf-8").endswith(extension):
            continue
        if metadata.split()[0] != b"100644":
            raise FragmentError("fragment must be a non-executable regular file")
        paths.append(path.decode("utf-8"))
    return sorted(paths)


def assemble_guide(text: str, fragments: dict[str, str], *, check_legacy_hash: bool = True) -> str:
    legacy = definitions(text)
    prefix_sections = {}
    section = None
    for line in text.splitlines():
        if line.startswith("#"):
            section = line if line.startswith("### ") else None
        if match := DEFINITION_RE.fullmatch(line):
            prefix_sections.setdefault(match["id"].split("-", 1)[0], set()).add(section)
    replacements = {}
    for path, fragment in sorted(fragments.items()):
        test_id = Path(path).stem
        lines = fragment.strip().splitlines()
        if path != f"{CASE_DIR}/{test_id}.md" or not ID_RE.fullmatch(test_id):
            raise FragmentError(f"{path}: expected a permanent-ID .md filename")
        if len(lines) != 3 or lines[1] or not (header := HEADER_RE.fullmatch(lines[0])):
            raise FragmentError(f"{path}: expected legacy hash, blank line and one unchecked scenario")
        match = DEFINITION_RE.fullmatch(lines[2])
        if not match or match["id"] != test_id or match[1] != "1":
            raise FragmentError(f"{path}: expected exactly one matching unchecked scenario with ordinal 1")
        expected = digest(legacy[test_id]) if test_id in legacy else "none"
        if check_legacy_hash and header[1] != expected:
            raise FragmentError(f"{path}: legacy definition changed; reconcile the fragment")
        if test_id in re.findall(r"^\| ([A-Z]{3,4}-\d{3}) \|", text, re.M):
            raise FragmentError(f"{path}: cannot override a retired ID")
        if test_id not in legacy and len(prefix_sections.get(test_id.split("-", 1)[0], set())) > 1:
            raise FragmentError(f"{path}: prefix spans multiple sections; add this new ID to the shared guide in its intended section")
        replacements[test_id] = lines[2]

    # Appended cases stay inside their existing registered checklist section.
    lines = text.splitlines()
    remaining = dict(replacements)
    result = []
    section_ids = []
    ordinal = 0

    def append_new_cases():
        nonlocal ordinal
        prefixes = {test_id.split("-", 1)[0] for test_id in section_ids}
        for test_id in sorted(set(remaining) - set(legacy)):
            if test_id.split("-", 1)[0] in prefixes:
                ordinal += 1
                result.append(re.sub(r"^1\.", f"{ordinal}.", remaining.pop(test_id)))

    for line in lines:
        if line.startswith("#"):
            append_new_cases()
            section_ids = []
            ordinal = 0
        if match := DEFINITION_RE.fullmatch(line):
            test_id = match["id"]
            section_ids.append(test_id)
            ordinal = int(match[1])
            if test_id in remaining:
                line = re.sub(r"^1\.", f"{ordinal}.", remaining.pop(test_id))
        result.append(line)
    append_new_cases()
    if remaining:
        raise FragmentError(f"no existing checklist section for: {', '.join(sorted(remaining))}")
    return "\n".join(result) + "\n"


def assemble_inventory(data: dict, fragments: dict[str, str], *, check_legacy_hash: bool = True) -> dict:
    # Copy only after validating raw entries: an overlay must not erase duplicates.
    result = json.loads(json.dumps(data))
    for path, text in sorted(fragments.items()):
        fragment = decode_inventory(text)
        source = fragment.get("source")
        if not valid_source(source) or path != f"{SURFACE_DIR}/{source}.json":
            raise FragmentError(f"{path}: source must match its canonical fragment filename")
        if set(fragment) - {"source", "legacy_sha256", "categories", "discovery_exceptions"}:
            raise FragmentError(f"{path}: unknown fragment fields")
        if check_legacy_hash and fragment.get("legacy_sha256") != source_digest(data, source):
            raise FragmentError(f"{path}: legacy source mapping changed; reconcile the fragment")
        if not isinstance(fragment.get("legacy_sha256"), str) or not re.fullmatch(r"[0-9a-f]{64}", fragment["legacy_sha256"]):
            raise FragmentError(f"{path}: invalid legacy source hash")
        entries = [e for group in fragment["categories"].values() for e in group]
        entries += fragment.get("discovery_exceptions", [])
        if any(e.get("source") != source for e in entries):
            raise FragmentError(f"{path}: every entry must belong to {source}")
        for category in sorted(set(result["categories"]) | set(fragment["categories"])):
            replacement = fragment["categories"].get(category, [])
            merged = []
            inserted = False
            for entry in result["categories"].get(category, []):
                if entry.get("source") == source:
                    if not inserted:
                        merged.extend(replacement)
                        inserted = True
                else:
                    merged.append(entry)
            if not inserted:
                merged.extend(replacement)
            result["categories"][category] = merged
        if "discovery_exceptions" in result or fragment.get("discovery_exceptions"):
            result["discovery_exceptions"] = [
                e for e in result.get("discovery_exceptions", []) if e.get("source") != source
            ] + fragment.get("discovery_exceptions", [])
    return result


def load_guide(
    root: Path, guide_path: str = GUIDE_PATH, revision: str | None = None, *, check_legacy_hash: bool = True,
) -> str:
    paths = fragment_paths(root, CASE_DIR, revision)
    return assemble_guide(
        read_tree(root, revision, guide_path), {p: read_tree(root, revision, p) for p in paths},
        check_legacy_hash=check_legacy_hash,
    )


def load_inventory(
    root: Path, inventory_path: str = INVENTORY_PATH, revision: str | None = None, *, check_legacy_hash: bool = True,
) -> dict:
    paths = fragment_paths(root, SURFACE_DIR, revision)
    data = decode_inventory(read_tree(root, revision, inventory_path))
    return assemble_inventory(data, {p: read_tree(root, revision, p) for p in paths}, check_legacy_hash=check_legacy_hash)


def write_new(root: Path, path: str, text: str) -> None:
    destination = root / path
    if any(p.is_symlink() for p in [destination, *destination.parents]):
        raise FragmentError("extraction cannot write through a symlink")
    destination.parent.mkdir(parents=True, exist_ok=True)
    with destination.open("x", encoding="utf-8") as handle:
        handle.write(text)


def extract_case(root: Path, test_id: str) -> None:
    if not ID_RE.fullmatch(test_id):
        raise FragmentError("invalid permanent test ID")
    legacy = definitions(read_tree(root, None, GUIDE_PATH))
    effective = definitions(load_guide(root))
    if test_id not in effective:
        raise FragmentError(f"unknown test ID: {test_id}")
    hash_value = digest(legacy[test_id]) if test_id in legacy else "none"
    write_new(root, f"{CASE_DIR}/{test_id}.md", f"<!-- legacy-sha256: {hash_value} -->\n\n{effective[test_id]}\n")


def extract_source(root: Path, source: str) -> None:
    if not valid_source(source):
        raise FragmentError("invalid source path")
    legacy = decode_inventory(read_tree(root, None, INVENTORY_PATH))
    effective = source_inventory(load_inventory(root), source)
    if not (root / source).is_file():
        raise FragmentError(f"source does not exist: {source}")
    fragment = {"source": source, "legacy_sha256": source_digest(legacy, source), **effective}
    write_new(root, f"{SURFACE_DIR}/{source}.json", json.dumps(fragment, indent=2, ensure_ascii=False) + "\n")
