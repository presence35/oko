# Changelog

## [Unreleased]
- Wizard threat toggles now only switch alerts, no longer hiding the type from the map 09-26_15:05:00
- Removed the Telegram SDK-change notifier 09-26_14:09:49
- Stale threats dim the icon and show a "Stale" pill beneath it on the threat card 09-26_14:22
- Incoming threats heading to your city now ride the yellow ring instead of landing on your position, with an "Approaching" line on the card; fast ones still sound red 09-26_13:14:31
- Area-level drone warnings now show as the area-level chip instead of repeating the type, region and "point unknown" as a sentence 09-26_13:05:06
- Reset all tips now sits with the other Settings action buttons, and the System & Display section is renamed to Display 09-26_12:47:46
- Threat cards no longer repeat the type and place under the title in non-Ukrainian languages 09-26_12:25:11
- Connection log rows show the network as an icon beside Online, Degraded or Offline, and each row keeps the network it was recorded on instead of flipping to the current one, while an outage row stays put with a new online row added instead of flickering 09-26_12:48:45
- The Official group header in the Decisions proximity view is now neutral grey, with alert colour carried by the rows 09-26_12:22:25
- Tally replay footer no longer shows a distance label that sat stuck on the last group 09-26_12:19:07
- Tally replays always play the whole show, so shootdowns can no longer stop while vibrations continue 09-26_12:11:07
- Official alert banners now name the cause even when that type's alerts are off; types hidden from the map are never named 09-26_12:08:07
- If Android blocks restarting the background monitor, a tap-to-resume notification now offers to bring it back instead of failing silently 09-25_12:45:00
- Dropping one network such as Wi-Fi while mobile data is still up no longer tears the connection down or flashes Offline 09-25_12:45:00
- Notification taps no longer snap the map back to an old threat after you leave and return to the map 09-25_12:45:00
- Background location keeps updating on Android 14+ because the monitor now declares the location foreground-service type 09-25_12:45:00
- Sirens that fire before the alarm sound has finished loading are queued and played instead of dropped, and an incoming call no longer silences them 09-25_12:45:00
- Yellow official alerts now show a yellow row and group header in Decisions instead of red 09-25_12:22:11
- Alert region matching is now exact by canonical region ID: a district alert can no longer ring a different oblast and one city alert can never cover another by a shared name prefix 09-25_12:30:00
- City, raion and region names show their real Russian forms when the phone language is Russian 09-25_12:30:00
- Removed the retired Ubilling aliases and the substring stem fallback from region resolution so unknown names never match by accident 09-25_12:30:00
- Connection log rows now show which network the status change happened on, Wi-Fi or cellular or other 09-25_12:06:59
- Night mode gets a big Just let me sleep! toggle: one tap turns off every night alert, zone and official, swaps the label to All alerts are off and hides the finer toggles; tapping again restores your previous night settings. Night settings also gain explicit Red/Yellow official toggles 09-24_23:10:00
- Map scale bar and CARTO credit hide during a strike flourish, and the flourish message is horizontally centred 09-24_22:44:23
- Shelters load again after the old Odesa data removal: the index is fetched from the server on start and the map button and nearby list work 09-24_20:05:00
- Removed redundant shelter description prefixes/suffixes 09-24_18:50:30
- Cold start shows a branded trident splash instead of void black, and monitoring re-arms past the first frame 09-24_11:19:11
- Alert region style chips are one uniform neutral style with selection shown by border and weight 09-24_13:59:35
- Falling debris icon is a falling person with debris dots instead of the starburst, district-borders row aligned with the oblast row, and Limit repeat sounds badge is Z with superscript z (X with superscript x in UA/RU) 09-24_13:48:36
- Threat card opens instantly on old phones: course words are matched with precompiled patterns instead of rebuilding 150 regexes per tap 09-24_11:19:11
- Critical offline row now uses the stock Wi-Fi off glyph instead of the broken bell-underneath vector 09-24_13:30:10
- Threat card is now skippable: selection travels in a stable holder with per-threat chip state, so feed ticks stop at the card instead of recomposing it 09-24_11:19:11
- Removed the settings gear pulse animation 09-24_11:19:11
- Critical offline row uses the Wi-Fi off icon and tally badges sit outside the bell with a shadow so the 1 stays readable 09-24_13:13:28
- Both morale tally badges now show 1 with a superscript 2 09-24_13:00:51
- Fixed phantom all-clear with chime after an update when skies were quiet: the announced-episode latch is now cleared together with the episode, and a restored latch that was never seen live expires silently instead of chiming 09-24_09:15:00
- Threat card no longer recomposes on every live feed push: freshness is bucketed to 10s and host inputs are stabilized so feed ticks skip the card 09-24_11:19:11
- Threat card opens faster on old phones: popup math now runs off the main thread and the card memoizes its text in one pass 09-24_11:19:11
- Threat card skull gauge is now per threat instead of the global aggregate 09-24_11:19:11
- Cut overnight radio and CPU drain: WebSocket ping 15s â†’ 60s and watchdog tick 3s â†’ 30s so the LTE modem can sleep between heartbeats 09-24_10:45:00
- Once per type no longer re-sounds when a track flickers: the gate is per type per sitting and re-arms only when the sky is clear of that type 09-24_10:25:00
- Added connection layer unit tests: backoff algorithm, monotonic clock, reconnect log labels, NEPTUN JSON decoder 09-23_20:30:00
- Bad GPS fix outside Ukraine no longer blacks out the map, falls back to country view with GPS warning 09-23_20:15:00
- Quieter settings icons: the notify-policy and offline-critical rows now use a plain bell with a larger top-right Z badge, and the invisible tally badge is gone 09-23_16:45:00
- Fixed false all-clear right after an update: the alert feed now marks its first real snapshot, and a restored alarm episode holds until then instead of ending on the initial empty 09-23_16:27:09
- Threat icon packs, overlap mode and icon zoom now live in Threats at the top â€” changing the pack immediately repaints the type icons above 09-23_14:45:00
- Red-zone frequency now respects the policy: Once per type and Digest no longer re-sound on every new red entry â€” the policy gates all alerts including red. Limit repeat sounds now shows the notifications-off icon and How often to sound is a muted section label, not a second setting 09-23_14:20:00

