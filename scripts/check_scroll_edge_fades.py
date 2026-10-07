#!/usr/bin/env python3
"""Check the reviewed app-owned viewport inventory, including aliased and qualified calls."""
from __future__ import annotations
import json
import re
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = 'app/src/main/java/dev/ipf/whitenoise/android/ui'
INVENTORY = 'docs/scroll-edge-fade-surfaces.json'
METHODS = {
    'verticalScroll', 'LazyColumn', 'LazyVerticalGrid', 'fadingVerticalScroll',
    'WhiteNoiseLazyColumn', 'WhiteNoiseLazyVerticalGrid', 'whiteNoiseVerticalScroll', 'SettingsList', 'DropdownMenu',
}
RAW_ALLOWED = {
    ('common/WhiteNoiseScrollContainers.kt', 'verticalScroll'),
    ('common/WhiteNoiseScrollContainers.kt', 'LazyColumn'),
    ('common/WhiteNoiseScrollContainers.kt', 'LazyVerticalGrid'),
    ('chats/ChatsScreen.kt', 'LazyColumn'),
    ('conversation/ConversationScreen.kt', 'LazyColumn'),
    ('conversation/composer/ComposerPills.kt', 'verticalScroll'),
    # Retain the tested IME form until its offscreen fade survives the real-keyboard regression.
    ('conversation/messages/ReportMessageSheet.kt', 'verticalScroll'),
}
RAW = {'verticalScroll', 'LazyColumn', 'LazyVerticalGrid'}


def code_only(text: str) -> str:
    pattern = r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|//[^\n]*|/\*[\s\S]*?\*/'
    return re.sub(pattern, lambda m: re.sub(r'[^\n]', ' ', m.group()), text)


def calls(text: str) -> Counter:
    code = code_only(text)
    aliases = {}
    for match in re.finditer(r'^[ \t]*import\s+([\w.]+)(?:\s+as\s+(\w+))?', code, re.M):
        method = match[1].split('.')[-1]
        if method in METHODS:
            aliases[match[2] or method] = method
    names = METHODS | aliases.keys()
    pattern = r'\b((?:\w+\.)*(' + '|'.join(sorted(names)) + r'))\s*[({]'
    result = Counter()
    for match in re.finditer(pattern, code):
        prefix = code[code.rfind('\n', 0, match.start()) + 1:match.start()]
        if re.search(r'\bfun\s*$', prefix):
            continue
        result[aliases.get(match[2], match[2])] += 1
    return result


def inventory_errors(root: Path = ROOT) -> list[str]:
    document = json.loads((root / INVENTORY).read_text())
    entries = {entry['source']: entry for entry in document['surfaces']}
    errors = []
    observed = set()
    for path in sorted((root / SOURCE).rglob('*.kt')):
        text = path.read_text()
        found = calls(text)
        if not found:
            continue
        relative = path.relative_to(root).as_posix()
        observed.add(relative)
        entry = entries.get(relative)
        if entry is None or dict(found) != entry['calls']:
            errors.append(f'{relative}: viewport inventory differs: {dict(found)}')
        if entry and entry.get('family') not in document['families']:
            errors.append(f'{relative}: unknown reviewed screen family')
        if entry and not all(entry.get(key) for key in ['family', 'policy', 'test_ids']):
            errors.append(f'{relative}: incomplete viewport/state/evidence disposition')
        for method in RAW & found.keys():
            short = path.relative_to(root / SOURCE).as_posix()
            if (short, method) not in RAW_ALLOWED:
                errors.append(f'{relative}: use the shared fade container for {method}')
        if 'DropdownMenu' in found and not ('scrollState = menuScrollState' in text and '.scrollEdgeFade(menuScrollState)' in text):
            errors.append(f'{relative}: native menu must bind its mask to its actual scroll state')
    for stale in entries.keys() - observed:
        errors.append(f'{stale}: stale viewport inventory')
    return errors


if __name__ == '__main__':
    findings = inventory_errors()
    if findings:
        print('\n'.join(findings))
        raise SystemExit(1)
    print('Scroll edge inventory: all app-owned vertical viewports accounted for.')
