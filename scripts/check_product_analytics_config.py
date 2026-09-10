#!/usr/bin/env python3
"""Check resolved release BuildConfig without logging destinations, keys, or policy values."""

import argparse
import json
import re
from pathlib import Path
from urllib.parse import urlsplit

FIELDS = ("EVENTS_ENDPOINT", "APP_KEY", "OPERATOR", "RETENTION")


def read_config(source: str) -> dict[str, str]:
    """Decode Java string literals emitted by the Gradle BuildConfig generator."""
    values = {}
    for suffix in FIELDS:
        match = re.search(r'WHITENOISE_PRODUCT_' + suffix + r'\s*=\s*("(?:[^"\\]|\\.)*")\s*;', source)
        if match:
            values[suffix] = json.loads(match[1])
    return values


def validate(values: dict[str, str]) -> list[str]:
    """Reject missing or malformed configuration; ingestion and deployed policy need separate checks."""
    errors = [field for field in FIELDS if not values.get(field, "").strip()]
    endpoint = values.get("EVENTS_ENDPOINT", "")
    try:
        url = urlsplit(endpoint)
        valid = (
            url.scheme == "https" and url.hostname and url.path == "/api/v0/events"
            and not url.username and not url.password and not url.query and not url.fragment
            and (url.port is None or 0 < url.port <= 65535)
            and not any(c.isspace() for c in endpoint)
        )
    except ValueError:
        valid = False
    if not valid and "EVENTS_ENDPOINT" not in errors:
        errors.append("EVENTS_ENDPOINT")
    key = values.get("APP_KEY", "")
    if (len(key) > 128 or not re.fullmatch(r"A-SH-[A-Za-z0-9-]+", key)) and "APP_KEY" not in errors:
        errors.append("APP_KEY")
    if not re.fullmatch(r"[a-z0-9_]{1,80}", values.get("OPERATOR", "")) and "OPERATOR" not in errors:
        errors.append("OPERATOR")
    return errors


def main() -> int:
    """Validate resolved build output and expose only failing field names."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("build_config", type=Path)
    args = parser.parse_args()
    try:
        errors = validate(read_config(args.build_config.read_text()))
    except (OSError, ValueError):
        print("Product analytics check failed: cannot read resolved BuildConfig")
        return 1
    if errors:
        print("Product analytics check failed: " + ", ".join("WHITENOISE_PRODUCT_" + field for field in errors))
        return 1
    print("Product analytics release configuration passed (ingestion and deployed retention require operator verification)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
