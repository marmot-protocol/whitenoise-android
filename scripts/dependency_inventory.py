#!/usr/bin/env python3
"""Export observed resolution and packaging evidence without resolving or building."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import zipfile

if __package__:
    from .verify_dependency_security import verify
    from .prepare_marmotkit_artifact import parse_properties
else:
    from verify_dependency_security import verify
    from prepare_marmotkit_artifact import parse_properties


def require(condition, message):
    """Reject evidence that cannot support the requested inventory claim."""
    if not condition:
        raise ValueError(message)


def digest(path):
    """Hash existing payloads incrementally, including large APKs and native files."""
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def fingerprint(path):
    """Describe bytes without exposing machine-specific cache locations."""
    require(path.is_file() and not path.is_symlink(), "Expected a regular evidence file")
    return {"name": path.name, "sha256": digest(path), "bytes": path.stat().st_size}


def read_json(path):
    """Reject ambiguous keys rather than accepting last-value-wins evidence."""
    def unique(pairs):
        """Require exactly one value per key in every nested record."""
        result = {}
        for key, value in pairs:
            require(key not in result, "Duplicate inventory/evidence key")
            result[key] = value
        return result
    return json.loads(path.read_text(), object_pairs_hook=unique)


def purpose(report):
    """Reserve shipping classification for exact app production runtime variants."""
    configuration = report["configuration"]
    runtime = re.fullmatch(r"production(Play|Zapstore)ReleaseRuntimeClasspath", configuration)
    if report["scope"] == "project" and report["project"] == ":app" and runtime:
        return "release_runtime", runtime[1].lower()
    if report["scope"] == "plugin" or re.search(r"test|lint|detekt|ktlint|kover|kapt|ksp", configuration, re.I):
        return "tooling_test", None
    return "other_configurations", None


def selected_inventory(reports, source):
    """Validate every observation and keep distribution, tooling and other graphs apart."""
    require(re.fullmatch(r"[a-f0-9]{40}", source or ""), "Invalid Android source identity")
    require(reports, "Dependency resolution evidence missing")
    require(all(isinstance(r, dict) for r in reports), "Malformed dependency resolution record")
    result = {"schema": 1, "source_sha": source, "release_runtime": {}, "tooling_test": [],
              "other_configurations": [], "retained_maven_classes": {"status": "unverified",
                  "reason": "Resolution and R8 mappings do not establish retained dependency classes."}}
    seen = {}
    for report in sorted(reports, key=lambda r: (r.get("project", ""), r.get("scope", ""), r.get("configuration", ""))):
        verify(report, {}, source)
        key = (report["project"], report["scope"], report["configuration"])
        if key in seen:
            require(seen[key] == report, "Conflicting dependency selections for one configuration")
            continue
        seen[key] = report
        layer, distribution = purpose(report)
        components = []
        coordinates = set()
        for component in report["components"]:
            require(all(component[k] for k in ("group", "name", "version")), "Empty Maven coordinate")
            coordinate = ":".join(component[k] for k in ("group", "name", "version"))
            require(coordinate not in coordinates, "Duplicate selected component")
            coordinates.add(coordinate)
            licenses = component.get("licenses", [])
            require(isinstance(licenses, list) and all(isinstance(x, dict) and
                    isinstance(x.get("name"), str) and isinstance(x.get("url"), str) and
                    re.fullmatch(r"[a-f0-9]{64}", x.get("pom_sha256", "")) for x in licenses),
                    "Malformed licence evidence")
            components.append({"coordinate": coordinate, "supplier": component["group"],
                               "supplier_basis": "Maven namespace, not verified legal ownership",
                               "purpose": layer, "licenses": licenses,
                               "license_status": "declared_in_cached_pom" if licenses else "not_observed"})
        artifacts = report.get("artifacts")
        require(isinstance(artifacts, list), "Dependency artifact evidence missing")
        for artifact in artifacts:
            require(isinstance(artifact, dict) and artifact.get("coordinate") in coordinates and
                    isinstance(artifact.get("name"), str) and
                    re.fullmatch(r"[a-f0-9]{64}", artifact.get("sha256", "")) and
                    type(artifact.get("bytes")) is int and artifact["bytes"] >= 0,
                    "Malformed dependency artifact evidence")
        entry = {"project": key[0], "scope": key[1], "configuration": key[2],
                 "components": sorted(components, key=lambda x: x["coordinate"]),
                 "artifacts": sorted(artifacts, key=lambda x: (x["coordinate"], x["name"])),
                 "artifact_coverage": report.get("artifact_coverage", "observed"),
                 "edges": report.get("edges", [])}
        if distribution:
            result[layer][distribution] = entry
        else:
            result[layer].append(entry)
    return result


def observations(inventory):
    """Index selections by configuration, preventing cross-scope delta comparisons."""
    require(inventory.get("schema") == 1, "Unsupported inventory schema")
    entries = list(inventory["release_runtime"].values()) + inventory["tooling_test"] + inventory["other_configurations"]
    reports = []
    for entry in entries:
        components = []
        for component in entry["components"]:
            parts = component["coordinate"].split(":")
            require(len(parts) == 3 and all(parts), "Malformed inventory coordinate")
            components.append(dict(zip(("group", "name", "version"), parts)) | {"licenses": component["licenses"]})
        reports.append({**entry, "schema": 1, "source_sha": inventory["source_sha"],
                        "components": components, "violations": [], "unresolved": []})
    validated = selected_inventory(reports, inventory["source_sha"])
    for layer in ("release_runtime", "tooling_test", "other_configurations"):
        require(validated[layer] == inventory[layer], "Inventory classification or selection metadata mismatch")
    return {(x["project"], x["scope"], x["configuration"]): x for x in entries}


def compare(before, after, before_source, after_source):
    """Return additions/removals only for matching observed configuration coverage."""
    require(before.get("source_sha") == before_source and after.get("source_sha") == after_source,
            "Inventory delta source mismatch")
    old, new = observations(before), observations(after)
    require(old.keys() == new.keys(), "Inventory delta requires identical configuration coverage")
    changes = {"schema": 1, "before_source": before_source, "after_source": after_source, "added": [], "removed": [],
               "changed_artifacts": []}
    for key in sorted(old):
        previous = {x["coordinate"]: x for x in old[key]["components"]}
        current = {x["coordinate"]: x for x in new[key]["components"]}
        for side, values, other in [("removed", previous, current), ("added", current, previous)]:
            changes[side].extend({**values[c], "configuration": key[2], "project": key[0], "scope": key[1]}
                                 for c in sorted(values.keys() - other.keys()))
        identity = ("coordinate", "name", "sha256", "bytes")
        previous_artifacts = [{k: artifact[k] for k in identity} for artifact in old[key]["artifacts"]]
        current_artifacts = [{k: artifact[k] for k in identity} for artifact in new[key]["artifacts"]]
        if previous_artifacts != current_artifacts:
            changes["changed_artifacts"].append({"configuration": key[2], "project": key[0], "scope": key[1],
                                                  "before": previous_artifacts, "after": current_artifacts})
    return changes


def packaged_native(path, distribution, provenance):
    """Hash actual packaged libraries and require the pinned MarmotKit bytes per ABI."""
    known = {x["abi"]: x["sha256"] for x in provenance["libraries"]}
    prefix = "base/lib/" if distribution == "play" else "lib/"
    records = []
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)), "Duplicate packaged entries")
        for name in sorted(names):
            if not name.startswith(prefix) or not name.endswith(".so"):
                continue
            match = re.fullmatch(re.escape(prefix) + r"([A-Za-z0-9_-]+)/([A-Za-z0-9_.-]+\.so)", name)
            require(match, "Malformed packaged native path")
            with archive.open(name) as stream:
                sha = hashlib.file_digest(stream, "sha256").hexdigest()
            marmot = match[2] == "libmarmot_uniffi.so"
            require(not marmot or known.get(match[1]) == sha, "Packaged MarmotKit differs from verified artifact")
            records.append({"path": name, "abi": match[1], "sha256": sha,
                            "marmotkit_match": marmot, "supplier": "marmot-protocol/mdk" if marmot else "unattributed"})
    expected_abis = {"arm64-v8a"} if distribution == "zapstore" else set(known)
    actual_abis = {x["abi"] for x in records if x["marmotkit_match"]}
    require(actual_abis == expected_abis, "Packaged MarmotKit ABI set differs from release contract")
    return records


def release_inventory(selected, provenance, payloads, mappings, lock_sha, lock_properties=None):
    """Bind each release distribution to selected graphs, native provenance and bytes."""
    require(set(selected["release_runtime"]) == {"play", "zapstore"}, "Both release runtime graphs are required")
    require(provenance.get("schema") == 1 and provenance.get("lock_sha256") == lock_sha,
            "Native provenance does not match the Android MarmotKit lock")
    require(re.fullmatch(r"[a-f0-9]{40}", provenance.get("source_sha", "")) and
            re.fullmatch(r"[a-f0-9]{64}", provenance.get("artifact_sha256", "")), "Malformed native provenance")
    libraries = provenance.get("libraries")
    require(isinstance(libraries, list) and len(libraries) == 4 and
            {x.get("abi") for x in libraries} == {"arm64-v8a", "armeabi-v7a", "x86", "x86_64"} and
            all(re.fullmatch(r"[a-f0-9]{64}", x.get("sha256", "")) for x in libraries),
            "Malformed native target inventory")
    if lock_properties is not None:
        for record_key, lock_key in [("source", "source"), ("source_sha", "mdk-sha"),
                                     ("artifact_sha256", "artifact-sha256"), ("artifact_id", "artifact-id")]:
            require(provenance.get(record_key) == lock_properties[lock_key], "Native provenance/source pin mismatch")
        require(provenance.get("features", {}).get("android_lock") == sorted(lock_properties["features"].split(",")),
                "Native feature selection mismatch")
    require(set(payloads) == set(mappings) == {"play", "zapstore"}, "Both release payloads/mappings are required")
    result = {**selected, "native": {**provenance, "crate_reachability": {"status": "unverified",
               "reason": "Artifact provenance/Cargo.lock digest is not an Android target-resolved link set."}}, "packaging": {}}
    for distribution in sorted(payloads):
        result["packaging"][distribution] = {
            "artifact": fingerprint(payloads[distribution]), "r8_mapping": fingerprint(mappings[distribution]),
            "native_libraries": packaged_native(payloads[distribution], distribution, provenance)}
    return result


def export_selected(directory, source, output):
    """Reuse reports emitted by this job; never resolve another configuration."""
    reports = [read_json(path) for path in sorted(directory.glob("*.json"))]
    result = selected_inventory(reports, source)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n")
    return result


def main():
    """Assemble release evidence or compare explicitly identified inventory sources."""
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    delta = sub.add_parser("compare")
    for name in ("before", "after", "output"):
        delta.add_argument("--" + name, type=Path, required=True)
    delta.add_argument("--before-source", required=True)
    delta.add_argument("--after-source", required=True)
    release = sub.add_parser("release")
    release.add_argument("--bundle", type=Path, required=True)
    release.add_argument("--output", type=Path, required=True)
    release.add_argument("--reports", type=Path, default=Path("build/reports/dependency-security"))
    release.add_argument("--provenance", type=Path, default=Path("app/build/reports/marmotkit/native-provenance.json"))
    release.add_argument("--lock", type=Path, default=Path("app/src/main/marmotkit/MARMOT_VERSION"))
    args = parser.parse_args()
    try:
        if args.command == "compare":
            result = compare(read_json(args.before), read_json(args.after),
                             args.before_source, args.after_source)
        else:
            source = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
            manifest_path = args.bundle / "release-manifest.json"
            manifest = read_json(manifest_path)
            require(manifest.get("sourceCommit") == source and manifest.get("worktreeDirty") is False,
                    "Release source/clean-worktree evidence mismatch")
            version = manifest["versionName"]
            require(re.fullmatch(r"20\d{2}\.\d{1,2}\.\d{1,2}", version or ""), "Invalid release version")
            payloads = {"play": args.bundle / f"whitenoise-android-{version}-play.aab",
                        "zapstore": args.bundle / f"whitenoise-android-{version}-arm64-v8a.apk"}
            mappings = {d: args.bundle / f"mapping-{version}-{d}.txt" for d in payloads}
            for path in [*payloads.values(), *mappings.values()]:
                require(fingerprint(path) == {"name": path.name, **manifest["files"][path.name]},
                        "Release manifest/payload digest mismatch")
            selected = selected_inventory([read_json(p) for p in sorted(args.reports.glob("*.json"))], source)
            result = release_inventory(selected, read_json(args.provenance), payloads, mappings,
                                       digest(args.lock), parse_properties(args.lock))
            result["release_manifest"] = fingerprint(manifest_path)
            result["version"] = version
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, sort_keys=True, indent=2) + "\n")
        print(f"Dependency inventory written: {args.output}")
        return 0
    except (OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile, subprocess.SubprocessError) as error:
        print(f"Dependency inventory failed: {error}")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
