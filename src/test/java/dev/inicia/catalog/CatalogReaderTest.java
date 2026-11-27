package dev.inicia.catalog;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatalogReaderTest {

    private static final String SHA = "9b697f65ecc2d9b0152f5ee96d72dde522a7827690349d9307935dacf40f42ec";

    @Test
    void builtInCatalogReads() {
        Catalog catalog = CatalogReader.builtIn();
        assertThat(catalog.apps()).extracting(App::id).contains("7zip", "notepad-plus-plus", "putty", "vlc", "firefox",
                "editora", "termina", "torrenta", "mirada", "nux", "modula");
        App sevenZip = catalog.app("7zip").orElseThrow();
        assertThat(sevenZip.version()).contains("26.06");
        assertThat(sevenZip.installerFor(Arch.ARM64).orElseThrow().arch()).isEqualTo(Arch.ARM64);
        assertThat(catalog.app("firefox").orElseThrow().version()).isEmpty();
        assertThat(catalog.apps()).filteredOn(a -> a.suite().isPresent()).extracting(App::id)
                .containsExactly("editora", "termina", "torrenta", "mirada", "nux", "modula", "jkit");
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
    void allAndCategoriesExpandInCatalogOrder() {
        Catalog catalog = CatalogReader.builtIn();
        assertThat(Catalog.categoryToken("Developer tools")).isEqualTo("cat-developer-tools");
        assertThat(Catalog.categoryToken("Web browsers")).isEqualTo("cat-web-browsers");
        assertThat(catalog.expand(List.of("all"))).hasSize(catalog.apps().size());
        assertThat(catalog.expand(List.of("vlc", "cat-web-browsers", "firefox", "nope", "cat-nothing")))
                .containsExactly("vlc", "firefox", "chrome", "nope", "cat-nothing");
        assertThat(Catalog.suiteToken("Vitrina")).isEqualTo("suite-vitrina");
        assertThat(catalog.expand(List.of("git", "suite-vitrina", "editora", "suite-nothing")))
                .containsExactly("git", "editora", "termina", "torrenta", "mirada", "nux", "modula", "jkit", "suite-nothing");
        // every category's selector fits an exe name, and names only its own apps
        for (App app : catalog.apps()) {
            assertThat(catalog.expand(List.of(Catalog.categoryToken(app.category())))).contains(app.id());
            assertThat(Catalog.categoryToken(app.category())).matches(CatalogReader.ID.pattern());
        }
    }

    @Test
    void groupSelectorsAreReservedIds() {
        String installer = "[ { \"arch\": \"x64\", \"type\": \"nsis\", \"url\": \"https://e.com/a.exe\", \"sha256\": \"" + SHA + "\" } ]";
        assertThatThrownBy(() -> CatalogReader.read("t", """
                { "catalog": 1, "apps": [ { "id": "all", "name": "A", "installers": %1$s },
                                          { "id": "cat-x", "name": "B", "installers": %1$s } ] }
                """.formatted(installer)))
                .isInstanceOfSatisfying(CatalogException.class, e -> assertThat(e.problems()).containsExactly(
                        "apps[0].id: \"all\" is reserved: \"all\", \"cat-…\" and \"suite-…\" select groups of apps",
                        "apps[1].id: \"cat-x\" is reserved: \"all\", \"cat-…\" and \"suite-…\" select groups of apps"));
    }

    @Test
    void builtInRequirementsAllExist() {
        assertThat(CatalogReader.builtIn().missingRequirements()).isEmpty();
    }

    @Test
    void requirementsComeFirstOnceAndCyclesEnd() throws Exception {
        String installer = "[ { \"arch\": \"x64\", \"type\": \"nsis\", \"url\": \"https://e.com/a.exe\", \"sha256\": \"" + SHA + "\" } ]";
        Catalog catalog = CatalogReader.read("t", """
                { "catalog": 1, "apps": [
                  { "id": "runtime", "name": "Runtime", "installers": %1$s },
                  { "id": "a", "name": "A", "requires": ["runtime"], "note": "accepts a license", "installers": %1$s },
                  { "id": "b", "name": "B", "requires": ["runtime", "c"], "installers": %1$s },
                  { "id": "c", "name": "C", "requires": ["b"], "installers": %1$s },
                  { "id": "d", "name": "D", "requires": ["nowhere"], "installers": %1$s }
                ] }
                """.formatted(installer));
        assertThat(catalog.withRequirements(List.of(catalog.app("a").orElseThrow(), catalog.app("b").orElseThrow())))
                .extracting(App::id).containsExactly("runtime", "a", "c", "b");
        assertThat(catalog.missingRequirements()).containsExactly("d: requires nowhere, which isn't in the catalog");
        assertThat(catalog.app("a").orElseThrow().note()).contains("accepts a license");
        assertThatThrownBy(() -> CatalogReader.read("t", """
                { "catalog": 1, "apps": [ { "id": "a", "name": "A", "requires": ["a", 3], "installers": %s } ] }
                """.formatted(installer)))
                .isInstanceOfSatisfying(CatalogException.class, e -> assertThat(e.problems()).containsExactly(
                        "apps[0].requires: \"a\" isn't another app's id",
                        "apps[0].requires: 3 isn't another app's id"));
    }

    @Test
    void sizesAreReadAndChecked() throws Exception {
        assertThat(CatalogReader.builtIn().app("7zip").orElseThrow().installers().getFirst().size()).hasValue(1659704);
        assertThatThrownBy(() -> CatalogReader.read("t", """
                { "catalog": 1, "apps": [ { "id": "a", "name": "A", "installers": [
                  { "arch": "x64", "type": "nsis", "url": "https://e.com/a.exe", "sha256": "%s", "size": -5 } ] } ] }
                """.formatted(SHA)))
                .hasMessageContaining("apps[0].installers[0].size: must be the file's size in bytes");
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
