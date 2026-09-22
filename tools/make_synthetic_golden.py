"""Generate golden fixtures for languages that have no recorded transcript.

make_golden.py needs a real Whisper transcript. This script makes one: it
takes a public-domain text, cuts it into segments the size of Whisper's, and
adds the kinds of error a small speech model makes (a dropped word, a wrong
word, lost punctuation, a hallucinated line). The reference implementation
then aligns that transcript, and every stage is recorded as make_golden.py
records it. The same fixture file is read by the Kotlin tests and by the
browser engine tests of subplz-web.

The transcript is deterministic: the seed is derived from the fixture name.

Needs the subplz environment, like make_golden.py:

    SubPlz/venv311/Scripts/python tools/make_synthetic_golden.py \
        --text dom_casmurro.txt --language pt --name pt_casmurro \
        --skip 40 --chars 5000 --out core/src/test/resources/golden \
        --out ../subplz-web/tests/engine/golden

The text is a Project Gutenberg plain-text file: the header and the licence
are cut at the START and END markers.
"""

from __future__ import annotations

import argparse
import json
import random
import re
import sys
import zlib
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from make_golden import run_case  # noqa: E402

# Marks that a speech model never writes.
_SILENT = "«»„“”‘’—–\"'()[]"
# Marks that a speech model writes only sometimes.
_SOMETIMES = ".,;:!?¿¡…"


def gutenberg_paragraphs(path: Path) -> list[str]:
    raw = path.read_text(encoding="utf-8-sig")
    lines = raw.replace("\r\n", "\n").split("\n")
    start = next((i for i, l in enumerate(lines) if l.startswith("*** START")), -1) + 1
    end = next((i for i, l in enumerate(lines) if l.startswith("*** END")), len(lines))
    out, current = [], []
    for line in lines[start:end]:
        if line.strip():
            current.append(line.strip())
        elif current:
            out.append(" ".join(current))
            current = []
    if current:
        out.append(" ".join(current))
    return out


def prose_only(paragraphs: list[str]) -> list[str]:
    """Drop headings and tables of contents: short lines in capitals, or without a full stop."""
    return [p for p in paragraphs if len(p) > 60 and not p.isupper()]


def garble(word: str, rng: random.Random, vocabulary: list[str]) -> str:
    kind = rng.random()
    letters = [c for c in word if c.isalpha()]
    if kind < 0.4 and len(letters) > 3:
        # One letter wrong, as in a mishearing.
        at = rng.randrange(len(word))
        return word[:at] + rng.choice("aeiou") + word[at + 1:]
    if kind < 0.7 and len(word) > 3:
        # A letter lost.
        at = rng.randrange(len(word))
        return word[:at] + word[at + 1:]
    # A different word of the same text.
    return rng.choice(vocabulary)


def transcribe(paragraphs: list[str], rng: random.Random) -> list[dict]:
    """Segments the size of Whisper's, with its errors, and times at a reading speed."""
    words = " ".join(paragraphs).split()
    vocabulary = [w.strip(_SILENT + _SOMETIMES) for w in words if w.strip(_SILENT + _SOMETIMES)]
    segments = []
    at = 0.0
    i = 0
    while i < len(words):
        want = rng.randint(25, 75)
        chunk = []
        length = 0
        while i < len(words) and (length < want or not chunk):
            w = words[i]
            i += 1
            length += len(w) + 1
            r = rng.random()
            if r < 0.03:
                continue                          # a word lost
            if r < 0.10:
                w = garble(w, rng, vocabulary)     # a word wrong
            w = "".join(c for c in w if c not in _SILENT)
            if w and w[-1] in _SOMETIMES and rng.random() < 0.5:
                w = w.rstrip(_SOMETIMES)
            w = w.lstrip("¿¡")
            if w:
                chunk.append(w)
        text = " ".join(chunk)
        if not text:
            continue
        seconds = len(text) / rng.uniform(12.0, 18.0)
        segments.append({"text": text, "start": round(at, 3), "end": round(at + seconds, 3)})
        at += seconds + rng.uniform(0.0, 0.6)
    # A hallucinated line at the start, as Whisper makes on silence.
    lead = {"text": rng.choice(vocabulary).capitalize() + ".", "start": 0.0, "end": 1.2}
    return [lead] + [{**s, "start": round(s["start"] + 1.5, 3), "end": round(s["end"] + 1.5, 3)}
                     for s in segments]


def main() -> None:
    sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser()
    ap.add_argument("--text", type=Path, required=True, help="Project Gutenberg plain text")
    ap.add_argument("--language", required=True, help="Whisper language code")
    ap.add_argument("--name", required=True, help="fixture name, also the random seed")
    ap.add_argument("--skip", type=int, default=0, help="prose paragraphs to skip first")
    ap.add_argument("--chars", type=int, default=5000, help="characters of text to keep")
    ap.add_argument("--out", type=Path, action="append", required=True, help="output directory (repeatable)")
    args = ap.parse_args()

    paragraphs = prose_only(gutenberg_paragraphs(args.text))[args.skip:]
    kept, total = [], 0
    for p in paragraphs:
        if total >= args.chars:
            break
        kept.append(p)
        total += len(p)
    if not kept:
        sys.exit("no paragraphs left after --skip")

    rng = random.Random(zlib.crc32(args.name.encode("utf-8")))
    segments = transcribe(kept, rng)
    case = run_case(args.name, args.language, segments, kept)
    case["synthetic"] = True
    for out in args.out:
        out.mkdir(parents=True, exist_ok=True)
        (out / f"{args.name}.json").write_text(
            json.dumps(case, ensure_ascii=False, indent=0), encoding="utf-8")
        print(f"wrote {out / (args.name + '.json')}")


if __name__ == "__main__":
    main()
