#!/usr/bin/env python3
"""Check exact retained upstream icon data and prohibit accidental stack reintroduction."""
import hashlib
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]


def main():
    provenance = json.loads((ROOT / 'docs/dependency-reduction/material-icons-provenance.json').read_text())
    folder = ROOT / 'app/src/main/generated/material-icons'
    actual = {str(path.relative_to(folder)): hashlib.sha256(path.read_bytes()).hexdigest()
              for path in sorted(folder.rglob('*.kt'))}
    if actual != provenance['retained_source_sha256']:
        raise ValueError('retained icon data changed; reconcile pinned upstream geometry and provenance')
    for source_set in ('main', 'test', 'androidTest'):
        for path in (ROOT / f'app/src/{source_set}/java').rglob('*.kt'):
            if re.search(r'^import androidx[.]compose[.]material[.]icons[.]', path.read_text(), re.M):
                raise ValueError(f'old icon import remains: {path.relative_to(ROOT)}')
    build = (ROOT / 'app/build.gradle.kts').read_text()
    if 'implementation(libs.androidx.compose.material.icons.extended)' in build:
        raise ValueError('extended icon runtime reintroduced')
    print(f'Exact pinned vector source verified: {len(actual) - 1} glyphs and one upstream helper')


if __name__ == '__main__':
    main()
