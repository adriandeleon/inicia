package dev.inicia.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * Compares version strings the way Windows' installed-programs list writes them: dotted numbers,
 * sometimes with letters ({@code 3.0.21}, {@code 8.9.9.1}, {@code 26.06}, {@code 0.85}).
 * Numbers compare as numbers, missing trailing parts count as zero ({@code 3.0.21 = 3.0.21.0}),
 * a number sorts after text in the same place ({@code 1.0.1 > 1.0-beta}), and text against a
 * missing part is skipped.
 */
public final class Versions {

    private Versions() {
    }

    public static int compare(String a, String b) {
        List<String> x = parts(a);
        List<String> y = parts(b);
        int n = Math.max(x.size(), y.size());
        for (int i = 0; i < n; i++) {
            String p = i < x.size() ? x.get(i) : null;
            String q = i < y.size() ? y.get(i) : null;
            // text where the other has run out says nothing either way: Git's 2.45.2.windows.1
            // is 2.45.2 plus a build number, and 1.0-beta counts as 1.0
            if ((p == null && !isNumber(q)) || (q == null && !isNumber(p))) {
                continue;
            }
            int c = comparePart(p == null ? "0" : p, q == null ? "0" : q);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    private static int comparePart(String p, String q) {
        boolean pn = isNumber(p);
        boolean qn = isNumber(q);
        if (pn && qn) {
            String ps = p.replaceFirst("^0+(?=.)", "");
            String qs = q.replaceFirst("^0+(?=.)", "");
            return ps.length() != qs.length() ? Integer.compare(ps.length(), qs.length()) : ps.compareTo(qs);
        }
        if (pn != qn) {
            // a number sorts after text: 1.0 > 1.0-beta's "beta"
            return pn ? 1 : -1;
        }
        return p.compareToIgnoreCase(q);
    }

    private static boolean isNumber(String s) {
        return !s.isEmpty() && s.chars().allMatch(Character::isDigit);
    }

    private static List<String> parts(String v) {
        List<String> parts = new ArrayList<>();
        for (String p : v.strip().split("[^A-Za-z0-9]+")) {
            if (!p.isEmpty()) {
                // "21b" → "21", "b": digits and letters are separate parts
                for (String q : p.split("(?<=\\d)(?=\\D)|(?<=\\D)(?=\\d)")) {
                    parts.add(q);
                }
            }
        }
        while (parts.size() > 1 && parts.getLast().equals("0")) {
            parts.removeLast();
        }
        return parts;
    }
}
