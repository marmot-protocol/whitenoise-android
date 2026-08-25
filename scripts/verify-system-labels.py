#!/usr/bin/env python3
"""Verify packaged Android system labels for every enabled app variant."""

from __future__ import annotations

import argparse
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path

ANDROID_NS = "{http://schemas.android.com/apk/res/android}"
BASE_PACKAGE = "dev.ipf.whitenoise.android"
MAIN_ACTIVITY = f"{BASE_PACKAGE}.MainActivity"
REQUIRED_ACTIONS = {
    "android.intent.action.MAIN",
    "android.intent.action.VIEW",
    "android.intent.action.SEND",
    "android.intent.action.SEND_MULTIPLE",
}
EXPECTED_VARIANTS = {
    "devPlayBenchmarkRelease",
    "devPlayDebug",
    "devPlayNonMinifiedRelease",
    "devZapstoreBenchmarkRelease",
    "devZapstoreDebug",
    "devZapstoreNonMinifiedRelease",
    "previewPlayRelease",
    "previewZapstoreRelease",
    "productionPlayRelease",
    "productionZapstoreRelease",
    "stagingPlayRelease",
    "stagingZapstoreRelease",
}


@dataclass(frozen=True)
class PackageIdentity:
    package_name: str
    label: str


def android_attribute(element: ET.Element, name: str) -> str | None:
    return element.get(f"{ANDROID_NS}{name}")


def expected_identity(
    variant: str,
    preview_channel: str,
    preview_number: str,
) -> PackageIdentity:
    if variant.startswith("dev"):
        return PackageIdentity(f"{BASE_PACKAGE}.dev", "White Noise Dev")
    if variant.startswith("production"):
        return PackageIdentity(BASE_PACKAGE, "White Noise")
    if variant.startswith("staging"):
        return PackageIdentity(f"{BASE_PACKAGE}.staging", "White Noise Staging")
    if variant.startswith("preview"):
        if preview_channel == "stable":
            return PackageIdentity(f"{BASE_PACKAGE}.preview", "White Noise PR")
        return PackageIdentity(
            f"{BASE_PACKAGE}.preview.pr{preview_number}",
            f"PR {preview_number} Isolated",
        )
    raise AssertionError(f"Unexpected app variant: {variant}")


def verify_manifest(path: Path, expected: PackageIdentity) -> PackageIdentity:
    root = ET.parse(path).getroot()
    application = root.find("application")
    if application is None:
        raise AssertionError(f"{path}: missing application element")

    actual = PackageIdentity(
        package_name=root.get("package", ""),
        label=android_attribute(application, "label") or "",
    )
    if actual != expected:
        raise AssertionError(f"{path}: expected {expected}, got {actual}")

    exported_activities = [
        activity
        for tag in ("activity", "activity-alias")
        for activity in application.findall(tag)
        if android_attribute(activity, "exported") == "true"
    ]
    if not exported_activities:
        raise AssertionError(f"{path}: no exported activity entry surfaces")

    for activity in exported_activities:
        resolved_label = android_attribute(activity, "label") or actual.label
        if resolved_label != actual.label:
            name = android_attribute(activity, "name")
            raise AssertionError(
                f"{path}: exported activity {name} resolves {resolved_label!r}, "
                f"not application label {actual.label!r}",
            )

    main_activity = next(
        (
            activity
            for activity in exported_activities
            if android_attribute(activity, "name") == MAIN_ACTIVITY
        ),
        None,
    )
    if main_activity is None:
        raise AssertionError(f"{path}: exported {MAIN_ACTIVITY} is missing")

    actions = {
        android_attribute(action, "name")
        for intent_filter in main_activity.findall("intent-filter")
        for action in intent_filter.findall("action")
    }
    missing_actions = REQUIRED_ACTIONS - actions
    if missing_actions:
        raise AssertionError(f"{path}: MainActivity is missing actions {sorted(missing_actions)}")

    shortcut_resources = {
        android_attribute(metadata, "resource")
        for metadata in main_activity.findall("meta-data")
        if android_attribute(metadata, "name") == "android.app.shortcuts"
    }
    if shortcut_resources != {"@xml/shortcuts"}:
        raise AssertionError(
            f"{path}: MainActivity Direct Share metadata is {shortcut_resources}",
        )

    return actual


def packaged_manifest(manifests_dir: Path, variant: str) -> Path:
    matches = sorted(
        (manifests_dir / variant).glob("process*/universal/AndroidManifest.xml"),
    )
    if len(matches) != 1:
        raise AssertionError(
            f"{variant}: expected one universal packaged manifest, found {len(matches)}",
        )
    return matches[0]


def verify_all(
    manifests_dir: Path,
    preview_channel: str,
    preview_number: str,
) -> None:
    actual_variants = {
        path.name
        for path in manifests_dir.iterdir()
        if path.is_dir() and not path.name.endswith(("AndroidTest", "UnitTest"))
    }
    if actual_variants != EXPECTED_VARIANTS:
        missing_variants = EXPECTED_VARIANTS - actual_variants
        unexpected_variants = actual_variants - EXPECTED_VARIANTS
        raise AssertionError(
            "Packaged-manifest variant set changed: "
            f"missing={sorted(missing_variants)}, "
            f"unexpected={sorted(unexpected_variants)}",
        )

    identities_by_package: dict[str, str] = {}
    for variant in sorted(EXPECTED_VARIANTS):
        expected = expected_identity(variant, preview_channel, preview_number)
        actual = verify_manifest(packaged_manifest(manifests_dir, variant), expected)
        existing_label = identities_by_package.setdefault(actual.package_name, actual.label)
        if existing_label != actual.label:
            raise AssertionError(
                f"{actual.package_name} changes label across variants: "
                f"{existing_label!r} vs {actual.label!r}",
            )
        print(f"{variant}: {actual.package_name} -> {actual.label}")

    coinstallable = [
        PackageIdentity(BASE_PACKAGE, "White Noise"),
        PackageIdentity(f"{BASE_PACKAGE}.dev", "White Noise Dev"),
        PackageIdentity(f"{BASE_PACKAGE}.staging", "White Noise Staging"),
        PackageIdentity(f"{BASE_PACKAGE}.preview", "White Noise PR"),
        PackageIdentity(
            f"{BASE_PACKAGE}.preview.pr{preview_number}",
            f"PR {preview_number} Isolated",
        ),
    ]
    labels = [identity.label for identity in coinstallable]
    if len(labels) != len(set(labels)):
        raise AssertionError(f"Co-installable packages share labels: {coinstallable}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--manifests-dir",
        type=Path,
        default=Path("app/build/intermediates/packaged_manifests"),
    )
    parser.add_argument(
        "--preview-channel",
        choices=("stable", "isolated"),
        required=True,
    )
    parser.add_argument("--preview-number", default="42")
    args = parser.parse_args()

    try:
        verify_all(args.manifests_dir, args.preview_channel, args.preview_number)
    except (AssertionError, ET.ParseError, OSError) as error:
        print(f"system-label verification failed: {error}", file=sys.stderr)
        return 1

    print(
        f"Verified {len(EXPECTED_VARIANTS)} packaged manifests "
        f"for the {args.preview_channel} preview channel.",
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
