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
    from .prepare_marmotkit_artifact import ABI_TARGETS, parse_properties
    from .release_bundle import require, sha256 as digest, validate_version
else:
    from verify_dependency_security import verify
    from prepare_marmotkit_artifact import ABI_TARGETS, parse_properties
    from release_bundle import require, sha256 as digest, validate_version


def stream_digest(stream):
    """Hash a file or ZIP member incrementally without newer interpreter APIs."""
    result = hashlib.sha256()
    for block in iter(lambda: stream.read(1024 * 1024), b""):
        result.update(block)
    return result.hexdigest()


def fingerprint(path):
    """Describe bytes without exposing machine-specific cache locations."""
    require(path.is_file() and not path.is_symlink(), "Expected a regular evidence file")
    return {"name": path.name, "sha256": digest(path), "bytes": path.stat().st_size}


def decode_json(data):
    """Reject ambiguous keys in local files and retained artifact bytes alike."""
    def unique(pairs):
        """Require exactly one value per key in every nested record."""
        result = {}
        for key, value in pairs:
            require(key not in result, "Duplicate inventory/evidence key")
            result[key] = value
        return result
    return json.loads(data, object_pairs_hook=unique)


def read_json(path):
    """Read an existing inventory using the shared duplicate-key rejection boundary."""
    return decode_json(path.read_bytes())


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
        edges = report.get("edges", [])
        require(isinstance(edges, list) and all(isinstance(edge, dict) and
                isinstance(edge.get("from"), str) and isinstance(edge.get("to"), str) and
                isinstance(edge.get("requested", ""), str) for edge in edges), "Malformed dependency edges")
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
            reasons = component.get("reasons", [])
            require(isinstance(reasons, list) and all(isinstance(reason, str) for reason in reasons),
                    "Malformed selection reasons")
            components.append({"coordinate": coordinate, "supplier": component["group"],
                               "supplier_basis": "Maven namespace, not verified legal ownership",
                               "purpose": layer, "selection_reasons": sorted(reasons),
                               "requested_by": sorted([edge for edge in edges if edge["to"] == coordinate],
                                                      key=lambda edge: json.dumps(edge, sort_keys=True)),
                               "licenses": licenses,
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
                 "artifact_coverage": report.get("artifact_coverage"),
                 "edges": edges}
        require(entry["artifact_coverage"] in {"resolved_configuration", "partial"},
                "Missing or unsupported artifact coverage; enable dependencySecurity.inventory")
        unfingerprinted = report.get("unfingerprinted_artifacts", [])
        require(isinstance(unfingerprinted, list) and all(isinstance(item, dict) and
                ((item.get("kind") == "resolution_failure" and "coordinate" not in item and "name" not in item) or
                 (item.get("kind") in {"directory", "non_regular", "read_failure"} and
                  item.get("coordinate") in coordinates and isinstance(item.get("name"), str) and item["name"])) and
                isinstance(item.get("reason"), str) and
                item["reason"] and "sha256" not in item and "bytes" not in item for item in unfingerprinted),
                "Malformed unfingerprinted artifact evidence")
        require(bool(unfingerprinted) == (entry["artifact_coverage"] == "partial"),
                "Partial artifact coverage requires explicit unfingerprinted evidence")
        if unfingerprinted:
            entry["unfingerprinted_artifacts"] = sorted(unfingerprinted, key=lambda x: json.dumps(x, sort_keys=True))
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
            components.append(dict(zip(("group", "name", "version"), parts)) | {"licenses": component["licenses"], "reasons": component["selection_reasons"]})
        reports.append({**entry, "schema": 1, "source_sha": inventory["source_sha"],
                        "components": components, "violations": [], "unresolved": []})
    validated = selected_inventory(reports, inventory["source_sha"])
    normalized = {(entry["project"], entry["scope"], entry["configuration"]): entry for entry in entries}
    for layer in ("release_runtime", "tooling_test", "other_configurations"):
        original = inventory[layer]
        if isinstance(original, dict):
            expected = {key: normalized[(row["project"], row["scope"], row["configuration"])]
                        for key, row in original.items()}
        else:
            expected = [normalized[(row["project"], row["scope"], row["configuration"])] for row in original]
        require(validated[layer] == expected, "Inventory classification or selection metadata mismatch")
    return {(x["project"], x["scope"], x["configuration"]): x for x in entries}


def compare(before, after, before_source, after_source):
    """Compare shared graphs and report observation gaps separately from removals."""
    require(before.get("source_sha") == before_source and after.get("source_sha") == after_source,
            "Inventory delta source mismatch")
    old, new = observations(before), observations(after)
    def configuration(key):
        """Keep a configuration's scope attached to every comparison result."""
        return dict(zip(("project", "scope", "configuration"), key))
    changes = {"schema": 1, "before_source": before_source, "after_source": after_source,
               "coverage": {"newly_observed": [configuration(k) for k in sorted(new.keys() - old.keys())],
                            "no_longer_observed": [configuration(k) for k in sorted(old.keys() - new.keys())]},
               "added": [], "removed": [], "changed_artifacts": [], "changed_edges": [],
               "artifact_comparison_unavailable": []}
    for key in sorted(old.keys() & new.keys()):
        previous = {x["coordinate"]: x for x in old[key]["components"]}
        current = {x["coordinate"]: x for x in new[key]["components"]}
        for side, values, other in [("removed", previous, current), ("added", current, previous)]:
            changes[side].extend({**values[c], **configuration(key)}
                                 for c in sorted(values.keys() - other.keys()))
        previous_edges = {json.dumps(edge, sort_keys=True) for edge in old[key]["edges"]}
        current_edges = {json.dumps(edge, sort_keys=True) for edge in new[key]["edges"]}
        if previous_edges != current_edges:
            changes["changed_edges"].append({**configuration(key),
                "added": [json.loads(edge) for edge in sorted(current_edges - previous_edges)],
                "removed": [json.loads(edge) for edge in sorted(previous_edges - current_edges)]})
        if old[key].get("unfingerprinted_artifacts") or new[key].get("unfingerprinted_artifacts"):
            changes["artifact_comparison_unavailable"].append({**configuration(key),
                "reason": "Artifact delta requires complete regular-file fingerprint coverage"})
            continue
        identity = ("coordinate", "name", "sha256", "bytes")
        previous_artifacts = [{k: artifact[k] for k in identity} for artifact in old[key]["artifacts"]]
        current_artifacts = [{k: artifact[k] for k in identity} for artifact in new[key]["artifacts"]]
        if previous_artifacts != current_artifacts:
            changes["changed_artifacts"].append({**configuration(key),
                                                 "before": previous_artifacts, "after": current_artifacts})
    for layer in ("native", "packaging"):
        available = layer in before and layer in after
        changes[layer] = {"status": "compared" if available else "unavailable",
                          "before_observed": layer in before, "after_observed": layer in after}
        if available:
            changes[layer]["changed"] = before[layer] != after[layer]
            if changes[layer]["changed"]:
                changes[layer].update(before=before[layer], after=after[layer])
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
            match = re.fullmatch(re.escape(prefix) + r"([A-Za-z0-9_-]+)/([A-Za-z0-9_.+-]+\.so)", name)
            require(match, "Malformed packaged native path")
            with archive.open(name) as stream:
                sha = stream_digest(stream)
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
    require(isinstance(libraries, list) and len(libraries) == 4 and all(isinstance(x, dict) for x in libraries) and
            {x.get("abi") for x in libraries} == {"arm64-v8a", "armeabi-v7a", "x86", "x86_64"} and
            all(isinstance(x, dict) and x.get("target") == ABI_TARGETS.get(x.get("abi")) and
                re.fullmatch(r"[a-f0-9]{64}", x.get("sha256", "")) for x in libraries),
            "Malformed native target inventory")
    features = provenance.get("features", {})
    selected_features = features.get("android_lock")
    require(isinstance(selected_features, list) and selected_features and
            all(isinstance(feature, str) and re.fullmatch(r"[A-Za-z0-9_-]+", feature) for feature in selected_features) and
            selected_features == sorted(set(selected_features)), "Malformed native feature selection")
    upstream = features.get("upstream_manifest")
    require((upstream is None and features.get("status") == "unverified") or
            (upstream == selected_features and features.get("status") == "matched_manifest"),
            "Native manifest features differ from Android selection")
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
            validate_version(version)
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
