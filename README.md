# inicia

Installs a chosen set of Windows apps in one go: silently, each download checked against the
catalog's SHA-256 or its publisher's code signature, with no toolbars or other bundled extras.
Apps already at the catalog's version are left alone; older ones are upgraded. One native exe,
built with GraalVM; nothing to install first.

```powershell
inicia list                              # what the catalog has
inicia install 7zip vlc notepad-plus-plus
inicia install -f my-apps.txt            # ids from a file, # for comments
inicia install 7zip --dry-run            # what it would do
inicia status                            # catalog apps installed here, and which are out of date
```

Renamed `inicia_<id>_<id>.exe` (for example `inicia_7zip_vlc.exe`), the exe installs those
apps when double-clicked: that's what the picker page hands out. A browser's ` (1)` is ignored.
Installing needs administrator rights; inicia asks Windows for them (the UAC prompt) and carries
on in an administrator window.

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
| `id` | lowercase letters, digits, hyphens; used on the command line and in exe names |
| `version` | what the installers install. Leave it out for a "latest" URL: an installed copy is then left alone |
| `detect.displayName` | a regular expression for the name in Settings > Apps. Default: starts with `name` |
| `arch` | `x64`, `x86` or `arm64`. ARM64 machines fall back to x64, then x86 |
| `type` | `msi` (msiexec `/qn /norestart`), `inno` (`/VERYSILENT /SUPPRESSMSGBOXES /NORESTART /SP-`), `nsis` (`/S`), or `exe` with your own `args` |
| `sha256` | the file's hash. Required unless `signer` is given |
| `signer` | the Authenticode certificate's CN or O; the signature must also be valid and trusted |
| `args` | extra arguments after the type's own |

Every `url` must be https, and redirects are never followed to plain http.

## The picker page

`web/` is a static page: tick apps, and it offers `inicia.exe` under the name
`inicia_<ids>.exe` (the link's `download` attribute), so one file on any static host serves every
selection. It also gives the `inicia install …` command and an `apps.txt`, and keeps the
selection in the address (`#7zip,vlc`) for sharing. The page and the exe must be on the same
origin, or browsers ignore the download name.

```bash
web/assemble.sh path/to/inicia.exe   # target/web: page, catalog.json, inicia.exe; upload it as is
```

The page lists the built-in catalog, so apps of your own that people should pick there go into
`src/main/resources/dev/inicia/catalog.json`, and the exe is rebuilt.

## Building

```bash
./mvnw verify                      # tests (Linux, macOS or Windows)
```

The exe has to be built on Windows: native-image can't cross-compile. With GraalVM 25 as
`JAVA_HOME`/`GRAALVM_HOME` and the Visual Studio 2022 Build Tools (C++ workload) installed:

```powershell
.\mvnw.cmd -Pnative package        # target\inicia.exe
```

## Not done yet

- Code-signing the exe: unsigned and downloaded from the web, it gets SmartScreen's "Windows
  protected your PC" warning.
- Catalog signing: a remote `--catalog` is trusted as far as its https host is.
- Downloads go to the user's temp folder, which unelevated programs of the same user can write
  to between the check and the run; they should go to a folder only administrators can write.
- An embedded UAC manifest and an icon for the exe.
- Uninstall, and keeping the catalog's versions current.
