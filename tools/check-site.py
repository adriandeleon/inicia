#!/usr/bin/env python3
"""Checks the website's data against the catalog, so a new app can't ship half-done.

    tools/check-site.py

- every catalog app has an icon in web/icons (an .svg or a .png)
- every icon belongs to an app in the catalog
- every app named in web/news.json is in the catalog, and every app in the catalog is in some
  news entry (so the News page tells about every app)
- news dates are YYYY-MM-DD
Exits 1 with every problem listed.
"""
import datetime
import json
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
catalog = json.loads((ROOT / "src/main/resources/dev/inicia/catalog.json").read_text())
news = json.loads((ROOT / "web/news.json").read_text())
ids = [a["id"] for a in catalog["apps"]]
icons = {p.stem for p in (ROOT / "web/icons").iterdir() if p.suffix in (".svg", ".png")}
problems = []

for app_id in ids:
    if app_id not in icons:
        problems.append(f"{app_id}: no icon in web/icons (web/fetch-icons.py {app_id}, then look at it)")
for icon in sorted(icons - set(ids)):
    problems.append(f"web/icons/{icon}: no app with that id")

mentioned = set()
for i, item in enumerate(news):
    try:
        datetime.date.fromisoformat(item["date"])
    except (KeyError, ValueError):
        problems.append(f"news.json[{i}]: date must be YYYY-MM-DD")
    for app_id in item.get("apps", []):
        mentioned.add(app_id)
        if app_id not in ids:
            problems.append(f"news.json[{i}]: {app_id} isn't in the catalog")
for app_id in ids:
    if app_id not in mentioned:
        problems.append(f"{app_id}: not in any news.json entry (add one saying it's new)")

for p in problems:
    print(p)
print(f"{len(ids)} apps, {len(icons)} icons, {len(news)} news entries: "
      + ("ok" if not problems else f"{len(problems)} problems"))
sys.exit(1 if problems else 0)
