package com.james.codendrive.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CodeNDrive correction engine. Pure Java, no Android dependencies.
 *
 * Pipeline (voice): escapes -> spoken/fragmented domain repair -> decode map
 * -> multiword snap -> token snap/casing -> period discipline -> normalize.
 * Typed mode runs the same pipeline minus period-break collapsing (a typed
 * period is deliberate), plus word-level APIs for the suggestion strip.
 *
 * Conservatism rule throughout: a wrong snap is worse than a miss. Anything
 * ambiguous is left exactly as it arrived.
 */
public final class CorrectionEngine {

    // ------------------------------------------------------------------
    // Lexicon
    // ------------------------------------------------------------------

    /** Canonical single-token entries (exact casing as committed). */
    static final String[] LEXICON = {
        // people
        // DEMO PACK -- invented persona (Marisol Vega, see README.md).
        // The personal layer ships with a fictional household so the
        // mechanics stay exercisable without anyone's real names.
        "Marisol", "Vega", "Diego", "Priya", "Chand", "Tomas", "Rosa", "Ruby",
        // projects / products (brands with canonical casing)
        "Netlify", "Tailscale", "Supabase", "PostGrid", "RunPod", "Ghidra",
        "DeepSeek",
        "BakeBoard", "CrumbRoute", "WhiskWorks", "ProofBox",
        // v3.2 (Big List, cat 1/3): AI-ecosystem + infra names whose
        // lowercase form is a distinctive non-word or proper name, so an
        // exact match is safe ungated. Real-word collisions (Gemini,
        // Whisper, Cursor, Bolt, Stripe, ...) are gated or
        // recognition-only instead -- never in this list.
        "Claude", "OpenAI", "ChatGPT", "Anthropic", "Heroku", "Okta",
        "Laravel", "Firebase", "Shopify", "WordPress", "LangChain",
        "Midjourney", "Replit", "Kubernetes",
        // Collision audit: Ollama / Ansible / Prisma are NOT here --
        // fuzzy snap would pull "llama" -> Ollama, "audible" ->
        // Ansible, "prism" -> Prisma. They live in exact paths only
        // (DECODE_WORDS / a gate), where no neighbor can be captured.
        // lowercase-canonical product tokens (demo pack)
        "crumbandcraft", "crumbtest",
        // places (demo pack)
        "Riverton", "Fairview",
    };

    /** Multiword entries, canonical form. */
    static final String[] LEXICON_MULTI = {
        "Marisol Vega", "Diego Vega", "Priya Chand", "Crumb & Craft",
        "Golden Grain Mills", "Mill District", "Riverton Market",
        // v3.2 (Big List, cat 1/2)
        "Hugging Face", "React Native",
    };

    /**
     * v3.2: recognition-only names (Big List). These join SUGGESTIONS
     * (the keyboard knows the word) but are never casing targets and
     * never snap targets: the spoken form is a living English word
     * (cursor, bolt, render) or a same-spelling sense pair (the model
     * names Sonnet/Opus/Haiku), so a substitution layer has no safe
     * move. Recognition widens; authority does not.
     */
    static final Set<String> RECOGNITION_ONLY = new HashSet<>(Arrays.asList(
        "Cursor", "Bolt", "Render", "Sonnet", "Opus", "Haiku", "Devin"));

    /** Exact lowercase token -> canonical casing (short tokens fuzzy snap can't touch). */
    static final Map<String, String> CASING = new HashMap<>();
    static {
        CASING.put("pos", "POS");
        CASING.put("faq", "FAQ");
        CASING.put("diy", "DIY");
        CASING.put("usb", "USB");
        CASING.put("ai", "AI");
        // v3.2 (Big List, cat 1/3/4): acronyms whose lowercase form is
        // never an English word in the speaker's mouth. Real-word acronyms (rag,
        // rest, id, os, nlp) are deliberately NOT here -- gated or
        // recognition-only instead (see THE-BIG-LIST.md).
        CASING.put("api", "API");
        CASING.put("llm", "LLM");
        CASING.put("mcp", "MCP");
        CASING.put("sql", "SQL");
        CASING.put("json", "JSON");
        CASING.put("css", "CSS");
        CASING.put("html", "HTML");
        CASING.put("xml", "XML");
        CASING.put("csv", "CSV");
        CASING.put("pdf", "PDF");
        CASING.put("svg", "SVG");
        CASING.put("http", "HTTP");
        CASING.put("https", "HTTPS");
        CASING.put("ssh", "SSH");
        CASING.put("dns", "DNS");
        CASING.put("ssl", "SSL");
        CASING.put("tls", "TLS");
        CASING.put("ftp", "FTP");
        CASING.put("cpu", "CPU");
        CASING.put("gpu", "GPU");
        CASING.put("sdk", "SDK");
        CASING.put("cli", "CLI");
        CASING.put("url", "URL");
        CASING.put("uri", "URI");
        CASING.put("ip", "IP");
        CASING.put("ui", "UI");
        CASING.put("ux", "UX");
        CASING.put("yaml", "YAML");
        CASING.put("toml", "TOML");
        CASING.put("aws", "AWS");
        CASING.put("gcp", "GCP");
        CASING.put("gpt", "GPT");
        CASING.put("jwt", "JWT");
    }

    /** Domains that must survive as single tokens (lowercase canonical). */
    static final String[] DOMAINS = {
        "crumbandcraft.com", "crumbtest.app", "marisolvega.com",
        "goldengrainmills.com", "vegastall.crumbroute.app",
    };

    /** Domain stems (dots/spaces removed) recognized in fragmented dictation. */
    static final Set<String> DOMAIN_STEMS = new HashSet<>(Arrays.asList(
        "crumbandcraft", "crumbtest",
        "goldengrainmills", "vegastallcrumbroute"));

    static final Set<String> TLDS = new HashSet<>(Arrays.asList(
        "com", "net", "app", "org", "io", "dev", "co", "us", "info",
        // v3.2: file extensions join the same spoken-dot machinery
        // ("report dot md" -> report.md, "index dot tsx" -> index.tsx).
        "md", "ts", "js", "jsx", "tsx", "json", "css", "html", "py", "sh",
        "yaml", "yml", "toml", "sql", "txt", "xml", "csv", "svg"));

    /** Phrase decode map: lowercased garble -> canonical replacement. Longest first at runtime. */
    static final Map<String, String> DECODE_PHRASES = new LinkedHashMap<>();
    static {
        DECODE_PHRASES.put("crumb and craft", "Crumb & Craft");
        DECODE_PHRASES.put("bake bored", "BakeBoard");
        DECODE_PHRASES.put("bake board", "BakeBoard");
        DECODE_PHRASES.put("crumb root", "CrumbRoute");
        DECODE_PHRASES.put("whisk works", "WhiskWorks");
        DECODE_PHRASES.put("golden grain meals", "Golden Grain Mills");
        DECODE_PHRASES.put("proof box", "ProofBox");
        DECODE_PHRASES.put("net lafaye", "Netlify");
        DECODE_PHRASES.put("tail scaled", "Tailscale");
        DECODE_PHRASES.put("tails scale", "Tailscale");
        DECODE_PHRASES.put("super base", "Supabase");
        DECODE_PHRASES.put("deep seek", "DeepSeek");
        DECODE_PHRASES.put("run pod", "RunPod");
        // v3.2 (Big List, cat 1/2/3/5): tech-name garbles. Split-word
        // renderings of names that are never ordinary prose in this
        // sequence ("type script", "chat gee pee tee", "engine x").
        DECODE_PHRASES.put("type script", "TypeScript");
        DECODE_PHRASES.put("java script", "JavaScript");
        DECODE_PHRASES.put("next js", "Next.js");
        DECODE_PHRASES.put("node js", "Node.js");
        DECODE_PHRASES.put("tail wind", "tailwind"); // casing is gated (flight sense)
        DECODE_PHRASES.put("see sharp", "C#");
        DECODE_PHRASES.put("see plus plus", "C++");
        DECODE_PHRASES.put("c plus plus", "C++");
        DECODE_PHRASES.put("eff sharp", "F#");
        DECODE_PHRASES.put("pie thon", "Python");
        DECODE_PHRASES.put("go lang", "Go");
        DECODE_PHRASES.put("ruby on rails", "Ruby on Rails");
        DECODE_PHRASES.put("get hub", "GitHub");
        DECODE_PHRASES.put("git hub", "GitHub");
        DECODE_PHRASES.put("hugging face", "Hugging Face");
        DECODE_PHRASES.put("chat gpt", "ChatGPT");
        DECODE_PHRASES.put("chat gee pee tee", "ChatGPT");
        DECODE_PHRASES.put("open ai", "OpenAI");
        DECODE_PHRASES.put("co pilot", "Copilot");
        DECODE_PHRASES.put("em cee pee", "MCP");
        DECODE_PHRASES.put("engine x", "nginx");
        DECODE_PHRASES.put("en jinx", "nginx");
        DECODE_PHRASES.put("rej ex", "regex");
        DECODE_PHRASES.put("you bun too", "Ubuntu");
        DECODE_PHRASES.put("coober netties", "Kubernetes");
        DECODE_PHRASES.put("ess queue el", "SQL"); // Add-on E: the owner's own form
        DECODE_PHRASES.put("ess cue el", "SQL");
        DECODE_PHRASES.put("es queue el", "SQL");
        DECODE_PHRASES.put("auth zero", "Auth0");
        DECODE_PHRASES.put("graph ql", "GraphQL");
        DECODE_PHRASES.put("mongo db", "MongoDB");
        DECODE_PHRASES.put("post gress", "Postgres");
        DECODE_PHRASES.put("vs code", "VS Code");
        DECODE_PHRASES.put("vee zero", "v0");
        DECODE_PHRASES.put("v zero", "v0");
        DECODE_PHRASES.put("dot env", ".env");
        DECODE_PHRASES.put("dot git ignore", ".gitignore");
        DECODE_PHRASES.put("dot gitignore", ".gitignore");
        DECODE_PHRASES.put("git ignore", ".gitignore");
        // Workflow compounds (cat 4): the two-word rendering is only
        // ever the compound in dictation; the merged form is also the
        // correct ordinary word (payload, database, username).
        DECODE_PHRASES.put("data base", "database");
        DECODE_PHRASES.put("user name", "username");
        DECODE_PHRASES.put("pass word", "password");
        DECODE_PHRASES.put("web hook", "webhook");
        DECODE_PHRASES.put("end point", "endpoint");
        DECODE_PHRASES.put("pay load", "payload");
        DECODE_PHRASES.put("boiler plate", "boilerplate");
        DECODE_PHRASES.put("re factor", "refactor");
        DECODE_PHRASES.put("work flow", "workflow");
    }

    /** Single-word decodes (with guards applied in code where noted). */
    static final Map<String, String> DECODE_WORDS = new HashMap<>();
    static {
        DECODE_WORDS.put("gidra", "Ghidra");
        DECODE_WORDS.put("superbase", "Supabase");
        DECODE_WORDS.put("preeya", "Priya");
        DECODE_WORDS.put("vegah", "Vega");
        DECODE_WORDS.put("sourdow", "sourdough"); // her standing decode (the bread)
        DECODE_WORDS.put("bakarat", "Bakkarat"); // recognizer spelling of her game name; not an English word, repairs directly
        // v3.2 (Big List): one-token names whose spoken form is a
        // non-word or a joined brand form -- safe to repair directly.
        DECODE_WORDS.put("javascript", "JavaScript");
        DECODE_WORDS.put("typescript", "TypeScript");
        DECODE_WORDS.put("mongodb", "MongoDB");
        DECODE_WORDS.put("postgres", "Postgres");
        DECODE_WORDS.put("redis", "Redis");
        DECODE_WORDS.put("webpack", "Webpack");
        DECODE_WORDS.put("vite", "Vite");
        DECODE_WORDS.put("deno", "Deno");
        DECODE_WORDS.put("cloudflare", "Cloudflare");
        DECODE_WORDS.put("vercel", "Vercel");
        DECODE_WORDS.put("huggingface", "Hugging Face");
        DECODE_WORDS.put("copilot", "Copilot");
        DECODE_WORDS.put("oauth", "OAuth");
        DECODE_WORDS.put("golang", "Go");
        DECODE_WORDS.put("enjinx", "nginx");
        DECODE_WORDS.put("redgex", "regex");
        DECODE_WORDS.put("graphql", "GraphQL");
        DECODE_WORDS.put("vscode", "VS Code");
        DECODE_WORDS.put("ollama", "Ollama");   // exact only: "llama" is the animal / the model gate
        DECODE_WORDS.put("ansible", "Ansible"); // exact only: "audible" must never snap here
    }

    /** Followers that prove a sentence-start "Anna" is really "And now". */
    static final Set<String> ANNA_FOLLOWERS = new HashSet<>(Arrays.asList(
        "the", "we", "i", "you", "it", "they", "he", "she", "this", "that", "there", "let"));

    /** Words after which "brie" is the cheese, not Bree the event planner. */
    static final Set<String> BRIE_GUARDS = new HashSet<>(Arrays.asList(
        "cheese", "wheel", "rind", "baked"));

    /** Abbreviations whose trailing period is never a sentence break. */
    static final Set<String> ABBREV = new HashSet<>(Arrays.asList(
        "mr", "mrs", "ms", "dr", "st", "vs", "jr", "sr", "prof", "sgt", "capt",
        "lt", "col", "gen", "rev", "hon", "fig", "approx", "dept", "est",
        "inc", "corp", "ltd", "e.g", "i.e", "a.m", "p.m", "u.s", "u.s.a"));

    /** Strong continuers: ". " before one of these is a pause, not a break. */
    static final Set<String> CONTINUERS = new HashSet<>(Arrays.asList(
        "and", "but", "or", "so", "then", "because", "yet"));

    /** Common English words that must never be fuzzy-snapped into the lexicon. */
    static final Set<String> COMMON = new HashSet<>(Arrays.asList(
        "the", "and", "for", "are", "but", "not", "you", "all", "any", "can",
        "had", "her", "was", "one", "our", "out", "day", "get", "has", "him",
        "his", "how", "man", "old", "see", "two", "way", "who", "did", "its",
        "let", "put", "say", "she", "too", "use", "that", "with", "have",
        "this", "will", "your", "from", "they", "know", "want", "been",
        "good", "much", "some", "time", "very", "when", "come", "here",
        "just", "like", "long", "make", "many", "more", "only", "over",
        "such", "take", "than", "them", "well", "were", "what", "about",
        "would", "there", "their", "which", "after", "first", "think",
        "where", "being", "every", "great", "might", "shall", "still",
        "those", "under", "while", "should", "people", "through",
        "because", "without", "network", "between", "computer", "another",
        "around", "family", "found", "general", "morning", "several",
        "something", "different", "garden", "kitchen", "window", "letter",
        "mother", "father", "water", "paper", "river", "money", "story",
        "example", "business", "problem", "question", "complete", "customer",
        "metro", "forms", "form", "gate", "site", "page", "text", "send",
        "sent", "call", "work", "home", "house", "need", "next", "last",
        "back", "look", "feel", "felt", "left", "list", "line", "open",
        "read", "real", "run", "running", "set", "shows", "side", "soon",
        "state", "sure", "tell", "told", "try", "turn", "week", "year",
        // v3.2 collision audit: ordinary words within fuzzy distance
        // of Big List lexicon entries (clause~Claude, chatbot~ChatGPT).
        "clause", "clauses", "chatbot",
        // Demo-persona shields: Chand~hand, Vega~vegan, Rosa~rose
        // sit one edit from common words; never snap them.
        "hand", "vegan", "rose"));

    private static final Map<String, String> LEXICON_BY_LOWER = new HashMap<>();
    static {
        for (String e : LEXICON) LEXICON_BY_LOWER.put(e.toLowerCase(Locale.US), e);
    }

    // ------------------------------------------------------------------
    // Layer toggles (settings screen)
    // ------------------------------------------------------------------
    public boolean escapesOn = true;
    public boolean domainsOn = true;
    public boolean decodeOn = true;
    public boolean snapOn = true;
    public boolean periodsOn = true;
    public boolean gatesOn = true;    // v2: gated homophones
    public boolean symbolsOn = true;  // v2: symbol names -> glyphs (voice only)
    public boolean learningOn = true; // v3: learning tier (settings key "learn")
    /** Test hook: when >= 0, replaces the wall clock for learning decay. */
    public long nowOverrideMs = -1;

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /** Full pipeline for dictated (voice) text. */
    public String correctVoice(String raw) {
        return correct(raw, true);
    }

    /** Full pipeline for typed text (no period-break collapsing). */
    public String correctTyped(String raw) {
        return correct(raw, false);
    }

