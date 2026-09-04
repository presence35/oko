# Changelog

## [Unreleased]

- New Ubilling REST source: when the primary WS source is down past a 5-min grace, Ubilling takes over official-oblast alerts with battery-aware adaptive polling (15s foreground / 30s background), auto-clearing on recovery — no false all-clear, no double-notify / Нове REST-джерело Ubilling: коли основне WS-джерело недоступне понад 5 хв, Ubilling бере на себе офіційні тривоги областей з енергоощадним адаптивним опитуванням (15с у фоні активного / 30с у фоні), автоматично очищаючись при відновленні — без фальшивого «відбій», без подвійних сповіщень
- Logs Sources tab: per-source toggle actually sticks (bound to real enabled state, not derived from mode); fallback takeovers logged in the connection log / Вкладка «Джерела» в журналі: перемикач джерела тепер працює (прив'язаний до реального стану), перехід на резервне джерело фіксується у журналі з'єднань
- Logs Sources tab: Test button per source runs a live check (REST fetch / WS state) and shows the result inline; a live activity feed lists source toggles and alert-owner handovers / Вкладка «Джерела»: кнопка «Тест» для кожного джерела виконує живу перевірку (REST-запит / стан WS) і показує результат одразу; жива стрічка активності відображає перемикання джерел та переходи власника тривог
- Removed 20s all-clear grace period — alerts clear instantly / Видалено 20с затримку попередження — сповіщення зникають миттєво
- Explosion now scales with map zoom level / Вибух тепер масштабується з рівнем зуму карти
- Shot-down drones hide during death animation then scale back in after 2.1s / Збиті дрони ховаються під час анімації знищення і повертаються за 2.1с
- Fixed threat icon flipping direction when shot / Виправлено перевертання іконки загрози при пострілі
- Alerts never fall back to a pinned city while following GPS — the last known GPS fix is used even when stale; no fix at all shows a persistent "No GPS fix" warning / Alerts never fall back to a pinned city while following GPS — the last known GPS fix is used even when stale; no fix at all shows a persistent "No GPS fix" warning
- Map panning now extends past Ukraine's tight bounds so the viewport can shift content out from under the threat card when zoomed at a country edge; added a subtle outline of Ukraine's perimeter / Map panning now extends past Ukraine's tight bounds so the viewport can shift content out from under the threat card when zoomed at a country edge; added a subtle outline of Ukraine's perimeter

