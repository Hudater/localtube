# LocalTube

Personal Android app: a YouTube-mobile-style UI over a local library of
already-downloaded videos. Not a downloader — a player + browser for
videos downloaded by external yt-dlp-based tools (YTDLnis, Pinchflat,
etc), sourced from local storage and/or SMB/NFS network shares.

## Why this exists

Built to replace the stock YouTube app as a viewing surface for 100GB+ of
downloaded videos ahead of a custom-ROM clean flash, and to keep working
the same way regardless of which specific downloader tool produced the
files.

## Docs

- [`docs/BUILD.md`](docs/BUILD.md) — how to build, sign, and release
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — module boundaries, key
  decisions, and known gaps (read this before assuming something is
  finished)

## Tech stack

Kotlin, Jetpack Compose, Media3 ExoPlayer, Room, `smbj`, WorkManager.
No cross-platform framework, no DI framework — see
`docs/ARCHITECTURE.md` for why.

## Status

Initial scaffold. See `docs/ARCHITECTURE.md`, "Known gaps / open items",
for what's stubbed vs finished.
