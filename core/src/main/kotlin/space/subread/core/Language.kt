package space.subread.core

/**
 * How text is reduced to something comparable before alignment.
 *
 * Whisper-tiny gets a great deal wrong - kana for kanji, は for わ, numerals
 * either way round - and the book and the transcript disagree on punctuation
 * and spacing everywhere. Alignment happens on a cleaned copy of both, and the
 * result is mapped back onto the original text afterwards.
 *
 * Ported from ats/lang.py. Behaviour has to match character for character, or
 * offsets computed here would not line up with the reference implementation.
 */
sealed class Language {
    protected open val translations: Map<Int, Int> = emptyMap()

    /** One-pass, one-to-one character substitution, then lowercased. */
    fun translate(s: String): String {
        if (translations.isEmpty()) return s.lowercase()
        val out = StringBuilder(s.length)
        s.codePoints().forEach { cp -> out.appendCodePoint(translations[cp] ?: cp) }
        return out.toString().lowercase()
    }

    open fun clean(s: String): String = translate(s)

    companion object {
        /** Only Japanese has rules of its own; everything else is case-folded. */
        fun of(code: String?): Language = if (code == "ja") Japanese else Plain
    }
}

object Plain : Language()

object Japanese : Language() {
    override val translations: Map<Int, Int> = buildMap {
        // Katakana to hiragana: the recogniser picks between them at random.
        for (i in 0 until 0x56) put(0x30A1 + i, 0x3041 + i)

        // Kanji numerals to digits. The digit string is shorter than the kanji
        // one and wraps, which is how 十 and 拾 both come out as １.
        val kansuu = "一二三四五六七八九十〇零壱弐参肆伍陸漆捌玖拾".toCodePoints()
        val arabic = "１２３４５６７８９１００".toCodePoints()
        kansuu.forEachIndexed { i, cp -> put(cp, arabic[i % arabic.size]) }

        // ASCII to full width, so "A" in one text meets "Ａ" in the other.
        for (cp in 0x21 until 0x7F) put(cp, cp + 0xFEE0)

        // Particles written one way and pronounced another.
        put('は'.code, 'わ'.code)
        put('あ'.code, 'わ'.code)
        put('お'.code, 'を'.code)
        put('へ'.code, 'え'.code)
    }

    // Everything that is not a letter or digit goes, except 。 when it leads a
    // run - sentence ends are worth keeping as alignment landmarks.
    private val noise = Regex("(?![。])[\\p{C}\\p{M}\\p{P}\\p{S}\\p{Z}\\sー々ゝ]+")

    // Collapse repeats to a single character: long vowels and stutters are
    // exactly where the recogniser and the book disagree on length.
    private val repeats = Regex("(.)(?=\\1+)")

    override fun clean(s: String): String =
        repeats.replace(noise.replace(translate(s), ""), "")
}
