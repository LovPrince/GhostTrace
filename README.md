# 👻 GhostTrace
**Deleted App Forensics** — by @princeyy

Scans Android `UsageStatsManager` events to find apps that were once
active on the device but are now uninstalled, showing foreground time,
session count, and per-day activity.

---

## Features
- 🔍 Detects all deleted/uninstalled apps from the usage log
- 📊 Bar chart of top apps by foreground time (MPAndroidChart)
- 🏷 Auto-tags GAME and SYS apps
- ⬇ Expandable rows with per-day session timeline
- 💾 Copy / Save .TXT / Share export with branded header
- 🔃 Filter: All / Used > 0s / Games
- 🔀 Sort by last active or total FG time

---

## Build in Android Studio

1. Open Android Studio → **Open** → select this `GhostTrace` folder
2. Wait for Gradle sync to finish
3. Connect your device (USB debugging on)
4. **Run ▶**
5. On first launch: tap **Grant Permission** → enable **GhostTrace** in
   Usage Access settings → go back

---

## Build from Termux (on-device)

```bash
# Install Java & Gradle
pkg install openjdk-17 gradle

# Clone/copy project to ~/GhostTrace
cd ~/GhostTrace

# Build debug APK
./gradlew assembleDebug

# Install on device (requires adb or root)
adb install app/build/outputs/apk/debug/app-debug.apk
```

> Note: MPAndroidChart is fetched from JitPack. Add to your top-level
> `build.gradle` repositories block if needed:
> ```gradle
> maven { url 'https://jitpack.io' }
> ```

---

## Permissions

- `PACKAGE_USAGE_STATS` — read-only usage event log. Must be granted
  manually in Settings → Digital Wellbeing / Usage Access.

No internet permission. No data leaves the device.

---

## Package
`com.princeyy.ghosttrace`
