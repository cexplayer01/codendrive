import com.james.codendrive.AcceptAsWritten;
import com.james.codendrive.engine.CorrectionEngine;

import java.util.List;

/**
 * v1.0.4 policy tests for AcceptAsWritten — the accept-as-written
 * option James ruled in on 2026-11-14 ("we need to have the option
 * to accept as written"), ported from James Keyboard v3.4.6. The
 * policy changes NOTHING about what the engine snaps; these tests
 * pin (a) which options are offered after a correction, and (b)
 * that accepting one restores the user's literal word byte-exact.
 * Engine-driven cases run the REAL CorrectionEngine of this tree so
 * the corrected text is the shipped behavior, not a mock — the
 * typed cases exercise engine.correctToken, the exact personal
 * layer CodeNDriveService v1.0.4 wires the undo slot into, and the
 * segment cases exercise the policy against the engine's real
 * segment corrections. Plain main() harness, same style as
 * SendPasteLogicTests / CodeTextBattery.
 *
 * Run: javac -encoding UTF-8 -d /tmp/awt \
 *        app/src/com/james/codendrive/AcceptAsWritten.java \
 *        $(find engine -name '*.java') \
 *        tests/AcceptAsWrittenTests.java
 *      java -cp /tmp/awt AcceptAsWrittenTests
 */
public class AcceptAsWrittenTests {
    static int pass = 0, fail = 0;

