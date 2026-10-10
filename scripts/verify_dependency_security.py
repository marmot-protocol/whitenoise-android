#!/usr/bin/env python3
"""Validate selected Gradle resolution evidence; never resolve or compile dependencies."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import subprocess


class InvalidEvidence(ValueError):
    """Missing, malformed or unsafe selected-version evidence."""


def below(version: str, minimum: str) -> bool:
    match = re.fullmatch(r"(\d+(?:\.\d+)*)(.*)", version)
    if match is None:
        raise InvalidEvidence("non-numeric selected security dependency version")
    actual = [int(x) for x in match[1].split(".")]
    floor = [int(x) for x in minimum.split(".")]
    length = max(len(actual), len(floor))
    actual.extend([0] * (length - len(actual)))
    floor.extend([0] * (length - len(floor)))
    return actual < floor or (actual == floor and bool(match[2]))


def unsafe_prerelease(version: str, fixed: str | None) -> bool:
    if fixed is None:
        return False
    boundary = re.fullmatch(r"(\d+(?:[.]\d+)*)-alpha(\d+)", fixed)
    if boundary is None:
        raise InvalidEvidence("unsupported prerelease policy")
    actual = re.fullmatch(r"(\d+(?:[.]\d+)*)-alpha(\d*)(?:[.+-].*)?", version)
    return (actual is not None and actual[1] == boundary[1]
            and int(actual[2] or "0") < int(boundary[2]))


def verify(report: dict, minimums: dict[str, str], expected_source: str | None = None,
           prereleases: dict[str, str] | None = None) -> int:
    if (report.get("schema") != 1 or not isinstance(report.get("project"), str)
            or report.get("scope") not in {"project", "plugin"}
            or not isinstance(report.get("configuration"), str)
            or not isinstance(report.get("components"), list)
            or report.get("violations") != [] or report.get("unresolved") != []):
        raise InvalidEvidence("incomplete or failed dependency resolution")
    source = report.get("source_sha")
    if not isinstance(source, str) or not re.fullmatch(r"[a-f0-9]{40}", source):
        raise InvalidEvidence("dependency source identity missing")
    if expected_source is not None and source != expected_source:
        raise InvalidEvidence("dependency evidence belongs to another source revision")
    count = 0
    for component in report["components"]:
        if not isinstance(component, dict) or any(
                not isinstance(component.get(key), str) for key in ("group", "name", "version")):
            raise InvalidEvidence("malformed selected component")
        key = f"{component['group']}:{component['name']}"
        if key in minimums:
            count += 1
            if below(component["version"], minimums[key]) or unsafe_prerelease(
                    component["version"], (prereleases or {}).get(key)):
                raise InvalidEvidence(f"security floor not met: {key}@{component['version']}")
    return count


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--policy", type=Path, default=Path("gradle/dependency-security.json"))
    args = parser.parse_args()
    try:
        policy = json.loads(args.policy.read_text())
        minimums = policy["minimum_versions"]
        if policy.get("schema") != 1 or not isinstance(minimums, dict):
            raise InvalidEvidence("invalid security policy")
        paths = sorted(args.directory.glob("*.json"))
        if not paths:
            raise InvalidEvidence("selected dependency evidence missing")
        source = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True, timeout=10).strip()
        checked = sum(verify(json.loads(path.read_text()), minimums, source, policy.get("fixed_prerelease_versions", {})) for path in paths)
        print(f"Verified {len(paths)} resolved configurations; {checked} security-floor selections")
        return 0
    except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError) as error:
        print(f"Dependency security evidence failed: {error}")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
