"""Select installed Android command-line tools by package revision, never folder spelling."""
import pathlib
import re
import sys


def select_tools(sdk_root: pathlib.Path, minimum_major: int = 22) -> pathlib.Path:
    """Return a complete tools bin directory whose revision supports API 37 AVD metadata."""
    candidates = []
    for directory in (sdk_root / "cmdline-tools").glob("*"):
        properties = directory / "source.properties"
        if not properties.is_file():
            continue
        revision = re.search(r"^Pkg\.Revision\s*=\s*(\d+(?:\.\d+)*)\s*$", properties.read_text(), re.MULTILINE)
        bin_directory = directory / "bin"
        if revision and all((bin_directory / tool).is_file() for tool in ("sdkmanager", "avdmanager")):
            version = tuple(int(part) for part in revision.group(1).split("."))
            candidates.append((version, str(bin_directory)))
    if not candidates or max(candidates)[0][0] < minimum_major:
        raise ValueError(f"Android command-line tools {minimum_major}+ unavailable")
    return pathlib.Path(max(candidates)[1])


if __name__ == "__main__":
    print(select_tools(pathlib.Path(sys.argv[1])))