- Connection stays up on weak cell: the socket is no longer torn down on brief signal loss, quiet-night silence kills it only after 3 min, and sub-15s flaps no longer spam the log 09-23_10:45:00

- Battery exemption prompt no longer appears after first launch - it now waits until the system actually kills background monitoring, and never covers the welcome shootdown 09-22_12:45:18
- Wizard morale is now a single Morale master toggle, full tuning stays in settings 09-22_12:38:06
- Wizard threat toggles now tick with haptic feedback like the rest of the app 09-22_12:38:06

- Overlapping threats now only Default or Count per type, Grid and Spread removed 09-22_14:12:00
- Digest preset no longer has a redundant Off window: duplicates of Every change removed 09-22_12:57:00
- What-if counts now use a single alert format instead of separate actual/estimate strings 09-22_12:57:00
- Fast yellow zone sound is now off by default on fresh installs 09-22_12:30:00
- Tapping the all-clear notification now replays the alarm's shoot-down show, and watching one replay no longer wipes the other tally 09-22_12:12:07
- Welcome shootdown now lands on the pinned city when one is selected, with live focus and Kyiv only as fallbacks 09-22_11:54:02
- Fixed wizard trapping you on the threat step with a disabled Next after skipping the location choice: the location gate now guards the location step itself 09-22_11:54:02

- Russian language support: the app follows the phone locale with no manual switch 09-22_11:31:08
- Morale is now the only name (all Just Fun leftovers renamed) and it is on by default 09-21_17:06:03
- After the first-run wizard the app greets you with one fake SHAHED shootdown near your focus, once per install 09-21_17:06:03

