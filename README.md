# SubRead for Android

Times an audiobook against its ebook, entirely on the phone, and writes the
`.srt` that [Hoshi Reader](https://github.com/HuangAntimony/Hoshi-Reader-Android)'s
read-along uses. Nothing is uploaded.

It is the same method as [subread.space](https://subread.space) and
[SubPlz](https://github.com/kanjieater/SubPlz): a small speech model transcribes
the audio, roughly; the transcript is aligned against the book; the subtitles
take their *timing* from the transcript and their *words* from the book.

## Layout

| Path | What |
|---|---|
| `core/` | The alignment engine. Plain Kotlin, no Android - builds and tests with only a JDK |
| `app/` | The Android app: audio decoding, the whisper.cpp bridge, the long-running job, the UI |
| `third_party/whisper.cpp` | Pinned submodule |
| `tools/make_golden.py` | Generates test fixtures by running the reference Python implementation |

## The engine

`core/` is a port of SubPlz's aligner (`ats.align`, `subplz.align.shift_align`),
checked stage by stage against the original's real output on real Whisper-tiny
transcripts: every cue across the fixtures comes out identical.

What is new is `AnchoredAligner`. The reference hands Biopython one chapter at a
time and needs gigabytes to do it, so it has to guess first which chapter of the
book each chapter of audio is. This aligns the whole book at once instead, by
anchoring on stretches unique to both texts and solving exactly only between
anchors: a 19-hour audiobook against its full text in a few seconds and a few
megabytes. `BookAligner` then uses the alignment itself to leave out text nobody
narrated - front matter, notes - instead of matching chapters.

```bash
./gradlew :core:test
```

## Building the app

CI builds it (`.github/workflows/build.yml`): the APK is an artifact of every
push to `main`. Locally it needs the Android SDK, NDK 29 and CMake 3.31, and the
speech model at `app/src/main/assets/models/ggml-tiny-q8_0.bin` (the workflow has
the URL and checksum). Without an SDK, Gradle leaves `:app` out and `:core`
still builds.

The release key is not in the repository; CI reads it from Actions secrets.

## Requirements

Android 8+, a 64-bit ARM processor with ARMv8.2 half-precision and dot-product
instructions (anything from 2018 on). Transcription runs at a small multiple of
real time, so a long book takes hours; it carries on with the screen off and
resumes where it stopped if interrupted.
