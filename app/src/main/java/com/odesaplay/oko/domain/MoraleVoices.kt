package com.odesaplay.oko

import java.time.LocalDate
import kotlin.math.abs

data class MoraleVoicePack(
    val calmDesc: String,
    val flybyDesc: String,
    val deathDesc: String,
    val followBulletDesc: String,
    val hdExplosionDesc: String,
    val tallyDesc: String,
    val allUkraineDesc: String,
    val alarmEpisodeDesc: String,
    val note: String,
    val calm: List<String>
)

fun resolveMoraleVoice(selected: MoraleVoice, today: LocalDate = LocalDate.now()): MoraleVoice {
    if (selected != MoraleVoice.RANDOM) return selected
    val voices = MoraleVoice.values().filter { it != MoraleVoice.RANDOM }
    return voices[(abs(today.toEpochDay()) % voices.size).toInt()]
}

fun moraleVoicePack(lang: AppLanguage, voice: MoraleVoice): MoraleVoicePack {
    if (voice == MoraleVoice.PLAIN) {
        val s = Strings.get(lang)
        return MoraleVoicePack(
            calmDesc = s.calmMessagesDesc,
            flybyDesc = s.flybyAnimationDesc,
            deathDesc = s.deathAnimationDesc,
            followBulletDesc = s.followBulletDesc,
            hdExplosionDesc = s.hdExplosionDesc,
            tallyDesc = s.neutralizedTallyDesc,
            allUkraineDesc = s.neutralizedTallyAllUkraineDesc,
            alarmEpisodeDesc = s.alarmEpisodeTallyDesc,
            note = s.moraleNote,
            calm = s.calmMessages
        )
    }
    // RU reads the EN packs until a real RU translation lands (see [AppLanguage.pick]).
    return lang.pick(ukrainianPack(voice), englishPack(voice), englishPack(voice))
}

fun MoraleVoice.label(lang: AppLanguage): String = when (this) {
    MoraleVoice.RANDOM -> lang.pick("Випадково", "Random", "Random")
    MoraleVoice.PLAIN -> lang.pick("Просто", "Plain", "Plain")
    MoraleVoice.WARM -> lang.pick("Тепло", "Warm", "Warm")
    MoraleVoice.SPICY -> lang.pick("З перцем", "Spicy", "Spicy")
    MoraleVoice.SLANG -> lang.pick("Сленг", "Slang", "Slang")
    MoraleVoice.VIYSKO -> lang.pick("Військо", "Viysko", "Viysko")
    MoraleVoice.BABUSIA -> lang.pick("Бабуся", "Babusia", "Babusia")
}

fun moraleVoiceSectionTitle(lang: AppLanguage): String =
    lang.pick("Голос", "Voice", "Voice")

fun randomTodayLabel(lang: AppLanguage, voiceLabel: String): String =
    lang.pick("Випадково · сьогодні: $voiceLabel", "Random · today: $voiceLabel", "Random · today: $voiceLabel")

