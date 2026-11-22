"""Validate the Android release's offline user documentation."""
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GUIDES = ROOT / "app/src/main/assets/user-guides"


def main():
    guides = sorted(GUIDES.glob("*.md"))
    if not guides:
        raise SystemExit("No Android user guides found")
    for guide in guides:
        text = guide.read_text(encoding="utf-8")
        if not re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*", guide.stem):
            raise SystemExit(f"{guide.name}: expected a stable lowercase article ID")
        if not text.startswith("# ") or not text.partition("\n")[2].strip():
            raise SystemExit(f"{guide.name}: expected a title and article body")
        if re.search(r"<(?:img|figure|video)\b|!\[", text, re.IGNORECASE):
            raise SystemExit(f"{guide.name}: use written instructions instead of screenshots")
    print(f"Validated {len(guides)} Android user guides")


if __name__ == "__main__":
    main()
