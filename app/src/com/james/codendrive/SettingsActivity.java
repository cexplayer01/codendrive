package com.james.codendrive;

import android.Manifest;
import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.james.codendrive.engine.CorrectionEngine;

/**
 * Settings for CodeNDrive: per-layer engine toggles, the mic permission
 * grant, and a read-only view of the built-in lexicon.
 */
public class SettingsActivity extends Activity {

    private static final String PREFS = "cnd_prefs";
    private SharedPreferences prefs;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(40, 40, 40, 40);

        TextView title = new TextView(this);
        title.setText("CodeNDrive");
        title.setTextSize(24);
        col.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Your keyboard, with your words in it. Dictate with the 🎤 key and "
                + "your names, projects, and places come out spelled right — and stray "
                + "periods that fake websites get cleaned up before they land.");
        sub.setTextSize(14);
        sub.setPadding(0, 12, 0, 20);
        col.addView(sub);

        Button mic = new Button(this);
        mic.setText(checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                        == PackageManager.PERMISSION_GRANTED
                ? "Microphone: granted ✓"
                : "Grant microphone (needed for the 🎤 key)");
        mic.setOnClickListener(v -> requestPermissions(
                new String[]{Manifest.permission.RECORD_AUDIO}, 7));
        col.addView(mic);

        col.addView(sectionLabel("Correction layers"));
        addToggle(col, "esc", "Escape cleanup — turn literal \\n into a real line break or space");
        addToggle(col, "dom", "Domain repair — 'crumb and craft dot com' becomes crumbandcraft.com");
        addToggle(col, "dec", "Decode map — your known dictation garbles (net lafaye → Netlify)");
        addToggle(col, "snap", "Vocabulary snap — near-miss words snap to your names and projects");
        addToggle(col, "per", "Period discipline — kill stray mid-sentence period breaks in dictation");
        addToggle(col, "gate", "Homophone gates — carrot vs carat vs caret, solder not soldier, sell not cell (context decides)");
        addToggle(col, "sym", "Symbol glyphs — dictated 'asterisk'/'backslash'/'tilde' become * \\ ~");
        addToggle(col, "learn", "Learning — remember words you send often and leave them alone (stays on this phone)");

        col.addView(sectionLabel("Words the keyboard has learned"));
        TextView learnNote = new TextView(this);
        learnNote.setText("Words you use a lot in finished messages get remembered on "
                + "this phone only — the keyboard has no internet access, so they can't "
                + "leave the phone. A learned word is left alone by the corrections; "
                + "it never replaces a different word. Words you stop using fade "
                + "away on their own after about two months.");
        learnNote.setTextSize(14);
        col.addView(learnNote);

        CorrectionEngine learnView = new CorrectionEngine();
        loadLearned(learnView);
        java.util.List<String> learned = learnView.learnedWords(System.currentTimeMillis());
        if (learned.isEmpty()) {
            TextView none = new TextView(this);
            none.setText("Nothing learned yet — a word joins this list after you send "
                    + "it in finished text four times.");
            none.setTextSize(14);
            none.setPadding(0, 8, 0, 0);
            col.addView(none);
        } else {
            for (final String w : learned) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                TextView tw = new TextView(this);
                tw.setText(w);
                tw.setTextSize(15);
                row.addView(tw, new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                Button del = new Button(this);
                del.setText("Delete");
                del.setOnClickListener(v -> {
                    CorrectionEngine e2 = new CorrectionEngine();
                    loadLearned(e2);
                    e2.removeLearned(w);
                    saveLearned(e2);
                    recreate();
                });
                row.addView(del);
                col.addView(row);
            }
        }
        Button clearLearned = new Button(this);
        clearLearned.setText("Clear learned data");
        clearLearned.setOnClickListener(v -> {
            new java.io.File(getFilesDir(), CodeNDriveService.LEARN_FILE).delete();
            recreate();
        });
        col.addView(clearLearned);

        col.addView(sectionLabel("How to turn the keyboard on"));
        TextView how = new TextView(this);
        how.setText("Settings → General management → Keyboard list and default → "
                + "turn on CodeNDrive → Default keyboard → CodeNDrive.");
        how.setTextSize(14);
        col.addView(how);

        col.addView(sectionLabel("Your built-in vocabulary"));
        TextView lex = new TextView(this);
        StringBuilder sb = new StringBuilder();
        for (String e : new CorrectionEngine().lexiconEntries()) {
            sb.append("• ").append(e).append("\n");
        }
        lex.setText(sb.toString());
        lex.setTextSize(13);
        col.addView(lex);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(col);
        setContentView(scroll);
    }

    private TextView sectionLabel(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(17);
        t.setPadding(0, 28, 0, 8);
        t.setGravity(Gravity.START);
        return t;
    }

    /** Read the learning file the IME service maintains (same format). */
    private void loadLearned(CorrectionEngine e) {
        try {
            java.io.File f = new java.io.File(getFilesDir(), CodeNDriveService.LEARN_FILE);
            if (!f.exists()) return;
            byte[] buf = new byte[(int) f.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            int off = 0, n;
            while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) off += n;
            in.close();
            e.loadLearning(new String(buf, 0, off, java.nio.charset.StandardCharsets.UTF_8),
                    System.currentTimeMillis());
        } catch (Exception ignored) { }
    }

    private void saveLearned(CorrectionEngine e) {
        try {
            java.io.FileOutputStream out = new java.io.FileOutputStream(
                    new java.io.File(getFilesDir(), CodeNDriveService.LEARN_FILE));
            out.write(e.dumpLearning().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.close();
        } catch (Exception ignored) { }
    }

    private void addToggle(LinearLayout col, String key, String label) {
        CheckBox cb = new CheckBox(this);
        cb.setText(label);
        cb.setTextSize(14);
        cb.setChecked(prefs.getBoolean(key, true));
        cb.setOnCheckedChangeListener((bv, checked) ->
                prefs.edit().putBoolean(key, checked).apply());
        col.addView(cb);
    }
}
