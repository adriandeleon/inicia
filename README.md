# inicia

[![CI](https://github.com/adriandeleon/inicia/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/adriandeleon/inicia/actions/workflows/ci.yml)
[![Catalog links](https://github.com/adriandeleon/inicia/actions/workflows/catalog-links.yml/badge.svg)](https://github.com/adriandeleon/inicia/actions/workflows/catalog-links.yml)

Installs a chosen set of Windows apps in one go: silently, each download checked against the
catalog's SHA-256 or its publisher's code signature, with no toolbars or other bundled extras.
Apps already at the catalog's version are left alone; older ones are upgraded. One native exe,
built with GraalVM; nothing to install first.

Double-click a downloaded `inicia_<id>_<id>.exe` and it opens its window: a progress bar for
the whole run (on the taskbar button too), each app's status, the details on demand, and
Retry failed. A plain `inicia.exe` opens as a picker, with search, sizes and what's installed. The window is Windows Forms, run by the Windows PowerShell every Windows 10 and 11 PC
has, so inicia stays one exe with nothing beside it.

The command line is for administrators and scripts:

```powershell
inicia list                              # what the catalog has
inicia install 7zip vlc notepad-plus-plus
inicia install cat-media                 # a whole category; inicia list shows their names
inicia install suite-vitrina             # every app of a suite
inicia install all                       # the whole catalog
inicia install -f my-apps.txt            # ids from a file, # for comments
inicia install 7zip --dry-run            # what it would do
inicia install all --quiet --log C:\logs\inicia.log   # no output; exit code 0 when all went well
inicia status                            # catalog apps installed here, and which are out of date
inicia_7zip_vlc.exe --silent             # a downloaded exe's apps, with no window and no output
inicia_7zip_vlc.exe --cli                # the same, in the console
inicia install vlc --proxy proxy.corp:3128   # through a proxy (else INICIA_PROXY, HTTPS_PROXY, or Windows' settings)
inicia install vlc --built-in-catalog    # the exe's own catalog, without fetching the latest
```

Exit codes: 0 all went well, 1 an app failed, 2 nothing to do with what was asked, 3 no
administrator rights.

Renamed `inicia_<id>_<id>.exe` (for example `inicia_7zip_vlc.exe`), the exe installs those
apps when double-clicked: that's what the picker page hands out. A whole category goes in as
`cat-<category>`, a suite as `suite-<suite>` and the whole catalog as `all` (`inicia_all.exe`),
which keeps the name short. A browser's ` (1)` is ignored. Installing needs administrator
rights: inicia asks Windows for them (the UAC prompt) before its window shows the installs.

An app that's open when inicia would update it is waited for in the window ("Close VLC to
update it") and fails with that message on the command line; apps marked `updatesWhileRunning`
(Chrome, Slack, Dropbox) aren't waited for.

## The catalog it fetches

Each run, inicia fetches `catalog.json` and `catalog.json.sig` from the website and uses them in
place of its built-in catalog, so a saved exe installs current versions. The `.sig` file holds
the publication time and an Ed25519 signature over `inicia-catalog 1\n<time>\n` plus the
catalog's bytes (`SignedCatalog.java`). The exe takes the catalog only when the signature matches
the public key built into it (`src/main/resources/dev/inicia/catalog-key.pub`), when it was
published no earlier than the exe was built (no rolling back by replaying an old one), when it
can read it, and when it has every app asked for; otherwise it logs why and uses its own.

- `tools/catalog-key.sh` makes the key pair: the private key goes straight into the
  `CATALOG_SIGNING_KEY` repository secret, the public one into `catalog-key.pub` to commit. Run it
  again to replace a lost key; exes built before that keep to their built-in catalogs.
- `tools/sign-catalog.sh`, in `pages.yml`, signs the catalog the site serves, after checking the
  secret matches `catalog-key.pub`. Without the secret it signs nothing.
- `inicia check --signed <file|url>` checks a catalog and its signature as inicia would.

## Your own installers

A catalog is a JSON file. Pass yours with `--catalog` (a file, or an `https://` address); its apps
are added to the built-in ones, and an app with a built-in id replaces that entry.

```json
{
  "catalog": 1,
  "apps": [
    {
      "id": "my-tool",
      "name": "My Tool",
      "category": "In-house",
      "version": "2.1.0",
      "installers": [
        { "arch": "x64", "type": "inno",
          "url": "https://downloads.example.com/my-tool-2.1.0-x64.exe",
          "sha256": "…", "signer": "Example Corp",
          "args": ["/MERGETASKS=!desktopicon"] }
      ]
    }
  ]
}
```

```powershell
inicia hash https://downloads.example.com/my-tool-2.1.0-x64.exe   # sha256, signer, and an entry to start from
inicia check my-catalog.json                                       # every problem, with where it is
inicia install my-tool --catalog my-catalog.json
```

| Field | |
| --- | --- |
| `id` | lowercase letters, digits, hyphens; used on the command line and in exe names. `all`, `cat-…` and `suite-…` are taken |
| `version` | what the installers install. Leave it out for a "latest" URL: an installed copy is then left alone |
| `license` | an SPDX id (`GPL-3.0`, `MIT`…) for open source, or `Proprietary`, or `Subscription`; shown on the page |
| `released` | when the publisher released that version, `YYYY-MM-DD`; shown on the page |
| `detect.displayName` | a regular expression for the name in Settings > Apps. Default: starts with `name` |
| `requires` | ids of apps installed first (VirtualBox requires `vcredist`) |
| `note` | something to know before installing, such as a license it accepts; shown on the page and in the plan |
| `updatesWhileRunning` | `true` when its installer updates it while it's open: inicia doesn't wait for it to be closed |
| `suite` | the family of apps it belongs to (`Vitrina`); the page shows "Part of the Vitrina Suite", and `suite-vitrina` selects them all |
| `arch` | `x64`, `x86` or `arm64`. ARM64 machines fall back to x64, then x86 |
| `type` | `msi` (msiexec `/qn /norestart`), `msix` (PowerShell's Add-AppxPackage, for the account that runs inicia; no `args`), `inno` (`/VERYSILENT /SUPPRESSMSGBOXES /NORESTART /SP-`), `nsis` (`/S`), or `exe` with your own `args` |
| `sha256` | the file's hash. Required unless `signer` is given |
| `signer` | the Authenticode certificate's CN or O; the signature must also be valid and trusted |
| `args` | extra arguments after the type's own |
| `runAs` | `user`: run the installer as the signed-in user, without administrator rights, for installers that refuse to run elevated (Spotify's) |
| `size` | the download's size in bytes; shown on the page, and the progress total when the server doesn't say |

Every `url` must be https, and redirects are never followed to plain http.

## The website

`web/` is a static site, light by default with a dark theme to switch to:

- `index.html`: the picker. Tick apps, and it offers `inicia.exe` under the name
  `inicia_<ids>.exe` (the link's `download` attribute), so one file on any static host serves
  every selection. It also gives the `inicia install …` command and an `apps.txt`, and keeps the
  selection in the address (`#7zip,vlc`) for sharing. The page and the exe must be on the same
  origin, or browsers ignore the download name.
- `news.html`: new apps, from `news.json`. Add an entry there when apps are added
  (`tools/check-site.py` checks every app has one). The home page shows the latest five.
- `security.html`: what inicia checks, what it never does, and how to report a problem.
- `excluded.json`: apps people look for that inicia can't include, and why; the picker's search
  shows the reason.
- Generated by `web/build-pages.py` when the site is assembled: `apps.html`, every app, category
  and "everything" as plain download links (no JavaScript needed), and `feed.xml`, an Atom feed.
- `docs.html`: how inicia works, for the people who use it.
- `hero.png`: a real screenshot of inicia's window, for the front page.
- `icons/<id>.svg` or `.png`: one per app. `web/fetch-icons.py` fetches missing ones from each
  app's homepage; look at what it gets, and add the ones it can't get right by hand.

```bash
web/assemble.sh path/to/inicia.exe   # target/web: pages, catalog.json, icons, inicia.exe; upload it as is
```

The site lists the built-in catalog, so apps of your own that people should pick there go into
`src/main/resources/dev/inicia/catalog.json`, and the exe is rebuilt. App names and logos belong
to their owners.

## Building

```bash
./mvnw verify                      # tests (Linux, macOS or Windows)
```

The exe has to be built on Windows: native-image can't cross-compile. With GraalVM 25 as
`JAVA_HOME`/`GRAALVM_HOME` and the Visual Studio 2022 Build Tools (C++ workload) installed:

```powershell
.\mvnw.cmd -Pnative package        # target\inicia.exe
```

On Windows the build links inicia's icon (`src/main/windows/inicia.res`) and the Visual C++ runtime into the exe (the `windows-static-vcruntime`
profile), so `inicia.exe` starts on a fresh Windows, which doesn't have `vcruntime140.dll`. It
needs only DLLs that ship with Windows 10 and 11.

The icon is `web/brand/inicia.svg`; `tools/make-icons.py` (needs Chrome or Chromium, and
ImageMagick) makes the site's favicons, the window's `inicia.ico` and the exe's `inicia.res` from
it.

## CI

- `ci.yml`, on every pull request and push to main: the tests on Linux and Windows, the native
  exe (checked for Visual C++ runtime imports, then smoke-tested by really installing 7-Zip,
  PuTTY and, as `inicia_peazip.exe --silent`, PeaZip on the runner), the site's data
  (`tools/check-site.py`: every app has an icon and a News entry), and actionlint.
- `pages.yml`, on every push to main: builds the exe and publishes the website, with the exe the
  download buttons serve and the signed catalog, on GitHub Pages (https://adriandeleon.github.io/inicia/). The
  repository's Pages source must be set to GitHub Actions.
- `catalog-updates.yml`, daily: `tools/update-catalog.py` looks for new versions of every pinned
  app (where to look is in `tools/sources.json`: GitHub releases, winget's manifests, or
  Adoptium's API), downloads and hashes each new installer (its SHA-256 must match the one the
  source publishes), updates `catalog.json` and `news.json` on the `catalog-updates` branch and
  opens a pull request. It then installs the updated apps on Windows with inicia from that branch
  and comments with the result. Merging it publishes the new versions. Opening the pull request
  needs Settings > Actions > General > "Allow GitHub Actions to create and approve pull requests".
  A new pinned app needs an entry in `tools/sources.json`.
- `catalog-links.yml`, weekly and when the catalog changes: every installer still answers at its
  address, and pinned ones are still the size the catalog says (`tools/check-links.py`).

## Not done yet

- Code-signing the exe: unsigned and downloaded from the web, it gets SmartScreen's "Windows
  protected your PC" warning.
- A remote `--catalog` of your own is trusted as far as its https host is; only the site's
  catalog is signed.
- Downloads go to the user's temp folder, which unelevated programs of the same user can write
  to between the check and the run; they should go to a folder only administrators can write.
- Uninstall.