    static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("PASS " + name); }
        else { fail++; System.out.println("FAIL " + name); }
    }
    static void checkStr(String name, String got, String want) {
        if (want == null ? got == null : want.equals(got)) {
            pass++; System.out.println("PASS " + name);
        } else {
            fail++;
            System.out.println("FAIL " + name + " want=[" + want + "] got=[" + got + "]");
        }
    }
    static void checkInt(String name, int got, int want) {
        if (got == want) { pass++; System.out.println("PASS " + name); }
        else { fail++; System.out.println("FAIL " + name + " want=" + want + " got=" + got); }
    }

    /** Accept the first offered option and recompute, the way a
     *  strip tap does, until no options remain or the cap on rounds
     *  is hit. Returns the resulting segment. */
    static String acceptAll(String raw, String corrected) {
        String seg = corrected;
        for (int round = 0; round < 10; round++) {
            List<AcceptAsWritten.Option> opts = AcceptAsWritten.forSegment(raw, seg);
            if (opts.isEmpty()) return seg;
            seg = AcceptAsWritten.apply(seg, opts.get(0));
        }
        return seg;
    }

    public static void main(String[] args) {
        CorrectionEngine e = new CorrectionEngine();

        // ---------- forWord: the typed-path decision ----------
        AcceptAsWritten.Option w =
                AcceptAsWritten.forWord("replied", "Replit");
        check("WORD snap offers an option", w != null);
        checkStr("WORD option carries the literal original",
                w == null ? null : w.original, "replied");
        checkStr("WORD option names the applied snap",
                w == null ? null : w.applied, "Replit");
        checkStr("WORD apply restores the literal byte-exact",
                AcceptAsWritten.apply("Replit", w), "replied");
        check("WORD unchanged word offers nothing",
                AcceptAsWritten.forWord("hello", "hello") == null);
        check("WORD null applied offers nothing",
                AcceptAsWritten.forWord("replied", null) == null);
        check("WORD null original offers nothing",
                AcceptAsWritten.forWord(null, "Replit") == null);
        checkStr("WORD apply with a stale option is a no-op",
                AcceptAsWritten.apply("Replit",
                        new AcceptAsWritten.Option("x", "y", 4, 99)),
                "Replit");

        // ---------- typed path, end to end through the real engine ----------
        // The service's personal layer is engine.correctToken; this is
        // the exact "replied" -> "Replit" snap James hit on this
        // keyboard, and the layer v1.0.4 wires the undo slot into.
        String typedApplied = e.correctToken("replied", null);
        checkStr("TYPED engine snaps replied (precondition)",
                typedApplied, "Replit");
        AcceptAsWritten.Option t =
                AcceptAsWritten.forWord("replied", typedApplied);
        check("TYPED as-written option offered for the snap", t != null);
        checkStr("TYPED accepting it commits the literal word",
                AcceptAsWritten.apply(typedApplied, t), "replied");
        check("TYPED a word the engine leaves alone offers nothing",
                AcceptAsWritten.forWord("buyer", e.correctToken("buyer", null))
                        == null);

        // ---------- segment: one snap ----------
        String raw1 = "he replied to the buyer";
        String cor1 = e.correctVoice(raw1);
        checkStr("SEGMENT engine output (precondition)",
                cor1, "He Replit to the buyer");
        List<AcceptAsWritten.Option> o1 = AcceptAsWritten.forSegment(raw1, cor1);
        checkInt("SEGMENT exactly one option (sentence-start case is not one)",
                o1.size(), 1);
        checkStr("SEGMENT option is the literal word",
                o1.isEmpty() ? null : o1.get(0).original, "replied");
        checkStr("SEGMENT accepting restores the segment byte-exact",
                AcceptAsWritten.apply(cor1, o1.get(0)),
                "He replied to the buyer");

        // ---------- segment: two different changes, each its own option ----------
        // Unchanged tokens ("to", "today") anchor the alignment, so
        // the snap and the decode phrase form separate regions.
        String raw2 = "i replied to net lafaye today";
        String cor2 = e.correctVoice(raw2);
        checkStr("SEGMENT decode+snap output (precondition)",
                cor2, "I Replit to Netlify today");
        List<AcceptAsWritten.Option> o2 = AcceptAsWritten.forSegment(raw2, cor2);
        checkInt("SEGMENT snap + decode phrase = two options", o2.size(), 2);
        checkStr("SEGMENT first option is the snapped word",
                o2.size() > 0 ? o2.get(0).original : null, "replied");
        checkStr("SEGMENT second option is the whole spoken phrase",
                o2.size() > 1 ? o2.get(1).original : null, "net lafaye");
        // (Sentence-start "i" -> "I" is a case-only change: by the
        // policy's rule it is never offered, so it stays capitalized.)
        checkStr("SEGMENT accepting both restores every snapped token",
                acceptAll(raw2, cor2), "I replied to net lafaye today");

        // A segment whose EVERY token changed has no anchors: one
        // whole-segment option, accepting restores the raw byte-exact.
        String raw2b = "net lafaye replied";
        String cor2b = e.correctVoice(raw2b);
        checkStr("SEGMENT fully-changed output (precondition)",
                cor2b, "Netlify Replit");
        List<AcceptAsWritten.Option> o2b =
                AcceptAsWritten.forSegment(raw2b, cor2b);
        checkInt("SEGMENT fully-changed segment = one option", o2b.size(), 1);
        checkStr("SEGMENT whole-segment option carries the raw segment",
                o2b.isEmpty() ? null : o2b.get(0).original, raw2b);
        checkStr("SEGMENT accepting it returns the raw segment byte-exact",
                o2b.isEmpty() ? null
                        : AcceptAsWritten.apply(cor2b, o2b.get(0)), raw2b);

        // ---------- segment: two phrase merges ----------
        String raw3 = "i use super base and deep seek";
        String cor3 = e.correctVoice(raw3);
        checkStr("SEGMENT two merges output (precondition)",
                cor3, "I use Supabase and DeepSeek");
        List<AcceptAsWritten.Option> o3 = AcceptAsWritten.forSegment(raw3, cor3);
        checkInt("SEGMENT two merges = two options", o3.size(), 2);
        checkStr("SEGMENT merge option one",
                o3.size() > 0 ? o3.get(0).original : null, "super base");
        checkStr("SEGMENT merge option two",
                o3.size() > 1 ? o3.get(1).original : null, "deep seek");
        checkStr("SEGMENT accepting all merges returns the raw segment",
                acceptAll(raw3, cor3), "I use super base and deep seek");

        // ---------- segment: same word snapped twice ----------
        String raw4 = "replied and replied";
        String cor4 = e.correctVoice(raw4);
        checkStr("SEGMENT repeated snap output (precondition)",
                cor4, "Replit and Replit");
        List<AcceptAsWritten.Option> o4 = AcceptAsWritten.forSegment(raw4, cor4);
        checkInt("SEGMENT each snapped instance gets its own option",
                o4.size(), 2);
        checkStr("SEGMENT accepting the first leaves the second snapped",
                AcceptAsWritten.apply(cor4, o4.get(0)), "replied and Replit");
        checkStr("SEGMENT accepting all returns the raw segment",
                acceptAll(raw4, cor4), raw4);

        // ---------- segment: punctuation outside the span survives ----------
        String raw5 = "i replied, thanks";
        String cor5 = e.correctVoice(raw5);
        checkStr("SEGMENT punctuation output (precondition)",
                cor5, "I Replit, thanks");
        List<AcceptAsWritten.Option> o5 = AcceptAsWritten.forSegment(raw5, cor5);
        checkInt("SEGMENT punctuated snap = one option", o5.size(), 1);
        checkStr("SEGMENT accepting keeps the comma exactly",
                AcceptAsWritten.apply(cor5, o5.get(0)), "I replied, thanks");

        // ---------- segment: no snap -> no option ----------
        String raw6 = "the quick brown fox";
        String cor6 = e.correctVoice(raw6);
        checkStr("SEGMENT clean segment output (precondition)",
                cor6, "The quick brown fox");
        check("SEGMENT no snap offers no option",
                AcceptAsWritten.forSegment(raw6, cor6).isEmpty());
        check("SEGMENT identical strings offer nothing",
                AcceptAsWritten.forSegment("same text", "same text").isEmpty());
        check("SEGMENT case-only difference offers nothing",
                AcceptAsWritten.forSegment("call me back", "Call me back")
                        .isEmpty());

        // ---------- the strip cap: first MAX_OPTIONS regions win ----------
        String raw7 = "replied replied replied replied";
        String cor7 = e.correctVoice(raw7);
        checkStr("SEGMENT four snaps output (precondition)",
                cor7, "Replit Replit Replit Replit");
        checkInt("SEGMENT options capped at the strip size",
                AcceptAsWritten.forSegment(raw7, cor7).size(),
                AcceptAsWritten.MAX_OPTIONS);

        // ---------- synthetic split + pure deletion ----------
        List<AcceptAsWritten.Option> o8 =
                AcceptAsWritten.forSegment("use netlify now", "use Net lify now");
        checkInt("SEGMENT a split word is one option", o8.size(), 1);
        checkStr("SEGMENT split option restores the joined literal",
                o8.isEmpty() ? null
                        : AcceptAsWritten.apply("use Net lify now", o8.get(0)),
                "use netlify now");
        check("SEGMENT a purely deleted token offers nothing",
                AcceptAsWritten.forSegment("end period now", "End. now")
                        .isEmpty());

        // ---------- FLOW replay: corrected segment + one tap ----------
        // Policy-level replay of the commit-then-tap shape: the
        // corrected segment lands (with the trailing space the commit
        // rule adds), one as-written tap swaps the snapped token in
        // the committed text for the literal original, byte-exact.
        String rawF = "he replied to the buyer";
        String cleanedF = e.correctVoice(rawF);
        String committedF = cleanedF + " ";
        List<AcceptAsWritten.Option> oF =
                AcceptAsWritten.forSegment(rawF, cleanedF);
        checkInt("FLOW one option offered after the correction", oF.size(), 1);
        String restoredF = AcceptAsWritten.apply(cleanedF, oF.get(0)) + " ";
        checkStr("FLOW tap restores the literal in the committed text",
                restoredF, "He replied to the buyer ");
        check("FLOW restored text differs from committed only at the snap",
                committedF.startsWith("He Replit") && restoredF.contains("replied"));

        System.out.println("----------------------------------------");
        System.out.println("AcceptAsWrittenTests: " + pass + " PASS, " + fail + " FAIL");
        if (fail > 0) System.exit(1);
    }
}
