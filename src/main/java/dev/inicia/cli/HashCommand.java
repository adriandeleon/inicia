package dev.inicia.cli;

import dev.inicia.engine.Downloader;
import dev.inicia.engine.SignatureCheck;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.Callable;

@Command(name = "hash", mixinStandardHelpOptions = true,
        description = {
                "Prints an installer's SHA-256 and (on Windows) its signer, with a catalog entry to",
                "start from: the quickest way to add an installer of your own."})
final class HashCommand implements Callable<Integer> {

    @Parameters(paramLabel = "<file|url>", description = "A local installer, or its https:// download address.")
    String source;

    @Override
    public Integer call() throws Exception {
        Path file;
        Path tmpDir = null;
        String url;
        if (source.startsWith("https://")) {
            tmpDir = Files.createTempDirectory("inicia-hash-");
            URI uri = URI.create(source);
            String name = Path.of(uri.getPath()).getFileName() == null ? "download" : Path.of(uri.getPath()).getFileName().toString();
            file = tmpDir.resolve(name.isBlank() ? "download" : name);
            new Downloader("inicia/" + Version.get()).fetch(uri, file);
            url = source;
        } else {
            file = Path.of(source);
            url = "https://…/" + file.getFileName();
        }
        try {
            String sha256 = Downloader.sha256(file);
            String signer = null;
            System.out.println("sha256  " + sha256);
            if (Platform.isWindows()) {
                SignatureCheck.Result sig = Platform.signatures().check(file);
                System.out.println("signature  " + sig.status() + (sig.subject().isEmpty() ? "" : ": " + sig.subject()));
                if (sig.valid()) {
                    signer = commonName(sig.subject());
                }
            }
            String lower = file.getFileName().toString().toLowerCase(Locale.ROOT);
            String type = lower.endsWith(".msi") ? "msi" : "nsis";
            System.out.println();
            System.out.println("{ \"arch\": \"x64\", \"type\": \"" + type + "\",");
            System.out.println("  \"url\": \"" + url + "\",");
            System.out.println("  \"sha256\": \"" + sha256 + "\"" + (signer != null ? "," : ""));
            if (signer != null) {
                System.out.println("  \"signer\": \"" + signer + "\"");
            }
            System.out.println("}");
            if (!type.equals("msi")) {
                System.out.println("Check the type: nsis takes /S, inno /VERYSILENT; anything else is exe, with \"args\".");
            }
            return 0;
        } finally {
            if (tmpDir != null) {
                Files.deleteIfExists(file);
                Files.deleteIfExists(tmpDir);
            }
        }
    }

    private static String commonName(String subject) {
        for (String part : subject.split(",(?=\\s*[A-Za-z]+=)")) {
            String p = part.strip();
            if (p.regionMatches(true, 0, "CN=", 0, 3)) {
                return p.substring(3).replaceAll("^\"|\"$", "");
            }
        }
        return null;
    }
}
