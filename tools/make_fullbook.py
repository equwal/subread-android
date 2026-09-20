"""Whole-book input for the scale test: every cached chapter transcript of one
audiobook, end to end, plus the full text. No reference output - the reference
cannot align a whole book at once, which is rather the point.

Local only (golden-local/ is gitignored)."""
import argparse, ast, json, re, sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent))
from make_golden import aozora_paragraphs, chapter_files

ap = argparse.ArgumentParser()
ap.add_argument("--cache", type=Path, required=True)
ap.add_argument("--book", required=True)
ap.add_argument("--aozora", type=Path, required=True)
ap.add_argument("--out", type=Path, required=True)
a = ap.parse_args()

segments, offset, language = [], 0.0, None
for p in chapter_files(a.cache, a.book):
    d = ast.literal_eval(p.read_text(encoding="utf-8"))
    language = language or d["language"]
    for s in d["segments"]:
        segments.append({"text": s["text"], "start": s["start"] + offset, "end": s["end"] + offset})
    if d["segments"]:
        offset = segments[-1]["end"]
paragraphs = aozora_paragraphs(a.aozora)
a.out.write_text(json.dumps({"language": language, "transcript": segments, "paragraphs": paragraphs},
                            ensure_ascii=False), encoding="utf-8")
print(len(segments), "segments,", round(offset / 3600, 1), "hours;", len(paragraphs), "paragraphs,",
      sum(map(len, paragraphs)), "chars")
