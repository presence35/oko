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

**The loop is not the problem.** An earlier read of these logs claimed the wake-up tick "decides
nothing" because `resumeAfterSleep` fires on every gap. That is wrong and is corrected here:
`resumeAfterSleep` (`AlertService.kt:1788`) has no skip in it — it drops `lastZoneTiers`, sets
`lastSweepAtMs = 0L` (which forces the next sweep *sooner*) and nulls `emptySince`, then
`nowFlow.value = tick` runs unconditionally. The wake tick evaluates; it just does so from a clean
slate rather than computing deltas across a gap nobody observed, which is correct — the deltas are
the part that cannot be trusted. The comment above the call site ("Skip that tick's decisions")
describes intent the code does not implement, and the data agrees: a 466 s stall shows as a gap
*between* two decisions, not two gaps. Consequence: `SLEEP_GAP_REBASE_MS = 3 min` is a correct
detector and must NOT be lowered to ~90 s — that would push ordinary hiccups onto the rebase path
and discard hysteresis for nothing. There is no cheap in-app correctness win here.

So the whole remaining problem is **silence, not wrongness**: the app knows it slept (it logs
`PAUSED` on every one of those stalls) and never tells the user. The exemption flow **already
exists and is already surfaced** — `BatteryOptimization.requestExemption` is wired into
`MainScreen`, `SettingsScreen` (the button renders whenever the app is not exempt) and the
first-launch wizard, and the bundle reports `batteryUnoptimized`. Both test phones are un-exempt,
so both are showing the button today. What is missing:

- **Promote the sleep from a diagnostic to a fact with a consumer.** `resumeAfterSleep` already
  receives both endpoints of the window; it currently writes a string (`"paused 22m"`) into a
  diagnostic ring buffer. Make it a typed `SleepWindow` (engine-owned, like `OblastAlert`) and let a
  **pure** `SleepPolicy` classify it. One producer (the single call site that can know the tick
  stopped), one fact, consumers orchestrate — the existing no-mirror shape.
- **Report only what carries a server timestamp.** Two claims are provable from the feed alone:
  official-episode onsets inside the window, and tracks whose `confirmedAt` falls inside it. Both
  are compared server-timestamp-to-server-timestamp, anchored on the newest `updatedAt` in the feed
  as "server now", so no client/server clock skew can fabricate or hide one. This is also why no
  pre-gap snapshot and no cold-cache flag are needed: a cold cache cannot invent a server-timed
  event inside the window. Threats that appeared *and resolved* while blind stay uncountable — so
  the copy says "an official alert started while Oko was asleep", never "you missed 14 alerts".
- **Throttle by notification identity, not by state.** Reuse one notification id through the
  existing `safeNotify(id, ...)` so a re-post *replaces* rather than stacks: one updating row per
  sleep episode ("asleep 6 min" → "asleep 22 min"), coalescing for free with no `lastNagAtMs` and
  no pref. 165-301 stalls a day makes this the load-bearing detail: a notification that nags is
  worse than no notification. Clear it when the exemption is granted or the app is opened.
- **Let the measured sleep history choose the copy instead of the vendor string.**
  `BatteryOptimization.getOemInfo()`'s manufacturer table exists only to pick which settings screen
  to open, and its `isAggressive` flag currently changes one *title adjective* — the EN and UA
  bodies are byte-identical and the click path is identical (`SettingsScreen.kt:1276` calls
  `requestExemption`, never the OEM-aware variant). `hasAutoStartIntent` is written 7x and read
  0x. So either give `requestExemptionOemAware()` its one caller (the sleep notice's tap target, so
  Samsung/Xiaomi land on their OEM screen) or delete it, and delete the write-only field. The plain
  claim in its KDoc — that a Pixel returns `isAggressive = false` — is false, and the two have
  disagreed since the same commit (`136af82`). Net line count should go **down**.
- **Keep the loud path off the battery.** A wake lock held across the idle tick, or
  `setExactAndAllowWhileIdle` on the official-alert path, are the only things that shrink the
  windows themselves. They cost battery, which is the trade this app cannot afford to lose — try
  them only if the exemption ask proves insufficient, and measure on the Samsung (worst by 3x).

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

## Deferred alert-path follow-ups (only if reproduced)

Two known-weak spots found while auditing the all-clear/episode path (2026-10-06). Neither is a
correctness bug today; both change product behaviour, so they wait for a real report.

- **Zone-empty grace is one tick.** `AlertService.ALL_CLEAR_GRACE_MS = 0L`: the zone-empty path
  cancels the alert notification and releases the raid mute on the second consecutive empty tick
  (~1 s while alerting), because `emptySince` already debounces once. A GPS/feed flicker that
  blinks a threat out and back can therefore blink the notification off and re-post it. Raise it
  (5 s was the original value) only if a field report shows the flicker; otherwise it is a magic
  constant with no reproduced symptom.
- **Critical-offline alarm ignores the raid mute.** `AlertService.handleState`'s critical-offline
  branch fires `dispatchCriticalOffline` + a notification while `bellsMuted()` is true; it honours
  only the offline-specific "ignore 30 min" deadline. That is arguably correct — the alarm means
  "the app has gone blind", which is worst during a raid, and `criticalOfflineBypassSilent` exists
  to make it pierce silence. If it must be muted too, route it through the one silence-decision
  owner (`SilentReason`) rather than adding a local `&& !bellsMuted()`.

## Alert-path testability (partly done)

`buildPrimary`/`reconcileEpisode`/`reconcileNotif` are private locals inside
`AlertService.handleState`, so the suite could not reach them and four all-clear bugs shipped
unverified. `handleState` + `MonitorState` are now `internal` and `AlertEpisodeClearTest` drives
real ticks against a fabricated state (`AlertService.monitoringReady` orders the test against the
onCreate restore; `clearPersistedServiceState()` isolates the shared DataStore). Remaining:

- `Primary.zone: ThreatZone?` still means two things — "this is a zone threat" (three consumers
  branch on it) and, in `postAlert`, "derive the channel from the level". Replace with a sealed
  `AlertSource` (`Zone(tier, threat)` / `Official`) so a channel tweak cannot reclassify an alert;
  that double meaning is how the official-alerts-on-the-wrong-channel bug was introduced and how a
  plausible-looking wrong fix slipped past review.
- Optionally extract one pure `decideEpisode(EpisodeState, EpisodeFacts) -> EpisodeOutcome` if the
  tick seam stops being enough; not needed for the current tests.
- **`UserPrefsTest`'s night-sleep snapshot test is order-dependent.** `touching a night bell drops
  the sleep snapshot` passes alone and flips in a full run as soon as another test class touches
  the shared `user_prefs` DataStore — observed when `clearPersistedServiceState()` was briefly
  widened to clear prefs too. The test calls `UserPrefs.clearAll()` itself, so the coupling lives
  in whatever the other class wrote. Prefs isolation belongs to whoever owns that store's tests:
  do not widen the service-level fixture to cover `user_prefs`.
