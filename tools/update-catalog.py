#!/usr/bin/env python3
"""Finds new versions of the catalog's pinned apps and updates their entries.

    tools/update-catalog.py --check          what's newer, and whether every source still agrees
                                             with the catalog; changes nothing, downloads nothing
    tools/update-catalog.py --write          update catalog.json and add a news.json entry
        [--summary FILE] [--changed FILE]    a Markdown summary, and the updated ids one per line

Where to look is in tools/sources.json. For each app with a newer version, every installer the
catalog lists (one per architecture) is downloaded here and hashed: its SHA-256 must match the one
the source publishes (GitHub's asset digest, winget's manifest, Adoptium's API), and becomes the
catalog's, with the file's size. The version, the release date, the download address and, where
the source gives a template, the detect pattern are updated too. The signer, the installer type,
its arguments and everything else stay as they are; the update workflow then installs the updated
apps on Windows with inicia, which checks hash and signer for real.

Exits 1 when a source fails or disagrees with the catalog, so the workflow shows it.
"""
import argparse
import base64
import datetime
import hashlib
import json
import pathlib
import re
import subprocess
import sys
import urllib.request

import yaml

ROOT = pathlib.Path(__file__).resolve().parent.parent
CATALOG = ROOT / "src/main/resources/dev/inicia/catalog.json"
NEWS = ROOT / "web/news.json"
SOURCES = ROOT / "tools/sources.json"
UA = "inicia-catalog-updater"


# --- versions, compared the way inicia's Versions does --------------------------------------

def parts(v):
    out = []
    for p in re.split(r"[^A-Za-z0-9]+", v.strip()):
        out += [q for q in re.split(r"(?<=\d)(?=\D)|(?<=\D)(?=\d)", p) if q]
    while len(out) > 1 and out[-1] == "0":
        out.pop()
    return out


def compare(a, b):
    x, y = parts(a), parts(b)
    for i in range(max(len(x), len(y))):
        p = x[i] if i < len(x) else None
        q = y[i] if i < len(y) else None
        if (p is None and not (q or "").isdigit()) or (q is None and not (p or "").isdigit()):
            continue
        p, q = p or "0", q or "0"
        if p.isdigit() and q.isdigit():
            c = (int(p) > int(q)) - (int(p) < int(q))
        elif p.isdigit() != q.isdigit():
            c = 1 if p.isdigit() else -1
        else:
            c = (p.lower() > q.lower()) - (p.lower() < q.lower())
        if c:
            return c
    return 0


# --- sources: each returns (version, released date, {arch: (url, sha256 or None)}) ----------

def gh_api(path):
    r = subprocess.run(["gh", "api", path], capture_output=True, text=True)
    if r.returncode:
        raise RuntimeError(f"gh api {path}: {r.stderr.strip()}")
    return json.loads(r.stdout)


def from_github(src, arches):
    rel = gh_api(f"repos/{src['github']}/releases/latest")
    m = re.match(src["tag"], rel["tag_name"])
    if not m:
        raise RuntimeError(f"latest tag {rel['tag_name']} doesn't match {src['tag']}")
    files = {}
    for arch in arches:
        pattern = src["assets"].get(arch)
        found = [a for a in rel["assets"] if pattern and re.search(pattern, a["name"])]
        if len(found) != 1:
            raise RuntimeError(f"{arch}: {len(found)} assets match {pattern} in {rel['tag_name']}")
        digest = found[0].get("digest") or ""
        files[arch] = (found[0]["browser_download_url"], digest[7:] if digest.startswith("sha256:") else None)
    return m.group(1), rel["published_at"][:10], files


def winget_dir(pid):
    return f"manifests/{pid[0].lower()}/{pid.replace('.', '/')}"


