package dev.inicia.engine;

/**
 * An entry in Windows' list of installed programs (the registry's Uninstall keys).
 *
 * @param displayName what Settings > Apps shows
 * @param version     DisplayVersion, may be empty
 */
public record InstalledApp(String displayName, String version) {
}
