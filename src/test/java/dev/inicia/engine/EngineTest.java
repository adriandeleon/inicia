package dev.inicia.engine;

import dev.inicia.catalog.Arch;
import dev.inicia.catalog.Catalog;
import dev.inicia.catalog.CatalogReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EngineTest {

    @TempDir
    Path work;

    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
    private final List<List<String>> ran = new ArrayList<>();
    private final List<InstalledApp> installed = new ArrayList<>();

    /** Writes the given bytes instead of downloading, reporting the hash of what it wrote. */
    private Downloader downloads(byte[] content) {
        return new Downloader("test") {
            @Override
            public Download fetch(URI url, Path target, Listener listener) throws IOException {
                Files.write(target, content);
                listener.progress(content.length, content.length);
                return new Download(target, Downloader.sha256(target), content.length, url);
            }
        };
    }

    private ProcessRunner installs(String displayName, String version, int exitCode) {
        return (command, timeout) -> {
            ran.add(command);
            if (exitCode == 0 || exitCode == 3010) {
                installed.add(new InstalledApp(displayName, version));
            }
            return exitCode;
        };
    }

    private Catalog catalog(String sha256, String signer) throws Exception {
        String check = (sha256 != null ? ", \"sha256\": \"" + sha256 + "\"" : "")
                + (signer != null ? ", \"signer\": \"" + signer + "\"" : "");
        return CatalogReader.read("t", """
                { "catalog": 1, "apps": [ { "id": "tool", "name": "Tool", "version": "2.0",
                  "installers": [ { "arch": "x64", "type": "msi", "url": "https://example.com/tool-2.0.msi"%s } ] } ] }
                """.formatted(check));
    }

    private List<Engine.Result> run(Catalog catalog, Downloader downloader, SignatureCheck signatures,
                                    ProcessRunner runner) throws Exception {
        List<Step> steps = Planner.plan(catalog.apps(), installed, Arch.X64, false);
        return new Engine(downloader, signatures, runner, () -> List.copyOf(installed), Progress.printing(out), work).run(steps);
    }

    @Test
    void verifiedInstallRunsAndIsConfirmed() throws Exception {
        byte[] content = "msi bytes".getBytes(StandardCharsets.UTF_8);
        Path tmp = Files.write(work.resolve("x"), content);
        Catalog catalog = catalog(Downloader.sha256(tmp), null);

        List<Engine.Result> results = run(catalog, downloads(content), Authenticodes.none(), installs("Tool", "2.0", 0));

        assertThat(results).singleElement().satisfies(r -> {
            assertThat(r.status()).isEqualTo(Engine.Status.INSTALLED);
            assertThat(r.detail()).isEqualTo("installed 2.0");
        });
        assertThat(ran).singleElement().satisfies(cmd -> assertThat(cmd).startsWith("msiexec.exe", "/i")
                .contains("/qn", "/norestart", "/l*v"));
        assertThat(work.resolve("tool/tool-2.0.msi")).doesNotExist();
        assertThat(output.toString(StandardCharsets.UTF_8)).contains("SHA-256 matches the catalog");
    }

    @Test
    void hashMismatchNeverRuns() throws Exception {
        Catalog catalog = catalog("0".repeat(64), null);
        List<Engine.Result> results = run(catalog, downloads(new byte[]{1, 2, 3}), Authenticodes.none(), installs("Tool", "2.0", 0));
        assertThat(results.getFirst().status()).isEqualTo(Engine.Status.FAILED);
        assertThat(results.getFirst().detail()).startsWith("SHA-256 mismatch");
        assertThat(ran).isEmpty();
        assertThat(work.resolve("tool/tool-2.0.msi")).doesNotExist();
    }

    @Test
    void wrongSignerNeverRuns() throws Exception {
        Catalog catalog = catalog(null, "Example Corp");
        SignatureCheck other = file -> new SignatureCheck.Result(true, "CN=Someone Else, O=Someone Else, C=US", "Valid");
        List<Engine.Result> results = run(catalog, downloads(new byte[]{1}), other, installs("Tool", "2.0", 0));
        assertThat(results.getFirst().detail()).isEqualTo("signed by \"CN=Someone Else, O=Someone Else, C=US\", not Example Corp");
        assertThat(ran).isEmpty();

        SignatureCheck broken = file -> new SignatureCheck.Result(false, "CN=Example Corp", "HashMismatch");
        results = run(catalog, downloads(new byte[]{1}), broken, installs("Tool", "2.0", 0));
        assertThat(results.getFirst().detail()).isEqualTo("Authenticode signature isn't valid (HashMismatch)");
        assertThat(ran).isEmpty();
    }

    @Test
    void rightSignerRuns() throws Exception {
        Catalog catalog = catalog(null, "Example Corp");
        SignatureCheck good = file -> new SignatureCheck.Result(true, "CN=Example Corp, O=Example Corp, L=Springfield, C=US", "Valid");
        List<Engine.Result> results = run(catalog, downloads(new byte[]{1}), good, installs("Tool", "2.0", 3010));
        assertThat(results.getFirst().status()).isEqualTo(Engine.Status.INSTALLED);
        assertThat(results.getFirst().rebootNeeded()).isTrue();
    }

    @Test
    void failedInstallerExplainsItsExitCode() throws Exception {
        Catalog catalog = catalog(null, "Example Corp");
        SignatureCheck good = file -> new SignatureCheck.Result(true, "CN=Example Corp", "Valid");
        List<Engine.Result> results = run(catalog, downloads(new byte[]{1}), good, installs("Tool", "2.0", 1618));
        assertThat(results.getFirst().detail())
                .startsWith("the installer exited with code 1618: another installation is already running");
    }

    @Test
    void upToDateIsSkippedWithoutDownloading() throws Exception {
        installed.add(new InstalledApp("Tool", "2.0"));
        Downloader never = new Downloader("test") {
            @Override
            public Download fetch(URI url, Path target, Listener listener) {
                throw new AssertionError("downloaded");
            }
        };
        List<Engine.Result> results = run(catalog(null, "Example Corp"), never, Authenticodes.none(), installs("Tool", "2.0", 0));
        assertThat(results.getFirst().status()).isEqualTo(Engine.Status.SKIPPED);
        assertThat(results.getFirst().detail()).isEqualTo("up to date");
    }

    @Test
    void eventsForTheWindowAndCancelling() throws Exception {
        byte[] content = "msi bytes".getBytes(StandardCharsets.UTF_8);
        Path tmp = Files.write(work.resolve("x"), content);
        Catalog catalog = catalog(Downloader.sha256(tmp), null);
        List<String> events = new ArrayList<>();
        Progress recorder = new Progress() {
            @Override public void line(String text) { }
            @Override public void phase(dev.inicia.catalog.App app, Phase phase) { events.add(phase.name()); }
            @Override public void downloaded(dev.inicia.catalog.App app, long bytes, long total) { events.add(bytes + "/" + total); }
            @Override public void finished(Engine.Result result) { events.add(result.status() + " " + result.detail()); }
        };
        List<Step> steps = Planner.plan(catalog.apps(), installed, Arch.X64, false);
        new Engine(downloads(content), Authenticodes.none(), installs("Tool", "2.0", 0), () -> List.copyOf(installed), recorder, work)
                .run(steps);
        assertThat(events).containsExactly("DOWNLOADING", "9/9", "CHECKING", "INSTALLING", "INSTALLED installed 2.0");

        events.clear();
        installed.clear();
        Engine engine = new Engine(downloads(content), Authenticodes.none(), installs("Tool", "2.0", 0),
                () -> List.copyOf(installed), recorder, work);
        engine.cancel();
        engine.run(steps);
        assertThat(events).containsExactly("SKIPPED cancelled");
    }

    @Test
    void anInstallerThatRefusesAdminRightsRunsAsTheUser() throws Exception {
        byte[] content = "exe".getBytes(StandardCharsets.UTF_8);
        Catalog catalog = CatalogReader.read("t", """
                { "catalog": 1, "apps": [ { "id": "tool", "name": "Tool", "version": "2.0",
                  "installers": [ { "arch": "x64", "type": "exe", "url": "https://example.com/tool.exe",
                    "signer": "Example Corp", "args": ["/silent"], "runAs": "user" } ] } ] }
                """);
        List<String> how = new ArrayList<>();
        ProcessRunner runner = new ProcessRunner() {
            @Override public int run(List<String> command, java.time.Duration t) { how.add("admin"); return 0; }
            @Override public int runAsUser(List<String> command, java.time.Duration t) {
                how.add("user " + command.getLast());
                installed.add(new InstalledApp("Tool", "2.0"));
                return 0;
            }
        };
        SignatureCheck good = file -> new SignatureCheck.Result(true, "CN=Example Corp", "Valid");
        List<Engine.Result> results = run(catalog, downloads(content), good, runner);
        assertThat(how).containsExactly("user /silent");
        assertThat(results.getFirst().status()).isEqualTo(Engine.Status.INSTALLED);
        assertThat(output.toString(StandardCharsets.UTF_8)).contains("installing (exe, silent, as the signed-in user)");
    }

    @Test
    void sizes() {
        assertThat(Engine.size(512 * 1024)).isEqualTo("512 KB");
        assertThat(Engine.size(1_659_704)).isEqualTo("1.6 MB");
    }

    /** Signature checks for tests that don't use a signer. */
    static final class Authenticodes {
        static SignatureCheck none() {
            return file -> {
                throw new AssertionError("no signer in this catalog, so no signature check");
            };
        }
    }
}
