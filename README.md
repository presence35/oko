# Ukraine Drones · Українські дрони

Android app for monitoring publicly available Ukrainian air-threat data from the NEPTUN service.

The app displays live threat information on an OpenStreetMap-based map and can generate local audio/visual alerts when threats approach user-configured zones.

> **Important:** This is an experimental situational-awareness tool. It is **not an official warning system** and must not be relied upon as a sole source of safety information. Always follow official air-raid alerts and local authorities.

## Screenshots

To replace a capture, run the app (e.g. in an emulator) and use
`adb exec-out screencap -p > docs/screenshots/<name>.png`, keeping the filename.

| Map | Edit alert zones |
| --- | --- |
| <img src="docs/screenshots/map.png" width="220" alt="Live threat map"> | <img src="docs/screenshots/edit-zones.png" width="220" alt="Edit alert zones"> |

| Settings | Feature guide |
| --- | --- |
| <img src="docs/screenshots/settings.png" width="220" alt="Settings"> | <img src="docs/screenshots/feature-guide.png" width="220" alt="Feature guide"> |

## Features

* Live air-threat data from the public NEPTUN API
* WebSocket streaming with REST fallback
* Map display using OpenStreetMap / OSMdroid
* Support for multiple threat types, including UAVs, missiles and guided bombs
* User-configurable red/yellow monitoring zones
* Local siren, chime and notification alerts
* Optional location-based monitoring
* UA / EN interface
* Persistent foreground monitoring service
* Monitoring recovery after reboot and app/package restart
* Local settings stored with Android DataStore
* Experimental threat-level indicator
* Dead-reckoning / predicted threat positions when appropriate
* No user accounts
* No Firebase
* No analytics or advertising backend

## Location

The app normally uses the device's network location to determine which monitoring zones apply.

A GPS-based location refresh may also be requested when the app needs to obtain or improve a location fix.

Location processing is performed locally by the app and is not intentionally sent to the NEPTUN service.

Location availability and freshness can affect the accuracy of zone-based alerts.

## Threat data

Threat information is obtained from the public NEPTUN service:

* WebSocket: `wss://neptun.in.ua/api/v1/stream`
* REST fallback: `https://neptun.in.ua/api/v1/threats`

The app maintains a local threat state and combines streaming updates with REST snapshots when the live stream becomes unavailable or stale.

Network connectivity can fail independently of the app. A connection indicator is provided in the UI, but users should not assume that a connected state guarantees complete or perfectly current threat information.

## Threat prediction

For some fast-moving threats, the app can estimate a short-term predicted position using the reported position, heading, speed and timestamp.

Predicted positions are **estimates**, not direct observations. They are intentionally bounded and should not be interpreted as precise tracking.

## Alerts

Alerts are generated locally based on:

* current threat state
* threat type
* threat position / predicted position
* user-configured zones
* current device location
* alert settings

The app can also surface official alert-state information received from the data source.

Notification, audio, location, network and Android background restrictions can all affect alert delivery. The app should therefore be treated as a supplementary warning tool rather than a guaranteed alert mechanism.

## Threat level

The 0–10 threat-level indicator is an **experimental heuristic** based on factors such as threat type, distance, reliability, confirmations, position quality, staleness and estimated time to arrival.

It is **not an official threat assessment, probability, or prediction**, and the numeric value should not be interpreted as a calibrated measure of risk.

## Privacy

The project is designed to operate without user accounts or a dedicated application backend.

There is no Firebase integration, advertising SDK or analytics service.

The app does communicate with external services required for its functionality, including NEPTUN and map/tile providers. Network providers may therefore receive normal connection metadata such as the device IP address.

Threat data and map data are downloaded from their respective services. Local monitoring and alert calculations are performed on the device.

## Monitoring service

Continuous monitoring uses an Android foreground service.

The service is responsible for:

* maintaining the threat connection
* tracking local location
* evaluating configured zones
* generating local alerts
* recovering monitoring state after restart/reboot where Android permits it

Android system restrictions, battery-management settings and manufacturer-specific background restrictions can still affect long-running operation.

## Updates

The project includes an APK update mechanism for independently distributed builds.

This mechanism is intended for builds distributed outside Google Play. Google Play distribution has additional restrictions around APK installation and `REQUEST_INSTALL_PACKAGES`.

## Technology

* Kotlin
* Jetpack Compose
* Android Foreground Services
* Kotlin Coroutines / Flow
* DataStore
* OSMdroid
* OkHttp / WebSocket
* OpenStreetMap data

## Project status

This is an independent experimental project.

It is actively developed and tested, but it should not be considered safety-critical software. Network outages, upstream data errors, Android restrictions, stale location data, missing permissions, or other failures can result in delayed, missing or incorrect information.

## Disclaimer

This software is provided as-is, without any guarantee that threat information is complete, accurate, timely or available.

Do not use this application as a replacement for official Ukrainian air-raid warning systems, emergency instructions, or local authorities.

The developer is not responsible for decisions made solely on the basis of information displayed by the application.
