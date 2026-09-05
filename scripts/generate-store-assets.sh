#!/usr/bin/env bash

set -euo pipefail

repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
image_dir="$repo_dir/fastlane/metadata/android/en-US/images"
screenshot_dir="$image_dir/phoneScreenshots"

if ! command -v magick >/dev/null 2>&1; then
  echo "error: ImageMagick 7 ('magick') is required to generate store assets" >&2
  exit 1
fi

mkdir -p "$screenshot_dir"

# Google Play requires a 512 px 32-bit PNG icon. Use the complete launcher
# artwork rather than one adaptive-icon layer so Zapstore and Play match the
# installed app.
magick \
  "$repo_dir/app/src/main/res/mipmap-xxxhdpi/ic_launcher_background.png" \
  "$repo_dir/app/src/main/res/mipmap-xxxhdpi/ic_launcher_foreground.png" \
  -composite -filter Lanczos -resize 512x512 \
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

generate_phone_screenshot() {
  local source_name="$1"
  local output_name="$2"
  local background="$3"
  local source_file="$repo_dir/app/src/test/snapshots/$source_name"

  if [[ ! -f "$source_file" ]]; then
    echo "error: missing deterministic screenshot baseline: $source_file" >&2
    exit 1
  fi

  # Preserve the app's aspect ratio and letterbox it into Google's recommended
  # 1080x1920 portrait canvas. These remain actual app renders with no invented
  # UI or device frame.
  magick "$source_file" \
    -filter Lanczos -resize 1080x1920 \
    -background "$background" -gravity center -extent 1080x1920 \
    -alpha off PNG24:"$screenshot_dir/$output_name"
}

generate_phone_screenshot onboarding_content_idle_light.png 01-onboarding.png '#eceeee'
generate_phone_screenshot conversation_route_terminal_light.png 02-conversation.png '#eceeee'
generate_phone_screenshot group_details_admin_full_light.png 03-group-details.png '#eceeee'
generate_phone_screenshot profile_sheet_other_full_light.png 04-profile.png '#eceeee'
generate_phone_screenshot chat_folders_screen_dark.png 05-folders.png '#0f1112'
generate_phone_screenshot settings_screen_default_dark.png 06-settings.png '#0f1112'

echo "Generated store assets in $image_dir"
