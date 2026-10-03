"""Require literal PR triggers for every production parser copied into :fuzz.

This deliberately does not approximate GitHub's glob matcher. Extra trigger paths
are allowed, but exclusions and unsupported configuration forms fail closed.
"""
from pathlib import Path
import re
import sys


class TriggerPolicyError(ValueError):
    pass


def production_sources(build: str) -> list[str]:
    matches = re.findall(
        r'val\s+fuzzProductionIncludes\s*=\s*listOf\((.*?)\n\s*\)', build, re.DOTALL,
    )
    if len(matches) != 1:
        raise TriggerPolicyError('expected exactly one literal fuzzProductionIncludes list')
    body = re.sub(r'//[^\n]*', '', matches[0])
    entries = re.findall(r'"([^"\n]+)"', body)
    remainder = re.sub(r'"[^"\n]+"', '', body)
    if re.sub(r'[\s,]', '', remainder) or not entries or len(entries) != len(set(entries)):
        raise TriggerPolicyError('production allow-list must contain unique literal source paths')
    for entry in entries:
        if not re.fullmatch(r'dev/ipf/whitenoise/android/[A-Za-z0-9_/]+\.kt', entry):
            raise TriggerPolicyError(f'unsupported production source path: {entry}')
    return ['app/src/main/java/' + entry for entry in entries]


def mapping_block(text: str, name: str, indent: int) -> str:
    lines = text.splitlines()
    header = re.compile(rf'^ {{{indent}}}(?:{name}|\'{name}\'|"{name}"):\s*(?:#.*)?$')
    starts = [index for index, line in enumerate(lines) if header.fullmatch(line)]
    if len(starts) != 1:
        raise TriggerPolicyError(f'expected exactly one {name} mapping at indentation {indent}')
    selected = []
    for line in lines[starts[0] + 1:]:
        stripped = line.lstrip()
        if not stripped or stripped.startswith('#'):
            continue
        if '\t' in line or len(line) - len(stripped) <= indent:
            break
        selected.append(line)
    return '\n'.join(selected)


def pr_paths(workflow: str) -> list[str]:
    events = mapping_block(workflow, 'on', 0)
    event = mapping_block(events, 'pull_request', 2)
    paths = mapping_block(event, 'paths', 4)
    entries = []
    for line in paths.splitlines():
        match = re.fullmatch(r' {6}-\s+(?:\'([^\']+)\'|"([^"]+)"|([^\s#]+))\s*(?:#.*)?', line)
        if match is None:
            raise TriggerPolicyError('unsupported PR paths list; use literal YAML string entries')
        entry = next(value for value in match.groups() if value is not None)
        if entry.startswith('!'):
            raise TriggerPolicyError('PR path exclusions could suppress a production parser trigger')
        entries.append(entry)
    if not entries:
        raise TriggerPolicyError('empty PR paths list')
    return entries


def validate(build: str, workflow: str) -> None:
    paths = set(pr_paths(workflow))
    missing = [source for source in production_sources(build) if source not in paths]
    if missing:
        raise TriggerPolicyError('missing literal production triggers: ' + ', '.join(missing))


def main() -> int:
    root = Path(__file__).resolve().parents[1]
    try:
        validate(
            (root / 'fuzz/build.gradle.kts').read_text(),
            (root / '.github/workflows/fuzz-pr.yml').read_text(),
        )
    except (OSError, TriggerPolicyError) as error:
        print(f'fuzz PR trigger policy failed: {error}', file=sys.stderr)
        return 1
    print('fuzz PR trigger policy passed')
    return 0


if __name__ == '__main__':
    sys.exit(main())