    private String correct(String raw, boolean voice) {
        if (raw == null) return "";
        String s = raw;
        if (escapesOn) s = fixEscapes(s);
        // Process per line so escape-created line breaks survive spacing rules.
        String[] lines = s.split("\n", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) out.append('\n');
            out.append(correctLine(lines[i], voice));
        }
        return out.toString();
    }

    private String correctLine(String line, boolean voice) {
        String s = line;
        if (domainsOn) {
            s = joinSpokenDomains(s);
            s = repairFragmentedDomains(s);
        }
        if (decodeOn) s = applyDecodePhrases(s);
        // Token stream for snap + word decodes + Anna rule.
        List<String> tokens = new ArrayList<>(Arrays.asList(s.split(" ", -1)));
        // v3: learned-word/pair shields are computed once, on the
        // post-decode-phrase token stream, and honored by every token
        // stage below (Anna rule, multiword snap, token path, gates,
        // glyphs). The string-level decode-phrase layer above runs
        // before tokenization and is out of shield scope by design.
        boolean[] shield = computeShields(tokens);
        if (decodeOn) applyAnnaRule(tokens, shield);
        if (snapOn) applyMultiwordSnap(tokens, shield);
        for (int i = 0; i < tokens.size(); i++) {
            if (shield != null && shield[i]) continue;
            tokens.set(i, correctToken(tokens.get(i),
                i + 1 < tokens.size() ? coreOf(tokens.get(i + 1)) : null));
        }
        if (gatesOn) applyGates(tokens, shield);
        if (voice && gatesOn) applyColinGate(tokens, shield);
        if (voice) applySpokenCase(tokens, shield);
        if (voice && symbolsOn) applyGlyphs(tokens);
        s = joinTokens(tokens);
        if (voice && periodsOn) s = periodDiscipline(s);
        s = normalize(s);
        return s;
    }

    /** Correct one whitespace-delimited token; returns canonical form or the token unchanged. */
    public String correctToken(String token, String nextCoreLowerHint) {
        if (token == null || token.isEmpty()) return token;
        String[] parts = splitAffixes(token);
        String lead = parts[0], core = parts[1], trail = parts[2];
        if (core.isEmpty()) return token;
        String lower = core.toLowerCase(Locale.US);
        if (core.indexOf('.') >= 0 || core.indexOf('/') >= 0 || hasDigit(core)) return token;
        // v3 shield: a tier-2 learned word is a valid word -- leave it
        // exactly as it arrived (covers the service's single-word path,
        // which has no line context for computeShields).
        if (learningOn && isLearnedWord(lower, nowMs())) return token;
        if (decodeOn) {
            String dw = DECODE_WORDS.get(lower);
            if (dw != null) return lead + dw + trail;
            if (lower.equals("brie")) {
                String nxt = nextCoreLowerHint == null ? null
                        : nextCoreLowerHint.toLowerCase(Locale.US);
                if (nxt == null || !BRIE_GUARDS.contains(nxt)) return lead + "Bree" + trail;
                return token;
            }
        }
        if (!snapOn) return token;
        String cased = CASING.get(lower);
        if (cased != null) return lead + cased + trail;
        String exact = LEXICON_BY_LOWER.get(lower);
        if (exact != null) return lead + exact + trail;
        String snapped = snapWord(core);
        if (snapped != null) return lead + snapped + trail;
        return token;
    }

    /**
     * Fuzzy-snap a single word to a lexicon entry, or null. Strong-similarity
     * only: edit distance budget scales with length, first letter must match
     * (or distance 1), and common English words are never snapped.
     */
    public String snapWord(String word) {
        if (word == null) return null;
        String lower = word.toLowerCase(Locale.US);
        if (lower.length() < 4) return null;
        if (COMMON.contains(lower)) return null;
        if (LEXICON_BY_LOWER.containsKey(lower)) return LEXICON_BY_LOWER.get(lower);
        String best = null;
        int bestDist = Integer.MAX_VALUE;
        for (String entry : LEXICON) {
            String el = entry.toLowerCase(Locale.US);
            if (Math.abs(el.length() - lower.length()) > 3) continue;
            int d = levenshtein(lower, el);
            int budget = lower.length() <= 5 ? 1 : (lower.length() <= 8 ? 2 : 3);
            if (d > budget || d == 0) continue;
            boolean firstOk = lower.charAt(0) == el.charAt(0);
            if (!firstOk && d > 1) continue;
            double sim = 1.0 - (double) d / Math.max(lower.length(), el.length());
            if (sim < 0.70) continue;
            if (d < bestDist) { bestDist = d; best = entry; }
        }
        return best;
    }

    /** Suggestions for the strip: lexicon + decode targets matching a prefix. */
    public List<String> suggest(String prefix) {
        List<String> out = new ArrayList<>();
        if (prefix == null) return out;
        String p = prefix.toLowerCase(Locale.US);
        if (p.length() < 2) return out;
        for (String e : LEXICON) {
            if (e.toLowerCase(Locale.US).startsWith(p) && !out.contains(e)) out.add(e);
        }
        for (String e : LEXICON_MULTI) {
            if (e.toLowerCase(Locale.US).startsWith(p) && !out.contains(e)) out.add(e);
        }
        for (Map.Entry<String, String> en : DECODE_PHRASES.entrySet()) {
            if (en.getKey().startsWith(p) && !out.contains(en.getValue())) out.add(en.getValue());
        }
        // v3.2: recognition-only names are suggestible, never snappable.
        for (String e : RECOGNITION_ONLY) {
            if (e.toLowerCase(Locale.US).startsWith(p) && !out.contains(e)) out.add(e);
        }
        // v3: learned words may be SUGGESTED (recognition), even though
        // they are never snap targets (no authority).
        if (learningOn) {
            for (String w : learnedWords(nowMs())) {
                if (w.toLowerCase(Locale.US).startsWith(p) && !out.contains(w)) out.add(w);
            }
        }
        String snapped = snapWord(prefix);
        if (snapped != null && !out.contains(snapped)) out.add(0, snapped);
        if (out.size() > 6) return new ArrayList<>(out.subList(0, 6));
        return out;
    }

    /** All lexicon entries (settings screen view). */
    public List<String> lexiconEntries() {
        List<String> out = new ArrayList<>(Arrays.asList(LEXICON));
        out.addAll(Arrays.asList(LEXICON_MULTI));
        out.addAll(Arrays.asList(DOMAINS));
        return out;
    }

    // ------------------------------------------------------------------
    // Layer: typed autocorrect (v3.3, WS2)
    // ------------------------------------------------------------------
    //
    // Real word-level autocorrect for TYPED text, behind the frozen
    // two-argument API the IME service calls per committed word:
    //   correctTyped(word, sentenceStart)   -> correction or null
    //   typedSuggestions(word, sentenceStart) -> up to 3 strip entries
    // (The one-argument correctTyped(String) above is the typed TEXT
    // pipeline and is unchanged; this section never runs inside it.)
    //
    // Pipeline, in order (each step documented at its code):
    //   1. guards/exclusions   2. personal-first   3. contractions
    //   4. common-typo table   5. known-word check 6. dictionary fix
    //   7. capitalization restore (inside restoreTypedCase).
    // Conservatism rule, same as the rest of the engine: a wrong
    // correction is worse than a miss, so anything ambiguous is left
    // exactly as typed and only offered as a suggestion.

    /** Contraction table: bare typed form -> canonical contraction. */
    static final String[][] CONTRACTIONS = {
        {"dont", "don't"}, {"cant", "can't"}, {"isnt", "isn't"},
        {"wont", "won't"}, {"didnt", "didn't"}, {"doesnt", "doesn't"},
        {"couldnt", "couldn't"}, {"shouldnt", "shouldn't"},
        {"wouldnt", "wouldn't"}, {"havent", "haven't"}, {"hasnt", "hasn't"},
        {"wasnt", "wasn't"}, {"werent", "weren't"},
        {"youre", "you're"}, {"theyre", "they're"}, {"weve", "we've"},
        {"ive", "I've"}, {"couldve", "could've"}, {"shouldve", "should've"},
        {"wouldve", "would've"},
        {"im", "I'm"}, {"ill", "I'll"}, {"id", "I'd"},
        // "aint" is deliberately absent: it is a dialect word, left alone.
    };

    /**
     * Contractions that are also real words / identifiers (I've, I'm,
     * I'll, I'd): auto-applied ONLY at a sentence start. Mid-sentence
     * correctTyped returns null and typedSuggestions offers the
     * contraction as its first suggestion instead.
     */
    static final Set<String> GATED_CONTRACTIONS = new HashSet<>(Arrays.asList(
        "ive", "im", "ill", "id"));

    static final Map<String, String> CONTRACTION_MAP = new HashMap<>();
    static {
        for (String[] p : CONTRACTIONS) CONTRACTION_MAP.put(p[0], p[1]);
    }

    /** Common-typo table: true non-word -> the intended word. */
    static final String[][] TYPOS = {
        {"teh", "the"}, {"hte", "the"}, {"taht", "that"}, {"tihs", "this"},
        {"thier", "their"}, {"thign", "thing"},
        {"recieve", "receive"}, {"recieved", "received"},
        {"recieving", "receiving"}, {"reciept", "receipt"},
        {"adress", "address"}, {"seperate", "separate"},
        {"seperated", "separated"}, {"definately", "definitely"},
        {"definate", "definite"}, {"definetly", "definitely"},
        {"occured", "occurred"}, {"goverment", "government"},
        {"freind", "friend"}, {"freindly", "friendly"},
        {"finaly", "finally"}, {"realy", "really"}, {"jsut", "just"},
        {"liek", "like"}, {"wiht", "with"},
        {"beleive", "believe"}, {"belive", "believe"},
        {"buisness", "business"}, {"calender", "calendar"},
        {"wierd", "weird"}, {"tommorow", "tomorrow"}, {"tomorow", "tomorrow"},
        {"absense", "absence"}, {"accidentaly", "accidentally"},
        {"acheive", "achieve"}, {"acknowlege", "acknowledge"},
        {"advertisment", "advertisement"},
        {"aggresive", "aggressive"}, {"alchohol", "alcohol"},
        {"alegiance", "allegiance"}, {"allmost", "almost"},
        {"amatuer", "amateur"},
        {"apparentely", "apparently"}, {"apparantly", "apparently"},
        {"aparently", "apparently"}, {"arguement", "argument"},
        {"assasination", "assassination"}, {"basicly", "basically"},
        {"begginer", "beginner"}, {"begining", "beginning"},
        {"bizzare", "bizarre"}, {"brodcast", "broadcast"},
        {"camoflage", "camouflage"}, {"carreer", "career"},
        {"catagory", "category"}, {"cemetary", "cemetery"},
        {"changable", "changeable"}, {"cheif", "chief"},
        {"collosal", "colossal"}, {"collegue", "colleague"},
        {"comming", "coming"}, {"commited", "committed"},
        {"commitee", "committee"}, {"comparision", "comparison"},
        {"completly", "completely"}, {"concious", "conscious"},
        {"copywrite", "copyright"}, {"curiosoty", "curiosity"},
        {"dissapear", "disappear"}, {"dissapoint", "disappoint"},
        {"dissapointed", "disappointed"}, {"doccument", "document"},
        {"dollor", "dollar"}, {"domminant", "dominant"},
        {"embarass", "embarrass"}, {"embarassing", "embarrassing"},
        {"enviroment", "environment"}, {"enviromental", "environmental"},
        {"equiped", "equipped"}, {"equippment", "equipment"},
        {"espically", "especially"}, {"exagerate", "exaggerate"},
        {"exellent", "excellent"}, {"experiance", "experience"},
        {"existance", "existence"}, {"familar", "familiar"},
        {"familliar", "familiar"}, {"fluroscent", "fluorescent"},
        {"foriegn", "foreign"}, {"formating", "formatting"},
        {"fourty", "forty"}, {"funtion", "function"},
        {"governer", "governor"}, {"greatful", "grateful"},
        {"grammer", "grammar"}, {"happend", "happened"},
        {"happyness", "happiness"},
        {"harasment", "harassment"}, {"harassement", "harassment"},
        {"heighth", "height"}, {"hierachy", "hierarchy"},
        {"humerous", "humorous"}, {"imigrant", "immigrant"},
        {"immediatly", "immediately"}, {"importent", "important"},
        {"indispensible", "indispensable"}, {"interupt", "interrupt"},
        {"intresting", "interesting"}, {"jelous", "jealous"},
        {"jewllery", "jewelry"}, {"knowlege", "knowledge"},
        {"langauge", "language"}, {"languge", "language"},
        {"libary", "library"}, {"lonley", "lonely"}, {"loseing", "losing"},
        {"machien", "machine"}, {"magestic", "majestic"},
        {"maintainance", "maintenance"}, {"managment", "management"},
        {"manouver", "maneuver"}, {"mariage", "marriage"},
        {"medalion", "medallion"},
        {"millenium", "millennium"}, {"mischieveous", "mischievous"},
        {"morgage", "mortgage"}, {"necesary", "necessary"},
        {"neighboor", "neighbor"},
        {"noticable", "noticeable"}, {"ocassion", "occasion"},
        {"ofice", "office"}, {"officail", "official"},
        {"omision", "omission"}, {"ommitted", "omitted"},
        {"oppinion", "opinion"}, {"orignal", "original"},
        {"packege", "package"}, {"parrallel", "parallel"},
        {"particuler", "particular"}, {"pasword", "password"},
        {"pavillion", "pavilion"}, {"peform", "perform"},
        {"performence", "performance"}, {"peice", "piece"},
        {"peopel", "people"}, {"permanant", "permanent"},
        {"perseverence", "perseverance"}, {"pleese", "please"},
        {"posession", "possession"}, {"posion", "poison"},
        {"posible", "possible"}, {"possable", "possible"},
        {"poweful", "powerful"}, {"preferance", "preference"},
        {"prefered", "preferred"}, {"presance", "presence"},
        {"propably", "probably"}, {"propper", "proper"},
        {"proteen", "protein"}, {"publically", "publicly"},
        {"quarentine", "quarantine"},
        {"readible", "readable"}, {"recomend", "recommend"},
        {"reconize", "recognize"}, {"refered", "referred"},
        {"refering", "referring"}, {"relevent", "relevant"},
        {"religous", "religious"}, {"remeber", "remember"},
        {"rember", "remember"}, {"repitition", "repetition"},
        {"requied", "required"}, {"requirment", "requirement"},
        {"reserach", "research"}, {"resistence", "resistance"},
        {"resourse", "resource"}, {"responce", "response"},
        {"restaraunt", "restaurant"}, {"restraunt", "restaurant"},
        {"retreive", "retrieve"}, {"runing", "running"},
        {"rythm", "rhythm"}, {"saftey", "safety"},
        {"schedual", "schedule"}, {"secound", "second"},
        {"secratary", "secretary"}, {"sentance", "sentence"},
        {"sieze", "seize"}, {"similiar", "similar"},
        {"sincerly", "sincerely"}, {"skillfull", "skillful"},
        {"sofware", "software"}, {"somewere", "somewhere"},
        {"speach", "speech"}, {"speek", "speak"},
        {"spellling", "spelling"}, {"strenght", "strength"},
        {"sucess", "success"}, {"sucessful", "successful"},
        {"supercede", "supersede"}, {"suposed", "supposed"},
        {"suprise", "surprise"}, {"swiming", "swimming"},
        {"tatoo", "tattoo"}, {"tendancy", "tendency"},
        {"theif", "thief"}, {"theives", "thieves"},
        {"thinkng", "thinking"}, {"thourough", "thorough"},
        {"threshhold", "threshold"}, {"togehter", "together"},
        {"togethr", "together"}, {"tounge", "tongue"},
        {"tranfer", "transfer"}, {"trasfer", "transfer"},
        {"treadmil", "treadmill"}, {"truely", "truly"},
        {"tryed", "tried"}, {"twelth", "twelfth"},
        {"typicial", "typical"}, {"unecessary", "unnecessary"},
        {"unforseen", "unforeseen"}, {"unfortunatly", "unfortunately"},
        {"untill", "until"}, {"upcomming", "upcoming"},
        {"usefull", "useful"}, {"usualy", "usually"},
        {"vaccum", "vacuum"}, {"vaccuum", "vacuum"},
        {"varation", "variation"}, {"varity", "variety"},
        {"vegitable", "vegetable"}, {"vehical", "vehicle"},
        {"verison", "version"}, {"verry", "very"},
        {"vigilence", "vigilance"}, {"villian", "villain"},
        {"visiters", "visitors"}, {"waitting", "waiting"},
        {"waranty", "warranty"}, {"warrent", "warrant"},
        {"weahter", "weather"}, {"weigth", "weight"},
        {"wensday", "wednesday"}, {"werehouse", "warehouse"},
        {"wich", "which"}, {"writen", "written"}, {"writting", "writing"},
        {"yeild", "yield"},
    };

    static final Map<String, String> TYPO_MAP = new HashMap<>();
    static {
        for (String[] p : TYPOS) TYPO_MAP.put(p[0], p[1]);
    }

    /**
     * Hand-authored common core (604 of the most common English words).
     * Its only jobs in the typed layer: a word here is known, and when
     * several dictionary candidates tie at the minimum distance the
     * correction may auto-apply only if exactly one of them is here.
     */
    static final String TYPED_COMMON_CSV =
        "the,of,and,a,an,to,in,is,you,that,it,"
        + "he,was,for,on,are,as,with,his,they,i,at,"
        + "be,this,have,from,or,one,had,by,word,but,not,"
        + "what,all,were,we,when,your,can,said,there,use,each,"
        + "which,she,do,how,their,if,will,up,other,about,out,"
        + "many,then,them,these,so,some,her,would,make,like,him,"
        + "into,time,has,look,two,more,write,go,see,number,no,"
        + "way,could,people,my,than,first,water,been,call,who,oil,"
        + "its,now,find,long,down,day,did,get,come,made,may,"
        + "part,over,new,sound,take,only,little,work,know,place,year,"
        + "live,me,back,give,most,after,thing,our,just,name,good,"
        + "sentence,man,think,say,great,where,help,through,much,before,line,"
        + "right,too,mean,old,any,same,tell,boy,follow,came,want,"
        + "show,also,around,form,three,small,set,put,end,does,another,"
        + "well,large,must,big,even,such,because,turn,here,why,ask,"
        + "went,men,read,need,land,different,home,us,move,try,kind,"
        + "hand,picture,again,change,off,play,spell,air,away,animal,house,"
        + "point,page,letter,mother,answer,found,study,still,learn,should,world,"
        + "high,every,near,add,food,between,own,below,country,plant,last,"
        + "school,father,keep,tree,never,start,city,earth,eye,light,thought,"
        + "head,under,story,saw,left,few,while,along,might,close,something,"
        + "seem,next,hard,open,example,begin,life,always,those,both,paper,"
        + "together,got,group,often,run,mile,river,car,feet,care,second,"
        + "book,carry,took,science,eat,room,friend,began,idea,fish,mountain,"
        + "north,once,base,hear,horse,cut,sure,watch,color,face,wood,"
        + "main,enough,plain,girl,usual,young,ready,above,ever,red,list,"
        + "though,feel,talk,bird,soon,body,dog,family,direct,pose,leave,"
        + "song,measure,door,product,black,short,numeral,class,wind,question,happen,"
        + "complete,ship,area,half,rock,order,fire,south,problem,piece,told,"
        + "knew,pass,since,top,whole,king,space,heard,best,hour,better,"
        + "true,during,hundred,remember,step,early,hold,west,ground,interest,reach,"
        + "fast,verb,sing,listen,six,table,travel,less,morning,ten,simple,"
        + "several,vowel,toward,war,lay,against,pattern,slow,center,love,person,"
        + "money,serve,appear,road,map,rain,rule,govern,pull,cold,notice,"
        + "voice,unit,power,town,fine,certain,fly,fall,lead,cry,dark,"
        + "machine,note,wait,plan,figure,star,box,noun,field,rest,correct,"
        + "able,pound,done,beauty,drive,contain,front,teach,week,final,gave,"
        + "green,oh,quick,develop,ocean,warm,free,minute,strong,special,behind,"
        + "clear,produce,fact,street,inch,multiply,nothing,course,stay,wheel,full,"
        + "force,blue,object,decide,surface,deep,moon,island,foot,system,busy,"
        + "test,record,boat,common,gold,possible,plane,stead,dry,wonder,laugh,"
        + "thousand,ago,ran,check,game,shape,equate,hot,miss,brought,heat,"
        + "snow,tire,bring,yes,distant,fill,east,paint,language,among,grand,"
        + "ball,yet,wave,drop,heart,present,heavy,dance,engine,position,arm,"
        + "wide,sail,material,size,vary,settle,speak,weight,general,ice,matter,"
        + "circle,pair,include,divide,syllable,felt,perhaps,pick,sudden,count,square,"
        + "reason,length,represent,art,subject,region,energy,hunt,probable,bed,brother,"
        + "egg,ride,cell,believe,fraction,forest,window,store,summer,train,sleep,"
        + "prove,lone,leg,exercise,wall,catch,mount,wish,sky,board,joy,"
        + "winter,sat,written,wild,instrument,kept,glass,grass,cow,job,edge,"
        + "sign,visit,past,soft,fun,bright,gas,weather,month,million,bear,"
        + "finish,hope,flower,cloth,strange,village,meeting,oxygen,night,walk,sea,"
        + "far,cannot,build,eyes,across,almost,alone,already,although,anyone,anything,"
        + "asked,become,children,company,four,government,however,important,information,later,let,"
        + "public,really,side,state,today,until,very,without,siege,forward,";

    static final Set<String> TYPED_COMMON = new HashSet<>();
    static {
        for (String w : TYPED_COMMON_CSV.split(",")) {
            String t = w.trim();
            if (!t.isEmpty()) TYPED_COMMON.add(t);
        }
        // Words the disambiguation tests and daily typing rely on;
        // listed explicitly so the CSV above stays a frequency list.
        TYPED_COMMON.addAll(Arrays.asList(
            "matter", "siege", "forward", "friend", "people", "morning",
            "because", "through", "another", "question", "complete",
            "customer", "business", "example", "something", "different"));
    }

    /** Lowercased decode targets + multiword components: engine-known words. */
    static final Set<String> TYPED_EXTRA_KNOWN = new HashSet<>();
    static {
        for (String v : DECODE_WORDS.values()) TYPED_EXTRA_KNOWN.add(v.toLowerCase(Locale.US));
        TYPED_EXTRA_KNOWN.addAll(DECODE_WORDS.keySet());
        for (String e : RECOGNITION_ONLY) TYPED_EXTRA_KNOWN.add(e.toLowerCase(Locale.US));
        for (String e : LEXICON_MULTI) {
            for (String w : e.split(" ")) TYPED_EXTRA_KNOWN.add(w.toLowerCase(Locale.US));
        }
        for (String v : CONTRACTION_MAP.values()) TYPED_EXTRA_KNOWN.add(v.toLowerCase(Locale.US));
        TYPED_EXTRA_KNOWN.add("aint"); // dialect word: known, never corrected
    }

    /**
     * Step 1 guards, shared by correctTyped and typedSuggestions.
     * True when the token shape is excluded from the typed layer:
     * any non-letter except an internal apostrophe (digits, @, ., -, _
     * and friends), ALL-CAPS of length >= 2 (acronyms), mixed-case
     * shapes that are neither lowercase nor Capitalized (identifiers),
     * and the proper-name shape (Capitalized away from a sentence
     * start).
     */
    private static boolean typedExcluded(String word, boolean sentenceStart) {
        int n = word.length();
        for (int i = 0; i < n; i++) {
            char c = word.charAt(i);
            if (Character.isLetter(c)) continue;
            if (c == '\'' && i > 0 && i < n - 1) continue;
            return true;
        }
        boolean hasUpper = false, hasLower = false;
        for (int i = 0; i < n; i++) {
            if (Character.isUpperCase(word.charAt(i))) hasUpper = true;
            if (Character.isLowerCase(word.charAt(i))) hasLower = true;
        }
        if (n >= 2 && hasUpper && !hasLower) return true; // ALL-CAPS acronym
        if (hasUpper) {
            boolean capitalized = Character.isUpperCase(word.charAt(0))
                    && word.substring(1).equals(word.substring(1).toLowerCase(Locale.US));
            if (!capitalized) return true;         // camelCase / mixed identifier
            if (!sentenceStart) return true;       // proper-name shape mid-sentence
        }
        return false;
    }

    /** Step 5: is this lowercase word already a known word? */
    private boolean isTypedKnown(String lower) {
        if (WordListData.contains(lower)) return true;
        if (WordListData.KEEP_SET.contains(lower)) return true;
        if (TYPED_COMMON.contains(lower)) return true;
        if (COMMON.contains(lower)) return true;
        if (LEXICON_BY_LOWER.containsKey(lower)) return true;
        if (CASING.containsKey(lower)) return true;
        if (TYPED_EXTRA_KNOWN.contains(lower)) return true;
        if (learningOn && isLearnedWord(lower, nowMs())) return true;
        return false;
    }

    /** Step 7: restore the user's capitalization pattern onto a result. */
    private static String restoreTypedCase(String result, String input) {
        // Intrinsically capitalized results (I'm, I've, I'll, I'd) stand.
        if (result.equals("I'm") || result.equals("I've")
                || result.equals("I'll") || result.equals("I'd")) return result;
        if (!input.isEmpty() && Character.isUpperCase(input.charAt(0))) {
            return Character.toUpperCase(result.charAt(0)) + result.substring(1);
        }
        return result;
    }

    /**
     * Typed autocorrect for one committed word (frozen v3.3 API).
     * Returns the corrected word, or null for no auto-correction.
     */
    public String correctTyped(String word, boolean sentenceStart) {
        // Step 1: null/short guards + shape exclusions.
        if (word == null || word.isEmpty()) return null;
        if (typedExcluded(word, sentenceStart)) return null;
        String lower = word.toLowerCase(Locale.US);
        // Step 2: personal-first. If the personal layer (correctToken:
        // lexicon, casings, decodes, learned shields) changes this word,
        // it has already won service-side; the typed layer must never
        // override it, so it stands down and returns null.
        String personal = correctToken(word, null);
        if (personal != null && !personal.equals(word)) return null;
        // Step 3: contraction table (case-insensitive). The gated four
        // (ive/im/ill/id) apply only at a sentence start; mid-sentence
        // they return null here and surface via typedSuggestions.
        String contr = CONTRACTION_MAP.get(lower);
        if (contr != null) {
            if (GATED_CONTRACTIONS.contains(lower) && !sentenceStart) return null;
            return restoreTypedCase(contr, word);
        }
        if (word.length() < 3) return null;
        // A word the user already typed with an apostrophe (don't,
        // dog's) is a finished contraction/possessive: leave it.
        if (word.indexOf('\'') >= 0) return null;
        // Step 4: common-typo table.
        String typo = TYPO_MAP.get(lower);
        if (typo != null) return restoreTypedCase(typo, word);
        // Step 5: known-word check (dictionary + engine lexicon +
        // learned words + keep-set).
        if (isTypedKnown(lower)) return null;
        // Step 6: conservative Damerau dictionary correction.
        String fixed = dictionaryAuto(lower);
        if (fixed != null) return restoreTypedCase(fixed, word);
        return null;
    }

    /**
     * Strip suggestions for one typed word (frozen v3.3 API): the
     * auto-correction if one exists; else the gated contraction offer;
     * else up to 3 ranked near-miss candidates (common-core members
     * first, then distance, then alphabetical). Empty when the word is
     * excluded, personal, or already known.
     */
    public List<String> typedSuggestions(String word, boolean sentenceStart) {
        List<String> out = new ArrayList<>();
        if (word == null || word.isEmpty()) return out;
        String auto = correctTyped(word, sentenceStart);
        if (auto != null) { out.add(auto); return out; }
        String lower = word.toLowerCase(Locale.US);
        if (typedExcluded(word, sentenceStart)) return out;
        // Gated contraction: offered, never auto-applied, mid-sentence.
        if (GATED_CONTRACTIONS.contains(lower) && !sentenceStart) {
            out.add(CONTRACTION_MAP.get(lower));
            return out;
        }
        if (word.length() < 3 || word.indexOf('\'') >= 0) return out;
        String personal = correctToken(word, null);
        if (personal != null && !personal.equals(word)) return out;
        if (isTypedKnown(lower)) return out;
        for (String c : dictionaryCandidates(lower)) {
            out.add(restoreTypedCase(c, word));
            if (out.size() >= 3) break;
        }
        return out;
    }

    // --- Step 6 machinery: Damerau-Levenshtein over the vendored list ---

    /** All edit-distance-1 strings of s (deletes, transposes, replaces, inserts). */
    private static Set<String> edit1Variants(String s) {
        Set<String> out = new HashSet<>();
        int n = s.length();
        for (int i = 0; i < n; i++) out.add(s.substring(0, i) + s.substring(i + 1));
        for (int i = 0; i + 1 < n; i++) {
            out.add(s.substring(0, i) + s.charAt(i + 1) + s.charAt(i) + s.substring(i + 2));
        }
        for (int i = 0; i < n; i++) {
            for (char c = 'a'; c <= 'z'; c++) {
                if (c != s.charAt(i)) out.add(s.substring(0, i) + c + s.substring(i + 1));
            }
        }
        for (int i = 0; i <= n; i++) {
            for (char c = 'a'; c <= 'z'; c++) out.add(s.substring(0, i) + c + s.substring(i));
        }
        out.remove(s);
        return out;
    }

    /**
     * The minimum-distance candidate set for an unknown word: every
     * dictionary word at Damerau distance 1, or (only when there are
     * none and the word is long enough to trust two edits) at
     * distance 2. Membership in the vendored set is the test; variants
     * are generated from the input, never by scanning the dictionary.
     */
    private static Set<String> minDistanceCandidates(String lower) {
        Set<String> out = new HashSet<>();
        for (String v : edit1Variants(lower)) {
            if (WordListData.contains(v)) out.add(v);
        }
        if (!out.isEmpty() || lower.length() < 7) return out;
        for (String v1 : edit1Variants(lower)) {
            for (String v2 : edit1Variants(v1)) {
                if (WordListData.contains(v2) && damerau(lower, v2) == 2) out.add(v2);
            }
        }
        return out;
    }

    /** Ranked candidates: common-core members first, then alphabetical. */
    private static List<String> dictionaryCandidates(String lower) {
        List<String> cands = new ArrayList<>(minDistanceCandidates(lower));
        cands.sort((a, b) -> {
            boolean ca = TYPED_COMMON.contains(a), cb = TYPED_COMMON.contains(b);
            if (ca != cb) return ca ? -1 : 1;
            return a.compareTo(b);
        });
        return cands;
    }

    /**
     * The step-6 auto-apply rule: exactly one candidate at the minimum
     * distance, or several of which exactly one is in the common core.
     * Anything else is a suggestion, never a silent correction.
     */
    private static String dictionaryAuto(String lower) {
        Set<String> cands = minDistanceCandidates(lower);
        if (cands.isEmpty()) return null;
        if (cands.size() == 1) return cands.iterator().next();
        String commonPick = null;
        for (String c : cands) {
            if (TYPED_COMMON.contains(c)) {
                if (commonPick != null) return null; // two common candidates: ambiguous
                commonPick = c;
            }
        }
        return commonPick;
    }

    /** Damerau-Levenshtein distance (adjacent transposition costs 1). */
    static int damerau(String a, String b) {
        int n = a.length(), m = b.length();
        int[][] d = new int[n + 1][m + 1];
        for (int i = 0; i <= n; i++) d[i][0] = i;
        for (int j = 0; j <= m; j++) d[0][j] = j;
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1),
                                   d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2)
                        && a.charAt(i - 2) == b.charAt(j - 1)) {
                    d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
                }
            }
        }
        return d[n][m];
    }

    // ------------------------------------------------------------------
    // Layer: learning tier (v3)
    // ------------------------------------------------------------------
    //
    // Clean-room implementation of a two-tier learned vocabulary. The
    // DESIGN follows HeliBoard's documented behavior -- a decaying history
    // tier, promotion into a permanent personal vocabulary only after
    // repeated deliberate use, behind a guard list -- reimplemented from
    // scratch in this engine's idiom. No GPL code, no file-by-file
    // translation, no external dictionary data: every word in the learned
    // vocabulary is one the user themself committed on their own phone.
    //
    // Tier 1 (history): words and adjacent word pairs observed in FINAL
    // COMMITTED text only -- the service reports text at commit boundaries,
    // so a typo typed and edited away before commit is never observed and
    // can never be enshrined (the known flaw of the reference design,
    // fixed here by construction). Tier 2 (promoted): a word whose
    // decayed count reaches LEARN_PROMOTE_AT under the eligibility rules.
    //
    // Doctrine: a promoted word is a VALID word. It
    // shields itself -- and any learned pair it sits in -- from the snap,
    // gate, and glyph layers, and it may appear as a suggestion. It NEVER
    // becomes a snap target and no gate may replace anything with it:
    // learning widens recognition, never authority. Entries decay (count
    // halves per 30 idle days) and expire at 60 idle days, so a stale
    // word demotes and disappears instead of ruling forever.

    /** Committed uses required for tier-2 promotion. */
    static final int LEARN_PROMOTE_AT = 4;
    /** Decay step: the effective count halves per full idle period. */
    static final long LEARN_DECAY_MS = 30L * 24 * 3600 * 1000;
    /** Entries idle this long are dropped entirely. */
    static final long LEARN_EXPIRE_MS = 60L * 24 * 3600 * 1000;

    // Android EditorInfo inputType bits, mirrored from
    // android.text.InputType as raw masks so the engine stays
    // Android-free and the exclusion rule is battery-testable on the JVM.
    private static final int IT_MASK_CLASS = 0x0000000f;
    private static final int IT_MASK_VARIATION = 0x00000ff0;
    private static final int IT_CLASS_NUMBER = 2;
    private static final int IT_CLASS_PHONE = 3;
    private static final int IT_CLASS_DATETIME = 4;
    private static final int IT_VAR_PASSWORD = 0x00000080;
    private static final int IT_VAR_VISIBLE_PASSWORD = 0x00000090;
    private static final int IT_VAR_WEB_PASSWORD = 0x000000e0;
    private static final int IT_VAR_EMAIL = 0x00000020;
    private static final int IT_VAR_WEB_EMAIL = 0x000000d0;

    /**
     * Fields the learner must never observe: password variations (text,
     * visible, web), the structured classes (number -- which also covers
     * the number-password variation -- phone, datetime), and the email
     * variations, which in practice are sign-in fields or other people's
     * addresses; neither is vocabulary.
     */
    public static boolean isLearningExcludedField(int inputType) {
        int cls = inputType & IT_MASK_CLASS;
        int var = inputType & IT_MASK_VARIATION;
        if (cls == IT_CLASS_NUMBER || cls == IT_CLASS_PHONE || cls == IT_CLASS_DATETIME) return true;
        if (var == IT_VAR_PASSWORD || var == IT_VAR_VISIBLE_PASSWORD || var == IT_VAR_WEB_PASSWORD) return true;
        if (var == IT_VAR_EMAIL || var == IT_VAR_WEB_EMAIL) return true;
        return false;
    }

    /** One history entry: a word, or a pair keyed "w1\u0001w2". */
    private static final class LEntry {
        int count;
        long lastSeen;
        String display; // proven committed capitalization (words only)
    }

    private final Map<String, LEntry> learnWords = new HashMap<>();
    private final Map<String, LEntry> learnPairs = new HashMap<>();

    /**
     * Words that can never promote: the built-in vocabulary proper
     * (common words, the lexicon, casing shorthands, decode garbles,
     * TLDs, domain stems). The gate tables are deliberately NOT here:
     * learning a gate word ("cell", "sail") is the user asserting the word
     * is real in their mouth, and the shield is their override of a default.
     * Symbol-name (glyph) words are not here either: a symbol name the user
     * demonstrably uses as a word, four committed times, is a word.
     */
    private static final Set<String> LEARN_BASE = new HashSet<>();
    static {
        LEARN_BASE.addAll(COMMON);
        LEARN_BASE.addAll(LEXICON_BY_LOWER.keySet());
        LEARN_BASE.addAll(CASING.keySet());
        LEARN_BASE.addAll(DECODE_WORDS.keySet());
        LEARN_BASE.addAll(TLDS);
        LEARN_BASE.addAll(DOMAIN_STEMS);
    }

    long nowMs() {
        return nowOverrideMs >= 0 ? nowOverrideMs : System.currentTimeMillis();
    }

    private static int effectiveCount(LEntry e, long now) {
        long age = now - e.lastSeen;
        if (age < 0) return e.count;
        if (age >= LEARN_EXPIRE_MS) return 0;
        long periods = age / LEARN_DECAY_MS;
        if (periods >= 31) return 0;
        return e.count >> (int) periods;
    }

    private static void pruneExpired(Map<String, LEntry> map, long now) {
        Iterator<Map.Entry<String, LEntry>> it = map.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue().lastSeen >= LEARN_EXPIRE_MS) it.remove();
        }
    }

    /** Record one observation, decaying the stored count to `now` first. */
    private static void bump(Map<String, LEntry> map, String key, long now) {
        LEntry e = map.get(key);
        if (e == null) {
            e = new LEntry();
            map.put(key, e);
        } else {
            e.count = effectiveCount(e, now);
        }
        e.count++;
        e.lastSeen = now;
    }

    /** A recordable word core: 2+ chars, letters with internal apostrophes only. */
    private static boolean learnableCore(String core) {
        if (core == null || core.length() < 2) return false;
        if (!Character.isLetter(core.charAt(0))
                || !Character.isLetter(core.charAt(core.length() - 1))) return false;
        for (int i = 0; i < core.length(); i++) {
            char c = core.charAt(i);
            if (!Character.isLetter(c) && c != '\'') return false;
        }
        return true;
    }

    private static boolean isAllLower(String s) {
        return s.equals(s.toLowerCase(Locale.US));
    }

    private static boolean isCapitalizedForm(String s) {
        return !s.isEmpty() && Character.isUpperCase(s.charAt(0)) && !isAllLower(s)
                && !s.equals(s.toUpperCase(Locale.US));
    }

    /**
     * Observe FINAL committed text -- what actually landed in the field,
     * never a draft in progress. The IME service calls this at commit
     * boundaries only. No-op when learning is off or the field type is
     * excluded. A capitalized token counts as proof of a proper display
     * form only when it appears away from a sentence start: a
     * sentence-start capital of a lowercase-known word is an artifact of
     * position, not a new word (such words accrue to their lowercase key,
     * which the base-vocabulary rule keeps unpromotable anyway).
     */
    public void observeCommittedText(String text, int inputType, long now) {
        if (!learningOn || text == null || text.isEmpty()) return;
        if (isLearningExcludedField(inputType)) return;
        pruneExpired(learnWords, now);
        pruneExpired(learnPairs, now);
        String[] raw = text.split("\\s+");
        String prevCore = null;
        boolean prevEndsSentence = true; // text start is a sentence start
        for (String tok : raw) {
            if (tok.isEmpty()) continue;
            boolean endsSentence = tok.endsWith(".") || tok.endsWith("!") || tok.endsWith("?");
            String core = coreOf(tok);
            if (!learnableCore(core)) {
                prevCore = null;
                prevEndsSentence = endsSentence;
                continue;
            }
            String lower = core.toLowerCase(Locale.US);
            boolean sentenceStart = prevEndsSentence;
            bump(learnWords, lower, now);
            LEntry e = learnWords.get(lower);
            if (!sentenceStart) {
                if (e.display == null) e.display = core;
                else if (isAllLower(e.display) && isCapitalizedForm(core)) e.display = core;
            }
            if (prevCore != null && !prevEndsSentence) {
                bump(learnPairs, prevCore + "\u0001" + lower, now);
            }
            prevCore = lower;
            prevEndsSentence = endsSentence;
        }
    }

    /** Tier 2: promoted and in force at `now`. */
    public boolean isLearnedWord(String lowerWord, long now) {
        if (!learningOn || lowerWord == null) return false;
        if (LEARN_BASE.contains(lowerWord)) return false;
        LEntry e = learnWords.get(lowerWord);
        return e != null && effectiveCount(e, now) >= LEARN_PROMOTE_AT;
    }

    /** A pair is well-learned at `now`. Pairs shield; they never snap. */
    public boolean isLearnedPair(String lowerA, String lowerB, long now) {
        if (!learningOn || lowerA == null || lowerB == null) return false;
        LEntry e = learnPairs.get(lowerA + "\u0001" + lowerB);
        return e != null && effectiveCount(e, now) >= LEARN_PROMOTE_AT;
    }

    /** Present in tier-1 history (not yet expired) at `now`. */
    public boolean inHistory(String lowerWord, long now) {
        if (lowerWord == null) return false;
        LEntry e = learnWords.get(lowerWord);
        return e != null && now - e.lastSeen < LEARN_EXPIRE_MS;
    }

    /** Tier-2 words in proven display form, sorted -- the settings view. */
    public List<String> learnedWords(long now) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, LEntry> en : learnWords.entrySet()) {
            if (isLearnedWord(en.getKey(), now)) {
                LEntry e = en.getValue();
                out.add(e.display != null ? e.display : en.getKey());
            }
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    /** Forget one word entirely: its history, promotion, and pairs. */
    public void removeLearned(String word) {
        if (word == null) return;
        String lower = word.toLowerCase(Locale.US);
        learnWords.remove(lower);
        Iterator<String> it = learnPairs.keySet().iterator();
        while (it.hasNext()) {
            String k = it.next();
            int sep = k.indexOf('\u0001');
            if (sep < 0) continue;
            if (k.substring(0, sep).equals(lower) || k.substring(sep + 1).equals(lower)) it.remove();
        }
    }

    /** Forget everything learned. */
    public void clearLearning() {
        learnWords.clear();
        learnPairs.clear();
    }

    private static final String LEARN_HEADER = "JKLEARN1";

    /** Serialize all learning state (raw counts + last-seen + display). */
    public String dumpLearning() {
        StringBuilder sb = new StringBuilder(LEARN_HEADER).append('\n');
        List<String> wk = new ArrayList<>(learnWords.keySet());
        wk.sort(null);
        for (String k : wk) {
            LEntry e = learnWords.get(k);
            sb.append("w ").append(k).append(' ').append(e.count).append(' ')
              .append(e.lastSeen).append(' ')
              .append(e.display == null ? "-" : e.display).append('\n');
        }
        List<String> pk = new ArrayList<>(learnPairs.keySet());
        pk.sort(null);
        for (String k : pk) {
            LEntry e = learnPairs.get(k);
            int sep = k.indexOf('\u0001');
            sb.append("p ").append(k.substring(0, sep)).append(' ')
              .append(k.substring(sep + 1)).append(' ')
              .append(e.count).append(' ').append(e.lastSeen).append('\n');
        }
        return sb.toString();
    }

    /** Load a dumpLearning payload; expired entries drop on load. */
    public void loadLearning(String data, long now) {
        learnWords.clear();
        learnPairs.clear();
        if (data == null) return;
        String[] lines = data.split("\n");
        if (lines.length == 0 || !lines[0].trim().equals(LEARN_HEADER)) return;
        for (int i = 1; i < lines.length; i++) {
            String[] f = lines[i].trim().split(" ");
            try {
                if (f.length == 5 && f[0].equals("w")) {
                    LEntry e = new LEntry();
                    e.count = Integer.parseInt(f[2]);
                    e.lastSeen = Long.parseLong(f[3]);
                    e.display = f[4].equals("-") ? null : f[4];
                    if (now - e.lastSeen < LEARN_EXPIRE_MS) learnWords.put(f[1], e);
                } else if (f.length == 5 && f[0].equals("p")) {
                    LEntry e = new LEntry();
                    e.count = Integer.parseInt(f[3]);
                    e.lastSeen = Long.parseLong(f[4]);
                    if (now - e.lastSeen < LEARN_EXPIRE_MS) {
                        learnPairs.put(f[1] + "\u0001" + f[2], e);
                    }
                }
            } catch (NumberFormatException ignored) { }
        }
    }

    /**
     * Per-token shield flags for a tokenized line, or null when nothing is
     * shielded. A token is shielded when it is a tier-2 learned word or
     * sits inside a well-learned pair.
     */
    private boolean[] computeShields(List<String> tokens) {
        if (!learningOn || (learnWords.isEmpty() && learnPairs.isEmpty())) return null;
        long now = nowMs();
        int n = tokens.size();
        String[] low = new String[n];
        for (int i = 0; i < n; i++) {
            String c = coreOf(tokens.get(i));
            low[i] = c == null ? "" : c.toLowerCase(Locale.US);
        }
        boolean[] sh = new boolean[n];
        boolean any = false;
        for (int i = 0; i < n; i++) {
            if (!low[i].isEmpty() && isLearnedWord(low[i], now)) { sh[i] = true; any = true; }
        }
        for (int i = 1; i < n; i++) {
            if (!low[i - 1].isEmpty() && !low[i].isEmpty()
                    && isLearnedPair(low[i - 1], low[i], now)) {
                sh[i - 1] = true;
                sh[i] = true;
                any = true;
            }
        }
        return any ? sh : null;
    }

    // ------------------------------------------------------------------
    // Layer: escape artifacts
    // ------------------------------------------------------------------

    /**
     * Literal "\n" / "\t" / "\r\n" character pairs become a real newline at a
     * sentence/clause boundary, else a single space. A backslash inside a
     * path-shaped token (drive letter, extra backslashes, file extension) or
     * a doubled backslash is left untouched.
     */
    static String fixEscapes(String s) {
        StringBuilder out = new StringBuilder();
        int i = 0, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= n) { out.append(c); i++; continue; }
            char e = s.charAt(i + 1);
            if (e == '\\') { out.append("\\\\"); i += 2; continue; }
            boolean lowerEsc = (e == 'n' || e == 't' || e == 'r');
            boolean upperEsc = (e == 'N' || e == 'T' || e == 'R');
            if (!lowerEsc && !upperEsc) { out.append(c); i++; continue; }
            int seqLen = 2;
            if ((e == 'r' || e == 'R') && i + 3 < n && s.charAt(i + 2) == '\\'
                    && (s.charAt(i + 3) == 'n' || s.charAt(i + 3) == 'N')) {
                seqLen = 4; // literal "\r\n"
            }
            if (upperEsc) {
                // Capital escapes only count when clearly bounded (dictation artifact,
                // not a path like C:\Temp where T starts a word).
                char before = i > 0 ? s.charAt(i - 1) : ' ';
                char after = i + seqLen < n ? s.charAt(i + seqLen) : ' ';
                boolean bounded = (i == 0 || before == ' ' || before == '\n')
                        && (i + seqLen >= n || after == ' ' || after == '\n'
                            || Character.isUpperCase(after) || after == '.' || after == ',');
                if (!bounded) { out.append(c); i++; continue; }
            }
            if (insidePathToken(s, i, seqLen)) { out.append(c); i++; continue; }
            // Decide newline vs space from the surrounding context.
            String beforeText = out.toString();
            String afterText = trimLead(s.substring(Math.min(i + seqLen, n)));
            boolean sentenceEnd = trimTrail(beforeText).matches(".*[.!?:;]$");
            boolean nextCap = !afterText.isEmpty() && Character.isUpperCase(afterText.charAt(0));
            boolean endsComma = trimTrail(beforeText).endsWith(",");
            if (sentenceEnd || (nextCap && !endsComma && segmentWords(beforeText) >= 2)) {
                out.append('\n');
            } else {
                out.append(' ');
            }
            i += seqLen;
        }
        return out.toString();
    }

    /** True when position i sits inside a whitespace-delimited token shaped like a path. */
    private static boolean insidePathToken(String s, int i, int seqLen) {
        int start = i;
        while (start > 0 && !Character.isWhitespace(s.charAt(start - 1))) start--;
        int end = i;
        while (end < s.length() && !Character.isWhitespace(s.charAt(end))) end++;
        String tok = s.substring(start, end);
        if (tok.indexOf(':') >= 0) return true;                    // C:\...
        if (tok.indexOf('/', 0) >= 0) return true;
        // A "\r\n" artifact pair legitimately holds two backslashes in one
        // token; only treat extra backslashes as a path sign for 2-char seqs.
        if (seqLen == 2 && tok.indexOf('\\') != tok.lastIndexOf('\\')) return true;
        return tok.matches(".*\\.[A-Za-z0-9]{2,4}$");               // file extension
    }

    private static int segmentWords(String text) {
        String t = text.trim();
        if (t.isEmpty()) return 0;
        int lastNl = t.lastIndexOf('\n');
        String seg = lastNl >= 0 ? t.substring(lastNl + 1) : t;
        return seg.trim().isEmpty() ? 0 : seg.trim().split("\\s+").length;
    }

    static String trimLead(String s) {
        int i = 0;
        while (i < s.length() && s.charAt(i) <= ' ') i++;
        return s.substring(i);
    }

    static String trimTrail(String s) {
        int i = s.length();
        while (i > 0 && s.charAt(i - 1) <= ' ') i--;
        return s.substring(0, i);
    }

    // ------------------------------------------------------------------
    // Layer: domains
    // ------------------------------------------------------------------

    /** "crumb and craft dot com" / "vega stall dot crumbroute dot app" -> one token. */
    static String joinSpokenDomains(String s) {
        String[] toks = s.split(" ", -1);
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < toks.length) {
            int consumedTo = trySpokenDomain(toks, i, out);
            if (consumedTo > i) {
                i = consumedTo;
            } else {
                out.add(toks[i]);
                i++;
            }
        }
        return String.join(" ", out);
    }

    /**
     * If a spoken domain starts at toks[i], append any plain prefix tokens plus
     * the joined domain token to out and return the index after the domain;
     * otherwise return i (nothing consumed).
     */
    private static int trySpokenDomain(String[] toks, int i, List<String> out) {
        // Find the first "dot" within the next 4 tokens; everything between
        // must be plain word tokens (the spoken stem, e.g. "crumb and craft").
        int d = -1;
        for (int k = i + 1; k <= Math.min(i + 4, toks.length - 1); k++) {
            String core = coreOf(toks[k]);
            if (core != null && core.equalsIgnoreCase("dot")) { d = k; break; }
            if (core == null || !core.matches("[A-Za-z0-9-]+")) return i;
        }
        if (d < 0) return i;
        for (int k = i; k < d; k++) {
            String core = coreOf(toks[k]);
            if (core == null || !core.matches("[A-Za-z0-9-]+")) return i;
        }
        // Consume the dotted labels after the first "dot".
        List<String> dotted = new ArrayList<>();
        int j = d;
        while (j + 1 < toks.length) {
            String w = coreOf(toks[j + 1]);
            if (w == null || !w.matches("[A-Za-z0-9-]+")) break;
            dotted.add(w.toLowerCase(Locale.US));
            j++;
            if (TLDS.contains(dotted.get(dotted.size() - 1))) break;
            if (j + 1 >= toks.length) break;
            String dotCore = coreOf(toks[j + 1]);
            if (dotCore == null || !dotCore.equalsIgnoreCase("dot")) break;
            j++;
        }
        if (dotted.isEmpty() || !TLDS.contains(dotted.get(dotted.size() - 1))) return i;
        // Stem = plain words before the first dot. Longest suffix that yields
        // a canonical domain wins; else the last stem word joins generically.
        List<String> stem = new ArrayList<>();
        for (int k = i; k < d; k++) stem.add(coreOf(toks[k]).toLowerCase(Locale.US));
        String trail = splitAffixes(toks[j])[2];
        for (int suffix = stem.size(); suffix >= 1; suffix--) {
            List<String> labels = new ArrayList<>(stem.subList(stem.size() - suffix, stem.size()));
            labels.addAll(dotted);
            String cand = canonicalJoin(labels);
            if (cand != null) {
                for (int k = 0; k < stem.size() - suffix; k++) out.add(toks[i + k]);
                out.add(cand + trail);
                return j + 1;
            }
        }
        List<String> labels = new ArrayList<>();
        labels.add(stem.get(stem.size() - 1));
        labels.addAll(dotted);
        for (int k = 0; k < stem.size() - 1; k++) out.add(toks[i + k]);
        out.add(String.join(".", labels) + trail);
        return j + 1;
    }

    /**
     * The join of these labels that is one of the canonical domains, trying
     * the full join then every stem-collapse split; null if none matches.
     * ("crumb and craft dot com" collapses its stem; "vega stall dot
     * crumbroute dot app" keeps its middle dot.)
     */
    private static String canonicalJoin(List<String> labels) {
        Set<String> canon = new HashSet<>(Arrays.asList(DOMAINS));
        String full = String.join(".", labels);
        if (canon.contains(full)) return full;
        for (int split = 1; split < labels.size(); split++) {
            StringBuilder stem = new StringBuilder();
            for (int k = 0; k < split; k++) stem.append(labels.get(k));
            String cand = stem + "." + String.join(".", labels.subList(split, labels.size()));
            if (canon.contains(cand)) return cand;
        }
        return null;
    }

    /** "crumb and craft. com" / "crumbtest. app" -> the single clean domain token. */
    static String repairFragmentedDomains(String s) {
        Matcher m = Pattern.compile(
            "([A-Za-z0-9]+(?: [A-Za-z0-9]+){0,3})\\.\\s*(com|net|app|org|io|dev|co|us|info)\\b",
            Pattern.CASE_INSENSITIVE).matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String tld = m.group(2).toLowerCase(Locale.US);
            String[] words = m.group(1).split(" ");
            String replacement = null;
            // Longest suffix of the matched words whose spaceless form is a
            // known domain stem wins (the greedy match may have swallowed
            // leading sentence words like "check" or "open").
            for (int k = 0; k < words.length; k++) {
                StringBuilder stem = new StringBuilder();
                for (int w = k; w < words.length; w++) stem.append(words[w].toLowerCase(Locale.US));
                if (DOMAIN_STEMS.contains(stem.toString())) {
                    StringBuilder pre = new StringBuilder();
                    for (int w = 0; w < k; w++) { if (pre.length() > 0) pre.append(' '); pre.append(words[w]); }
                    replacement = (pre.length() > 0 ? pre + " " : "") + stem + "." + tld;
                    break;
                }
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(
                replacement != null ? replacement : m.group(0)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Layer: decode phrases + Anna rule
    // ------------------------------------------------------------------

    static String applyDecodePhrases(String s) {
        List<Map.Entry<String, String>> entries = new ArrayList<>(DECODE_PHRASES.entrySet());
        entries.sort((a, b) -> b.getKey().length() - a.getKey().length());
        String out = s;
        for (Map.Entry<String, String> en : entries) {
            Pattern p = Pattern.compile("\\b" + Pattern.quote(en.getKey()) + "\\b",
                    Pattern.CASE_INSENSITIVE);
            out = p.matcher(out).replaceAll(Matcher.quoteReplacement(en.getValue()));
        }
        return out;
    }

    /** Sentence-start "Anna" + function-word follower is the recognizer eating "And now". */
    static void applyAnnaRule(List<String> tokens, boolean[] shield) {
        for (int i = 0; i < tokens.size(); i++) {
            if (shield != null && shield[i]) continue; // a learned "Anna" is a person
            String core = coreOf(tokens.get(i));
            if (core == null || !core.equalsIgnoreCase("anna")) continue;
            boolean atStart = i == 0 || endsSentence(tokens.get(i - 1));
            if (!atStart) continue;
            if (i + 1 >= tokens.size()) continue;
            String next = coreOf(tokens.get(i + 1));
            if (next == null) continue;
            String nl = next.toLowerCase(Locale.US);
            boolean nextIsLower = !next.isEmpty() && Character.isLowerCase(next.charAt(0));
            if (nextIsLower && ANNA_FOLLOWERS.contains(nl)) {
                tokens.set(i, "And now");
            }
        }
    }

    private static boolean endsSentence(String token) {
        return token.endsWith(".") || token.endsWith("!") || token.endsWith("?");
    }

    // ------------------------------------------------------------------
    // Layer: multiword snap
    // ------------------------------------------------------------------

    static void applyMultiwordSnap(List<String> tokens, boolean[] shield) {
        // Longest entries first (4-word down to 2-word windows).
        List<String> multis = new ArrayList<>(Arrays.asList(LEXICON_MULTI));
        multis.sort((a, b) -> b.split(" ").length - a.split(" ").length);
        for (String entry : multis) {
            String[] ew = entry.split(" ");
            int w = ew.length;
            for (int i = 0; i + w <= tokens.size(); i++) {
                // A span touching a shielded (learned) token is his text,
                // not a garble of a lexicon phrase: hands off.
                if (shield != null) {
                    boolean spanShielded = false;
                    for (int k = 0; k < w; k++) if (shield[i + k]) { spanShielded = true; break; }
                    if (spanShielded) continue;
                }
                StringBuilder joined = new StringBuilder();
                boolean plausible = true;
                for (int k = 0; k < w; k++) {
                    String core = coreOf(tokens.get(i + k));
                    if (core == null || core.isEmpty() || core.indexOf('.') >= 0) { plausible = false; break; }
                    joined.append(core.toLowerCase(Locale.US));
                }
                if (!plausible) continue;
                String target = entry.replace(" ", "").toLowerCase(Locale.US);
                String j = joined.toString();
                int d = levenshtein(j, target);
                double sim = 1.0 - (double) d / Math.max(j.length(), target.length());
                if (d == 0 || (sim >= 0.84 && d <= 2)) {
                    tokens.set(i, entry);
                    for (int k = 1; k < w; k++) tokens.set(i + k, "");
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Layer: period discipline (voice only)
    // ------------------------------------------------------------------

    /**
     * Kill spurious mid-utterance breaks:
     *  - "word . word" spaced periods normalize to "word. word" first.
     *  - ". " before a strong continuer, or after a fragment of <=3 words,
     *    joins back with ", ".
     *  - ". " before a lowercase word after a real segment is a real break
     *    the recognizer under-capitalized: keep the period, capitalize.
     *  - Abbreviations and decimals are never touched.
     */
    static String periodDiscipline(String s) {
        String t = s.replaceAll(" \\. ", ". ").replaceAll(" \\.$", ".");
        StringBuilder out = new StringBuilder();
        int i = 0, n = t.length();
        int segStart = 0;
        while (i < n) {
            char c = t.charAt(i);
            if (c == '.' && i + 1 < n && t.charAt(i + 1) == ' '
                    && i + 2 < n && Character.isLetter(t.charAt(i + 2))) {
                // Word before the period.
                int ws = i - 1;
                while (ws >= 0 && (Character.isLetterOrDigit(t.charAt(ws)) || t.charAt(ws) == '.')) ws--;
                String wordBefore = t.substring(ws + 1, i).toLowerCase(Locale.US);
                // Word after.
                int we = i + 2;
                while (we < n && Character.isLetter(t.charAt(we))) we++;
                String wordAfter = t.substring(i + 2, we);
                boolean abbrev = ABBREV.contains(wordBefore)
                        || wordBefore.matches("[a-z](\\.[a-z])+");
                boolean decimal = i > 0 && Character.isDigit(t.charAt(i - 1));
                // v3.2: a period right after ? or ! is a glyph pair
                // (optional chaining "?.", never a sentence break).
                boolean glyphPair = i > 0 && (t.charAt(i - 1) == '?' || t.charAt(i - 1) == '!');
                if (!abbrev && !decimal && !glyphPair) {
                    String seg = t.substring(segStart, i).trim();
                    int segWords = seg.isEmpty() ? 0 : seg.split("\\s+").length;
                    boolean nextLower = Character.isLowerCase(wordAfter.charAt(0));
                    // v3.3 (field specimen, 2026-11-09): a capitalized word
                    // right after a continuer bridge means the continuer
                    // opens a new sentence ("... showing. and Colin should
                    // ...") -- the period was deliberate, so demotion is
                    // blocked on that signal alone, whatever the segment
                    // length. The mirrored spurious shape (". and
                    // Marisol ...") is indistinguishable from the
                    // specimen and also keeps its period; that is the
                    // documented cost of the signal.
                    boolean capAfterContinuer = false;
                    if (CONTINUERS.contains(wordAfter.toLowerCase(Locale.US))
                            && we + 1 < n && Character.isLetter(t.charAt(we + 1))
                            && Character.isUpperCase(t.charAt(we + 1))) {
                        capAfterContinuer = true;
                    }
                    if (nextLower && !capAfterContinuer
                            && (CONTINUERS.contains(wordAfter.toLowerCase(Locale.US))
                            || segWords <= 3)) {
                        out.append(", ");
                        i += 2;
                        segStart = out.length();
                        continue;
                    }
                    if (nextLower) {
                        out.append(". ");
                        out.append(Character.toUpperCase(wordAfter.charAt(0)));
                        i += 3;
                        segStart = out.length();
                        continue;
                    }
                }
            }
            out.append(c);
            if (c == '.' || c == '!' || c == '?') segStart = i + 1;
            i++;
        }
        return out.toString();
    }

    // ------------------------------------------------------------------
    // Normalization
    // ------------------------------------------------------------------

    static String normalize(String s) {
        String t = s.replaceAll(" +", " ");
        // v3.2: do not tighten a ? or ! that starts a glyph pair
        // ("?." optional chaining keeps its leading space).
        t = t.replaceAll(" ([,;:!?])(?!\\.)", "$1");
        t = t.trim();
        // Capitalize the first letter of the text (real sentence starts after
        // kept periods were already capitalized by periodDiscipline / snap).
        if (!t.isEmpty() && Character.isLowerCase(t.charAt(0))) {
            t = Character.toUpperCase(t.charAt(0)) + t.substring(1);
        }
        return t;
    }

    // ------------------------------------------------------------------
    // Layer: gated homophones (v2)
    // ------------------------------------------------------------------
    //
    // Homophone clusters where the recognizer hands us the wrong spelling
    // for the speaker's life. Every rule is a called default + a context gate:
    // signal words inside a +-3 token window fire the snap, guard words in
    // the same window veto it. Most clusters are gate-only (the heard
    // spelling stands unless the gate fires) because both readings are
    // daily words for the speaker; a wrong snap is worse than a miss. All rule
    // decisions evaluate against a snapshot of the line's cores, so rules
    // never cascade into each other.

    static final Set<String> NUMWORDS = new HashSet<>(Arrays.asList(
        "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
        "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen",
        "seventeen", "eighteen", "nineteen", "twenty", "thirty", "forty",
        "fifty", "sixty", "seventy", "eighty", "ninety", "hundred", "thousand",
        "half", "quarter", "dozen"));

    private static boolean isNumberish(String core) {
        if (core == null || core.isEmpty()) return false;
        if (NUMWORDS.contains(core)) return true;
        return hasDigit(core);
    }

    /** Token stripped of outer punctuation but keeping internal apostrophes, lowercased. */
    static String formOf(String token) {
        if (token == null) return "";
        int a = 0, b = token.length();
        while (a < b && !Character.isLetterOrDigit(token.charAt(a)) && token.charAt(a) != '\'') a++;
        while (b > a && !Character.isLetterOrDigit(token.charAt(b - 1)) && token.charAt(b - 1) != '\'') b--;
        return token.substring(a, b).toLowerCase(Locale.US);
    }

    private static void setCore(List<String> tokens, int i, String newCore) {
        String[] parts = splitAffixes(tokens.get(i));
        tokens.set(i, parts[0] + newCore + parts[2]);
    }

    private static String prevCore(String[] cores, int i) {
        for (int j = i - 1; j >= 0; j--) if (!cores[j].isEmpty()) return cores[j];
        return null;
    }

    private static String nextCore(String[] cores, int i) {
        for (int j = i + 1; j < cores.length; j++) if (!cores[j].isEmpty()) return cores[j];
        return null;
    }

    /** Any signal present in the +-4 window (excluding i). "num" matches any numberish core. */
    private static boolean windowHas(String[] cores, int i, Set<String> signals) {
        if (signals == null || signals.isEmpty()) return false;
        int lo = Math.max(0, i - 4), hi = Math.min(cores.length - 1, i + 4);
        for (int j = lo; j <= hi; j++) {
            if (j == i || cores[j].isEmpty()) continue;
            if (signals.contains(cores[j])) return true;
            if (signals.contains("num") && isNumberish(cores[j])) return true;
        }
        return false;
    }

    /** Any signal present anywhere in the line (used for the wide second group). */
    private static boolean lineHas(String[] cores, Set<String> signals) {
        if (signals == null || signals.isEmpty()) return false;
        for (String c : cores) {
            if (signals.contains(c)) return true;
            if (signals.contains("num") && isNumberish(c)) return true;
        }
        return false;
    }

    // --- phrase fixes: {sequence, index of the token to replace, replacement}
    private static final String[][] GATE_PHRASES = {
        {"for", "sail"},
        {"makes", "cents"},
        {"make", "cents"},
        {"weather", "or", "not"},
        {"my", "sun"},
        {"his", "sun"},
        {"old", "wives", "tail"},
        // v3.2 (Add-on C2, field specimen 2026-11-07): "vibe coders"
        // arrives as "Vibe Cutters". The word "vibe" itself is the
        // gate signal -- the pair is one unit, so the fix lives here
        // in the phrase table, not in a window rule. Real cutters
        // (pizza, wire, cookie) never carry the vibe prefix and stand.
        {"vibe", "cutters"},
        {"vibe", "cutter"},
        {"vibe", "cutting"},
    };
    private static final String[] GATE_PHRASE_TO = {
        "sale", "sense", "sense", "whether", "son", "son", "tale",
        "coders", "coder", "coding",
    };
    private static final int[] GATE_PHRASE_IDX = {1, 1, 1, 0, 1, 1, 2, 1, 1, 1};

    private void applyGatePhrases(List<String> tokens, String[] cores, boolean[] done) {
        for (int p = 0; p < GATE_PHRASES.length; p++) {
            String[] seq = GATE_PHRASES[p];
            for (int i = 0; i + seq.length <= tokens.size(); i++) {
                boolean match = true;
                for (int k = 0; k < seq.length; k++) {
                    if (!cores[i + k].equals(seq[k]) || done[i + k]) { match = false; break; }
                }
                if (match) {
                    int t = i + GATE_PHRASE_IDX[p];
                    setCore(tokens, t, GATE_PHRASE_TO[p]);
                    cores[t] = GATE_PHRASE_TO[p];
                    done[t] = true;
                }
            }
        }
    }

    /** "do's" -> dues under association signals; the "do's and don'ts" phrase is protected. */
    private void applyDues(List<String> tokens, String[] cores, String[] forms, boolean[] done) {
        Set<String> assoc = new HashSet<>(Arrays.asList(
            "monthly", "annual", "late", "member", "members", "market", "property"));
        for (int i = 0; i < tokens.size(); i++) {
            if (done[i]) continue;
            boolean isDos = forms[i].equals("do's") || cores[i].equals("dos");
            if (!isDos) continue;
            String nxt = nextCore(cores, i);
            if ("and".equals(nxt)) continue;
            boolean donts = false;
            int lo = Math.max(0, i - 3), hi = Math.min(forms.length - 1, i + 3);
            for (int j = lo; j <= hi; j++) if (forms[j].equals("don'ts")) donts = true;
            if (donts) continue;
            if (!windowHas(cores, i, assoc)) continue;
            if (forms[i].equals("do's")) {
                tokens.set(i, tokens.get(i).replaceFirst("(?i)do's", "dues"));
            } else {
                setCore(tokens, i, "dues");
            }
            cores[i] = "dues";
            done[i] = true;
        }
    }

    /**
     * DEMO MIRROR of a gated project-word rule: "baccarat" -> Bakkarat
     * when "testing" is in the +-4 window. Bakkarat is the demo
     * persona's invented bakery game (a bake pun on the card game); the
     * recognizer hears it as the casino word. Her tell is "testing" --
     * she is testing the game build. Without the signal, baccarat is
     * the card game and stands.
     */
    private void applyBakkaratGate(List<String> tokens, String[] cores, boolean[] done) {
        Set<String> sig = new HashSet<>(Arrays.asList("testing"));
        for (int i = 0; i < tokens.size(); i++) {
            if (done[i] || !cores[i].equals("baccarat")) continue;
            if (!windowHas(cores, i, sig)) continue;
            setCore(tokens, i, "Bakkarat");
            cores[i] = "bakkarat";
            done[i] = true;
        }
    }

    // --- v3.2 phrase gates: two-token (or one-token) garbles whose
    // target is a different word count ("local host" -> localhost,
    // "view js" -> Vue.js, "a sink" -> async, "you eye" -> UI,
    // "line ux" / "lineups" / "lennox" -> Linux). Each fires only
    // with its tech signal in the +-4 window and no guard present, so
    // the ordinary sentence ("the local host greeted us", "did you
    // eye the design") survives byte-identical. Merges blank the
    // second token, the multiword-snap idiom.
    private static final class PGate {
        final String w1, w2, to;
        final Set<String> sig, none, prevBlock;
        PGate(String w1, String w2, String to, String[] sig, String[] none,
              String[] prevBlock) {
            this.w1 = w1; this.w2 = w2; this.to = to;
            this.sig = toSet(sig); this.none = toSet(none);
            this.prevBlock = toSet(prevBlock);
        }
    }

    private static final List<PGate> PHRASE_GATES = new ArrayList<>();
    static {
        PHRASE_GATES.add(new PGate("local", "host", "localhost",
            A("port", "run", "running", "server", "dev", "development",
              "test", "open", "visit", "browser", "url"),
            A("greeted", "inn", "tour", "showed"), null));
        PHRASE_GATES.add(new PGate("view", "js", "Vue.js",
            A("framework", "app", "component", "build", "router", "spa",
              "dashboard"),
            A("file", "files", "browser", "source", "editor"), null));
        PHRASE_GATES.add(new PGate("a", "sink", "async",
            A("function", "functions", "await", "handler", "callback",
              "promise", "code"),
            A("kitchen", "bathroom", "faucet", "dishes", "plumbing"), null));
        PHRASE_GATES.add(new PGate("you", "eye", "UI",
            A("design", "layout", "screen", "interface", "update",
              "polish", "component", "element", "looks", "broken"),
            null,
            A("did", "do", "does", "can", "could", "will", "would", "if")));
        PHRASE_GATES.add(new PGate("line", "ux", "Linux",
            A("install", "installed", "boot", "distro", "server",
              "kernel", "terminal", "os", "runs", "run", "desktop",
              "laptop"),
            null, null));
        PHRASE_GATES.add(new PGate("lineups", null, "Linux",
            A("install", "installed", "boot", "distro", "server",
              "kernel", "terminal", "os"),
            A("store", "concert", "posted"), null));
        PHRASE_GATES.add(new PGate("lennox", null, "Linux",
            A("install", "installed", "boot", "distro", "os", "server",
              "kernel"),
            A("unit", "hvac", "furnace", "ac", "heating"), null));
    }

    private void applyPhraseGates(List<String> tokens, String[] cores, boolean[] done) {
        for (PGate pg : PHRASE_GATES) {
            for (int i = 0; i < tokens.size(); i++) {
                if (done[i] || !cores[i].equals(pg.w1)) continue;
                int j = i;
                if (pg.w2 != null) {
                    j = i + 1;
                    if (j >= tokens.size() || done[j] || !cores[j].equals(pg.w2)) continue;
                }
                if (windowHas(cores, i, pg.none)) continue;
                if (pg.w2 != null && windowHas(cores, j, pg.none)) continue;
                if (!pg.prevBlock.isEmpty()) {
                    String prev = prevCore(cores, i);
                    if (prev != null && pg.prevBlock.contains(prev)) continue;
                }
                if (!windowHas(cores, i, pg.sig)
                        && (pg.w2 == null || !windowHas(cores, j, pg.sig))) continue;
                setCore(tokens, i, pg.to);
                cores[i] = pg.to.toLowerCase(Locale.US);
                done[i] = true;
                if (pg.w2 != null) {
                    tokens.set(j, "");
                    cores[j] = "";
                    done[j] = true;
                }
            }
        }
    }

    // --- the caret machine (the worked example spec, see README.md) ---
    static final Set<String> CARET_FORMS = new HashSet<>(Arrays.asList(
        "carrot", "carat", "karat", "caret"));
    static final Set<String> CARET_PLURALS = new HashSet<>(Arrays.asList(
        "carrots", "carats"));
    static final Set<String> CARET_SYMCTX = new HashSet<>(Arrays.asList(
        "backslash", "tilde", "tildy", "asterisk", "slash", "caret", "code",
        "coding", "regex", "string", "function", "exponent", "symbol",
        "symbols", "command", "prompt"));
    static final Set<String> CARET_FOOD = new HashSet<>(Arrays.asList(
        "soup", "salad", "stew", "recipe", "cake", "grocery", "groceries",
        "dinner", "chop", "chopped", "slice", "sliced", "peel", "peeled",
        "rabbit", "pie", "sauce"));
    static final Set<String> CARET_GEM = new HashSet<>(Arrays.asList(
        "diamond", "stone", "gem", "ruby", "sapphire", "emerald", "solitaire",
        "weight", "weighs", "weighed", "point"));
    static final Set<String> CARET_GOLD = new HashSet<>(Arrays.asList("gold", "kt"));
    static final Set<String> CARET_APPROX = new HashSet<>(Arrays.asList(
        "about", "approximately", "around", "roughly"));

    /** Returns the core the caret-cluster token at i should become, or null to leave it. */
    private String resolveCaret(int i, String[] cores) {
        String core = cores[i];
        boolean plural = CARET_PLURALS.contains(core);
        // (4) symbol clusters / code context -> the glyph.
        if (!plural && windowHas(cores, i, CARET_SYMCTX)) return "^";
        // Food words anywhere in the line -> the vegetable.
        for (String c : cores) if (CARET_FOOD.contains(c)) return plural ? "carrots" : "carrot";
        // (1) vague small quantities -> the vegetable.
        String prev = prevCore(cores, i);
        if ("few".equals(prev) || "some".equals(prev)) return plural ? "carrots" : "carrot";
        if (plural) return null;
        // (2)+(3) jewelry readings: approximately+number, an exact number
        // right before, or gem/gold words nearby -> the ct/Kt gate.
        boolean approxNum = false;
        for (int j = Math.max(0, i - 3); j < i; j++) {
            if (CARET_APPROX.contains(cores[j])) {
                int lo = Math.max(0, i - 3), hi = Math.min(cores.length - 1, i + 3);
                for (int k = lo; k <= hi; k++) if (isNumberish(cores[k])) approxNum = true;
            }
        }
        boolean jewelry = approxNum || isNumberish(prev)
            || windowHas(cores, i, CARET_GEM) || windowHas(cores, i, CARET_GOLD);
        if (jewelry) return windowHas(cores, i, CARET_GOLD) ? "karat" : "carat";
        // The recognizer actually spelled "caret": glyph by default (ruling A).
        if (core.equals("caret")) return "^";
        return null;
    }

    // --- generic gate rules ---
    private static final class GRule {
        final String to, toAlt;
        final boolean dflt;
        final Set<String> from, any, any2, none, prevAny, nextAny, nextMust,
                          nextAlt, prevBlock, nextBlock;
        GRule(String to, String toAlt, boolean dflt, String[] from, String[] any,
              String[] any2, String[] none, String[] prevAny, String[] nextAny,
              String[] nextMust, String[] nextAlt, String[] prevBlock, String[] nextBlock) {
            this.to = to; this.toAlt = toAlt; this.dflt = dflt;
            this.from = toSet(from); this.any = toSet(any); this.any2 = toSet(any2);
            this.none = toSet(none); this.prevAny = toSet(prevAny);
            this.nextAny = toSet(nextAny); this.nextMust = toSet(nextMust);
            this.nextAlt = toSet(nextAlt); this.prevBlock = toSet(prevBlock);
            this.nextBlock = toSet(nextBlock);
        }
    }

    private static Set<String> toSet(String[] a) {
        return a == null ? new HashSet<String>() : new HashSet<>(Arrays.asList(a));
    }
    private static String[] A(String... ws) { return ws; }
    /** Gate-only: fire when a signal is present and no guard is. */
    private static GRule g(String to, String[] from, String[] any, String[] none) {
        return new GRule(to, null, false, from, any, null, none, null, null, null, null, null, null);
    }
    /** Gate-only with two required signal groups. */
    private static GRule g2(String to, String[] from, String[] any, String[] any2, String[] none) {
        return new GRule(to, null, false, from, any, any2, none, null, null, null, null, null, null);
    }
    /** Gate-only with prev/next word blocks. */
    private static GRule gb(String to, String[] from, String[] any, String[] none,
                            String[] prevBlock, String[] nextBlock) {
        return new GRule(to, null, false, from, any, null, none, null, null, null, null, prevBlock, nextBlock);
    }
    /** Default: fire unless a guard appears. */
    private static GRule d(String to, String[] from, String[] none) {
        return new GRule(to, null, true, from, null, null, none, null, null, null, null, null, null);
    }
    /** Position gate: the previous word must be one of prevAny. */
    private static GRule p(String to, String[] from, String[] prevAny) {
        return new GRule(to, null, false, from, null, null, null, prevAny, null, null, null, null, null);
    }
    /** Position gate: the next word must be one of nextAny (signals also required when given). */
    private static GRule n(String to, String[] from, String[] nextAny, String[] any, String[] none) {
        return new GRule(to, null, false, from, any, null, none, null, nextAny, null, null, null, null);
    }
    /** The next word must be one of nextMust and a signal must be present. */
    private static GRule nm(String to, String[] from, String[] nextMust, String[] any, String[] none) {
        return new GRule(to, null, false, from, any, null, none, null, null, nextMust, null, null, null);
    }

    static final List<GRule> GATE_RULES = new ArrayList<>();
    static {
        // bench / jewelry
        GATE_RULES.add(d("solder", A("soldier"),
            A("army", "war", "veteran", "veterans", "troops", "troop", "uniform",
              "memorial", "fortune", "game", "toy", "story", "book", "movie")));
        GATE_RULES.add(g("loupe", A("loop"),
            A("stone", "diamond", "gem", "eye", "inspect", "prongs", "setting", "jeweler", "magnification"),
            A("code", "function", "while", "for", "infinite", "video", "audio", "game")));
        GATE_RULES.add(g("bail", A("bale"),
            A("pendant", "necklace", "chain", "bent", "broke", "broken", "jewelry"),
            A("hay", "farm", "straw", "cotton")));
        GATE_RULES.add(g("gilt", A("guilt"),
            A("frame", "antique", "edge", "edges", "gold", "mirror", "original"),
            A("no", "about", "feel", "feeling", "trip")));
        GATE_RULES.add(g("banned", A("band"),
            A("account", "seller", "policy", "appeal"),
            A("watch", "steel", "leather", "rubber", "wedding")));
        GATE_RULES.add(g("assay", A("essay"),
            A("gold", "metal", "office", "silver"),
            A("write", "school", "chapter", "college", "writing")));
        GATE_RULES.add(g("LED", A("lead", "led"),
            A("light", "lights", "bulb", "bulbs", "tube", "tubes", "strip", "strips",
              "kit", "kits", "panel", "fixture", "fixtures", "retro"),
            A("pipe", "paint", "solder", "fishing", "sinker", "poison", "dog", "leash")));
        // The verb "led" with a pronoun subject is never the light.
        GATE_RULES.set(GATE_RULES.size() - 1, gb("LED", A("lead", "led"),
            A("light", "lights", "bulb", "bulbs", "tube", "tubes", "strip", "strips",
              "kit", "kits", "panel", "fixture", "fixtures", "retro"),
            A("pipe", "paint", "solder", "fishing", "sinker", "poison", "dog", "leash"),
            A("he", "she", "they", "we", "i", "you", "who"), null));
        GATE_RULES.add(g("metal", A("medal"),
            A("band", "detector", "roof", "sheet", "scrap"),
            A("won", "gold", "silver", "bronze", "olympic", "honor")));
        GATE_RULES.add(g("steel", A("steal"),
            A("band", "stainless", "case", "bracelet", "brushed", "frame", "metal"),
            A("thief", "stole", "stealing", "tried", "someone", "never", "they", "he", "she", "we", "you", "i")));
        GATE_RULES.add(g("bare", A("bear"),
            A("wire", "wires", "copper", "wood", "floor", "floors", "minimum", "bones"),
            A("animal", "woods", "market", "hug", "cub", "grizzly")));
        // commerce / eBay
        GATE_RULES.add(g("sell", A("cell"),
            A("ebay", "listing", "listings", "price", "priced", "inventory", "mower", "marketplace", "facebook", "tickets"),
            A("battery", "phone", "jail", "prison", "spreadsheet", "signal", "tower", "stem", "plan")));
        GATE_RULES.add(g("sale", A("sail"),
            A("price", "prices", "ebay", "listing", "garage", "yard", "discount", "percent"),
            A("boat", "lake", "wind", "water", "ship", "sea", "ocean", "sailboat")));
        GATE_RULES.add(g("seller", A("cellar"),
            A("feedback", "ebay", "rating", "ratings", "shipped", "returns", "store"),
            A("storm", "tornado", "wine", "basement", "underground")));
        GATE_RULES.add(g("bid", A("bit"),
            A("ebay", "auction", "listing", "offer", "place", "placed", "item"),
            A("little", "drill")));
        GATE_RULES.add(g("profit", A("prophet"),
            A("margin", "margins", "numbers", "quarter", "loss", "sales", "business"),
            A("bible", "testament", "church", "ancient")));
        GATE_RULES.add(g("capital", A("capitol"),
            A("raise", "raised", "need", "more", "money", "business", "working"),
            A("building", "dome", "tour", "visit", "steps", "hill")));
        GATE_RULES.add(g("cache", A("cash"),
            A("browser", "clear", "memory", "computer", "site", "page", "cookies"),
            A("pay", "paid", "money", "dollars", "register", "flow")));
        GATE_RULES.add(p("sum", A("some"), A("lump")));
        GATE_RULES.add(g("sum", A("some"), A("total"), null));
        // software / projects
        GATE_RULES.add(g("site", A("sight"),
            A("update", "deploy", "page", "traffic", "website", "online", "live", "hosting"),
            A("gun", "rifle", "bow", "tourist", "seeing", "sights")));
        GATE_RULES.add(g("site", A("cite"), A("web"), null));
        GATE_RULES.add(g("host", A("ghost"),
            A("server", "site", "website", "deploy", "dinner", "party", "podcast"),
            A("story", "stories", "haunted", "halloween", "movie", "costume")));
        GATE_RULES.add(g("role", A("roll"),
            A("harness", "team", "agent", "job", "project", "system"),
            A("tape", "bread", "paper", "dinner", "towels")));
        GATE_RULES.add(g("roll", A("role"),
            A("tape", "bread", "dinner", "paper", "towels"),
            A("job", "team", "agent", "harness")));
        GATE_RULES.add(g("miner", A("minor"),
            A("bitcoin", "crypto", "usb", "hash", "mining", "btc", "stick", "sticks"),
            A("repair", "child", "children", "violation", "surgery", "league")));
        GATE_RULES.add(g("write", A("right"),
            A("chapter", "book", "letter", "essay", "story", "article", "post"),
            A("turn", "left", "now", "away", "answer", "correct", "side")));
        GATE_RULES.add(n("write", A("right"), A("down"), null,
            A("road", "street", "there", "here", "now", "this")));
        GATE_RULES.add(g("alter", A("altar"),
            A("table", "database", "column", "query", "script"),
            A("church", "wedding", "candles")));
        GATE_RULES.add(g("altar", A("alter"),
            A("church", "wedding", "candles"),
            A("table", "database", "column", "query")));
        GATE_RULES.add(g("chord", A("cord"),
            A("guitar", "piano", "music", "minor", "major", "play", "played"),
            A("extension", "plug", "power")));
        GATE_RULES.add(p("chord", A("cord"), A("g", "d", "e", "c", "f")));
        GATE_RULES.add(g("cord", A("chord"),
            A("extension", "plug", "plugged", "power", "outlet", "usb"),
            A("guitar", "piano", "music", "minor", "major")));
        GATE_RULES.add(g("suite", A("sweet"),
            A("office", "hotel", "software", "rooms", "room", "test", "testing"),
            A("tea", "tooth", "potato", "potatoes", "home", "smell")));
        GATE_RULES.add(g("male", A("mail"),
            A("voice", "narrator", "actor", "audiobook", "model"),
            A("check", "inbox", "package", "box")));
        GATE_RULES.add(gb("aloud", A("allowed"),
            A("read", "reading", "book", "chapter", "letter", "story"),
            A("not", "no", "inside", "entry", "access"),
            A("read"), null));
        GATE_RULES.add(g("read", A("red"),
            A("chapter", "book", "page", "letter", "email", "note", "notes", "article", "instructions"),
            A("truck", "light", "paint", "wine", "car", "door", "flag", "color")));
        GATE_RULES.add(g("red", A("read"),
            A("truck", "light", "paint", "wine", "color"),
            A("book", "chapter", "page", "letter")));
        // kitchen / food
        GATE_RULES.add(g("flour", A("flower"),
            A("bread", "bake", "baking", "dough", "cups", "cup", "recipe", "wheat", "tortillas"),
            A("garden", "bed", "beds", "vase", "spring", "wild", "pot", "tea")));
        GATE_RULES.add(g("thyme", A("time"),
            A("teaspoon", "teaspoons", "tablespoon", "tablespoons", "herb", "herbs", "sprig", "sprigs", "fresh", "dried"),
            A("what", "meeting", "lunch", "now")));
        GATE_RULES.add(g("sauce", A("source"),
            A("tomato", "pasta", "homemade", "recipe", "marinara", "pizza", "spaghetti", "jar"),
            A("code", "file", "files", "repo", "data", "open")));
        GATE_RULES.add(g("source", A("sauce"),
            A("code", "file", "files", "repo", "data", "control"),
            A("tomato", "pasta", "homemade", "recipe", "pizza", "marinara")));
        GATE_RULES.add(g("steak", A("stake"),
            A("grill", "grilled", "ribeye", "dinner", "steakhouse"),
            A("percent", "company", "equity", "claim")));
        GATE_RULES.add(g("stake", A("steak"),
            A("percent", "company", "business", "money", "equity", "claim", "venture"),
            A("grill", "grilled", "dinner", "ribeye", "medium")));
        GATE_RULES.add(g2("knead", A("need"),
            A("dough"), A("rise", "rest", "minutes", "flour", "bread", "recipe"),
            A("money", "cash", "friday")));
        GATE_RULES.add(g("leeks", A("leaks"),
            A("soup", "potato", "recipe", "grocery", "groceries", "bunch"),
            A("roof", "pipe", "pipes", "faucet", "ceiling", "water")));
        GATE_RULES.add(g("beets", A("beats"),
            A("roasted", "roast", "pickled", "salad", "dinner", "canned"),
            A("music", "track", "audio", "song", "headphones")));
        GATE_RULES.add(g("currant", A("current"),
            A("jam", "jelly", "berry", "berries", "scone", "scones"),
            A("coil", "circuit", "amps", "voltage", "electric", "events", "account")));
        GATE_RULES.add(g("current", A("currant"),
            A("coil", "circuit", "amps", "voltage", "wire", "electric"),
            A("jam", "jelly", "berry")));
        GATE_RULES.add(g("grate", A("great"),
            A("drain", "rusted", "rusting", "cover", "iron"),
            A("deal", "day", "job")));
        GATE_RULES.add(g("great", A("grate"),
            A("deal", "job", "price", "work"),
            A("cheese", "drain", "metal")));
        GATE_RULES.add(d("pair", A("pare"),
            A("knife", "peel", "peeling", "nails", "down", "back")));
        GATE_RULES.add(g("pair", A("pear"),
            A("earrings", "shoes", "socks", "gloves", "of"),
            A("fruit", "tree", "lunch", "salad", "juice", "ripe")));
        GATE_RULES.add(g("pear", A("pair"),
            A("fruit", "tree", "lunch", "salad", "juice", "ripe"),
            A("earrings", "shoes", "socks", "gloves", "of")));
        GATE_RULES.add(g("plain", A("plane"),
            A("yogurt", "bagel", "shirt", "text"),
            A("flight", "wood", "tool", "ticket")));
        GATE_RULES.add(g("plane", A("plain"),
            A("flight", "ticket", "tickets", "leaves", "departs", "wood", "tool", "catch"),
            A("yogurt", "shirt", "bagel", "text", "simple")));
        // HOA / property / life
        GATE_RULES.add(g("gate", A("gait"),
            A("code", "codes", "opener", "storage", "market", "keypad", "community", "front", "back"),
            A("biscuit", "dog", "horse", "walk", "walking", "funny", "odd")));
        GATE_RULES.add(g("base", A("bass"),
            A("umbrella", "stand", "bottom", "heavy", "lamp", "table"),
            A("fish", "fishing", "music", "song", "guitar", "line", "drum")));
        GATE_RULES.add(g("bass", A("base"),
            A("fish", "fishing", "music", "song", "guitar", "drum"),
            A("umbrella", "stand", "bottom", "data", "military")));
        GATE_RULES.add(g("board", A("bored"),
            A("meeting", "directors", "vote", "members", "market"),
            A("am", "so", "very", "feel", "feeling", "tonight")));
        GATE_RULES.add(g("pole", A("poll"),
            A("flag", "fishing", "light", "tent", "utility", "lamp"),
            A("vote", "votes", "voting", "survey", "election", "results", "closes")));
        GATE_RULES.add(g("poll", A("pole"),
            A("vote", "survey", "election", "results"),
            A("flag", "fishing", "tent", "light")));
        GATE_RULES.add(g("lien", A("lean"),
            A("property", "file", "filed", "place", "placed", "owner", "house", "market"),
            A("beef", "meat", "protein", "ground", "turkey", "chicken")));
        GATE_RULES.add(g("tow", A("toe"),
            A("car", "truck", "vehicle", "parked", "parking", "zone"),
            A("stubbed", "shoe", "shoes", "nail", "foot", "big")));
        GATE_RULES.add(g("principal", A("principle"),
            A("loan", "mortgage", "balance", "payment", "school", "amount", "interest"),
            A("matter")));
        GATE_RULES.add(gb("patients", A("patience"),
            A("doctor", "clinic", "office", "nurse", "waiting"),
            A("have", "traffic", "line", "kids", "with"),
            A("in"), null));
        GATE_RULES.add(g("weather", A("whether"),
            A("forecast", "channel", "report", "app"), null));
        GATE_RULES.add(g("leased", A("least"),
            A("storage", "unit", "property", "building", "office", "contract"),
            A("common", "measure", "most", "bit", "favorite")));
        GATE_RULES.set(GATE_RULES.size() - 1, gb("leased", A("least"),
            A("storage", "unit", "property", "building", "office", "contract"),
            A("common", "measure", "most", "bit", "favorite"),
            A("at"), null));
        GATE_RULES.add(g("sense", A("cents"), A("common"), null));
        // family / body / book
        GATE_RULES.add(g("son", A("sun"),
            A("called", "texted", "said", "told", "asked"),
            A("hot", "sunny", "solar", "eclipse", "shining", "shine", "rises", "sunset")));
        GATE_RULES.add(nm("piece", A("peace"), A("of"),
            A("jewelry", "cake", "furniture", "equipment", "paper", "wood", "metal", "art", "land"), null));
        GATE_RULES.add(g("waist", A("waste"),
            A("inches", "inch", "down", "size", "pants", "belt", "measurement"),
            A("time", "money", "day", "food", "water", "energy")));
        GATE_RULES.add(gb("weight", A("wait"),
            A("shipping", "package", "scale", "pounds", "lbs", "ounces", "total"),
            A("minute", "minutes", "please", "hour", "hours", "second", "seconds"),
            null, A("for")));
        GATE_RULES.add(g("sole", A("soul"),
            A("shoe", "shoes", "boot", "boots", "worn", "sneaker", "sneakers", "walking"),
            A("food", "heart", "music")));
        GATE_RULES.add(g("soul", A("sole"),
            A("food", "heart", "music", "mate", "searching"),
            A("shoe", "shoes", "boot", "boots", "worn", "sneaker", "sneakers")));
        GATE_RULES.add(g("heel", A("heal"),
            A("biscuit", "dog", "side", "training", "command"),
            A("cut", "wound", "scrape", "surgery", "bone", "doctor")));
        GATE_RULES.add(g("heal", A("heel"),
            A("cut", "wound", "scrape", "surgery", "bone", "infection", "doctor"),
            A("shoe", "shoes", "biscuit", "dog", "command")));
        GATE_RULES.add(g("paws", A("pause"),
            A("muddy", "biscuit", "porch", "prints", "wet", "dog"),
            A("video", "music", "song", "button", "playback")));
        GATE_RULES.add(g("tale", A("tail"),
            A("story", "fairy", "wives", "tell"),
            A("biscuit", "dog", "wagged", "wag", "long")));
        GATE_RULES.add(g("mourning", A("morning"),
            A("funeral", "grief", "loss", "passed", "passing"),
            A("good", "this", "every", "tomorrow", "meeting")));
        GATE_RULES.add(g("scent", A("sent"),
            A("smell", "candle", "candles", "perfume", "rain", "strong", "flowers"),
            A("email", "message", "text", "texted")));
        GATE_RULES.set(GATE_RULES.size() - 1, gb("scent", A("sent"),
            A("smell", "candle", "candles", "perfume", "rain", "strong", "flowers"),
            A("email", "message", "text", "texted"),
            A("i", "he", "she", "we", "they", "you", "it"), null));
        // general
        GATE_RULES.add(g("aisle", A("isle"),
            A("store", "grocery", "groceries", "baking", "shelf", "shelves", "cleaning", "num"),
            A("skye")));
        GATE_RULES.add(g("brake", A("break"),
            A("pads", "pad", "rotors", "rotor", "pedal", "caliper", "fluid"),
            A("lunch", "noon", "news", "room", "spring", "summer", "even")));
        GATE_RULES.add(g("hole", A("whole"),
            A("drill", "dig", "dug", "pocket", "wall"), null));
        GATE_RULES.add(g("weak", A("week"),
            A("signal", "wifi", "coffee", "engine", "knees", "feel", "feeling", "stream"),
            A("next", "last", "this", "per", "every")));
        GATE_RULES.add(n("wear", A("where"), A("my", "your", "his", "her"),
            A("watch", "shirt", "shoes", "suit", "ring", "glasses", "hat", "jacket"), null));
        GATE_RULES.add(p("seen", A("scene"), A("have", "has", "had")));
        GATE_RULES.add(g("scene", A("seen"),
            A("opening", "game", "film", "movie", "level"),
            A("have", "has", "had")));
        GATE_RULES.add(p("hear", A("here"), A("can", "could", "did", "barely")));
        GATE_RULES.add(n("here", A("hear"), A("is", "are", "was"), null, null));
        GATE_RULES.add(g("wrap", A("rap"),
            A("gift", "gifts", "present", "presents", "package", "paper", "box"),
            A("music", "song", "album", "verse")));
        GATE_RULES.add(g("rap", A("wrap"),
            A("music", "song", "album", "verse"),
            A("gift", "gifts", "present", "presents", "package", "paper")));
        GATE_RULES.add(gb("road", A("rode"),
            A("home", "trip", "gravel", "dirt", "closed", "name", "back"),
            A("horse", "bike", "bus", "motorcycle"),
            A("he", "she", "i", "we", "they", "you", "it"), null));
        GATE_RULES.add(g("night", A("knight"),
            A("last", "tonight", "every", "late", "all", "friday", "saturday"),
            A("armor", "sword", "chess", "game", "dragon", "sir")));
        GATE_RULES.add(new GRule("main", "Main", false, A("mane"), null, null,
            A("lion", "horse", "animal"), null, null, null,
            A("street", "st"), null, null));
        GATE_RULES.add(g("main", A("mane"),
            A("project", "office", "line", "water", "sewer", "reason"),
            A("lion", "horse", "animal")));
        // ---- v3.2: vibe-coder hardening (Big List gates) ----
        // Add-on A: his dictated "get" IS the recognizer's "git". The
        // gate fires only on a git-command word in the window; the
        // ordinary verb ("get the build started", "go get the file")
        // carries none and stands. Tree/fire guards cover the
        // "branch"/"log" collisions with yard work.
        GATE_RULES.add(g("git", A("get"),
            A("commit", "push", "pull", "clone", "status", "branch",
              "merge", "checkout", "stash", "diff", "log", "rebase",
              "remote", "init"),
            A("tree", "fire", "fireplace", "firewood")));
        GATE_RULES.add(g("Bash", A("bash"),
            A("script", "scripts", "terminal", "shell", "command",
              "commands", "prompt", "run", "running", "file", "session"),
            A("birthday", "party", "door")));
        // Read-never-heard family (Add-on D).
        GATE_RULES.add(g("SQL", A("sequel"),
            A("query", "database", "table", "server", "schema", "join",
              "select", "db"),
            A("movie", "film", "novel")));
        GATE_RULES.add(g("sudo", A("pseudo"),
            A("apt", "install", "run", "command", "terminal", "root",
              "chmod", "chown", "systemctl", "make"),
            A("science", "random", "name")));
        GATE_RULES.add(g("daemon", A("demon"),
            A("process", "service", "services", "background", "server",
              "start", "stop", "restart", "killed", "runs", "running",
              "logs"),
            A("movie", "church", "hell", "possessed", "soul", "bible")));
        GATE_RULES.add(g("cache", A("catch"),
            A("clear", "flush", "bust", "browser", "memory", "cookies"),
            A("ball", "fish", "door", "breath", "latch", "play", "game")));
        GATE_RULES.add(g("GIF", A("jif"),
            A("image", "images", "file", "animated", "animation",
              "meme", "format", "loop"),
            A("peanut", "butter", "choosy", "brand")));
        GATE_RULES.add(g("LaTeX", A("latex"),
            A("document", "documents", "paper", "thesis", "manuscript",
              "journal", "typeset", "bibliography"),
            A("gloves", "paint", "allergy", "mattress")));
        // AI ecosystem traps (Add-on C). "cloud" is deliberately
        // untouched: it is itself a tech word and must never become
        // Claude. Only the non-tech garble "clawed" may repair, and
        // only in person-like AI context.
        GATE_RULES.add(g("Claude", A("clawed"),
            A("ask", "asked", "said", "says", "told", "wrote", "write",
              "writes", "prompt", "chat", "ai", "model", "code",
              "review"),
            A("cat", "dog", "door", "furniture", "sofa", "curtain")));
        GATE_RULES.add(g("Gemini", A("gemini"),
            A("ask", "google", "ai", "model", "chat", "app", "summary"),
            A("zodiac", "sign", "twins", "horoscope", "constellation")));
        GATE_RULES.add(g("Groq", A("grok", "grock"),
            A("api", "whisper", "inference", "chip", "chips", "lpu",
              "speed", "fast", "latency"),
            A("xai", "twitter", "elon", "musk", "x")));
        GATE_RULES.add(g("Grok", A("grock"),
            A("xai", "twitter", "elon", "musk"),
            A("api", "whisper", "inference", "chip")));
        GATE_RULES.add(g("Whisper", A("whisper"),
            A("model", "transcribe", "transcription", "audio",
              "speech", "openai"),
            A("softly", "ear", "secret", "quiet", "kids", "sleep")));
        GATE_RULES.add(g("Perplexity", A("perplexity"),
            A("ask", "search", "ai", "research", "answer", "answers",
              "engine"),
            A("confusion", "state", "my")));
        GATE_RULES.add(g("Lovable", A("lovable"),
            A("app", "builder", "ai", "site", "build", "landing"),
            A("dog", "puppy", "cat", "grandma", "kid", "kids")));
        GATE_RULES.add(g("Windsurf", A("windsurf"),
            A("editor", "ide", "code", "ai", "project"),
            A("lake", "beach", "board", "ocean", "waves")));
        GATE_RULES.add(g("Mistral", A("mistral"),
            A("model", "ai", "weights", "local", "locally", "api"),
            A("wind", "france", "southern")));
        GATE_RULES.add(g("Llama", A("llama"),
            A("model", "meta", "weights", "local", "locally", "ollama",
              "run"),
            A("farm", "wool", "zoo", "hay", "petting")));
        GATE_RULES.add(g("RAG", A("rag"),
            A("pipeline", "retrieval", "embeddings", "vector", "chunk",
              "chunks", "chatbot", "llm", "corpus", "index"),
            A("cleaning", "cloth", "dish", "mop", "soaked", "counter")));
        GATE_RULES.add(g("JSON", A("jason"),
            A("parse", "parsing", "format", "file", "data", "object",
              "payload", "string", "api", "schema", "config"),
            A("called", "texted", "said", "told", "asked", "birthday")));
        // Languages / frameworks / infra with real-word forms.
        GATE_RULES.add(g("Tailwind", A("tailwind"),
            A("css", "classes", "class", "utility", "config", "ui",
              "design", "styling", "styles", "style"),
            A("flight", "plane", "knots", "runway", "cycling",
              "sailing")));
        GATE_RULES.add(g("Angular", A("angular"),
            A("framework", "app", "component", "version", "cli",
              "module"),
            A("face", "features", "jaw", "shape", "cheek")));
        GATE_RULES.add(g("Svelte", A("svelte"),
            A("framework", "app", "component", "kit", "compiler"),
            A("figure", "silhouette")));
        GATE_RULES.add(g("Django", A("django"),
            A("python", "app", "framework", "model", "models", "admin",
              "orm"),
            A("film", "movie", "western", "unchained")));
        GATE_RULES.add(g("Docker", A("docker"),
            A("container", "containers", "image", "images", "compose",
              "file", "hub", "volume"),
            A("port", "ship", "union", "retired")));
        GATE_RULES.add(g("Azure", A("azure"),
            A("cloud", "microsoft", "deploy", "deploys", "functions",
              "devops", "subscription"),
            A("sky", "blue", "water", "sea", "lake")));
        GATE_RULES.add(g("Clerk", A("clerk"),
            A("auth", "login", "sign", "users", "user", "session",
              "sessions"),
            A("store", "desk", "hotel", "counter", "shop")));
        GATE_RULES.add(g("Stripe", A("stripe"),
            A("payments", "payment", "checkout", "billing", "api",
              "invoice", "invoices"),
            A("shirt", "flag", "paint", "zebra", "tiger")));
        GATE_RULES.add(g("Terraform", A("terraform"),
            A("infra", "config", "apply", "plan", "aws", "cloud",
              "module", "modules"),
            A("planet", "mars", "moon")));
        GATE_RULES.add(g("Drizzle", A("drizzle"),
            A("orm", "schema", "sql", "database", "query"),
            A("rain", "cake", "sauce", "oil", "porch")));
        GATE_RULES.add(g("Rails", A("rails"),
            A("ruby", "framework", "server", "app", "gem"),
            A("train", "stair", "stairs", "metal", "guard", "track")));
        // Coder homophones (Step 1).
        GATE_RULES.add(g("queue", A("cue"),
            A("job", "jobs", "build", "builds", "worker", "workers",
              "message", "messages", "task", "tasks", "deploy",
              "deploys"),
            A("music", "song", "lights", "stage", "pool")));
        GATE_RULES.add(g("byte", A("bite"),
            A("array", "stream", "order", "code", "data"),
            A("sandwich", "apple", "food", "snack")));
        GATE_RULES.add(g("kernel", A("colonel"),
            A("panic", "linux", "boot", "module", "version", "os",
              "driver"),
            A("sanders", "army", "chicken", "promoted", "regiment")));
        // write/right for code: the existing rules own prose writing;
        // this one additionally requires a verb position (after a
        // modal / "to"), so the adjective ("the right function")
        // can never fire it.
        GATE_RULES.add(new GRule("write", null, false, A("right"),
            A("function", "functions", "code", "script", "query",
              "test", "tests", "handler", "component"),
            null,
            A("turn", "left", "now", "away", "answer", "correct",
              "side"),
            A("to", "will", "would", "should", "must", "can", "could",
              "need", "needs", "let", "please"),
            null, null, null, null, null));
        // React: position gates only -- the verb ("react to the
        // news") is protected by requiring a component-class next
        // word or an in/with/using before it.
        GATE_RULES.add(n("React", A("react"),
            A("component", "components", "app", "native", "hook",
              "hooks", "version"),
            null, null));
        GATE_RULES.add(new GRule("React", null, false, A("react"),
            null, null,
            A("to", "when", "if", "because"),
            A("in", "with", "using"),
            null, null, null, null, null));
        // REST: only in the "rest api" adjacency.
        GATE_RULES.add(n("REST", A("rest"), A("api"), null,
            A("break", "sleep", "day", "area", "room", "stop", "long",
              "drive")));
        // Prisma: gated, never a lexicon entry -- "prism" (his bench
        // optics) sits one edit away and must stay an ordinary word.
        GATE_RULES.add(g("Prisma", A("prisma"),
            A("orm", "database", "schema", "sql", "client", "query"),
            A("light", "glass", "spectrum", "optics")));
    }

    private void applyGenericRule(List<String> tokens, String[] cores, int i, boolean[] done) {
        String core = cores[i];
        String prev = prevCore(cores, i);
        String next = nextCore(cores, i);
        for (GRule r : GATE_RULES) {
            if (!r.from.contains(core)) continue;
            if (windowHas(cores, i, r.none)) continue;
            if (prev != null && r.prevBlock.contains(prev)) continue;
            if (next != null && r.nextBlock.contains(next)) continue;
            boolean anyPresent = r.any.isEmpty() || windowHas(cores, i, r.any);
            boolean any2Present = r.any2.isEmpty() || lineHas(cores, r.any2);
            String target = r.to;
            boolean posOk = true;
            if (!r.nextAlt.isEmpty()) {
                if (next != null && r.nextAlt.contains(next)) target = r.toAlt;
                else continue;
            } else if (!r.prevAny.isEmpty()) {
                posOk = prev != null && r.prevAny.contains(prev);
            } else if (!r.nextAny.isEmpty()) {
                posOk = next != null && r.nextAny.contains(next);
            } else if (!r.nextMust.isEmpty()) {
                posOk = next != null && r.nextMust.contains(next);
            }
            boolean fire = r.dflt ? posOk : (posOk && anyPresent && any2Present);
            if (fire) {
                setCore(tokens, i, target);
                cores[i] = target.toLowerCase(Locale.US);
                done[i] = true;
                return;
            }
        }
    }

    /** Full gate pass: phrases, dues, the caret machine, then generic rules. */
    void applyGates(List<String> tokens) {
        applyGates(tokens, null);
    }

    /** Gate pass honoring v3 shields: shielded tokens start as done. */
    void applyGates(List<String> tokens, boolean[] shield) {
        int n = tokens.size();
        String[] cores = new String[n];
        String[] forms = new String[n];
        for (int i = 0; i < n; i++) {
            String c = coreOf(tokens.get(i));
            cores[i] = c == null ? "" : c.toLowerCase(Locale.US);
            forms[i] = formOf(tokens.get(i));
        }
        boolean[] done = new boolean[n];
        if (shield != null) {
            for (int i = 0; i < n && i < shield.length; i++) done[i] = shield[i];
        }
        applyGatePhrases(tokens, cores, done);
        applyDues(tokens, cores, forms, done);
        applyBakkaratGate(tokens, cores, done);
        applyPhraseGates(tokens, cores, done);
        for (int i = 0; i < n; i++) {
            if (done[i] || cores[i].isEmpty()) continue;
            if (CARET_FORMS.contains(cores[i]) || CARET_PLURALS.contains(cores[i])) {
                String r = resolveCaret(i, cores);
                if (r != null) {
                    if (!r.equals(cores[i])) setCore(tokens, i, r);
                    done[i] = true;
                }
                continue;
            }
            applyGenericRule(tokens, cores, i, done);
        }
    }


    // ------------------------------------------------------------------
    // Layer: Colin -> colon gated decode (v3.3, voice only)
    // ------------------------------------------------------------------
    //
    // The speaker said "colon"; the recognizer delivered "Colin" (field
    // specimen). The standing call: "Colin should USUALLY come in
    // as colon." So the default is CONVERT -- to the WORD "colon",
    // never the glyph ":" (the glyph layer's period/colon exclusion
    // is a standing v2 ruling and is untouched). Person evidence
    // vetoes the conversion; with no evidence either way the default
    // stands, which is what "usually" means. There is no Colin in the
    // people index, so the documented call is: evidence of a person
    // wins, silence converts. The possessive is absolute (a person
    // owns things; a colon does not). "semi Colin" merges to the
    // single token "semicolon", which the glyph layer then renders
    // ";" exactly as the spoken word does. This gate is voice-only:
    // a TYPED "Colin" is a name the user spelled on purpose, and the
    // typed word API's proper-name exclusion keeps hands off it.

    /** Words before "Colin" that prove a person (tell/ask/title context). */
    static final Set<String> COLIN_PERSON_PREV = new HashSet<>(Arrays.asList(
        "tell", "ask", "told", "asked", "call", "called", "text", "texted",
        "email", "emailed", "thank", "thanked", "meet", "met", "visit",
        "dear", "mr", "mrs", "ms", "dr", "see", "saw"));

    /** Person verbs right after "Colin" (he is the sentence subject). */
    static final Set<String> COLIN_PERSON_NEXT = new HashSet<>(Arrays.asList(
        "said", "says", "went", "goes", "going", "is", "was", "were", "are",
        "called", "calls", "calling", "texted", "texts", "wants", "want",
        "asked", "asks", "told", "came", "comes", "lives", "lived", "works",
        "worked", "sent", "replied", "answered", "arrived", "left", "needs",
        "need", "has", "had", "will", "would", "can", "could", "should",
        "did", "does", "do", "laughed", "smiled", "knows", "know", "thinks",
        "think", "drives", "drove", "bought", "sold", "made", "makes",
        "took", "gave"));

    /**
     * Words before "Colin" that prove the punctuation word instead:
     * determiners and punctuation verbs. These beat a following person
     * verb ("the Colin was inflamed" is anatomy, not a man).
     */
    static final Set<String> COLIN_COLON_PREV = new HashSet<>(Arrays.asList(
        "a", "an", "the", "this", "that", "use", "using", "insert",
        "put", "type", "typed", "typing", "add", "after", "before", "with"));

    void applyColinGate(List<String> tokens, boolean[] shield) {
        int n = tokens.size();
        String[] cores = new String[n];
        String[] forms = new String[n];
        for (int i = 0; i < n; i++) {
            String c = coreOf(tokens.get(i));
            cores[i] = c == null ? "" : c.toLowerCase(Locale.US);
            forms[i] = formOf(tokens.get(i));
        }
        for (int i = 0; i < n; i++) {
            if (!cores[i].equals("colin")) continue;
            if (shield != null && shield[i]) continue; // a learned Colin is a person
            if (forms[i].equals("colin's")) continue;  // possessive: a person owns it
            String prev = prevCore(cores, i);
            String next = nextCore(cores, i);
            if ("semi".equals(prev)) {
                // Merge into the "semi" token and blank this one, so
                // the glyph layer sees a single "semicolon" token.
                int pi = i - 1;
                while (pi >= 0 && cores[pi].isEmpty()) pi--;
                if (pi >= 0) {
                    setCore(tokens, pi, "semicolon");
                    cores[pi] = "semicolon";
                    tokens.set(i, "");
                    cores[i] = "";
                    continue;
                }
            }
            if (prev != null && COLIN_COLON_PREV.contains(prev)) {
                setCore(tokens, i, "colon");
                cores[i] = "colon";
                continue;
            }
            if (prev != null && COLIN_PERSON_PREV.contains(prev)) continue;
            if ("and".equals(next)) {
                // "Colin and Marisol ..." pairs two people: a
                // capitalized name after "and" vetoes. ("Colin and
                // semicolon ..." has no capital and converts.)
                int j = i + 1;
                while (j < n && cores[j].isEmpty()) j++;
                if (j + 1 < n) {
                    String after = coreOf(tokens.get(j + 1));
                    if (after != null && !after.isEmpty()
                            && Character.isUpperCase(after.charAt(0))) continue;
                }
            }
            if (next != null && COLIN_PERSON_NEXT.contains(next)) continue;
            setCore(tokens, i, "colon");
            cores[i] = "colon";
        }
    }

    // ------------------------------------------------------------------
    // Layer: spoken identifier shapes (v3.2, voice only)
    // ------------------------------------------------------------------
    //
    // "snake case order total" -> order_total; "camel case user id"
    // -> userId. The spoken phrase names the transform and is
    // consumed; the run of plain words after it (2-4, stopping at a
    // function word) becomes the identifier. A transform verb must
    // sit in the +-4 window ("use snake case ...", "call it camel
    // case ..."), so a display case for an actual snake at the zoo
    // never fires it. "at" is deliberately NOT a stop word: the
    // canonical dictation in this family is "created at" ->
    // created_at.

    static final Set<String> CASE_SIGNALS = new HashSet<>(Arrays.asList(
        "use", "using", "make", "write", "written", "call", "called",
        "named", "name", "in", "as", "convert"));
    static final Set<String> CASE_STOP = new HashSet<>(Arrays.asList(
        "and", "the", "for", "in", "on", "with", "to", "a", "an", "is",
        "are", "here", "please", "now", "then", "next", "after",
        "before", "of", "or", "but", "so"));

    void applySpokenCase(List<String> tokens, boolean[] shield) {
        int n = tokens.size();
        String[] cores = new String[n];
        for (int i = 0; i < n; i++) {
            String c = coreOf(tokens.get(i));
            cores[i] = c == null ? "" : c.toLowerCase(Locale.US);
        }
        for (int i = 0; i + 1 < n; i++) {
            boolean snake = cores[i].equals("snake");
            boolean camel = cores[i].equals("camel");
            if ((!snake && !camel) || !cores[i + 1].equals("case")) continue;
            if (shield != null && (shield[i] || shield[i + 1])) continue;
            if (!windowHas(cores, i, CASE_SIGNALS)) continue;
            List<String> run = new ArrayList<>();
            int j = i + 2;
            while (j < n && run.size() < 4) {
                String c = cores[j];
                if (c.isEmpty() || !c.matches("[a-z]+") || CASE_STOP.contains(c)) break;
                if (shield != null && shield[j]) break;
                run.add(c);
                j++;
            }
            if (run.size() < 2) continue;
            StringBuilder sb = new StringBuilder();
            for (int k = 0; k < run.size(); k++) {
                if (snake) {
                    if (k > 0) sb.append('_');
                    sb.append(run.get(k));
                } else if (k == 0) {
                    sb.append(run.get(k));
                } else {
                    sb.append(Character.toUpperCase(run.get(k).charAt(0)));
                    sb.append(run.get(k).substring(1));
                }
            }
            tokens.set(i, sb.toString());
            for (int k = i + 1; k < j; k++) tokens.set(k, "");
            i = j - 1;
        }
    }

    // ------------------------------------------------------------------
    // Layer: symbol names -> glyphs (v2, voice only, ruling A)
    // ------------------------------------------------------------------
    //
    // Dictated symbol names become the glyph by default: a false glyph is
    // the cheaper accident than a missed glyph. Plurals never convert.
    // Backslash, "(" and "@" glue to what follows; ")" and "@" glue to
    // what precedes; adjacent asterisks merge ("asterisk asterisk" -> **,
    // the markdown-bold dictation case). "period"/"colon" are deliberately
    // NOT here (content words in his book / health dictation).

    static final Map<String, String> GLYPHS = new HashMap<>();
    static {
        GLYPHS.put("asterisk", "*");
        GLYPHS.put("backslash", "\\");
        GLYPHS.put("tilde", "~");
        GLYPHS.put("tildy", "~");   // the live mis-hearing of "tilde"
        GLYPHS.put("slash", "/");
        GLYPHS.put("underscore", "_");
        GLYPHS.put("semicolon", ";");
        GLYPHS.put("apostrophe", "'");
        GLYPHS.put("ampersand", "&");
        GLYPHS.put("caret", "^");
        // v3.2 (Add-on B inventory): names with no living prose sense.
        // "comma" is the judgment call: as a dictated word it is the
        // punctuation command far more often than a grammar lesson,
        // and the recognizer's native "," covers the rest.
        GLYPHS.put("backtick", "`");
        GLYPHS.put("hyphen", "-");
        GLYPHS.put("comma", ",");
    }

    static final Map<String, String> GLYPH_PHRASES = new HashMap<>();
    static {
        GLYPH_PHRASES.put("at sign", "@");
        GLYPH_PHRASES.put("percent sign", "%");
        GLYPH_PHRASES.put("dollar sign", "$");
        GLYPH_PHRASES.put("exclamation point", "!");
        GLYPH_PHRASES.put("exclamation mark", "!");
        GLYPH_PHRASES.put("question mark", "?");
        GLYPH_PHRASES.put("open paren", "(");
        GLYPH_PHRASES.put("left paren", "(");
        GLYPH_PHRASES.put("close paren", ")");
        GLYPH_PHRASES.put("right paren", ")");
        GLYPH_PHRASES.put("quotation mark", "\"");
        GLYPH_PHRASES.put("plus sign", "+");
        GLYPH_PHRASES.put("equals sign", "=");
        // v3.2 (Add-on B): unambiguous symbol-name phrasings.
        GLYPH_PHRASES.put("triple backtick", "```");
        GLYPH_PHRASES.put("pound sign", "#");
        GLYPH_PHRASES.put("number sign", "#");
        GLYPH_PHRASES.put("vertical bar", "|");
        GLYPH_PHRASES.put("minus sign", "-");
        GLYPH_PHRASES.put("open bracket", "[");
        GLYPH_PHRASES.put("close bracket", "]");
        GLYPH_PHRASES.put("open brace", "{");
        GLYPH_PHRASES.put("close brace", "}");
        GLYPH_PHRASES.put("open quote", "\"");
        GLYPH_PHRASES.put("close quote", "\"");
        GLYPH_PHRASES.put("single quote", "'");
        GLYPH_PHRASES.put("double quote", "\"");
        GLYPH_PHRASES.put("forward slash", "/");
        GLYPH_PHRASES.put("grave accent", "`");
        GLYPH_PHRASES.put("em dash", "\u2014");
        GLYPH_PHRASES.put("en dash", "\u2013");
    }

    /** v3.2: three-token symbol phrases. */
    static final Map<String, String> GLYPH_PHRASES3 = new HashMap<>();
    static {
        GLYPH_PHRASES3.put("open curly brace", "{");
        GLYPH_PHRASES3.put("close curly brace", "}");
        GLYPH_PHRASES3.put("open square bracket", "[");
        GLYPH_PHRASES3.put("close square bracket", "]");
        GLYPH_PHRASES3.put("open curly bracket", "{");
        GLYPH_PHRASES3.put("close curly bracket", "}");
        GLYPH_PHRASES3.put("less than sign", "<");
        GLYPH_PHRASES3.put("greater than sign", ">");
        GLYPH_PHRASES3.put("dot dot dot", "...");
    }

    /**
     * v3.2: cluster-gated singles. These names live double lives in
     * prose (hash browns, the star of the show, the pipe under the
     * sink), so they convert ONLY with a symbol name adjacent -- a
     * dictated symbol cluster. Alone in prose they stand.
     */
    static final Map<String, String> GLYPH_CLUSTER = new HashMap<>();
    static {
        GLYPH_CLUSTER.put("hash", "#");
        GLYPH_CLUSTER.put("star", "*");
        GLYPH_CLUSTER.put("pipe", "|");
    }

    /**
     * v3.2: cluster-gated phrases. "less than" is everyday math and
     * price prose ("less than six dollars a pound"), so it converts
     * only next to a symbol name or a variable letter (x/y/z/n/i/j/k).
     * Digit adjacency was rejected on purpose: his price speech is
     * full of digits after "than".
     */
    static final Map<String, String> GLYPH_CLUSTER_PHRASES = new HashMap<>();
    static {
        GLYPH_CLUSTER_PHRASES.put("less than", "<");
        GLYPH_CLUSTER_PHRASES.put("greater than", ">");
    }

    static final Set<String> SYMBOL_NAMES = new HashSet<>(Arrays.asList(
        "asterisk", "backslash", "tilde", "tildy", "slash", "underscore",
        "semicolon", "apostrophe", "ampersand", "caret", "backtick",
        "comma", "hyphen", "hash", "star", "pipe", "less", "greater",
        "than", "sign", "paren", "bracket", "brace", "curly", "square",
        "quote", "mark", "point", "dot", "equals", "plus", "minus",
        "bar", "accent", "dash", "vertical", "open", "close", "left",
        "right", "single", "double", "triple", "forward", "grave"));

    static final Set<String> VAR_LETTERS = new HashSet<>(Arrays.asList(
        "x", "y", "z", "n", "i", "j", "k"));

    /** Function words that veto a member-access dot join. */
    static final Set<String> DOT_STOP = new HashSet<>(Arrays.asList(
        "the", "a", "an", "of", "on", "in", "is", "was", "are", "to",
        "and", "or", "at", "by", "for", "with", "from", "it", "this",
        "that"));

    private static boolean gluesForward(String g) {
        return g.equals("\\") || g.equals("(") || g.equals("@")
            || g.equals("[") || g.equals("{");
    }

    private static boolean gluesBack(String g) {
        return g.equals(")") || g.equals("@") || g.equals("]")
            || g.equals("}");
    }

    private boolean shieldedWord(String coreLower, long now) {
        return learningOn && isLearnedWord(coreLower, now);
    }

    /** A symbol cluster sits next to the span [from, to]? */
    private static boolean clusterOk(String[] cores, int from, int to,
                                     boolean phrase) {
        int[] nb = {from - 1, to + 1};
        for (int j : nb) {
            if (j < 0 || j >= cores.length || cores[j].isEmpty()) continue;
            if (SYMBOL_NAMES.contains(cores[j])) return true;
            if (phrase && VAR_LETTERS.contains(cores[j])) return true;
        }
        return false;
    }

    /** Member-access dot: both neighbors identifier-shaped, no function words. */
    private static boolean dotJoinOk(String[] cores, boolean[] consumed, int i) {
        int p = i - 1;
        while (p >= 0 && (consumed[p] || cores[p].isEmpty())) p--;
        int q = i + 1;
        while (q < cores.length && (consumed[q] || cores[q].isEmpty())) q++;
        if (p < 0 || q >= cores.length) return false;
        if (DOT_STOP.contains(cores[p]) || DOT_STOP.contains(cores[q])) return false;
        if (!cores[p].matches("[a-z][a-z0-9]*")) return false;
        if (!cores[q].matches("[a-z][a-z0-9]*")) return false;
        return !SYMBOL_NAMES.contains(cores[q]);
    }

    void applyGlyphs(List<String> tokens) {
        int n = tokens.size();
        long now = nowMs();
        String[] cores = new String[n];
        for (int i = 0; i < n; i++) {
            String c = coreOf(tokens.get(i));
            cores[i] = c == null ? "" : c.toLowerCase(Locale.US);
        }
        // Path heuristic: a line with 2+ spoken slashes is a path --
        // its slashes glue on both sides ("/home/user/bin") -- while a
        // single slash keeps the v2 spaced form ("a / between").
        int slashCount = 0;
        for (int i = 0; i < n; i++) {
            if (cores[i].equals("slash")
                    && (i == 0 || !cores[i - 1].equals("forward"))) slashCount++;
        }
        boolean pathMode = slashCount >= 2;

        String[] rep = new String[n];
        boolean[] isGlyph = new boolean[n];
        boolean[] consumed = new boolean[n];
        boolean[] glueAfter = new boolean[n];
        boolean[] glueBack = new boolean[n];
        for (int i = 0; i < n; i++) {
            if (consumed[i]) continue;
            rep[i] = tokens.get(i);
            if (cores[i].isEmpty()) continue;
            String cl = cores[i];
            // v3 shield: a tier-2 learned word is a word in the speaker's mouth,
            // not a symbol name -- ruling A yields to proven usage.
            if (shieldedWord(cl, now)) continue;
            // 3-token phrases first (open curly brace, dot dot dot).
            if (i + 2 < n && !consumed[i + 1] && !consumed[i + 2]
                    && !shieldedWord(cores[i + 1], now)
                    && !shieldedWord(cores[i + 2], now)) {
                String g3 = GLYPH_PHRASES3.get(
                    cl + " " + cores[i + 1] + " " + cores[i + 2]);
                if (g3 != null) {
                    String[] a = splitAffixes(tokens.get(i));
                    String[] b = splitAffixes(tokens.get(i + 2));
                    rep[i] = a[0] + g3 + b[2];
                    isGlyph[i] = true;
                    glueAfter[i] = gluesForward(g3);
                    glueBack[i] = gluesBack(g3);
                    consumed[i + 1] = true; rep[i + 1] = "";
                    consumed[i + 2] = true; rep[i + 2] = "";
                    continue;
                }
            }
            // 2-token phrases (incl. cluster-gated less/greater than).
            if (i + 1 < n && !consumed[i + 1]
                    && !shieldedWord(cores[i + 1], now)) {
                String key = cl + " " + cores[i + 1];
                String g = GLYPH_PHRASES.get(key);
                boolean clusterPhrase = false;
                if (g == null) {
                    g = GLYPH_CLUSTER_PHRASES.get(key);
                    clusterPhrase = g != null;
                }
                if (g != null && (!clusterPhrase
                        || clusterOk(cores, i, i + 1, true))) {
                    String[] a = splitAffixes(tokens.get(i));
                    String[] b = splitAffixes(tokens.get(i + 1));
                    rep[i] = a[0] + g + b[2];
                    isGlyph[i] = true;
                    glueAfter[i] = gluesForward(g) || key.equals("open quote");
                    glueBack[i] = gluesBack(g) || key.equals("close quote");
                    consumed[i + 1] = true;
                    rep[i + 1] = "";
                    // Optional chaining: "question mark dot" -> "?."
                    if (g.equals("?") && i + 2 < n && !consumed[i + 2]
                            && cores[i + 2].equals("dot")) {
                        rep[i] = rep[i] + ".";
                        consumed[i + 2] = true;
                        rep[i + 2] = "";
                    }
                    continue;
                }
            }
            // Repeated "equals" / "plus" merge (===, ++). A single one
            // is math prose ("one plus one equals two") and stands.
            if ((cl.equals("equals") || cl.equals("plus")) && i + 1 < n
                    && cores[i + 1].equals(cl) && !consumed[i + 1]) {
                int run = 1;
                while (i + run < n && cores[i + run].equals(cl)
                        && !consumed[i + run]) run++;
                StringBuilder sb = new StringBuilder();
                for (int k = 0; k < run; k++) sb.append(cl.equals("equals") ? "=" : "+");
                String[] a = splitAffixes(tokens.get(i));
                String[] b = splitAffixes(tokens.get(i + run - 1));
                rep[i] = a[0] + sb.toString() + b[2];
                isGlyph[i] = true;
                for (int k = 1; k < run; k++) {
                    consumed[i + k] = true;
                    rep[i + k] = "";
                }
                continue;
            }
            // Cluster-gated singles (hash / star / pipe).
            String cg = GLYPH_CLUSTER.get(cl);
            if (cg != null && clusterOk(cores, i, i, false)) {
                String[] a = splitAffixes(tokens.get(i));
                rep[i] = a[0] + cg + a[2];
                isGlyph[i] = true;
                continue;
            }
            // Member-access dot ("console dot log" -> console.log):
            // glues both sides when both neighbors are identifiers.
            if (cl.equals("dot") && dotJoinOk(cores, consumed, i)) {
                rep[i] = ".";
                isGlyph[i] = true;
                glueAfter[i] = true;
                glueBack[i] = true;
                continue;
            }
            // Plain singles.
            String g = GLYPHS.get(cl);
            if (g != null) {
                String[] a = splitAffixes(tokens.get(i));
                rep[i] = a[0] + g + a[2];
                isGlyph[i] = true;
                glueAfter[i] = gluesForward(g) || (pathMode && g.equals("/"));
                glueBack[i] = gluesBack(g) || (pathMode && g.equals("/"));
            }
        }
        List<String> out = new ArrayList<>();
        boolean glueNext = false;
        boolean lastGlyph = false;
        boolean prevFollowedSlash = false;
        for (int i = 0; i < n; i++) {
            if (consumed[i]) continue;
            String r = rep[i];
            boolean slashRep = isGlyph[i] && r.equals("/");
            boolean wasGlueNext = glueNext;
            if (out.isEmpty()) {
                out.add(r);
            } else if (glueNext) {
                out.set(out.size() - 1, out.get(out.size() - 1) + r);
            } else if (isGlyph[i] && glueBack[i]
                    && !(slashRep && !prevFollowedSlash)) {
                // In path mode the FIRST slash of a run keeps its
                // leading space ("is /home/..."); later slashes glue
                // back onto the segment before them.
                out.set(out.size() - 1, out.get(out.size() - 1) + r);
            } else if (isGlyph[i] && lastGlyph && !r.isEmpty()
                    && (r.charAt(0) == '*' || r.charAt(0) == '#'
                        || r.charAt(0) == '`')
                    && out.get(out.size() - 1).endsWith(
                        String.valueOf(r.charAt(0)))) {
                out.set(out.size() - 1, out.get(out.size() - 1) + r);
            } else {
                out.add(r);
            }
            glueNext = glueAfter[i];
            lastGlyph = isGlyph[i];
            if (slashRep) prevFollowedSlash = true;
            else if (!wasGlueNext) prevFollowedSlash = false;
        }
        tokens.clear();
        tokens.addAll(out);
    }

    // ------------------------------------------------------------------
    // Token helpers
    // ------------------------------------------------------------------

    /** Split a token into [leading punct, core, trailing punct]. */
    static String[] splitAffixes(String token) {
        int a = 0, b = token.length();
        while (a < b && !Character.isLetterOrDigit(token.charAt(a)) && token.charAt(a) != '@') a++;
        while (b > a && !Character.isLetterOrDigit(token.charAt(b - 1))) b--;
        return new String[]{token.substring(0, a), token.substring(a, b), token.substring(b)};
    }

    static String coreOf(String token) {
        if (token == null) return null;
        return splitAffixes(token)[1];
    }

    private static boolean hasDigit(String s) {
        for (int i = 0; i < s.length(); i++) if (Character.isDigit(s.charAt(i))) return true;
        return false;
    }

    static String joinTokens(List<String> tokens) {
        StringBuilder sb = new StringBuilder();
        for (String tok : tokens) {
            if (tok.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(tok);
        }
        return sb.toString();
    }

    static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev; prev = cur; cur = tmp;
        }
        return prev[b.length()];
    }
}
