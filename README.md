# Lean Cleaner

A small, private Android cleaner that combines the genuinely useful parts of **SD Maid**, **CCleaner** and
**Android Assistant**, and leaves out everything else.

- ~1.6 MB release APK
- No ads, trackers or analytics, no accounts, and **no INTERNET permission**
- Nothing runs in the background: no notifications, no wakeups or scheduled jobs. The only service is the optional
  cache-cleaning accessibility service; while it's switched on Android keeps it loaded, but it ignores
  everything until you start a run (you can switch it off again after use)
- Nothing is deleted until you've reviewed it, and one copy of every duplicate is always kept

## Features

| Tool | What it does | Inspired by |
|---|---|---|
| **Junk cleaner** | Finds app caches on shared storage, thumbnail caches, `*.tmp`/`*.log` files, empty folders, `LOST.DIR` fragments, PC/Mac leftovers (`.DS_Store`, `Thumbs.db`, `._*`, `.Trash-*`), old APK installers (pre-selected only when that app is already installed) and trashed media. | SD Maid SystemCleaner, CCleaner Quick Clean |
| **One-tap app cache cleaning** | Clears the internal cache of every app (or the apps you select) with one tap: an optional accessibility service opens each app's *Storage* page in Settings and presses *Clear cache* for you, behind a progress screen with a Stop button. On Android 11+ the system's own "clear all caches" dialog is offered too. | SD Maid AppCleaner, CCleaner |
| **Leftovers** | Finds folders left behind by uninstalled apps in `Android/data`, `Android/obb`, `Android/media` and in shared storage (package-named folders plus a curated list such as `WhatsApp/`, `Telegram/`, `Pictures/Instagram`). Leftovers containing photos, videos or music are flagged and never pre-selected. | SD Maid CorpseFinder |
| **Duplicates** | Finds byte-identical files using a size → partial hash → SHA-256 pipeline, so most files are never fully read. Auto-select keeps the oldest, newest or shortest-path copy; the last copy can never be selected. | SD Maid Deduplicator, CCleaner duplicates |
| **Storage analyzer** | Breakdown by file type, a folder browser sorted by size (drill down and delete), and a list of your largest files with type filters. | SD Maid Storage Analyzer, CCleaner Analyze |
| **Apps** | All apps with size, data, cache and last-used date. Filter by user/system, show apps unused for 30+ days, sort by size, cache, last use or install date. Batch uninstall, export APKs (split apps become `.apks`), and jump to App info for clear cache / force stop. | SD Maid AppControl, CCleaner App Manager, Android Assistant batch uninstall |
| **System monitor** | Live CPU core frequencies, thermal state, memory, network traffic (Wi-Fi and mobile download/upload speeds in KB/s or MB/s, plus totals since boot), battery (level, temperature, current, voltage, health, estimated capacity, cycle count), storage and device details. | Android Assistant |
| **Widgets** | *System stats* (battery, RAM, storage and Wi-Fi speed in one widget) and *Quick clean* (scan for junk, or clear all app caches with one tap). | Android Assistant, CCleaner |
| **Exclusions** | Long-press any scan result (or use Settings) to protect a file or folder from every tool. | SD Maid exclusions |

### Deliberately left out (the "bloat")

- **RAM boosters / task killers.** Android manages memory itself; killing apps makes them cold-start
  again and costs battery. The monitor shows memory but has no "boost" button.
- **CPU coolers, battery savers, "deep clean" scores.** These are placebo on modern Android.
- **Background scanning, notifications, scheduled cleaning.** The app works only when you open it (the optional
  accessibility service only acts during a cleaning run you start); live stats stop polling when it leaves the
  screen. The optional System stats widget refreshes about once a minute, and only while the phone is awake
  anyway: it never wakes the phone and does nothing while the screen is off.
- **Network features, cloud backup, accounts, ads, upsells.** The app can't reach the internet, and
  backups of its settings are disabled too.
- **Heavy dependencies.** Jetpack Compose and AndroidX core only: no DI framework, no database, no image
  loader, and no 10 MB icon pack (only the 42 icons used are bundled).

### Widgets

- **System stats** (4×2, resizable): battery level, temperature and status; RAM and storage use; and the current
  Wi-Fi download and upload speed (it switches to mobile data while that's what's carrying traffic). Tap a gauge to
  open the System monitor, or the network cell to refresh right away. Android lets widgets update only now and
  then, so the speed is a one-second measurement taken at each refresh, not a live counter; the System monitor
  shows live speeds.
