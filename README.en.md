<div align="center">

# ListenStat

**Local Android listening-time tracker (听时)**

*Day / month / year (any date) · app & title Top 20 · achievements · data stays on device*

[简体中文](README.md) | **English**

[![License](https://img.shields.io/badge/license-MIT-blue)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-green)](https://github.com/dongzhongcen/ListenStat)

</div>

## Status

**v0.2.0** — update check, wallpaper background, achievements, backup/restore, free date switching and a new warm UI. Test builds are on [Releases](https://github.com/dongzhongcen/ListenStat/releases) (pre-release `v0.2.0-test`).

## Overview

ListenStat reads system media sessions (same source as the control-center media player) and accumulates play time **on device only**.

- **Day / month / year**, and jump to **any day, month or year**: prev/next arrows, a horizontal date strip, or tap the date for a calendar / month / year picker
- **Top 20 by app** and **Top 20 by title** (depends on the player writing media metadata)
- "Now listening" card with the current segment and today's total; in-progress time is added to stats only when the selected period includes now
- **Achievements** (local only): first listen, 1 / 10 / 100 / 500 hours total, 3 / 7 / 30-day streaks, 3 hours in one day, night owl (listening 0–5 am), 50 different titles… computed from local data when the app is opened or resumed, with a subtle notice when one unlocks
- **Wallpaper background**: pick an image with the system photo picker, adjust blur and veil; cards turn semi-transparent; removable any time
- **Backup / restore**: export to a JSON file; import merges without duplicates
- **Update check**: on app open (at most once a day) or manually, against GitHub Releases; can be turned off in Settings

## Permissions & privacy

| Permission | Purpose |
| --- | --- |
| Notification Listener access | read system media sessions (required for tracking) |
| `INTERNET` | **only** to query GitHub Releases for a newer version |

- Local Room database only (package name, title, artist, timestamps)
- **No data is uploaded**, no account, no analytics/ads SDK; the update check only reads public release info and sends no statistics or identifiers
- Wallpaper comes from the system photo picker and is copied into app-private storage; backups are read/written via the system file picker — **no storage or media permission**
- No notification permission, no foreground service, no persistent notification
- Uninstalling the app removes all data

## Backup & restore

1. Settings → "备份 / 恢复" (Backup / Restore) → Export, choose a location in the system file picker → `ListenStat-backup-<date>.json`
2. On a new phone or after reinstalling: Settings → Backup / Restore → Import, pick the file
3. Import **merges**: records that already exist (same start time + package name + title) are skipped; achievements keep the earliest unlock time; settings (auto update check, wallpaper blur/veil) are restored too (the wallpaper image itself is not part of the backup)

## Upgrading

- v0.2.0 is signed with **the same certificate** as v0.1.0 / v0.1.1-test, so it installs over them **in place — no uninstall, all records kept**
- The database moves from version 1 to 2 with an explicit migration (adds an achievements table and a de-dup index); existing data is never dropped. Schemas are exported under `app/schemas/`

## Battery

- **Event-driven**: records only on play / pause / track or chapter change / session gone — no 15 s polling, no timed wakeups
- While playing, only the start time is kept in memory; one database write when the segment ends; segments under 3 s are ignored
- If the process is killed, the next start finalizes the last segment using the system-recorded exit time (no invented time)
- **No foreground service, no persistent notification, no wake lock**; the UI timer ticks only while the screen is visible and something is playing
- Achievements are computed only when the app is opened/resumed (no background work); the only network use is the daily-at-most version check (can be disabled)
- Tip: only if the system keeps killing ListenStat and stats get interrupted, set it to "unrestricted" in battery optimization

## Build

`./gradlew :app:assembleDebug` · `./gradlew :app:testDebugUnitTest` (includes a database migration check).

Release signing is read from `~/.gradle/gradle.properties` or environment variables (never committed):
`LISTENSTAT_KEYSTORE`, `LISTENSTAT_KEYSTORE_PASSWORD`, `LISTENSTAT_KEY_ALIAS`, `LISTENSTAT_KEY_PASSWORD`, then `./gradlew :app:assembleRelease`. Without them the release falls back to the local debug key (which cannot upgrade an install signed with the project key).

## Credits

- Fonts: Poppins, Shadows Into Light (both SIL Open Font License 1.1; license texts in `app/src/main/assets/licenses/`)

## License

[MIT](LICENSE)
