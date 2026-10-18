<div align="center">

# ListenStat

**Local listening / media play-time stats for Android**

*By day · month · year · per app · title Top 20 · data stays on device*

[简体中文](README.md)

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android-green.svg)](#)

</div>

## Overview

ListenStat is an **Android** local tracker. It reads the system “now playing” media session, accumulates play time per app and title, summarizes by day / month / year, and shows Top 10–20 titles.

- **Local-first**: stats stay on device; nothing is uploaded by default
- **Permissions**: notification / media-session access is required to read the control-center player
- **Privacy**: only package name, media title, duration, and timestamps—no passwords or contacts

## v1 scope

| Feature | Description |
|---------|-------------|
| Auto logging | Background listen for playing app + title |
| Time buckets | Day / month / year |
| Rankings | Per app and per title Top 20 |
| Storage | On-device DB / files |

## Status

Public repository ready; app implementation in progress.

## License

[MIT](LICENSE)