- Zone notifications are now logged as notified at the moment they fire 09-21_09:09:19
- Decisions log always shows the last 4 digits of the threat ID, and notifications include it when the threat-ID toggle is on 09-21_09:46:36
- Zone sirens now follow a frequency preset (every change, once per threat, once per type, or digest) with red always sounding first, plus boundary hysteresis so edge-hovering threats stop re-sirening 09-21_13:57:29
- Repeat-sound limiting is now behind a master toggle (off by default, meaning every change sounds) 09-22_09:01:51
- Stale threats now stay parked on their last fix while dimmed 09-21_12:02:47
- Locate and card pan now land on the marker live position 09-21_12:02:47
- Shot-down threats now glide nose-first along their course 09-21_12:02:47

- Smoother fast scrolling in Settings: the list no longer rebuilds on every threat and location update 09-20_23:23:56
- Falling debris advisory is now wired up: 0-10 min slider in Settings drives the all-clear countdown in the notification and footer 09-20_12:00:00
- Threat cards are no longer forced to fill the full available width; hover buttons are left-aligned side by side 09-20_12:00:00
- Morale settings now share a single source: wizard and Settings use the same toggles (including HD explosions and alarm summary) 09-21_01:07:00

- Fixed city size toggles not updating map labels 09-20_23:38:00

- Alarm summary: when your oblast alarm ends, a separate morale notification shows what was neutralized during that alarm, with tap-to-replay 09-20_23:10:00
- Morale replays and shot-down cards now play even with the shelter list open (tap snaps back to the map) 09-21_00:00:00
- Alarm summary now keeps its origin city and shows duration (e.g. "Kyiv: 12min alarm summary" + "1 threat resolved. Tap to see.") 09-21_00:01:00

- Redesigned small threat card with fixed 280dp width, larger icon, no title, vertical R and P and horizontal skull gauge 09-20_19:42:05

- Haptics on every button and tappable row (press tick via shared Haptics helpers)

- Night mode and morale master switches now tick like every other control

- Fixed stale GPS on devices with a missing network provider: subscribe passively to fixes other apps request, and keep GPS armed during calibration until satellites lock

- Map shelter pins now show only shelters inside your red zone radius

- Home-screen widget now goes live within seconds even when placed after the app has been running

- Night-time city-scope toggle now applies to the map banner when night custom zones are on

- Unknown-reliability threats now score per spec instead of being underweighted

- Speed-cache track history bounded so long-running monitoring stays lean

- Removed the pinned-city pin, the GPS dot now marks the zone epicentre in all modes

- Fixed decisions log duplicate key crash, restored full drone decisions visibility, and added suppressed bell to legend

- Fixed overnight ConcurrentModificationException crashes by synchronizing tally snapshots, debug sweep verdicts, and threat updates

- Fixed broken map zone circles and alert polygons under Ukrainian system locales

- Smooth projectile intercept animation with in-flight threat motion and aligned popup controls

- Dynamic threat tap target expanding up to 48dp when isolated without stealing taps from nearby entities

- Eliminated popup card open hitch by isolating height state from full-screen recomposition, removing title icon spring allocations, and pausing idle frame loops

- Instant 0ms threat tap haptic and card launch via direct touch interception

- Fixed ghost offline episode counter on Wi-Fi by making in-progress episodes purely runtime state

- Cleaned up alert region style buttons, moved show threat IDs below haptics, defaulted haptics to ON and small cities to OFF

- Eliminated ghost hits by tightening hit-test radius to icon bounds, filtering animating/dead threats, and decoupling overlay invalidations from card recompositions

- Fixed stale offline timers firing all milestones at once by clearing the episode stamp on recovery and stitching only sub-3s flaps

- Alert strokes now render above admin borders so active threats override outlines

