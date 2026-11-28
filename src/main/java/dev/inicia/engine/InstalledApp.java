package dev.inicia.engine;

/**
 * An entry in Windows' list of installed programs (the registry's Uninstall keys).
 *
 * @param displayName what Settings > Apps shows
 * @param version     DisplayVersion, may be empty
 * @param location    the folder it's installed in (InstallLocation, else the folder of its icon's
 *                    exe), to find its programs running; may be empty
 */
public record InstalledApp(String displayName, String version, String location) {

    public InstalledApp(String displayName, String version) {
        this(displayName, version, "");
    }
}
