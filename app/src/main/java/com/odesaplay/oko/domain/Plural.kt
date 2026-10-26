package com.odesaplay.oko

/**
 * Index into a count phrase's forms: 0 = one (1, 21, 31…), 1 = few (2–4, 22–24), 2 = many
 * (0, 5–20, 11–14). EN has only two slots, so it never returns 2.
 *
 * One rule for every counted noun in the app: the word-form lists in [Strings.WordForms] and the
 * type labels in `TYPE_PLURALS` both index through it, so a phrase cannot decline on a rule of
 * its own.
 */
fun pluralIndex(count: Int, lang: AppLanguage): Int = when (lang) {
    AppLanguage.EN -> if (count == 1) 0 else 1
    AppLanguage.UA, AppLanguage.RU -> {
        val n10 = count % 10
        val n100 = count % 100
        when {
            n10 == 1 && n100 != 11 -> 0
            n10 in 2..4 && n100 !in 12..14 -> 1
            else -> 2
        }
    }
}
