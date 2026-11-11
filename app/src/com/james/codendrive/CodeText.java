package com.james.codendrive;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * CodeNDrive's two deterministic dictation passes. Pure Java, no Android
 * dependencies, so both are host-testable (tests/CodeTextBattery.java).
 *
 * These passes exist because a general speech recognizer will never be
 * reliable at two things coders say constantly: spelled identifiers and
 * symbol names. Both passes are deliberately dumb: a fixed table, a
 * fixed spacing rule, no guessing. Deterministic beats helpful here —
 * the same spoken input produces the same output every time.
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
}
