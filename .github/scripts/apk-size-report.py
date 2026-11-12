#!/usr/bin/env python3
"""Report where an APK's bytes go, per component, as JSON and Markdown.

Reads the APK as a ZIP only; it never installs, signs or changes the file. Each row
gives the bytes the component occupies inside the APK (after ZIP compression) and
its uncompressed size. The rows add up to the APK file size: whatever is not an
entry's stored data (local headers, alignment padding, the signing block and the
central directory) is reported as ZIP overhead.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys
import zipfile

SCHEMA = 1
MIB = 1024 * 1024


def component(name: str) -> tuple[str, str]:
    """Return (kind, label) for one ZIP entry name."""
    if name.startswith("lib/") and name.endswith(".so"):
        return "native", name
    if name.startswith("classes") and name.endswith(".dex") and "/" not in name:
        return "dex", name
    if name == "resources.arsc":
        return "resources", name
    if name.startswith("res/"):
        return "resources", "res/"
    if name.startswith("assets/"):
        return "assets", "assets/"
    if name.startswith("META-INF/"):
        return "metadata", "META-INF/"
    if name == "AndroidManifest.xml":
        return "metadata", name
    return "other", "other"


def measure(apk: Path, top: int) -> dict:
    if apk.is_symlink() or not apk.is_file():
        raise ValueError(f"APK must be a regular file: {apk}")
    file_bytes = apk.stat().st_size
    rows: dict[str, dict] = {}
    assets: list[dict] = []
    with zipfile.ZipFile(apk) as archive:
        for entry in archive.infolist():
            if entry.is_dir():
                continue
            kind, label = component(entry.filename)
            row = rows.setdefault(label, {"component": label, "kind": kind, "entries": 0,
                                          "apk_bytes": 0, "uncompressed_bytes": 0, "stored_entries": 0})
            row["entries"] += 1
            row["apk_bytes"] += entry.compress_size
            row["uncompressed_bytes"] += entry.file_size
            if entry.compress_type == zipfile.ZIP_STORED:
                row["stored_entries"] += 1
            if kind == "assets":
                assets.append({"path": entry.filename, "apk_bytes": entry.compress_size,
                               "uncompressed_bytes": entry.file_size})
    entry_bytes = sum(row["apk_bytes"] for row in rows.values())
    rows["ZIP overhead"] = {"component": "ZIP overhead", "kind": "overhead", "entries": 0,
                            "apk_bytes": file_bytes - entry_bytes, "uncompressed_bytes": 0,
                            "stored_entries": 0}
    components = sorted(rows.values(), key=lambda row: (-row["apk_bytes"], row["component"]))
    for row in components:
        row["share"] = round(row["apk_bytes"] / file_bytes, 4) if file_bytes else 0.0
    kinds: dict[str, int] = {}
    for row in components:
        kinds[row["kind"]] = kinds.get(row["kind"], 0) + row["apk_bytes"]
    assets.sort(key=lambda item: (-item["apk_bytes"], item["path"]))
    return {"apk": apk.name, "apk_bytes": file_bytes, "components": components,
            "kinds": dict(sorted(kinds.items(), key=lambda item: (-item[1], item[0]))),
            "largest_assets": assets[:top]}


def read_marmot_version(path: Path | None) -> dict:
    if path is None:
        return {}
    keys = {"artifact-id", "artifact-tag", "mdk-sha", "features"}
    values = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        key, separator, value = line.partition("=")
        if separator and key in keys:
            values[key] = value
    return values


def mib(value: int) -> str:
    return f"{value / MIB:.2f} MiB"


def markdown(report: dict) -> str:
    title = f"APK size report: {report['flavor']}"
    lines = [f"### {title}", "",
             f"`{report['apk']}` is **{mib(report['apk_bytes'])}** ({report['apk_bytes']:,} bytes)."]
    if report.get("commit"):
        lines.append(f"Commit `{report['commit']}`.")
    marmot = report.get("marmotkit") or {}
    if marmot:
        detail = ", ".join(f"{key} `{marmot[key]}`" for key in ("artifact-tag", "mdk-sha", "features") if key in marmot)
        lines.append(f"MarmotKit: {detail}.")
    lines += ["", "| Component | In APK | Share | Uncompressed | Entries |", "| --- | ---: | ---: | ---: | ---: |"]
    for row in report["components"]:
        stored = " (stored)" if row["entries"] and row["stored_entries"] == row["entries"] else ""
        lines.append(f"| `{row['component']}`{stored} | {mib(row['apk_bytes'])} | {row['share']:.1%} | "
                     f"{mib(row['uncompressed_bytes'])} | {row['entries']} |")
    if report["largest_assets"]:
        lines += ["", "Largest assets:", "", "| Asset | In APK | Uncompressed |", "| --- | ---: | ---: |"]
        for item in report["largest_assets"]:
            lines.append(f"| `{item['path']}` | {mib(item['apk_bytes'])} | {mib(item['uncompressed_bytes'])} |")
    return "\n".join(lines) + "\n"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--flavor", required=True)
    parser.add_argument("--commit")
    parser.add_argument("--marmot-version", type=Path, help="MARMOT_VERSION file naming the bundled native build")
    parser.add_argument("--top", type=int, default=10, help="Largest assets to list")
    parser.add_argument("--json", type=Path, help="Write the JSON report here")
    parser.add_argument("--markdown", type=Path, help="Write the Markdown table here")
    args = parser.parse_args(argv)
    try:
        report = {"schema": SCHEMA, "flavor": args.flavor, "commit": args.commit,
                  "marmotkit": read_marmot_version(args.marmot_version), **measure(args.apk, args.top)}
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        print(f"APK size report failed: {error}", file=sys.stderr)
        return 1
    text = markdown(report)
    if args.json:
        args.json.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    if args.markdown:
        args.markdown.write_text(text, encoding="utf-8")
    if not args.json and not args.markdown:
        sys.stdout.write(text)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
