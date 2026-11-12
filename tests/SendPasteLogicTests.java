import com.james.codendrive.ClipScan;
import com.james.codendrive.SendPlan;

import java.util.Arrays;

/**
 * Host tests for the v3.4.2 pure logic extracted from the service:
 * SendPlan (enter/send attempt ordering) and ClipScan (clipboard
 * classification + editor image support). Plain main() harness, same
 * shape as the engine Battery. The framework-bound behavior (actually
 * performing editor actions, commitContent) is compile-proven only;
 * A physical phone is its acceptance test.
 *
 * Run: javac -encoding UTF-8 -d /tmp/splt \
 *        app/src/com.james.codendrive/SendPlan.java \
 *        app/src/com.james.codendrive/ClipScan.java \
 *        tests/SendPasteLogicTests.java
 *      java -cp /tmp/splt SendPasteLogicTests
 */
public class SendPasteLogicTests {
    static int pass = 0, fail = 0;

    // EditorInfo action constants (passed through, mirrored here).
    static final int UNSPECIFIED = 0, NONE = 1, GO = 2, SEARCH = 3,
            SEND = 4, NEXT = 5, DONE = 6;

    static void plan(String name, int actionId, int imeAction, int[] want) {
        int[] got = SendPlan.plan(actionId, imeAction);
        if (Arrays.equals(got, want)) { pass++; System.out.println("PASS plan " + name); }
        else {
            fail++;
            System.out.println("FAIL plan " + name + " want=" + Arrays.toString(want)
                    + " got=" + Arrays.toString(got));
        }
    }

    static void check(String name, boolean got, boolean want) {
        if (got == want) { pass++; System.out.println("PASS " + name); }
        else { fail++; System.out.println("FAIL " + name + " want=" + want + " got=" + got); }
    }

    static void checkInt(String name, int got, int want) {
        if (got == want) { pass++; System.out.println("PASS " + name); }
        else { fail++; System.out.println("FAIL " + name + " want=" + want + " got=" + got); }
    }

    public static void main(String[] args) {
        final int A = SendPlan.ATTEMPT_ACTION_ID, I = SendPlan.ATTEMPT_IME_ACTION,
                S = SendPlan.ATTEMPT_EDITOR_SEND,
                K = SendPlan.ATTEMPT_KEY_EVENT, N = SendPlan.ATTEMPT_NEWLINE;

        // ---- SendPlan (v3.4.3: explicit SEND attempt between the
        // declared actions and the key event, deduped when a declared
        // attempt already performed performEditorAction(SEND)) ----
        plan("declared SEND only", 0, SEND, new int[]{I, K, N});
        plan("declared DONE only", 0, DONE, new int[]{I, S, K, N});
        plan("nothing declared (Muse-app shape)", 0, NONE, new int[]{S, K, N});
        plan("unspecified", 0, UNSPECIFIED, new int[]{S, K, N});
        plan("custom actionId only", 0x1234, NONE, new int[]{A, S, K, N});
        plan("actionId + SEND", 0x1234, SEND, new int[]{A, I, K, N});
        plan("actionId == imeAction dedupes", SEND, SEND, new int[]{A, K, N});
        plan("actionId is SEND alone", SEND, NONE, new int[]{A, K, N});
        plan("no EditorInfo at all", 0, 0, new int[]{S, K, N});

        // ---- ClipScan.descHasImage ----
        check("desc image/png", ClipScan.descHasImage(new String[]{"image/png"}), true);
        check("desc text only", ClipScan.descHasImage(new String[]{"text/plain"}), false);
        check("desc mixed", ClipScan.descHasImage(new String[]{"text/plain", "image/*"}), true);
        check("desc null", ClipScan.descHasImage(null), false);
        check("desc empty", ClipScan.descHasImage(new String[0]), false);

        // ---- ClipScan.findImageItem / findTextItem ----
        // Screenshot clip: single Uri item, no text, image desc.
        checkInt("screenshot clip image",
                ClipScan.findImageItem(new boolean[]{true}, new boolean[]{false}, true), 0);
        // Browser copy-image: text item first, image Uri second, image desc.
        checkInt("image behind text item",
                ClipScan.findImageItem(new boolean[]{false, true}, new boolean[]{true, false}, true), 1);
        checkInt("text item found alongside",
                ClipScan.findTextItem(new boolean[]{true, false}), 0);
        // Image item carrying filename text, image desc: still the image.
        checkInt("uri+text item, image desc",
                ClipScan.findImageItem(new boolean[]{true}, new boolean[]{true}, true), 0);
        // Uri+text item, NO image desc: text wins (pre-3.4.2 behavior).
        checkInt("uri+text item, plain desc",
                ClipScan.findImageItem(new boolean[]{true}, new boolean[]{true}, false), -1);
        // Standalone Uri, no image desc: image (legacy fallback).
        checkInt("bare uri, plain desc",
                ClipScan.findImageItem(new boolean[]{true}, new boolean[]{false}, false), 0);
        // Pure text clip: no image anywhere.
        checkInt("text-only clip image",
                ClipScan.findImageItem(new boolean[]{false}, new boolean[]{true}, false), -1);
        checkInt("text-only clip text",
                ClipScan.findTextItem(new boolean[]{true}), 0);
        checkInt("no text items",
                ClipScan.findTextItem(new boolean[]{false, false}), -1);

        // ---- ClipScan.editorAcceptsImage ----
        check("editor image/png", ClipScan.editorAcceptsImage(new String[]{"image/png"}), true);
        check("editor image/*", ClipScan.editorAcceptsImage(new String[]{"image/*"}), true);
        check("editor */*", ClipScan.editorAcceptsImage(new String[]{"*/*"}), true);
        check("editor text only", ClipScan.editorAcceptsImage(new String[]{"text/plain"}), false);
        check("editor mixed", ClipScan.editorAcceptsImage(new String[]{"text/plain", "image/jpeg"}), true);
        check("editor null array", ClipScan.editorAcceptsImage(null), false);
        check("editor empty array", ClipScan.editorAcceptsImage(new String[0]), false);

        System.out.println("----------------------------------------");
        System.out.println("SendPasteLogicTests: " + pass + " PASS, " + fail + " FAIL");
        if (fail > 0) System.exit(1);
    }
}
