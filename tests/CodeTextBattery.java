import com.james.codendrive.CodeText;

/**
 * CodeNDrive deterministic-pass battery. Plain main() harness, no
 * dependencies — same shape as the tree's engine Battery. Every case
 * is an exact string equality; nothing here is fuzzy.
 */
public class CodeTextBattery {
    static int pass = 0, fail = 0;

    static void spell(String name, String in, String want) {
        check("SPELL " + name, CodeText.spell(in), want);
    }

    static void code(String name, String in, String want) {
        check("CODE " + name, CodeText.codeSymbols(in), want);
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
        // ---------- SPELL mode ----------
        spell("supabase", "ess you pee ay bee ay ess ee", "supabase");
        spell("identifier with underscore", "ess you pee ay underscore bee ay ess ee", "supa_base");
        spell("workflow", "double you oh ar kay ef el oh double you", "workflow");
        spell("junk token dropped", "ess banana you pee", "sup");
        spell("dash inside", "jay ay vee ay dash ess cee ar eye pee tee", "java-script");
        spell("xyz", "ex why zee", "xyz");
        spell("omg", "oh em gee", "omg");
        spell("digit inside", "pee why three", "py3");
        spell("double-you pair", "double you eye en", "win");
        spell("capitalized input lowercased", "Ay Bee Cee", "abc");
        spell("dot inside", "dee ee vee dot en ee tee", "dev.net");
        spell("hyphenated letter", "double-you eye en", "win");
        spell("single letters", "a b c", "abc");
        spell("all junk", "hello world", "");

        // ---------- CODE mode: one case per symbol-table row ----------
        code("underscore", "snake underscore case", "snake_case");
        code("dash", "well dash known", "well-known");
        code("dot", "file dot py", "file.py");
        code("open paren", "open paren ex close paren", "(x)");
        code("close paren (sentence)", "open paren ex plus one close paren", "(x+1)");
        code("open bracket", "open bracket zero close bracket", "[0]");
        code("close bracket", "list open bracket one two close bracket", "list[12]");
        code("open brace", "open brace close brace", "{}");
        code("close brace", "open brace key colon value close brace", "{key:value}");
        code("equals", "ex equals one", "x=1");
        code("plus", "two plus two", "2+2");
        code("arrow", "ay arrow bee", "a->b");
        code("fat arrow", "ex fat arrow ex", "x=>x");
        code("colon", "key colon value", "key:value");
        code("semicolon", "one semicolon two", "1;2");
        code("comma", "one comma two", "1,2");
        code("quote", "quote hi quote", "\"hi\"");
        code("single quote", "single quote ay single quote", "'a'");
        code("backtick", "backtick code backtick", "`code`");
        code("slash", "usr slash bin", "usr/bin");
        code("backslash", "cee backslash temp", "c\\temp");
        code("pipe", "cat pipe grep", "cat|grep");
        code("ampersand", "salt ampersand pepper", "salt&pepper");
        code("asterisk", "pointer asterisk value", "pointer*value");
        code("hash", "hash include", "#include");
        code("hash bang", "hash bang", "#!");
        code("at sign", "user at sign host", "user@host");
        code("dollar sign", "dollar sign home", "$home");
        code("percent", "one hundred percent", "1 hundred%");
        code("bang", "not bang true", "not!true");
        code("question mark", "really question mark", "really?");
        code("tilde", "tilde user", "~user");
        code("caret", "two caret ten", "2^10");
        code("less than", "ex less than ten", "x<10");
        code("greater than", "ex greater than ten", "x>10");
        code("new line", "line one new line line two", "line 1\nline 2");
        code("tab", "tab indented", "\tindented");
        code("space", "open paren space ex close paren", "( x)");
        code("space both sides", "open paren space ex space close paren", "( x )");
        code("words passthrough", "hello world", "hello world");
        code("digits hug", "port eight zero eight zero", "port 8080");
        code("letters hug", "ay bee cee", "abc");
        code("trailing punctuation stripped", "hello world,", "hello world");

        System.out.println("CodeTextBattery: " + pass + " PASS, " + fail + " FAIL");
        if (fail > 0) System.exit(1);
    }
}
