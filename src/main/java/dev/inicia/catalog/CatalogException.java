package dev.inicia.catalog;

import java.util.List;

/** A catalog that can't be read, with every problem found, each naming where it is. */
public final class CatalogException extends Exception {

    private final List<String> problems;

    public CatalogException(String source, List<String> problems) {
        super(source + ": " + String.join("; ", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}
