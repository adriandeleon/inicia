#!/usr/bin/env bash
# Assembles the picker site in target/web: the page, the built-in catalog, and inicia.exe.
#   web/assemble.sh [path/to/inicia.exe]      (default: target/inicia.exe, if it's there)
# Upload target/web to any static host. The page serves the one exe under a name per selection
# (the download attribute), so no server code is needed; it must be on the same origin as the page.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
out="$root/target/web"
exe="${1:-$root/target/inicia.exe}"

rm -rf "$out"
mkdir -p "$out"
cp "$root/web/index.html" "$root/web/picker.js" "$root/web/picker.css" "$out/"
cp "$root/src/main/resources/dev/inicia/catalog.json" "$out/catalog.json"
if [[ -f "$exe" ]]; then
  cp "$exe" "$out/inicia.exe"
  echo "assembled $out with $(basename "$exe")"
else
  echo "assembled $out without inicia.exe (none at $exe): the download link won't work yet" >&2
fi
