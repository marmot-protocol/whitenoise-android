#!/usr/bin/env python3
"""Verify an APK's signatures and require the pinned certificate on every SDK range."""
import re
import subprocess
import sys


def verify_certificates(output, expected):
    if not re.fullmatch(r'[0-9a-f]{64}', expected):
        raise ValueError('Expected certificate must be a lowercase SHA-256 fingerprint')
    signer = r'(?:Signer #[1-9][0-9]*|Signer \(minSdkVersion=[0-9]+(?: \(dev release=true\))?, maxSdkVersion=[0-9]+\)|V[1-4](?:\.[0-9]+)? Signer:)'
    pattern = re.compile(signer + r' certificate SHA-256 digest: ([0-9a-fA-F]{64})')
    fingerprints = set()
    for line in output.splitlines():
        if 'Signer' in line and 'certificate SHA-256 digest:' in line and not line.startswith('Source Stamp Signer '):
            match = pattern.fullmatch(line)
            if not match:
                raise ValueError(f'Unsupported APK signer certificate output: {line}')
            fingerprints.add(match.group(1).lower())
    if not fingerprints:
        raise ValueError(f'No APK signer certificate fingerprints found in apksigner output:\n{output}')
    if fingerprints != {expected}:
        raise ValueError(f'APK signer certificate mismatch: expected {expected}, actual {sorted(fingerprints)}')
    return expected


def verify(apksigner, apk, expected):
    result = subprocess.run([apksigner, 'verify', '--verbose', '--print-certs', apk],
                            text=True, capture_output=True)
    if result.returncode:
        raise ValueError(f'APK signature verification failed:\n{result.stdout}{result.stderr}')
    return verify_certificates(result.stdout, expected)


if __name__ == '__main__':
    if len(sys.argv) != 4:
        sys.exit('Usage: verify_apk_signature.py <apksigner> <apk> <expected-sha256>')
    try:
        print(verify(*sys.argv[1:]))
    except (OSError, ValueError) as error:
        sys.exit(f'error: {error}')
