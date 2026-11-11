package com.james.codendrive;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.inputmethodservice.InputMethodService;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputContentInfo;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.james.codendrive.engine.CorrectionEngine;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * CodeNDrive IME. Pure framework Java (no AndroidX), so the whole app
 * builds with the manual aapt2/javac/d8 pipeline.
 *
 * Voice results always pass through the correction engine before commit.
 * Typed words are corrected on word boundaries (space/punctuation) using the
 * engine's token path. There is deliberately NO double-space-inserts-period
 * and NO auto-period behavior anywhere in this keyboard.
 */
public class CodeNDriveService extends InputMethodService {

    private final CorrectionEngine engine = new CorrectionEngine();
    private LinearLayout root;
    private LinearLayout keysBox;
    private LinearLayout stripBox;
    private TextView stripStatus;
    private final List<Button> stripButtons = new ArrayList<>();
    private final List<Button> letterButtons = new ArrayList<>();
    private Button shiftButton;
    private Button micButton;

    private boolean symbolsMode = false;
    private int shiftState = 0; // 0 off, 1 one-shot, 2 caps lock
    private boolean passwordField = false;
    private long lastShiftTapMs = 0;

    // ---------------- v3.3 WS2: typed-correction strip state ----------------
    //
    // After fixWordBeforeCursor runs at a word boundary, the strip may be
    // showing suggestions for the word that just ended (which now sits
    // BEFORE a separator) instead of for a word still being typed:
    //   - undo case: the field holds the typed CORRECTION; slot 0 is the
    //     ORIGINAL word, and tapping it restores the original.
    //   - ambiguous case: the field holds the original word unchanged;
    //     the slots are engine.typedSuggestions for it.
    // boundaryWord is the exact text currently in the field that a tap
    // must replace; the pick is stale (and the state dropped) the moment
    // it no longer matches, or on any fresh key input.
    private String boundaryWord = null;
    private String boundaryOriginal = null; // undo case only: the original word
    private boolean boundaryIsUndo = false;
    private final List<String> boundarySuggestions = new ArrayList<>();
    // One-shot typed-correction suppression: set when the user taps the
    // undo slot. The next time fixWordBeforeCursor meets this exact word
    // it skips the typed path for that one instance; the flag is cleared
    // at that next word boundary whether it matched or not (a boundary
    // elsewhere consumes it), so it can never suppress a later, separate
    // occurrence of the same word.
    private String suppressTypedWord = null;

    // ---------------- v3.3 WS3: clipboard panel state ----------------
    private Button clipboardButton;
    private boolean clipboardPanelOpen = false;
    // Clip contents cached by refreshClipboardCache(): read fresh on
    // panel open, onStartInput and onStartInputView — never polled.
    private String pendingClipText = null;
    private Uri pendingClipUri = null;
    private ClipDescription pendingClipDesc = null;
    // Learning guard: set before one of OUR paste commits. The paste
    // handler resyncs lastFieldSnapshot to the post-paste field text
    // (the primary guard — the delta observer then sees no paste delta
    // at all); this flag is the fallback for fields whose text cannot
    // be re-read after the commit, and makes observeCommittedDelta skip
    // exactly one delta. See observeCommittedDelta / paste handlers.
    private boolean suppressLearnForNextDelta = false;

    private SpeechRecognizer recognizer;
    private boolean listening = false;
    private final Handler ui = new Handler(Looper.getMainLooper());

    // ---------------- v3.4 ClickSend ----------------
    // Hands-free send: three tongue clicks while dictation is armed fire
    // the existing enter/send path after a cancellable 2s countdown.
    // A parallel AudioRecord feeds ClickDetector the same mic signal the
    // recognizer hears (SpeechRecognizer's onRmsChanged is too coarse —
    // its cadence cannot resolve the 85-110ms gaps between clicks).
    // Armed only from a mic tap; never an always-on mic.
    private ClickDetector clickDetector;
    private AudioRecord clickRec;
    private volatile boolean clickWatchOn = false;
    private boolean clickCountingDown = false;
    private long clickCancelArmedAtMs = 0;
    private ToneGenerator toneGen;
    private boolean clickDeafNoted = false;
    private float rmsSessionMin = Float.MAX_VALUE;
    private float rmsSessionMax = -Float.MAX_VALUE;

    private final ClickDetector.Listener clickListener = new ClickDetector.Listener() {
        @Override public void onTripleClick() {
            ui.post(new Runnable() {
                @Override public void run() { onClickTripleDetected(); }
            });
        }
        @Override public void onAnyClick() {
            ui.post(new Runnable() {
                @Override public void run() { onClickAnyDetected(); }
            });
        }
    };

    private final Runnable clickGraceStop = new Runnable() {
        @Override public void run() {
            stopClickWatch();
            if (!clickCountingDown) refreshRestingStrip();
        }
    };

    private final Runnable clickTick1 = new Runnable() {
        @Override public void run() {
            if (clickCountingDown) {
                setStripStatus("Sending in 1 \u2014 click or tap any key to cancel");
            }
        }
    };

    private final Runnable clickFire = new Runnable() {
        @Override public void run() { completeClickSend(); }
    };

    // ---------------- CodeNDrive modes ----------------
    // CODE: dictation passes through CodeText.codeSymbols — spoken
    // symbol names always become glyphs. SPELL: dictation passes
    // through CodeText.spell — spelled letters become the exact
    // lowercase string. Mutually exclusive; both off = the inherited
    // keyboard behavior, byte for byte. Modes affect dictation only;
    // typed keys are untouched. State lives on the two mode keys
    // (lit when on) and in the strip's resting text.
    private boolean codeMode = false;
    private boolean spellMode = false;
    private Button codeButton;
    private Button spellButton;

    private static final String PREFS = "cnd_prefs";

    // ---------------- lifecycle ----------------

    @Override
    public void onCreate() {
        super.onCreate();
        loadPrefs();
        loadLearning();
    }

    @Override
    public boolean onEvaluateInputViewShown() {
        // The framework default suppresses the soft keyboard whenever a
        // hardware keyboard is present (emulators always present one;
        // so do BT keyboards/keyboard cases on real phones). This
        // keyboard is the user's chosen input — always show it.
        return true;
    }