private fun englishPack(voice: MoraleVoice): MoraleVoicePack = when (voice) {
    MoraleVoice.WARM -> MoraleVoicePack(
        calmDesc = "A kind word when the sky is quiet.",
        flybyDesc = "A friendly jet passes by now and then, just to say hi.",
        deathDesc = "A small send-off for threats that leave the feed.",
        followBulletDesc = "The camera gently follows the send-off.",
        hdExplosionDesc = "Softer, prettier send-off effects. A touch heavier on the phone.",
        tallyDesc = "Keeps count of resolved threats in your oblast. Tap to replay.",
        allUkraineDesc = "Count the whole country too.",
        alarmEpisodeDesc = "What got resolved while your alarm was on.",
        note = "Morale features are decoration, not detection. \u201cNeutralized\u201d only means a threat left the feed — intercepted, crashed, or radar lost. We can\u2019t know which, so we honor our defenders, never celebrate harm. These never affect alerts or zones, and during an active air-raid alert they step aside entirely. Always trust official sirens.",
        calm = listOf(
            "Quiet — you\u2019re doing great",
            "Breathe easy",
            "Call someone you love",
            "This calm is hard-earned",
            "Rest while the sky rests",
            "Check on a neighbour",
            "A good evening for tea",
            "All quiet on your street",
            "Peace suits this city",
            "Take a slow walk",
            "Hold your people close",
            "Put the worries down a while",
            "Quiet nights matter too",
            "Recharge — you\u2019ve earned it",
            "Someone out there is watching over you",
            "Tomorrow can wait"
        )
    )
    MoraleVoice.SPICY -> MoraleVoicePack(
        calmDesc = "Wholesome words while it\u2019s quiet.",
        flybyDesc = "A MiG photobombs your map. No permission asked.",
        deathDesc = "Threat leaves the feed? Fireworks. That\u2019s the rule.",
        followBulletDesc = "Camera chases the drama to the very end.",
        hdExplosionDesc = "Maximum Hollywood per threat type. Phone may sweat.",
        tallyDesc = "Your oblast\u2019s kill count. Tap to watch the fireworks.",
        allUkraineDesc = "Whole-country kill count. Bigger smile.",
        alarmEpisodeDesc = "The afterparty report for your alarm.",
        note = "Morale is the glitter on top — not the radar. \u201cNeutralized\u201d only means a threat ghosted the feed: intercepted, crashed, or radar lost it in the couch cushions. We can\u2019t know which, so we cheer for our defenders, never for anyone\u2019s harm. Zero effect on alerts or zones, and the second a real alarm sounds the party freezes and sirens take the stage. Official sirens or bust.",
        calm = listOf(
            "Quiet — go touch grass",
            "Certified calm moment",
            "Sky: clear. You: thriving",
            "No threats, only vibes",
            "Breathe. Hydrate. Repeat",
            "Text your mum",
            "Tea hits different in peacetime",
            "All quiet — suspiciously pleasant",
            "Your oblast is chilling",
            "Main-character rest arc",
            "Nap like you mean it",
            "Touch grass: advanced edition",
            "Silence, but make it golden",
            "Charge your phone AND yourself",
            "The radar is bored. Good",
            "Plot twist: nothing happened"
        )
    )
    MoraleVoice.SLANG -> MoraleVoicePack(
        calmDesc = "Tyho. Just quiet — take a breather.",
        flybyDesc = "A MiG does a flyby. Krasyvo, that\u2019s it.",
        deathDesc = "Target leaves the feed? That\u2019s bavovna. Pure morale.",
        followBulletDesc = "Camera rides along to watch the bavovna land.",
        hdExplosionDesc = "Premium bavovna per threat type — shards, sparks, smoke. Phone may chug.",
        tallyDesc = "Counting the minusy in your oblast. Tap to replay.",
        allUkraineDesc = "Minusy across all of Ukraine. Bigger bavovna energy.",
        alarmEpisodeDesc = "The trilogy report: what got pryzemleno during your alarm.",
        note = "Morale is decoration, not detection. \u201cNeutralized\u201d only means a target left the feed — zbyto, crashed, or radar just lost it. We can\u2019t know which, so we cheer for nashi defenders, never for anyone\u2019s harm — that\u2019s the whole point of bavovna: celebrating our people, not the hit. Zero effect on alerts or zones; during a real air-raid alarm the show stops and sirens own the screen. Always trust official sirens.",
        calm = listOf(
            "Tyho — vydykhay",
            "Bavovna-free evening. Nice",
            "Sky is chysta. Relax",
            "No minusy needed today",
            "Chill — PPO has the shift",
            "Tyho means the lads are working",
            "Tea time, viysko-style: hot and sweet",
            "All tyho on your street",
            "Minus count: zero. Perfect",
            "Rest — zhyty treba",
            "Call svoikh",
            "Tyho is the best zvit",
            "Recharge like a powerbank",
            "Nothing pryzemleno, nothing to see",
            "Oblast is calm. Tak trymaty",
            "Slava the quiet. Rest up"
        )
    )
    MoraleVoice.VIYSKO -> MoraleVoicePack(
        calmDesc = "Status line while no contacts on scope.",
        flybyDesc = "Scheduled flyby. Map effect only.",
        deathDesc = "Contact lost from feed: rendered as interception. Display only.",
        followBulletDesc = "Camera tracks to point of last contact.",
        hdExplosionDesc = "Full visual detail per type. Performance cost: moderate.",
        tallyDesc = "Running count of contacts lost in your oblast. Tap for replay.",
        allUkraineDesc = "Extend count to all oblasts. Theatre-wide picture.",
        alarmEpisodeDesc = "Debrief of contacts lost during your alarm.",
        note = "Morale assets are display layer only — they do not feed detection, zones or alerts. \u201cNeutralized\u201d is a display label meaning a contact left the feed: intercepted, down, or lost by radar. Cause is unknown, so the tally honours our defenders, never anyone\u2019s harm. During an active air-raid alert all morale assets stand down and sirens have the screen. Official sirens are the order of the day.",
        calm = listOf(
            "Sector quiet. At ease",
            "No contacts on scope",
            "Stand easy, stay ready",
            "All posts report calm",
            "Shift quiet. Hydrate",
            "Perimeter holds",
            "Rest is also a task. Execute",
            "Comms check: all good",
            "Night passes without incident",
            "Morale: steady",
            "Check on your buddy",
            "Rations: tea. Status: hot",
            "Scope clean",
            "Hold position. Relax shoulders",
            "Silence is a successful operation",
            "Stand by for nothing. Enjoy"
        )
    )
    MoraleVoice.BABUSIA -> MoraleVoicePack(
        calmDesc = "A warm word from babusia while it\u2019s quiet.",
        flybyDesc = "A nice plane flies by, sonechko. Just to look at.",
        deathDesc = "The bad thing went away, khtyf — gone! Don\u2019t you worry.",
        followBulletDesc = "The camera watches it go, so you don\u2019t have to.",
        hdExplosionDesc = "Prettier sparks, kvitochko. Slightly heavier, like good borscht.",
        tallyDesc = "How many bad things our boys chased off in your oblast. Tap to see!",
        allUkraineDesc = "Count across the whole country — our boys everywhere, dyakuyu Bohu.",
        alarmEpisodeDesc = "What our defenders took care of while you sat safe, rybko.",
        note = "These little shows are just for your heart, sonechko — they don\u2019t change the alerts one bit. \u201cNeutralized\u201d only means the bad thing disappeared from the screen: our boys got it, it fell or the radar simply lost it. We can\u2019t know which, so we thank our defenders and never, ever cheer for anyone\u2019s hurt — a good heart doesn\u2019t work that way. And when a real alarm sounds, all the cartoons stop and the siren is the boss. Listen to official sirens, didusyu. Babusia loves you.",
        calm = listOf(
            "Tyho, sonechko. All good",
            "Did you eat? Eat something",
            "Put on warm socks",
            "Call your mother, vona chekaye",
            "Drink your tea before it cools",
            "Such a quiet evening, dyakuyu Bohu",
            "Rest your eyes, kvitochko",
            "Babusia is proud of you",
            "Sit, vidpochyn. Everything is fine",
            "Did you lock the door? Good. Sleep",
            "The boys are watching. Spokoyno",
            "Eat the kotletka. There are two",
            "Hug someone, sertsyu teplo bude",
            "Quiet night — yak zamovyla",
            "You\u2019re such a good child",
            "Sleep tight, rybko. Babusia\u2019s here"
        )
    )
    else -> throw IllegalArgumentException("PLAIN is built from Strings, RANDOM never resolves here")
}

