package dev.inicia.catalog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Reads a catalog file (JSON, format 1). Every problem is collected, each with its place in the
 * file ({@code apps[2].installers[0].sha256}), so one run shows all there is to fix.
 */
public final class CatalogReader {

    public static final int FORMAT = 1;
    public static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,39}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> TOP_KEYS = Set.of("catalog", "apps");
    private static final Set<String> APP_KEYS = Set.of(
            "id", "name", "category", "description", "homepage", "version", "detect", "installers",
            "note", "requires", "suite", "released", "license");
    private static final Set<String> DETECT_KEYS = Set.of("displayName");
    private static final Set<String> INSTALLER_KEYS = Set.of("arch", "type", "url", "sha256", "signer", "args", "size", "runAs");

    private static final ObjectMapper JSON = new ObjectMapper();

    private final List<String> problems = new ArrayList<>();

    private CatalogReader() {
    }

    /** The catalog built into inicia. */
    public static Catalog builtIn() {
        try (InputStream in = CatalogReader.class.getResourceAsStream("/dev/inicia/catalog.json")) {
            if (in == null) {
                throw new IllegalStateException("the built-in catalog is missing");
            }
            return read("built-in catalog", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException | CatalogException e) {
            throw new IllegalStateException("the built-in catalog can't be read: " + e.getMessage(), e);
        }
    }

    public static Catalog read(Path file) throws IOException, CatalogException {
        return read(file.toString(), Files.readString(file));
    }

    public static Catalog read(String source, String json) throws CatalogException {
        // Windows PowerShell 5.1's "UTF8", and some editors, start the file with a byte-order mark
        if (json.startsWith("﻿")) {
            json = json.substring(1);
        }
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (JsonProcessingException e) {
            throw new CatalogException(source, List.of("not valid JSON: " + e.getOriginalMessage()
                    + " (line " + e.getLocation().getLineNr() + ")"));
        }
        CatalogReader reader = new CatalogReader();
        List<App> apps = reader.catalog(root);
        if (!reader.problems.isEmpty()) {
            throw new CatalogException(source, reader.problems);
        }
        return new Catalog(apps);
    }

    private List<App> catalog(JsonNode root) {
        List<App> apps = new ArrayList<>();
        if (root == null || !root.isObject()) {
            problems.add("the file must hold a JSON object");
            return apps;
        }
        unknownKeys(root, TOP_KEYS, "");
        JsonNode format = root.get("catalog");
        if (format == null || !format.isInt() || format.intValue() != FORMAT) {
            problems.add("catalog: must be " + FORMAT + " (the catalog format this inicia reads)");
        }
        JsonNode list = root.get("apps");
        if (list == null || !list.isArray()) {
            problems.add("apps: must be a list");
            return apps;
        }
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < list.size(); i++) {
            String at = "apps[" + i + "]";
            App app = app(list.get(i), at);
            if (app != null) {
                if (!ids.add(app.id())) {
                    problems.add(at + ".id: " + app.id() + " appears more than once");
                }
                apps.add(app);
            }
        }
        return apps;
    }

    private App app(JsonNode node, String at) {
        if (!node.isObject()) {
            problems.add(at + ": must be an object");
            return null;
        }
        int before = problems.size();
        unknownKeys(node, APP_KEYS, at);
        String id = text(node, "id", at, true);
        if (id != null && !ID.matcher(id).matches()) {
            problems.add(at + ".id: \"" + id + "\" must be lowercase letters, digits and hyphens, at most 40");
        } else if (id != null && Catalog.isSelector(id)) {
            problems.add(at + ".id: \"" + id + "\" is reserved: \"all\", \"cat-…\" and \"suite-…\" select groups of apps");
        }
        String name = text(node, "name", at, true);
        String category = Optional.ofNullable(text(node, "category", at, false)).orElse("Other");
        String description = Optional.ofNullable(text(node, "description", at, false)).orElse("");
        String homepage = text(node, "homepage", at, false);
        if (homepage != null) {
            https(homepage, at + ".homepage");
        }
        Optional<String> version = Optional.ofNullable(text(node, "version", at, false));
        Pattern detect = detect(node.get("detect"), at + ".detect", name);
        Optional<String> note = Optional.ofNullable(text(node, "note", at, false));
        Optional<String> suite = Optional.ofNullable(text(node, "suite", at, false));
        Optional<String> license = Optional.ofNullable(text(node, "license", at, false));
        Optional<java.time.LocalDate> released = Optional.empty();
        String releasedText = text(node, "released", at, false);
        if (releasedText != null) {
            try {
                released = Optional.of(java.time.LocalDate.parse(releasedText));
            } catch (java.time.format.DateTimeParseException e) {
                problems.add(at + ".released: \"" + releasedText + "\" must be a date, YYYY-MM-DD");
            }
        }
        List<String> requires = new ArrayList<>();
        JsonNode requiresNode = node.get("requires");
        if (requiresNode != null) {
            if (!requiresNode.isArray()) {
                problems.add(at + ".requires: must be a list of app ids");
            } else {
                for (JsonNode r : requiresNode) {
                    if (r.isTextual() && ID.matcher(r.textValue()).matches() && !r.textValue().equals(id)) {
                        requires.add(r.textValue());
                    } else {
                        problems.add(at + ".requires: " + r + " isn't another app's id");
                    }
                }
            }
        }

        List<Installer> installers = new ArrayList<>();
        JsonNode list = node.get("installers");
        if (list == null || !list.isArray() || list.isEmpty()) {
            problems.add(at + ".installers: must be a list with at least one installer");
        } else {
            Set<Arch> arches = new HashSet<>();
            for (int i = 0; i < list.size(); i++) {
                String iat = at + ".installers[" + i + "]";
                Installer installer = installer(list.get(i), iat);
                if (installer != null) {
                    if (!arches.add(installer.arch())) {
                        problems.add(iat + ".arch: a second " + installer.arch().id() + " installer");
                    }
                    installers.add(installer);
                }
            }
        }
        if (problems.size() > before) {
            return null;
        }
        return new App(id, name, category, description, homepage, version, detect, installers, note, requires, suite, released, license);
    }

    private Pattern detect(JsonNode node, String at, String name) {
        if (node == null) {
            // by default the display name starts with the app's name: "7-Zip 26.06 (x64)". Not \b
            // after it: "Notepad++ (64-bit x64)" has no word boundary between "+" and " "
            return name == null ? null : Pattern.compile("^" + Pattern.quote(name) + "(?!\\w)");
        }
        if (!node.isObject()) {
            problems.add(at + ": must be an object");
            return null;
        }
        unknownKeys(node, DETECT_KEYS, at);
        String regex = text(node, "displayName", at, true);
        if (regex == null) {
            return null;
        }
        try {
            return Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            problems.add(at + ".displayName: not a valid regular expression: " + e.getDescription());
            return null;
        }
    }

    private Installer installer(JsonNode node, String at) {
        if (!node.isObject()) {
            problems.add(at + ": must be an object");
            return null;
        }
        int before = problems.size();
        unknownKeys(node, INSTALLER_KEYS, at);

        String archText = text(node, "arch", at, true);
        Arch arch = null;
        if (archText != null) {
            arch = Arch.parse(archText).orElse(null);
            if (arch == null) {
                problems.add(at + ".arch: \"" + archText + "\" isn't x64, x86 or arm64");
            }
        }
        String typeText = text(node, "type", at, true);
        InstallerType type = null;
        if (typeText != null) {
            type = InstallerType.parse(typeText).orElse(null);
            if (type == null) {
                problems.add(at + ".type: \"" + typeText + "\" isn't msi, inno, nsis or exe");
            }
        }
        String urlText = text(node, "url", at, true);
        URI url = urlText == null ? null : https(urlText, at + ".url");

        Optional<String> sha256 = Optional.ofNullable(text(node, "sha256", at, false))
                .map(s -> s.toLowerCase(Locale.ROOT));
        if (sha256.isPresent() && !SHA256.matcher(sha256.get()).matches()) {
            problems.add(at + ".sha256: must be 64 hexadecimal characters");
        }
        Optional<String> signer = Optional.ofNullable(text(node, "signer", at, false));
        boolean asUser = false;
        String runAs = text(node, "runAs", at, false);
        if (runAs != null) {
            if (runAs.equals("user")) {
                asUser = true;
            } else {
                problems.add(at + ".runAs: \"" + runAs + "\" isn't user, the only other way to run an installer");
            }
        }
        java.util.OptionalLong size = java.util.OptionalLong.empty();
        JsonNode sizeNode = node.get("size");
        if (sizeNode != null) {
            if (sizeNode.canConvertToLong() && sizeNode.isIntegralNumber() && sizeNode.longValue() > 0) {
                size = java.util.OptionalLong.of(sizeNode.longValue());
            } else {
                problems.add(at + ".size: must be the file's size in bytes, a whole number above 0");
            }
        }
        if (sha256.isEmpty() && signer.isEmpty()) {
            problems.add(at + ": needs sha256, signer or both, so the download can be checked");
        }

        List<String> args = new ArrayList<>();
        JsonNode argsNode = node.get("args");
        if (argsNode != null) {
            if (!argsNode.isArray()) {
                problems.add(at + ".args: must be a list of strings");
            } else {
                for (JsonNode a : argsNode) {
                    if (a.isTextual()) {
                        args.add(a.textValue());
                    } else {
                        problems.add(at + ".args: must be a list of strings");
                        break;
                    }
                }
            }
        }
        if (type == InstallerType.EXE && args.isEmpty()) {
            problems.add(at + ".args: an exe installer needs the arguments that make it silent");
        }
        if (problems.size() > before) {
            return null;
        }
        return new Installer(arch, type, url, sha256, signer, args, size, asUser);
    }

    private String text(JsonNode node, String key, String at, boolean required) {
        JsonNode value = node.get(key);
        if (value == null || value.isNull()) {
            if (required) {
                problems.add(at + "." + key + ": missing");
            }
            return null;
        }
        if (!value.isTextual() || value.textValue().isBlank()) {
            problems.add(at + "." + key + ": must be a non-empty string");
            return null;
        }
        return value.textValue().strip();
    }

    private URI https(String text, String at) {
        try {
            URI uri = new URI(text);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                problems.add(at + ": must be an https:// address");
                return null;
            }
            return uri;
        } catch (URISyntaxException e) {
            problems.add(at + ": not a valid address: " + e.getReason());
            return null;
        }
    }

    private void unknownKeys(JsonNode node, Set<String> known, String at) {
        for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
            String key = it.next();
            if (!known.contains(key)) {
                problems.add((at.isEmpty() ? "" : at + ".") + key + ": unknown key (known: "
                        + String.join(", ", known.stream().sorted().toList()) + ")");
            }
        }
    }
}
