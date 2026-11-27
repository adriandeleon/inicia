package dev.inicia.engine;

import dev.inicia.catalog.App;
import dev.inicia.catalog.Arch;
import dev.inicia.catalog.Catalog;
import dev.inicia.catalog.CatalogReader;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlannerTest {

    private final Catalog catalog = CatalogReader.builtIn();
    private final App sevenZip = catalog.app("7zip").orElseThrow();
    private final App npp = catalog.app("notepad-plus-plus").orElseThrow();
    private final App firefox = catalog.app("firefox").orElseThrow();

    @Test
    void installUpgradeOrLeave() {
        List<InstalledApp> installed = List.of(
                new InstalledApp("7-Zip 25.01 (x64)", "25.01"),
                new InstalledApp("Notepad++ (64-bit x64)", "8.9.9.1"),
                new InstalledApp("Mozilla Firefox (x64 en-US)", "150.0"));
        List<Step> steps = Planner.plan(List.of(sevenZip, npp, firefox, catalog.app("vlc").orElseThrow()),
                installed, Arch.X64, false);
        assertThat(steps).extracting(Step::action).containsExactly(
                Step.Action.UPGRADE, Step.Action.UP_TO_DATE, Step.Action.PRESENT, Step.Action.INSTALL);
        assertThat(steps.getFirst().installed()).contains("25.01");
    }

    @Test
    void forceReinstallsWhatsThere() {
        List<Step> steps = Planner.plan(List.of(npp, firefox),
                List.of(new InstalledApp("Notepad++ (64-bit x64)", "8.9.9.1")), Arch.X64, true);
        assertThat(steps).extracting(Step::action).containsExactly(Step.Action.REINSTALL, Step.Action.INSTALL);
    }

    @Test
    void newerInstalledIsUpToDate() {
        Step step = Planner.step(sevenZip, List.of(new InstalledApp("7-Zip 27.00 (x64)", "27.00")), Arch.X64, false);
        assertThat(step.action()).isEqualTo(Step.Action.UP_TO_DATE);
    }

    @Test
    void highestOfSeveralMatchesCounts() {
        List<InstalledApp> installed = List.of(
                new InstalledApp("7-Zip 9.20", "9.20"),
                new InstalledApp("7-Zip 26.06 (x64)", "26.06"));
        assertThat(Planner.installedVersion(sevenZip, installed)).contains("26.06");
    }

    @Test
    void architectureFallback() {
        // ARM64 has its own 7-Zip, and runs Notepad++'s x64 build under emulation
        assertThat(Planner.step(sevenZip, List.of(), Arch.ARM64, false).installer().orElseThrow().arch()).isEqualTo(Arch.ARM64);
        assertThat(Planner.step(npp, List.of(), Arch.ARM64, false).installer().orElseThrow().arch()).isEqualTo(Arch.X64);
        // a 32-bit Windows can't run either
        assertThat(Planner.step(npp, List.of(), Arch.X86, false).action()).isEqualTo(Step.Action.NO_INSTALLER);
    }
}
