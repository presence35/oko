# Roadmap

Planned-but-not-started features, tracked so a future session can pick them up. Nothing here is
committed to a release.

## Multi-city official-alert monitoring (TL;DR)

Replace the single-focus alert model with a tappable watch set: a "Monitor cities" card grid in
Settings (26 MAJOR cities, tap on/off, pin auto-included). Each watched city is
checked per tick with the existing shared gates (`officialAlertActiveFor`/`coversCity`) — official
alerts only; zone tiering stays tied to GPS. Siren + city-named notification per new
region onset, all-clear chime per region end (remaining cities rewrite the notification). UI banner
gains a display-only "also in alert" line; widget's official-alert status covers focus ∪ watch set.
Core work: per-region episode latching in `AlertService` (replaces the single
`officialAnnounced*`/`officialRegionToken` state) driven by a pure, tested policy
(`domain/OfficialWatch.kt`); mirror-side computation in `MainViewModel`. Full plan with file list
was worked out 2026-08-26 (see session notes / this entry).

## Map engine: migrate raster/osmdroid → vector/MapLibre (TL;DR)

CARTO's free basemap key unblocks the current raster tiles today, but their PNG tiles are on a
recorded retirement path, and the industry direction is vector-only. Target stack:

- **Renderer**: MapLibre Native for Android. Wrapper decision deferred between
  `org.maplibre.compose:maplibre-compose` (official Compose Multiplatform wrapper, idiomatic,
  pre-1.0 API churn) vs raw SDK in `AndroidView` (stable API, more boilerplate).
- **Tiles/styles**: OpenFreeMap public instance — free, no key, unlimited views; Dark style at
  `https://tiles.openfreemap.org/styles/dark` (caveat: Dark/Fiord are unmaintained upstream
  forks; Liberty/Bright/Positron get the upkeep — may need runtime layer tweaks).
- **Sizing**: 38 osmdroid touchpoints across 10 files. Full rewrites: `MapView.kt`
  (~1200 lines), `UkraineTileProvider.kt` (obsolete — Ukraine-only tile blocking becomes hard
  camera bounds). Rework: `flourish/` death animations + `Cities.kt` label overlay draw
  directly onto osmdroid canvases. Decouple: `GeoPoint` leaks into `domain/` (`Prediction`,
  `ThreatEvaluator`, `Cities`) — replace with a local lat/lon value class.
- **Gotchas**: osmdroid `TileWriter` cache disappears (MapLibre manages its own); zone circles,
  threat markers and shelter pins become GeoJSON layers or annotation plugins; attribution
  string changes to OpenMapTiles/OSM/OpenFreeMap.

Worked out 2026-08-26. Ship behind nothing — it replaces the map wholesale; do it as its own
focused task with device testing of every visual surface.


### We need to track if neptun.in.ua changes their API hash
neptun.in.ua/sdk/build-manifest.json

// In UpdateManager's daily check, add:
ApiMonitor.checkForChanges(context)

Also log unkonwn threat types
// In your Threat.fromJson() or wherever you parse type
val typeRaw = json.optString("type", "unknown")
val type = typeRaw.toThreatTypeOrUnknown() // falls back to UNKNOWN instead of crashing
###

## In-app tutorial popup (TL;DR)

Lightweight tutorial dialog that appears on ~10th app open or day 3 (whichever comes first),
explaining core concepts the first-launch wizard doesn't cover:

- How advisory vs non-advisory threats work (amber badge = observation, not danger)
- Why some threats show at oblast level (areaOnly — amber dot, no precise point)
- How the connection status pill works
- That Telegram channels are faster than official air-raid alerts
- How zone alerts differ from official air-raid alerts

Implementation: new pref key (`tutorialShownCount`/`tutorialShownAt`) in `UserPrefs`, check in
`MainViewModel.init` or `MainScreen` composition. Simple AlertDialog with swipeable pages or
single rich-text dialog. Steer users to Settings → Feature Guide at the end. Strings in
`Strings.kt` (UA + EN).

## Threat clustering at low zoom (TL;DR)

When multiple threats overlap at low zoom levels, show a single count badge instead of 10
stacked icons. Zoom in reveals individual threats. Requires a spatial index or grid-based
grouping in the map rendering layer (`MapView.kt`). Could piggyback on the osmdroid → MapLibre
migration or be done standalone with a simple grid-bucket approach.