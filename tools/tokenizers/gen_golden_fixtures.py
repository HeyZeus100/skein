#!/usr/bin/env python3
"""Generate golden tokenizer fixtures for bd skein-bpt (E5.I2).

Run from a venv with the pinned `tokenizers` version:

    python3 -m venv venv
    ./venv/bin/pip install 'tokenizers==0.22.1'
    ./venv/bin/python tools/tokenizers/gen_golden_fixtures.py \
        core/rag/src/test/resources/tokenizers \
        core/rag/src/test/resources/tokenizers/golden

The `tokenizer.json` artifacts are read from their checked-in gzipped form
(see that directory's MANIFEST.md); plain `.json` is accepted too.

Emits one JSON file per tokenizer:

    {"tokenizer": <name>, "tokenizers_version": "0.22.1",
     "offset_units": "utf16",
     "cases": [{"text":..., "ids":[...], "tokens":[...], "offsets":[[s,e],...]}],
     "truncation": [{"text":..., "max_length":n, "ids":[...], ...}]}

`offsets` are converted from Python's code-point indices to UTF-16 code-unit
indices so that a Kotlin/Java `String.substring(start, end)` reproduces the
token surface directly.
"""
import gzip
import json
import os
import random
import sys
import unicodedata

from tokenizers import Tokenizer

RES = sys.argv[1]
OUT = sys.argv[2]

TOKENIZERS = [
    ("nomic-embed-text-v1.5", "nomic-embed-text-v1.5.tokenizer.json"),
    ("ms-marco-MiniLM-L-6-v2", "ms-marco-MiniLM-L-6-v2.tokenizer.json"),
    ("gliner-small-v2.5-deberta-v3", "gliner-small-v2.5-deberta-v3.tokenizer.json"),
]


def load_tokenizer(path):
    """Accepts either `<name>.tokenizer.json` or its gzipped form."""
    if os.path.exists(path):
        return Tokenizer.from_file(path)
    with gzip.open(path + ".gz", "rt", encoding="utf-8") as fh:
        return Tokenizer.from_str(fh.read())


def utf16_map(text):
    """cp index -> utf-16 code-unit index (len(text)+1 entries)."""
    out = [0] * (len(text) + 1)
    n = 0
    for i, ch in enumerate(text):
        out[i] = n
        n += 2 if ord(ch) > 0xFFFF else 1
    out[len(text)] = n
    return out


