# Make subtitles from another app

A reader or an audiobook player can ask SubRead to make the `.srt` for a book,
and get the file back. The other app needs no code of SubRead and no speech
model of its own. SubRead shows its usual screen with the progress, so the user
sees that a long job runs and can stop it.

## Ask

```kotlin
val ask = Intent("space.subread.app.action.ALIGN").apply {
    setPackage("space.subread.app")
    putExtra("space.subread.extra.AUDIO", audioUri)   // Uri: m4b, m4a, mp3, opus, ogg, flac, wav
    putExtra("space.subread.extra.BOOK", bookUri)     // Uri: epub, txt, Aozora zip
    putExtra("space.subread.extra.LANGUAGE", "ja")    // optional: a Whisper language code, or "auto" (the default)
    // SubRead must be able to read both files:
    clipData = ClipData.newRawUri("audio", audioUri).apply { addItem(ClipData.Item(bookUri)) }
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}
startActivityForResult(ask, REQUEST_SUBTITLES)        // or an ActivityResultLauncher
```

Both Uris must be `content://` Uris that the calling app may grant (its own
`FileProvider`, or a document Uri for which it holds a persisted permission).
SubRead refuses every other Uri, a `file://` Uri included: SubRead would read
its own private files for the caller.

One audio file for one book. A book in many audio files is not handled yet.

Declare that the app looks for SubRead (Android 11 and later hide other apps
without this), in the manifest of the calling app:

```xml
<queries>
    <package android:name="space.subread.app" />
</queries>
```

`ask.resolveActivity(packageManager) == null` means that SubRead is not
installed, or is older than 0.9.0. Offer the download then:
<https://github.com/equwal/subread-android/releases/latest> (also on F-Droid
and Google Play when those listings are up).

## Answer

`RESULT_OK`:

| Where | What |
|---|---|
| `data` | a `content://` Uri of the `.srt` (UTF-8), with `FLAG_GRANT_READ_URI_PERMISSION`. Copy the file; the grant ends with the calling activity |
| `space.subread.extra.CUES` | `Int`: the number of subtitle lines |
| `space.subread.extra.MATCH_RATE` | `Double`, 0 to 1: the share of lines whose words were found in the book. Under 0.8 usually means another edition, a translation, or the wrong language |
| `space.subread.extra.LANGUAGE` | `String`: the language that was used |

`RESULT_CANCELED`: the user went back, or the job failed.
`space.subread.extra.ERROR` (`String`) says why when it failed.

SubRead makes one set of subtitles at a time. An ask that arrives while a job
runs is refused with `RESULT_CANCELED` and an `ERROR`; the job that runs is not
touched. An ask with a file that is missing or is not a `content://` Uri is
refused the same way, before any work starts.

The job takes about a third of the length of the audio on a mid-range device.
SubRead keeps what it has transcribed, so asking again for the same audio file
continues and does not start over.

## The subtitles

SubRip, UTF-8, one cue for each phrase the narrator says, times on the clock of
the audio file. The words are the book's own. A line that starts with `＊` is
what the speech model heard where no text of the book could be matched.
