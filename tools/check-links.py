#!/usr/bin/env python3
"""Checks that every installer in the catalog can still be downloaded, without downloading it.

    tools/check-links.py

For each installer it asks the server for the file (HEAD, following redirects, as inicia's
downloader does) and checks:
- the address answers 200, ending on https (curl refuses redirects to anything else)
- a pinned installer (its app has a version) is still the size the catalog says, when the server
  says: a different size means the file at that address changed, and its SHA-256 won't match
"Latest" downloads only have to answer; their size changes with every release.
Exits 1 with every problem listed. Run weekly by .github/workflows/catalog-links.yml.
"""
import concurrent.futures
import json
import pathlib
import sys
import time
import subprocess

ROOT = pathlib.Path(__file__).resolve().parent.parent
catalog = json.loads((ROOT / "src/main/resources/dev/inicia/catalog.json").read_text())
problems = []


ATTEMPTS = 3


def curl(args, url):
    """curl's output; a network or TLS failure is tried again, as inicia's downloader does: a
    mirror network (LibreOffice's, SourceForge's) sends each try to a mirror of its choosing, and
    the odd mirror is broken. When every try fails, the error names where it failed."""
    for attempt in range(1, ATTEMPTS + 1):
        r = subprocess.run(["curl", "-sL", "--proto-redir", "=https", "--max-time", "90",
                            "-A", "inicia-link-check", "-w", "\n%{http_code} %{url_effective}", *args, url],
                           capture_output=True, text=True)
        if r.returncode in (0, 23):
            return r.stdout
        failed_at = r.stdout.strip().splitlines()[-1].split(" ", 1)[-1] if r.stdout.strip() else url
        if attempt == ATTEMPTS:
            raise RuntimeError(f"curl exit code {r.returncode} at {failed_at}, {ATTEMPTS} tries"
                               + (" (60: the server's TLS certificate isn't valid)" if r.returncode == 60 else ""))
        print(f"  (try {attempt} failed, curl exit code {r.returncode} at {failed_at}; trying again)", flush=True)
        time.sleep(2)


def head(url):
    """(status, final address, size) for a HEAD request following redirects, the way inicia's
    downloader does; a one-byte GET when the server refuses HEAD. curl, because Python's urllib
    waits out its timeout on some of these redirects."""
    for extra in (["-I"], ["-r", "0-0", "-o", "/dev/null", "-D", "-"]):
        lines = curl(extra, url).strip().splitlines()
        status, final = lines[-1].split(" ", 1)
        status = int(status)
        if status in (403, 405, 501) and extra == ["-I"]:
            continue
        headers = [l.lower() for l in lines[:-1]]
        lengths = [l.split(":", 1)[1].strip() for l in headers if l.startswith("content-length:")]
        ranges = [l.split("/", 1)[1].strip() for l in headers if l.startswith("content-range:") and "/" in l]
        size = ranges[-1] if ranges else (lengths[-1] if lengths and extra == ["-I"] else None)
        return status, final, int(size) if size and size.isdigit() else None
    return None, url, None


def check(app, inst):
    """The problem with one installer, or None, and a line for the log."""
    where = f"{app['id']} ({inst['arch']}): {inst['url']}"
    started = time.monotonic()
    try:
        status, final, size = head(inst["url"])
    except Exception as e:  # network errors, timeouts
        return f"{where}: {e}", f"  {app['id']:18} {inst['arch']:6} error"
    line = f"  {app['id']:18} {inst['arch']:6} {status} {size} ({time.monotonic() - started:.1f} s)"
    if status not in (200, 206):
        return f"{where}: HTTP {status}", line
    if not final.lower().startswith("https://"):
        return f"{where}: ends on {final}, not https", line
    if "version" in app and "size" in inst and size is not None and size != inst["size"]:
        return f"{where}: {size} bytes now, the catalog says {inst['size']}: the file changed", line
    return None, line


# a few servers take a minute to answer: ask eight at a time, and print in catalog order
jobs = [(app, inst) for app in catalog["apps"] for inst in app["installers"]]
checked = len(jobs)
with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
    for problem, line in pool.map(lambda j: check(*j), jobs):
        print(line, flush=True)
        if problem:
            problems.append(problem)

for p in problems:
    print(p)
print(f"{checked} installers checked: " + ("ok" if not problems else f"{len(problems)} problems"))
sys.exit(1 if problems else 0)
