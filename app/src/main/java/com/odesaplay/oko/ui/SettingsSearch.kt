package com.odesaplay.oko

import com.odesaplay.oko.ThreatTypeCatalog
import com.odesaplay.oko.City

/** The Settings screens: a root list of sections, and a full detail page per section. */
sealed interface SettingsRoute {
    object Root : SettingsRoute
    data class Detail(val section: SettingsSection) : SettingsRoute
}

/** The Settings sections, in root-list order. Each one is its own detail page; MORE holds the
 *  standalone actions (relaunch, reset tips, guide, update, exit), the battery exemption and the
 *  footer. The disclaimer is not a section — it stays pinned on the root list. */
enum class SettingsSection { LOCATION, ALERTS, NIGHT, SHELTERS, THREATS, SYSTEM, FLOURISH, MORE }

/** A suggestion chip shown by the search box: a tappable hint that fills the query with a
 *  keyword that resolves to its setting. */
internal data class SearchChip(
    val labelUa: String,
    val labelEn: String,
    val queryUa: String,
    val queryEn: String
) {
    fun label(lang: AppLanguage): String = lang.pick(labelUa, labelEn, labelEn)
    fun query(lang: AppLanguage): String = lang.pick(queryUa, queryEn, queryEn)
}

/** A related concept: alternative words a user might type (synonyms, intent words, other
 *  languages) mapped to the suggestion chips that point at what they probably want. */
internal data class RelatedConcept(
    val words: List<String>,
    val chips: List<SearchChip>
)

/** Normalizes text for search matching: lowercase, drop apostrophes/quotes, dashes → spaces. */
internal fun String.searchNorm(): String = lowercase()
    .replace(Regex("[''´`]"), "")
    .replace(Regex("[-–—]"), " ")
    .replace(Regex("\\s+"), " ")
    .trim()

/** True when every query word is a substring of at least one keyword. */
internal fun matchesSearch(queryWords: List<String>, keywords: List<String>): Boolean =
    queryWords.all { qw -> keywords.any { qw in it } }

/** Classic Levenshtein edit distance, for the "did you mean" suggestions. */
internal fun levenshtein(a: String, b: String): Int {
    if (a == b) return 0
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length
    var prev = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        val cur = IntArray(b.length + 1) { j -> if (j == 0) i else 0 }
        for (j in 1..b.length) {
            cur[j] = minOf(
                prev[j] + 1,
                cur[j - 1] + 1,
                prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
            )
        }
        prev = cur
    }
    return prev[b.length]
}

internal fun searchChip(labelUa: String, labelEn: String, queryUa: String, queryEn: String) =
    SearchChip(labelUa, labelEn, queryUa.searchNorm(), queryEn.searchNorm())

internal fun chipList(vararg c: SearchChip) = c.toList()

/** The search database: curated direct keywords per section + pooled related concepts. */
internal data class SettingsSearchDb(
    val sectionDirect: Map<SettingsSection, List<String>>,
    val related: List<RelatedConcept>
)

/**
 * A single block inside a section. Search resolves to a section, but the thing the user typed
 * about is almost always one row on that page — so the tap also arms a flash on the block it
 * names, and the row pulses for a moment instead of leaving them to hunt for it.
 */
internal data class SearchAnchor(
    val id: String,
    val section: SettingsSection,
    val words: List<String>
)

private fun anchor(id: String, section: SettingsSection, vararg words: String) = SearchAnchor(
    id,
    section,
    words.map { it.searchNorm() }.filter { it.isNotBlank() }.distinct()
)

