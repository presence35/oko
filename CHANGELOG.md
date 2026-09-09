# Changelog

## [Unreleased]

- WebSocket now identifies as `Oko/<version> (Android)` so NEPTUN can contact us at scale instead of blanket-blocking / WebSocket тепер ідентифікується як `Oko/<version> (Android)`, щоб NEPTUN міг зв'язатися з нами при масштабуванні замість блокування
- Toggling off all sources now shows "Offline" immediately instead of "Backup" for 5 minutes / When all sources are disabled, the app immediately shows "Offline" instead of incorrectly displaying "Backup" for 5 minutes
- Connections tab now shows "Offline" when a source is manually toggled off / The connections tab now correctly records an offline episode when a source is manually disabled
- Removed threat/alert counts from the Logs screen header / Removed threat and alert count badges from the Logs screen header

- In landscape, the zone/red & yellow alert pills and edit-zones gear stack vertically in the bottom-right corner, the shelter button sits in the bottom-left, and the "all alerts off" warning moves above the footer — the two redundant crossed-bell icons above each pill were removed / In landscape, the zone/red & yellow alert pills and edit-zones gear stack vertically in the bottom-right corner, the shelter button sits in the bottom-left, and the "all alerts off" warning moves above the footer — the two redundant crossed-bell icons above each pill were removed
- The zone editor bottom sheet now scrolls in portrait, so the bottom sliders are reachable on shorter screens / The zone editor bottom sheet now scrolls in portrait, so the bottom sliders are reachable on shorter screens
- When following GPS with no fresh fix, startup now retries a fast cell-tower fix until the provider warms, and retries a precise GPS fix in parallel, so the location dot appears promptly on a fresh install without needing a restart / When following GPS with no fresh fix, startup now retries a fast cell-tower fix until the provider warms, and retries a precise GPS fix in parallel, so the location dot appears promptly on a fresh install without needing a restart
- Precise location refresh now retries GPS a few times before falling back to approximate (network) fix, and works when only coarse permission is granted / Precise location refresh now retries GPS a few times before falling back to approximate (network) fix, and works when only coarse permission is granted
- Denying the location permission with "Don't ask again" now routes you to the app's settings screen / Denying the location permission with "Don't ask again" now routes you to the app's settings screen
- GPS acquisition now starts the moment you grant permission in the onboarding wizard, instead of waiting for the wizard to finish / GPS acquisition now starts the moment you grant permission in the onboarding wizard, instead of waiting for the wizard to finish
- GPS dot stays centred on the red/yellow zone epicentre at any zoom level; zone circles and orbit path are now geodesic / GPS dot stays centred on the red/yellow zone epicentre at any zoom level; zone circles and orbit path are now geodesic

