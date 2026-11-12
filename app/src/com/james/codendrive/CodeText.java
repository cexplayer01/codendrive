package com.james.codendrive;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * CodeNDrive's two deterministic dictation passes. Pure Java, no Android
 * dependencies, so both are host-testable (tests/CodeTextBattery.java).
 *
 * These passes exist because a general speech recognizer will never be
 * reliable at two things coders say constantly: spelled identifiers and
 * symbol names. Both passes are deliberately dumb: a fixed table, a
 * fixed spacing rule, no guessing. Deterministic beats helpful here —
 * the same spoken input produces the same output every time.
 *
 * v1.0.3: the mode keys are gone (James: the transforms "should be
 * automatically applied"), so autoApply() below is the entry the
 * keyboard actually calls — spell() and codeSymbols() remain as the
 * two reference passes it combines.
 */
public final class CodeText {

    private CodeText() { }

    // ------------------------------------------------------------------
    // Shared spoken-name tables
    // ------------------------------------------------------------------

    /** Spoken letter name -> the letter. Includes the homophone forms
     *  (eye/oh/you/why) because SPELL mode has no prose to protect. */
    static final Map<String, String> LETTER_NAMES = new HashMap<>();
    static {
        LETTER_NAMES.put("ay", "a");
        LETTER_NAMES.put("bee", "b");
        LETTER_NAMES.put("cee", "c");
        LETTER_NAMES.put("dee", "d");
        LETTER_NAMES.put("ee", "e");
        LETTER_NAMES.put("ef", "f");
        LETTER_NAMES.put("gee", "g");
        LETTER_NAMES.put("aitch", "h");
        LETTER_NAMES.put("eye", "i");
        LETTER_NAMES.put("jay", "j");
        LETTER_NAMES.put("kay", "k");
        LETTER_NAMES.put("el", "l");
        LETTER_NAMES.put("em", "m");
        LETTER_NAMES.put("en", "n");
        LETTER_NAMES.put("oh", "o");
        LETTER_NAMES.put("pee", "p");
        LETTER_NAMES.put("cue", "q");
        LETTER_NAMES.put("ar", "r");
        LETTER_NAMES.put("ess", "s");
        LETTER_NAMES.put("tee", "t");
        LETTER_NAMES.put("you", "u");
        LETTER_NAMES.put("vee", "v");
        LETTER_NAMES.put("ex", "x");
        LETTER_NAMES.put("why", "y");
        LETTER_NAMES.put("zee", "z");
        LETTER_NAMES.put("zed", "z");
    }

    /** Letter names safe to map in CODE mode: the distinctive phonetic
     *  forms only. The homophones (eye/oh/you/why) stay ordinary words
     *  there — code dictation still contains prose-like fragments, and
     *  turning every "you" into "u" would garble them. */
    static final Map<String, String> CODE_LETTER_NAMES = new HashMap<>();
    static {
        for (Map.Entry<String, String> e : LETTER_NAMES.entrySet()) {
            String k = e.getKey();
            if (k.equals("eye") || k.equals("oh") || k.equals("you") || k.equals("why")) continue;
            CODE_LETTER_NAMES.put(k, e.getValue());
        }
    }

    /** Spoken number name -> digits. Shared by both passes. */
    static final Map<String, String> NUMBER_NAMES = new HashMap<>();
    static {
        NUMBER_NAMES.put("zero", "0");
        NUMBER_NAMES.put("one", "1");
        NUMBER_NAMES.put("two", "2");
        NUMBER_NAMES.put("three", "3");
        NUMBER_NAMES.put("four", "4");
        NUMBER_NAMES.put("five", "5");
        NUMBER_NAMES.put("six", "6");
        NUMBER_NAMES.put("seven", "7");
        NUMBER_NAMES.put("eight", "8");
        NUMBER_NAMES.put("nine", "9");
        NUMBER_NAMES.put("ten", "10");
        NUMBER_NAMES.put("eleven", "11");
        NUMBER_NAMES.put("twelve", "12");
        NUMBER_NAMES.put("thirteen", "13");
        NUMBER_NAMES.put("fourteen", "14");
        NUMBER_NAMES.put("fifteen", "15");
        NUMBER_NAMES.put("sixteen", "16");
        NUMBER_NAMES.put("seventeen", "17");
        NUMBER_NAMES.put("eighteen", "18");
        NUMBER_NAMES.put("nineteen", "19");
        NUMBER_NAMES.put("twenty", "20");
        NUMBER_NAMES.put("thirty", "30");
        NUMBER_NAMES.put("forty", "40");
        NUMBER_NAMES.put("fifty", "50");
        NUMBER_NAMES.put("sixty", "60");
        NUMBER_NAMES.put("seventy", "70");
        NUMBER_NAMES.put("eighty", "80");
        NUMBER_NAMES.put("ninety", "90");
    }

    // ------------------------------------------------------------------
    // SPELL mode
    // ------------------------------------------------------------------

    /**
     * Normalize a recognizer result heard in SPELL mode into the exact
     * lowercase string the user spelled. Letter names, single letters,
     * number names, single digits, and the inline identifier symbols
     * (underscore / dash / hyphen / dot) are kept; every other token is
     * DROPPED — in spelling mode an unrecognized token is recognizer
     * noise, and dropping beats guessing a word the user never spelled.
     */
    public static String spell(String raw) {
        if (raw == null) return "";
        String lower = raw.toLowerCase(Locale.US);
        // Split on whitespace and hyphens alike: "double-you" and
        // "double you" are the same spoken letter.
        String[] toks = lower.split("[\\s-]+");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < toks.length; i++) {
            String t = stripTrailPunct(toks[i]);
            if (t.isEmpty()) continue;
            if (t.equals("double") && i + 1 < toks.length) {
                String nxt = stripTrailPunct(toks[i + 1]);
                if (nxt.equals("you") || nxt.equals("u")) {
                    out.append('w');
                    i++;
                    continue;
                }
            }
            String v = LETTER_NAMES.get(t);
            if (v == null) v = NUMBER_NAMES.get(t);
            if (v == null && t.length() == 1) {
                char c = t.charAt(0);
                if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) v = t;
            }
            if (v == null) {
                if (t.equals("underscore")) v = "_";
                else if (t.equals("dash") || t.equals("hyphen")) v = "-";
                else if (t.equals("dot")) v = ".";
            }
            if (v != null) out.append(v);
            // else: unknown token — dropped by design.
        }
        return out.toString();
    }

    // ------------------------------------------------------------------
    // CODE mode
    // ------------------------------------------------------------------

    /** Two-token symbol phrases -> glyph (or control piece). */
    static final Map<String, String> SYMBOL_PHRASES = new HashMap<>();
    static {
        SYMBOL_PHRASES.put("open paren", "(");
        SYMBOL_PHRASES.put("close paren", ")");
        SYMBOL_PHRASES.put("open bracket", "[");
        SYMBOL_PHRASES.put("close bracket", "]");
        SYMBOL_PHRASES.put("open brace", "{");
        SYMBOL_PHRASES.put("close brace", "}");
        SYMBOL_PHRASES.put("fat arrow", "=>");
        SYMBOL_PHRASES.put("at sign", "@");
        SYMBOL_PHRASES.put("dollar sign", "$");
        SYMBOL_PHRASES.put("percent sign", "%");
        SYMBOL_PHRASES.put("question mark", "?");
        SYMBOL_PHRASES.put("single quote", "'");
        SYMBOL_PHRASES.put("exclamation point", "!");
        SYMBOL_PHRASES.put("less than", "<");
        SYMBOL_PHRASES.put("greater than", ">");
    }

    /** One-token symbol names -> glyph (or control piece). */
    static final Map<String, String> SYMBOL_WORDS = new HashMap<>();
    static {
        SYMBOL_WORDS.put("underscore", "_");
        SYMBOL_WORDS.put("dash", "-");
        SYMBOL_WORDS.put("hyphen", "-");
        SYMBOL_WORDS.put("dot", ".");
        SYMBOL_WORDS.put("equals", "=");
        SYMBOL_WORDS.put("plus", "+");
        SYMBOL_WORDS.put("arrow", "->");
        SYMBOL_WORDS.put("colon", ":");
        SYMBOL_WORDS.put("semicolon", ";");
        SYMBOL_WORDS.put("comma", ",");
        SYMBOL_WORDS.put("quote", "\"");
        SYMBOL_WORDS.put("backtick", "`");
        SYMBOL_WORDS.put("slash", "/");
        SYMBOL_WORDS.put("backslash", "\\");
        SYMBOL_WORDS.put("pipe", "|");
        SYMBOL_WORDS.put("ampersand", "&");
        SYMBOL_WORDS.put("asterisk", "*");
        SYMBOL_WORDS.put("hash", "#");
        SYMBOL_WORDS.put("percent", "%");
        SYMBOL_WORDS.put("bang", "!");
        SYMBOL_WORDS.put("tilde", "~");
        SYMBOL_WORDS.put("caret", "^");
    }

    // Piece classes for the spacing rule.
    private static final int P_NONE = 0;
    private static final int P_WORD = 1;   // ordinary passthrough word
    private static final int P_CHAR = 2;   // identifier char run (letter / number name, digit)
    private static final int P_GLYPH = 3;  // symbol glyph — hugs both sides
    private static final int P_BREAK = 4;  // explicit space / newline / tab

    /**
     * Rewrite a recognizer result heard in CODE mode: spoken symbol
     * names ALWAYS become glyphs, never literal words. Spacing is one
     * deterministic rule — glyphs hug their neighbours; identifier-char
     * pieces hug each other; a space appears between two word-ish pieces
     * except between consecutive char pieces; explicit "space",
     * "new line" and "tab" pieces break spacing exactly where spoken.
     * Unmatched words pass through with their recognizer casing.
     */
    public static String codeSymbols(String raw) {
        if (raw == null) return "";
        String[] toks = raw.split("\\s+");
        StringBuilder out = new StringBuilder();
        int prev = P_NONE;
        for (int i = 0; i < toks.length; i++) {
            String rawTok = toks[i];
            if (rawTok.isEmpty()) continue;
            String tok = stripTrailPunct(rawTok);
            if (tok.isEmpty()) continue;
            String lower = tok.toLowerCase(Locale.US);

            // Two-token phrases first (longest match wins).
            if (i + 1 < toks.length) {
                String nxt = stripTrailPunct(toks[i + 1]).toLowerCase(Locale.US);
                String phrase = SYMBOL_PHRASES.get(lower + " " + nxt);
                if (phrase != null) {
                    prev = appendPiece(out, prev, phrase, P_GLYPH);
                    i++;
                    continue;
                }
                if (lower.equals("double") && (nxt.equals("you") || nxt.equals("u"))) {
                    prev = appendPiece(out, prev, "w", P_CHAR);
                    i++;
                    continue;
                }
                if (lower.equals("new") && nxt.equals("line")) {
                    prev = appendPiece(out, prev, "\n", P_BREAK);
                    i++;
                    continue;
                }
            }

            String glyph = SYMBOL_WORDS.get(lower);
            if (glyph != null) {
                prev = appendPiece(out, prev, glyph, P_GLYPH);
                continue;
            }
            if (lower.equals("space")) {
                prev = appendPiece(out, prev, " ", P_BREAK);
                continue;
            }
            if (lower.equals("tab")) {
                prev = appendPiece(out, prev, "\t", P_BREAK);
                continue;
            }
            if (lower.equals("newline")) {
                prev = appendPiece(out, prev, "\n", P_BREAK);
                continue;
            }
            String num = NUMBER_NAMES.get(lower);
            if (num != null) {
                prev = appendPiece(out, prev, num, P_CHAR);
                continue;
            }
            String letter = CODE_LETTER_NAMES.get(lower);
            if (letter != null) {
                prev = appendPiece(out, prev, letter, P_CHAR);
                continue;
            }
            if (tok.length() == 1 && !Character.isLetterOrDigit(tok.charAt(0))) {
                // A glyph the recognizer already produced — keep it a glyph.
                prev = appendPiece(out, prev, tok, P_GLYPH);
                continue;
            }
            if (isAllDigits(tok)) {
                prev = appendPiece(out, prev, tok, P_CHAR);
                continue;
            }
            prev = appendPiece(out, prev, tok, P_WORD);
        }
        return out.toString();
    }

    private static int appendPiece(StringBuilder out, int prev, String piece, int type) {
        if (type == P_BREAK) {
            out.append(piece);
            return P_BREAK;
        }
        boolean space = false;
        if (prev == P_WORD || prev == P_CHAR) {
            if (type == P_WORD) space = true;
            else if (type == P_CHAR && prev == P_WORD) space = true;
        }
        if (space) out.append(' ');
        out.append(piece);
        return type;
    }

    private static boolean isAllDigits(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }

    /** Recognizer sentence decoration (a trailing comma or period glued
     *  to a token) is not code. Strip it before lookup and passthrough. */
    private static String stripTrailPunct(String t) {
        int end = t.length();
        while (end > 0 && (t.charAt(end - 1) == ',' || t.charAt(end - 1) == '.')) end--;
        return t.substring(0, end);
    }

    // ------------------------------------------------------------------
    // Auto-apply (v1.0.3): the combined pass, no modes
    // ------------------------------------------------------------------

    /** Tokens that carry code meaning on their own or as half of a
     *  two-token construct (phrase heads AND tails, the break words).
     *  A token in this set is never treated as spelling noise — "plus"
     *  between two spelled letters is a glyph the user said on
     *  purpose, not a recognizer hiccup. */
    private static final Set<String> CODE_TOKENS = new HashSet<>();
    static {
        CODE_TOKENS.addAll(SYMBOL_WORDS.keySet());
        for (String phrase : SYMBOL_PHRASES.keySet()) {
            for (String w : phrase.split(" ")) CODE_TOKENS.add(w);
        }
        CODE_TOKENS.add("space");
        CODE_TOKENS.add("tab");
        CODE_TOKENS.add("newline");
        CODE_TOKENS.add("new");
        CODE_TOKENS.add("line");
    }

    /** The value spell() extracts from the token at i, or null when
     *  the token is not spelling material: letter names (homophones
     *  included — inside a spelled run, "you" is the letter u),
     *  number names, single letters/digits, digit runs, the inline
     *  identifier symbols (underscore / dash / hyphen / dot), and the
     *  "double you" pair (value "w", consumes two tokens). */
    private static String spellUnit(String[] lower, int i, int[] consume) {
        String t = lower[i];
        consume[0] = 1;
        if (t.equals("double") && i + 1 < lower.length) {
            String nxt = lower[i + 1];
            if (nxt.equals("you") || nxt.equals("u")) {
                consume[0] = 2;
                return "w";
            }
        }
        String v = LETTER_NAMES.get(t);
        if (v == null) v = NUMBER_NAMES.get(t);
        if (v == null && t.length() == 1) {
            char c = t.charAt(0);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) v = t;
        }
        if (v == null && isAllDigits(t)) v = t;
        if (v == null) {
            if (t.equals("underscore")) v = "_";
            else if (t.equals("dash") || t.equals("hyphen")) v = "-";
            else if (t.equals("dot")) v = ".";
        }
        return v;
    }

    /** True when the token is droppable noise INSIDE a spelled run:
     *  it means nothing to either pass. Plain prose words qualify;
     *  anything with code meaning (CODE_TOKENS), a literal glyph
     *  character, or spelling material does not. The drop rule is
     *  spell()'s own — never guess a word the user didn't spell —
     *  scoped to where a spelling is demonstrably in progress. */
    private static boolean isRunNoise(String[] lower, int i, int[] scratch) {
        String t = lower[i];
        if (spellUnit(lower, i, scratch) != null) return false;
        if (CODE_TOKENS.contains(t)) return false;
        if (t.length() == 1 && !Character.isLetterOrDigit(t.charAt(0))) return false;
        return true;
    }

    /**
     * The v1.0.3 dictation pass: spell() and codeSymbols() as one
     * deterministic transform, applied to every voice result.
     *
     * The token stream is walked once. At each position, a SPELL RUN
     * is tried first (spell runs first where they overlap): two or
     * more consecutive spelling units — letter names, number names,
     * identifier symbols, double-you — collapse into one hugging
     * identifier piece, exactly spell()'s output for that stretch. A
     * single droppable noise token inside a run (recognizer junk
     * between spelled letters) is dropped, never guessed; a token
     * with code meaning always breaks the run instead. Anything that
     * is not a run goes through codeSymbols()' classification
     * unchanged: symbol phrases and words become glyphs that hug
     * their neighbours, number and distinctive letter names become
     * identifier chars, everything else passes through as a word with
     * its recognizer casing — so ordinary prose ("hello world this
     * is a test") comes out byte-identical.
     *
     * A LONE spelling unit is not a run: it falls to the code
     * classification, which is why a stray "you" / "oh" / "why" in
     * prose stays a word (the homophones are excluded there), while
     * "you" inside "ess you pee" is the letter u.
     */
    public static String autoApply(String raw) {
        if (raw == null) return "";
        // "double-you" and "double you" are the same spoken letter
        // (spell() splits on hyphens; do the equivalent here without
        // touching hyphens that belong to ordinary words).
        String norm = raw.replaceAll("(?i)double-you", "double you");
        String[] parts = norm.split("\\s+");
        List<String> toksL = new ArrayList<>();
        List<String> lowerL = new ArrayList<>();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            String t = stripTrailPunct(part);
            if (t.isEmpty()) continue;
            toksL.add(t);
            lowerL.add(t.toLowerCase(Locale.US));
        }
        String[] toks = toksL.toArray(new String[0]);
        String[] lower = lowerL.toArray(new String[0]);
        int n = toks.length;
        StringBuilder out = new StringBuilder();
        int prev = P_NONE;
        int[] consume = new int[1];
        int i = 0;
        while (i < n) {
            // 1) A spell run starting here?
            if (spellUnit(lower, i, consume) != null) {
                StringBuilder sb = new StringBuilder();
                int units = 0;
                int j = i;
                int[] c2 = new int[1];
                while (j < n) {
                    String v = spellUnit(lower, j, c2);
                    if (v != null) {
                        sb.append(v);
                        units++;
                        j += c2[0];
                        continue;
                    }
                    // One noise token may sit inside a run when a
                    // unit follows it — dropped, never guessed.
                    if (j + 1 < n && isRunNoise(lower, j, c2)
                            && spellUnit(lower, j + 1, c2) != null) {
                        j++;
                        continue;
                    }
                    break;
                }
                if (units >= 2) {
                    prev = appendPiece(out, prev, sb.toString(), P_CHAR);
                    i = j;
                    continue;
                }
                // A lone unit is not a run — fall through and let
                // the code classification below handle this token.
            }

            // 2) codeSymbols() classification, unchanged.
            String tok = toks[i];
            String low = lower[i];
            if (i + 1 < n) {
                String nxt = lower[i + 1];
                String phrase = SYMBOL_PHRASES.get(low + " " + nxt);
                if (phrase != null) {
                    prev = appendPiece(out, prev, phrase, P_GLYPH);
                    i += 2;
                    continue;
                }
                if (low.equals("double") && (nxt.equals("you") || nxt.equals("u"))) {
                    prev = appendPiece(out, prev, "w", P_CHAR);
                    i += 2;
                    continue;
                }
                if (low.equals("new") && nxt.equals("line")) {
                    prev = appendPiece(out, prev, "\n", P_BREAK);
                    i += 2;
                    continue;
                }
            }
            String glyph = SYMBOL_WORDS.get(low);
            if (glyph != null) {
                prev = appendPiece(out, prev, glyph, P_GLYPH);
                i++;
                continue;
            }
            if (low.equals("space")) {
                prev = appendPiece(out, prev, " ", P_BREAK);
                i++;
                continue;
            }
            if (low.equals("tab")) {
                prev = appendPiece(out, prev, "\t", P_BREAK);
                i++;
                continue;
            }
            if (low.equals("newline")) {
                prev = appendPiece(out, prev, "\n", P_BREAK);
                i++;
                continue;
            }
            String num = NUMBER_NAMES.get(low);
            if (num != null) {
                prev = appendPiece(out, prev, num, P_CHAR);
                i++;
                continue;
            }
            String letter = CODE_LETTER_NAMES.get(low);
            if (letter != null) {
                prev = appendPiece(out, prev, letter, P_CHAR);
                i++;
                continue;
            }
            if (tok.length() == 1 && !Character.isLetterOrDigit(tok.charAt(0))) {
                prev = appendPiece(out, prev, tok, P_GLYPH);
                i++;
                continue;
            }
            if (isAllDigits(tok)) {
                prev = appendPiece(out, prev, tok, P_CHAR);
                i++;
                continue;
            }
            prev = appendPiece(out, prev, tok, P_WORD);
            i++;
        }
        return out.toString();
    }
}
