package dev.inicia.catalog;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatalogReaderTest {

    private static final String SHA = "9b697f65ecc2d9b0152f5ee96d72dde522a7827690349d9307935dacf40f42ec";

    @Test
    void builtInCatalogReads() {
        Catalog catalog = CatalogReader.builtIn();
        assertThat(catalog.apps()).extracting(App::id).contains("7zip", "notepad-plus-plus", "putty", "vlc", "firefox");
        App sevenZip = catalog.app("7zip").orElseThrow();
        assertThat(sevenZip.version()).contains("26.06");
        assertThat(sevenZip.installerFor(Arch.ARM64).orElseThrow().arch()).isEqualTo(Arch.ARM64);
        assertThat(catalog.app("firefox").orElseThrow().version()).isEmpty();
    }

    @Test
    void defaultDetectMatchesNamesEndingInSymbols() {
        App npp = CatalogReader.builtIn().app("notepad-plus-plus").orElseThrow();
        assertThat(npp.detect().matcher("Notepad++ (64-bit x64)").find()).isTrue();
        App sevenZip = CatalogReader.builtIn().app("7zip").orElseThrow();
        assertThat(sevenZip.detect().matcher("7-Zip 26.06 (x64)").find()).isTrue();
        assertThat(sevenZip.detect().matcher("7-ZipX").find()).isFalse();
    }

    @Test
    void everyProblemIsReportedWithItsPlace() {
        String json = """
                {
                  "catalog": 1,
                  "colour": "blue",
                  "apps": [
                    { "id": "Bad Id", "name": "A",
                      "installers": [ { "arch": "x64", "type": "nsis", "url": "http://example.com/a.exe", "sha256": "%s" } ] },
                    { "id": "b", "name": "B",
                      "installers": [ { "arch": "sparc", "type": "zip", "url": "https://example.com/b.exe" } ] },
                    { "id": "c", "name": "C",
                      "installers": [ { "arch": "x64", "type": "exe", "url": "https://example.com/c.exe", "sha256": "%s" } ] },
                    { "id": "d", "name": "D", "detect": { "displayName": "([" },
                      "installers": [ { "arch": "x64", "type": "nsis", "url": "https://example.com/d.exe", "sha256": "abc" } ] }
                  ]
                }
                """.formatted(SHA, SHA);
        assertThatThrownBy(() -> CatalogReader.read("test.json", json))
                .isInstanceOfSatisfying(CatalogException.class, e -> assertThat(e.problems()).containsExactly(
                        "colour: unknown key (known: apps, catalog)",
                        "apps[0].id: \"Bad Id\" must be lowercase letters, digits and hyphens, at most 40",
                        "apps[0].installers[0].url: must be an https:// address",
                        "apps[1].installers[0].arch: \"sparc\" isn't x64, x86 or arm64",
                        "apps[1].installers[0].type: \"zip\" isn't msi, inno, nsis or exe",
                        "apps[1].installers[0]: needs sha256, signer or both, so the download can be checked",
                        "apps[2].installers[0].args: an exe installer needs the arguments that make it silent",
                        "apps[3].detect.displayName: not a valid regular expression: Unclosed character class",
                        "apps[3].installers[0].sha256: must be 64 hexadecimal characters"));
    }

    @Test
    void duplicateIdsAndArchitectures() {
        String installer = "{ \"arch\": \"x64\", \"type\": \"nsis\", \"url\": \"https://e.com/a.exe\", \"sha256\": \"" + SHA + "\" }";
        String json = "{ \"catalog\": 1, \"apps\": ["
                + "{ \"id\": \"a\", \"name\": \"A\", \"installers\": [" + installer + "," + installer + "] },"
                + "{ \"id\": \"b\", \"name\": \"B\", \"installers\": [" + installer + "] },"
                + "{ \"id\": \"b\", \"name\": \"B2\", \"installers\": [" + installer + "] } ] }";
        assertThatThrownBy(() -> CatalogReader.read("t", json))
                .isInstanceOfSatisfying(CatalogException.class, e -> assertThat(e.problems()).containsExactly(
                        "apps[0].installers[1].arch: a second x64 installer",
                        "apps[2].id: b appears more than once"));
    }

    @Test
    void byteOrderMarkIsSkipped() throws Exception {
        assertThat(CatalogReader.read("t", "﻿{ \"catalog\": 1, \"apps\": [] }").apps()).isEmpty();
    }

    @Test
    void wrongFormatAndBadJson() {
        assertThatThrownBy(() -> CatalogReader.read("t", "{ \"catalog\": 2, \"apps\": [] }"))
                .hasMessageContaining("catalog: must be 1");
        assertThatThrownBy(() -> CatalogReader.read("t", "{ \"catalog\": 1, "))
                .hasMessageContaining("not valid JSON");
    }

    @Test
    void ownCatalogOverridesBuiltInEntryInPlace() throws Exception {
        Catalog own = CatalogReader.read("own", """
                { "catalog": 1, "apps": [
                  { "id": "7zip", "name": "7-Zip", "version": "99.0",
                    "installers": [ { "arch": "x64", "type": "msi", "url": "https://intranet.example/7z.msi", "signer": "Igor Pavlov" } ] },
                  { "id": "my-tool", "name": "My Tool", "category": "In-house",
                    "installers": [ { "arch": "x64", "type": "inno", "url": "https://intranet.example/tool.exe", "signer": "Example Corp" } ] }
                ] }
                """);
        Catalog merged = CatalogReader.builtIn().with(own);
        assertThat(merged.apps().getFirst().id()).isEqualTo("7zip");
        assertThat(merged.app("7zip").orElseThrow().version()).contains("99.0");
        assertThat(merged.apps().getLast().id()).isEqualTo("my-tool");
    }

    @Test
    void fileNameFromUrlOrFallback() {
        Installer pinned = CatalogReader.builtIn().app("putty").orElseThrow().installers().getFirst();
        assertThat(pinned.fileName("putty")).isEqualTo("putty-64bit-0.85-installer.msi");
        Installer latest = CatalogReader.builtIn().app("firefox").orElseThrow().installers().getFirst();
        assertThat(latest.fileName("firefox")).isEqualTo("firefox-setup.exe");
    }
}
