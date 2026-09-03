package ua.ukrainedrones

/**
 * Official Ukrainian → Latin transliteration (КМУ Постанова №55). Proper nouns — city,
 * oblast, district names and similar — are transliterated, never semantically translated:
 * an EN reader always gets the romanized name (Київ → Kyiv, not a translation), and a wrong
 * "translation" (Золоте → "Gold", Біла Церква → "White Church") is worse than the romanization.
 */
object Transliteration {

    private const val LETTERS = "абвгґдеєжзиіїйклмнопрстуфхцчшщьюя"

    /** True when [c] opens a word (start, whitespace, hyphen, punctuation) — not apostrophe or ь. */
    private fun wordStart(c: Char): Boolean =
        c != '\'' && c != '\u02BC' && c.lowercaseChar() !in LETTERS

    private fun letter(c: Char, prev: Char): String = when (c) {
        'а' -> "a"; 'б' -> "b"; 'в' -> "v"; 'г' -> "h"; 'ґ' -> "g"
        'д' -> "d"; 'е' -> "e"; 'ж' -> "zh"; 'з' -> "z"; 'и' -> "y"
        'і' -> "i"; 'к' -> "k"; 'л' -> "l"; 'м' -> "m"
        'н' -> "n"; 'о' -> "o"; 'п' -> "p"; 'р' -> "r"; 'с' -> "s"
        'т' -> "t"; 'у' -> "u"; 'ф' -> "f"; 'х' -> "kh"; 'ц' -> "ts"
        'ч' -> "ch"; 'ш' -> "sh"; 'щ' -> "shch"; 'ь' -> ""
        // Digraph (Ye/Yi/Yu/Ya) only at the start of a word; single form elsewhere:
        // Гаєвич → Haievych, Костянтин → Kostiantyn, Юрій → Yurii, Київ → Kyiv.
        'є' -> if (wordStart(prev)) "ye" else "ie"
        'ї' -> if (wordStart(prev)) "yi" else "i"
        'й' -> if (wordStart(prev)) "y" else "i"
        'ю' -> if (wordStart(prev)) "yu" else "iu"
        'я' -> if (wordStart(prev)) "ya" else "ia"
        else -> ""
    }

    /** Transliterates every Ukrainian letter in [ua]; all other characters pass through. */
    fun transliterate(ua: String): String {
        val sb = StringBuilder(ua.length)
        var prev = ' '
        var i = 0
        while (i < ua.length) {
            val ch = ua[i]
            val lower = ch.lowercaseChar()
            if (lower == 'з' && i + 1 < ua.length && ua[i + 1].lowercaseChar() == 'г') {
                // "зг" renders as "zgh" (Згурський → Zghurskyi), to distinguish it from "ж".
                val upper = ch.isUpperCase()
                val nextUpper = ua[i + 1].isUpperCase()
                sb.append(
                    if (upper && nextUpper) "ZGH" else if (upper) "Zgh" else "zgh"
                )
                prev = ua[i + 1]
                i += 2
                continue
            }
            if (lower == '\'' || lower == '\u02BC') { // apostrophe not rendered
                prev = ch
                i++
                continue
            }
            if (lower !in LETTERS) { // space, punctuation, digits, "·" — pass through
                sb.append(ch)
                prev = ch
                i++
                continue
            }
            val out = letter(lower, prev)
            sb.append(if (ch.isUpperCase() && out.isNotEmpty()) out.replaceFirstChar { it.uppercase() } else out)
            prev = ch
            i++
        }
        return sb.toString()
    }
}