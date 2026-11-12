package com.james.codendrive;

/**
 * Pure ordering logic for the enter/send action (v3.4.2; v3.4.3 adds the
 * explicit SEND attempt). Dependency-free (ints in, ints out) so it is
 * host-testable — see tests/SendPasteLogicTests.java.
 *
 * The service walks the returned attempts in order and stops at the first
 * one the editor accepts:
 *   ATTEMPT_ACTION_ID   — InputConnection.performEditorAction(EditorInfo.actionId)
 *   ATTEMPT_IME_ACTION  — InputConnection.performEditorAction(imeOptions & IME_MASK_ACTION)
 *   ATTEMPT_EDITOR_SEND — InputConnection.performEditorAction(IME_ACTION_SEND),
 *                         attempted even when the editor declared a different
 *                         action (or none at all): some editors listen for
 *                         SEND specifically without declaring it. Skipped when
 *                         a declared attempt already made that same call.
 *   ATTEMPT_KEY_EVENT   — a raw KEYCODE_ENTER down/up pair via sendKeyEvent
 *   ATTEMPT_NEWLINE     — commitText("\n") — the pre-3.4.2 terminal fallback
 *
 * Action values are the EditorInfo constants passed through verbatim:
 * IME_ACTION_UNSPECIFIED = 0 and IME_ACTION_NONE = 1 both mean "the editor
 * declared no action".
 */
public final class SendPlan {
    public static final int ATTEMPT_ACTION_ID = 1;
    public static final int ATTEMPT_IME_ACTION = 2;
    public static final int ATTEMPT_KEY_EVENT = 3;
    public static final int ATTEMPT_NEWLINE = 4;
    public static final int ATTEMPT_EDITOR_SEND = 5;

    /** EditorInfo.IME_ACTION_SEND, mirrored so this class stays
     *  dependency-free. */
    public static final int IME_ACTION_SEND = 4;

    private SendPlan() { }

    /**
     * @param actionId  EditorInfo.actionId (0 = the editor set no custom id)
     * @param imeAction imeOptions & EditorInfo.IME_MASK_ACTION
     * @return ordered attempt codes; always ends with KEY_EVENT, NEWLINE.
     */
    public static int[] plan(int actionId, int imeAction) {
        boolean hasActionId = actionId != 0;
        boolean hasImeAction = imeAction != 0 && imeAction != 1;
        int[] tmp = new int[5];
        int n = 0;
        if (hasActionId) tmp[n++] = ATTEMPT_ACTION_ID;
        // Skip the imeOptions action when it is the same call the
        // actionId attempt already made — performing it twice could
        // double-send in editors that act on both deliveries.
        if (hasImeAction && imeAction != actionId) tmp[n++] = ATTEMPT_IME_ACTION;
        // v3.4.3: the explicit SEND attempt sits between the declared
        // actions and the key event. Dedupe: if either declared attempt
        // already performed performEditorAction(IME_ACTION_SEND), this
        // would be the identical call a second time.
        if (actionId != IME_ACTION_SEND && imeAction != IME_ACTION_SEND) {
            tmp[n++] = ATTEMPT_EDITOR_SEND;
        }
        tmp[n++] = ATTEMPT_KEY_EVENT;
        tmp[n++] = ATTEMPT_NEWLINE;
        int[] out = new int[n];
        System.arraycopy(tmp, 0, out, 0, n);
        return out;
    }
}
