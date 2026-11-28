#!/usr/bin/env python3
"""Makes every copy of inicia's icon from web/brand/inicia.svg.

    tools/make-icons.py

Run it after changing the SVG, and commit what it writes:
  web/brand/favicon.ico, web/brand/apple-touch-icon.png    the site (with the SVG itself)
  src/main/resources/dev/inicia/windows/inicia.ico           the window's title bar and taskbar
  src/main/windows/inicia.res                                linked into inicia.exe: Explorer's icon

It needs Google Chrome or Chromium, which draws the SVG at each size (ImageMagick's own SVG
renderer drops strokes), and ImageMagick to pack the sizes into an .ico. The .res is written here,
not by Windows' rc.exe, so a Linux machine can make it: it's the icon's images as RT_ICON
resources and an RT_GROUP_ICON that lists them, which link.exe takes as it is.
"""
import pathlib
import shutil
import struct
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
SVG = ROOT / "web/brand/inicia.svg"
ICO_SIZES = [16, 20, 24, 32, 40, 48, 64, 256]


def chrome():
    for name in ("google-chrome", "chromium", "chromium-browser", "chrome"):
        path = shutil.which(name)
        if path:
            return path
    sys.exit("make-icons: needs Google Chrome or Chromium on the PATH")


def render(tmp, size):
    page = tmp / f"r{size}.html"
    page.write_text(f'<html><body style="margin:0;background:transparent">'
                    f'<img src="{SVG.as_uri()}" width="{size}" height="{size}" style="display:block"></body></html>')
    out = tmp / f"i{size}.png"
    subprocess.run([chrome(), "--headless=new", "--disable-gpu", "--no-sandbox", "--hide-scrollbars",
                    "--force-device-scale-factor=1", "--default-background-color=00000000",
                    f"--window-size={size},{size}", f"--screenshot={out}", page.as_uri()],
                   check=True, capture_output=True, timeout=120)
    return out


def ico(pngs, target):
    # 256 stays PNG inside the .ico (Windows' own layout); the small sizes become 32-bit bitmaps
    subprocess.run(["magick", *map(str, pngs), str(target)], check=True)


def res_entry(type_id, name_id, data, flags, lang=0x0409):
    header = struct.pack("<IIHHHHIHHII", len(data), 32, 0xFFFF, type_id, 0xFFFF, name_id, 0, flags, lang, 0, 0)
    pad = b"\0" * ((4 - len(data) % 4) % 4)
    return header + data + pad


def res_from_ico(ico_bytes):
    reserved, kind, count = struct.unpack_from("<HHH", ico_bytes, 0)
    assert reserved == 0 and kind == 1, "not an .ico file"
    out = bytearray(struct.pack("<IIHHHHIHHII", 0, 32, 0xFFFF, 0, 0xFFFF, 0, 0, 0, 0, 0, 0))
    group = bytearray(struct.pack("<HHH", 0, 1, count))
    for i in range(count):
        w, h, colors, res, planes, bits, size, offset = struct.unpack_from("<BBBBHHII", ico_bytes, 6 + 16 * i)
        out += res_entry(3, i + 1, ico_bytes[offset:offset + size], 0x1010)           # RT_ICON
        group += struct.pack("<BBBBHHIH", w, h, colors, res, planes, bits, size, i + 1)
    out += res_entry(14, 1, bytes(group), 0x1030)                                     # RT_GROUP_ICON 1
    return bytes(out)


def main():
    with tempfile.TemporaryDirectory() as t:
        tmp = pathlib.Path(t)
        pngs = {s: render(tmp, s) for s in sorted(set(ICO_SIZES + [180]))}

        app_ico = ROOT / "src/main/resources/dev/inicia/windows/inicia.ico"
        ico([pngs[s] for s in ICO_SIZES], app_ico)
        res = ROOT / "src/main/windows/inicia.res"
        res.parent.mkdir(parents=True, exist_ok=True)
        res.write_bytes(res_from_ico(app_ico.read_bytes()))

        ico([pngs[s] for s in (16, 32, 48)], ROOT / "web/brand/favicon.ico")
        shutil.copy(pngs[180], ROOT / "web/brand/apple-touch-icon.png")
    for f in (app_ico, res, ROOT / "web/brand/favicon.ico", ROOT / "web/brand/apple-touch-icon.png"):
        print(f"{f.relative_to(ROOT)}  {f.stat().st_size} bytes")


if __name__ == "__main__":
    main()
