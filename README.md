# Lean Cleaner

A small, private Android cleaner that combines the genuinely useful parts of **SD Maid**, **CCleaner** and
**Android Assistant**, and leaves out everything else.

- ~1.5 MB release APK
- No ads, trackers or analytics, no accounts, and **no INTERNET permission**
- Nothing runs in the background: no services, notifications or scheduled jobs
- Nothing is deleted until you've reviewed it, and one copy of every duplicate is always kept

## Features

| Tool | What it does | Inspired by |
|---|---|---|
| **Junk cleaner** | Finds app caches on shared storage, thumbnail caches, `*.tmp`/`*.log` files, empty folders, `LOST.DIR` fragments, PC/Mac leftovers (`.DS_Store`, `Thumbs.db`, `._*`, `.Trash-*`), old APK installers (pre-selected only when that app is already installed) and trashed media. On Android 11+ it can also clear **every app's internal cache at once** through the system dialog. | SD Maid SystemCleaner, CCleaner Quick Clean |
| **Leftovers** | Finds folders left behind by uninstalled apps in `Android/data`, `Android/obb`, `Android/media` and in shared storage (package-named folders plus a curated list such as `WhatsApp/`, `Telegram/`, `Pictures/Instagram`). Leftovers containing photos, videos or music are flagged and never pre-selected. | SD Maid CorpseFinder |
| **Duplicates** | Finds byte-identical files using a size → partial hash → SHA-256 pipeline, so most files are never fully read. Auto-select keeps the oldest, newest or shortest-path copy; the last copy can never be selected. | SD Maid Deduplicator, CCleaner duplicates |
| **Storage analyzer** | Breakdown by file type, a folder browser sorted by size (drill down and delete), and a list of your largest files with type filters. | SD Maid Storage Analyzer, CCleaner Analyze |
| **Apps** | All apps with size, data, cache and last-used date. Filter by user/system, show apps unused for 30+ days, sort by size, cache, last use or install date. Batch uninstall, export APKs (split apps become `.apks`), and jump to App info for clear cache / force stop. | SD Maid AppControl, CCleaner App Manager, Android Assistant batch uninstall |
| **System monitor** | Live CPU core frequencies, thermal state, memory, battery (level, temperature, current, voltage, health, estimated capacity, cycle count), storage and device details. | Android Assistant |
| **Exclusions** | Long-press any scan result (or use Settings) to protect a file or folder from every tool. | SD Maid exclusions |

### Deliberately left out (the "bloat")

- **RAM boosters / task killers.** Android manages memory itself; killing apps makes them cold-start
  again and costs battery. The monitor shows memory but has no "boost" button.
- **CPU coolers, battery savers, "deep clean" scores.** These are placebo on modern Android.
- **Background scanning, notifications, widgets, scheduled cleaning.** The app runs only when you open it;
  live stats stop polling when it leaves the screen.
- **Network features, cloud backup, accounts, ads, upsells.** The app can't reach the internet, and
  backups of its settings are disabled too.
- **Heavy dependencies.** Jetpack Compose and AndroidX core only: no DI framework, no database, no image
  loader, and no 10 MB icon pack (only the 42 icons used are bundled).

## Permissions

| Permission | Why |
|---|---|
| All files access (Android 11+) / Storage (Android 8–10) | Scan and clean shared storage. Requested when you open a storage tool. |
| Usage access (optional) | App sizes, cache sizes and last-used dates in Apps and the cache total in Junk cleaner. |
| Query all packages | Telling leftovers of uninstalled apps apart from folders of installed ones. |
| Request delete packages | Opening the system uninstall dialog. |

## What Android does and doesn't allow (no root)

- **Other apps' internal caches:** Android 11+ offers one system dialog that clears all of them, which the Junk
  cleaner uses. Clearing a *single* app's cache is only possible from its App info page (one tap away from
  the Apps screen). Android 8–10 offer no way to clear other apps' internal caches without root.
- **`Android/data` and `Android/obb`:** these are off-limits to every app on Android 11+, so their caches and
  leftovers can only be cleaned on Android 8–10. `Android/media` remains accessible.
- **Thermal sensors and some CPU stats** are blocked by many vendors; the monitor shows what the device exposes.

## Building

Requirements: JDK 17+ (21 recommended) and the Android SDK with platform 37. Android Studio sets this up
for you.

```bash
./gradlew testDebugUnitTest   # JVM unit tests for the scan/delete engines
./gradlew lintDebug           # Android lint
./gradlew assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease     # minified ~1.5 MB APK in app/build/outputs/apk/release/
```

**Release signing:** create an untracked `keystore.properties` in the project root:

```properties
storeFile=release.jks
storePassword=…
keyAlias=…
keyPassword=…
```

Without it, release builds are signed with the debug key so they still install.

**CI:** every push runs tests, lint and both builds on GitHub Actions and uploads the APKs as the
`lean-cleaner-apks` artifact. To have CI sign with your own key (so CI builds can update each other), add
the repository secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD`.

## Project layout

```
app/src/main/java/io/github/mdmac1983/leancleaner/
├── core/        Formatting, file kinds, exclusions, safe deletion, storage volumes, permissions, settings
├── junk/        JunkScanner (pure Kotlin) + screen
├── corpse/      CorpseFinder + known app folders + screen
├── dupes/       DuplicateFinder + screen
├── analyzer/    StorageAnalyzer (size tree) + screen
├── apps/        App list, sizes, usage, APK export + screen
├── monitor/     CPU / memory / battery / device readers + screen
├── dashboard/   Home screen
├── settings/    Permissions, exclusions, preferences
└── ui/          Theme, shared components, bundled icons
```

The scanners only use `java.io.File`, so they're covered by fast JVM unit tests under `app/src/test`. Every
deletion goes through `Deleter` + `SafetyGuard`, which refuse to delete storage roots, `Android`,
`Android/data|obb|media` themselves, or anything outside shared storage.

## Credits

Icon path data comes from [Material Icons](https://fonts.google.com/icons) (Apache License 2.0, The Android
Open Source Project).
