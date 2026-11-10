#!/usr/bin/env python3
"""Carry a Kover variant's compiled class directories from one CI job to another.

android-ci runs the unit suite in shards and computes coverage in a separate job.
That job merges every shard's binary report but does not recompile: the first
Zapstore shard packs the class directories its Kover artifact lists, and the
coverage job unpacks them at the same repository-relative paths.

* pack ARTIFACT TAR: read the output-directory section of a Kover artifact file
  (written by koverGenerateArtifact<Variant>) and archive those directories.
* unpack TAR: extract the archive into the current directory, refusing any member
  that would land outside it.
"""

import argparse
import sys
import tarfile
from pathlib import Path, PurePosixPath

# Kover writes the sources, output directories and binary reports as three
# blank-line-separated sections, one path per line.
OUTPUTS_SECTION = 1


def output_directories(artifact_text, root):
    """Return the artifact's existing output directories relative to root."""
    sections = artifact_text.split('\n\n')
    if len(sections) <= OUTPUTS_SECTION:
        raise ValueError('Kover artifact has no output-directory section')
    root = root.resolve()
    directories = []
    for line in sections[OUTPUTS_SECTION].splitlines():
        if not line.strip():
            continue
        path = Path(line.strip())
        absolute = (path if path.is_absolute() else root / path).resolve()
        try:
            relative = absolute.relative_to(root)
        except ValueError as error:
            raise ValueError(f'Kover output directory {line!r} is outside {root}') from error
        if absolute.is_dir():
            directories.append(relative)
    return directories


def pack(artifact, archive, root):
    directories = output_directories(artifact.read_text(encoding='utf-8'), root)
    if not directories:
        raise ValueError(f'{artifact} lists no existing class directories')
    with tarfile.open(archive, 'w') as tar:
        for directory in directories:
            tar.add(root / directory, arcname=directory.as_posix())
    for directory in directories:
        print(f'packed {directory.as_posix()}')


def unpack(archive, root):
    with tarfile.open(archive) as tar:
        members = tar.getmembers()
        for member in members:
            name = PurePosixPath(member.name)
            if name.is_absolute() or '..' in name.parts or not (member.isdir() or member.isfile()):
                raise ValueError(f'unsafe archive member {member.name!r}')
        tar.extractall(root, members=members, filter='data')
    print(f'unpacked {len(members)} entries')


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    commands = parser.add_subparsers(dest='command', required=True)
    pack_parser = commands.add_parser('pack')
    pack_parser.add_argument('artifact', type=Path)
    pack_parser.add_argument('archive', type=Path)
    unpack_parser = commands.add_parser('unpack')
    unpack_parser.add_argument('archive', type=Path)
    args = parser.parse_args(argv)
    root = Path.cwd()
    try:
        if args.command == 'pack':
            pack(args.artifact, args.archive, root)
        else:
            unpack(args.archive, root)
    except (OSError, ValueError, tarfile.TarError) as error:
        print(f'error: {error}', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
