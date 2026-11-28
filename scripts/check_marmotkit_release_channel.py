"""Require an exact published Stable MarmotKit pin for production."""

import argparse
from pathlib import Path
import re
import sys

if __package__:
    from .prepare_marmotkit_artifact import PreparationError, parse_properties
else:
    from prepare_marmotkit_artifact import PreparationError, parse_properties


LOCK = Path("app/src/main/marmotkit/MARMOT_VERSION")
STABLE_VERSION = re.compile(r"(?:0|[1-9][0-9]*)(?:\.(?:0|[1-9][0-9]*)){2}")
RELEASE_ROOT = "https://github.com/marmot-protocol/mdk/releases/download"


def check_stable_pin(path: Path) -> dict[str, str]:
    """Return a pin with a formal version and matching tag, archive name, and upstream URL."""
    pin = parse_properties(path)
    version = pin["artifact-id"]
    if STABLE_VERSION.fullmatch(version) is None:
        raise PreparationError("artifact-id must be a final X.Y.Z version")
    tag = f"marmotkit-v{version}"
    archive_root = f"marmotkit-android-{version}"
    expected = {
        "artifact-tag": tag,
        "archive-root": archive_root,
        "artifact-url": f"{RELEASE_ROOT}/{tag}/{archive_root}.zip",
    }
    for key, value in expected.items():
        if pin[key] != value:
            raise PreparationError(f"{key} must match the published archive for {version}")
    return pin


def main() -> int:
    """Check the selected lock without downloading artifacts or using release credentials."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("lock", nargs="?", type=Path, default=LOCK)
    args = parser.parse_args()
    try:
        check_stable_pin(args.lock)
    except (OSError, PreparationError, UnicodeError) as error:
        print(f"Stable MarmotKit pin required: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
