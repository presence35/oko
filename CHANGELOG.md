# Changelog

## [Unreleased]

- New Ubilling REST source: when the primary WS source is down past a 5-min grace, Ubilling takes over official-oblast alerts with battery-aware adaptive polling (15s foreground / 30s background), auto-clearing on recovery — no false all-clear, no double-notify / Нове REST-джерело Ubilling: коли основне WS-джерело недоступне понад 5 хв, Ubilling бере на себе офіційні тривоги областей з енергоощадним адаптивним опитуванням (15с у фоні активного / 30с у фоні), автоматично очищаючись при відновленні — без фальшивого «відбій», без подвійних сповіщень
- Removed 20s all-clear grace period — alerts clear instantly / Видалено 20с затримку попередження — сповіщення зникають миттєво
- Explosion now scales with map zoom level / Вибух тепер масштабується з рівнем зуму карти
- Shot-down drones hide during death animation then scale back in after 2.1s / Збиті дрони ховаються під час анімації знищення і повертаються за 2.1с
- Fixed threat icon flipping direction when shot / Виправлено перевертання іконки загрози при пострілі
- Alerts never fall back to a pinned city while following GPS — the last known GPS fix is used even when stale; no fix at all shows a persistent "No GPS fix" warning / Alerts never fall back to a pinned city while following GPS — the last known GPS fix is used even when stale; no fix at all shows a persistent "No GPS fix" warning

