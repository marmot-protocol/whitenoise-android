#!/usr/bin/env python3
"""Validate public Android release invariants and store-listing assets."""

from __future__ import annotations

import re
import struct
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
PROPERTIES = ROOT / "config" / "android-release.properties"
GRADLE = ROOT / "app" / "build.gradle.kts"
UPDATE_MODELS = (
    ROOT
    / "app"
    / "src"
    / "main"
    / "java"
    / "dev"
    / "ipf"
    / "whitenoise"
    / "android"
    / "updates"
    / "AppUpdateModels.kt"
)
METADATA = ROOT / "fastlane" / "metadata" / "android" / "en-US"


def fail(message: str) -> None:
    print(f"error: {message}", file=sys.stderr)
    raise SystemExit(1)


def release_properties() -> dict[str, str]:
    result: dict[str, str] = {}
    for line in PROPERTIES.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        key, separator, value = stripped.partition("=")
        if not separator or not key or not value:
            fail(f"invalid release property: {line!r}")
        result[key] = value
    return result


def unique_match(pattern: str, content: str, label: str) -> str:
    matches = re.findall(pattern, content, flags=re.MULTILINE)
    if len(matches) != 1:
        fail(f"expected exactly one {label}, found {len(matches)}")
    return matches[0]


def png_info(image: Path) -> tuple[int, int, int, int]:
    payload = image.read_bytes()
    if payload[:8] != b"\x89PNG\r\n\x1a\n" or payload[12:16] != b"IHDR":
        fail(f"not a PNG: {image.relative_to(ROOT)}")
    width, height, bit_depth, color_type = struct.unpack(">IIBB", payload[16:26])
    return width, height, bit_depth, color_type


def require_png(
    image: Path,
    *,
    dimensions: tuple[int, int],
    color_type: int,
) -> None:
    if not image.is_file():
        fail(f"missing store asset: {image.relative_to(ROOT)}")
    width, height, bit_depth, actual_color_type = png_info(image)
    if (width, height) != dimensions:
        fail(
            f"{image.relative_to(ROOT)} is {width}x{height}; "
            f"expected {dimensions[0]}x{dimensions[1]}"
        )
    if bit_depth != 8 or actual_color_type != color_type:
        fail(
            f"{image.relative_to(ROOT)} has PNG bit depth/color type "
            f"{bit_depth}/{actual_color_type}; expected 8/{color_type}"
        )


def main() -> None:
    properties = release_properties()
    required_properties = {
        "APPLICATION_ID",
        "ZAPSTORE_APP_ID",
        "ZAPSTORE_PUBLISHER_PUBKEY",
        "APP_SIGNING_SHA256",
        "PLAY_UPLOAD_SHA256",
        "ZSP_VERSION",
        "ZSP_DARWIN_ARM64_SHA256",
        "ZSP_LINUX_AMD64_SHA256",
        "ZSP_LINUX_ARM64_SHA256",
    }
    missing = sorted(required_properties - properties.keys())
    if missing:
        fail(f"missing release properties: {', '.join(missing)}")

    for key in (
        "ZAPSTORE_PUBLISHER_PUBKEY",
        "APP_SIGNING_SHA256",
        "PLAY_UPLOAD_SHA256",
        "ZSP_DARWIN_ARM64_SHA256",
        "ZSP_LINUX_AMD64_SHA256",
        "ZSP_LINUX_ARM64_SHA256",
    ):
        if not re.fullmatch(r"[0-9a-f]{64}", properties[key]):
            fail(f"{key} must be a lowercase 64-character hex digest")
    if properties["APP_SIGNING_SHA256"] == properties["PLAY_UPLOAD_SHA256"]:
        fail("the Play upload key must remain distinct from the app-signing key")
    if not re.fullmatch(r"\d+\.\d+\.\d+", properties["ZSP_VERSION"]):
        fail("ZSP_VERSION must be a pinned semantic version without a leading v")

    gradle = GRADLE.read_text(encoding="utf-8")
    application_id = unique_match(
        r'^\s*applicationId\s*=\s*"([^"]+)"\s*$', gradle, "applicationId"
    )
    version_code = int(
        unique_match(r"^\s*versionCode\s*=\s*(\d+)\s*$", gradle, "versionCode")
    )
    version_name = unique_match(
        r'^\s*versionName\s*=\s*"([^"]+)"\s*$', gradle, "versionName"
    )
    if application_id != properties["APPLICATION_ID"]:
        fail("Gradle applicationId differs from config/android-release.properties")
    if not re.fullmatch(r"20\d{2}\.\d{1,2}\.\d{1,2}", version_name):
        fail("production versionName must use YYYY.M.D calendar versioning")

    update_models = UPDATE_MODELS.read_text(encoding="utf-8")
    zapstore_app_id = unique_match(
        r'WHITENOISE_ZAPSTORE_APP_ID\s*=\s*"([^"]+)"',
        update_models,
        "WHITENOISE_ZAPSTORE_APP_ID",
    )
    if zapstore_app_id != properties["ZAPSTORE_APP_ID"]:
        fail("the in-app updater and release config use different Zapstore app IDs")
    if properties["ZAPSTORE_APP_ID"] != application_id:
        fail("Zapstore and Play must use the same production application ID")

    zapstore_yaml = (ROOT / "zapstore.yaml").read_text(encoding="utf-8")
    if "./build/production-release/*.apk" not in zapstore_yaml:
        fail("zapstore.yaml must publish the verified production-release APK")
    for relative_asset in re.findall(
        r"^\s*-\s+(\./fastlane/[^\s]+\.png)\s*$",
        zapstore_yaml,
        re.MULTILINE,
    ):
        if not (ROOT / relative_asset.removeprefix("./")).is_file():
            fail(f"zapstore.yaml references missing asset {relative_asset}")

    title = (METADATA / "title.txt").read_text(encoding="utf-8").strip()
    summary = (METADATA / "short_description.txt").read_text(encoding="utf-8").strip()
    description = (METADATA / "full_description.txt").read_text(encoding="utf-8").strip()
    if not title or len(title) > 30:
        fail("store title must contain 1-30 characters")
    if not summary or len(summary) > 80:
        fail("short description must contain 1-80 characters")
    if not description or len(description) > 4000:
        fail("full description must contain 1-4000 characters")
    if "https://www.whitenoise.chat/privacy" not in description:
        fail("full description must link the canonical privacy policy")

    release_notes = METADATA / "changelogs" / f"{version_code}.txt"
    if not release_notes.is_file() or not release_notes.read_text(encoding="utf-8").strip():
        fail(f"missing release notes for versionCode {version_code}")

    images = METADATA / "images"
    require_png(images / "icon.png", dimensions=(512, 512), color_type=6)
    require_png(images / "featureGraphic.png", dimensions=(1024, 500), color_type=2)
    screenshots = sorted((images / "phoneScreenshots").glob("*.png"))
    if len(screenshots) < 4 or len(screenshots) > 8:
        fail("Play phone listing requires 4-8 curated screenshots")
    for screenshot in screenshots:
        require_png(screenshot, dimensions=(1080, 1920), color_type=2)

    print(
        f"Release metadata OK: {application_id} {version_name} "
        f"(versionCode {version_code}), {len(screenshots)} screenshots, "
        f"ZSP v{properties['ZSP_VERSION']}"
    )


if __name__ == "__main__":
    main()
