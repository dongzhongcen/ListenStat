<div align="center">

# ListenStat

**Android / Windows media play-time tracker (听时)**

*Day / month / year · app & title Top 20 · achievements · local by default · optional cloud sync · phone ↔ PC pairing*

[简体中文](README.md) | **English**

[![License](https://img.shields.io/badge/license-MIT-blue)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%208.0%2B%20%7C%20Windows-green)](https://github.com/dongzhongcen/ListenStat)

</div>

## Status

**v0.2.3** — optional cloud sync (off by default in Settings). When enabled, registers with a stable device id against a self-hosted sync API and push/pulls day listening aggregates (package, title, day, duration). Wallpaper images are never uploaded. With sync off, behaviour matches the local-only app. Built on v0.2.2.

**Windows desktop (`desktop/`)** — Electron client that can read Windows “Now playing” to track PC listening time, and optionally pair with the phone after sync is enabled. Installers will appear on the same [Releases](https://github.com/dongzhongcen/ListenStat/releases) page; see [`desktop/README.md`](desktop/README.md) for build steps.

**v0.2.2** — tap an app in the Top-apps ranking to open a local detail screen: period total, titles under that package, and the same day/month/year switching as the stats page. Built on v0.2.1.

**v0.2.1** — track any app that publishes a system media session (music, podcasts, video, browsers that expose sessions; blank titles fall back to the app name), and shorter Settings copy. Built on v0.2.0. Test builds are on [Releases](https://github.com/dongzhongcen/ListenStat/releases) (pre-release `v0.2.1-test`).

## Overview

ListenStat reads system media sessions from **any** app that publishes one (music, podcasts, video players, browsers that expose sessions, etc.) and accumulates play time **on device only**. If the system has no media session, nothing can be recorded.

- **Android**: Notification Listener access (same source as the control-center media player)
- **Windows**: the desktop app under [`desktop/`](desktop/) reads System Media Transport Controls (“Now playing” in the volume flyout); tray and launch-at-login are optional

- **Day / month / year**, and jump to **any day, month or year**: prev/next arrows, a horizontal date strip, or tap the date for a calendar / month / year picker
- **Top 20 by app** and **Top 20 by title** (title depends on the player writing media metadata; blank titles fall back to the app name)
- "Now listening" card with the current segment and today's total; in-progress time is added to stats only when the selected period includes now
- **Achievements** (local only): first listen, 1 / 10 / 100 / 500 hours total, 3 / 7 / 30-day streaks, 3 hours in one day, night owl (listening 0–5 am), 50 different titles… computed from local data when the app is opened or resumed, with a subtle notice when one unlocks
- **Wallpaper background**: pick an image with the system photo picker, adjust blur and veil; cards turn semi-transparent; removable any time
- **Backup / restore**: export to a JSON file; import merges without duplicates
- **Update check**: on app open (at most once a day) or manually, against GitHub Releases; can be turned off in Settings (independent of cloud sync)
- **Cloud sync (optional, off by default)**: when enabled in Settings, registers the device and syncs day listening aggregates; with the toggle off, no sync network traffic. Wallpaper is never uploaded

## Permissions & privacy

| Permission | Purpose |
| --- | --- |
| Notification Listener access | read system media sessions (required for tracking) |
| `INTERNET` | (1) optional GitHub Releases update check; (2) optional cloud sync API when the user turns sync on |

- By default all records stay in the on-device Room database (package name, title, artist, timestamps)
- **Cloud sync is off by default.** With it off, nothing is uploaded and no sync endpoints are called
- When sync is on, the app uploads a stable device id, an optional display name, and day aggregates (`package` / `title` / `day` / `duration_ms`). **Wallpaper images are never uploaded.** No ads or third-party analytics SDK
- Phone and Windows can be merged into one account with a **pairing code** (both sides need sync on); unpaired devices stay separate accounts
- Update check is independent of sync and only reads public release metadata
- The sync device token is stored in EncryptedSharedPreferences (or equivalent private storage) and is never written to logs
- Wallpaper comes from the system photo picker and is copied into app-private storage; backups are read/written via the system file picker — **no storage or media permission**
- No notification permission, no foreground service, no persistent notification
- Uninstalling the app removes all data

### Cloud sync notes

1. Settings → “云端同步” (Cloud sync) toggle (default off) → on enable, register + initial push/pull
2. Optional display name; renaming while sync is on PATCHes the server
3. Advanced: server base URL (default `https://jiketianxiawang.top`). Debug builds may use `http://IP` for lab testing; release is HTTPS-only
4. Pull merge: per `record_id` (derived from package+title+day) keep the **max** of local vs remote duration — if remote is larger, insert a synthetic session for the gap only; **never delete or shorten existing local rows**
5. **Pairing**: one device creates a short-lived code; the other claims it to join the same account (server-ready; phone UI follows in a small update)

## Backup & restore

1. Settings → "备份 / 恢复" (Backup / Restore) → Export, choose a location in the system file picker → `ListenStat-backup-<date>.json`
2. On a new phone or after reinstalling: Settings → Backup / Restore → Import, pick the file
3. Import **merges**: records that already exist (same start time + package name + title) are skipped; achievements keep the earliest unlock time; settings (auto update check, wallpaper blur/veil) are restored too (the wallpaper image itself is not part of the backup)

## Upgrading

- v0.2.3 / v0.2.2 / v0.2.1 / v0.2.0 are signed with **the same certificate** as v0.1.0 / v0.1.1-test, so they install over them **in place — no uninstall, all records kept**
- The database moves from version 1 to 2 with an explicit migration (adds an achievements table and a de-dup index); existing data is never dropped. Schemas are exported under `app/schemas/`

## Windows desktop

Source lives in [`desktop/`](desktop/). On Windows:

```bat
cd desktop
npm install
npm start
npm run dist:win
```

Artifacts land in `desktop/release/` (NSIS installer and portable). See [`desktop/README.md`](desktop/README.md). Electron / Chromium upstream licenses ship with the installer.

## Limits

- Android and Windows; iOS cannot offer the same approach
- Requires a system media session (Android MediaSession / Windows SMTC); apps that never publish one cannot be tracked
- Title detail depends on `MediaMetadata`; blank titles fall back to the app name
- Counts wall-clock time while the session is Playing

## Battery

- **Event-driven**: records only on play / pause / track or chapter change / session gone — no 15 s polling, no timed wakeups
- While playing, only the start time is kept in memory; one database write when the segment ends; segments under 3 s are ignored
- If the process is killed, the next start finalizes the last segment using the system-recorded exit time (no invented time)
- **No foreground service, no persistent notification, no wake lock**; the UI timer ticks only while the screen is visible and something is playing
- Achievements are computed only when the app is opened/resumed (no background work); by default the only network use is the daily-at-most version check (can be disabled); cloud sync must be turned on separately
- Tip: only if the system keeps killing ListenStat and stats get interrupted, set it to "unrestricted" in battery optimization

## Build

`./gradlew :app:assembleDebug` · `./gradlew :app:testDebugUnitTest` (includes a database migration check).

Release signing is read from `~/.gradle/gradle.properties` or environment variables (never committed):
`LISTENSTAT_KEYSTORE`, `LISTENSTAT_KEYSTORE_PASSWORD`, `LISTENSTAT_KEY_ALIAS`, `LISTENSTAT_KEY_PASSWORD`, then `./gradlew :app:assembleRelease`. Without them the release falls back to the local debug key (which cannot upgrade an install signed with the project key).

## Credits

- Fonts: Poppins, Shadows Into Light (both SIL Open Font License 1.1; license texts in `app/src/main/assets/licenses/`)

## License

[MIT](LICENSE)
