#!/usr/bin/env python3
"""Merge per-journey ART profile dumps into one committed profile file.

BaselineProfileGenerator writes one `BaselineProfileGenerator_<journey>-baseline-prof.txt`
or `BaselineProfileGenerator_<journey>-startup-prof.txt` per journey. When the AGP runner cannot copy them
into the source set (for example on a device with a second user), pull them
with `adb` and merge them here:

    python3 scripts/merge-baseline-profiles.py \
      app/src/main/generated/baselineProfiles/startup-prof.txt \
      BaselineProfileGenerator_generateStartup-startup-prof.txt \
      BaselineProfileGenerator_generateInboundShareStartup-startup-prof.txt

Each rule identity appears once, with the union of its H, S and P flags, in
class order with a class rule before its members.
"""

import argparse
import re
from pathlib import Path

RULE = re.compile(r"^([HSP]*)(L.*)$")


def merge_rules(lines):
    """Return merged `flags + identity` rules for the given profile lines."""
    flags = {}
    for line in lines:
        line = line.rstrip("\n")
        if not line:
            continue
        match = RULE.match(line)
        prefix, identity = (match.group(1), match.group(2)) if match else ("", line)
        flags.setdefault(identity, set()).update(prefix)
    return ["".join(flag for flag in "HSP" if flag in flags[identity]) + identity
            for identity in sorted(flags, key=sort_key)]


def sort_key(identity):
    """Order a class rule before its member rules, then members by signature."""
    cls, _, member = identity.partition("->")
    return (cls, 1 if member else 0, member)


def main():
    """Merge the input dumps into the output profile and print the rule count."""
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("output", type=Path)
    parser.add_argument("inputs", type=Path, nargs="+")
    args = parser.parse_args()
    lines = []
    for path in args.inputs:
        lines.extend(path.read_text(encoding="utf-8").splitlines())
    rules = merge_rules(lines)
    args.output.write_text("".join(rule + "\n" for rule in rules), encoding="utf-8")
    print(f"{args.output}: {len(rules)} rules")


if __name__ == "__main__":
    main()