    private void loadPrefs() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        engine.escapesOn = p.getBoolean("esc", true);
        engine.domainsOn = p.getBoolean("dom", true);
        engine.decodeOn = p.getBoolean("dec", true);
        engine.snapOn = p.getBoolean("snap", true);
        engine.periodsOn = p.getBoolean("per", true);
        engine.gatesOn = p.getBoolean("gate", true);
        engine.symbolsOn = p.getBoolean("sym", true);
        engine.learningOn = p.getBoolean("learn", true);
    }

    // ---------------- learning tier (v3): file IO ----------------
    //
    // The engine owns learning state + logic; this service owns the file.
    // Learning data lives only in app-private storage (getFilesDir) and
    // the manifest has no INTERNET permission: nothing learned can leave
    // the phone.

    static final String LEARN_FILE = "cnd_learn.txt";

    private int currentInputType = 0;
    private boolean learningExcluded = true; // until a session says otherwise
    private String lastFieldSnapshot = "";

    private void loadLearning() {
        try {
            File f = new File(getFilesDir(), LEARN_FILE);
            if (!f.exists()) return;
            byte[] buf = new byte[(int) f.length()];
            FileInputStream in = new FileInputStream(f);
            int off = 0, n;
            while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) off += n;
            in.close();
            engine.loadLearning(new String(buf, 0, off, StandardCharsets.UTF_8),
                    System.currentTimeMillis());
        } catch (Exception ignored) { }
    }

    private void saveLearning() {
        try {
            FileOutputStream out = new FileOutputStream(new File(getFilesDir(), LEARN_FILE));
            out.write(engine.dumpLearning().getBytes(StandardCharsets.UTF_8));
            out.close();
        } catch (Exception ignored) { }
    }

    /** Full current field text (best effort), or null if unreadable. */
    private String readFieldText() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return null;
        try {
            CharSequence before = ic.getTextBeforeCursor(100000, 0);
            CharSequence after = ic.getTextAfterCursor(100000, 0);
            if (before == null || after == null) return null;
            return before.toString() + after.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Observe the final text accumulated since the last snapshot. Called
     * at commit boundaries only (Enter/editor action, input finish), so
     * drafts-in-progress are never observed: a word typed and edited away
     * before commit leaves no trace. The delta is a longest-common-prefix
     * diff against the snapshot, so text the session started with (e.g.
     * a reopened draft) is not re-learned.
     */
    private void observeCommittedDelta() {
        String now = readFieldText();
        if (now == null) return;
        // v3.3 learning guard (fallback arm): our own clipboard paste
        // could not be snapshot-resynced, so skip learning this one
        // delta entirely — pasted text is not the user's vocabulary.
        if (suppressLearnForNextDelta) {
            suppressLearnForNextDelta = false;
            lastFieldSnapshot = now;
            return;
        }
        if (engine.learningOn && !learningExcluded) {
            String delta = committedDelta(lastFieldSnapshot, now);
            if (!delta.isEmpty()) {
                engine.observeCommittedText(delta, currentInputType,
                        System.currentTimeMillis());
                saveLearning();
            }
        }
        lastFieldSnapshot = now;
    }

    private static String committedDelta(String prev, String now) {
        if (prev == null || prev.isEmpty()) return now == null ? "" : now;
        if (now == null || now.isEmpty()) return "";
        int k = 0, max = Math.min(prev.length(), now.length());
        while (k < max && prev.charAt(k) == now.charAt(k)) k++;
        return now.substring(k);
    }

    @Override
    public void onStartInput(EditorInfo info, boolean restarting) {
        super.onStartInput(info, restarting);
        loadPrefs();
        loadLearning(); // pick up Settings edits/deletes between sessions
        currentInputType = info.inputType;
        learningExcluded = CorrectionEngine.isLearningExcludedField(info.inputType);
        // Snapshot what the field already holds so the delta observer
        // only learns text this session commits.
        String existing = readFieldText();
        lastFieldSnapshot = existing != null ? existing : "";
        int variation = info.inputType & android.text.InputType.TYPE_MASK_VARIATION;
        passwordField = variation == android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                || variation == android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                || variation == android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                || variation == android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD;
        if (symbolsMode) { symbolsMode = false; buildKeys(); }
        shiftState = 0;
        // v3.3: a new input session invalidates strip boundary state and
        // any one-shot suppression, closes the clipboard panel (panel
        // closes on switching input view), and re-reads the clipboard so
        // a screenshot/copy taken just before opening is already cached.
        clearBoundarySuggestions();
        suppressTypedWord = null;
        if (clipboardPanelOpen) { clipboardPanelOpen = false; buildKeys(); }
        refreshClipboardCache();
        refreshShiftLabels();
        updateStrip();
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        // v3.3: keyboard is being shown — refresh the clip cache again,
        // and if the panel is somehow still open, rebuild it from the
        // fresh clip.
        refreshClipboardCache();
        if (clipboardPanelOpen) buildClipboardPanel();
    }

    @Override
    public View onCreateInputView() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(24, 26, 30));
        root.setPadding(dp(4), dp(4), dp(4), dp(6));
        // v1.0.1 NavFix (identical to James Keyboard v3.4.1): at
        // targetSdk 36 the IME window is edge-to-edge — it extends under
        // the system navigation bar and the platform does NOT pad IME
        // content for us. Without this, the bottom row (mode / comma /
        // mic / space / period / enter) renders underneath the nav
        // controls and is untappable (James, 2026-11-11, from his own
        // screenshot). Lift the whole keyboard by the navigation-bar
        // bottom inset. The listener SETS padding absolutely from the
        // captured base values (never +=), so repeated inset dispatches
        // and input-view recreations cannot accumulate padding; buildKeys
        // only rebuilds children inside keysBox and never touches root
        // padding, so rebuilds cannot double-apply either.
        final int padL = dp(4), padT = dp(4), padR = dp(4), padB = dp(6);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int navBottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                navBottom = insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
            } else {
                navBottom = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(padL, padT, padR, padB + navBottom);
            return insets;
        });

        stripBox = new LinearLayout(this);
        stripBox.setOrientation(LinearLayout.HORIZONTAL);
        stripBox.setGravity(Gravity.CENTER_VERTICAL);
        stripBox.setMinimumHeight(dp(40));
        root.addView(stripBox);
        stripStatus = new TextView(this);
        stripStatus.setTextColor(Color.rgb(170, 178, 190));
        stripStatus.setTextSize(13);
        stripStatus.setPadding(dp(8), 0, dp(8), 0);
        // v3.3 WS3: clipboard toggle — always visible, strip styling,
        // kept out of stripButtons so updateStrip never hides it.
        clipboardButton = new Button(this);
        styleStripButton(clipboardButton);
        clipboardButton.setText("📋");
        clipboardButton.setOnClickListener(v -> toggleClipboardPanel());
        stripBox.addView(clipboardButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        for (int i = 0; i < 3; i++) {
            Button b = new Button(this);
            styleStripButton(b);
            b.setVisibility(View.GONE);
            final int idx = i;
            b.setOnClickListener(v -> pickSuggestion(idx));
            stripButtons.add(b);
            stripBox.addView(b, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        stripBox.addView(stripStatus);

        keysBox = new LinearLayout(this);
        keysBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(keysBox);
        buildKeys();
        return root;
    }

    // ---------------- keyboard construction ----------------

    private void buildKeys() {
        if (keysBox == null) return;
        clipboardPanelOpen = false; // (re)building the key rows closes the panel
        keysBox.removeAllViews();
        letterButtons.clear();
        if (!symbolsMode) {
            addRow(new String[]{"q","w","e","r","t","y","u","i","o","p"}, true);
            // v3.3 WS1: home row gains ' as its 10th key (long-press = ").
            addRow(new String[]{"a","s","d","f","g","h","j","k","l","'"}, false);
            addRow(new String[]{"SHIFT","z","x","c","v","b","n","m","BKSP"}, false);
            addBottomRow("?123");
        } else {
            addRow(new String[]{"1","2","3","4","5","6","7","8","9","0"}, false);
            addRow(new String[]{"@","#","$","%","&","-","+","(",")","/"}, false);
            // v3.3 WS1: third symbols row gains ' and ".
            addRow(new String[]{"_","=","*",":",";","!","?","'","\"","BKSP"}, false);
            addBottomRow("ABC");
        }
        addModeRow();
        refreshShiftLabels();
    }

    private void addRow(String[] keys, boolean digitLongPress) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int idx = 0; idx < keys.length; idx++) {
            final String key = keys[idx];
            Button b = new Button(this);
            styleKey(b);
            float weight = 1f;
            if (key.equals("BKSP") || key.equals("SHIFT")) weight = 1.5f;
            if (key.equals("SHIFT")) { b.setText("⇧"); shiftButton = b; b.setOnClickListener(v -> onShiftKey()); }
            else if (key.equals("BKSP")) { b.setText("⌫"); attachBackspace(b); }
            else {
                b.setText(key);
                letterButtons.add(b);
                if (key.equals("'") && !symbolsMode) {
                    // v3.3 WS1: home-row apostrophe gets its own handlers —
                    // tap commits ' without touching shift state, and
                    // long-press commits ". (In symbols mode the generic
                    // path below is used; shift is not applied there.)
                    b.setOnClickListener(v -> onApostropheKey());
                    b.setOnLongClickListener(v -> { onQuoteKey(); return true; });
                } else {
                    b.setOnClickListener(v -> onLetterKey(key));
                    if (digitLongPress) {
                        final String digit = "1234567890".substring(idx, idx + 1);
                        b.setOnLongClickListener(v -> { commit(digit); afterCommit(); return true; });
                    }
                }
            }
            row.addView(b, new LinearLayout.LayoutParams(0, dp(52), weight));
        }
        keysBox.addView(row);
    }

    private void addBottomRow(String modeLabel) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button mode = new Button(this);
        styleKey(mode);
        mode.setText(modeLabel);
        mode.setOnClickListener(v -> { cancelCountdownOnTap(); symbolsMode = !symbolsMode; buildKeys(); });
        row.addView(mode, new LinearLayout.LayoutParams(0, dp(52), 1.3f));

        Button comma = new Button(this);
        styleKey(comma);
        comma.setText(",");
        comma.setOnClickListener(v -> onSeparatorKey(","));
        row.addView(comma, new LinearLayout.LayoutParams(0, dp(52), 0.8f));

        micButton = new Button(this);
        styleKey(micButton);
        micButton.setText("🎤");
        micButton.setOnClickListener(v -> onMicKey());
        row.addView(micButton, new LinearLayout.LayoutParams(0, dp(52), 1f));

        Button space = new Button(this);
        styleKey(space);
        space.setText("CodeNDrive");
        space.setTextSize(12);
        space.setOnClickListener(v -> onSeparatorKey(" "));
        row.addView(space, new LinearLayout.LayoutParams(0, dp(52), 3.6f));

        Button period = new Button(this);
        styleKey(period);
        period.setText(".");
        period.setOnClickListener(v -> onSeparatorKey("."));
        row.addView(period, new LinearLayout.LayoutParams(0, dp(52), 0.8f));

        Button enter = new Button(this);
        styleKey(enter);
        enter.setText("⏎");
        enter.setOnClickListener(v -> onEnterKey());
        row.addView(enter, new LinearLayout.LayoutParams(0, dp(52), 1.2f));
        keysBox.addView(row);
    }

    /** CodeNDrive mode row: two clearly labelled toggles under the key
     *  rows, always visible. Active mode is lit green (CODE) or amber
     *  (SPELL); the strip's resting text names the mode too. */
    private void addModeRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        codeButton = new Button(this);
        styleKey(codeButton);
        codeButton.setText("CODE");
        codeButton.setTextSize(13);
        codeButton.setOnClickListener(v -> onCodeModeKey());
        row.addView(codeButton, new LinearLayout.LayoutParams(0, dp(40), 1f));
        spellButton = new Button(this);
        styleKey(spellButton);
        spellButton.setText("SPELL");
        spellButton.setTextSize(13);
        spellButton.setOnClickListener(v -> onSpellModeKey());
        row.addView(spellButton, new LinearLayout.LayoutParams(0, dp(40), 1f));
        keysBox.addView(row);
        refreshModeButtons();
    }

    private void onCodeModeKey() {
        cancelCountdownOnTap();
        codeMode = !codeMode;
        if (codeMode) spellMode = false;
        refreshModeButtons();
        refreshRestingStrip();
    }

    private void onSpellModeKey() {
        cancelCountdownOnTap();
        spellMode = !spellMode;
        if (spellMode) codeMode = false;
        refreshModeButtons();
        refreshRestingStrip();
    }

    private void refreshModeButtons() {
        if (codeButton != null) {
            codeButton.setBackgroundColor(codeMode
                    ? Color.rgb(30, 110, 70) : Color.rgb(52, 56, 64));
        }
        if (spellButton != null) {
            spellButton.setBackgroundColor(spellMode
                    ? Color.rgb(140, 95, 20) : Color.rgb(52, 56, 64));
        }
    }

    /** The strip's resting text: the active dictation mode, or blank.
     *  Listening, the ClickSend countdown and error messages own the
     *  strip while they run — this only sets what it returns to. */
    private void refreshRestingStrip() {
        if (stripStatus == null) return;
        if (passwordField) { setStripStatus(""); return; }
        if (codeMode) setStripStatus("CODE \u2014 symbols come out as glyphs");
        else if (spellMode) setStripStatus("SPELL \u2014 say letters, get the exact word");
        else setStripStatus("");
    }

    private void styleKey(Button b) {
        b.setAllCaps(false);
        b.setTextSize(17);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(Color.rgb(52, 56, 64));
        b.setPadding(0, 0, 0, 0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(52), 1f);
        lp.setMargins(dp(2), dp(3), dp(2), dp(3));
        b.setLayoutParams(lp);
    }

    private void styleStripButton(Button b) {
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTextColor(Color.rgb(140, 200, 255));
        b.setBackgroundColor(Color.TRANSPARENT);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ---------------- key handling ----------------

    private void onLetterKey(String key) {
        cancelCountdownOnTap();
        clearBoundarySuggestions(); // fresh typing invalidates boundary strip state
        String out = key;
        if (!symbolsMode) {
            if (shiftState > 0) out = key.toUpperCase(Locale.US);
            if (shiftState == 1) { shiftState = 0; refreshShiftLabels(); }
        }
        commit(out);
        afterCommit();
    }

    /**
     * v3.3 WS1: home-row '. Deliberately NOT onLetterKey and NOT a
     * separator: an apostrophe is part of the word being typed, so it
     * must not trigger fixWordBeforeCursor mid-word, and it must not
     * consume a one-shot shift (it has no case — a pending sentence-start
     * shift belongs to the next real letter).
     */
    private void onApostropheKey() {
        cancelCountdownOnTap();
        clearBoundarySuggestions();
        commit("'");
        afterCommit();
    }

    /** v3.3 WS1: long-press ' commits ". Same no-shift-consumption rule. */
    private void onQuoteKey() {
        cancelCountdownOnTap();
        clearBoundarySuggestions();
        commit("\"");
        afterCommit();
    }

    private void onSeparatorKey(String sep) {
        cancelCountdownOnTap();
        clearBoundarySuggestions(); // the fix below may set fresh state
        fixWordBeforeCursor();
        commit(sep);
        afterCommit();
    }

    private void onEnterKey() {
        // ClickSend countdown running + enter tapped = "send now", via
        // this same path (completeClickSend re-enters here with the
        // countdown flag already cleared).
        if (clickCountingDown) { completeClickSend(); return; }
        clearBoundarySuggestions(); // the fix below may set fresh state
        fixWordBeforeCursor();
        // Commit boundary: the field's final text is what gets learned.
        observeCommittedDelta();
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        EditorInfo info = getCurrentInputEditorInfo();
        int action = info != null ? (info.imeOptions & EditorInfo.IME_MASK_ACTION) : 0;
        if (action != EditorInfo.IME_ACTION_NONE
                && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action);
        } else {
            ic.commitText("\n", 1);
        }
        afterCommit();
    }

    private void attachBackspace(Button b) {
        b.setOnTouchListener(new View.OnTouchListener() {
            private final Handler h = new Handler(Looper.getMainLooper());
            private final Runnable repeater = new Runnable() {
                @Override public void run() {
                    deleteOne();
                    h.postDelayed(this, 55);
                }
            };
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_DOWN) {
                    cancelCountdownOnTap();
                    deleteOne();
                    h.postDelayed(repeater, 420);
                    return true;
                }
                if (e.getAction() == MotionEvent.ACTION_UP
                        || e.getAction() == MotionEvent.ACTION_CANCEL) {
                    h.removeCallbacks(repeater);
                    afterCommit();
                    return true;
                }
                return false;
            }
        });
    }

    private void deleteOne() {
        clearBoundarySuggestions(); // editing invalidates boundary strip state
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        CharSequence selected = ic.getSelectedText(0);
        if (selected != null && selected.length() > 0) {
            ic.commitText("", 1);
        } else {
            ic.deleteSurroundingText(1, 0);
        }
    }

    private void onShiftKey() {
        cancelCountdownOnTap();
        long now = System.currentTimeMillis();
        if (shiftState == 1 && now - lastShiftTapMs < 350) {
            shiftState = 2; // caps lock on double tap
        } else if (shiftState == 2) {
            shiftState = 0;
        } else {
            shiftState = 1;
        }
        lastShiftTapMs = now;
        refreshShiftLabels();
    }

    private void refreshShiftLabels() {
        if (shiftButton != null) {
            shiftButton.setText(shiftState == 2 ? "⇪" : "⇧");
            shiftButton.setBackgroundColor(shiftState > 0
                    ? Color.rgb(90, 120, 170) : Color.rgb(52, 56, 64));
        }
        for (Button b : letterButtons) {
            CharSequence t = b.getText();
            if (t.length() == 1 && Character.isLetter(t.charAt(0))) {
                String s = t.toString();
                b.setText(shiftState > 0 ? s.toUpperCase(Locale.US) : s.toLowerCase(Locale.US));
            }
        }
    }

    // ---------------- text plumbing ----------------

    private void commit(String text) {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) ic.commitText(text, 1);
    }

    private void afterCommit() {
        autoShiftFromContext();
        updateStrip();
    }

    /** Capitalize the next letter at a field start or after a real sentence end. */
    private void autoShiftFromContext() {
        if (symbolsMode || passwordField) return;
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        CharSequence before = ic.getTextBeforeCursor(3, 0);
        boolean cap = false;
        if (before == null || before.length() == 0) cap = true;
        else {
            String s = before.toString();
            if (s.endsWith(". ") || s.endsWith("! ") || s.endsWith("? ")
                    || s.endsWith("\n")) cap = true;
        }
        if (cap && shiftState == 0) { shiftState = 1; refreshShiftLabels(); }
        if (!cap && shiftState == 1 && before != null && before.length() > 0
                && !endsWithSentenceSpace(before.toString())) {
            // A one-shot shift consumed by context change (e.g. cursor moved).
            // Leave as-is; letter keys consume it. Nothing to do.
        }
    }

    private boolean endsWithSentenceSpace(String s) {
        return s.endsWith(". ") || s.endsWith("! ") || s.endsWith("? ") || s.endsWith("\n");
    }

    /** Word characters for the before-cursor scans (fix / current word). */
    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '\'' || c == '&' || c == '-';
    }

    private void clearBoundarySuggestions() {
        boundaryWord = null;
        boundaryOriginal = null;
        boundaryIsUndo = false;
        boundarySuggestions.clear();
    }

    /**
     * Sentence start for the typed path: field start (nothing, or only
     * whitespace, before the word) or the text before the word ends a
     * sentence — the same signals autoShiftFromContext uses (". " /
     * "! " / "? " / newline), plus a bare sentence punctuation directly
     * against the word ("...end.Teh").
     */
    private boolean isSentenceStart(String before, int wordStart) {
        String pre = before.substring(0, wordStart);
        if (pre.trim().isEmpty()) return true;
        return endsWithSentenceSpace(pre)
                || pre.endsWith(".") || pre.endsWith("!") || pre.endsWith("?")
                || pre.endsWith("\n");
    }

    /**
     * Autocorrect the word immediately before the cursor. Runs when a
     * separator is typed, so a dictated-style garble typed by hand gets
     * the same treatment as voice text.
     *
     * v3.3 WS2 order of authority:
     *   1. Personal layer — engine.correctToken runs FIRST and wins.
     *   2. Typed layer — engine.correctTyped(word, sentenceStart), only
     *      when the personal layer made no change. A change is applied
     *      and the ORIGINAL word becomes strip slot 0 (undo: tapping it
     *      restores the original and suppresses typed re-correction of
     *      that one instance — see suppressTypedWord).
     *   3. Ambiguous — correctTyped returned null but typedSuggestions
     *      is non-empty: the strip shows those suggestions; tapping one
     *      replaces the just-finished word (pickSuggestion boundary path).
     */
    private void fixWordBeforeCursor() {
        if (passwordField) return;
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        CharSequence before = ic.getTextBeforeCursor(40, 0);
        if (before == null || before.length() == 0) return;
        String s = before.toString();
        int end = s.length();
        int start = end;
        while (start > 0 && isWordChar(s.charAt(start - 1))) start--;
        if (start == end) return;
        String word = s.substring(start, end);
        if (word.length() < 2) return;
        // 1. Personal layer first, and it wins outright.
        String fixed = engine.correctToken(word, null);
        if (fixed != null && !fixed.equals(word)) {
            suppressTypedWord = null; // a boundary elsewhere consumes the flag
            ic.deleteSurroundingText(word.length(), 0);
            ic.commitText(fixed, 1);
            return;
        }
        // One-shot undo suppression: consumed at this boundary either way.
        if (suppressTypedWord != null) {
            boolean suppressedInstance = suppressTypedWord.equals(word);
            suppressTypedWord = null;
            if (suppressedInstance) return; // leave the restored word alone, once
        }
        // 2./3. Typed layer (general English: contractions, typos, dictionary).
        boolean sentenceStart = isSentenceStart(s, start);
        String typedFixed = engine.correctTyped(word, sentenceStart);
        if (typedFixed != null && !typedFixed.equals(word)) {
            ic.deleteSurroundingText(word.length(), 0);
            ic.commitText(typedFixed, 1);
            boundaryWord = typedFixed;
            boundaryOriginal = word;
            boundaryIsUndo = true;
            boundarySuggestions.clear();
            boundarySuggestions.add(word); // slot 0 = original = undo
            return;
        }
        List<String> sugg = engine.typedSuggestions(word, sentenceStart);
        if (sugg != null && !sugg.isEmpty()) {
            boundaryWord = word;
            boundaryOriginal = null;
            boundaryIsUndo = false;
            boundarySuggestions.clear();
            for (String cand : sugg) {
                if (cand != null && !cand.equals(word) && !boundarySuggestions.contains(cand)) {
                    boundarySuggestions.add(cand);
                }
                if (boundarySuggestions.size() >= 3) break;
            }
            if (boundarySuggestions.isEmpty()) clearBoundarySuggestions();
        }
    }

    private String currentWord() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return "";
        CharSequence before = ic.getTextBeforeCursor(40, 0);
        if (before == null) return "";
        String s = before.toString();
        int start = s.length();
        while (start > 0) {
            char c = s.charAt(start - 1);
            if (Character.isLetterOrDigit(c) || c == '\'' || c == '-') start--;
            else break;
        }
        return s.substring(start);
    }

    // ---------------- suggestions strip ----------------

    private final List<String> currentSuggestions = new ArrayList<>();

    private void updateStrip() {
        if (stripBox == null) return;
        currentSuggestions.clear();
        if (!passwordField && boundaryWord != null && !boundarySuggestions.isEmpty()) {
            // v3.3 WS2: boundary state (undo original / typed candidates
            // for the just-finished word) takes the strip until fresh
            // input invalidates it.
            currentSuggestions.addAll(boundarySuggestions);
        } else if (!passwordField) {
            String w = currentWord();
            if (w.length() >= 2) currentSuggestions.addAll(engine.suggest(w));
        }
        for (int i = 0; i < stripButtons.size(); i++) {
            Button b = stripButtons.get(i);
            if (i < currentSuggestions.size()) {
                b.setText(currentSuggestions.get(i));
                b.setVisibility(View.VISIBLE);
            } else {
                b.setVisibility(View.GONE);
            }
        }
        if (!listening && !clickCountingDown) refreshRestingStrip();
    }

    private void pickSuggestion(int idx) {
        cancelCountdownOnTap();
        if (idx >= currentSuggestions.size()) return;
        if (boundaryWord != null && !boundarySuggestions.isEmpty()
                && idx < boundarySuggestions.size()
                && currentSuggestions.get(idx).equals(boundarySuggestions.get(idx))) {
            pickBoundarySuggestion(idx);
            return;
        }
        String word = currentWord();
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        if (!word.isEmpty()) ic.deleteSurroundingText(word.length(), 0);
        ic.commitText(currentSuggestions.get(idx), 1);
        ic.commitText(" ", 1);
        afterCommit();
    }

    /**
     * v3.3 WS2: tap on a boundary strip slot. The target word sits before
     * the separator that triggered the fix, so this replaces
     * word + one trailing separator char (preserving that separator),
     * unlike the in-progress-word path in pickSuggestion. For the undo
     * slot, the replacement also arms the one-shot suppression so the
     * restored word is not immediately re-corrected.
     */
    private void pickBoundarySuggestion(int idx) {
        String replacement = boundarySuggestions.get(idx);
        boolean isUndoPick = boundaryIsUndo && replacement.equals(boundaryOriginal);
        String expectedInField = boundaryWord;
        String restoredOriginal = boundaryOriginal;
        InputConnection ic = getCurrentInputConnection();
        clearBoundarySuggestions();
        if (ic == null) { updateStrip(); return; }
        CharSequence beforeCs = ic.getTextBeforeCursor(40, 0);
        if (beforeCs == null) { updateStrip(); return; }
        String s = beforeCs.toString();
        int end = s.length();
        int wordEnd = end;
        String suffix = "";
        if (wordEnd > 0 && !isWordChar(s.charAt(wordEnd - 1))) {
            suffix = s.substring(wordEnd - 1, wordEnd); // the separator that ended the word
            wordEnd--;
        }
        int start = wordEnd;
        while (start > 0 && isWordChar(s.charAt(start - 1))) start--;
        if (!s.substring(start, wordEnd).equals(expectedInField)) {
            updateStrip(); // stale strip state — field no longer matches; do nothing
            return;
        }
        ic.deleteSurroundingText(end - start, 0);
        ic.commitText(replacement + (suffix.isEmpty() ? " " : suffix), 1);
        if (isUndoPick) suppressTypedWord = restoredOriginal;
        afterCommit();
    }

    private void setStripStatus(String text) {
        if (stripStatus != null) stripStatus.setText(text);
    }

    // ---------------- clipboard panel (v3.3 WS3) ----------------
    //
    // The IME is the active input method, which is the platform's
    // sanctioned route to the current clipboard: read it directly via
    // ClipboardManager at the moment it is needed. No background
    // polling, no new permissions, no INTERNET.

    /**
     * Read the CURRENT clip into the pending* cache. Called fresh on
     * panel open, onStartInput and onStartInputView, so a screenshot
     * taken just before opening the keyboard shows up. Any failure
     * (no clip, access error) leaves the cache empty — the panel then
     * shows "Clipboard is empty" rather than failing silently.
     */
    private void refreshClipboardCache() {
        pendingClipText = null;
        pendingClipUri = null;
        pendingClipDesc = null;
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) return;
            ClipData clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return;
            ClipDescription desc = clip.getDescription();
            pendingClipDesc = desc;
            boolean descIsImage = false;
            if (desc != null) {
                for (int i = 0; i < desc.getMimeTypeCount(); i++) {
                    String m = desc.getMimeType(i);
                    if (m != null && m.startsWith("image/")) { descIsImage = true; break; }
                }
            }
            ClipData.Item item = clip.getItemAt(0);
            Uri uri = item.getUri();
            CharSequence text = item.getText();
            if (uri != null && (descIsImage || text == null)) {
                pendingClipUri = uri; // image clip (e.g. a screenshot)
            } else if (text != null) {
                pendingClipText = text.toString();
            } else if (uri != null) {
                pendingClipUri = uri;
            } else {
                // Structured text (HTML/intent clips): coerce as a last resort.
                CharSequence coerced = item.coerceToText(this);
                if (coerced != null && coerced.length() > 0) pendingClipText = coerced.toString();
            }
        } catch (Exception e) {
            pendingClipText = null;
            pendingClipUri = null;
            pendingClipDesc = null;
        }
    }

    private void toggleClipboardPanel() {
        if (clipboardPanelOpen) closeClipboardPanel();
        else buildClipboardPanel();
    }

    private void closeClipboardPanel() {
        clipboardPanelOpen = false;
        buildKeys();
    }

    /** Replace the key rows with the clipboard panel (fresh clip read first). */
    private void buildClipboardPanel() {
        if (keysBox == null) return;
        refreshClipboardCache(); // read at this exact moment
        clipboardPanelOpen = true;
        keysBox.removeAllViews();
        letterButtons.clear();
        shiftButton = null;

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        Button back = new Button(this);
        styleKey(back);
        back.setText("⌨ ABC");
        back.setOnClickListener(v -> closeClipboardPanel());
        top.addView(back, new LinearLayout.LayoutParams(0, dp(52), 1.3f));
        TextView title = new TextView(this);
        title.setText("Clipboard");
        title.setTextColor(Color.rgb(170, 178, 190));
        title.setTextSize(15);
        title.setGravity(Gravity.CENTER);
        top.addView(title, new LinearLayout.LayoutParams(0, dp(52), 2f));
        keysBox.addView(top);

        if (pendingClipText != null && !pendingClipText.isEmpty()) {
            String oneLine = pendingClipText.replace('\n', ' ').replace('\r', ' ');
            if (oneLine.length() > 90) oneLine = oneLine.substring(0, 90) + "…";
            Button paste = new Button(this);
            styleKey(paste);
            paste.setTextSize(14);
            paste.setText("📋 " + oneLine);
            paste.setOnClickListener(v -> pasteClipText());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(64));
            lp.setMargins(dp(2), dp(3), dp(2), dp(3));
            keysBox.addView(paste, lp);
        } else if (pendingClipUri != null) {
            Bitmap thumb = decodeClipThumbnail(pendingClipUri);
            if (thumb != null) {
                ImageView iv = new ImageView(this);
                iv.setImageBitmap(thumb);
                iv.setAdjustViewBounds(true);
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                iv.setBackgroundColor(Color.rgb(52, 56, 64));
                iv.setOnClickListener(v -> pasteClipImage());
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(120));
                lp.setMargins(dp(2), dp(3), dp(2), dp(3));
                keysBox.addView(iv, lp);
                TextView hint = new TextView(this);
                hint.setText("Tap image to paste");
                hint.setTextColor(Color.rgb(170, 178, 190));
                hint.setTextSize(13);
                hint.setGravity(Gravity.CENTER);
                keysBox.addView(hint);
            } else {
                // Thumbnail unreadable — still offer the insert attempt;
                // commitContent does not need us to decode the image.
                Button paste = new Button(this);
                styleKey(paste);
                paste.setTextSize(14);
                paste.setText("🖼 Image — tap to paste");
                paste.setOnClickListener(v -> pasteClipImage());
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(64));
                lp.setMargins(dp(2), dp(3), dp(2), dp(3));
                keysBox.addView(paste, lp);
            }
        } else {
            TextView empty = new TextView(this);
            empty.setText("Clipboard is empty");
            empty.setTextColor(Color.rgb(170, 178, 190));
            empty.setTextSize(15);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(24), 0, dp(24));
            keysBox.addView(empty, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(80)));
        }
    }

    /**
     * Bounds-limited thumbnail decode (~120dp target): probe bounds
     * first, then decode with a power-of-two sample size. The full-size
     * image is never decoded. Returns null on any failure.
     */
    private Bitmap decodeClipThumbnail(Uri uri) {
        InputStream in = null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            in = getContentResolver().openInputStream(uri);
            if (in == null) return null;
            BitmapFactory.decodeStream(in, null, bounds);
            try { in.close(); } catch (Exception ignored) { }
            in = null;
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            int target = dp(120);
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= target
                    && bounds.outHeight / (sample * 2) >= target) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            in = getContentResolver().openInputStream(uri);
            if (in == null) return null;
            return BitmapFactory.decodeStream(in, null, opts);
        } catch (Exception e) {
            return null;
        } finally {
            if (in != null) { try { in.close(); } catch (Exception ignored) { } }
        }
    }

    /**
     * Paste the cached text clip via commitText.
     * LEARNING GUARD (primary arm): lastFieldSnapshot is resynced to the
     * post-paste field text, so the commit-boundary delta observer sees
     * no paste delta and can learn nothing from it. If the field text
     * cannot be re-read, suppressLearnForNextDelta stays set and the
     * observer skips exactly one delta instead.
     */
    private void pasteClipText() {
        String text = pendingClipText;
        if (text == null || text.isEmpty()) return;
        suppressLearnForNextDelta = true;
        commit(text);
        String after = readFieldText();
        if (after != null) {
            lastFieldSnapshot = after;
            suppressLearnForNextDelta = false; // resync provably covers the paste
        }
        closeClipboardPanel();
        setStripStatus("");
        updateStrip();
    }

    /**
     * Insert the cached image clip via the framework recipe:
     * InputContentInfo + InputConnection.commitContent with
     * INPUT_CONTENT_GRANT_READ_URI_PERMISSION (the flag is what grants
     * the target app read access to the clip Uri).
     * Support is checked against EditorInfo.contentMimeTypes first:
     * declared-without-image is a visible no-op (never attempted);
     * undeclared is attempted and a false return gets the same message.
     * Failures are always visible in the strip — never silent, never a crash.
     */
    private void pasteClipImage() {
        Uri uri = pendingClipUri;
        if (uri == null) return;
        EditorInfo ei = getCurrentInputEditorInfo();
        String[] mimes = ei != null ? ei.contentMimeTypes : null;
        if (mimes != null) {
            boolean supportsImage = false;
            for (String m : mimes) {
                if (m != null && (m.equals("image/*") || m.startsWith("image/"))) {
                    supportsImage = true;
                    break;
                }
            }
            if (!supportsImage) {
                setStripStatus("This app doesn't accept pasted images");
                return; // panel stays open; the refusal is the visible outcome
            }
        }
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) {
            setStripStatus("Couldn't paste that image here");
            return;
        }
        suppressLearnForNextDelta = true;
        boolean ok = false;
        try {
            ClipDescription desc = pendingClipDesc != null ? pendingClipDesc
                    : new ClipDescription("Image", new String[]{"image/*"});
            InputContentInfo info = new InputContentInfo(uri, desc);
            ok = ic.commitContent(info,
                    InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null);
        } catch (Exception e) {
            setStripStatus("Couldn't paste that image here");
            suppressLearnForNextDelta = false; // nothing was committed
            return;
        }
        if (!ok) {
            suppressLearnForNextDelta = false; // nothing was committed
            setStripStatus("This app doesn't accept pasted images");
            return;
        }
        // Success: same learning guard as text paste (an image insert
        // should not shift the snapshot the delta observer diffs against).
        String after = readFieldText();
        if (after != null) {
            lastFieldSnapshot = after;
            suppressLearnForNextDelta = false;
        }
        closeClipboardPanel();
        setStripStatus("");
        updateStrip();
    }

    // ---------------- v3.4 ClickSend machinery ----------------

    /** Start the parallel capture + detector. Recognizer starts first, so
     *  on devices that feed only one capture, dictation wins and this
     *  degrades silently (see the deaf check) instead of breaking voice. */
    private void startClickWatch() {
        ui.removeCallbacks(clickGraceStop);
        if (clickWatchOn) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) return;
        final int sr = 16000;
        int minBuf = AudioRecord.getMinBufferSize(sr,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) return;
        AudioRecord rec;
        try {
            rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    sr, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(minBuf, sr / 2));
        } catch (Exception e) {
            return;
        }
        if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
            try { rec.release(); } catch (Exception ignored) { }
            return;
        }
        if (clickDetector == null) clickDetector = new ClickDetector(sr, clickListener);
        clickDetector.reset();
        clickRec = rec;
        clickWatchOn = true;
        clickDeafNoted = false;
        rmsSessionMin = Float.MAX_VALUE;
        rmsSessionMax = -Float.MAX_VALUE;
        try {
            rec.startRecording();
        } catch (Exception e) {
            stopClickWatch();
            return;
        }
        Thread t = new Thread(new Runnable() {
            @Override public void run() { runClickCapture(); }
        }, "clicksend-capture");
        t.setDaemon(true);
        t.start();
        ui.postDelayed(new Runnable() {
            @Override public void run() { checkClickAudibility(); }
        }, 3000);
    }

    private void runClickCapture() {
        short[] buf = new short[640]; // 40ms at 16kHz
        AudioRecord rec = clickRec;
        while (clickWatchOn && rec != null) {
            int n;
            try {
                n = rec.read(buf, 0, buf.length);
            } catch (Exception e) {
                break;
            }
            if (n > 0) {
                ClickDetector d = clickDetector;
                if (d != null) d.feed(buf, n);
            } else if (n < 0) {
                break;
            }
        }
    }

    private void stopClickWatch() {
        ui.removeCallbacks(clickGraceStop);
        ui.removeCallbacks(clickTick1);
        ui.removeCallbacks(clickFire);
        clickCountingDown = false;
        if (!clickWatchOn) return;
        clickWatchOn = false;
        AudioRecord rec = clickRec;
        clickRec = null;
        if (rec != null) {
            try { rec.stop(); } catch (Exception ignored) { }
            try { rec.release(); } catch (Exception ignored) { }
        }
    }

    /** Best-effort platform check: if the recognizer clearly hears sound
     *  (its RMS swings) while our recorder has heard nothing at all, this
     *  device is feeding the recorder silence. Say so once; dictation is
     *  unaffected either way. */
    private void checkClickAudibility() {
        if (!clickWatchOn || clickDeafNoted || clickDetector == null) return;
        boolean recognizerHears = rmsSessionMax - rmsSessionMin > 5f;
        if (recognizerHears && clickDetector.getMaxFrameRms() < ClickDetector.ABS_MIN_RMS) {
            clickDeafNoted = true;
            setStripStatus("Click\u00d73 send can\u2019t hear the mic on this phone \u2014 dictation unaffected.");
        }
    }

    /** After a result commits, the recognizer session is over — but
     *  dictate-THEN-click is the natural order, so keep the watch armed
     *  for a bounded grace with a strip hint. */
    private void armClickGrace() {
        if (!clickWatchOn) return;
        setStripStatus("\ud83c\udfa4 Click \u00d73 = SEND (30s)");
        ui.removeCallbacks(clickGraceStop);
        ui.postDelayed(clickGraceStop, 30000);
    }

    private void onClickTripleDetected() {
        if (clickCountingDown) return;
        clickCountingDown = true;
        // The clicks must never land as dictated text, and a live session
        // must not deliver results over the send: cancel it outright.
        if (listening && recognizer != null) {
            try { recognizer.cancel(); } catch (Exception ignored) { }
            listening = false;
            if (micButton != null) micButton.setText("\ud83c\udfa4");
        }
        ui.removeCallbacks(clickGraceStop);
        beep(ToneGenerator.TONE_PROP_BEEP, 150);
        // The measured trailer lands ~210ms after the third click;
        // suspend cancel-clicks briefly so it can't cancel his own send.
        clickCancelArmedAtMs = SystemClock.uptimeMillis() + 350;
        setStripStatus("Sending in 2 \u2014 click or tap any key to cancel");
        ui.postDelayed(clickTick1, 1000);
        ui.postDelayed(clickFire, 2000);
    }

    private void onClickAnyDetected() {
        if (!clickCountingDown) return;
        if (SystemClock.uptimeMillis() < clickCancelArmedAtMs) return;
        cancelClickSend();
    }

    private void cancelClickSend() {
        if (!clickCountingDown) return;
        clickCountingDown = false;
        ui.removeCallbacks(clickTick1);
        ui.removeCallbacks(clickFire);
        beep(ToneGenerator.TONE_PROP_NACK, 120);
        setStripStatus("Send cancelled \u2014 click \u00d73 to try again.");
        if (clickDetector != null) clickDetector.reset();
        // Re-arm the grace window so he can retry without re-dictating.
        ui.removeCallbacks(clickGraceStop);
        ui.postDelayed(clickGraceStop, 30000);
    }

    private void completeClickSend() {
        if (!clickCountingDown) return;
        clickCountingDown = false;
        stopClickWatch();
        setStripStatus("");
        onEnterKey(); // the existing send/enter path — identical to the key
    }

    /** Any key tap during the countdown cancels it (the tap itself then
     *  proceeds normally). Enter is handled separately in onEnterKey:
     *  there, a tap during countdown means "send now". */
    private void cancelCountdownOnTap() {
        if (clickCountingDown) cancelClickSend();
    }

    private void beep(int tone, int ms) {
        try {
            if (toneGen == null) {
                toneGen = new ToneGenerator(AudioManager.STREAM_MUSIC, 80);
            }
            toneGen.startTone(tone, ms);
        } catch (Exception ignored) { }
    }

    // ---------------- voice ----------------

    private void onMicKey() {
        if (clickCountingDown) { cancelClickSend(); return; }
        if (listening) {
            stopListening();
            stopClickWatch();
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            setStripStatus("Mic needs permission — open the CodeNDrive app once and tap \u201CGrant microphone\u201D.");
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            setStripStatus("No speech recognizer on this phone.");
            return;
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(new VoiceListener());
        }
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US");
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        try {
            recognizer.startListening(intent);
            listening = true;
            startClickWatch();
            if (micButton != null) micButton.setText("🔴");
            setStripStatus("Listening… tap 🎤 again to stop.");
        } catch (Exception e) {
            setStripStatus("Voice failed to start.");
            listening = false;
        }
    }

    private void stopListening() {
        if (recognizer != null) {
            try { recognizer.stopListening(); } catch (Exception ignored) { }
        }
    }

    private class VoiceListener implements RecognitionListener {
        @Override public void onReadyForSpeech(Bundle params) { }
        @Override public void onBeginningOfSpeech() { }
        @Override public void onRmsChanged(float rmsdB) {
            if (rmsdB < rmsSessionMin) rmsSessionMin = rmsdB;
            if (rmsdB > rmsSessionMax) rmsSessionMax = rmsdB;
        }
        @Override public void onBufferReceived(byte[] buffer) { }
        @Override public void onEndOfSpeech() { }
        @Override public void onError(int error) {
            listening = false;
            if (micButton != null) micButton.setText("\ud83c\udfa4");
            // A cancel() from ClickSend's triple lands here: while the
            // countdown owns the strip and the watch, stay out of its way.
            if (clickCountingDown) return;
            stopClickWatch();
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                setStripStatus("Mic needs permission — open the CodeNDrive app once and tap \u201CGrant microphone\u201D.");
            } else if (error == SpeechRecognizer.ERROR_NO_MATCH
                    || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                setStripStatus("Didn't catch that — tap 🎤 to try again.");
            } else {
                setStripStatus("Voice error " + error + " — tap 🎤 to retry.");
            }
        }
        @Override public void onResults(Bundle results) {
            // Results racing in after ClickSend cancelled the session are
            // the click segment itself — never commit them.
            if (clickCountingDown) return;
            listening = false;
            if (micButton != null) micButton.setText("🎤");
            ArrayList<String> texts = results != null
                    ? results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) : null;
            if (texts != null && !texts.isEmpty()) {
                String raw = texts.get(0);
                // v3.1 field fix: re-read the layer toggles at the moment
                // of correction, so the voice path never runs on stale
                // toggle state captured at an earlier onStartInput.
                loadPrefs();
                String cleaned;
                if (spellMode) {
                    // SPELL: the letter pass IS the result — the exact
                    // lowercase string, no correction pipeline, and no
                    // trailing space (exactness is the whole point).
                    cleaned = CodeText.spell(raw);
                    commit(cleaned);
                } else if (codeMode) {
                    // CODE: spoken symbol names become glyphs, always.
                    // The deterministic pass replaces the prose pipeline
                    // for this result; modes off = inherited path below.
                    cleaned = CodeText.codeSymbols(raw);
                    commit(cleaned);
                    if (!cleaned.endsWith(" ") && !cleaned.endsWith("\n")) commit(" ");
                } else {
                    cleaned = engine.correctVoice(raw);
                    commit(cleaned);
                    // Dictation flows sentence to sentence: add a trailing space
                    // unless the text already ends with one.
                    if (!cleaned.endsWith(" ") && !cleaned.endsWith("\n")) commit(" ");
                }
                // Voice results are final committed text by definition:
                // observe exactly what was committed, then resync the
                // snapshot so the delta observer cannot count it twice.
                if (engine.learningOn && !learningExcluded) {
                    engine.observeCommittedText(cleaned, currentInputType,
                            System.currentTimeMillis());
                    saveLearning();
                }
                String after = readFieldText();
                if (after != null) lastFieldSnapshot = after;
                setStripStatus("");
                afterCommit();
                armClickGrace();
            } else {
                stopClickWatch();
                setStripStatus("Didn't catch that — tap 🎤 to try again.");
            }
        }
        @Override public void onPartialResults(Bundle partialResults) {
            ArrayList<String> texts = partialResults != null
                    ? partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) : null;
            if (texts != null && !texts.isEmpty()) {
                String p = texts.get(0);
                // In a mode, show the mode's own output forming — the
                // spelled letters / glyphs — not the raw recognizer text.
                if (spellMode) p = "✍ " + CodeText.spell(p);
                else if (codeMode) p = CodeText.codeSymbols(p);
                if (p.length() > 42) p = "…" + p.substring(p.length() - 42);
                setStripStatus(p);
            }
        }
        @Override public void onEvent(int eventType, Bundle params) { }
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        stopClickWatch();
        // Commit boundary: keyboard is going away, field text is final.
        observeCommittedDelta();
        // v3.3: the clipboard panel never survives the input view.
        if (clipboardPanelOpen) { clipboardPanelOpen = false; buildKeys(); }
        super.onFinishInputView(finishingInput);
    }

    @Override
    public void onFinishInput() {
        stopClickWatch();
        observeCommittedDelta();
        super.onFinishInput();
    }

    @Override
    public void onDestroy() {
        stopClickWatch();
        if (toneGen != null) {
            try { toneGen.release(); } catch (Exception ignored) { }
            toneGen = null;
        }
        if (recognizer != null) {
            try { recognizer.destroy(); } catch (Exception ignored) { }
            recognizer = null;
        }
        super.onDestroy();
    }
}