def from_winget(src, arches):
    pid = src["winget"]
    entries = gh_api(f"repos/microsoft/winget-pkgs/contents/{winget_dir(pid)}")
    versions = [e["name"] for e in entries if e["type"] == "dir" and re.match(r"^\d", e["name"])]
    if not versions:
        raise RuntimeError(f"no versions of {pid} in winget-pkgs")
    raw = max(versions, key=lambda v: [int(p) if p.isdigit() else -1 for p in re.split(r"[.\-+]", v)])
    f = gh_api(f"repos/microsoft/winget-pkgs/contents/{winget_dir(pid)}/{raw}/{pid}.installer.yaml")
    manifest = yaml.safe_load(base64.b64decode(f["content"]))
    version = raw
    if "version" in src:
        m = re.match(src["version"], raw)
        if not m:
            raise RuntimeError(f"winget version {raw} doesn't match {src['version']}")
        version = m.group(1)
    released = manifest.get("ReleaseDate")
    files = {}
    for arch in arches:
        pattern = src["urls"].get(arch)
        found = {}
        for inst in manifest["Installers"]:
            if inst.get("Architecture") == arch and pattern and re.search(pattern, inst["InstallerUrl"]):
                found[inst["InstallerUrl"]] = inst["InstallerSha256"].lower()
        if len(found) != 1:
            raise RuntimeError(f"{arch}: {len(found)} installers match {pattern} in {pid} {raw}")
        url, sha = next(iter(found.items()))
        if "rewrite" in src and url.startswith(src["rewrite"][0]):
            url = src["rewrite"][1] + url[len(src["rewrite"][0]):]
        files[arch] = (url, sha)
    return version, str(released) if released else None, files


def from_adoptium(src, arches):
    feature = src["adoptium"]
    files = {}
    version = released = None
    for arch in arches:
        api_arch = {"x64": "x64", "arm64": "aarch64"}[arch]
        url = (f"https://api.adoptium.net/v3/assets/latest/{feature}/hotspot?architecture={api_arch}"
               f"&image_type=jdk&os=windows&vendor=eclipse")
        req = urllib.request.Request(url, headers={"User-Agent": UA})
        with urllib.request.urlopen(req, timeout=60) as res:
            assets = json.load(res)
        msi = [a for a in assets if a["binary"].get("installer", {}).get("name", "").endswith(".msi")]
        if len(msi) != 1:
            raise RuntimeError(f"{arch}: {len(msi)} Temurin {feature} MSIs")
        a = msi[0]
        # 21.0.13.1+2-LTS → 21.0.13.1, as the catalog and Windows' list write it
        v = re.sub(r"[+-].*$", "", a["version"]["openjdk_version"])
        if version and v != version:
            raise RuntimeError(f"architectures disagree on the version: {version}, {v}")
        version, released = v, a["binary"]["updated_at"][:10]
        files[arch] = (a["binary"]["installer"]["link"], a["binary"]["installer"]["checksum"].lower())
    return version, released, files


def resolve(src, arches):
    if "github" in src:
        return from_github(src, arches)
    if "winget" in src:
        return from_winget(src, arches)
    if "adoptium" in src:
        return from_adoptium(src, arches)
    raise RuntimeError("no source")


# --- downloading and the catalog file ----------------------------------------------------------

def download(url):
    """(sha256, size) of the file at url, streamed: nothing is kept."""
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    sha, size = hashlib.sha256(), 0
    with urllib.request.urlopen(req, timeout=120) as res:
        if not res.geturl().lower().startswith("https://"):
            raise RuntimeError(f"redirected off https, to {res.geturl()}")
        while chunk := res.read(1 << 20):
            sha.update(chunk)
            size += len(chunk)
    return sha.hexdigest(), size