private fun ukrainianPack(voice: MoraleVoice): MoraleVoicePack = when (voice) {
    MoraleVoice.WARM -> MoraleVoicePack(
        calmDesc = "Добре слово, поки тихо.",
        flybyDesc = "Дружній літак іноді пролітає повз — просто привітатись.",
        deathDesc = "Маленьке прощання для загроз, що зникли зі стрічки.",
        followBulletDesc = "Камера лагідно проводжає прощання.",
        hdExplosionDesc = "М’якші, гарніші ефекти прощання. Трохи важче для телефону.",
        tallyDesc = "Рахуємо вирішені загрози в твоїй області. Тапай — покажемо повтор.",
        allUkraineDesc = "Рахувати всю країну теж.",
        alarmEpisodeDesc = "Що вирішили, поки тривала твоя тривога.",
        note = "Функції моралі — прикраса, а не виявлення. \u201cНейтралізовано\u201d означає лише, що загроза зникла зі стрічки: перехоплена, впала чи радар її просто втратив. Ми не знаємо напевно, тож вшановуємо наших захисників, а не святкуємо чиюсь біду. Це не впливає ні на тривоги, ні на зони, а під час активної повітряної тривоги все крокає назустоп. Завжди довіряй офіційним сиренам.",
        calm = listOf(
            "Тихо — ти молодець",
            "Дихай спокійно",
            "Подзвони тим, кого любиш",
            "Цей спокій важко здобутий",
            "Відпочинь, поки небо відпочиває",
            "Перевір сусіда",
            "Гарний вечір для чаю",
            "На твоїй вулиці тихо",
            "Мир личить цьому місту",
            "Пройдись повільно",
            "Обійми своїх",
            "Відклади тривоги на потім",
            "Тихі ночі теж важливі",
            "Перезарядись — ти заслужив",
            "Хтось там стежить за тобою",
            "Завтра зачекає"
        )
    )
    MoraleVoice.SPICY -> MoraleVoicePack(
        calmDesc = "Теплі слова, поки тихо.",
        flybyDesc = "МіГ фотобомбить твою мапу. Дозволу не питав.",
        deathDesc = "Загроза зникла зі стрічки? Феєрверк. Таке правило.",
        followBulletDesc = "Камера женеться за драмою до самого кінця.",
        hdExplosionDesc = "Максимум Голлівуду на кожен тип. Телефон може підвисати.",
        tallyDesc = "Рахунок твоєї області. Тапай — подивимо феєрверки.",
        allUkraineDesc = "Рахунок по всій країні. Більша усмішка.",
        alarmEpisodeDesc = "Звіт з вечірки після твоєї тривоги.",
        note = "Мораль — це блискітки зверху, а не радар. \u201cНейтралізовано\u201d означає лише, що загроза злиняла зі стрічки: перехоплена, гепнулась чи радар її просто загубив між подушками. Ми не знаємо напевно, тож вболіваємо за наших захисників, а не за чиюсь біду. Нуль впливу на тривоги й зони, а щойно лунає справжня тривога — вечірка на паузі, сцена за сиренами. Або офіційні сирени, або нічого.",
        calm = listOf(
            "Тихо — підйди торкнись трави",
            "Сертифікований момент спокою",
            "Небо: чисте. Ти: квітнеш",
            "Жодних загроз, самі вайби",
            "Дихай. Пий воду. Повторюй",
            "Напиши мамі",
            "Чай у мирний час — окремий вид насолоди",
            "Усе тихо — підозріло приємно",
            "Твоя область чилить",
            "Арка відпочинку головного героя",
            "Спи як слід",
            "Торкнися трави: просунутий рівень",
            "Тиша, але золота",
            "Заряджай телефон І себе",
            "Радар нудьгує. І добре",
            "Сюжетний твіст: нічого не сталось"
        )
    )
    MoraleVoice.SLANG -> MoraleVoicePack(
        calmDesc = "Тихо. Можна видихнути.",
        flybyDesc = "МіГ робить прохід. Красіво, і все.",
        deathDesc = "Ціль зникла зі стрічки? Це бавовна. Чисто для настрою.",
        followBulletDesc = "Камера їде дивитись, куди лягла бавовна.",
        hdExplosionDesc = "Преміальна бавовна під кожен тип — уламки, іскри, дим. Телефон може підвисати.",
        tallyDesc = "Рахуємо мінуси в твоїй області. Тапай — покажемо повтор.",
        allUkraineDesc = "Мінуси по всій Україні. Бавовняна енергія більша.",
        alarmEpisodeDesc = "Звіт по тривозі: що приземлили, поки ти був в укритті.",
        note = "Мораль — прикраса, а не виявлення. \u201cНейтралізовано\u201d означає лише, що ціль зникла зі стрічки: збита, впала чи радар її просто загубив. Ми не знаємо напевно, тож топимо за наших захисників, а не за чиюсь біду — у цьому весь сенс бавовни: святкуємо своїх, а не приліт. Нуль впливу на тривоги й зони; під час справжньої тривоги шоу стопається, екраном володеють сирени. Завжди слухай офіційні сирени.",
        calm = listOf(
            "Тихо. Видихай",
            "Вечір без бавовни. Кайф",
            "Небо чисте. Чилимо",
            "Сьогодні мінусувати не треба",
            "Чил — ППО на зміні",
            "Тихо означає: хлопці працюють",
            "Чай по-військовому: гарячий і солодкий",
            "На твоїй вулиці тихо",
            "Мінусів: нуль. Ідеально",
            "Відпочинь — жити треба",
            "Набери своїх",
            "Тихо — найкращий звіт",
            "Заряджайся, як павербанк",
            "Нічого не приземленено, нема на що дивитися",
            "Область спокійна. Так тримати",
            "Слава тиші. Відпочивай"
        )
    )
    MoraleVoice.VIYSKO -> MoraleVoicePack(
        calmDesc = "Рядок статусу, поки контактів немає.",
        flybyDesc = "Плановий прохід. Лише ефект на мапі.",
        deathDesc = "Контакт втрачено зі стрічки: відображено як перехоплення. Тільки дисплей.",
        followBulletDesc = "Камера супроводжує до точки останнього контакту.",
        hdExplosionDesc = "Повна візуалізація за типами. Ціна: продуктивність.",
        tallyDesc = "Поточний рахунок втрачених контактів у твоїй області. Натисни для повтору.",
        allUkraineDesc = "Розширити рахунок на всі області. Картина по театру.",
        alarmEpisodeDesc = "Розбір контактів, втрачених за час твоєї тривоги.",
        note = "Засоби моралі — лише рівень відображення: на виявлення, зони й тривоги вони не впливають. \u201cНейтралізовано\u201d — службова позначка, що контакт залишив стрічку: перехоплений, збитий чи втрачений радаром. Причина невідома, тож рахунок вшановує наших захисників, а не чиюсь шкоду. Під час активної повітряної тривоги всі засоби моралі відходять, екраном володіють сирени. Офіційні сирени — наказ дня.",
        calm = listOf(
            "Сектор тихий. Вільно",
            "Контактів немає",
            "Спочинь, але бувай напоготові",
            "Усі пости доповідають: спокійно",
            "Зміна тиха. Пий воду",
            "Периметр тримається",
            "Відпочинок — теж задача. Виконуй",
            "Зв’язок: усе добре",
            "Ніч минула без пригод",
            "Мораль: стабільно",
            "Перевір побратима",
            "Паєк: чай. Статус: гарячий",
            "Приціл чистий",
            "Тримай позицію. Розслаб плечі",
            "Тиша — успішна операція",
            "Чекай нічого. Насолоджуйся"
        )
    )
    MoraleVoice.BABUSIA -> MoraleVoicePack(
        calmDesc = "Тепле слово від бабусі, поки тихо.",
        flybyDesc = "Гарний літачок пролетів, сонечко. Просто подивитись.",
        deathDesc = "Погане полетіло геть, хух — і нема! Не переживай.",
        followBulletDesc = "Камера сама подивиться, тобі не треба.",
        hdExplosionDesc = "Гарніші іскорки, квіточко. Трохи важче, як добрий борщ.",
        tallyDesc = "Скільки поганого наші хлопці відігнали в твоїй області. Натисни — покажу!",
        allUkraineDesc = "Рахуємо по всій країні — наші хлопці скрізь, дякувати Богу.",
        alarmEpisodeDesc = "Що наші захисники порішили, поки ти сидів у безпеці, рибко.",
        note = "Ці маленькі вистави — лише для твого серця, сонечко, на тривоги вони не впливають анітрохи. \u201cНейтралізовано\u201d означає тільки, що погане зникло з екрану: наші хлопці його дістали, воно впало чи радар просто загубив. Ми не знаємо напевно, тож дякуємо нашим захисникам і ніколи-ніколи не радіємо чужій біді — добре серце так не працює. А коли лунає справжня тривога, усі мультики зупиняються і головна — сирена. Слухай офіційні сирени, дідусю. Бабуся тебе любить.",
        calm = listOf(
            "Тихо, сонечко. Усе добре",
            "Ти поїв? Піди поїж",
            "Вдягни теплі шкарпетки",
            "Подзвони мамі, вона чекає",
            "Допий чай, поки не охолов",
            "Такий тихий вечір, дякувати Богу",
            "Відпочинь очима, квіточко",
            "Бабуся тобою пишається",
            "Сиди, відпочинь. Усе гаразд",
            "Двері замкнув? Добре. Спи",
            "Хлопці стежать. Спокійно",
            "Доїж котлетку. Там дві",
            "Обійми когось, серцю тепло буде",
            "Тиха ніч — як замовляла",
            "Ти така добра дитина",
            "Спи спокійно, рибко. Бабуся поруч"
        )
    )
    else -> throw IllegalArgumentException("PLAIN is built from Strings, RANDOM never resolves here")
}
