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

## Build

Open in Android Studio (Android 8.0+), or `./gradlew :app:assembleDebug`.

## License

[MIT](LICENSE)
