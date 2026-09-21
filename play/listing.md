# Google Play listing

All that the Play Console asks for, ready to paste. Graphics are in this folder.

| Field | Value |
|---|---|
| App name (30) | `SubRead: audiobook read-along` |
| Package | `space.subread.app` |
| Default language | English (United States) |
| App or game | App |
| Category | Books & Reference |
| Free or paid | Free |
| Contact email | the address in `SUBPLZ_WEB_CONTACT_EMAIL` |
| Website | https://subread.space |
| Privacy policy | https://subread.space/terms.html |
| Upload | `app/build/outputs/bundle/release/app-release.aab` (`./gradlew :app:bundleRelease`, signed with the release key; turn on Play App Signing and keep this key as the upload key) |

## Short description (80)

```
Line an audiobook up with its ebook: subtitles and video, made on your device.
```

## Full description (4000)

```
SubRead lines an audiobook up with its ebook, sentence by sentence, and gives you subtitles with the time of each line. Read along while you listen: for language learners, and for anyone who wants text and voice together.

The words come from your book, not from a speech model. A small model only listens for where the narrator is; the text of each line is the book's own. Names, spelling and punctuation stay right.

WHAT IT MAKES
• Subtitles (.srt) - each line of the book with the time it is read. Made for Hoshi Reader's read-along, and they work in any player.
• Video + subtitles - an .mp4 of the book's cover over the audio, with the .srt beside it. It plays with subtitles in any video player, and you can upload it to YouTube. 720p and larger, 1 to 60 frames a second. The picture is still, so the video adds only about 60 MB to a 10-hour book.

WHAT IT TAKES
• Audio: m4b, m4a, mp3, opus, ogg, flac, wav
• Book: epub, plain text, Aozora Bunko zip
• Languages: Japanese, Spanish, Portuguese, Russian, Finnish, English, German, French, Italian, Chinese, Korean and more, or automatic detection

PRIVATE, AND YOURS
• All of the work is done on your device. Nothing is uploaded.
• No account, no ads, no tracking. The app asks for no permissions and does not use the network.
• Free, and open source (AGPL-3.0): https://github.com/equwal/subread-android

GOOD TO KNOW
• A long book takes hours: about a third of the length of the audio on a mid-range device. Keep the app open while it works. If it is interrupted, Continue starts from where it was.
• Pages that nobody reads aloud (front matter, notes) are left out by themselves.
• The app needs a 64-bit ARM processor from about 2018 or later (ARMv8.2).
• You need the audiobook and the ebook of the same text. A different edition or translation gives poor results; the app tells you how much of the book it found.

In a hurry, or on an old phone? The same conversion runs in a browser at https://subread.space
```

## Graphics

| Asset | File |
|---|---|
| Icon, 512 x 512 | `icon-512.png` |
| Feature graphic, 1024 x 500 | `feature-1024x500.png` |
| Phone screenshots (2 or more; 824 x 1648) | `screenshots/` |

The screenshots come from an e-ink tablet, so they show its floating navigation
button. Replace them with screenshots from a phone when one is at hand.

## App content (declarations)

| Question | Answer |
|---|---|
| Privacy policy | https://subread.space/terms.html |
| Ads | No ads |
| App access | All of the app works without a login |
| Content rating | Reference / utility. No violence, sexuality, language, drugs, gambling; no user-to-user communication; does not share location; no purchases |
| Target audience | 18 and over (simplest: no children's policy applies) |
| News app | No |
| Data safety | No data collected, no data shared. The app has no network permission |
| Government app, financial features, health | No |
| Advertising ID | Not used |

## Release path for a new personal developer account

Google wants a closed test before production: at least 12 testers, opted in for
14 days without a break. Then apply for production access in the Console.

1. Testing > Closed testing > create a track, upload the `.aab`, add release notes.
2. Add testers by email list (or a Google Group), and send them the opt-in link.
3. After 14 days with 12 or more testers: Dashboard > Apply for production.

Release notes for the first release:

```
First release. Subtitles (.srt) and video + subtitles from an audiobook and its ebook, made on the device.
```
