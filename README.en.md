<div align="center">

# ListenStat

**Local Android listening-time tracker (听时)**

*Day / month / year · app & title Top 20 · data stays on device*

[简体中文](README.md) | **English**

[![License](https://img.shields.io/badge/license-MIT-blue)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-green)](https://github.com/dongzhongcen/ListenStat)

</div>

## Status

**MVP is on `main`** — build and install locally to try.

## Overview

ListenStat reads system media sessions (same source as the control-center media player) and accumulates play time **on device only**.

**Privacy: local Room database only (package name, title, artist, timestamps) — no upload, no account, no analytics SDK.** Requires Notification Listener access.

## Battery

- **Event-driven**: records only on play / pause / track or chapter change / session gone — no 15 s polling, no timed wakeups
- While playing, only the start time is kept in memory; one database write when the segment ends; segments under 3 s are ignored
- If the process is killed, the next start finalizes the last segment using the system-recorded exit time (no invented time)
- **No network, no foreground service, no persistent notification**; the UI timer ticks only while the screen is visible
- Tip: only if the system keeps killing ListenStat and stats get interrupted, set it to "unrestricted" in battery optimization

## Build

Open in Android Studio (Android 8.0+), or `./gradlew :app:assembleDebug`.

## License

[MIT](LICENSE)
