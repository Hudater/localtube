# Building LocalTube

## Prerequisites

- Android Studio (Ladybug or newer recommended) — or command-line Gradle
  + Android SDK if you'd rather not use the IDE
- JDK 17
- Android SDK with `compileSdk 35` / `minSdk 26` platforms installed

## Opening the project

1. `File > Open` in Android Studio, point it at the repo root
   (`localtube/`, the folder containing `settings.gradle.kts`).
2. Let Gradle sync. First sync will be slow (pulls Media3, Room, smbj,
   Compose, etc).
3. Run the `app` configuration on a device or emulator running API 26+.

## Command-line build

```bash
./gradlew assembleDebug
```

Debug APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

### Gradle wrapper

This repo does **not** commit `gradle/wrapper/gradle-wrapper.jar` (it's a
binary file). Before running `./gradlew` for the first time, generate it:

```bash
gradle wrapper --gradle-version 8.9
```

(requires a system-installed Gradle just for this one bootstrap step — or
let Android Studio's sync do it for you, which it does automatically).
CI does the same thing as its first step — see
`.github/workflows/build-and-release.yml`.

## Running tests

```bash
./gradlew testDebugUnitTest
```

`core-metadata`'s tests are the ones to run first if you're debugging a
metadata-parsing issue — see `docs/ARCHITECTURE.md`, "Verifying the
info.json schema", for why.

## Release signing (optional, for tagged releases)

`assembleRelease` works unsigned-for-store (falls back to debug signing)
if you don't set anything up. To produce a properly signed release APK —
which the GitHub Actions release workflow does automatically on a
`v*` tag push — four environment variables need to be available:

- `RELEASE_KEYSTORE_PATH`
- `RELEASE_KEYSTORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`

### One-time keystore setup

```bash
keytool -genkeypair -v -keystore release.keystore \
  -alias localtube -keyalg RSA -keysize 2048 -validity 10000
```

Keep `release.keystore` OUT of the repo. For CI, base64-encode it and
store it as a GitHub Actions secret:

```bash
base64 -w0 release.keystore > release.keystore.b64
```

Add these secrets in the private repo's Settings > Secrets and
variables > Actions:

| Secret name | Value |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | contents of `release.keystore.b64` |
| `RELEASE_KEYSTORE_PASSWORD` | keystore password |
| `RELEASE_KEY_ALIAS` | `localtube` (or whatever alias you chose) |
| `RELEASE_KEY_PASSWORD` | key password |

## Triggering a release build

Push a tag matching `v*`:

```bash
git tag v0.1.0
git push origin v0.1.0
```

The workflow builds a signed release APK and attaches it to a GitHub
Release. Every push to `main` (and every PR) also builds and
unit-tests a debug APK, uploaded as a workflow artifact, so you get a
sideloadable build without needing to cut a tag every time.

## Installing on your device

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

or download the APK from the GitHub Release / Actions artifact and
sideload it directly on-device.

## First-run setup on-device

1. Open the app, go to the source-selector dropdown > "Manage sources".
2. Add your local YTDLnis download folder (SAF folder picker).
3. Add your SMB share(s) (host, share name, path, optional credentials).
4. Pull-to-refresh on the home feed, or wait for the periodic background
   scan (every 6 hours by default — see `ScanScheduler`).
