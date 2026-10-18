<div align="center">

# ListenStat

**Local Android listening-time tracker**

*Day / month / year · app & title Top 20 · data stays on device*

[简体中文](README.md) | **English**

[![License](https://img.shields.io/badge/license-MIT-blue)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-green)](https://github.com/dongzhongcen/ListenStat)

</div>

## Overview

ListenStat reads the system media sessions used by the control-center / media notification player and accumulates play time **on device only**:

- Ranges: today / this month / this year
- Top 20 by app
- Top 20 by content title (depends on what the player puts in `MediaMetadata`)

**Privacy: local Room database only — no upload, no account, no analytics SDK.**

Requires **Notification Listener** access. Uninstalling the app removes the data.

## Build

Open in Android Studio (Android 8.0+), or:

```bash
./gradlew :app:assembleDebug
```

## License

[MIT](LICENSE)
