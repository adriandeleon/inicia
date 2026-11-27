package dev.inicia.catalog;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * One app in a catalog.
 *
 * @param id          lowercase id used on the command line and in download names ({@code 7zip})
 * @param name        display name ({@code 7-Zip})
 * @param category    group on the picker page ({@code Utilities})
 * @param description one line for the picker page, may be empty
 * @param homepage    the publisher's page, may be null
 * @param version     the version the installers install; empty when they always fetch the latest,
 *                    in which case an installed copy is left alone rather than upgraded
 * @param detect      matches the app's DisplayName in Windows' list of installed programs
 * @param installers  one per architecture
 */
public record App(String id, String name, String category, String description, String homepage,
                  Optional<String> version, Pattern detect, List<Installer> installers) {

    public App {
        installers = List.copyOf(installers);
    }

    /** The installer to use on a machine of the given architecture, if any can run there. */
    public Optional<Installer> installerFor(Arch machine) {
        for (Arch candidate : machine.runs()) {
            for (Installer installer : installers) {
                if (installer.arch() == candidate) {
                    return Optional.of(installer);
                }
            }
        }
        return Optional.empty();
    }
}
