#!/usr/bin/env bash

set -euo pipefail

repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
image_dir="$repo_dir/fastlane/metadata/android/en-US/images"

if ! command -v magick >/dev/null 2>&1; then
  echo "error: ImageMagick 7 ('magick') is required to generate store assets" >&2
  exit 1
fi

mkdir -p "$image_dir"

# Google Play requires a 512 px 32-bit PNG icon. Composite both adaptive layers,
# then crop the 108dp canvas to its central 72dp viewport (288 of 432 px), removing
# launcher bleed before scaling. Store surfaces apply their own outer mask.
magick \
  "$repo_dir/app/src/main/res/mipmap-xxxhdpi/ic_launcher_background.png" \
  "$repo_dir/app/src/main/res/mipmap-xxxhdpi/ic_launcher_foreground.png" \
  -composite -gravity center -crop 288x288+0+0 +repage -filter Lanczos -resize 512x512 \
  PNG32:"$image_dir/icon.png"

# Play requires a 1024x500 opaque feature graphic.
temporary_feature="$(mktemp -t whitenoise-feature.XXXXXX.png)"
trap 'rm -f "$temporary_feature"' EXIT
magick \
  -background none "$repo_dir/store-assets/feature-graphic.svg" \
  -alpha off -resize 1024x500! \
  PNG24:"$temporary_feature"
magick "$temporary_feature" \
  \( "$repo_dir/app/src/main/res/mipmap-xxxhdpi/ic_launcher_foreground.png" \
     -trim +repage -filter Lanczos -resize 180x180 \) \
  -geometry +115+180 -composite -alpha off \
  PNG24:"$image_dir/featureGraphic.png"

# Phone screenshots are curated source assets. Never overwrite or regenerate them.
echo "Generated icon and feature graphic in $image_dir; curated screenshots preserved"
