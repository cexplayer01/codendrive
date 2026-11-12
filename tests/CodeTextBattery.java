import com.james.codendrive.CodeText;

/**
 * CodeNDrive deterministic-pass battery. Plain main() harness, no
 * dependencies — same shape as the tree's engine Battery. Every case
 * is an exact string equality; nothing here is fuzzy.
 *
 * v1.0.3: the mode keys are gone, so every case runs through
 * CodeText.autoApply — the old SPELL cases (a spelled run collapses,
 * junk inside the run drops) and the old CODE cases (symbol names
 * become glyphs, spacing hugs) re-expressed against the combined
 * pass, plus prose-safety cases: ordinary dictated sentences must
 * come through byte-identical.
 */
public class CodeTextBattery {
    static int pass = 0, fail = 0;

    static void auto(String name, String in, String want) {
        check("AUTO " + name, CodeText.autoApply(in), want);
    }

    static void check(String name, String got, String want) {
        if (got.equals(want)) { pass++; System.out.println("PASS " + name); }
        else {
            fail++;
            System.out.println("FAIL " + name);
            System.out.println("   want: " + esc(want));
            System.out.println("   got:  " + esc(got));
        }
    }

    static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t");
    }

    public static void main(String[] args) {
        // ---------- formerly SPELL: spelled runs collapse ----------
        auto("supabase", "ess you pee ay bee ay ess ee", "supabase");
        auto("identifier with underscore", "ess you pee ay underscore bee ay ess ee", "supa_base");
        auto("workflow", "double you oh ar kay ef el oh double you", "workflow");
        auto("junk token dropped", "ess banana you pee", "sup");
        auto("dash inside", "jay ay vee ay dash ess cee ar eye pee tee", "java-script");
        auto("xyz", "ex why zee", "xyz");
        auto("omg", "oh em gee", "omg");
        auto("digit inside", "pee why three", "py3");
        auto("double-you pair", "double you eye en", "win");
        auto("capitalized input lowercased", "Ay Bee Cee", "abc");
        auto("dot inside", "dee ee vee dot en ee tee", "dev.net");
        auto("hyphenated letter", "double-you eye en", "win");
        auto("single letters", "a b c", "abc");
        // Was SPELL "all junk" (expected ""): under auto-apply there
        // is no mode in which prose is dropped — plain words are
        // prose and pass through. See the prose cases below.
        auto("prose is not dropped", "hello world", "hello world");

        // ---------- formerly CODE: one case per symbol-table row ----------
        auto("underscore", "snake underscore case", "snake_case");
        auto("dash", "well dash known", "well-known");
        auto("dot", "file dot py", "file.py");
        auto("open paren", "open paren ex close paren", "(x)");
        auto("close paren (sentence)", "open paren ex plus one close paren", "(x+1)");
        auto("open bracket", "open bracket zero close bracket", "[0]");
        auto("close bracket", "list open bracket one two close bracket", "list[12]");
        auto("open brace", "open brace close brace", "{}");
        auto("close brace", "open brace key colon value close brace", "{key:value}");
        auto("equals", "ex equals one", "x=1");
        auto("plus", "two plus two", "2+2");
        auto("arrow", "ay arrow bee", "a->b");
        auto("fat arrow", "ex fat arrow ex", "x=>x");
        auto("colon", "key colon value", "key:value");
        auto("semicolon", "one semicolon two", "1;2");
        auto("comma", "one comma two", "1,2");
        auto("quote", "quote hi quote", "\"hi\"");
        auto("single quote", "single quote ay single quote", "'a'");
        auto("backtick", "backtick code backtick", "`code`");
        auto("slash", "usr slash bin", "usr/bin");
        auto("backslash", "cee backslash temp", "c\\temp");
        auto("pipe", "cat pipe grep", "cat|grep");
        auto("ampersand", "salt ampersand pepper", "salt&pepper");
        auto("asterisk", "pointer asterisk value", "pointer*value");
        auto("hash", "hash include", "#include");
        auto("hash bang", "hash bang", "#!");
        auto("at sign", "user at sign host", "user@host");
        auto("dollar sign", "dollar sign home", "$home");
        auto("percent", "one hundred percent", "1 hundred%");
        auto("bang", "not bang true", "not!true");
        auto("question mark", "really question mark", "really?");
        auto("tilde", "tilde user", "~user");
        auto("caret", "two caret ten", "2^10");
        auto("less than", "ex less than ten", "x<10");
        auto("greater than", "ex greater than ten", "x>10");
        auto("new line", "line one new line line two", "line 1\nline 2");
        auto("tab", "tab indented", "\tindented");
        auto("space", "open paren space ex close paren", "( x)");
        auto("space both sides", "open paren space ex space close paren", "( x )");
        auto("words passthrough", "hello world", "hello world");
        auto("digits hug", "port eight zero eight zero", "port 8080");
        auto("letters hug", "ay bee cee", "abc");
        auto("trailing punctuation stripped", "hello world,", "hello world");

        // ---------- mixed: runs and glyphs in one result ----------
        auto("plus is not spelling noise", "ex plus one", "x+1");
        auto("spelled word then equals", "ess you pee equals four", "sup=4");

        // ---------- prose safety: byte-identical ----------
        auto("prose 1", "hello world this is a test", "hello world this is a test");
        auto("prose 2", "let me know when you get home", "let me know when you get home");
        auto("prose 3", "what time does the meeting start", "what time does the meeting start");
        auto("prose 4", "see you at the office", "see you at the office");
        auto("prose 5", "call me when you land", "call me when you land");
        auto("prose 6", "i will send the file tonight", "i will send the file tonight");

        System.out.println("CodeTextBattery: " + pass + " PASS, " + fail + " FAIL");
        if (fail > 0) System.exit(1);
    }
}
