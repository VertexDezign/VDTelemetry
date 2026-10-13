#!/usr/bin/env bash
# Assembles the GitHub Pages site into one folder (default: _site/ at the repo root, git-ignored).
#
# The single definition of what the site is, run by .github/workflows/pages.yml and locally alike:
#   site/build.sh && python3 -m http.server -d _site
#
# The screenshots are not copied into site/: they live once, in screenshots/, where the README uses
# them too. This resizes each into img/ (what the page shows; the originals are iPad-sized and
# 12 MB together) and copies the original into full/ (what a tap on one opens). Needs ImageMagick.
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
out=${1:-$root/_site}

im=$(command -v magick || command -v convert || true)
[ -n "$im" ] || { echo "ImageMagick (magick or convert) is required" >&2; exit 1; }

rm -rf "$out"
mkdir -p "$out/img" "$out/full"
cp -r "$root/site/." "$out/"
rm "$out/build.sh"

# screenshots/apps/map/layer_pfYield.png -> apps_map_layer_pfYield, the name the pages use.
while IFS= read -r -d '' f; do
  rel=${f#"$root/screenshots/"}
  name=$(printf '%s' "${rel%.png}" | tr / _)
  # Shrink only (`>`): the portrait cluster shot is already smaller than this.
  "$im" "$f" -resize '1400x>' -strip -quality 82 "$out/img/$name.jpg"
  cp "$f" "$out/full/$name.png"
done < <(find "$root/screenshots" -name '*.png' -print0)

"$im" "$root/vdTelemetry/icon_vdTelemetry.png" -resize 192x192 -strip "$out/icon.png"

# Every image the pages reference must exist -- a renamed screenshot is otherwise a silently broken
# picture on the live site.
missing=0
for page in "$out/index.html" "$out/de/index.html"; do
  while IFS= read -r ref; do
    [ -f "$out/$ref" ] || { echo "$page references missing $ref" >&2; missing=1; }
  done < <(grep -oE '(src|href)="(\.\./)?(img|full)/[^"]+"' "$page" | sed -E 's/^(src|href)="(\.\.\/)?//; s/"$//')
done
exit $missing