internal val searchAnchors: List<SearchAnchor> = listOf(
    anchor("language", SettingsSection.SYSTEM, "language", "мова", "язык", "translation", "переклад",
        "ukrainian", "english", "russian", "українськ", "англійськ", "русск", "мови"),
    anchor("cardSize", SettingsSection.SYSTEM, "card size", "card", "size", "scale", "розмір", "картка"),
    anchor("cityLabels", SettingsSection.SYSTEM, "city labels", "labels", "мітка", "підпис"),
    anchor("mapScale", SettingsSection.SYSTEM, "map scale", "scale bar", "шкала", "масштаб"),
    anchor("regionFill", SettingsSection.SYSTEM, "fill", "region", "oblast", "заливка", "область", "регіон"),
    anchor("borders", SettingsSection.SYSTEM, "border", "borders", "outline", "boundary", "кордон", "межа", "контур"),
    anchor("haptics", SettingsSection.SYSTEM, "haptic", "vibration when tapping", "вібрація", "тап"),
    anchor("threatIds", SettingsSection.SYSTEM, "threat id", "ids", "номер", "ід"),
    anchor("overlap", SettingsSection.SYSTEM, "overlap", "overlapping", "count", "перекриття", "рахунок"),
    anchor("iconZoom", SettingsSection.SYSTEM, "zoom", "icon zoom", "зум", "наближення"),
    anchor("iconSet", SettingsSection.THREATS, "icon", "icons", "icon set", "style", "іконка", "іконки", "набір", "стиль"),
    anchor("deathAnimStyle", SettingsSection.THREATS, "explosion", "explosion style", "animation", "classic", "simple", "advanced", "вибух", "анімація", "стиль"),
    anchor("threatToggles", SettingsSection.THREATS, "threat", "threats", "type", "types", "group", "загроз", "тип", "група"),
    anchor("shelters", SettingsSection.SHELTERS, "shelter", "shelters", "directory", "укриття", "сховище", "бомбосховище"),
    anchor("followMe", SettingsSection.LOCATION, "follow me", "gps", "location", "слід", "за мною", "локація", "місце"),
    anchor("pinCity", SettingsSection.LOCATION, "pin", "pinned city", "city", "пін", "місто"),
    anchor("night", SettingsSection.NIGHT, "night", "sleep", "silent", "ніч", "сон", "тиша"),
    anchor("officialAlerts", SettingsSection.ALERTS, "official", "official alerts", "siren", "оповіщення", "сирена", "офіційн"),
    anchor("battery", SettingsSection.MORE, "battery", "charge", "power", "energy", "батарея", "заряд", "енерг"),
    anchor("guide", SettingsSection.MORE, "guide", "tutorial", "how to", "путівник", "інструкція"),
    anchor("update", SettingsSection.MORE, "update", "version", "download", "оновлення", "версія"),
    anchor("exit", SettingsSection.MORE, "exit", "quit", "stop", "monitoring", "вихід", "вийти", "зупинити"),
    anchor("morale", SettingsSection.FLOURISH, "morale", "fun", "animation", "effects", "мораль", "розваг", "анімація")
)

/** The block a query names, or null when it only resolves to a section. */
internal fun anchorFor(queryWords: List<String>, anchors: List<SearchAnchor> = searchAnchors): SearchAnchor? =
    anchors.firstOrNull { matchesSearch(queryWords, it.words) }

/** Builds the settings search database. Direct keywords are curated (both languages), threat-type
 *  terms are auto-derived from the catalog, and related concepts map user intents to chips.
 *  Future languages extend the keyword/concept lists without changing the matching logic. */
