"""Generate golden fixtures for the Kotlin aligner from the reference Python one.

The Android app ports subplz's alignment (ats.align + subplz.align.shift_align)
to Kotlin. This runs the *original* on real Whisper-tiny transcripts and records
every intermediate stage, so each ported function can be checked against the
real thing with identical inputs - not just the end result.

Needs the subplz environment (ats, Bio, rapidfuzz):

    SubPlz/venv311/Scripts/python tools/make_golden.py --cache <dir> --book <name> ...

Fixtures committed to the repo use public-domain text only (Aozora Bunko).
Anything else goes to golden-local/, which is gitignored; tests skip it when
it is absent.
"""

from __future__ import annotations

import argparse
import ast
import json
import re
import sys
import zipfile
from pathlib import Path

from ats import align as ats_align
from ats.lang import get_lang
from ats.main import to_subs
from Bio import Align
from subplz.align import shift_align
from subplz.cli import END_PUNC, START_PUNC

OTHER_PUNC = "＊　,，、…"
PREPEND = START_PUNC
APPEND = END_PUNC + OTHER_PUNC
NOPEND = (
    "うぁぃぅぇぉっゃゅょゎゕゖァィゥェォヵㇰヶㇱㇲッㇳㇴㇵㇶㇷㇷ゚ㇸㇹㇺャュョㇻㇼㇽㇾㇿヮ…　\x20"
)


def plain(x):
    """Deep copy as plain JSON types. align_sub leaves numpy ints in its lists."""
    return json.loads(json.dumps(x, default=int))


class Para:
    """Duck-types ats.main.Paragraph: to_subs only ever calls .text()."""

    def __init__(self, s: str):
        self._s = s

    def text(self) -> str:
        return self._s


def load_transcript(path: Path) -> dict:
    return ast.literal_eval(path.read_text(encoding="utf-8"))


def aozora_paragraphs(zip_path: Path) -> list[str]:
    """Body paragraphs of an Aozora Bunko ruby text, markup removed."""
    with zipfile.ZipFile(zip_path) as z:
        name = next(n for n in z.namelist() if n.lower().endswith(".txt"))
        raw = z.read(name).decode("shift_jis", errors="replace")
    lines = raw.replace("\r\n", "\n").split("\n")

    # Header: title block, then a legend fenced by two dashed rules.
    rules = [i for i, l in enumerate(lines) if l.startswith("-----")]
    body = lines[rules[1] + 1 :] if len(rules) >= 2 else lines
    # Footer: colophon.
    for i, l in enumerate(body):
        if l.startswith("底本："):
            body = body[:i]
            break

    out = []
    for l in body:
        l = re.sub(r"［＃[^］]*］", "", l)      # editorial annotations
        l = re.sub(r"《[^》]*》", "", l)        # ruby readings
        l = l.replace("｜", "").strip()         # ruby base marker
        if l:
            out.append(l)
    return out


def clean_len(lang, s: str) -> int:
    return len(lang.clean(s))


def run_case(name: str, language: str, segments: list[dict], paragraphs: list[str]) -> dict:
    lang = get_lang(language)  # same call do_batch makes: no punctuation args
    transcript = [s["text"] for s in segments]

    # Re-run the aligner by hand as well as through ats.align.align, to capture
    # the raw coordinates and the pre-heuristic segments.
    aligner = Align.PairwiseAligner(
        mode="global", match_score=1, open_gap_score=-0.8,
        mismatch_score=-0.6, extend_gap_score=-0.5,
    )
    t_clean = [lang.clean(i) for i in transcript]
    p_clean = [lang.clean(i) for i in paragraphs]
    best = aligner.align("".join(p_clean), "".join(t_clean))[0]
    coords = best.coordinates
    # align_sub writes into this array (it keeps a numpy view of a column and
    # assigns through it), so take the copy that gets recorded first.
    coords_in = [[int(v) for v in row] for row in coords]

    raw_segments = ats_align.align_sub(coords, p_clean, t_clean)
    after_sub = plain(raw_segments)
    ats_align.fix(lang, paragraphs, p_clean, raw_segments)
    after_fix = plain(raw_segments)
    ats_align.fix_punc(paragraphs, raw_segments, set(PREPEND), set(APPEND), set(NOPEND))
    after_punc = plain(raw_segments)

    # The public entry point must agree with the hand-run stages.
    alignment, _ = ats_align.align(
        None, lang, transcript, paragraphs, [], set(PREPEND), set(APPEND), set(NOPEND)
    )
    assert plain(alignment) == after_punc, "stage capture drifted"

    subs = to_subs([Para(p) for p in paragraphs], segments, alignment, 0, None)
    cues_raw = [{"text": s.text, "start": s.start, "end": s.end} for s in subs]
    shifted = shift_align(subs)
    cues = [{"text": s.text, "start": s.start, "end": s.end} for s in shifted]

    matched = sum(1 for c in cues_raw if not c["text"].startswith("＊"))
    print(f"{name}: {len(segments)} segs, {len(paragraphs)} paras, "
          f"{sum(map(len, t_clean))}x{sum(map(len, p_clean))} chars, "
          f"score {best.score:.1f}, {matched}/{len(cues_raw)} cues matched")

    return {
        "name": name,
        "language": language,
        "prepend": PREPEND, "append": APPEND, "nopend": NOPEND,
        "transcript": [{"text": s["text"], "start": s["start"], "end": s["end"]}
                       for s in segments],
        "paragraphs": paragraphs,
        "transcript_clean": t_clean,
        "paragraphs_clean": p_clean,
        "score": float(best.score),
        "coords": coords_in,
        "after_align_sub": after_sub,
        "after_fix": after_fix,
        "after_fix_punc": after_punc,
        "cues_raw": cues_raw,
        "cues": cues,
    }