def corpus():
    """300 deterministic strings across the bead's categories."""
    rnd = random.Random(20260921)
    out = []

    # --- 1. Edge cases / whitespace (12) -------------------------------------
    out += [
        "", " ", "   ", "\t", "\n", "\r\n", " \t\n ", " ", "a", "  a  ",
        "\u0000� bad", "​​zero width",
    ]

    # --- 2. Plain ASCII prose (60) -------------------------------------------
    words = ("the quick brown fox jumps over a lazy dog while several "
             "engineers argue about tokenization offsets and whether the "
             "vault should index every note twice").split()
    for i in range(60):
        n = 1 + (i % 17)
        out.append(" ".join(rnd.choice(words) for _ in range(n)))

    # --- 3. Accents & Latin diacritics, precomposed AND decomposed (45) ------
    accented = [
        "café", "naïve", "résumé", "Grüße", "señor", "Škoda", "ångström",
        "Ærø", "Łódź", "François", "Zoë", "piñata", "crème brûlée",
        "Ελλάδα", "Москва", "İstanbul",
    ]
    for w in accented:
        out.append(w)                                   # precomposed
        out.append(unicodedata.normalize("NFD", w))      # decomposed
    out += [
        "école", "Å ngström", "á́́ triple",
        "the café was naïve about résumé parsing",
        "Más allá del rı́o",
        "ẛ̣ sigma", "ﬁ ligature ﬂow", "½ ¼ ¾ fractions",
        "①②③ circled", "ｆｕｌｌｗｉｄｔｈ", "ＡＢＣ　ｄｅｆ",
    ]

    # --- 4. CJK + other scripts (45) -----------------------------------------
    cjk = [
        "你好世界", "今日はいい天気ですね", "안녕하세요 반갑습니다",
        "北京大学计算机科学技术系", "漢字とひらがなとカタカナ",
        "中文English混合text测试", "日本語のトークナイザ",
        "繁體中文與简体中文", "東京都渋谷区", "机器学习模型",
        "ひらがな", "カタカナ", "한글", "泰文ภาษาไทย", "العربية مرحبا",
        "עברית שלום", "हिन्दी नमस्ते", "ελληνικά", "русский язык",
        "𠀋 𡈽 𣇄 rare cjk ext-b",
    ]
    out += cjk
    for i in range(45 - len(cjk)):
        a, b = rnd.choice(cjk), rnd.choice(words)
        out.append(f"{a} {b} {a}")

    # --- 5. Emoji (30) --------------------------------------------------------
    emoji = ["😀", "🎉", "🇺🇸", "👨‍👩‍👧‍👦", "🧑🏽‍💻", "❤️", "🔥", "🚀", "✅", "🤖"]
    out += emoji
    for i in range(20):
        e = rnd.choice(emoji)
        w = rnd.choice(words)
        out.append(rnd.choice([f"{w} {e} {w}", f"{e}{e}{e}", f"{e} {w}", f"{w}{e}"]))

    # --- 6. Code (45) ---------------------------------------------------------
    code = [
        "fn main() { let x = 1; }",
        "val tokenizer = TokenizerFactory.fromJson(stream)",
        "SELECT * FROM notes WHERE id = ? AND deleted_at IS NULL;",
        "#include <stdio.h>\nint main(void) { return 0; }",
        "public static void main(String[] args) throws IOException {}",
        "const re = /^[a-z0-9_.-]+@[a-z0-9.-]+$/i;",
        "if (a && b || !c) { d += e * f; }",
        "x <- c(1, 2, 3); mean(x)",
        "git commit -m \"fix: off-by-one in offsets\"",
        "https://huggingface.co/nomic-ai/nomic-embed-text-v1.5",
        "path/to/file.kt:42:13: warning: unused",
        "{\"key\": [1, 2, {\"nested\": true}], \"n\": null}",
        "<div class=\"row\" id='x'>&amp;</div>",
        "0xDEADBEEF 0b1010 1_000_000 3.14e-9",
        "a=1;b=2;c=a+b # comment",
        "@Composable fun Foo() { Text(\"hi\") }",
        "def f(*args, **kwargs): return args[0] if args else None",
        "λ x -> x + 1",
        "SELECT\n  id,\n  body\nFROM docs\nORDER BY 1;",
        "shell$ ls -la | grep -v '^d' | wc -l",
    ]
    out += code
    for i in range(45 - len(code)):
        out.append(rnd.choice(code) + " " + rnd.choice(words))

    # --- 7. Very long words / long inputs (30) --------------------------------
    out += [
        "supercalifragilisticexpialidocious",
        "pneumonoultramicroscopicsilicovolcanoconiosis",
        "Donaudampfschiffahrtselektrizitaetenhauptbetriebswerkbauunterbeamtengesellschaft",
        "a" * 200,
        "ab" * 150,
        "x" * 512,
        "你" * 120,
        "😀" * 60,
        "word" * 80,
        "-" * 120,
    ]
    for i in range(20):
        out.append(" ".join(rnd.choice(words) for _ in range(rnd.randint(40, 120))))

    # --- 8. Punctuation-heavy / mixed (fill to 300) ---------------------------
    punct = [
        "Hello, world! How are you?!?", "...", "--- === ~~~",
        "«quoted» “smart” ‘quotes’", "1,000.00 USD — €9,99",
        "e.g. i.e. etc. vs. Dr. Smith", "C++ / C# / F# / Objective-C",
        "re:search re-search research", "#hashtag @mention $CASH %pct",
        "[[wikilink|alias]] and ^footnote", "a­b soft hyphen",
        "TAB\there", "MiXeD CaSe StRiNg", "ALLCAPS SHOUTING",
    ]
    out += punct
    while len(out) < 300:
        out.append(" ".join(rnd.choice(words + punct) for _ in range(rnd.randint(2, 12))))

    assert len(out) == 300, len(out)
    return out


TRUNCATION_MAXLEN = [8, 16, 32, 128]


def main():
    cases = corpus()
    long_cases = [c for c in cases if len(c) > 60][:10]

    for name, fname in TOKENIZERS:
        tok = load_tokenizer(f"{RES}/{fname}")
        entries = []
        for text in cases:
            e = tok.encode(text)
            m = utf16_map(text)
            entries.append({
                "text": text,
                "ids": e.ids,
                "tokens": e.tokens,
                "offsets": [[m[a], m[b]] for (a, b) in e.offsets],
            })

        trunc = []
        for maxlen in TRUNCATION_MAXLEN:
            tok.enable_truncation(max_length=maxlen)
            for text in long_cases:
                e = tok.encode(text)
                m = utf16_map(text)
                trunc.append({
                    "text": text,
                    "max_length": maxlen,
                    "ids": e.ids,
                    "tokens": e.tokens,
                    "offsets": [[m[a], m[b]] for (a, b) in e.offsets],
                })
        tok.no_truncation()

        payload = {
            "tokenizer": name,
            "tokenizers_version": __import__("tokenizers").__version__,
            "offset_units": "utf16",
            "cases": entries,
            "truncation": trunc,
        }
        path = f"{OUT}/{name}.golden.json"
        with open(path, "w", encoding="utf-8") as fh:
            json.dump(payload, fh, ensure_ascii=False, separators=(",", ":"), sort_keys=False)
            fh.write("\n")
        print(f"wrote {path}: {len(entries)} cases, {len(trunc)} truncation cases")


if __name__ == "__main__":
    main()
