pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "LocalTube"

// core-model: plain Kotlin data classes (Video, Channel, Playlist, Source).
// No Android dependency. Reusable as-is in a future KMP/server/web backend.
include(":core-model")

// core-metadata: parses yt-dlp .info.json sidecars into core-model types.
// Pure Kotlin + kotlinx.serialization, no Android dependency.
// This is the module to touch first if yt-dlp's info.json shape changes,
// or if a second metadata source (a different downloader's format) needs
// support.
include(":core-metadata")

// core-filesystem: abstracts "list files under a source" and "open a
// stream to a file" across LOCAL / SMB / NFS. Android-dependent (SAF),
// but has no UI and no Room dependency, so it is the layer to reimplement
// (not reuse) if this logic ever needs to run on a server instead.
include(":core-filesystem")

// core-data: Room database, repositories, and the background scan/sync
// engine. Depends on core-model, core-metadata, core-filesystem.
// This is the "business logic" layer — an iOS or web client would not
// reuse this module directly (Room is Android-only) but would reimplement
// the same repository contracts against SQLDelight/SQLite/a server API.
include(":core-data")

// core-player: Media3 ExoPlayer setup, including the custom SMB
// DataSource. Android/Media3-specific, not reusable outside Android.
include(":core-player")

// app: Compose UI + ViewModels + navigation. The only module that should
// import Compose, Activity, or ViewModel classes.
include(":app")
