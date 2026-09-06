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

Source files are grouped into subdirectories by subsystem (`data/ domain/ engine/ plugins/
connection/ service/ ui/ widget/ flourish/ theme/ lang/`). The UI/domain/widget/flourish/lang
layers share the root package `ua.ukrainedrones`; the subsystems keep their own packages
(`ua.ukrainedrones.engine`, `.plugins`, `.connection`, `.service`, `.theme`, `.data`) where
isolation matters (the engine kernel, the plugin SPI, the connection layer). There is no
single flat package — sub-packages are already the norm; keep adding files inside their
subsystem's package rather than the root.

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
LocationTracker ──┬──► MainViewModel        NightMode.kt / Cities.kt (resolveFocus)
                  └──► AlertService
```

## Core ownership

| Concern | Source of truth | Consumers |
| --- | --- | --- |
| NEPTUN connection | `NeptunConnectionClient` (via `ConnectionHolder`) | UI, `AlertService` |
| Connection state machine | `ConnectionState` sealed interface | UI, `AlertService`, widget |
| Network validation | `NetworkMonitor` | `NeptunConnectionClient` |
| Official oblast alerts | `PluginRegistry.allAlerts` (priority-ordered sources, takeover merge; primary = `NeptunConnectionClient.alerts`, fallback = `UbillingPlugin`); **derivation** (`officialAlertActiveFor`, `redCities`, `raionName`, reason) owned by `engine/` | UI, `AlertService`, widget |
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

- **Threat ingest.** NEPTUN WS + REST merge in `NeptunConnectionClient` (via `ConnectionHolder`) → separate `StateFlow`s: `connectionState`, `threats`, `alerts`; `removedThreats` SharedFlow for map death animations. `PluginRegistry` merges plugin feeds into `allThreats`/`allAlerts` (takeover semantics); consumers read the registry, never a specific source. Consumers read flows directly — no intermediate `NeptunState`. NEPTUN's alerts carry the whole-oblast/region split: each `OblastAlert` is tagged `wide` from the `raions`/`oblasts` arrays, so map coloring is region-precise (fill = wide only; city labels = `coversCity`) instead of guessing from the name.
- **Position prediction** (both consumers): the full contract — `SpeedCache` speed priority,
  `predictPosition` dead-reckoning gates (`canDrift` = fresh + `flying`, server-coursed only),
  `motionHeading`/`courseDeg` facing chain, per-type horizon/ghost caps, slow-distance vs
  fast-ETA tiering — is the **engine behavioral contract** in `BEHAVIORS.md`; `ThreatEngineTest`
  pins it. App-side facts: MapView glides markers on its own 1s tick (30fps tween); the service
  clears on a 20s grace.
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
| `connection/NeptunConnectionClient.kt` | High-reliability WebSocket client for the NEPTUN telemetry feed. Strict generation-based lifecycle (`AtomicInteger`) prevents stale socket callbacks. Asynchronous channel processor dispatches frames → separate `StateFlow`s: `connectionState`, `threats`, `alerts`, `removedThreats`, `threatDataStale`; reconnect backoff 1–3 s → capped 15 s (`calculateBackoffMs`, tested); keep-alive pings + 45 s watchdog. Official-alert clears are **debounced** (`ALERT_CLEAR_CONFIRM_MS` = 30 s): an empty alerts frame holds the last-known list until the clear persists, so a momentary feed gap can't flip an active alert off and back on. `markUserShot(id)` / `wasUserShotRecently(id)` for map death-animation grace. `recordEvent` / `dismissConnLog` for the connection-log card. Isolated `TestHarnessImpl` for MiG injection + force-offline. |
| `connection/NetworkMonitor.kt` | Validated network observer via `ConnectivityManager.NetworkCallback`. Emits `isValidated` StateFlow. On validation, kicks `onValidatedReturn` callback (triggers fast reconnect in client). |
| `connection/ConnectionSupervisor.kt` | Milestone tracker for offline episodes. Compares `offlineSinceOrNull` to thresholds (3/5/6/10/20 min) and emits milestone `ConnEventKind` entries. |
| `connection/ConnectionHolder.kt` | Lazy singleton holder for `NeptunConnectionClient` + `ConnectionSupervisor`. `getClient(context)` / `getSupervisor(context)` / `clear()`. Avoids startup race — MainViewModel constructs before `AlertService.onCreate` runs. |
| `NeptunClient.kt` | `object` holding shared constants (`OFFLINE_GRACE_MS`, `DEGRADED_STALE_MS`, `USER_SHOT_GRACE_MS`, `NEPTUN_DOMAIN`) and data classes (`ConnRetryState`, `ConnEventKind`, `ConnEvent`, `ThreatRemoved`). |
| `Threat.kt` | NEPTUN display metadata + JSON parsing: `ThreatType`/`ThreatTypeCatalog`/`Reliability` (labels, staleness, nominal speeds), `translateCourseAssessment` (EN course text, word-level common-word translation), `normalizedThreatFromJson` — NEPTUN JSON → `NormalizedThreat` directly (the engine currency; no `Threat` display DTO). The alert currency + matching gates moved out to `engine/OblastAlert.kt`. |

### State / orchestration

| File | Responsibility |
| --- | --- |
| `MainViewModel.kt` | `AndroidViewModel`. Combines NEPTUN + GPS + prefs (flourish data/policy live in `flourish/Flourish.kt` — `FlourishRecord`/`FlourishShow` and the `FlourishPolicy` gates used by `buildUiState`) → `StateFlow<UiState>`; drives the update flow (daily start check, a Settings-open check that raises `updateReminderTick` — a snackbar with a Download action — instead of a clickable toast, which Android can't make touchable, plus manual check/download/install); `neutralizeThreat` long-press hook. Reads `ConnectionHolder.getClient(app)` for `connectionState` and the registry for `threats`/`alerts` (`registry.allThreats`/`allAlerts`); sampled at 120 ms for UI. `neptunForUi` is a `combine(connectionState, threats, alerts)` → `.sample(120)`. All zone/tier/prediction and official-alert facts come from one `engine.evaluate(...)` (mirror rule); the UI keeps only display projections (`alertOblastTokens`/`alertRaionKeys` for the red region fills — engine-derived via `computeFillKeys`, `alertingOblastCount` for the Logs header) plumbed from the engine's alert gates. No global wall-clock StateFlow — consumers with time-based UI run their own scoped clocks (see the recomposition contract below). Derives `UiState.protectionState` (`ACTIVE`/`REDUCED`/`OFFLINE`) via `deriveProtectionState` from monitoring liveness, notification permission, armed channels, official alerts, critical-offline override, silenced types, and NEPTUN state — the single source for the header status chip. |
| `ConnectionLog.kt` | `object` singleton. Persisted ring buffer (last 10 episodes) fed by the watchdog; commits drops only past `OFFLINE_GRACE_MS`. `ConnStatus` = ONLINE/OFFLINE (no backup state). `ConnLogEntry.activeSource` records which source owned the alert feed during the episode (null = primary). Pure `commitLogState` (tested); rendered in the Logs screen. Non-blocking persistence (no `runBlocking` on IO). |
| `DebugLog.kt` | `object` singleton. Persisted audit trail (last 500 decisions, rolling 24h window) written by `AlertService`, read by the Logs screen. Records every alert/threat decision in the active region — official on/off, zone entries, region threats — with day/night and effective sound, whether a notification was shown and why not. `DebugLog.sweep` runs once per service tick and is **read-only for the decision path**: it describes the service's own computed maps (`zoneThreats`/`alertable`/`knownZones`/`postedId`), never re-derives formulas. Pure `computeSweep`/serialize/parse (tested). Non-blocking persistence (no `runBlocking` on IO). *Note:* the whole feature is additive — removing it is deleting the write hooks + this object + `DebugLogScreen`. |
| `Shelters.kt` | Odesa shelter dataset: `Shelter`/`NearestShelter` (adult ~5 km/h, kid ~3 km/h walk minutes), `ShelterIndex` (JSON parse, Odesa bbox, nearest ranking). |

### Engine kernel (source-agnostic)

| File | Responsibility |
| --- | --- |
| `engine/NormalizedThreat.kt` | Mapping-library-agnostic `LatLng`, `TrailPoint`, `NormalizedThreat` (source-agnostic threat model; `type` is a plain String, `flying` gate), `fallbackCourse` (NEPTUN's `A(id)` pseudo-course). |
| `engine/ThreatProps.kt` | `ThreatProps` (isFast, reachKm, alwaysInnerWithinReach, staleAfterMs, ghostCapMs, nominalSpeedMps, horizonSec, maxGhostMeters), `DEFAULT_THREAT_PROPS`, `NEPTUN_TYPES` catalog. |
| `engine/Distance.kt` | Haversine `distanceHaversine`/`bearingHaversine`; equirectangular `distanceFlat`/`bearingFlat` (short-range display basis). |
| `engine/SpeedCache.kt` | Engine-internal per-threat fix queue → measured speed/heading; `SpeedSource` (RECORDED/TYPICAL). |
| `engine/OblastAlert.kt` | Source-agnostic alert currency + matching gates: `OblastAlert`, `inOblast` (prefix or whole-word, so Crimea's republic form hits the "Крим" stem), `isOblastWide` (NEPTUN tag, fallback name heuristic), `coversCity` (4-char stem match; oblast-wide covers every city), `officialAlertActiveFor` scope gate. Engine-owned (moved from `data/Threat.kt`). |
| `engine/ThreatEngine.kt` | The core: `evaluate` (inner/outer zones, mapThreats, scores, activeZone, threatLevel, **plus the engine-owned official-alert outputs** `redCities`/`focusOblastAlertActive`/`officialReason`/`reasonThreatId`), `zoneTier`, `predictPosition` (drift capped by a **distance** floor `DRIFT_MAX_METERS` ≈ 5 km so a marker never crosses the country while a track sits quiet), `motionHeading`, `isStale`/`isExpired`/`isGhost`, `canDrift`, `computeProximity`, `scoreThreat`/`aggregateScores`, `computeRedCities(alerts, fillRegions)` — region-precise labels (wide alerts cover the whole oblast; raion alerts cover their raion's cities when the fill is on, else the whole oblast), `computeFillKeys(alerts, redCities, fillRegions)` — the region-fill keys derived from the same alert→city coverage, so every red city sits on a filled polygon (`fillOblastTokens` whole-oblast fills + `fillRaionKeys` per-city raion fills, raions only when a boundary polygon exists), `deriveOfficialAlertReason`; `ThreatZone`, `ZoneParams`, `ThreatEvaluationResult`, `ThreatProximity`. |
| `engine/ThreatSource.kt` | `ThreatSource` plugin interface + `PluginConnectionState` (source-agnostic connection state) + `SourceType` (WS/REST) + `OperationalMode` (STREAMING/POLLING/STANDBY) + `enabled: StateFlow<Boolean>`/`setEnabled` (Sources-tab switch; false stops the source and clears its alerts) + `badgeLabel: String?` (optional override badge shown instead of WS/REST; `null` = default) + `testConnection()` (`SourceTestResult` — live one-shot check for the Sources tab Test button; REST does a real fetch, WS reports state/freshness). |
| `engine/TypeMapping.kt` | `ThreatType`→engine string (`toEngineString`). No reverse mapper — `NormalizedThreat` is the app-wide currency; NEPTUN JSON parses straight to it (`data/Threat.kt`). |
| `engine/TypeBridge.kt` | App↔engine bridge: `String.toThreatType()`, `threatTypeInfoByString`, `isFastType(type)` (from `ThreatProps.isFast`), `typicalSpeedKmh(type)` (from `nominalSpeedMps`). |
| `engine/OblastUtils.kt` | Geographic/text utilities (kept from the old `ThreatEvaluator`): `inOblast`, `inFocusOblast`, `matchOblast`, `canonicalToken`, `threatBody`, `alertRegionName`, `OblastMatch`. *Note:* `alertRegionName` renders the alert's region in the UI language (UA raw / EN transliterated) for the official-reason fallbacks; the nearest-in-zone reason picker lives on `ThreatEngine.deriveOfficialAlertReason`. |
| `plugins/NeptunPlugin.kt` | Wraps `NeptunConnectionClient` as a `ThreatSource` (`sourceType = WS`, `operationalMode = STREAMING`). |
| `plugins/UbillingPlugin.kt` | REST alert fallback (`sourceType = REST`). Polls `https://ubilling.net.ua/aerialalerts/` with **adaptive** intervals: idle while the primary is healthy or within a 5-min grace; past grace → 15s foreground / 30s background. Self-tracks the offline duration from `registry.wsHealthy`; exponential 2s→8s backoff on failures; `alertnow` states → `OblastAlert`; `since = null`. Clears alerts on recovery/disable so it hands ownership back to the primary. On every successful poll, a structural schema fingerprint (sorted nested-key hash of the JSON response body) is compared to the last-known hash in `ServiceState`; a mismatch is recorded as `UBILLING_SCHEMA_CHANGED` in `ApiMonitor` and pushed to Telegram — same pattern as the NEPTUN SDK manifest check. |
| `plugins/TestPlugin.kt` | Peace-time simulator (`sourceType = WS`). While enabled it fetches `testplugin.json` from the update server and plays a timed script of threat/alert events (random bursts aimed at the focus, a MiG-31K AVIATION takeoff that auto-triggers the flyby, fake oblast alerts, clear events). Reports CONNECTED while running; `stop()`/disable clears its output — exactly like a real source. `typeCatalog` empty (inherits NEPTUN_TYPES via registry merge). Disabled by default. |
| `plugins/PluginRegistry.kt` | Health authority: manages plugins, merges threats/alerts with **takeover** semantics (authoritative source's snapshots are sole truth; stale holders fill only when nothing is authoritative), exposes `perSourceState`, `wsHealthy`, `activeAlertSource`, aggregate `connectionState`, merged `Threat` feed + `typeCatalog`, and a `sourceEvents` SharedFlow (toggles + alert-owner handovers) feeding the live connection log / Sources-tab activity. For alerts only a **CONNECTED** WS socket is authoritative — `DEGRADED` (quiet >30s) is stale data and falls back to the union-hold instead of owning the feed. REST sources activate off `wsHealthy`, never by observing a sibling plugin. Threats merge by concatenation; alerts by oblast key, so a Test-source fake alert only owns oblasts no live source covers. |
| `AppPluginHolder.kt` | Singleton holding `PluginRegistry`; registers Neptun (WS) then Ubilling (REST) then Test (WS, disabled by default); exposes `appForeground` (drives REST cadence) + `setAppForeground`. Initialized in `AlertService.onCreate` and `MainViewModel.init`. |

### Domain logic

| File | Responsibility |
| --- | --- |
| `NightMode.kt` | Shared night helpers for **both** consumers (mirror rule): `isNightActive`, `effectiveZoneParams`/`effectiveArmed`, `NightConfig`/`NightZones`/`ZoneArmed`. |
| `Cities.kt` | ~483 places grouped by oblast in three zoom tiers (`CityTier`: 26 curated MAJOR always / MEDIUM from mid-zoom — 14 curated non-seats only / rest MINOR up close; non-curated places derived from GeoNames CC BY 4.0 via `tools/gen_cities.ps1`, 2 km dedupe, same-name towns resolve to the major city first, then by population) + `CityLabelOverlay` (colors labels red for the `redCities` set — region-precise: whole oblast for a whole-oblast alert, covered cities only for a raion alert; a red-filled polygon keeps its labels white; MAJOR labels reveal progressively by `MajorReveal` — top-5 OVERVIEW from the country view, 8 MID from mid-zoom, the rest LATE up close); EN names from the app's own КМУ №55 transliteration; `resolveFocus` maps focus point → oblast stem via `cityOblast` (majors only). |
| `CityRaions.kt` | Auto-generated `cityRaion: Map<cityUa, raionAdjectival>` — each city's post-2020 raion (e.g. "Дніпро" → "Дніпровський"), assigned by point-in-polygon against OSM admin_level=6 boundaries, matching NEPTUN's raion alert keys. Generated by `tools/gen_cities_raion.py`. |
| `OblastBoundaries.kt` | Simplified oblast boundary polygons (RDP ~0.01° from EugeneBorshch/ukraine_geojson GeoJSON). `byStem` maps 25 oblast stems to rings of `[lat, lon]` pairs. Used by `MapView` to draw subtle red fills on alerting oblasts when the `fillAlertRegions` toggle is on. Generated by `tools/gen_oblast_boundaries.py`. |
| `RaionBoundaries.kt` | Auto-generated post-2020 raion boundary polygons (OSM admin_level=6 via Overpass, RDP ~0.01°). `forKey(oblastStem, raionName)` resolves a raion's rings, scoped by parent oblast because raion adjectival names repeat across oblasts; keys match `CityRaions` and NEPTUN's raion alert names (case-insensitive). Used by `MapView` to shade the alerting raion when `fillAlertRegions` is on and the alert is raion-level. Generated by `tools/gen_raion_boundaries.py`. |
| `GeoConstants.kt` | Shared geographic constants: Ukraine bounding boxes (tight for UI clamping, wide for tile coverage), Odesa city-centre fallback coordinates. Used by `DeathFxController`, `UkraineTileProvider`, `MapView`, `MainViewModel`. (Root package, not `domain/`.) |
| `Transliteration.kt` | Official КМУ №55 Ukrainian→Latin romanization (the EN gate). |
| `ZonePrefs.kt` | `AppLanguage`/`ThreatCardSize`/`ThreatIconSet`/`OverlapMode` + DataStore store (`zone_prefs`): all toggles/thresholds/language/follow/pin/visibility, night config, and — problematically — serialized `ConnectionLog`, offline-restore state, onboarding flags. `haptics_enabled` is tri-state (absent = follow the system haptic setting). Also `threatMapFlow`/`threatAlertFlow`; the resolved-threat tally's focus-oblast default + "All of Ukraine" opt-in (`neutralized_tally_all_ukraine`), and the daily-update notify marker (`last_notified_update_code`). *Note:* god object mixing prefs with persisted state — split candidate (tradeoffs). |
| `Strings.kt` | UA/EN `StringSet` table (never Android resource localization); `formatRelativeTime`, `formatDateTime` (app language, not device locale). |
| `WidgetSnapshot.kt` | `WidgetSnapshot` + pure `computeWidgetSnapshot(...)` — deterministic projection of threat state for the widget, computed via the engine (`ThreatEngine(NEPTUN_TYPES).evaluate`, `engine.isStale`, `distanceFlat`, `resolveFocus`); `officialAlert` comes from `eval.focusOblastAlertActive`. Counts + per-type `typeCounts` mirror the footer-strip semantics; `primaryThreat` = nearest live threat (id + position) so the widget can reveal it; `sourceOnline` is grace-filtered like the app pill. Takes `ConnectionState` + `Map<String, NormalizedThreat>` + `List<OblastAlert>` directly (no `NeptunState`). Tested by `WidgetSnapshotTest`. |
| `IconCatalog.kt` | Single source for threat icons: vector/photo/army/comic/russian sets, per-set facing (`baseDeg`), `ThreatIcon` composable; assets in `app/src/main/iconpacks/`. |
| `Toasts.kt` | Shared toast helper: one function decides placement — top (below the header banner, via `ToastHost(topInset)`) normally, bottom (above the floating zone/shelter buttons) when a card/popup is visible. Dark themed pill. Callers never hardcode gravity. |
| `Compat.kt` | *(deleted — Session 6)* engine `LatLng`/`ThreatZone`/`ZoneParams` are now imported directly (`ua.ukrainedrones.engine.*`) instead of root-package typealiases. |

### UI (Compose)

| File | Responsibility |
| --- | --- |
| `MainScreen.kt` | Top-level Compose UI: header, alert banner, map, threat strip, `ZonesSheet`, `UpdateDialog`, first-run wizard + battery prompt. *Note:* wizard gated on a dedicated `wizard_completed` pref surfaced as tri-state `UiState.wizardCompleted` (`null` = DataStore not loaded yet → blank dark frame, neither map nor wizard composes; `false` → wizard-only screen, map is not even composed so no tile flash); "Replay first launch" keeps the map composed beneath (`wizardFromSettings`). While the wizard is up `mapVisible` is false so strikes/haptics/replays are suppressed; card flip timed to `DEATH_EXPLOSION_START_MS`; popup height feeds the map as `popupCoverPx`; the tally-tap replay flourish closes every modal and forces the map screen. The auto-shootdown countdown strip shows the number of strikes actually pending/in flight (`DeathFxController.pendingStrikeCount`), not the count of threats still on the map. The bottom region is a single decision point driven by `flourishActive` (countdown / auto-strike / death / replay / flyby, all equal members): when true the threat strip is hidden and `flourish/FlourishFooter` owns the region (left-aligned Stop pill, whole bar tappable), ejecting to a non-fun map via `cancelTick` → `DeathFxController.clear()` + `ejectAllFun()` (flyby cleared, card NOT opened); when false the pure-threat `ThreatStripFooter` shows (icon pills, or the calm "no threats" message passed in as a precomputed string). Floating zone buttons also hide during any flourish via the same `flourishActive`. Header branches on `UiState.protectionState`: `OFFLINE` → the full-tap "SERVICE OFFLINE" banner; `REDUCED` → an amber "Reduced" chip; an INNER-zone live alert with the siren override off shows an amber "siren follows system volume" chip. |
| `ConnectionStatus.kt` | Connection pill (online / degraded-orange / offline) + `SystemStatusDialog` (hosted in `MainScreen`): per-source dot, the three-tier connection legend, the NEPTUN attribution link, and a prominent "Logs" button opening the Logs screen. The orange middle state mirrors `ConnectionState.Degraded` — connected but the stream is quiet (threats delaying). |
| `Haptics.kt` | Global press-haptics: `LocalHapticsEnabled` CompositionLocal (provided from the `hapticsEnabled` pref at the MainScreen root) + `Modifier.pressTick(source)` — vibrates via `LaunchedEffect` when the element's own `MutableInteractionSource` reports pressed (the same signal as its press animation; pointer-event listeners proved unreliable here). The source must be **shared** with the element's clickable/toggleable. Raw `Vibrator`, short one-shot at full amplitude (`USAGE_ALARM` on API 30+ — same always-on channel as the shoot-down flourish) because Compose's haptic API is muted by system touch-feedback settings and predefined `EFFECT_TICK` is a silent no-op on many OEMs. Also hosts `animationsOff()` (zero animator scale → snap instead of animate) and the imperative `hapticTick()` for non-Compose tap sites. Applied across map controls, settings rows, and popup cards. |
| `LogsScreen.kt` | Full-screen Logs: one card list over decisions (the audit trail) and connection episodes, switched by chips (Decisions / Connections / Sources / System / Tests). A **Sources** tab lists every registered alert source with its type (WS/REST), real `PluginConnectionState`, operational mode (Streaming/Polling/Standby), an enable switch (`setEnabled`), and a **Test** button that runs `testConnection()` and shows the result inline, plus a live activity feed (toggles + takeovers); connection cards tag `activeSource` on fallback episodes. A **Tests** tab lists live per-source `testConnection()` results (ok/summary + timestamp) for every registered source, with a rerun button each; the battery-OEM simulator lives on the **System** tab. The generic empty-state is skipped for Sources/Tests (they render their own content/empty text). The Decisions tab offers group-by (Timeline / Proximity = official, red zone, yellow zone, in-oblast, left / Type), a standard sort-direction icon toggle (newest/oldest) that applies within every grouping, and a "shown only" switch (only rows where a notification was actually shown); controls stay visible even when the list is empty so the shown-only switch can be flipped back. A double-arrow reveals more rows; a leading per-threat-type icon on threat rows (red trident = official on, green check = all-clear), an "ago" + absolute timestamp, day/night + effective sound, "Notification shown" or "No notification — \<reason\>", Clear button. |
| `MapView.kt` | `NeptunMapView` + `DARK_TILE_SOURCE`. Owns OSMdroid rendering: zone circles, marker overlays, course rotation, dead-reckoned positions. Markers store `threatMarkerRotation` (`-(heading − icon base)` — osmdroid renders `marker.rotation` negated) so each nose tracks its travel. A marker's pose is a pure function of the wall clock: `resolveThreatPose` decides parked (raw fix) / orbit (approx threats patrol their ring — fast types the red ring, slow the yellow — with tangent heading) / drift (`predictPosition`, reported course); one adaptive loop recomputes every marker's pose each frame while anything moves (30fps), idles at 1s otherwise, freezes writes while the map is hidden/touched (ground-fixed during pan, resumes from the live clock on release), and the rebuild shares the same resolver (in-place marker moves, no full rebuild); the shoot-down visuals/camera/haptics/replay delegate to `flourish/DeathFxController` (the death-anim collector is subscription-gated on `deathAnimationEnabled` and focus-gated on `mapIsUserFocus(paused, mapVisible, shelters, lifecycle)`, and a functional grey lost-dot collector runs regardless). Threat icons scale with map zoom by default (1x→3x across only the final ~3 zoom levels before `NORMAL_MAX_ZOOM`, 8dp-bucket quantization, driven live from the map's zoom listener so size tracks the gesture); a display toggle (`threat_icon_zoom`) pins them to a constant 32dp. Same-coordinate threats de-overlap per the `OverlapMode` setting (`deOverlapThreats`, deterministic screen-space spread: DEFAULT stacks, GRID spreads on a 2D grid, SPREAD fans half-overlapping, COUNT collapses same-type stacks into a counted representative with mixed types auto-gridded). Long-pressing a threat shoots it down for fun: `markUserShot` + death animation, the marker stays hidden while the animation plays and the rebuild redraws it afterwards (the object itself is never removed); the strike camera (`followStrike`) glides onto the target and — since the camera-return rework — pans back to where the user was after the explosion, with a fresh strike replacing any pending return. Long-press is disabled while an alert is active. With the `fillAlertRegions` toggle on, alerting oblasts (`OblastBoundaries`) and alerting raions (`RaionBoundaries`, via `alertRaionKeys`) get a subtle red polygon fill and their covered cities' labels stay white; the country outline is the land-border `Polyline` (`UKRAINE_LAND_BORDER`). The reveal marker is a small green **dot baked into the threat icon's top-right corner** (a single tappable marker — no separate overlay intercepting the tap) and a grey "lost" dot marks where a threat just vanished. Selecting a threat opens its popup without moving the camera. Threat-marker taps are handled by an `InstantThreatTapOverlay` on the immediate `onSingleTapUp` (osmdroid's own `onSingleTapConfirmed` waits out the ~300 ms double-tap window, which read as lag before the haptic + card); it never consumes the touch stream and the markers' consume-only click listeners absorb the late confirmed tap so it never falls through to the map-tap overlay. Shelter markers are hand-drawn teardrop pins (stroke-only, per-type color, white when selected) anchored tip-on-spot; tapping a shelter opens its card without moving the camera (only the shelter-list button zooms). The GPS dot is gray while "locating" (before the first fix) and blue once a fix exists. *Note:* shelter-mode zoom + deep-zoom unlock run in a dedicated `LaunchedEffect` (not the recompose-driven update block) so the fit fires reliably after the shelter markers are placed; normal zoom is capped at `NORMAL_MAX_ZOOM` (14.5, the ~5 km viewport) and only raised to `SHELTER_MAX_ZOOM` (19) while the shelter overlay is up — keeping the tile cache to what the threat map needs; zooming below `SHELTER_AUTO_EXIT_ZOOM` (13) while shelter mode is up auto-exits it. The resolved-threat death flourish (`deathFx`) is skipped while the shelter overlay is visible (`showNearbySheltersState`) and, during an alert, only when the resolution is off-screen — in-camera resolutions still play. `overlayKey` excludes raw positions so live movement updates in-place; must not perform alert decisions. The tally-tap replay flourish groups the remembered resolutions by viewport-adaptive proximity (`clusterFlourish`, threshold approx 1/3 of the current viewport width), zooms onto each group in turn, fires its bullets, then returns home; each bullet vibrates short-on-shot / longer-on-detonation, and `deathFx.active` is surfaced via `onDeathActiveChange` so the footer can swap its copy. the reveal framing always pins the revealed threat to the lower viewport so it never sits under the top popup card. |
| `UkraineBorder.kt` | Two static outlines generated by `tools/gen_ukraine_border.py`: `UKRAINE_BORDER` — the closed outer hull of the oblast boundary polygons (`OblastBoundaries.kt`), same source + simplification, the silhouette coinciding with the combined red fills (also drawn as the red "Fill alerting regions" toggle icon); and `UKRAINE_LAND_BORDER` — the same ring with every sea-coastline edge removed (midpoint-in-ocean test vs Natural Earth 10m), chained into an open polyline. `MapView` renders the land border as an osmdroid `Polyline`, so the outline hugs land/river borders and never crosses the sea. |
| `SettingsScreen.kt` | Collapsible sections: language, map centre, per-type Map/Alerts toggles + icon packs + icon zoom, card size, alert toggles, the **Just Fun** section (last card: calm messages, shoot-down animation + follow-the-bullet, neutralized count + all-Ukraine — calm messages is effective only when the Just Fun master is on), night-mode card, updates, battery exemption, guide; one-time explainers. The night-mode section tints its whole collapsible card darker purple + border (`NightSectionBg`/`NightSectionBorder`), no moon icon on the enabled toggle; battery exemption lives in the Alerts section; shelter button toggle + shelter directory row live in their own dedicated **Shelter** section; sections stay user-collapsible even while searching; a Reset-tips row re-arms every first-use hint (toast counters + explainers); icon packs ship PHOTO/ARMY/COMIC/RUSSIAN only (CLASSIC removed — a stored `"CLASSIC"` pref falls back to PHOTO, classic vectors remain the internal pack-fallback); the card-size tiles preview the small card as its real compact top-left chip (~75% of tile width) and the large card full-width. |
| `ZonesSheet.kt` | "Edit zones" sheet: Slow (km)/Fast (min) sliders + per-zone bells; edits day or night values depending on the active window; night rows carry day reference ticks. |
| `ThreatPopupCard.kt` | Threat popup (small chip / large card); `AlertsOffChip` when type alerts are off; neutralized/neutralizing variant. The ETA pill's blue GPS dot mirrors the map location dot (same core + white ring, subtler radial glow). |
| `ThreatTogglePanel.kt` | Shared Fast/Slow grouping, `ToggleChip`/`IconToggle`, `SlimThreatToggles` (reused by first-run dialog + Settings). |
| `FeatureExplainer.kt` | One-time explainer popups keyed by setting id; seen state via `ZonePrefs.explainerSeen`. |
| `FeatureGuide.kt` / `FeatureDiagrams.kt` | Static feature guide + its diagram drawables. |
| `FirstLaunchWizard.kt` | The 5-page first-run wizard (language+tips → threat care → location follow-me/city-pick → zone controls → core features), extracted from MainScreen; thin yellow→blue progress bar on the **top** edge of the Next pill, whole pill dims while disabled. *Note:* threat care is a 2-column vertical grid (fast ⚡ | slow 🐢, 52dp icons) with no icon-pack picker (icon packs live only in the Just Fun panel); the zone-controls step shows big red/yellow concentric circles, a REAL slow-red radius slider + armed toggle (wired to the same ViewModel setter as the map) with the slider hint beneath it, then vertical rows for shelter / red-dot / gear with short captions (`wizardShelterDesc`, `zoneRedLabel`, `editZonesLabel`); Just Fun is **off by default** on fresh installs (the master switch is the global flourish kill-switch, enforced by the engine gates, not just a panel toggle) with the "⚙ settings" mention rendered as an inline colored gear; every interactive control ticks via `pressTick`. |
| `CityChipPicker.kt` | Shared major-city chip grid (`CityChipGrid`) used by the wizard's location step and Settings' pin-city row. |
| `ShelterScreen.kt` | "Go to shelter" list: nearest Odesa shelters ranked by distance to the focus point, adult/kid walk times (kid row only when the "With kids" setting is on), a GPS-age header with a force precise-fix button (re-prompts location permission), transliterated names in EN; "open in maps" (`geo:` intent); the map button is a red-filled (official alert) or ghost-outlined pill in `MainScreen.kt`. |
### Flourish (isolated death + tally subsystem)

| File | Responsibility |
| --- | --- |
| `flourish/Flourish.kt` | Pure flourish core: `FlourishRecord`/`FlourishShow`/`ReplayProgress` (per-group copy + overall position for the footer bar); `FLOURISH_STAGGER_MS` + `REVEAL_MIN_SPAN_*`; `clusterFlourish`/`flourishesBoundingBox` (viewport-adaptive replay grouping); `FlourishPolicy` (the neutralized-card gate, pure + tested). |
| `flourish/DeathFxController.kt` | Map-side facade: owns `ThreatDeathOverlay` + strike camera glide/return, shot/kill haptics, a random viewport-edge take-off origin (clamped to Ukraine, so a projectile never launches from "another country") and the tally-tap replay orchestration (exposes `replayProgress: ReplayProgress?` for the footer's per-group "Resolving N threats" + overall progress bar; `startReplay` owns the replay job so `clear()` cancels a show mid-flight; while queued/running `isReplayActive` makes MapView hold its follow-me pan + default fit so the show jumps straight onto its targets). Camera notes: `getMapCenter()` is snapshotted into a new GeoPoint everywhere (osmdroid hands back its live mutable projection point — holding it made "return home" land randomly); replay jumps per group via `zoomToBoundingBox(box, false)`. Pacing: each group's targets are PRE-SPAWNED on arrival with staggered `fireAtDelayMs` leads (the overlay renders pre-fired deaths as standing icons, so nothing pops up as its bullet launches); the fire loop aligns to the prespawn clock; intermediate groups pan `REPLAY_PAN_BEAT_MS` (120ms) after the last impact via quickBoom deaths; only the final group plays the full 5s lifecycle. The tally-tap tick is consumed only on a real decision — transient blockers (cold start, Settings, shelters) retry; animation-off toasts + audits (`DebugLog.recordFlourish`, detail localized via the `showDetail` lambda); a live official alert does NOT block the replay (explicit user action) though a NEW alert onset mid-show still ejects it via `clear()`. The whole facade is master-gated (`justFunEnabled` mirrors the Just Fun master pref): `strike`/`strikeDud`/`startAutoCountdown`/`startReplay` no-op while it's off — which also gates the long-press easter egg — `strike`/`strikeDud` return whether a projectile actually launched (the long-press handler applies its marker-hide / user-shot-grace / haptics side effects only then, so a disabled flourish leaves the marker untouched), `strikeHaptics` is master-gated too, and flipping the pref off `clear()`s anything in flight. Any map-covering modal (paused/mapVisible/shelters) or lifecycle ON_PAUSE also ejects a RUNNING show via `clear()` (queued-but-unstarted ticks stay queued and play on uncover; first-3 ejections toast a hint via `notifyFlourishEjected` + `flourishEjectHintRemaining`). `clear()` glides the camera back to the saved home FIRST (a strike/replay parks it), then tears down behind the scenes — an early eject never leaves the view stuck on the target; the replay remembers its pre-center in the same saved-home slot. Auto-strikes publish a `pendingStrikeCount` (strikes pending + in flight) for the countdown strip. `MapView` keeps only thin policy hooks and delegates every flourish mechanic here. |
| `flourish/NeutralizedTally.kt` | Service-side facade: tally count + 21-record resolution memory + the silent tally notification (tap replays the show, swipe resets), with a recent-ids ring deduping NEPTUN's re-sent removals so duplicates never double-count nor plant twin replay records. `reset()` keeps the ring (re-sends within the grace don't re-count/re-post ~1 min after a swipe/tap). Owns `CHANNEL_NEUTRALIZED`/`NOTIF_NEUTRALIZED`/`EXTRA_FLOURISH_*`/`ACTION_NEUTRALIZED_DISMISS`. `AlertService` keeps only the enabled-pref subscription gate + focus-scope filter; the facade itself is master-gated (`onResolved` no-ops while the Just Fun master is off, and flipping it off resets the tally). |
| `flourish/AviationFlyby.kt` | Pure policy/geometry for the MiG-31K takeoff flyby: `nextShow` picks each new INNER-tier AVIATION once per process (never while the app is backgrounded or off the map screen, and never while the Just Fun master or flyby toggle is off), a fresh random bearing every pass; `tapShow` is the same gate for user-initiated passes. `endpoints` computes edge-to-edge entry/exit through the viewport center; `spriteTransform` returns the mirror-compensated rotation so the jet never renders upside-down. When it lands `MainViewModel.onFlybyFinished` opens the threat card. Tested by `AviationFlybyTest`. |
| `flourish/AviationFlybyOverlay.kt` | The flyby's Compose overlay (owned here, not in screens): full-size jet sprite + contrail Canvas driven by an Animatable pass. Rotation and trail origin come from `IconGeometryCache` per icon set through the same mirror→rotate chain graphicsLayer applies; legacy constants are only a decode-failure fallback. |
| `flourish/FlourishFooter.kt` | The flourish's bottom-region UI (owned here, not in screens): countdown digits, tally-replay progress, and the neutralizing label with a left-aligned Stop pill; the whole bar is a tap-to-stop target. Fully isolated from the threat strip — the caller passes `active` (the single "any flourish" boolean), so it never knows about threats or the map footer. Self-sized; no dependency on ThreatStripFooter's measured height. |
| `flourish/IconGeometry.kt` | Alpha-silhouette metrology for icon art: `computeIconGeometry` recovers the true facing (PCA principal axis, end disambiguated by the pack's declared `baseDeg`) and the exhaust anchor (rear-band centroid) as slot-local fractions — no per-pack pixel tuning. Pure core tested by `IconGeometryTest`; `IconGeometryCache` decodes/caches per `ThreatIconSet`. |
| `flourish/ThreatDeathAnimation.kt` | `ThreatDeathOverlay`: 5s neutralized flourish (projectile enters from just off the screen edge along a random edge-clamped origin -> explosion). Per-death `durationMs`: `quickBoom` deaths (intermediate replay groups) compress the explosion to impact + ~0.8s flash; boom/fade curves and pruning derive from each death's own duration. Perf practices: explosion glow is a lazily pre-rendered per-density bitmap (no per-frame `RadialGradient` allocation), icons are cached bitmaps with fresh drawable wrappers (the per-frame alpha mutation must never touch a live marker's icon), concurrent deaths capped at `MAX_DEATHS = 14` (sized for a full replay, the old 6 silently ate bullets), and the map redraws at 30fps while active (16ms -> 33ms; invalidate redraws the whole overlay stack). `DEATH_EXPLOSION_START_MS` drives the card flip; dud on duplicate resolutions; `isActiveFor(id)` guards double-strikes; the target icon vanishes at the explosion (no fade); `active` StateFlow tells the UI when a bullet/explosion is on screen. |

### Background / alerting

| File | Responsibility |
| --- | --- |
| `AlertService.kt` | Foreground service; reads `ConnectionHolder.getClient(ctx)` flows (`connectionState`, `threats`, `alerts`) via 5-flow combine; `ConnectionHolder.getSupervisor(ctx)` for milestone recording. (`specialUse` on API 34+, `dataSync` on API 29–33 — Android 15 caps `dataSync` FGS at 6h per 24h in the background, which would stop a 24/7 monitor) — the always-on monitor. Owns background monitoring and the notification lifecycle: siren/chime/all-clear (20s zone grace, coalescing), the always-visible monitor notification switching to offline wording + Retry on a drop (no separate one-shot offline alert) and — when an official alert or an INNER-zone threat is live — prefixing its title with a 🔴 dot and colorizing the card red on supported devices, resolved-threat tally, per-notification vibration, `DebugLog` feed. Zone-alert dedup: ids drop from `knownZones` only when they leave the zones *and* their `userShotAt` grace (3 s) has passed, so a same-id respawn of a shot-down drone never re-alerts. | The resolved-threat tally counts by **focus oblast** by default (GPS-follow or pinned city's oblast, matched via the shared `inOblast` (`engine/OblastUtils.kt`)); an "All of Ukraine" opt-in (`neutralized_tally_all_ukraine`) lifts it to any resolution country-wide. Tapping the tally notification opens `MainActivity` directly with the last **21** remembered resolutions (position + type) baked into the tap for the map's replay flourish; a red alert (official or INNER zone) erases that memory. Zone-alert dedup: ids drop from `knownZones` only when they leave the zones *and* their `userShotAt` grace (3 s) has passed, so a same-id respawn of a shot-down drone never re-alerts. *Note:* the official-alert all-clear is region-latched: it fires only while the focus is still on the region whose alert was ringing (`state.focusToken == officialRegionToken`); switching the focus away to a non-alerting region — **or re-pinning to a different city, even within the same oblast** (the announced city is tracked alongside the token) — silently drops tracking (no false all-clear, no lingering siren), and returning to the still-alerting region re-announces fresh. The silent reason-refresh only re-posts while the new reason is a threat inside the user's zones; a reason that falls back to the bare oblast name never re-raises a dismissed notification. `ACTION_NEUTRALIZED_DISMISS` (swipe or tap-consumed tally) resets the tally. The offline drop is persisted (`offline_pending_since`) so it re-flags after a service kill; evaluates via the shared domain functions, never local formulas. Official-alert lifecycle keys on the RAW episode (`focusOblastAlertRaw`): the City-level scope toggle only gates announce/suppress — flipping it mid-alert never fires a false all-clear nor re-rings (suppression cancels silently; returning coverage announces fresh). Reconnect milestone flags reset **and** the milestone notification is cancelled on the reconnect transition; official-alert notifications track their own notified-state (turning the toggle back on mid-alert re-announces); `ConnectionLog`/`DebugLog` restore is awaited before `NeptunClient.start()`. Notification taps that carry a threat use their own `PendingIntent` request code (1) so the reveal extras can't be clobbered by the plain status/tally/milestone intents (0); the ongoing status title reads "Monitoring GPS" when following and "Monitoring \<city\>" when pinned, and it switches to the offline wording with a Retry action once a drop outlasts the shared grace. A daily 16:20 coroutine checks `UpdateManager` silently and posts one silent "new version available" notification per new build (`last_notified_update_code` pref, `NOTIF_UPDATE`); its tap carries `EXTRA_SHOW_UPDATE` with its own `PendingIntent` request code (4). |
| `MonitoringStatus.kt` | In-memory `running` StateFlow mirroring whether `AlertService` is alive in this process. Set true by `AlertService.start()`/`onStartCommand`, false by `onDestroy`; the UI replaces the whole header with a tappable "SERVICE OFFLINE" banner when it's false. The socket alone is not proof alerting works — the connection is owned by the app process, not the service. |
| `AlertWatchdog.kt` | WorkManager periodic worker (15 min) that restarts `AlertService` if `MonitoringStatus.running` is false and `bootRestartEnabled` is true. Scheduled from `BootReceiver` and `MainActivity.onCreate`. Closes the process-kill silent dead state. |
| `BootReceiver.kt` | Restarts `AlertService` on `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED`, gated on the user's `boot_restart_enabled` pref (Settings → Alerts). When monitoring is not auto-restarted, posts a persistent "Monitoring paused after reboot — tap to start" notification. Always schedules `AlertWatchdog`. |
| `NeutralizedDismissReceiver.kt` | Delete intent for the tally notification (resets the count). |
| `LocationTracker.kt` | `object` singleton. Coarse `NETWORK_PROVIDER` only (~2 min / 250 m), falls back to last known -> `StateFlow<LatLng?>`; tracks the last fix time (`lastFixAtMs`) and offers a `forceRefresh()` GPS one-shot for the shelter screen. A 15-min periodic GPS sync loop runs by default (pref `periodic_gps_enabled`, default on) so Android sees real GPS access and cell drift is corrected. || `BatteryOptimization.kt` | Battery-exemption helpers. |

### Widget

| File | Responsibility |
| --- | --- |
| `widget/ThreatWidget.kt` | Glance home-screen widget (`provideGlance` + `provideContent`, `SizeMode.Responsive`). Passive renderer of the persisted [WidgetSnapshot] - never evaluates zones/tiers itself (mirror rule). Three density buckets (compact 2x1 / standard 4x2 / detailed 4x3) picked from `LocalSize`; dark-only palette; tap opens the app. The primary threat icon (the nearest threat) is its own tap that reveals that threat on the map via the same reveal extras as a notification tap; the status badge mirrors the app-grace-filtered online/offline pill. `ThreatWidgetReceiver` is the manifest-declared `GlanceAppWidgetReceiver`. |
| `widget/WidgetUpdater.kt` | `object` singleton. Started by `AlertService` (the already-running monitor, ~zero marginal battery). Guards on `hasPlacedWidgets()` before starting the pipeline. Combines `ConnectionHolder.getClient(ctx).connectionState` + `AppPluginHolder.registry.allThreats/allAlerts` + `LocationTracker.location` + zone/follow/pin/language/type-gate prefs + a 30s clock → `computeWidgetSnapshot` → persists to the `widget_snapshot` DataStore and calls `updateAll()` only when a widget is actually placed. Persists `primaryThreat` (id/lat/lon/type) so the widget can reveal the nearest threat. Exposes `readSnapshot`/`readLang`/`readIconSet` |
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
- **No GPS fix + no pinned city = no claims.** The focus is `null` and the attribution is
  country-wide (`resolveFocus`'s final branch — token `null`, banner "Ukraine"), so no oblast
  zones or official-alert scope is asserted for an unknown position. While following GPS with no
  fix at all, the UI and the ongoing notification show a persistent "No GPS fix" warning.
- **Single evaluation logic.** `MainViewModel` (UI) and `AlertService` (notifications) each
  construct their own `ThreatEngine(registry.typeCatalog.value)` and call `engine.evaluate(...)`
  directly — no reimplemented zone/tier/prediction/alert logic in either consumer. The full
  engine contract (tiering, dead-reckoning, scoring, official alerts) lives in `BEHAVIORS.md`,
  pinned by `ThreatEngineTest`. Official-alert scope (oblast/city) is engine-owned: the
  `officialAlertActiveFor` gate lives in `engine/OblastAlert.kt`, and the UI banner, `redCities`
  labels and the service's siren all read engine outputs. Oblast-wide alerts (name/oblast ends
  in "область" or "республіка") cover the whole stem — every city in the region lights and
  rings; the City scope narrows only alerts that name a specific city/raion. `inOblast` matches
  by prefix or whole word, so Crimea's "Автономна Республіка Крим" hits the "Крим" stem. The UI
  pill (`neptunDown`) immediately reflects connection drops (no grace filter) so the header and
  notifications agree; the grace is only applied to the connection log.
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
  the engine (`ThreatEngine.evaluate`, `isStale`, `distanceFlat`) plus `resolveFocus`; the
  `officialAlert` flag comes straight from `eval.focusOblastAlertActive` (no third
  implementation). A change to zone/tier/prediction/alert logic must **not** be reimplemented in
  the widget layer; update `computeWidgetSnapshot` instead. Counts mirror footer-strip semantics.

- **Threat type gating.** `threatMapFlow` gates map rendering, `threatAlertFlow` gates alerts —
  decoupled toggles: map-off doesn't silence alerts; alerts-on auto-enables map visibility (an
  armed alert is never hidden). A type hidden from the map is dropped from the map and the
  footer strip; a type with alerts off stays fully mapped (never dimmed — dimming is
  staleness-only) but is omitted from the footer strip, with a red crossed bell on its popup.

- **Focus point — one shared resolver.** `followMe` → camera + zones + alerts centre on the
  last-known GPS fix (staleness is fine; the focus never falls back to a pinned city while
  following), else the pinned city; pinning disables follow-me. Attribution via
  `resolveFocus` → `cityOblast` stem match, **major cities only** — the ~300 minors are
  map-context, never banner/alert. All three consumers (`MainViewModel`, `AlertService`,
  `WidgetUpdater`) call the same `resolveFocus`, so a stale fix keeps ringing where the user
  last was instead of drifting to a pin. With no fix at all the location is `null` — no fake
  region alert, and `gpsFixMissing` drives the persistent "No GPS fix" warning.

- **Zone tiering.** The formula — fast-by-ETA vs slow-by-distance, `reachKm` caps,
  `alwaysInnerWithinReach` (aviation's country-wide INNER), speed from `SpeedCache` — is the
  engine contract in `BEHAVIORS.md`. App-side facts: map circles show the slow km thresholds
  only; advisory threats never tier/sound; armed bells are per group×tier (slow/fast ×
  red/yellow), stored for day and night, resolved per tick by `effectiveArmed`. **Slider
  coupling:** the yellow threshold is relative to red — setters clamp yellow to `red+2 … max`
  (slow 1–20 km red / yellow ≤50; fast 1–5 min red / yellow ≤20) in `ZonePrefs`; the UI sliders
  derive the yellow range from the red value live.

- **Expiry / ghosts.** `staleAfterMs`/`ghostCapMs` per type and the `canDrift` dead-reckon gate
  are the engine contract in `BEHAVIORS.md`. App-side facts: stale threats stay mapped
  **dimmed** (alpha 0.45, tappable) but excluded from strip, tiers, gauge, alerts; removal only
  on server resolve / `remove` frame / ghost. **AVIATION never locally expires:** a MiG-31K
  takeoff pin sits at the launch airbase without fix refreshes, so age-based expiry would kill
  every real alert before it rang — only the server's `status: "stale"` retires it early, and
  its ghost cap is its own (2h). When the **selected** threat disappears that way,
  `MainViewModel` swaps the popup for the neutralizing card (flips at
  `DEATH_EXPLOSION_START_MS`, fades across the explosion) — only while the map screen is visible
  and the shelter overlay is down; it *does* play during an alert; with `deathAnimationEnabled`
  off nothing animates. MapView
  glides markers on its own 1s tick (30fps tween); the service clears on a 20s grace.

- **Threat facing always matches its motion.** The heading resolution chain (`motionHeading`:
  `bearingDeg` → `heading` → measured), the dead-reckon course rule (server-reported only,
  gated by `canDrift`) and the `fallbackCourse(id)` pseudo-course for stationary threats are the
  engine contract in `BEHAVIORS.md` — a change must stay in the engine so the marker never faces
  a direction it doesn't move.

- **Place names transliterate, never translate.** Any Ukrainian proper noun in the EN UI →
  `Cities.uaToEn` → `Transliteration.transliterate`; military vocabulary is hard-coded
  (`COURSE_PATTERNS`/`COURSE_GLOSSARY`/`ThreatTypeCatalog`). No network translation.

- **REST never clobbers WS.** REST merge keeps the newer record per threat id
  (`updatedAtMillis`); a REST snapshot can be CDN-stale.

- **Official alerts come from a priority-ordered set of sources, one live owner at a time.**
  `PluginRegistry` is the health authority: it derives per-source state, aggregate
  `connectionState`, and `wsHealthy` (any WS source CONNECTED/DEGRADED). The merged alert feed
  uses **takeover** semantics, not union: while a source is *authoritative* (WS socket
  CONNECTED/DEGRADED, or REST poller engaged and having actually fetched), its snapshots are the
  sole truth; if nothing is authoritative the last-known state is held (never fabricated all-clear).
  This means an active REST fallback (Ubilling) supersedes the primary's stale held alerts during
  an outage, so a real all-clear can't be masked. Alert **scope** (oblast/city) is engine-owned:
  both consumers read the `officialAlertActiveFor(...)` gate in `engine/OblastAlert.kt`, and the
  map's red city labels (`redCities`) come from the engine result — oblast-wide alerts cover the
  whole stem, City scope narrows only city/raion-named alerts. NEPTUN's list is held (never
  cleared) while its socket is down — but once a fallback takes over,
  the fallback's snapshot is authoritative for the regions it reports. A continuous
  Neptun→Ubilling→Neptun handover never re-rings: the runtime announce latch keys on the alert
  staying active, not on `since` (Ubilling reports `since = null`); only a service kill
  mid-outage falls back to the documented `since`-based restart reconcile (may re-ring once).
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

- **Monitoring is always-on; "Stop Monitoring & Exit" is session-only.** Every cold start
  (`MainActivity.onCreate`) re-arms `AlertService`; the old `monitoring_enabled` persisted off-switch
  is gone because it produced a silent dead state (the NEPTUN socket stays alive from the app
  process, so the map looks fine while no alert/notification machinery runs). The only persisted
  off-switch is `boot_restart_enabled` (Settings → Alerts → "Restart monitoring after reboot",
  guarded by a security warning) which gates `BootReceiver`. If the service dies while the app is
  open, `MonitoringStatus.running` goes false and the header is replaced by a red tappable
  "SERVICE OFFLINE" banner — the one loud signal that alerting is off when the connection isn't.

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
- compute official-alert facts locally (read them from the engine result)

### AlertService

Owns:
- background monitoring
- notification lifecycle
- official-alert orchestration (region latch, announce-once persistence, sound policy) — reading
  engine-produced facts, never re-deriving the gate or formulas

Must not:
- depend on Compose
- use UI-only selected state
- implement new zone formulas locally
- re-implement the official-alert gate/reason (engine-owned)

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
  staleness/ghost rules, scoring, proximity **and the official-alert facts** (`redCities`,
  `focusOblastAlertActive`, reason); neither consumer re-implements a decision formula. Any
  change lands once, in `engine/`, and both paths are covered by `ThreatEngineTest`.
- **Note:** each `ThreatEngine` carries its own `SpeedCache` (speed history is per-consumer,
  not a shared singleton). Speed fallback is deterministic (server → trail → nominal), so
  consumers can't disagree near a zone boundary. The service additionally reads the raw
  (scope=false) gate and the `since`/region of the active alert for its region-latch
  orchestration — facts, not formulas.

### Sub-packages, not flat

The UI/domain/widget layers share the root package; the subsystems (`engine/ plugins/
connection/ service/ theme/ data/`) have real sub-packages (Package structure). Import
ceremony between them is the cost of isolation; the engine kernel stays free of app-layer
types.

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
| Monitoring silently dead (service stopped, app open) | The NEPTUN socket keeps working (owned by the process, not the service), so the map looks healthy while alerts/notifications are off. `MonitoringStatus.running` flips false → `UiState.protectionState` becomes `OFFLINE` and the whole header becomes a red "SERVICE OFFLINE — tap to reactivate" banner. `AlertWatchdog` (WorkManager 15-min periodic) restarts the service if it died while the process was alive. | `MonitoringStatus`, `AlertWatchdog`, `MainScreen` |
| Reboot | `BootReceiver` restarts the service on `BOOT_COMPLETED` (unless the user disabled `boot_restart_enabled` in Settings). When not auto-restarted, a persistent "Monitoring paused" notification is posted. `AlertWatchdog` is always scheduled. | `BootReceiver`, `AlertWatchdog` |
| Package replaced | `BootReceiver` restarts the service on `MY_PACKAGE_REPLACED` (same gate). | `BootReceiver` |
| Location unavailable | Follow-me uses the last-known fix; no fix at all → country-wide focus + "No GPS fix" warning; pinned only when not following. | `LocationTracker`, `Cities` |

## Testing

JUnit unit tests in `app/src/test/java/ua/ukrainedrones/`. Invariant → test: the engine
(`zoneTier`, `predictPosition`, staleness/ghost, scoring, official alerts) is pinned by
`ThreatEngineTest`, which both UI and service consumers rely on.

- `engine/ThreatEngineTest.kt` — `evaluate` zoning, `zoneTier` tiering, `predictPosition`,
  `motionHeading`, `isStale`/`isGhost` caps, scoring, AVIATION override, null-speed fast,
  official-alert outputs (scoped/raw gate, `redCities`, reason).
- `OblastAlertScopeTest.kt` — `inOblast`/`isOblastWide`/`coversCity`/`officialAlertActiveFor`
  matching rules (the `engine/OblastAlert.kt` gates).
- `engine/TypeMappingTest.kt` — `ThreatType`↔String, `Threat`↔`NormalizedThreat` round trip.
- `plugins/PluginRegistryTest.kt` — plugin merging, `typeCatalog`.
- `CitiesTest.kt` — city-list integrity; majors-only `nearestCity`/`resolveFocus`.
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
- **Clock is not state.** There is no wall-clock StateFlow on the ViewModel — a global 1s ticker
  recomposed the whole tree for nothing. `lastFrameAt` is a flow collected only by the connection
  sheet; the shelter screen's GPS fix-age label runs a 10s local clock scoped to its own
  composition (it only ticks while that screen is open); the map's marker smoothing loop keeps its
  own 1s tick. None of these are fields of UiState, so a no-op tick never rebuilds the tree.
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
