"""Fail when a change loosens the source file size ratchet baseline.

SourceFileSizeRatchetTest checks files against SourceFileSizeBaseline.kt, so it
cannot notice when the baseline itself grows. This compares the baseline with a
base revision and fails when an entry is added or a limit rises. Lowering and
deleting entries is allowed. See docs/code-structure.md.

The parser accepts only the declaration line, one literal `"path" to limit,`
entry per line inside a single mapOf(...) block, or an empty mapOf(), and nothing
after the block. It fails on anything else, so another syntax cannot slip a new
entry past it.
"""
import argparse
from pathlib import Path
import re
import subprocess
import sys

BASELINE = 'app/src/test/java/dev/ipf/whitenoise/android/SourceFileSizeBaseline.kt'
DECLARATION = 'internal val sourceFileSizeBaseline: Map<String, Int> ='
ENTRY = re.compile(r'\s*"([A-Za-z0-9_/]+\.kt)" to (\d+),?\s*')


class BaselineError(ValueError):
    pass


def parse(text: str) -> dict[str, int]:
    lines = text.splitlines()
    starts = [i for i, line in enumerate(lines) if line.strip() in ('mapOf(', 'mapOf()')]
    if len(starts) != 1 or text.count('mapOf') != 1:
        raise BaselineError('expected exactly one literal mapOf( block')
    start = starts[0]
    if start == 0 or lines[start - 1].strip() != DECLARATION:
        raise BaselineError(f'expected `{DECLARATION}` followed by `mapOf(`')
    entries: dict[str, int] = {}
    if lines[start].strip() == 'mapOf()':
        return _nothing_after(lines, start, entries)
    for index in range(start + 1, len(lines)):
        line = lines[index]
        if line.strip() == ')':
            return _nothing_after(lines, index, entries)
        if not line.strip():
            continue
        match = ENTRY.fullmatch(line)
        if not match:
            raise BaselineError(f'unsupported baseline line: {line.strip()}')
        path, limit = match.group(1), int(match.group(2))
        if path in entries:
            raise BaselineError(f'duplicate baseline entry: {path}')
        entries[path] = limit
    raise BaselineError('unterminated mapOf( block')


def _nothing_after(lines: list[str], end: int, entries: dict[str, int]) -> dict[str, int]:
    if any(rest.strip() for rest in lines[end + 1:]):
        raise BaselineError('unexpected code after the baseline mapOf( block')
    return entries


def loosened(base: dict[str, int], head: dict[str, int]) -> list[str]:
    problems = []
    for path, limit in sorted(head.items()):
        if path not in base:
            problems.append(f'added entry {path} ({limit})')
        elif limit > base[path]:
            problems.append(f'raised {path} from {base[path]} to {limit}')
    return problems


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', required=True, help='git revision to compare with')
    args = parser.parse_args(argv)
    subprocess.run(['git', 'rev-parse', '--verify', '--quiet', f'{args.base}^{{commit}}'], check=True, stdout=subprocess.DEVNULL)
    if subprocess.run(['git', 'cat-file', '-e', f'{args.base}:{BASELINE}'], stderr=subprocess.DEVNULL).returncode != 0:
        print(f'{BASELINE} is absent at {args.base}; nothing to compare (bootstrap).')
        return 0
    base = subprocess.run(['git', 'show', f'{args.base}:{BASELINE}'], check=True, capture_output=True, text=True).stdout
    problems = loosened(parse(base), parse(Path(BASELINE).read_text(encoding='utf-8')))
    for problem in problems:
        print(f'::error file={BASELINE}::{problem}. Move the new code into a topic file or split the file first.')
    return 1 if problems else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