def dump(value, indent=0):
    """JSON in the catalog's own layout: lists of plain values and one-key objects on one line."""
    pad = "  " * indent
    if isinstance(value, dict):
        if len(value) == 1 and not isinstance(next(iter(value.values())), (dict, list)):
            k, v = next(iter(value.items()))
            return "{ " + json.dumps(k) + ": " + json.dumps(v, ensure_ascii=False) + " }"
        items = [f'{pad}  {json.dumps(k)}: {dump(v, indent + 1)}' for k, v in value.items()]
        return "{\n" + ",\n".join(items) + f"\n{pad}}}"
    if isinstance(value, list):
        if all(not isinstance(v, (dict, list)) for v in value):
            return "[" + ", ".join(json.dumps(v, ensure_ascii=False) for v in value) + "]"
        items = [f"{pad}  {dump(v, indent + 1)}" for v in value]
        return "[\n" + ",\n".join(items) + f"\n{pad}]"
    return json.dumps(value, ensure_ascii=False)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--write", action="store_true")
    ap.add_argument("--summary")
    ap.add_argument("--changed")
    ap.add_argument("--only", nargs="*", help="just these app ids")
    args = ap.parse_args()

    text = CATALOG.read_text()
    catalog = json.loads(text)
    if dump(catalog) + "\n" != text:
        sys.exit("catalog.json isn't in the layout this tool writes; fix the tool before writing with it")
    sources = {k: v for k, v in json.loads(SOURCES.read_text()).items() if not k.startswith("_")}

    problems, updates, lines = [], [], []
    for app in catalog["apps"]:
        if "version" not in app or (args.only and app["id"] not in args.only):
            continue
        src = sources.get(app["id"])
        if not src:
            problems.append(f"{app['id']}: pinned, but tools/sources.json has no source for it")
            continue
        arches = [i["arch"] for i in app["installers"]]
        try:
            version, released, files = resolve(src, arches)
        except Exception as e:
            problems.append(f"{app['id']}: {e}")
            lines.append(f"  {app['id']:18} source failed")
            continue
        c = compare(version, app["version"])
        if c < 0:
            problems.append(f"{app['id']}: the source's newest is {version}, older than the catalog's {app['version']}")
            continue
        if c == 0:
            # today's entry: the source must agree with it, or one of them is wrong
            disagree = [arch for arch in arches if files[arch][1] and files[arch][1] != next(
                i for i in app["installers"] if i["arch"] == arch)["sha256"]]
            if disagree and all("sha256" in i for i in app["installers"]):
                problems.append(f"{app['id']}: {version} has another SHA-256 at the source for {', '.join(disagree)}")
            lines.append(f"  {app['id']:18} {app['version']} up to date")
            continue
        lines.append(f"  {app['id']:18} {app['version']} → {version}")
        updates.append((app, src, version, released, files))

    print("\n".join(lines))
    if args.check or not args.write:
        for p in problems:
            print("problem: " + p)
        sys.exit(1 if problems else 0)

    done = []
    for app, src, version, released, files in updates:
        try:
            new_installers = []
            for inst in app["installers"]:
                url, published = files[inst["arch"]]
                sha, size = download(url)
                if published and published != sha:
                    raise RuntimeError(f"{inst['arch']}: the download's SHA-256 is {sha}, the source says {published}")
                new = dict(inst)
                new["url"], new["sha256"], new["size"] = url, sha, size
                # keep the entry's key order: url, size, sha256 as the catalog writes them
                order = [k for k in inst if k not in ("sha256", "size")]
                pos = order.index("url") + 1
                order[pos:pos] = ["size", "sha256"]
                new_installers.append({k: new[k] for k in order if k in new})
        except Exception as e:
            problems.append(f"{app['id']} {version}: {e}")
            continue
        old = app["version"]
        app["version"] = version
        app["released"] = released or datetime.date.today().isoformat()
        if "detect" in src:
            app["detect"] = {"displayName": src["detect"].replace("{version}", re.escape(version))}
        app["installers"] = new_installers
        done.append((app, old))
        print(f"updated {app['id']} {old} → {version}")

    if done:
        CATALOG.write_text(dump(catalog) + "\n")
        news = json.loads(NEWS.read_text())
        today = datetime.date.today().isoformat()
        names = ", ".join(f"{a['name']} {a['version']}" for a, _ in done)
        news.insert(0, {"date": today, "title": "Updated: " + names,
                        "text": "New versions, each download checked against its publisher's SHA-256: "
                                + "; ".join(f"{a['name']} {o} → {a['version']}" for a, o in done) + ".",
                        "apps": [a["id"] for a, _ in done]})
        NEWS.write_text(json.dumps(news, indent=2, ensure_ascii=False) + "\n")
    if args.changed:
        pathlib.Path(args.changed).write_text("".join(a["id"] + "\n" for a, _ in done))
    if args.summary:
        rows = "\n".join(f"| {a['name']} | {o} | {a['version']} | {a['released']} |" for a, o in done)
        body = ("| App | Was | Now | Released |\n|---|---|---|---|\n" + rows + "\n") if done else "No updates.\n"
        if problems:
            body += "\n**Problems:**\n" + "\n".join(f"- {p}" for p in problems) + "\n"
        pathlib.Path(args.summary).write_text(body)
    for p in problems:
        print("problem: " + p)
    sys.exit(1 if problems else 0)


if __name__ == "__main__":
    main()
