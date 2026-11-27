package dev.inicia.cli;

import picocli.CommandLine;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public final class Version implements CommandLine.IVersionProvider {

    public static String get() {
        try (InputStream in = Version.class.getResourceAsStream("/dev/inicia/inicia-version.properties")) {
            Properties p = new Properties();
            if (in != null) {
                p.load(in);
            }
            return p.getProperty("version", "dev");
        } catch (IOException e) {
            return "dev";
        }
    }

    @Override
    public String[] getVersion() {
        return new String[]{"inicia " + get()};
    }
}
