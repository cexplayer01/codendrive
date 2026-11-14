package com.james.codendrive;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * v1.0.4 accept-as-written policy — pure logic, no Android imports,
 * so the plain main() harness in tests/AcceptAsWrittenTests.java can
 * exercise it (same pattern as SendPlan / ClipScan). Ported from
 * James Keyboard v3.4.6, where the feature was built first.
 *
 * James's ruling (2026-11-14): "Sometimes we need to spell something
 * that's close to a snap to name we need to have the option to accept
 * as written." This policy changes NOTHING about what the correction
 * engine snaps or decodes — it only decides, after a correction has
 * been applied, which as-written options the suggestion strip offers,
 * and what accepting one produces.
 *
 * An Option carries the user's LITERAL ORIGINAL text and the span of
 * the corrected segment it replaces. Applying an option is a pure
 * span substitution, so the restored text is the original byte-exact
 * (casing included).
 *
 * Rules:
 *  - A word/segment that the engine left byte-identical gets NO
 *    option. Case-ONLY differences (the engine's casing layer,
 *    sentence-start capitalization) get NO option either — alignment
 *    below matches tokens case-insensitively, so a casing change
 *    never even forms a changed region.
 *  - Token alignment is LCS over word tokens. Unchanged tokens are
 *    the anchors that delimit changed regions: a maximal run of
 *    unmatched tokens on both sides forms one changed region. (So a
 *    segment in which EVERY token changed forms a single region and
 *    yields one whole-segment option — accepting it restores exactly
 *    what the user said, byte-exact.) Within a region:
 *      - equal-length runs pair up token-by-token (each changed
 *        token gets its own option);
 *      - unequal runs (a decode phrase merging "super base" into
 *        "Supabase", or a split) form ONE option whose original is
 *        the whole raw run, spaces included;
 *      - a run that is empty on one side (the engine purely inserted
 *        or deleted a token — e.g. a spoken symbol word becoming
 *        punctuation) gets NO option: there is no corrected span to
 *        swap the literal back into.
 *  - At most MAX_OPTIONS options per segment — the strip has three
 *    slots; the FIRST changed regions win.
 */
public final class AcceptAsWritten {

    /** Strip slots available for as-written options. */
    public static final int MAX_OPTIONS = 3;

    /** One as-written choice: restore {@link #original} in place of
     *  the corrected segment's span [{@link #start}, {@link #end}). */
    public static final class Option {
        /** The user's literal text, byte-exact as produced. */
        public final String original;
        /** The corrected text this option replaces (for display /
         *  verification; the span below is authoritative). */
        public final String applied;
        /** Span start (inclusive) in the corrected segment. */
        public final int start;
        /** Span end (exclusive) in the corrected segment. */
        public final int end;

        public Option(String original, String applied, int start, int end) {
            this.original = original;
            this.applied = applied;
            this.start = start;
            this.end = end;
        }
    }

    private AcceptAsWritten() { }

    /**
     * Typed-path decision for one word: the engine's applied form vs
     * the word the user actually typed. Returns the as-written option
     * when they differ, null when the correction changed nothing (or
     * either side is missing). The option's span covers the whole
     * applied word.
     */
    public static Option forWord(String original, String applied) {
        if (original == null || applied == null) return null;
        if (original.equals(applied)) return null;
        return new Option(original, applied, 0, applied.length());
    }

