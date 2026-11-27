package dev.inicia.engine;

import dev.inicia.catalog.App;
import dev.inicia.catalog.Installer;

import java.util.Optional;

/**
 * What inicia does with one selected app.
 *
 * @param installed the version found installed, if the app is installed
 */
public record Step(App app, Action action, Optional<Installer> installer, Optional<String> installed) {

    public enum Action {
        INSTALL("install"),
        UPGRADE("upgrade"),
        REINSTALL("reinstall"),
        /** installed at the catalog's version or newer */
        UP_TO_DATE("up to date"),
        /** installed, and the catalog fetches "latest", so there's no version to compare with */
        PRESENT("already installed"),
        /** no installer for this machine's architecture */
        NO_INSTALLER("no installer for this machine");

        private final String label;

        Action(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public boolean runs() {
            return this == INSTALL || this == UPGRADE || this == REINSTALL;
        }
    }
}
