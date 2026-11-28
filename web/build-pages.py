#!/usr/bin/env python3
"""Builds the site's generated pages into an assembled site folder (web/assemble.sh runs it):

    web/build-pages.py target/web

- apps.html: every app, every category and the whole catalog as plain download links, for
  browsers without JavaScript and for screen readers. Each link serves inicia.exe under the name
  that selects those apps (the download attribute needs no script).
- feed.xml: an Atom feed of news.json.
"""
import datetime
import html
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
out = pathlib.Path(sys.argv[1])
catalog = json.loads((ROOT / "src/main/resources/dev/inicia/catalog.json").read_text())["apps"]
news = json.loads((ROOT / "web/news.json").read_text())
e = html.escape


def slug(name):
    return re.sub(r"^-|-$", "", re.sub(r"[^a-z0-9]+", "-", name.lower()))


def link(name, label):
    return f'<a href="inicia.exe" download="inicia_{name}.exe">{e(label)}</a>'


categories = {}
for app in catalog:
    categories.setdefault(app.get("category", "Other"), []).append(app)

parts = []
for cat in sorted(categories):
    apps = sorted(categories[cat], key=lambda a: a["name"].lower())
    rows = []
    for a in apps:
        version = a.get("version", "always the latest")
        note = f'<br><span class="app-extra warn">{e(a["note"])}</span>' if a.get("note") else ""
        home = f' · <a href="{e(a["homepage"])}">website</a>' if a.get("homepage") else ""
        lic = a.get("license", "")
        lic = "" if not lic else f' ({e(lic if lic in ("Proprietary", "Subscription") else "open source, " + lic)})'
        rows.append(f'      <li><strong>{e(a["name"])}</strong> {e(version)}{lic}: {e(a.get("description", ""))}'
                    f'{note}<br>{link(a["id"], "Download the installer for " + a["name"])}{home}</li>')
    parts.append(f'    <h2 id="{slug(cat)}">{e(cat)}</h2>\n'
                 f'    <p>{link("cat-" + slug(cat), "Download one installer for all " + str(len(apps)) + " " + cat + " apps")}</p>\n'
                 f'    <ul class="plain-list">\n' + "\n".join(rows) + "\n    </ul>")

page = (ROOT / "web/security.html").read_text()
head, _, rest = page.partition('  <main class="page">')
_, _, footer = rest.partition("  </main>")
head = head.replace("<title>Security · inicia</title>", "<title>All apps · inicia</title>")
head = re.sub(r'<meta name="description" content="[^"]*">',
              '<meta name="description" content="Every app in the inicia catalog, as plain download links.">', head)
body = f'''  <main class="page">
    <div class="wrap prose">
      <h1>All apps</h1>
      <p class="lede">Every app in the catalog, as plain links: each downloads inicia's installer,
        named for the apps it installs. Run it, and keep its name. On the
        <a href="./">Apps page</a> you can pick any set of apps at once.</p>
      <p>{link("all", "Download one installer for all " + str(len(catalog)) + " apps")}</p>
{chr(10).join(parts)}
    </div>
  </main>'''
(out / "apps.html").write_text(head + body + footer)

# Atom: newest first; links relative to the feed, which readers resolve against its address
entries = []
for i, item in enumerate(sorted(news, key=lambda n: n["date"], reverse=True)):
    names = [a["name"] for a in catalog if a["id"] in item.get("apps", [])]
    summary = item.get("text", "") + (" Apps: " + ", ".join(names) + "." if names else "")
    entries.append(f'''  <entry>
    <id>tag:inicia,{item["date"][:4]}:news/{item["date"]}/{slug(item["title"])}</id>
    <title>{e(item["title"])}</title>
    <updated>{item["date"]}T00:00:00Z</updated>
    <link href="news.html"/>
    <summary>{e(summary)}</summary>
  </entry>''')
updated = max((n["date"] for n in news), default=datetime.date.today().isoformat())
(out / "feed.xml").write_text(f'''<?xml version="1.0" encoding="utf-8"?>
<feed xmlns="http://www.w3.org/2005/Atom">
  <id>tag:inicia,2026:news</id>
  <title>inicia news</title>
  <subtitle>New apps in the inicia catalog</subtitle>
  <updated>{updated}T00:00:00Z</updated>
  <link rel="alternate" href="news.html"/>
  <link rel="self" href="feed.xml"/>
  <author><name>inicia</name></author>
{chr(10).join(entries)}
</feed>
''')
print(f"built apps.html ({len(catalog)} apps) and feed.xml ({len(news)} entries)")
