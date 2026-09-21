"""Writes the app's small static resources. Run once; the output is committed."""
from pathlib import Path

RES = Path(__file__).resolve().parent.parent / "app/src/main/res"


def w(rel: str, text: str) -> None:
    p = RES / rel
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text, encoding="utf-8", newline="\n")


HEAD = '<?xml version="1.0" encoding="utf-8"?>\n'

w("xml/file_paths.xml", HEAD + """<paths>
    <files-path name="subtitles" path="subtitles/" />
    <files-path name="video" path="video/" />
</paths>
""")

w("values/strings.xml", HEAD + """<resources>
    <string name="app_name">SubRead</string>
    <string name="recents">Recent apps</string>
</resources>
""")

w("values/themes.xml", HEAD + """<resources>
    <!-- Compose draws everything; this only covers the moment before it does. -->
    <style name="Theme.SubRead" parent="android:Theme.Material.Light.NoActionBar">
        <item name="android:windowBackground">@android:color/white</item>
        <item name="android:statusBarColor">@android:color/white</item>
        <item name="android:windowLightStatusBar">true</item>
    </style>
</resources>
""")

w("values/colors.xml", HEAD + """<resources>
    <color name="ic_launcher_background">#FFFFFF</color>
</resources>
""")

# An open book with a sound wave across its pages, in one colour.
BOOK = ("M30,34 C38,30 46,30 53,34 L53,76 C46,72 38,72 30,76 Z "
        "M78,34 C70,30 62,30 55,34 L55,76 C62,72 70,72 78,76 Z")
WAVE = ("M35,50 h2 v10 h-2 Z M40,45 h2 v20 h-2 Z M45,52 h2 v6 h-2 Z "
        "M61,52 h2 v6 h-2 Z M66,45 h2 v20 h-2 Z M71,50 h2 v10 h-2 Z")


def vector(size: int, paths: list[tuple[str, str]]) -> str:
    body = "".join(f'    <path android:fillColor="{c}" android:pathData="{d}" />\n' for c, d in paths)
    return (HEAD + '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            f'    android:width="{size}dp" android:height="{size}dp"\n'
            '    android:viewportWidth="108" android:viewportHeight="108">\n' + body + "</vector>\n")


w("drawable/ic_launcher_foreground.xml", vector(108, [("#000000", BOOK), ("#FFFFFF", WAVE)]))
w("drawable/ic_notification.xml", vector(24, [("#FFFFFF", BOOK)]))

for name in ("ic_launcher", "ic_launcher_round"):
    w(f"mipmap-anydpi-v26/{name}.xml", HEAD + """<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
""")

print("resources written to", RES)