- **Quick clean** (2×2): *Scan junk* opens the Junk cleaner and starts a scan (you still review everything before
  it's deleted); *Clear caches* starts one-tap app cache cleaning. It also shows how much space has been freed.

Network speeds cover all apps and come from the system's traffic counters, so no network permission is needed.
"Wi-Fi" also counts Ethernet and tethering.

## Permissions

| Permission | Why |
|---|---|
| All files access (Android 11+) / Storage (Android 8–10) | Scan and clean shared storage. Requested when you open a storage tool. |
| Usage access (optional) | App sizes, cache sizes and last-used dates in Apps and the cache total in Junk cleaner. |
| Query all packages | Telling leftovers of uninstalled apps apart from folders of installed ones. |
| Request delete packages | Opening the system uninstall dialog. |
| Accessibility service (optional) | One-tap cache cleaning. Only active during a run you start, only acts on the Settings app, never presses anything but "Clear cache" (and a cache-confirmation "OK" on phones that ask). |

## What Android does and doesn't allow (no root)

- **Other apps' internal caches:** apps can't clear each other's caches directly. Lean Cleaner offers two ways:
  the one-tap accessibility automation (Android 8+), and on Android 11+ the system's "clear all caches" dialog.
- **`Android/data` and `Android/obb`:** these are off-limits to every app on Android 11+, so their caches and
  leftovers can only be cleaned on Android 8–10. `Android/media` remains accessible.
- **Thermal sensors and some CPU stats** are blocked by many vendors; the monitor shows what the device exposes.

### One-tap cache cleaning: setup and limits

1. Tap **Clear all (one tap)** in the Junk cleaner (or *Clear cache* in Apps) and follow the setup dialog to turn
   on *Lean Cleaner: clear app caches* under Settings > Accessibility.
2. **Android 13+ and an APK installed from a file or browser:** Android first blocks the toggle as a
   "restricted setting". After you've seen that message once, open Settings > Apps > Lean Cleaner, tap **⋮** and
   choose **Allow restricted settings**, then turn the service on.
3. Android shows a reminder about a day later that the app "can view and control your screen". That's the
   standard notice for non-assistive accessibility services; you can leave it on or switch it off after use.

It reads the button labels from your phone's own Settings app, so it works in any language. It's built for
stock Android/Pixel (8–16), Samsung One UI and Settings apps that follow the same App info > Storage > Clear
cache path (Motorola, Nokia, OnePlus, OPPO, realme, …). Xiaomi/MIUI/HyperOS use a different flow and aren't
supported. If the buttons can't be found (for example on Settings builds that hide them from accessibility),
the run stops after three apps without changing anything and suggests the system dialog instead. Android 17's
optional Advanced Protection mode blocks this kind of service entirely.

Safety rules: only exact label matches are clicked; nothing that mentions "Clear storage", "Clear data",
"Force stop" or "Uninstall" is ever pressed; confirmation dialogs are only accepted if they're about the cache.
The navigation logic is tested against a simulated Settings app covering Android 8–16 layouts.

## Building

Requirements: JDK 17+ (21 recommended) and the Android SDK with platform 37. Android Studio sets this up
for you.

```bash
./gradlew testDebugUnitTest   # JVM unit tests for the scan/delete engines
./gradlew lintDebug           # Android lint
./gradlew assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease     # minified ~1.6 MB APK in app/build/outputs/apk/release/
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

**Releases:** with those secrets in place, run *Actions > Release > Run workflow* (or push a tag like `v1.1.0`)
to publish a GitHub Release with the signed APK and its SHA-256 checksum. Bump `versionCode` and `versionName`
in `app/build.gradle.kts` first: the workflow refuses a tag that doesn't match `versionName` or that already
exists on another commit, and it refuses to publish without the signing secrets, so every release is signed
with the same key and installs as an update.

## Project layout

```
app/src/main/java/io/github/mdmac1983/leancleaner/
├── core/        Formatting, file kinds, exclusions, safe deletion, storage volumes, permissions, settings
├── junk/        JunkScanner (pure Kotlin) + screen
├── corpse/      CorpseFinder + known app folders + screen
├── dupes/       DuplicateFinder + screen
├── analyzer/    StorageAnalyzer (size tree) + screen
├── apps/        App list, sizes, usage, APK export + screen
├── automation/  Accessibility cache cleaning: Settings navigation (pure Kotlin), service, setup UI
├── monitor/     CPU / memory / battery / network traffic / device readers + screen
├── widget/      System stats and Quick clean home screen widgets
├── dashboard/   Home screen
├── settings/    Permissions, exclusions, preferences
└── ui/          Theme, shared components, bundled icons
```

The scanners only use `java.io.File` and the Settings automation works on a small `UiNode` abstraction, so
both are covered by fast JVM unit tests under `app/src/test`. Every
deletion goes through `Deleter` + `SafetyGuard`, which refuse to delete storage roots, `Android`,
`Android/data|obb|media` themselves, or anything outside shared storage.

## Credits

Icon path data comes from [Material Icons](https://fonts.google.com/icons) (Apache License 2.0, The Android
Open Source Project).
