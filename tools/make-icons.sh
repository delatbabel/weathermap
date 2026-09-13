#!/usr/bin/env bash
#
# Build every icon the application and its packages need, from one source image.
#
#   tools/make-icons.sh
#
# Source: dist/weathermap-logo.jpg, committed so the repository is self
# contained - regenerating an icon should not depend on a file in someone's
# home directory.
#
# The source is a square illustration on a white ground with a margin. Three
# things happen to it before it is an icon:
#
#   1. the outer white becomes transparent, by flood-filling from the corners
#      rather than replacing white globally - the clouds, houses and fence are
#      white too, and a global replace would punch holes through them;
#   2. it is trimmed to its content and re-padded square, because the source
#      margin would otherwise eat most of a 16-pixel icon;
#   3. it is written at every size each platform expects.
#
# Outputs are committed. Building the application needs none of this.
set -euo pipefail
cd "$(dirname "$0")/.."

SRC=dist/weathermap-logo.jpg
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

# 1 + 2: transparent surround, trimmed, square.
magick "$SRC" -alpha set -bordercolor white -border 1 \
    -fuzz 8% -fill none -floodfill +0+0 white -shave 1x1 \
    -trim +repage "$WORK/trim.png"
side=$(identify -format '%[fx:max(w,h)]' "$WORK/trim.png")
magick "$WORK/trim.png" -background none -gravity center \
    -extent "${side}x${side}" "$WORK/square.png"

# 3: the platform sizes.
magick "$WORK/square.png" -resize 512x512 dist/linux/weathermap.png
magick "$WORK/square.png" -define icon:auto-resize=256,128,64,48,32,16 \
    dist/windows/weathermap.ico

# Swing wants the window icon as a set of plain images; the toolkit picks the
# size it needs for the title bar, the task switcher and the dock.
mkdir -p src/main/resources/icons
for size in 16 32 48 64 128 256; do
    magick "$WORK/square.png" -resize "${size}x${size}" \
        "src/main/resources/icons/weathermap-${size}.png"
done

# macOS .icns: written directly, because neither ImageMagick here nor png2icns
# can produce one. The container is a magic, a length, then one typed chunk per
# size, and PNG payloads are legal for the modern types.
python3 - "$WORK/square.png" <<'PY'
import pathlib, struct, subprocess, sys, tempfile
source = sys.argv[1]
types = {b'icp4': 16, b'icp5': 32, b'ic07': 128, b'ic08': 256, b'ic09': 512}
chunks = b''
with tempfile.TemporaryDirectory() as tmp:
    for tag, size in types.items():
        out = f'{tmp}/{size}.png'
        subprocess.run(['magick', source, '-resize', f'{size}x{size}', out], check=True)
        data = pathlib.Path(out).read_bytes()
        chunks += tag + struct.pack('>I', len(data) + 8) + data
pathlib.Path('dist/macos/weathermap.icns').write_bytes(
    b'icns' + struct.pack('>I', len(chunks) + 8) + chunks)
PY

printf '  %-34s %s\n' \
    dist/linux/weathermap.png "$(identify -format '%wx%h' dist/linux/weathermap.png)" \
    dist/windows/weathermap.ico "$(identify -format '%wx%h' 'dist/windows/weathermap.ico[0]')" \
    dist/macos/weathermap.icns "$(du -h dist/macos/weathermap.icns | cut -f1)"
ls src/main/resources/icons/ | tr '\n' ' '; echo