    /**
     * Segment decision: the user's raw text vs an engine-corrected
     * text. Returns the as-written options in segment order
     * (possibly empty, at most MAX_OPTIONS).
     */
    public static List<Option> forSegment(String raw, String corrected) {
        List<Option> out = new ArrayList<>();
        if (raw == null || corrected == null) return out;
        if (raw.equals(corrected)) return out;
        List<int[]> rawToks = tokens(raw);
        List<int[]> corToks = tokens(corrected);
        if (rawToks.isEmpty() || corToks.isEmpty()) return out;

        // LCS lengths over lowercased token text.
        int n = rawToks.size(), m = corToks.size();
        int[][] dp = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                dp[i][j] = tokText(raw, rawToks.get(i))
                        .equals(tokText(corrected, corToks.get(j)))
                        ? dp[i + 1][j + 1] + 1
                        : Math.max(dp[i + 1][j], dp[i][j + 1]);
            }
        }
        // Walk the alignment; group consecutive non-match steps into
        // one changed region (raw run + corrected run).
        int i = 0, j = 0;
        while (i < n || j < m) {
            if (i < n && j < m
                    && tokText(raw, rawToks.get(i))
                            .equals(tokText(corrected, corToks.get(j)))) {
                i++; j++; // matched token — no change
                continue;
            }
            int rawFrom = i, corFrom = j;
            while (i < n || j < m) {
                if (i < n && j < m
                        && tokText(raw, rawToks.get(i))
                                .equals(tokText(corrected, corToks.get(j)))) {
                    break; // region ends at the next match
                }
                if (j < m && (i >= n || dp[i][j + 1] >= dp[i + 1][j])) {
                    j++; // corrected-only token in this region
                } else if (i < n) {
                    i++; // raw-only token in this region
                }
            }
            addRegion(out, raw, corrected,
                    rawToks, rawFrom, i, corToks, corFrom, j);
            if (out.size() >= MAX_OPTIONS) break;
        }
        return out;
    }

    /** Turn one changed region into option(s), per the class rules. */
    private static void addRegion(List<Option> out, String raw, String corrected,
                                  List<int[]> rawToks, int rawFrom, int rawTo,
                                  List<int[]> corToks, int corFrom, int corTo) {
        int rawCount = rawTo - rawFrom;
        int corCount = corTo - corFrom;
        if (rawCount == 0 || corCount == 0) return; // pure insert/delete
        if (rawCount == corCount) {
            // Pairwise: each changed token gets its own option.
            for (int k = 0; k < rawCount && out.size() < MAX_OPTIONS; k++) {
                int[] rt = rawToks.get(rawFrom + k);
                int[] ct = corToks.get(corFrom + k);
                String original = raw.substring(rt[0], rt[1]);
                String applied = corrected.substring(ct[0], ct[1]);
                if (!original.equals(applied)) {
                    out.add(new Option(original, applied, ct[0], ct[1]));
                }
            }
        } else {
            // Merge/split: one option carrying the whole raw run.
            int[] rFirst = rawToks.get(rawFrom);
            int[] rLast = rawToks.get(rawTo - 1);
            int[] cFirst = corToks.get(corFrom);
            int[] cLast = corToks.get(corTo - 1);
            String original = raw.substring(rFirst[0], rLast[1]);
            String applied = corrected.substring(cFirst[0], cLast[1]);
            if (!original.equals(applied)) {
                out.add(new Option(original, applied, cFirst[0], cLast[1]));
            }
        }
    }

    /**
     * Apply an option to the corrected segment it was computed from:
     * the option's span is replaced by the literal original. The
     * result holds the user's word byte-exact; everything outside
     * the span (punctuation included) is untouched. An option that
     * does not fit the segment is ignored (segment returned as-is).
     */
    public static String apply(String corrected, Option option) {
        if (corrected == null || option == null) return corrected;
        if (option.start < 0 || option.end < option.start
                || option.end > corrected.length()) {
            return corrected;
        }
        return corrected.substring(0, option.start)
                + option.original
                + corrected.substring(option.end);
    }

    // ---------------- tokenization ----------------

    /** Word-token spans ([start, end)) of s. Word characters mirror
     *  the service's scans: letters/digits plus internal ' - ’. */
    private static List<int[]> tokens(String s) {
        List<int[]> out = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            if (isWordChar(s.charAt(i))) {
                int start = i;
                while (i < s.length() && isWordChar(s.charAt(i))) i++;
                out.add(new int[]{start, i});
            } else {
                i++;
            }
        }
        return out;
    }

    private static String tokText(String s, int[] span) {
        return s.substring(span[0], span[1]).toLowerCase(Locale.US);
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c)
                || c == '\'' || c == '-' || c == '’';
    }
}
