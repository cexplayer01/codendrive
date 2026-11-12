# CodeNDrive

An Android keyboard for people who dictate **code** — while driving,
walking, or otherwise nowhere near a desk.

It is a fork of the James Keyboard / ContextGate line: a small,
from-scratch input method (pure Java, no AndroidX, no Gradle) built
around a correction engine whose rule is that it may only change a word
when the surrounding words prove the change is right. CodeNDrive keeps
that engine and adds the two things a speech recognizer will never do
reliably on its own:

## Automatic — no modes, no buttons

There are no mode keys. Every dictation result is passed through one
deterministic combined transform, automatically:

**Spelled runs collapse.** Spell a word and it comes out as the exact
lowercase string, every time: *"ess you pee ay bee ay ess ee"* →
`supabase`. No capitalization, no "helpful" substitution of a real
word, no sentence-casing. Letter names, digits, and the identifier
symbols (underscore, dash, dot) are recognized inline inside a spelled
run, so identifiers survive dictation intact. A token that isn't a
letter is dropped from the run, never guessed: deterministic beats
helpful. While you spell, the strip shows the word forming.

**Symbol names become glyphs.** Spoken symbol names come out as
glyphs, never literal words: underscore → `_`, open paren → `(`,
fat arrow → `=>`, backslash → `\`, at sign → `@`, backtick → `` ` `` —
the whole table, applied by a fixed deterministic pass with one fixed
spacing rule (glyphs hug their neighbours; letter and number runs hug
each other; words stay spaced). *"open paren ex plus one close paren"*
comes out as `(x+1)`. Say "space", "tab" or "new line" and the
whitespace lands exactly where you said it.

Ordinary prose is untouched: a spelled run or a symbol name triggers
the transform, anything else passes through exactly as the recognizer
heard it — *"hello world this is a test"* comes out as `hello world
this is a test`.

## ClickSend (inherited)

Dictate a message, then click your tongue **three times**: the
keyboard sends immediately and beeps as it fires — completely
hands-free. There is no countdown and no cancel window: a false
triple is an instant send, and that's the accepted trade. The
detector is armed only around dictation; it is calibrated so that
ordinary speech and stray mouth noise cannot fire it.

## Privacy

The manifest asks for exactly one permission: the microphone, for
dictation. There is no INTERNET permission — nothing the keyboard
learns can leave the phone. The learning tier lives in app-private
storage on the device.

## The demo pack

The correction engine's power comes from a *personal* layer: the
names, projects and places of one person's life. That layer is data
about a person, so this public tree ships it with an **invented demo
persona** — Marisol Vega, her bakery businesses (Crumb & Craft,
BakeBoard), her town (Riverton) — exercising every mechanism
(person snap, gated project words, spoken-domain repair) with zero
real personal data. Swap in your own pack to make it yours.

The typed-autocorrect dictionary is vendored from
[dwyl/english-words](https://github.com/dwyl/english-words)
(words_alpha.txt, The Unlicense) — see `engine/WordListData.java`.

## Install

Grab the APK from this repo's Releases, allow install from your
browser/files app, then: **Settings → System → Languages & input →
On-screen keyboard → CodeNDrive → Default keyboard → CodeNDrive.**
It coexists happily with other keyboards, including its parent.

## Build

Needs JDK 17 and the Android SDK (platform android-36, build-tools
36.0.0). Create your own signing keystore (see `build.sh`), then:

```sh
KEYSTORE_PASS=yourpass ./build.sh 1.0.0
```

The APK lands in `out/`. Host-side tests for the deterministic
transform live in `tests/CodeTextBattery.java` (plain `main()`, no
dependencies): every symbol in the table, every spelling rule, and a
set of prose pass-through cases are covered by exact-match cases.

## License

MIT — see [LICENSE](LICENSE). Copyright (c) 2026 James Van Daele.
