#!/usr/bin/env bash
# Assembles the site in target/web: the pages, the built-in catalog, the icons, and inicia.exe.
#   web/assemble.sh [path/to/inicia.exe]      (default: target/inicia.exe, if it's there)
# Upload target/web to any static host. The apps page serves the one exe under a name per
# selection (the download attribute), so no server code is needed; it must be on the same origin
# as the page.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
out="$root/target/web"
exe="${1:-$root/target/inicia.exe}"

rm -rf "$out"
mkdir -p "$out/icons"
cp "$root"/web/*.html "$root"/web/*.js "$root"/web/*.css "$root"/web/hero.png "$root"/web/news.json "$out/"
cp "$root/src/main/resources/dev/inicia/catalog.json" "$out/catalog.json"
cp "$root"/web/icons/*.svg "$root"/web/icons/*.png "$out/icons/" 2>/dev/null || true

# a stamp on the pages' own scripts and stylesheet, so a browser holding an older copy of the
# site never runs a new page with an old script or the other way round
stamp=$(cat "$out"/*.js "$out"/*.css "$out"/*.html | sha256sum | cut -c1-10)
sed -i -E "s#(src|href)=\"([a-z]+\.(js|css))\"#\1=\"\2?v=$stamp\"#g" "$out"/*.html

# icons/index.json: id → file, an SVG preferred over a PNG
python3 - "$out/icons" <<'EOF'
import json, pathlib, sys
d = pathlib.Path(sys.argv[1])
index = {}
for f in sorted(d.iterdir(), key=lambda p: p.suffix != ".svg"):
    if f.suffix in (".svg", ".png"):
        index.setdefault(f.stem, "icons/" + f.name)
(d / "index.json").write_text(json.dumps(index, indent=1, sort_keys=True) + "\n")
EOF

if [[ -f "$exe" ]]; then
  cp "$exe" "$out/inicia.exe"
  echo "assembled $out with $(basename "$exe")"
else
  echo "assembled $out without inicia.exe (none at $exe): the download link won't work yet" >&2
fi
