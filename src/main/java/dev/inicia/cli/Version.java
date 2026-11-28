package dev.inicia.cli;

import picocli.CommandLine;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Properties;

public final class Version implements CommandLine.IVersionProvider {

    public static String get() {
        return properties().getProperty("version", "dev");
    }

    /** When this inicia was built, and its catalog with it; the epoch when unknown. */
    static Instant built() {
        try {
            return Instant.parse(properties().getProperty("built", ""));
        } catch (DateTimeParseException e) {
            return Instant.EPOCH;
        }
    }

    private static Properties properties() {
        Properties p = new Properties();
        try (InputStream in = Version.class.getResourceAsStream("/dev/inicia/inicia-version.properties")) {
            if (in != null) {
                p.load(in);
            }
        } catch (IOException e) {
            // an empty set: "dev", built at the epoch
        }
        return p;
    }

    @Override
    public String[] getVersion() {
        return new String[]{"inicia " + get()};
    }
}
