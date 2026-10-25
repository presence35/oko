# 🇺🇦 Oko

[🇬🇧 English](#english) · [🇺🇦 Українська](#українська)

---

## English

**Beta · Android · Free**

### The siren tells you the oblast is under attack. Oko tells you if it's coming to you.

Start from a ready-made setup. Change any zone, type or sound. Then stop checking Telegram, live your life.

**[Install the beta APK](https://odesaplay.com.ua/other_apps/oko/app-release.apk)** — 26 MB · Android 8+ · Free, no ads, no account.

<p align="center">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1.png" width="200" alt="Live threat map">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/2.png" width="200" alt="Threat card: distance and time">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/3.png" width="200" alt="Day alert zones">
</p>

> **Not on Google Play yet.** Google requires 12 testers for 14 days before a personal developer account can publish anything. You're one of the twelve — after that it goes public.

### What it decides

- **Zones you size** — red and yellow, with separate day and night values. Fast threats are measured by time to reach you, slow ones by distance, so a ballistic missile and a Shahed don't share a rule.
- **Alerts by type** — a Shahed rings, a recon drone doesn't. What shows on the map is a separate switch from what rings.
- **Your frequency** — every change, once per threat, once per type, or a digest. One tap sleeps it until morning and restores exactly what you had.
- **It tells you why** — "Shahed, 14 km, heading your district." Silence is an answer too: nothing in your zones is coming.

### How hard should it work?

Four ready-made setups. Pick one — everything it sets becomes yours to change afterwards.

- **Aware** — for the thick of it: every change rings, all four rings armed, GPS kept warm, sirens sound even in silent mode.
- **Normal** — what most people want: one alert per threat, standard rings, the usual reliability guarantees.
- **Quiet** — a house with no time for this: one summary every ten minutes instead of bells, gentler effects.
- **Watching** — far from it, only curious? Nothing wakes up for you, no in-zone bells. The map still looks like the map.

### And who's holding the phone?

Seven ways of living near a war. Pick the one closest to your day.

- **Grandma** — her own city, official levels, loud, every change sounds. Distance rings off; she doesn't travel.
- **Parent** — region-wide, once per threat, every bell armed, cards clear themselves, two minutes of debris countdown.
- **Frontline** — every level, every type, every change, loud through silent mode, bells armed at night.
- **Teen** — red only, in-zone bell, loud. Yellow stays quiet so the phone isn't ignored.
- **Abroad** — official levels for your city, no alarm tone, no wake-ups for a city you don't live in.
- **At work** — official levels at normal volume, no distance rings, cards clear themselves.
- **Road** — region-wide official levels, loud over road noise, no distance rings, cards clear themselves.

### The map underneath

<p align="center">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/4.png" width="240" alt="Night zones, separate from day">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/5.png" width="240" alt="Nearest shelters with walking time">
</p>

> The engine does the reasoning. The flybys, the strike replays and the tally exist so the map doesn't feel like waiting to die.
>
> One evaluation engine feeds both the map and the background alert service, so the siren and the screen can never disagree.

### You're getting an alpha

- **Bugs:** send the logs before you uninstall — tap the version, then Logs → Send logs.
- **Translations** are AI-made from English. If something reads oddly, flag it in the same log.
- **Battery:** a bright screen costs battery. A fast drop while the phone sits idle is the bug we need most.

Oko is an independent civilian tool, not an official alert source — always follow official air-raid signals. Positions and distances are approximate.

### Everything it does

**The map** — live tracks gliding between updates · distance and time to reach you · official alerts and all-clears scoped to your city · shelters with walking times · home-screen widget · dark only, high contrast.

**The alerts** — red and yellow zones you size · fast threats by time, slow ones by distance · separate day and night radii · each zone armed independently · per-type bells · map visibility separate from sound · four frequency presets · digest with a window you set · no re-sounding when a track flickers · one-tap sleep, restored exactly · warnings when the feed goes offline · runs with the app closed.

**Morale** — MiG-31K flyby with sound and haptics · strike replays, optional camera follow · neutralized tally, tap to replay · alarm summary after the all-clear · every flourish retreats during a real alert.

**Honesty** — not a radar, and not a predictor of harm · positions and distances are approximate · threat data from NEPTUN · stale tracks dim instead of pretending.

**The rest** — free, no ads · no account, no tracker, no backend of ours · GPLv3, source available · Play and sideload builds, sideload self-updates.

---

## Built with

Kotlin and Jetpack Compose (Material 3, dark only) over MapLibre, in one module. Two flavors from one codebase: `play` for the store, `sideload` for the self-updating beta. Threat data streams straight from the public [NEPTUN](https://neptun.in.ua) WebSocket — no backend of ours, no Firebase, no push infrastructure. One evaluation engine is shared by the map and the background alert service, and it is specified in writing.

- Codebase map and invariants → [`ARCHITECTURE.md`](ARCHITECTURE.md)
- Threat-engine contract → [`BEHAVIORS.md`](BEHAVIORS.md)

GNU General Public License v3.0 only — see [`LICENSE`](LICENSE).

---

## Українська

**Бета · Android · Безкоштовно**

### Сирена каже, що під загрозою область. Око каже, чи вона йде до вас.

Почніть із готового налаштування. Змініть будь-яку зону, тип чи звук. А потім перестаньте перевіряти Telegram — живіть своїм життям.

**[Завантажити бета APK](https://odesaplay.com.ua/other_apps/oko/app-release.apk)** — 26 МБ · Android 8+ · Безкоштовно, без реклами, без акаунта.

<p align="center">
  <img src="fastlane/metadata/android/uk/images/phoneScreenshots/1.png" width="200" alt="Жива мапа загроз">
  <img src="fastlane/metadata/android/uk/images/phoneScreenshots/2.png" width="200" alt="Картка загрози: відстань і час">
  <img src="fastlane/metadata/android/uk/images/phoneScreenshots/3.png" width="200" alt="Денні зони тривоги">
</p>

> **Ще не в Google Play.** Google вимагає 12 тестувачів на 14 днів, перш ніж особистий акаунт розробника зможе щось опублікувати. Ви один із дванадцяти — після цього застосунок стане публічним.

### Що він вирішує

- **Зони, які задаєте ви** — червоне й жовте коло, з окремими значеннями для дня й ночі. Швидкі загрози міряються часом до вас, повільні — відстанню: балістика й «Шахед» не мають спільного правила.
- **Сповіщення за типом** — «Шахед» дзвонить, розвідувальний дрон — ні. Що видно на мапі — окремий перемикач від того, що дзвонить.
- **Ваша частота** — кожна зміна, раз на загрозу, раз на тип або дайджест. Один тап — і тиша до ранку, а потім усе повертається як було.
- **Каже, чому** — «Шахед, 14 км, курсом на ваш район». Тиша — теж відповідь: у ваших зонах нічого не йде до вас.

### Наскільки ретельно працювати?

Чотири готові налаштування. Оберіть одне — усе, що воно виставить, далі можна змінити.

- **Пильний** — для тих, хто в епіцентрі: кожна зміна дзвонить, усі чотири кола увімкнені, GPS не засинає, сирена звучить навіть у беззвучному режимі.
- **Звичайний** — те, що потрібно більшості: одне оповіщення на загрозу, звичайні кола, стандартні гарантії надійності.
- **Тихий** — дім, де немає часу на це: підсумок раз на десять хвилин замість дзвінків, м'якші ефекти.
- **Спостерігаю** — далеко від цього, просто цікаво? Ніщо не прокидається заради вас, жодних дзвінків у зоні. Карта лишається картою.

### А хто тримає телефон?

Сім способів жити поруч із війною. Оберіть найближчий до вашого дня.

- **Бабуся** — своє місто, офіційні рівні, гучно, звучить кожна зміна. Кільця відстані вимкнені — вона нікуди не їздить.
- **Батьки** — увесь регіон, раз на загрозу, всі дзвінки, картки зникають самі, дві хвилини відліку уламків.
- **Фронт** — кожен рівень, кожен тип, кожна зміна — гучно крізь беззвучний режим, дзвінки вночі.
- **Підліток** — лише червоний, дзвінок у своїй зоні, гучно. Жовтий мовчить, щоб телефон не ігнорували.
- **За кордоном** — офіційні рівні для свого міста, без сирени, без побудок через місто, де ви не живете.
- **На роботі** — офіційні рівні звичайною гучністю, без кілець відстані, картки зникають самі.
- **За кермом** — офіційні рівні по регіону, гучно крізь шум дороги, без кілець відстані, картки зникають самі.

### Під усім цим — мапа

<p align="center">
  <img src="fastlane/metadata/android/uk/images/phoneScreenshots/4.png" width="240" alt="Нічні зони окремо від денних">
  <img src="fastlane/metadata/android/uk/images/phoneScreenshots/5.png" width="240" alt="Найближчі укриття з часом пішки">
</p>

> Двигун робить висновки. Прольоти, повтори збиттів і лічильник існують, щоб мапа не відчувалася як очікування смерті.
>
> Один двигун оцінки живить і мапу, і фоновий сервіс тривог — тож сирена й екран не можуть розійтися.

### Ви отримуєте альфу

- **Баги:** надішліть логи до того, як видалите застосунок — торкніться версії, далі Журнал → Надіслати логи.
- **Переклади** зроблені ШІ з англійської. Якщо щось звучить дивно, зазначте це в тому ж лозі.
- **Батарея:** яскравий екран завжди витрачає заряд. Швидке падіння, коли телефон лежить без діла, — той баг, який нам потрібен найбільше.

Око — незалежний цивільний інструмент, а не офіційне джерело тривог. Завжди дотримуйтесь офіційних сигналів повітряної тривоги. Позиції та відстані приблизні.

### Усе, що він робить

**Мапа** — живі треки, що рухаються між оновленнями · відстань і час до вас · офіційні тривоги та відбої з прив'язкою до вашого міста · укриття з часом пішки · віджет на робочому екрані · тільки темна тема, високий контраст.

**Тривоги** — червоне й жовте коло, які задаєте ви · швидкі за часом, повільні за відстанню · окремі радіуси для дня й ночі · кожне коло вмикається окремо · дзвінки за типом загрози · видимість на мапі окремо від звуку · чотири готові режими частоти · дайджест із заданим вікном · жодного повторного звуку, якщо трек зник і повернувся · один тап — і тиша до ранку, потім усе як було · попередження, коли потік даних зникає · працює із закритим застосунком.

**Бойовий дух** — проліт МіГ-31К зі звуком і віддачею · повтори збиттів, опційна камера супроводу · лічильник знешкоджених, тап — і все наново · підсумок тривоги після відбою · усе це відступає під час справжньої тривоги.

**Чесність** — не радар і не пророк шкоди · позиції та відстані приблизні · дані про загрози від NEPTUN · застарілі треки тьмяніють, а не вдають живі.

**Решта** — безкоштовно, без реклами · без акаунта, без трекера, без нашого сервера · GPLv3, код відкритий · збірки Play і sideload, sideload оновлюється сам.
