?# Architecture — Ukraine Drones

Technical map of the codebase. Read this before exploring so you can jump straight to the
file(s) you need instead of re-deriving the structure. Keep it current: if you add a file or
change a documented invariant, update the relevant section.

## Quick facts

- Single-module Android app (`:app`) — a live air-threat map for Ukraine.
- Jetpack Compose (Material 3, dark-only) + OSMdroid. Kotlin 1.9.24, JDK 17, minSdk 26 /
  targetSdk 35, namespace `ua.ukrainedrones`.
- No runtime backend of ours: data comes straight from the public
  [NEPTUN](https://neptun.in.ua) API (WebSocket stream). No Firebase, no push.
- Update feed: static `version.json` + APK on `odesaplay.com.ua`, self-checked daily, in-app install.
- Coroutines + flows throughout; singletons expose `StateFlow`s.

## Package structure

Source files are grouped into subdirectories by subsystem (`data/ domain/ service/ ui/
widget/`) while keeping a **single flat package** `ua.ukrainedrones` — so any file can reach
any other without import ceremony. The subdirs are organizational only; don't add package
qualifiers. Migrate to a true multi-package layout only if it ever outgrows maintainability.

## System overview

The UI and the background service are **independent consumers of shared inputs** — both
re-derive state from the same singletons, never from each other.

```
                    ┌──────────────────────────┐
  NEPTUN WS ────────►│                          │
  NEPTUN REST ──────►│  NeptunConnectionClient  │
  NetworkMonitor ───►│       (via Holder)       │
                    └──────────┬───────────────┘
                               │ connectionState / threats / alerts
                 ┌─────────────┴─────────────┐
                 ▼                           ▼
          MainViewModel                AlertService
                 │                           │
                 ▼                           ▼
            Compose UI                 Notifications
```

```
ZonePrefs ────────┬──► MainViewModel        Shared logic (call, don't duplicate):
                  └──► AlertService         engine/ThreatEngine.kt (evaluate, predictPosition)
LocationTracker ──┬──► MainViewModel        NightMode.kt / Cities.kt (focusAttribution)
                  └──► AlertService
```

## Core ownership

| Concern | Source of truth | Consumers |
| --- | --- | --- |
| NEPTUN connection | `NeptunConnectionClient` (via `ConnectionHolder`) | UI, `AlertService` |
| Connection state machine | `ConnectionState` sealed interface | UI, `AlertService`, widget |
| Network validation | `NetworkMonitor` | `NeptunConnectionClient` |
| Official oblast alerts | `NeptunConnectionClient.alerts` | UI, `AlertService`, widget |
| Threat prediction | `engine/ThreatEngine.kt` | ViewModel, service, MapView, widget |
| Zone tier math | `engine/ThreatEngine.kt` | ViewModel, service |
| Night rule resolution | `NightMode.kt` | ViewModel, service |
| User preferences | `ZonePrefs` | all |
| UI orchestration | `MainViewModel` | Compose |
| Background monitoring | `AlertService` | notifications |
| Connection history | `ConnectionLog` | system status, Logs screen |
| Decision audit | `DebugLog` | Logs screen |
| Map rendering | `MapView.kt` | Compose |
| Threat icons | `IconCatalog.kt` | UI |

## Data flow

- **Threat ingest.** NEPTUN WS + REST merge in `NeptunConnectionClient` (via `ConnectionHolder`) → separate `StateFlow`s: `connectionState`, `threats`, `alerts`; `removedThreats` SharedFlow for map death animations. Consumers read flows directly — no intermediate `NeptunState`.
- **Position prediction** (both consumers):
  `ThreatEngine`'s `SpeedCache.estimate(id, t, props)` (server speed → trail → nominal)
  → `predictPosition(t, speed, props, now)` (dead-reckon **only tracks with a real velocity** —
  `bearingDeg` + `speedKmh`, matching NEPTUN's `flying`; everything else holds the raw fix —
  capped at the per-type horizon/ghost) → distance to focus → `zoneTier` (slow:
  distance-to-confirmed-fix, fast: predicted-ETA). Icon facing shares `motionHeading`, which
  prefers the server's authoritative `bearingDeg` over the reported `heading`.
- **Update flow.** `UpdateManager.check()` → `Available` → `download()` (progress) →
  `buildInstallIntent()` → system installer. `AlertService` also checks silently every day at
  16:20 while it runs and posts one "new version available" notification per new build
  (deduped by `last_notified_update_code`); tapping it re-opens the app and pops the update dialog.

## Module map

Grouped by subsystem. Every file with its responsibility; a terse *Note:* flags implementation
detail that matters when editing that file.

### App entry / theme

| File | Responsibility |
| --- | --- |
| `MainActivity.kt` | Single activity; dark theme (via `DarkThemePlugin`); starts `AlertService`; legacy osmdroid cache cleanup. *Note:* location→notification permissions defer until first-run onboarding resolves; "Later" sets `permission_prompt_deferred` (re-armed each cold start). |
| `theme/ThemePlugin.kt` | Theme plugin contract (`name`, `isDark`, `colors`, `typography`). |
| `theme/DarkThemePlugin.kt` | The only shipped theme (dark-only); `MainActivity` applies `DarkThemePlugin.colors`. |

### Data ingress (NEPTUN)

| File | Responsibility |
| --- | --- |
| `connection/ConnectionState.kt` | Sealed interface state machine: `Disconnected` → `Connecting` → `Connected` → `Degraded` → `Offline` → `Paused`; convenience extensions `isConnected`, `isDegraded`, `isOffline`, `isForceOffline`, `offlineSinceOrNull`, `reconnectStartMillisOrZero`. |
| `connection/NeptunConnectionClient.kt` | High-reliability WebSocket client for the NEPTUN telemetry feed. Strict generation-based lifecycle (`AtomicInteger`) prevents stale socket callbacks. Asynchronous channel processor dispatches frames → separate `StateFlow`s: `connectionState`, `threats`, `alerts`, `removedThreats`, `threatDataStale`; reconnect backoff 1–3 s → capped 15 s (`calculateBackoffMs`, tested); keep-alive pings + 45 s watchdog. `markUserShot(id)` / `wasUserShotRecently(id)` for map death-animation grace. `recordEvent` / `dismissConnLog` for the connection-log card. Isolated `TestHarnessImpl` for MiG injection + force-offline. |
| `connection/NetworkMonitor.kt` | Validated network observer via `ConnectivityManager.NetworkCallback`. Emits `isValidated` StateFlow. On validation, kicks `onValidatedReturn` callback (triggers fast reconnect in client). |
| `connection/ConnectionSupervisor.kt` | Milestone tracker for offline episodes. Compares `offlineSinceOrNull` to thresholds (3/5/6/10/20 min) and emits milestone `ConnEventKind` entries. |
| `connection/ConnectionHolder.kt` | Lazy singleton holder for `NeptunConnectionClient` + `ConnectionSupervisor`. `getClient(context)` / `getSupervisor(context)` / `clear()`. Avoids startup race — MainViewModel constructs before `AlertService.onCreate` runs. |
| `NeptunClient.kt` | `object` holding shared constants (`OFFLINE_GRACE_MS`, `DEGRADED_STALE_MS`, `USER_SHOT_GRACE_MS`, `NEPTUN_DOMAIN`) and data classes (`ConnRetryState`, `ConnEventKind`, `ConnEvent`, `ThreatRemoved`). |
| `Threat.kt` | NEPTUN display metadata + parsing: `ThreatType`/`ThreatTypeCatalog`/`Reliability` (labels, staleness, nominal speeds), `OblastAlert`/`inOblast`/`coversCity` + shared `officialAlertActiveFor` scope gate; `translateCourseAssessment` (EN course text, word-level common-word translation); `normalizedThreatFromJson` — NEPTUN JSON → `NormalizedThreat` directly (the engine currency; no `Threat` display DTO anymore). |

### State / orchestration

| File | Responsibility |
| --- | --- |
| `MainViewModel.kt` | `AndroidViewModel`. Combines NEPTUN + GPS + prefs + 1s clock (flourish data/policy live in `flourish/Flourish.kt` — `FlourishRecord`/`FlourishShow` and the `FlourishPolicy` gates used by `buildUiState`) → `StateFlow<UiState>`; drives the update flow (daily start check, a Settings-open check that raises `updateReminderTick` — a snackbar with a Download action — instead of a clickable toast, which Android can't make touchable, plus manual check/download/install); UI-side copy of zone/focus/alert logic (tradeoffs); `neutralizeThreat` long-press hook. Reads `ConnectionHolder.getClient(app)` flows directly: `connectionState`, `threats`, `alerts`; sampled at 120 ms for UI. `neptunForUi` is a `combine(connectionState, threats, alerts)` → `.sample(120)`. |
| `ConnectionLog.kt` | `object` singleton. Persisted ring buffer (last 10 episodes) fed by the watchdog; commits drops only past `OFFLINE_GRACE_MS`. `ConnStatus` = ONLINE/OFFLINE (no backup state). Pure `commitLogState` (tested); rendered in the Logs screen. Non-blocking persistence (no `runBlocking` on IO). |
| `DebugLog.kt` | `object` singleton. Persisted audit trail (last 500 decisions, rolling 24h window) written by `AlertService`, read by the Logs screen. Records every alert/threat decision in the active region — official on/off, zone entries, region threats — with day/night and effective sound, whether a notification was shown and why not. `DebugLog.sweep` runs once per service tick and is **read-only for the decision path**: it describes the service's own computed maps (`zoneThreats`/`alertable`/`knownZones`/`postedId`), never re-derives formulas. Pure `computeSweep`/serialize/parse (tested). Non-blocking persistence (no `runBlocking` on IO). *Note:* the whole feature is additive — removing it is deleting the write hooks + this object + `DebugLogScreen`. |
| `Shelters.kt` | Odesa shelter dataset: `Shelter`/`NearestShelter` (adult ~5 km/h, kid ~3 km/h walk minutes), `ShelterIndex` (JSON parse, Odesa bbox, nearest ranking). |

### Engine kernel (source-agnostic)

| File | Responsibility |
| --- | --- |
| `engine/NormalizedThreat.kt` | Mapping-library-agnostic `LatLng`, `TrailPoint`, `NormalizedThreat` (source-agnostic threat model; `type` is a plain String, `flying` gate), `fallbackCourse` (NEPTUN's `A(id)` pseudo-course). |
| `engine/ThreatProps.kt` | `ThreatProps` (isFast, reachKm, alwaysInnerWithinReach, staleAfterMs, ghostCapMs, nominalSpeedMps, horizonSec, maxGhostMeters), `DEFAULT_THREAT_PROPS`, `NEPTUN_TYPES` catalog. |
| `engine/Distance.kt` | Haversine `distanceHaversine`/`bearingHaversine`; equirectangular `distanceFlat`/`bearingFlat` (short-range display basis). |
| `engine/SpeedCache.kt` | Engine-internal per-threat fix queue → measured speed/heading; `SpeedSource` (RECORDED/TYPICAL). |
| `engine/ThreatEngine.kt` | The core: `evaluate` (inner/outer zones, mapThreats, scores, activeZone, threatLevel), `zoneTier`, `predictPosition`, `motionHeading`, `isStale`/`isExpired`/`isGhost`, `computeProximity`, `scoreThreat`/`aggregateScores`; `ThreatZone`, `ZoneParams`, `ThreatEvaluationResult`, `ThreatProximity`. |
| `engine/ThreatSource.kt` | `ThreatSource` plugin interface + `PluginConnectionState` (source-agnostic connection state). |
| `engine/TypeMapping.kt` | `ThreatType`→engine string (`toEngineString`). No reverse mapper — `NormalizedThreat` is the app-wide currency; NEPTUN JSON parses straight to it (`data/Threat.kt`). |
| `engine/TypeBridge.kt` | App↔engine bridge: `String.toThreatType()`, `threatTypeInfoByString`, `isFastType(type)` (from `ThreatProps.isFast`), `typicalSpeedKmh(type)` (from `nominalSpeedMps`). |
| `engine/OblastUtils.kt` | Geographic/text utilities (kept from the old `ThreatEvaluator`): `inOblast`, `inFocusOblast`, `matchOblast`, `canonicalToken`, `isCityScopedSuppressed`, `threatBody`, `deriveOfficialAlertReason`, `OblastMatch`. |
| `plugins/NeptunPlugin.kt` | Wraps `NeptunConnectionClient` as a `ThreatSource`. |
| `plugins/PluginRegistry.kt` | Manages plugins, merges threats/alerts, exposes the merged `Threat` feed + `typeCatalog`. |
| `AppPluginHolder.kt` | Singleton holding `PluginRegistry`; initialized in `AlertService.onCreate` and `MainViewModel.init`. |

### Domain logic

| File | Responsibility |
| --- | --- |
| `NightMode.kt` | Shared night helpers for **both** consumers (mirror rule): `isNightActive`, `effectiveZoneParams`/`effectiveArmed`, `NightConfig`/`NightZones`/`ZoneArmed`. |
| `Cities.kt` | ~483 places grouped by oblast in three zoom tiers (`CityTier`: 26 curated MAJOR always / MEDIUM from mid-zoom — 14 curated non-seats plus any place with `pop` ≥ 50k auto-promoted in `ALL` / rest MINOR up close; non-curated places derived from GeoNames CC BY 4.0 via `tools/gen_cities.ps1`, 2 km dedupe, same-name towns resolved by population; a few district rows carry no `pop` so inherited parent-city figures never promote them) + `CityLabelOverlay` (colors labels red for the `redCities` set — scope-aware: whole oblast by default, city-level when the City scope is on); EN names from the app's own КМУ №55 transliteration; `focusAttribution` maps focus point → oblast stem via `cityOblast` (majors only). |
| `GeoConstants.kt` | Shared geographic constants: Ukraine bounding boxes (tight for UI clamping, wide for tile coverage), Odesa city-centre fallback coordinates. Used by `DeathFxController`, `UkraineTileProvider`, `MapView`, `MainViewModel`. (Root package, not `domain/`.) |
| `Transliteration.kt` | Official КМУ №55 Ukrainian→Latin romanization (the EN gate). |
| `ZonePrefs.kt` | `AppLanguage`/`ThreatCardSize`/`ThreatIconSet` + DataStore store (`zone_prefs`): all toggles/thresholds/language/follow/pin/visibility, night config, and — problematically — serialized `ConnectionLog`, offline-restore state, onboarding flags. `haptics_enabled` is tri-state (absent = follow the system haptic setting). Also `threatMapFlow`/`threatAlertFlow`; the resolved-threat tally's focus-oblast default + "All of Ukraine" opt-in (`neutralized_tally_all_ukraine`), and the daily-update notify marker (`last_notified_update_code`). *Note:* god object mixing prefs with persisted state — split candidate (tradeoffs). |
| `Strings.kt` | UA/EN `StringSet` table (never Android resource localization); `formatRelativeTime`, `formatDateTime` (app language, not device locale). |
| `WidgetSnapshot.kt` | `WidgetSnapshot` + pure `computeWidgetSnapshot(...)` — deterministic projection of threat state for the widget, computed via the engine (`ThreatEngine(NEPTUN_TYPES).evaluate`, `engine.isStale`, `distanceFlat`, `focusAttribution`, `inOblast`). Counts + per-type `typeCounts` mirror the footer-strip semantics; `primaryThreat` = nearest live threat (id + position) so the widget can reveal it; `sourceOnline` is grace-filtered like the app pill. Takes `ConnectionState` + `Map<String, Threat>` + `List<OblastAlert>` directly (no `NeptunState`). Tested by `WidgetSnapshotTest`. |
| `IconCatalog.kt` | Single source for threat icons: vector/photo/army/comic/russian sets, per-set facing (`baseDeg`), `ThreatIcon` composable; assets in `app/src/main/iconpacks/`. |
| `Toasts.kt` | Shared toast helper: one function decides placement — top (below the header banner, via `ToastHost(topInset)`) normally, bottom (above the floating zone/shelter buttons) when a card/popup is visible. Dark themed pill. Callers never hardcode gravity. |
| `Compat.kt` | *(deleted — Session 6)* engine `LatLng`/`ThreatZone`/`ZoneParams` are now imported directly (`ua.ukrainedrones.engine.*`) instead of root-package typealiases. |

### UI (Compose)

| File | Responsibility |
| --- | --- |
| `MainScreen.kt` | Top-level Compose UI: header, alert banner, map, threat strip, `ZonesSheet`, `UpdateDialog`, first-run wizard + battery prompt. *Note:* wizard gated on a dedicated `wizard_completed` pref surfaced as tri-state `UiState.wizardCompleted` (`null` = DataStore not loaded yet → blank dark frame, neither map nor wizard composes; `false` → wizard-only screen, map is not even composed so no tile flash); "Replay first launch" keeps the map composed beneath (`wizardFromSettings`). While the wizard is up `mapVisible` is false so strikes/haptics/replays are suppressed; card flip timed to `DEATH_EXPLOSION_START_MS`; popup height feeds the map as `popupCoverPx`; the tally-tap replay flourish closes every modal and forces the map screen. |
| `ConnectionStatus.kt` | Connection pill (online / degraded-orange / offline) + system-status dialog: per-source dot, TEMP force-offline toggle, legend, attribution link, and a prominent "Logs" button (in the dialog header) opening the Logs screen. The orange middle state mirrors `ConnectionState.Degraded` — connected but the stream is quiet (threats delaying) — distinct from `SourceStatusRow`'s amber accent. |
| `Haptics.kt` | Global press-haptics: `LocalHapticsEnabled` CompositionLocal (provided from the `hapticsEnabled` pref at the MainScreen root) + `Modifier.pressTick(source)` — vibrates via `LaunchedEffect` when the element's own `MutableInteractionSource` reports pressed (the same signal as its press animation; pointer-event listeners proved unreliable here). The source must be **shared** with the element's clickable/toggleable. Raw `Vibrator`, short one-shot at full amplitude (`USAGE_ALARM` on API 30+ — same always-on channel as the shoot-down flourish) because Compose's haptic API is muted by system touch-feedback settings and predefined `EFFECT_TICK` is a silent no-op on many OEMs. Also hosts `animationsOff()` (zero animator scale → snap instead of animate) and the imperative `hapticTick()` for non-Compose tap sites. Applied across map controls, settings rows, and popup cards. |
| `LogsScreen.kt` | Full-screen Logs: one card list over decisions (the audit trail) and connection episodes, switched by chips (Decisions / Connections). The Decisions tab offers group-by (Timeline / Proximity = official, red zone, yellow zone, in-oblast, left / Type), a standard sort-direction icon toggle (newest/oldest) that applies within every grouping, and a "shown only" switch (only rows where a notification was actually shown); controls stay visible even when the list is empty so the shown-only switch can be flipped back. A double-arrow reveals more rows; a leading per-threat-type icon on threat rows (red trident = official on, green check = all-clear), an "ago" + absolute timestamp, day/night + effective sound, "Notification shown" or "No notification — \<reason\>", Clear button. |
| `MapView.kt` | `NeptunMapView` + `DARK_TILE_SOURCE`. Owns OSMdroid rendering: zone circles, marker overlays, course rotation, dead-reckoned positions; the shoot-down visuals/camera/haptics/replay delegate to `flourish/DeathFxController` (the death-anim collector is subscription-gated on `deathAnimationEnabled` and focus-gated on `mapIsUserFocus(paused, mapVisible, shelters, lifecycle)`, and a functional grey lost-dot collector runs regardless). Threat icons are a **fixed size at every zoom** (no zoom scaling); long-pressing a threat shoots it down for fun: `markUserShot` + death animation, the marker stays hidden while the animation plays and the rebuild redraws it afterwards (the object itself is never removed); the strike camera (`followStrike`) glides onto the target and — since the camera-return rework — pans back to where the user was after the explosion, with a fresh strike replacing any pending return. Long-press is disabled while an alert is active. The reveal marker is a small green **dot baked into the threat icon's top-right corner** (a single tappable marker — no separate overlay intercepting the tap) and a grey "lost" dot marks where a threat just vanished. Selecting a threat opens its popup without moving the camera. Shelter markers are hand-drawn teardrop pins (stroke-only, per-type color, white when selected) anchored tip-on-spot; tapping a shelter opens its card without moving the camera (only the shelter-list button zooms). The GPS dot is gray while "locating" (before the first fix) and blue once a fix exists. *Note:* shelter-mode zoom + deep-zoom unlock run in a dedicated `LaunchedEffect` (not the recompose-driven update block) so the fit fires reliably after the shelter markers are placed; normal zoom is capped at `NORMAL_MAX_ZOOM` (14.5, the ~5 km viewport) and only raised to `SHELTER_MAX_ZOOM` (19) while the shelter overlay is up — keeping the tile cache to what the threat map needs; zooming below `SHELTER_AUTO_EXIT_ZOOM` (13) while shelter mode is up auto-exits it. The resolved-threat death flourish (`deathFx`) is skipped while the shelter overlay is visible (`showNearbySheltersState`) and, during an alert, only when the resolution is off-screen — in-camera resolutions still play. `overlayKey` excludes raw positions so live movement updates in-place; must not perform alert decisions. The tally-tap replay flourish groups the remembered resolutions by viewport-adaptive proximity (`clusterFlourish`, threshold approx 1/3 of the current viewport width), zooms onto each group in turn, fires its bullets, then returns home; each bullet vibrates short-on-shot / longer-on-detonation, and `deathFx.active` is surfaced via `onDeathActiveChange` so the footer can swap its copy. the reveal framing always pins the revealed threat to the lower viewport so it never sits under the top popup card. |
| `SettingsScreen.kt` | Collapsible sections: language, map centre, per-type Map/Alerts toggles + icon packs, card size, alert toggles, the **Just Fun** section (last card: icon packs, calm messages, shoot-down animation + follow-the-bullet, neutralized count + all-Ukraine), night-mode card, updates, battery exemption, guide; one-time explainers. The night-mode section tints its whole collapsible card darker purple + border (`NightSectionBg`/`NightSectionBorder`), no moon icon on the enabled toggle; battery exemption lives in the Alerts section; shelter button toggle + shelter directory row live in their own dedicated **Shelter** section; sections stay user-collapsible even while searching; a Reset-tips row re-arms every first-use hint (toast counters + explainers); icon packs ship PHOTO/ARMY/COMIC/RUSSIAN only (CLASSIC removed — a stored `"CLASSIC"` pref falls back to PHOTO, classic vectors remain the internal pack-fallback); the card-size tiles preview the small card as its real compact top-left chip (~75% of tile width) and the large card full-width. |
| `ZonesSheet.kt` | "Edit zones" sheet: Slow (km)/Fast (min) sliders + per-zone bells; edits day or night values depending on the active window; night rows carry day reference ticks. |
| `ThreatPopupCard.kt` | Threat popup (small chip / large card); `AlertsOffChip` when type alerts are off; neutralized/neutralizing variant. The ETA pill's blue GPS dot mirrors the map location dot (same core + white ring, subtler radial glow). |
| `ThreatTogglePanel.kt` | Shared Fast/Slow grouping, `ToggleChip`/`IconToggle`, `SlimThreatToggles` (reused by first-run dialog + Settings). |
| `FeatureExplainer.kt` | One-time explainer popups keyed by setting id; seen state via `ZonePrefs.explainerSeen`. |
| `FeatureGuide.kt` / `FeatureDiagrams.kt` | Static feature guide + its diagram drawables. |
| `FirstLaunchWizard.kt` | The 5-page first-run wizard (language+tips → threat care → location follow-me/city-pick → zone controls → core features), extracted from MainScreen; thin yellow→blue progress bar on the **top** edge of the Next pill, whole pill dims while disabled. *Note:* threat care is a 2-column vertical grid (fast ⚡ | slow 🐢, 52dp icons) with no icon-pack picker (icon packs live only in the Just Fun panel); the zone-controls step shows big red/yellow concentric circles, a REAL slow-red radius slider + armed toggle (wired to the same ViewModel setter as the map) with the slider hint beneath it, then vertical rows for shelter / red-dot / gear with short captions (`wizardShelterDesc`, `zoneRedLabel`, `editZonesLabel`); Just Fun is **off by default** on fresh installs (master switch gates the panel) with the "⚙ settings" mention rendered as an inline colored gear; every interactive control ticks via `pressTick`. |
| `CityChipPicker.kt` | Shared major-city chip grid (`CityChipGrid`) used by the wizard's location step and Settings' pin-city row. |
| `ShelterScreen.kt` | "Go to shelter" list: nearest Odesa shelters ranked by distance to the focus point, adult/kid walk times (kid row only when the "With kids" setting is on), a GPS-age header with a force precise-fix button (re-prompts location permission), transliterated names in EN; "open in maps" (`geo:` intent); the map button is a red-filled (official alert) or ghost-outlined pill in `MainScreen.kt`. |
### Flourish (isolated death + tally subsystem)

| File | Responsibility |
| --- | --- |
| `flourish/Flourish.kt` | Pure flourish core: `FlourishRecord`/`FlourishShow`/`ReplayProgress` (per-group copy + overall position for the footer bar); `FLOURISH_STAGGER_MS` + `REVEAL_MIN_SPAN_*`; `clusterFlourish`/`flourishesBoundingBox` (viewport-adaptive replay grouping); `FlourishPolicy` (the neutralized-card gate, pure + tested). |
| `flourish/DeathFxController.kt` | Map-side facade: owns `ThreatDeathOverlay` + strike camera glide/return, shot/kill haptics, a random viewport-edge take-off origin (clamped to Ukraine, so a projectile never launches from "another country") and the tally-tap replay orchestration (exposes `replayProgress: ReplayProgress?` for the footer's per-group "Resolving N threats" + overall progress bar; `startReplay` owns the replay job so `clear()` cancels a show mid-flight; while queued/running `isReplayActive` makes MapView hold its follow-me pan + default fit so the show jumps straight onto its targets). Camera notes: `getMapCenter()` is snapshotted into a new GeoPoint everywhere (osmdroid hands back its live mutable projection point — holding it made "return home" land randomly); replay jumps per group via `zoomToBoundingBox(box, false)`. Pacing: each group's targets are PRE-SPAWNED on arrival with staggered `fireAtDelayMs` leads (the overlay renders pre-fired deaths as standing icons, so nothing pops up as its bullet launches); the fire loop aligns to the prespawn clock; intermediate groups pan `REPLAY_PAN_BEAT_MS` (120ms) after the last impact via quickBoom deaths; only the final group plays the full 5s lifecycle. The tally-tap tick is consumed only on a real decision — transient blockers (cold start, Settings, shelters) retry; animation-off toasts + audits (`DebugLog.recordFlourish`, detail localized via the `showDetail` lambda); a live official alert does NOT block the replay (explicit user action) though a NEW alert onset mid-show still ejects it via `clear()`. Any map-covering modal (paused/mapVisible/shelters) or lifecycle ON_PAUSE also ejects a RUNNING show via `clear()` (queued-but-unstarted ticks stay queued and play on uncover; first-3 ejections toast a hint via `notifyFlourishEjected` + `flourishEjectHintRemaining`). `MapView` keeps only thin policy hooks and delegates every flourish mechanic here. |
| `flourish/NeutralizedTally.kt` | Service-side facade: tally count + 21-record resolution memory + the silent tally notification (tap replays the show, swipe resets), with a recent-ids ring deduping NEPTUN's re-sent removals so duplicates never double-count nor plant twin replay records. Owns `CHANNEL_NEUTRALIZED`/`NOTIF_NEUTRALIZED`/`EXTRA_FLOURISH_*`/`ACTION_NEUTRALIZED_DISMISS`. `AlertService` keeps only the enabled-pref subscription gate + focus-scope filter. |
| `flourish/AviationFlyby.kt` | Pure policy/geometry for the MiG-31K takeoff flyby: `nextShow` picks each new INNER-tier AVIATION once per process (never while the app is backgrounded or off the map screen), a fresh random bearing every pass; `endpoints` computes edge-to-edge entry/exit through the viewport center; `spriteTransform` returns the mirror-compensated rotation so the jet never renders upside-down. When it lands `MainViewModel.onFlybyFinished` opens the threat card. Tested by `AviationFlybyTest`. |
| `flourish/AviationFlybyOverlay.kt` | The flyby's Compose overlay (owned here, not in screens): full-size jet sprite + contrail Canvas driven by an Animatable pass. Rotation and trail origin come from `IconGeometryCache` per icon set through the same mirror→rotate chain graphicsLayer applies; legacy constants are only a decode-failure fallback. |
| `flourish/IconGeometry.kt` | Alpha-silhouette metrology for icon art: `computeIconGeometry` recovers the true facing (PCA principal axis, end disambiguated by the pack's declared `baseDeg`) and the exhaust anchor (rear-band centroid) as slot-local fractions — no per-pack pixel tuning. Pure core tested by `IconGeometryTest`; `IconGeometryCache` decodes/caches per `ThreatIconSet`. |
| `flourish/ThreatDeathAnimation.kt` | `ThreatDeathOverlay`: 5s neutralized flourish (projectile enters from just off the screen edge along a random edge-clamped origin -> explosion). Per-death `durationMs`: `quickBoom` deaths (intermediate replay groups) compress the explosion to impact + ~0.8s flash; boom/fade curves and pruning derive from each death's own duration. Perf practices: explosion glow is a lazily pre-rendered per-density bitmap (no per-frame `RadialGradient` allocation), icons are cached bitmaps with fresh drawable wrappers (the per-frame alpha mutation must never touch a live marker's icon), concurrent deaths capped at `MAX_DEATHS = 14` (sized for a full replay, the old 6 silently ate bullets), and the map redraws at 30fps while active (16ms -> 33ms; invalidate redraws the whole overlay stack). `DEATH_EXPLOSION_START_MS` drives the card flip; dud on duplicate resolutions; `isActiveFor(id)` guards double-strikes; the target icon vanishes at the explosion (no fade); `active` StateFlow tells the UI when a bullet/explosion is on screen. |

### Background / alerting

| File | Responsibility |
| --- | --- |
| `AlertService.kt` | Foreground service; reads `ConnectionHolder.getClient(ctx)` flows (`connectionState`, `threats`, `alerts`) via 5-flow combine; `ConnectionHolder.getSupervisor(ctx)` for milestone recording. (`specialUse` on API 34+, `dataSync` on API 29–33 — Android 15 caps `dataSync` FGS at 6h per 24h in the background, which would stop a 24/7 monitor) — the always-on monitor. Owns background monitoring and the notification lifecycle: siren/chime/all-clear (20s zone grace, coalescing), the always-visible monitor notification switching to offline wording + Retry on a drop (no separate one-shot offline alert), resolved-threat tally, per-notification vibration, `DebugLog` feed. Zone-alert dedup: ids drop from `knownZones` only when they leave the zones *and* their `userShotAt` grace (3 s) has passed, so a same-id respawn of a shot-down drone never re-alerts. | The resolved-threat tally counts by **focus oblast** by default (GPS-follow or pinned city's oblast, matched via the shared `inOblast` (`engine/OblastUtils.kt`)); an "All of Ukraine" opt-in (`neutralized_tally_all_ukraine`) lifts it to any resolution country-wide. Tapping the tally notification opens `MainActivity` directly with the last **21** remembered resolutions (position + type) baked into the tap for the map's replay flourish; a red alert (official or INNER zone) erases that memory. Zone-alert dedup: ids drop from `knownZones` only when they leave the zones *and* their `userShotAt` grace (3 s) has passed, so a same-id respawn of a shot-down drone never re-alerts. *Note:* the official-alert all-clear is region-latched: it fires only while the focus is still on the region whose alert was ringing (`state.focusToken == officialRegionToken`); switching the focus away to a non-alerting region silently drops tracking (no false all-clear, no lingering siren), and returning to the still-alerting region re-announces fresh. The offline drop is persisted (`offline_pending_since`) so it re-flags after a service kill; evaluates via the shared domain functions, never local formulas. Official-alert lifecycle keys on the RAW episode (`focusOblastAlertRaw`): the City-level scope toggle only gates announce/suppress — flipping it mid-alert never fires a false all-clear nor re-rings (suppression cancels silently; returning coverage announces fresh). Reconnect milestone flags reset **and** the milestone notification is cancelled on the reconnect transition; official-alert notifications track their own notified-state (turning the toggle back on mid-alert re-announces); `ConnectionLog`/`DebugLog` restore is awaited before `NeptunClient.start()`. Notification taps that carry a threat use their own `PendingIntent` request code (1) so the reveal extras can't be clobbered by the plain status/tally/milestone intents (0); the ongoing status title reads "Monitoring GPS" when following and "Monitoring \<city\>" when pinned, and it switches to the offline wording with a Retry action once a drop outlasts the shared grace. A daily 16:20 coroutine checks `UpdateManager` silently and posts one silent "new version available" notification per new build (`last_notified_update_code` pref, `NOTIF_UPDATE`); its tap carries `EXTRA_SHOW_UPDATE` with its own `PendingIntent` request code (4). |
| `BootReceiver.kt` | Restarts `AlertService` on `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED`. |
| `NeutralizedDismissReceiver.kt` | Delete intent for the tally notification (resets the count). |
| `LocationTracker.kt` | `object` singleton. Coarse `NETWORK_PROVIDER` only (~2 min / 250 m), falls back to last known -> `StateFlow<LatLng?>`; tracks the last fix time (`lastFixAtMs`) and offers a `forceRefresh()` GPS one-shot for the shelter screen. A 15-min periodic GPS sync loop runs by default (pref `periodic_gps_enabled`, default on) so Android sees real GPS access and cell drift is corrected. || `BatteryOptimization.kt` | Battery-exemption helpers. |

### Widget

| File | Responsibility |
| --- | --- |
| `widget/ThreatWidget.kt` | Glance home-screen widget (`provideGlance` + `provideContent`, `SizeMode.Responsive`). Passive renderer of the persisted [WidgetSnapshot] - never evaluates zones/tiers itself (mirror rule). Three density buckets (compact 2x1 / standard 4x2 / detailed 4x3) picked from `LocalSize`; dark-only palette; tap opens the app. The primary threat icon (the nearest threat) is its own tap that reveals that threat on the map via the same reveal extras as a notification tap; the status badge mirrors the app-grace-filtered online/offline pill. `ThreatWidgetReceiver` is the manifest-declared `GlanceAppWidgetReceiver`. |
| `widget/WidgetUpdater.kt` | `object` singleton. Started by `AlertService` (the already-running monitor, ~zero marginal battery). Guards on `hasPlacedWidgets()` before starting the pipeline. Combines `ConnectionHolder.getClient(ctx).connectionState/threats/alerts` + `LocationTracker.location` + zone/follow/pin/language/type-gate prefs + a 30s clock → `computeWidgetSnapshot` → persists to the `widget_snapshot` DataStore and calls `updateAll()` only when a widget is actually placed. Persists `primaryThreat` (id/lat/lon/type) so the widget can reveal the nearest threat. Exposes `readSnapshot`/`readLang`/`readIconSet` |
for the widget (icon set mirrors the user's `threatIconSet` pref, so the widget uses `IconCatalog.res(type, set)` 
like the rest of the app). |

### Updates / misc

| File | Responsibility |
| --- | --- |
| `UpdateManager.kt` | `UPDATE_BASE_URL`, `check()`/`download()`/`buildInstallIntent()` (FileProvider); `fetchSheltersJson()` pulls the daily shelter-list copy. |
| `UkraineTileProvider.kt` | Tile provider that refuses to download/cache tiles outside Ukraine (+margin). |

### Build / release

| File | Responsibility |
| --- | --- |
| `app/build.gradle.kts` | Android config + custom tasks: `bumpVersion`, `release`, `uploadRelease`. |
| `app/version.properties` | `versionCode`/`versionName` — source of truth for the build + `version.json`. |
| `server/version.json` | Committed example of the generated update feed. |

## Key invariants

Treat these as a contract. If you change one, update **every** place that relies on it.

- **Onboarding gates on `wizard_completed`, not `language_chosen`.** The dedicated flag is what
  shows the wizard (`UiState.wizardCompleted`, tri-state: `null` = DataStore still loading —
  compose neither map nor wizard), what defers permission requests (`MainActivity`), and what
  re-runs via "Replay first launch" (which clears only this flag). `language_chosen` remains only
  as legacy migration input.
- **No GPS fix + no pinned city = Odesa everywhere.** The camera's Odesa fallback
  (`ODESA_FALLBACK_FOCUS`) and the header/banner/widget attribution (`focusAttribution`'s
  final branch → `Cities.ODESA`) must stay consistent — they intentionally resolve to the same
  city now, including official-alert matching on the Odesa oblast token.
- **Two independent alert paths.** `MainViewModel` (UI) and `AlertService` (notifications)
  each reimplement zone tiering, focus attribution, prediction. A change to `zoneTier`,
  `ZoneParams`, `focusAttribution`, `staleAfterMs`, or `predictPosition` must be mirrored in
  **both** files or UI and notifications drift. The UI pill (`neptunDown`) immediately reflects
  connection drops (no grace filter) so the header and notifications agree; the grace is only
  applied to the connection log. Official-alert **scope** (oblast/city) is shared:
  both call the single `officialAlertActiveFor(...)` gate in `Threat.kt`, and the map's red city
  labels (`redCities`) follow the same gate, so a city-scoped user sees only covered cities lit. Call `zoneTier` from
  `ThreatEngine` — never inline
  it. (Why/mitigation: Deliberate tradeoffs.)
- **Official alert announces once per episode, surviving service restarts.** `AlertService`
  persists the announced episode identity (focus token + NEPTUN `since` + reason threat id) to
  `ZonePrefs` (`officialAnnounced*`), loaded inside `startMonitoring()` before the first tick
  (no startup race), and reconciles it after a START_STICKY restart, so an alert already fired
  before a service kill never re-rings. The persisted identity is cleared only when the episode
  genuinely ends (all-clear / focus switch / alert gone). Re-enabling the official-alerts toggle
  does NOT re-announce a live alert that already rang (an alert that started while muted
  announces naturally). Silent reason refreshes only re-post while `NOTIF_ALERT` is still
  showing (`alertNotificationShowing()`) — a tapped/swiped alert is never resurrected as a new
  siren by a same-episode reason update. When NEPTUN omits `since`, dedup falls back to the old
  in-memory behavior (may re-ring once after a restart).
  Settings are not lifecycle events: the **City level** scope toggle only gates
  announcement/suppression. Each tick computes both the raw episode (`officialAlertActiveFor(...,
  scope = false)` → `MonitorEvent.State.focusOblastAlertRaw`) and the scoped activity; the
  all-clear keys on the raw episode ending, so flipping City level mid-alert never synthesizes a
  false all-clear nor re-rings. Scope-driven suppression (City level switched on without coverage,
  or coverage dropped) cancels the notification silently; returning coverage announces fresh.
- **Night mode is shared, not mirrored.** Both sides call `isNightActive`/
  `effectiveZoneParams`/`effectiveArmed` resolved per tick (`now`); a new
  night knob needs only a pref + a `NightMode.kt` change. Vibration is fixed, not per-night.

- **Widgets read snapshots, never evaluate.** The home-screen widget is a passive renderer of
  `WidgetSnapshot`, computed solely by `WidgetUpdater` via `computeWidgetSnapshot` — which calls
  the engine (`ThreatEngine.evaluate`, `isStale`, `distanceFlat`) plus `focusAttribution`/
  `inOblast`. A change to zone/tier/prediction logic must **not** be reimplemented in the widget
  layer; update `computeWidgetSnapshot` instead. Counts mirror footer-strip semantics.

- **Threat type gating.** `threatMapFlow` gates map rendering, `threatAlertFlow` gates alerts —
  decoupled toggles: map-off doesn't silence alerts; alerts-on auto-enables map visibility (an
  armed alert is never hidden). A type hidden from the map is dropped from the map and the
  footer strip; a type with alerts off stays fully mapped (never dimmed — dimming is
  staleness-only) but is omitted from the footer strip, with a red crossed bell on its popup.

- **Focus point.** `followMe` → camera + zones + alerts centre on GPS, else the pinned city;
  pinning disables follow-me. Attribution via `focusAttribution` → `cityOblast` stem match,
  **major cities only** — the ~300 minors are map-context, never banner/alert. *First-launch
  visual:* before the first GPS fix the **map's** `focusLocation` falls back to Odesa (complete
  first screen) — this is UI-only; `AlertService`/`focusAttribution` never use the fallback, so
  no fake region alert is ever produced.

- **Zone tiering.** `engine.zoneTier(props, distKm, speedKmh, ZoneParams(slowRedKm,
  slowYellowKm, fastRedMin, fastYellowMin))`. Fast types (`ThreatProps.isFast`, via
  `isFastType`) tier by ETA (≤ fastRedMin → INNER, ≤ fastYellowMin → OUTER); slow types by
  distance (≤ slowRedKm → INNER, ≤ slowYellowKm → OUTER). Slow distance is to the **confirmed
  raw fix**, never the dead-reckoned position, so the drawn circles and alerts always agree.
  Speed from the engine's `SpeedCache` (server → measured → nominal); a fast threat with no
  usable speed never tiers. **AVIATION is the exception:** a MiG-31K takeoff alert is a
  country-wide Kinzhal warning — within `reachKm` it always rings INNER regardless of ETA or
  thresholds; the only opt-out is the type's bell. `reachKm` caps distance (KAB 70, FPV 40,
  recon 50, Shahed 1000, else 1500 km). Map
  circles show the slow km thresholds only. Advisory (observation) threats never tier/sound.
  Armed bells are per group×tier (slow/fast × red/yellow), stored for day and night, resolved
  per tick by `effectiveArmed`. **Slider coupling:** the yellow threshold is relative to red —
  setters clamp yellow to `red+2 … max` (slow 1–20 km red / yellow ≤50; fast 1–5 min red /
  yellow ≤20) in `ZonePrefs`; the UI sliders derive the yellow range from the red value live.

- **Expiry / ghosts.** `ThreatProps.staleAfterMs` per type (90s ballistic … 300s UAV). Stale
  threats stay mapped **dimmed** (alpha 0.45, tappable) but are excluded from strip, tiers,
  gauge, alerts. Removal only on server resolve / `remove` frame / `isGhost` (staleness +
  `ghostCapMs` ~30 min). **AVIATION never locally expires:** a MiG-31K takeoff pin sits at the
  launch airbase without fix refreshes, so age-based expiry would kill every real alert before
  it rang — only the server's `status: "stale"` retires it early, and its ghost cap is its own
  (2h) instead of window + 30 min. When the **selected** threat disappears that way,
  `MainViewModel` swaps the popup for the neutralizing card (flips at
  `DEATH_EXPLOSION_START_MS`, fades across the explosion) — but only while the map screen is
  visible and the shelter overlay is down (a background screen never plays the flourish); it
  *does* play during an alert because the selected threat was already on screen; with
  `deathAnimationEnabled` off nothing animates. Dead-reckoning applies only to tracks with a
  real velocity (matching NEPTUN's `flying`), capped per type. ViewModel ticks 1s (`nowFlow`);
  service clears on a 20s grace.

- **Threat facing always matches its motion.** `engine.predictPosition` (dead-reckoning) and
  `engine.courseDeg(nt)` (icon rotation) both resolve the heading via the engine's `motionHeading`:
  the server's authoritative velocity `bearingDeg` first, then the top-level `heading`. (The
  old measured fix-track fallback was dropped with `Prediction.kt` — the speed cache's recorded
  fixes are not fed back into facing.) Dead-reckoning only glides tracks with a real velocity
  (`bearingDeg` + `speedKmh`, i.e. `NormalizedThreat.flying`) — matching NEPTUN; everything else holds
  the raw fix. `courseDeg` keeps the deterministic `fallbackCourse(id)` (NEPTUN's `A(id)`)
  pseudo-course for *stationary* threats that don't glide. A change to heading resolution must
  stay in the engine so the marker never faces a direction it doesn't move.

- **Place names transliterate, never translate.** Any Ukrainian proper noun in the EN UI →
  `Cities.uaToEn` → `Transliteration.transliterate`; military vocabulary is hard-coded
  (`COURSE_PATTERNS`/`COURSE_GLOSSARY`/`ThreatTypeCatalog`). No network translation.

- **REST never clobbers WS.** REST merge keeps the newer record per threat id
  (`updatedAtMillis`); a REST snapshot can be CDN-stale.

- **Official alerts come from NEPTUN only** (the alerts.com.ua backup was removed — a dead
  project). `ConnectionHolder.getClient(ctx).alerts` is NEPTUN's list, held (never cleared) while the socket
  is down: an outage must not fabricate "alert ended" / false all-clear — the truth arrives on
  reconnect. The scope is either oblast-wide (default) or city-level via the shared
  `officialAlertActiveFor(alerts, token, cityUa, scope)` gate (`OblastAlert.coversCity`), used
  by **both** `MainViewModel` and `AlertService` (mirror rule).
  **Connection health is three-tier, not two.** Green online / red offline stay grace-filtered
  as before; the orange **degraded** middle state (`ConnectionState.Degraded`) fires while the
  socket is connected but no frame has arrived for `DEGRADED_STALE_MS` (30 s, below the REST
  refresh and watchdog thresholds) — "threats are delaying". It is advisory only: it never
  suppresses tiering, alerts, or the widget, and offline always outranks it in the pill. The
  reconnect loop itself is network-aware via a `ConnectivityManager.NetworkCallback`: on a
  validated link it stays fast (1–15s), on a provably-dead link it widens to
  `NO_NETWORK_RECONNECT_MS` (60 s) and resumes immediately when the OS reports a network again.

- **Official alert is region-latched, never focus-bound for its end.** The official-alert
  all-clear (both notification and UI banner) must fire only for the region whose alert was
  ringing. Switching the focus away (pin → GPS, or to another city) to a non-alerting region
  must **not** announce an all-clear for the old region nor keep its banner — it silently drops
  the active-alert tracking, and returning to a still-alerting region re-announces fresh.
  Implemented in `AlertService` via `officialRegionToken`; the UI banner (`MainViewModel`)
  always reflects the *current* focus region only.

- **No cloud / no push.** Monitoring is a local foreground service (`specialUse` on API 34+,
  `dataSync` below; the manifest declares both types plus
  `PROPERTY_SPECIAL_USE_FGS_SUBTYPE = "continuous air-raid alert monitoring for user safety"`
  for Play review); alerts stop when it stops. No intermediate server buffers anything.

- **Battery-first location.** Continuous tracking is coarse `NETWORK_PROVIDER` only (~2 min /
  250 m), never fine GPS. A 15-min periodic GPS sync one-shot runs by default (`periodic_gps_enabled`,
  default on) to correct cell-tower drift and give Android real location access; `forceRefresh()` is
  the on-demand precise one-shot for calibration/shelters.

- **Siren channels.** Notification stream by default; alarm stream (DND-piercing) only with
  `sirenOverride`. All-clear never overrides.

## Ownership boundaries

### NeptunConnectionClient

Owns:
- network connection
- frame parsing
- REST/WS merge
- TEMP test toggles: `setForceOffline` (offline simulation, persisted + auto-cleared on Retry) and `setTestMig` (injects a synthetic MiG-31K takeoff from a random launch base into the shared state so the whole alert pipeline — tiering, siren, debug log, flyby — runs for real; session-only, fresh id per enable, self-retires silently after ~20 s like the plane flying on, manual OFF emits a removal)

Must not:
- decide alert tiers
- access UI state
- post notifications

### MainViewModel

Owns:
- UI state derivation
- selected threat
- UI flows

Must not:
- own the WebSocket lifecycle
- directly perform map rendering
- duplicate zone math

### AlertService

Owns:
- background monitoring
- notification lifecycle

Must not:
- depend on Compose
- use UI-only selected state
- implement new zone formulas locally

### MapView

Owns:
- rendering
- map interaction
- visual animation

Must not:
- decide whether a threat should alert
- persist application state

## Deliberate tradeoffs / risks

### Mirrored UI and alert evaluation

`MainViewModel` and `AlertService` both drive evaluation through the engine.

- **Why:** the UI needs continuously refreshed state; background monitoring must continue
  independently of the UI.
- **Mitigation:** both consumers call the engine — `ThreatEngine.evaluate(...)` /
  `computeProximity(...)` — on their own instance built from the shared `typeCatalog`
  (`AppPluginHolder.registry.typeCatalog.value`). The engine owns zone tiering, dead-reckoning,
  staleness/ghost rules, scoring and proximity; neither consumer re-implements a decision
  formula. Any change lands once, in `engine/`, and both paths are covered by `ThreatEngineTest`.
- **Note:** each `ThreatEngine` carries its own `SpeedCache` (speed history is per-consumer,
  not a shared singleton). Speed fallback is deterministic (server → trail → nominal), so
  consumers can't disagree near a zone boundary.

### Flat package

All code in one flat package — deliberate for now (Package structure). Cost grows with file
count; migrate by subsystem when it outgrows maintainability.

### Foreground service instead of backend/push

No intermediate server buffers anything, so nothing is missed server-side — but the app must be
kept alive (hence the battery-exemption flow), and alerts stop when monitoring stops.

### Direct third-party API dependency

NEPTUN is consumed directly, parsing isolated in `Threat.kt`. Risk: upstream schema/contract changes. The REST/WS merge protects against
CDN-cached staleness.

### `ZonePrefs` is becoming a god object

It owns every preference plus serialized `ConnectionLog`, offline-restore state
and onboarding flags. Split candidate:

```
AppPrefs
├── MapPrefs / ThreatPrefs / AlertPrefs / NightPrefs / UiPrefs / SystemPrefs
```

with persisted operational state moved out:

```
ConnectionLogStore
```

The doc distinguishes **preferences** from **persisted application state**.

## Failure modes

| Failure | Behavior | Owner |
| --- | --- | --- |
| NEPTUN offline | Offline pill + monitor notification with Retry; `retryNow()`; last-known oblast alerts held; drop persisted (`offline_pending_since`) across restarts; reconnect start persisted for milestone continuity. | `ConnectionHolder`, `AlertService` |
| Stale threats | Dimmed on map (alpha 0.45); excluded from tiers/alerts/strip/gauge; ghosts removed after ~30 min. | `engine/ThreatEngine.kt` + consumers |
| Service process interrupted | Recovery depends on Android's foreground-service lifecycle; connection log + debug log restored from DataStore when restarted. | `AlertService`, DataStore |
| Reboot | `BootReceiver` restarts the service on `BOOT_COMPLETED`. | `BootReceiver` |
| Package replaced | `BootReceiver` restarts the service on `MY_PACKAGE_REPLACED`. | `BootReceiver` |
| Location unavailable | Falls back to last known / pinned city; `focusAttribution` uses the pin. | `LocationTracker`, `Cities` |

## Testing

JUnit unit tests in `app/src/test/java/ua/ukrainedrones/`. Invariant → test: the engine
(`zoneTier`, `predictPosition`, staleness/ghost, scoring) is pinned by `ThreatEngineTest`, which
both UI and service consumers rely on.

- `engine/ThreatEngineTest.kt` — `evaluate` zoning, `zoneTier` tiering, `predictPosition`,
  `motionHeading`, `isStale`/`isGhost` caps, scoring, AVIATION override, null-speed fast.
- `engine/TypeMappingTest.kt` — `ThreatType`↔String, `Threat`↔`NormalizedThreat` round trip.
- `plugins/PluginRegistryTest.kt` — plugin merging, `typeCatalog`.
- `CitiesTest.kt` — city-list integrity; majors-only `nearestCity`/`focusAttribution`.
- `ThreatTest.kt` — JSON parsing, type mapping, course translation.
- `TransliterationTest.kt` — КМУ №55 romanization, no semantic translation, digraph rules.
- `UpdateManagerTest.kt` — `versionNameGreater`.
- `NeptunClientTest.kt` — reconnect backoff (`NeptunConnectionClient.calculateBackoffMs`), `ConnectionState` degradation.
- `NightModeTest.kt` — night-window resolution + effective params/armed.
- `ConnectionLogTest.kt` — episode-commit rules (grace window, blips, recovery, ring-buffer cap).
- `DebugLogTest.kt` — serialize/parse round trip, ring-buffer cap, auto-clear, `computeSweep` verdicts (fired/coalesced/bell-muted, steady-state dedup, tier escalation, exits, stale/type-off region rows).
- `VibrationTest.kt` — `vibrationPattern` levels.
- `StringsFormatTest.kt` — `formatDateTime` per-language correctness.
- `TestThreats.kt` — shared `threat(...)` builder helper.

Run: `.\gradlew.bat :app:testDebugUnitTest`

## Build & release

- `.\gradlew.bat :app:assembleDebug` — debug APK (no secrets needed).
- `.\gradlew.bat :app:release` — bumps version, builds release APK, uploads APK + generated
  `version.json` over FTP. Requires git-ignored `app/keystore.properties` (signing) and
  `app/upload.properties` (FTP creds). Release notes from `notes_en.txt` / `notes_ua.txt`.
- Full release workflow is documented in `AGENTS.md` ("release it").

## State plumbing / recomposition contract (perf)

How hot data reaches the UI without recomposition storms (added 2026-08; keep in sync with
MainViewModel.kt):

- **UI samples the stream.** MainViewModel reads `combine(connectionState, threats, alerts).sample(120)` for the UI path only; AlertService keeps consuming the raw flows (mirror rule unaffected).
- **Clock is not state.** 
ow (1s ticker) and lastFrameAt are separate StateFlows on the
  ViewModel, collected only by consumers that need them. They are NOT fields of UiState, so a
  no-op second tick is dropped by StateFlow equality instead of rebuilding the whole tree.
- **Selection is not UiState either.** Selected threat / proximity / neutralized card /
  fake-neutralize live in selectionUi: StateFlow<SelectionUi> (+ derived selectedThreatId
  for the map marker highlight). Only ThreatCardHost collects it, so tapping a threat never
  recomposes header/map/footer. FlourishPolicy gating moved verbatim into that chain.
- **Off-main state building.** The uiState combine chain ends in .flowOn(Dispatchers.Default)
  before stateIn; uildUiState must stay free of main-thread/Android-UI dependencies.
- **Stability.** Model classes passed to composables (Threat, ThreatProximity, City,
  ZoneParams, LatLng, night types, flourish types, RevealRequest) are annotated
  @Immutable; UiState itself too. MapView's overlayKey is memoized via
  
emember(<fields it reads>); staleness dimming happens in-place in the 1s marker loop,
  never through the key.
- **Popup clock is a leaf.** ThreatPopupCard's elapsed-time text comes from
  ThreatElapsedText, a small composable with its own 1s clock — the card body does not
  recompose per second.
