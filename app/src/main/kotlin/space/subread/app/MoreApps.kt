package space.subread.app

/** One link of the "More apps" section: a name, one line about it, and the page that a tap opens. */
data class MoreApp(val name: String, val line: String, val url: String)

/**
 * The other sites and apps of the same author, in the order of the catalog: the sites,
 * then the Android apps, then the list of all projects. SubRead for Android is not in it.
 */
val MORE_APPS = listOf(
    MoreApp("SubRead", "Read along with an audiobook.", "https://subread.space/"),
    MoreApp("Book Simulator", "A reading room for Aozora Bunko and Project Gutenberg books.", "https://booksimulator.com/"),
    MoreApp("honjimaku.com", "Subtitles for Japanese audiobooks.", "https://honjimaku.com/"),
    MoreApp("sbm Sync", "Your bookmarks, the same on every device.", "https://sbmsync.com/"),
    MoreApp("SubRead Overlay", "Subtitle lines over any Android media player.",
        "https://github.com/equwal/subread-overlay/releases/latest"),
    MoreApp("SubRead Dictionary", "Pop-up dictionary that reads Yomitan dictionaries.",
        "https://github.com/equwal/subread-dictionary/releases/latest"),
    MoreApp("SubRead Anki", "One tap makes an Anki card from any app.", "https://github.com/equwal/subread-anki"),
    MoreApp("Subrep", "Live captions of the sound of your phone.",
        "https://github.com/equwal/subrep-android/releases/latest"),
    MoreApp("sbm for Android", "Fuzzy search for your bookmarks.", "https://github.com/equwal/sbm-android/releases/latest"),
    MoreApp("Rebind", "Remap the hardware buttons of e-ink readers and Android.", "https://github.com/equwal/rebind/releases"),
    MoreApp("Ink Recents", "A recent-apps switcher for e-ink.", "https://github.com/equwal/ink-recents/releases/latest"),
    MoreApp("Ink Dim", "Frontlight below the lowest system level.", "https://github.com/equwal/ink-dim/releases/latest"),
    MoreApp("Ink Update", "Tells you when Rebind and its extensions update.",
        "https://github.com/equwal/ink-update/releases/latest"),
    MoreApp("All projects", "Everything, with source code.", "https://recentlywritten.com/projects.html"),
)