def chapter_files(cache: Path, book: str) -> list[Path]:
    rx = re.compile(re.escape(book) + r"\.(\d+)\.tiny\.subs$")
    found = [(int(m.group(1)), p) for p in cache.iterdir() if (m := rx.search(p.name))]
    return [p for _, p in sorted(found)]


def main() -> None:
    sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser()
    ap.add_argument("--cache", type=Path, required=True, help="subplz transcript cache dir")
    ap.add_argument("--book", required=True, help="audio file name the cache entries start with")
    ap.add_argument("--aozora", type=Path, help="Aozora Bunko ruby zip with the matching text")
    ap.add_argument("--epub", type=Path, help="epub with the matching text")
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--prefix", required=True)
    ap.add_argument("--spans", default="0:3,3:6,20:24",
                    help="audio chapter ranges to turn into cases, e.g. 0:3,10:14")
    args = ap.parse_args()

    files = chapter_files(args.cache, args.book)
    if not files:
        sys.exit(f"no cache entries for {args.book!r} in {args.cache}")
    transcripts = [load_transcript(p) for p in files]
    language = transcripts[0]["language"]
    lang = get_lang(language)

    if args.aozora:
        paragraphs = aozora_paragraphs(args.aozora)
    else:
        from ats.main import Epub
        paragraphs = [p.text() for ch in Epub.from_file(str(args.epub)) for p in ch.text()]
        paragraphs = [p for p in paragraphs if p.strip()]

    # Where each audio chapter starts in the text, by cumulative cleaned length.
    # Approximate on purpose: a fixture whose text runs a little long or short
    # at either end is a more honest test than a perfectly trimmed one.
    para_cum = [0]
    for p in paragraphs:
        para_cum.append(para_cum[-1] + clean_len(lang, p))
    chap_cum = [0]
    for t in transcripts:
        chap_cum.append(chap_cum[-1] + sum(clean_len(lang, s["text"]) for s in t["segments"]))
    scale = para_cum[-1] / max(1, chap_cum[-1])

    def para_at(chars: float) -> int:
        target = chars * scale
        return next((i for i, c in enumerate(para_cum) if c >= target), len(paragraphs))

    args.out.mkdir(parents=True, exist_ok=True)
    for span in args.spans.split(","):
        a, b = (int(x) for x in span.split(":"))
        b = min(b, len(transcripts))
        segments, offset = [], 0.0
        for t in transcripts[a:b]:
            for s in t["segments"]:
                segments.append({"text": s["text"], "start": s["start"] + offset,
                                 "end": s["end"] + offset})
            offset = segments[-1]["end"] if segments else offset
        lo = max(0, para_at(chap_cum[a]) - 2)
        hi = min(len(paragraphs), para_at(chap_cum[b]) + 2)
        name = f"{args.prefix}_{a:03d}_{b:03d}"
        case = run_case(name, language, segments, paragraphs[lo:hi])
        (args.out / f"{name}.json").write_text(
            json.dumps(case, ensure_ascii=False, indent=0), encoding="utf-8")


if __name__ == "__main__":
    main()
