#!/usr/bin/env python3
"""Fetches an icon for each catalog app that has none in web/icons yet.

    web/fetch-icons.py            every app without web/icons/<id>.png
    web/fetch-icons.py chrome     just these ids (replacing what's there)

It reads the app's homepage, takes the icon the page declares (SVG first, then the largest
PNG: apple-touch-icon, icon, mask-icon), falls back to /apple-touch-icon.png and /favicon.ico,
and keeps an SVG as it is or writes a 64x64 PNG with ImageMagick. Look at the result: a site's icon isn't always the
app's. Icons it can't get right are added by hand, and kept, since only missing ones are fetched.
"""
import json
import pathlib
import re
import subprocess
import sys
import tempfile
import urllib.parse
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
CATALOG = ROOT / "src/main/resources/dev/inicia/catalog.json"
ICONS = ROOT / "web/icons"
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0 Safari/537.36"


def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=30) as res:
        return res.geturl(), res.read()


def candidates(homepage):
    final, html = get(homepage)
    text = html.decode("utf-8", "replace")
    found = []
    for tag in re.findall(r"<link\b[^>]*>", text, re.I):
        rel = re.search(r'rel=["\']([^"\']+)', tag, re.I)
        href = re.search(r'href=["\']([^"\']+)', tag, re.I)
        if not rel or not href or "icon" not in rel.group(1).lower():
            continue
        sizes = re.search(r'sizes=["\'](\d+)x\d+', tag, re.I)
        url = urllib.parse.urljoin(final, href.group(1).replace("&amp;", "&"))
        size = int(sizes.group(1)) if sizes else (180 if "apple" in rel.group(1).lower() else 32)
        score = 10_000 if url.lower().split("?")[0].endswith(".svg") and "mask" not in rel.group(1).lower() else size
        found.append((score, url))
    found.sort(reverse=True)
    root = "{0.scheme}://{0.netloc}".format(urllib.parse.urlparse(final))
    return [u for _, u in found] + [root + "/apple-touch-icon.png", root + "/favicon.ico"]


def convert(data, url, out):
    suffix = pathlib.Path(urllib.parse.urlparse(url).path).suffix or ".img"
    if suffix == ".svg":
        # browsers draw SVG icons as their makers meant; ImageMagick's renderer often doesn't
        out.with_suffix(".svg").write_bytes(data)
        out.unlink(missing_ok=True)
        return
    with tempfile.NamedTemporaryFile(suffix=suffix) as src:
        src.write(data)
        src.flush()
        # [0] would pick an .ico's first frame; -flatten + largest frame is better for .ico
        frame = src.name if suffix != ".ico" else src.name
        subprocess.run(["magick", "-background", "none", "-density", "384", frame,
                        "-thumbnail", "64x64", "-gravity", "center", "-extent", "64x64",
                        "-delete", "1--1", str(out)], check=True, capture_output=True)


def main(ids):
    apps = json.loads(CATALOG.read_text())["apps"]
    ICONS.mkdir(exist_ok=True)
    for app in apps:
        out = ICONS / (app["id"] + ".png")
        if (ids and app["id"] not in ids) or (not ids and (out.exists() or out.with_suffix(".svg").exists())):
            continue
        if "homepage" not in app:
            print(f"{app['id']}: no homepage")
            continue
        try:
            urls = candidates(app["homepage"])
        except Exception as e:
            print(f"{app['id']}: homepage failed: {e}")
            continue
        for url in urls:
            try:
                _, data = get(url)
                convert(data, url, out)
                print(f"{app['id']}: {url}")
                break
            except Exception:
                continue
        else:
            print(f"{app['id']}: no icon found")


if __name__ == "__main__":
    main(sys.argv[1:])