- Region borders now crisp white in FILL (override token opacity so translucent fills don't wash them gray) and muted under colored strokes in BORDER

- Masked OpenGL white flash with dark curtain until first map frame (180ms fade, 2s fallback)

- Fixed white map on cold start by building border geometry off the main thread

- City labels are colored only in city-labels mode; fill and border modes keep them white (alert already shown by fill/border)

- Fill/border mode switches apply instantly without rebuilding unchanged region geometry

- Fixed white flash on cold start by painting the map surface and system splash black until the dark style loads

- Fixed red alert regions not rendering by dropping degenerate boundary slivers and normalizing polygon winding in alert fills

- Auto shoot-down now respects follow-bullet: off-screen strikes skip the countdown and animation when the setting is off, and the collector lives in the flourish

- Alert fills now translucent (45% red

- Settings alert-region options now show color previews: city labels split red/yellow text, fill chip 50/50 red-yellow gradient, border chip half-red half-yellow inner stroke

- Alert fills now fully override borders without blending: land/oblast/raion borders render below opaque red/yellow fills so no pink appears when both are enabled

- Ukraine border kept for masking only â€” pure black outside, no tile download beyond bounds, border line not drawn and alert fills override white region borders

- Alerted cities now surface 25% earlier than their tier (RED/YELLOW only), so a red region shows at country view

- Fill and border colors are now darker (deep red/amber) than bright zone circles (gold/red), with fill mode white borders and border mode colored override

- Fixed offline notification firing after seconds instead of minutes by making the degraded/offline episode single-authority in SourceRegistry, removing duplicate transport timers

- Fixed battery drain during outages by throttling background monitor ticks to 30s when the screen is off â€” active threats or degraded connectivity no longer force 1s wakeups

- Switched alert map fills and city coverage to direct canonical boundary lookup, eliminating stem-heuristic mismatches on Latin and Cyrillic alert keys

- Threat markers now show only the last four ID digits, threat cards no longer expose IDs, and Locate zooms directly to the maximum normal map zoom

- Fixed aviation alerts expiring minutes after takeoff by exempting always-inner threats from age-based expiry

- Threat-ID labels on the map are now opt-in: a Logs â†’ System toggle shows the last four ID digits on markers, off by default

- Reframed "Just Fun" into "Morale" with Ukrainian heart emblem, uplifting spirits copy, and expanded search keywords

- Consolidated every hardcoded color in the app into one theme-owned palette (`AppPalette`), so the widget, map layers, notifications, and screens all share exact-tone token values

- Unified map alert fills, city labels, and widget badges onto a single shared alert-color palette, so red/yellow tones match everywhere

- Critical offline alerts now actually ring: with "Override silent mode" on, the alert chime plays through the alarm stream just like the sirens, so it cuts through vibrate/silent; notification channels were renamed to Inner/Outer zone alerts and the always-sound channels get enforced alarm attributes

- MiG-31K takeoff alerts now read in proper English ("Kinzhal carrier Â· Country-wide threat") instead of transliterated Ukrainian, in the card and notifications
- Removed the fake "MiG in N min" ETA: the takeoff pin is a country centroid, not a position, so no speed or arrival time is shown for it

- Trimmed dead APK weight: dropped the unused coil dependency, scoped release resources to EN/UK, preview tooling to debug builds
- Fixed notification sounds going silent after app updates by referencing sounds by name instead of resource ID, with versioned channel recreation so the silent-mode override toggle takes effect immediately
- Brief reconnect blips no longer reset offline timers to zero: only sub-3s flaps stitch back onto the ongoing episode, and the offline counter no longer restarts when retry is tapped (a retry in progress is still offline, and the episode only resets after a genuine recovery)
- "Ignore 30 min" no longer pauses reconnects: it only mutes offline/critical notifications for 30 minutes while the connection keeps trying, and tapping it clears the current offline/critical alerts; the tap logs an "Ignored" line
- Manual retry is now visible in the connections log as a "Manual retry" line instead of staying silently on the old Offline row
- Removed the pointless attempt count from offline notifications; Ignore 30 min is now always offered while offline
- Fixed airplane-mode recovery stalling when validation flapped mid-connect: the watchdog now rechecks network capabilities while down instead of waiting for another OS event, and the retry countdown no longer shows phantom waits
- Offline episode now survives reconnect attempts without wiping the live connection log, retry no longer resurrects swiped-away critical alerts, and the critical alert is titled "Offline during alert" when fired during an active alert
- Removed the dead NeptunDecoder layer superseded by NeptunRawDecoder and MonitorCoreImpl
- Critical offline alert now fires after 5 minutes offline â€” or after 1 minute during an active alert â€” with milestone notifications at 3/6/10/20 minutes
- Fixed weak-WiFi stalls where the connection sat offline without retrying: reconnects no longer depend on network-validation timing, duplicate disconnects schedule exactly one retry, and a stuck-state watchdog force-retries
- Removed the dead WsTransport/ConnectionSupervisor/NetworkMonitor layers; ResilientConnectionSupervisor is now the single connection path

- Fixed red/yellow alert region fills and city labels with canonical boundary IDs end-to-end (engine always computes fill tokens, unknown raions no longer fill their whole oblast, city labels turn red regardless of the fill toggle)
- Eliminated overlay lag during map panning by invalidating synchronously on touch drag instead of waiting for camera callbacks
- Fixed overlapping threats rendering in GRID, SPREAD, and COUNT modes with distance-based clustering and coordinate-relative screen offsets
- Restricted native libraries to 64-bit ARM (arm64-v8a) to reduce APK size to ~6MB
- Fixed background notification delay when going offline or entering airplane mode by reacting immediately to connection state changes
- Fixed alert region fills and city label colors across zoom levels with enhanced GPU layer styling

- Expanded map zoom-out and panning boundaries with buffered pan limits and synchronized direct overlay rendering for zero-lag markers

- Migrated the map rendering engine to MapLibre Native SDK with hardware-accelerated OpenGL raster/vector layers and removed osmdroid
- Added MapLibre GeoJSON layer manager and camera coordinator bridging boundaries, alert fills, and range circles
- Added MapLibreStyle and MapLibreHostView composable binding the dark Carto basemap to native Android lifecycle
- Added MapLibre Native SDK dependency and configured universal multi-ABI APK packaging
- Decoupled flourish, overlay, and city label rendering from osmdroid onto a native projection lambda layer
- Long-press shoot-down now works during active alerts, and shot threats reliably reappear after 2.1s
- 51 cities across all alert-capable oblasts promoted from MINOR to MEDIUM tier so their names appear at zoom 6.5 instead of 10.0 when under air-raid alert
- Explosion animation now varies per threat type â€” core flash colour, spark count/colour, ring intensity, icon shards, and optional HD extras (smoke puffs, debris)
- Fixed official-alert city scope notifications not sounding for cities whose name differs from their raion's name by resolving city raions via CityRaions
- Fixed ghosting and prematurely disappearing threats by removing unauthorized 120s engine-side pruning so threats persist according to their source's declared staleness and ghosting caps
- Simplified falling debris safety buffer into a pure countdown timer, removing extraneous city-tracking state and mirrors
- Red official-alert notifications now refresh silently when the deduced threat changes within the same episode â€” no more re-vibration on every reason update
- Deep refactor: the alert notification state machine (9 latches, 5 cancel paths, 5 post paths) collapsed into a single `Primary` reconcile â€” the notif identity is now a stable key, so ETA churn no longer re-sounds

- The auto shoot-down countdown now pauses while you manually shoot down a threat, then fires when the animation ends
- The tally-tap replay now groups shoot-downs by oblast (with a wider zoom for context) when All-of-Ukraine is on
- Opening a threat card no longer snaps the camera to your GPS position; every threat selection now settles the threat at the same on-screen anchor below the card
- The threat card icon now pops in instantly with a snappy spring (no 140 ms dead time)
- Red official-alert notifications now show the colour in their title (e.g. "Donetsk: red alert"), and the body states the exact region the alert source named
- Threat notifications (red-zone, yellow-zone and official alerts with a deduced threat) now show the estimated time until the threat reaches your location
- Yellow official-alert notifications are no longer instantly cancelled by the all-clear housekeeping while the alert is still active

- Deep refactor: the NEPTUN connection layer is now a source-agnostic source SPI (socket, decoder and reconnect supervisor split into separate pieces registered through a single source registry), so future threat data sources plug in without touching the engine or UI
- Death flourish overlay now renders in its own self-contained View (no full-map redraws during shoot-down)
- Group threat toggles now batch DataStore writes into a single disk operation for snappier response
- Icon packs (photo/army/comic/russian) converted from PNG to lossless WebP, reducing asset size by ~35%
- Tapping a threat whose card is already open now hides the card
- A locate icon below the threat card centres the map on the threat when tapped
- WebSocket now identifies as `Oko/<version> (Android)` so NEPTUN can contact us at scale instead of blanket-blocking
- Toggling off all sources now shows "Offline" immediately instead of "Backup" for 5 minutes
- Connections tab now shows "Offline" when a source is manually toggled off
- Removed threat/alert counts from the Logs screen header
- Zone-alert sirens are now logged as "notified" on the tick they fire, so the Decisions log's 24h count no longer under-reports real notifications
- The monitor notification's trident now mirrors the official alert level (like the header and the widget) instead of turning red for your own red-zone threats
- Yellow official alerts now finish with an all-clear, and all-clears always key on your region's raw alert ending â€” even when a red alert narrowed away from your city mid-episode

- In landscape, the zone/red & yellow alert pills and edit-zones gear stack vertically in the bottom-right corner, the shelter button sits in the bottom-left, and the "all alerts off" warning moves above the footer â€” the two redundant crossed-bell icons above each pill were removed
- The zone editor bottom sheet now scrolls in portrait, so the bottom sliders are reachable on shorter screens
- When following GPS with no fresh fix, startup now retries a fast cell-tower fix until the provider warms, and retries a precise GPS fix in parallel, so the location dot appears promptly on a fresh install without needing a restart
- Precise location refresh now retries GPS a few times before falling back to approximate (network) fix, and works when only coarse permission is granted
- Denying the location permission with "Don't ask again" now routes you to the app's settings screen
- GPS acquisition now starts the moment you grant permission in the onboarding wizard, instead of waiting for the wizard to finish
- Onboarding wizard now asks for location permission before threat selection, so GPS has more time to lock
- The NEPTUN attribution in the onboarding wizard now credits the Neptun team and links to their website
- The onboarding zones step now shows a real map screenshot for official fills with an explanation of red vs yellow, and the button preview matches the map with red + yellow zoom buttons
- GPS dot stays centred on the red/yellow zone epicentre at any zoom level; zone circles and orbit path are now geodesic
- Night mode toggle now lives on the collapsed card header, matching Just Fun
- Disabled sources no longer trigger an all-clear signal â€” last-known oblast alerts are kept, and toggling a source back on repaints the alert regions
- Official alerts in Settings > Alerts are now a master switch with independent Red and Yellow channels (notifications only â€” the header trident and map colors always show the live alert)
- The header trident turns red with a glow for an official red alert and amber for an official yellow alert; the header border shows your zone state (inner red
- Official yellow alerts now color the notification icon and widget accent
- Threat zones renamed to "Inner zone" and "Outer zone" so they are not confused with official red/yellow alerts
- The all-clear notification is only sent while at least one official channel (red or yellow) is on, but is always logged so no event is missed
- The official-alerts trident is now larger in Settings > Alerts
- Version names now derive directly from versionCode, making app and server JSON versions directly comparable

- Alert region borders in FILL mode now translucent (40% white) and thinner (1.2dp) so land details show through and borders don't blow out the map

- Alert fill polygons now round coordinates to 4 decimal places, close rings, and deduplicate adjacent points â€” eliminating fill leaks and mismatched boundaries

- Shelter view now zooms to street level (16.0 max instead of 19.0) so buildings and streets are visible without excessive detail

- Zone circles now match zone slider colors (red/amber instead of neon), circle widths slimmed to 1.5dp

