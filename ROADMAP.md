# Roadmap

Planned-but-not-started features, tracked so a future session can pick them up. Nothing here is
committed to a release.

## Inbound approach model (staging follow-up)

Inbound tracks are now re-tagged by a consumer-side policy (`domain/ThreatBehavior.kt`:
`isInbound`/`stageInbound`, applied by `MainViewModel` + `AlertService`): slow → yellow ring,
fast → red ring, so an approximate track the source reports heading to your area no longer lands
on your position. Remaining work for the full design (worked out 2026-09-26):

- Split facts from policy: the engine reports raw data, a policy layer owns the classification,
  so the widget (and any future consumer) stages too — today only the app + service do.
- User setting: "an incoming threat means red or yellow" (default yellow), mapped plugin-side
  like `NotifyPrefs.from` rather than a hardcoded slow/fast rule.
- Uncertainty edge: tier and place approximate tracks by the far edge of `uncertaintyKm`, so an
  uncertain fix cannot claim overhead even without a destination or an aimed course.
- Match the destination to the focus (today any named destination inside the yellow ring
  qualifies) and share the "course points at me" geometry with the widget.

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

Timer in footer "how long this alert has been active"
Fill settings pills: city name, fill, border only