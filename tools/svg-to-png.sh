#!/usr/bin/env bash
#
# Rasterise the documentation diagrams for the in-application Help window.
#
# Swing's JEditorPane renders HTML 3.2 and cannot draw SVG, so the Help window
# needs raster copies. They are generated here rather than at build time and
# committed like the bundled gazetteer, so building the application needs no
# browser and no network.
#
# Headless Chrome rather than ImageMagick or Inkscape: the exported SVGs carry
# their colours in CSS variables, which Inkscape ignores - it renders them as
# grey shapes on white, which looks like a rendering bug rather than a missing
# feature.
#
# Output goes to docs/diagrams/png so one relative path works in both places:
# GitLab resolves it in the repository, and the jar resolves it under help/.
#
#   tools/svg-to-png.sh
#
# Re-run after re-exporting a diagram; the output is deterministic.
set -euo pipefail
cd "$(dirname "$0")/.."

CHROME=$(command -v google-chrome || command -v chromium || command -v chromium-browser)
OUT=docs/diagrams/png
mkdir -p "$OUT"

# Sized for the Help window rather than for print. JEditorPane draws an image at
# its natural size and offers a horizontal scrollbar when it does not fit, so a
# 2x render of a 1069-wide diagram shows the reader its blank left margin and
# nothing else. MAX_WIDTH is a little under the window's content width.
MAX_WIDTH=880

shopt -s nullglob
for svg in docs/architecture/svg/*.svg docs/diagrams/*.svg; do
    name=$(basename "$svg" .svg)
    read -r w h < <(grep -o 'viewBox="0 0 [0-9.]* [0-9.]*"' "$svg" \
        | head -1 | sed 's/viewBox="0 0 //;s/"//')
    scale=$(awk -v w="${w%.*}" -v m="$MAX_WIDTH" 'BEGIN{printf "%.4f", (w>m)?m/w:1}')
    "$CHROME" --headless --disable-gpu --no-sandbox \
        --default-background-color=FFFFFFFF \
        --window-size="${w%.*}","${h%.*}" \
        --force-device-scale-factor="$scale" \
        --screenshot="$OUT/$name.png" "file://$PWD/$svg" >/dev/null 2>&1
    printf '  %-22s %-6s %s\n' "$name.png" \
        "$(identify -format '%wx%h' "$OUT/$name.png" 2>/dev/null)" \
        "$(du -h "$OUT/$name.png" | cut -f1)"
done
