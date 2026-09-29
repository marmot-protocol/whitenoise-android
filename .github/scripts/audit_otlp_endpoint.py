#!/usr/bin/env python3
"""Validate an audit write URL before a release claims Android can use it."""

from __future__ import annotations

import ipaddress
import re
import sys
from urllib.parse import urlsplit

DNS_LABEL = re.compile(r"[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\Z")


def android_usable_host(host: str) -> bool:
    """Accept DNS labels or numeric IPs that Java URI can parse as server hosts."""
    if ":" in host:
        try:
            ipaddress.IPv6Address(host)
        except ValueError:
            return False
        return True
    host = host.removesuffix(".")
    labels = host.split(".")
    if len(labels) == 4 and all(label.isdecimal() for label in labels):
        try:
            ipaddress.IPv4Address(host)
        except ValueError:
            return False
        return True
    return all(DNS_LABEL.fullmatch(label) for label in labels)


def android_usable_audit_otlp_endpoint(value: str) -> bool:
    """Mirror the Android sender's HTTPS /v1/logs host and URL-part requirements."""
    endpoint = value.strip()
    try:
        parsed = urlsplit(endpoint)
        parsed.port  # Access rejects malformed or out-of-range ports.
        host = parsed.hostname
        return (
            parsed.scheme == "https"
            and parsed.path == "/v1/logs"
            and "?" not in endpoint
            and "#" not in endpoint
            and parsed.username is None
            and not any(character.isspace() for character in endpoint)
            and host is not None
            and android_usable_host(host)
        )
    except ValueError:
        return False


def main() -> int:
    """Return a status code for the production release preflight."""
    if len(sys.argv) != 2:
        print("usage: audit_otlp_endpoint.py URL", file=sys.stderr)
        return 2
    return 0 if android_usable_audit_otlp_endpoint(sys.argv[1]) else 1


if __name__ == "__main__":
    raise SystemExit(main())
