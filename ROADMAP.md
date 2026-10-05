# Roadmap

Planned-but-not-started features, tracked so a future session can pick them up. Nothing here is
committed to a release.

## Tell the user the app was asleep, and push for the battery exemption

From two sent logs (2026-10-03/05, Pixel 7 / Android 17 and SM-A505FN / Android 11): with the
screen off and the device idle, the whole process is CPU-suspended by Doze for 5-6 minutes at a
stretch. Measured off the power timeline and the decision log, the app was blind for **48% of the
day on the Samsung and 17% on the Pixel**, longest blind window 39 min and 22 min, on 301 and 165
stalls respectively. Not a bug in the loop: every gap has `interactive=false, deviceIdle=true`, and
the 466/634/469/405/450 s stalls contain *zero* power samples and *zero* connection-log entries —
the process is suspended end to end, not just the decision coroutine. Only the two long stalls
(2476 s, 1025 s) contain connection events, and those are the Doze maintenance windows where the
CPU came back on. A threat that entered a zone mid-stall is not seen until the loop wakes: one
demonstrated miss on the Samsung is a `ZONE_ENTER/FIRED` 7.8 min after a 7.8 min blackout.

**Worse, every wake-up currently decides nothing.** `AlertService.SLEEP_GAP_REBASE_MS` is 3 min
and the idle tick is 30 s, but a Doze window is 5-6 min — so every post-Doze tick is above the
threshold and takes `resumeAfterSleep`, which deliberately drops `lastZoneTiers`,
`lastSweepAtMs` and `emptySince` and evaluates nothing. The rebase is right for an *hours* gap; it
is the wrong call for a 5-minute one, and on any device that enters Doze it is the only call
being made. Fixing that interaction is the cheap half (see below).

The exemption flow **already exists and is already surfaced** — `BatteryOptimization.requestExemption`
is wired into `MainScreen`, `SettingsScreen` and the first-launch wizard, and the bundle reports
`batteryUnoptimized`. So this is not "add a button". What is missing:

- **A user-visible sleep report.** After a stall over some threshold, say so plainly — "Oko was
  asleep 22 min; 14 alerts were published while it was off" — counted from what actually arrived.
  The honest framing matters: the app cannot know what it *missed*, only what was published, so
  the copy must not claim "you missed 14 alerts". Own copy in EN, then UA/RU.
- **Make the exemption ask *sticky and consequential*.** It is currently one row in Settings that
  the user can dismiss forever. Given the exemption is the actual cure (Doze does not apply to an
  exempt app, so the 30 s tick runs normally and none of the hacks below are needed), the sleep
  report is the moment to name it: "this is why you were unprotected — tap to fix". Needs
  `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` in the manifest and a Play-policy review; core
  functionality is a defensible use case for an air-raid app, but state it in the listing.
- **Fix the rebase threshold, then measure before anything clever.** `SLEEP_GAP_REBASE_MS` down to
  ~90 s costs nothing and stops the app throwing away a perfectly good tick after every Doze
  window. It does **not** shrink the windows themselves. Only after that is worth trying a partial
  wake lock held across the idle tick, and `setExactAndAllowWhileIdle` on the official-alert path
  as the fallback for OEM throttling that ignores wake locks — both cost battery, which is the
  trade this app cannot afford to lose. Measure on the Samsung, which is the worst case by 3x.

Two adjacent bugs found while digging, cheap and independent:

- **`BatteryOptimization.getOemInfo()` returns `isAggressive = true` for every unlisted OEM**,
  including Google — its own KDoc says a Pixel must return `false`. So the wizard and Settings show
  the aggressive-OEM copy, and the OEM-aware intent, on the one phone whose Doze behaves best.
  The `else` branch should be `false`.
- **`requestExemptionOemAware()` has no caller.** Either wire it into the sticky ask above or
  delete it; dead code that looks like the fix is worse than no code.

Open question for whoever picks this up: is the promise "we will not sleep through an official
alert" (achievable with an alarm path) or "we will not sleep at all" (not achievable on an
OEM-throttled Android 11 without the exemption)? The copy depends on the answer.

## Inbound approach model (staging follow-up)

Inbound tracks are now re-tagged by a consumer-side policy (`domain/ThreatBehavior.kt`:
`isInbound`/`stageInbound`, applied by `MainViewModel` + `AlertService`): a source-named
destination inside the yellow ring makes a track inbound — slow reads OUTER, fast still sounds
red — and both ride the yellow ring instead of landing on your position. Remaining work for the
full design (worked out 2026-09-26):

- Split facts from policy: the engine reports raw data, a policy layer owns the classification,
  so the widget (and any future consumer) stages too — today only the app + service do.
- User setting: "an incoming threat means red or yellow" (default yellow), mapped plugin-side
  like `NotifyPrefs.from` rather than a hardcoded slow/fast rule.
- Uncertainty edge: tier and place approximate tracks by the far edge of `uncertaintyKm`, so an
  uncertain fix cannot claim overhead even without a named destination.
- Widen "inbound" beyond a named destination (a course that genuinely points at the focus), with
  the same geometry shared to the widget.

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


## Rotate the FTP/upload password

`app/upload.properties` holds the server password in plaintext (it is git-ignored, but it has
sat in chat logs, in build output, and on every machine that has ever run a release). Rotate the
FTP credential, then update that file. Worth doing whenever credentials appear in a transcript.

## Harden the log drop box (`server/upload.php`)

The beta log endpoint is deliberately unauthenticated — a token baked into the APK is extractable,
so it guards nothing while adding a setup step to every tester. As shipped it has a per-IP rate
limit, a body cap, a JSON-shape check, a sanitized `.json` filename in a fixed out-of-docroot
directory, and a 90-day prune. Still worth revisiting if the tester group grows: per-IP limits
assume clients can't rotate addresses, and the endpoint is publicly reachable on the same host
that serves the APK.

## We need to track if neptun.in.ua changes their API hash
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