internal fun buildSearchDb(pinnedCity: City?): SettingsSearchDb {
    fun kw(vararg words: String): List<String> =
        words.map { it.searchNorm() }.filter { it.isNotBlank() }.distinct()
    val threatTerms = ThreatTypeCatalog.INFO.values.flatMap { info ->
        val ua = info.labelUa.searchNorm()
        val en = info.labelEn.searchNorm()
        listOf(ua, en) + ua.split(" ") + en.split(" ")
    }.filter { it.length >= 2 }
    val cityTerms = pinnedCity?.let { listOf(it.nameUa.searchNorm(), it.nameEn.searchNorm()) }
        ?: emptyList()
    val sectionDirect = mapOf(
        SettingsSection.LOCATION to kw(
            "follow", "follow me", "location", "focus", "pin", "city", "gps", "calibrate",
            "periodic", "network", "fix", "refresh", "position",
            "локація", "фокус", "місто", "прив'язка", "пін", "слідувати", "за мною",
            "калібрування", "періодичн", "мереж", "фікс", "позиція"
        ) + cityTerms,
        SettingsSection.NIGHT to kw(
            "night", "night mode", "zone", "zones", "vibration", "vibrate", "sleep", "silent",
            "ніч", "нічний", "вночі", "зона", "зони", "вібрація"
        ),
        SettingsSection.ALERTS to kw(
            "alert", "alerts", "siren", "sirens", "sound", "official", "notification",
            "vibration", "vibrate", "volume", "chime", "boot", "reboot", "restart", "monitoring",
            "policy", "frequency", "often", "repeat", "repeats", "digest", "rate", "limit", "quiet",
            "оповіщення", "сповіщення", "сирена", "звук", "офіційн", "офіційна",
            "офіційні", "вібрація", "вібро", "гучність", "перезавантаження", "моніторинг",
            "частота", "часто", "повтор", "повтори", "тихо", "обмеження"
        ),
        SettingsSection.FLOURISH to kw(
            "morale", "brave", "bravery", "spirit", "heart", "fun", "animation", "bullet", "death", "flourish", "shoot", "tally", "neutralized",
            "calm", "icon", "icons", "icon set", "glory", "honor", "voice", "voices", "personality", "random", "babusia", "slang",
            "мораль", "хоробрість", "дух", "серце", "розваг", "анімація", "куля", "збиття", "загибель", "лічильник", "знешкоджен", "загроза",
            "заспокійлив", "іконка", "іконки", "набір іконок", "слава", "голос", "бабуся", "сленг", "випадково"
        ),
        SettingsSection.SHELTERS to kw(
            "shelter", "shelters", "directory", "укриття", "сховище", "бомбосховище", "каталог"
        ),
        SettingsSection.THREATS to (kw(
            "threat", "threats", "map", "icon", "icons", "fast", "slow", "type", "types", "group",
            "загроз", "загроза", "загрози", "мапа", "іконка", "іконки", "швидкі", "повільні",
            "швидк", "повільн", "тип", "типи", "група",
            "shahed", "шахед", "moped", "мопед", "drone", "дрон", "безпілотник", "бпла", "uav",
            "fpv", "фпв", "missile", "ракета", "cruise", "крилата", "ballistic", "балістика",
            "балістична", "kab", "каб", "bomb", "бомба", "aviation", "авіація", "mig", "міг",
            "літак", "recon", "reconnaissance", "розвідка", "розвідувальний", "unknown", "невідомий"
        ) + threatTerms),
        SettingsSection.SYSTEM to kw(
            "system", "display", "interface", "language", "ukrainian", "english", "russian",
            "мови", "язык", "русск", "переклад", "translation",
            "card", "cards", "size", "scale", "battery", "exempt",
            "overlap", "overlapping", "count", "zoom", "zoom icons",
            "перекриття", "перекриті", "зум", "наближення",
            "fill", "region", "regions", "oblast", "oblasts", "alert fill", "region fill",
            "border", "borders", "outline", "boundaries",
            "region", "raion", "district", "large", "big", "city labels",
            "система", "інтерфейс", "дисплей", "мова", "українськ", "англійськ",
            "картка", "картки", "розмір", "масштаб", "батарея",
            "заливка", "область", "області", "заливка областей", "заливка регіонів",
            "кордон", "кордони", "межа", "межі", "контури",
            "енерг", "звільнення"
        ),
        // The standalone actions and the battery exemption all live on the MORE page, so their
        // keywords resolve to that one row.
        SettingsSection.MORE to kw(
            "more", "about", "other", "actions", "relaunch", "replay", "wizard", "setup",
            "tips", "hints", "reset", "onboarding", "guide", "help", "features",
            "update", "check", "download", "version", "new", "exit", "stop", "monitoring", "quit",
            "disclaimer", "warning", "battery", "power", "заряд",
            "більше", "інше", "про", "перезапуск", "повторити", "початкове",
            "підказки", "скинути", "підказка", "путівник", "допомога", "функції",
            "оновлення", "перевір", "завантаж", "версія",
            "вийти", "зупинити", "моніторинг", "вихід",
            "застереження", "батарея", "автономність"
        )
    )
    val related = listOf(
        RelatedConcept(kw("quiet", "тихо", "mute", "беззвучний", "тиша", "silent"), chipList(
            searchChip("Сирена завжди", "Sirens always sound", "сирена", "sirens"),
            searchChip("Вібрація", "Vibration", "вібрація", "vibration")
        )),
        RelatedConcept(kw("ring", "дзвонити", "дзвінок", "alarm", "будильник", "гудок"), chipList(
            searchChip("Сирена завжди", "Sirens always sound", "сирена", "sirens")
        )),
        RelatedConcept(kw("air raid", "тривога", "сигнал", "emergency", "екстрений"), chipList(
            searchChip("Офіційні сповіщення", "Official alerts", "офіційні", "official")
        )),
        RelatedConcept(kw("bomb shelter", "бомбосховище", "сховище"), chipList(
            searchChip("Укриття", "Shelters", "укриття", "shelter")
        )),
        RelatedConcept(kw("dark", "темний", "темрява", "сон", "sleep", "тихіший", "вечір", "evening"), chipList(
            searchChip("Нічний режим", "Night mode", "ніч", "night")
        )),
        RelatedConcept(kw("danger", "небезпека", "небезпечний"), chipList(
            searchChip("Типи загроз", "Threat types", "загрози", "threats")
        )),
        RelatedConcept(kw("plane", "літак", "вертоліт", "helicopter", "aircraft", "гелікоптер"), chipList(
            searchChip("Авіація", "Aviation", "авіація", "aviation")
        )),
        RelatedConcept(kw("theme", "тема", "темна", "оформлення", "вигляд", "appearance"), chipList(
            searchChip("Стиль іконок", "Icon style", "іконки", "icon"),
            searchChip("Розмір картки", "Card size", "розмір", "size")
        )),
        RelatedConcept(kw("переклад", "translation", "translate", "мови"), chipList(
            searchChip("Мова", "Language", "мова", "language")
        )),
        RelatedConcept(kw("вибух", "explosion", "ефект", "effect"), chipList(
            searchChip("Анімація знищення", "Death animation", "анімація", "death animation")
        )),
        RelatedConcept(kw("заряд", "автономність", "charge", "power", "енергозбереження", "економія"), chipList(
            searchChip("Батарея", "Battery exemption", "батарея", "battery")
        )),
        RelatedConcept(kw("геолокація", "місцезнаходження", "трекінг", "tracking", "координати", "coordinates", "де я", "звідки"), chipList(
            searchChip("Слідувати за мною", "Follow me", "слідувати", "follow me"),
            searchChip("Прив'язати місто", "Pin city", "місто", "pin city")
        )),
        RelatedConcept(kw("інструкція", "як працює", "how to", "tutorial", "що нового"), chipList(
            searchChip("Путівник", "Feature guide", "путівник", "guide")
        )),
        RelatedConcept(kw("вимкнути", "disable", "turn off", "вимкнення"), chipList(
            searchChip("Зупинити й вийти", "Stop & exit", "вийти", "exit")
        )),
        RelatedConcept(kw("час", "time", "розклад", "schedule"), chipList(
            searchChip("Нічний режим", "Night mode", "ніч", "night")
        ))
    )
    return SettingsSearchDb(sectionDirect, related)
}
