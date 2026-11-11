# CCU825 Temperature Monitor

**Repository name:** `ccu825-temperature-monitor`

**Description:** Autonomous Android application built with Jetpack Compose for direct polling of CCU825 controller / `ccu.sh`, temperature and sensor visualization, local offline storage, background polling via WorkManager, Google Drive backup import/export, bilingual UI (English / Russian), and interactive Compose charts.

---

## 🚀 Key Features

1. **Direct Controller Polling**:
   - HTTP/HTTPS requests to `ccu.sh` or the CCU825 controller directly from the app (manual or scheduled).
   - Authentication support: Server URL, **Username**, and **Password** (HTTP Basic Authentication / query parameters).
2. **Local Offline Storage (`ccu_readings.json`)**:
   - All measurements are stored locally in the device's app-internal storage.
   - Automatic record merging and timestamp-based deduplication.
   - Charts and history render instantly offline without requiring a cloud backend.
3. **Background Polling (WorkManager)**:
   - Configurable background data update intervals (Off, 15, 30, 60 minutes).
4. **Backup & Import (Google Drive / SAF)**:
   - **Export Backup** and **Import File** buttons in Settings.
   - Ability to import previous backups or historical data exported from Google Sheets.
5. **Bilingual UI (English / Russian)**:
   - Interface language switching in Settings.
6. **Interactive Charts & Adaptive Design**:
   - Custom canvas-rendered charts with touch inspection slice, automatic axis scaling, and latest value badges.
   - Period filters: **7, 15, 30 days** or custom date picker ranges.
   - Full landscape mode support and responsive legend grid.

---

## 📁 Project Structure

```text
ccu825-temperature-monitor/
├── android/                                    # Android Studio / Kotlin / Jetpack Compose
│   └── app/src/main/java/com/example/ccu825/
│       ├── MainActivity.kt                      # Main screen, filters, chart, and settings
│       ├── data/
│       │   ├── CcuApiClient.kt                 # Direct HTTP polling with auth for CCU825 / ccu.sh
│       │   └── LocalRepository.kt              # Local JSON storage & import/export
│       └── worker/
│           └── CcuFetchWorker.kt               # Background periodic worker via WorkManager
```

---

## ⚡ Quick Start

1. Open the `android/` folder in Android Studio.
2. Build and run the app on your connected Android device.
3. Tap **Settings** and configure:
   - **Controller / ccu.sh URL** (e.g., `https://ccu.sh/api/…`).
   - **Username** and **Password** (if required).
   - **Interface Language** (English / Russian).
   - **Background Polling Interval** (Off, 15, 30, or 60 minutes).
4. Tap **Save**, then **Fetch from server**.
5. To import existing history from a backup file, use **Import File** in Settings.

---

## 🛠️ Android Build Instructions

Requires Android Studio, JDK 17 / 21, and Android SDK Platform 35.

```bash
cd android
./gradlew assembleDebug
```

Windows:

```bat
cd android
gradlew.bat assembleDebug
```

The debug APK is generated at:
`android/app/build/outputs/apk/debug/app-debug.apk`

---

## 📊 CCU825 Inputs

The app supports 16 independent CCU825 inputs:

1. `Volume`
2. `Smoke 2nd fl`
3. `Hatch UGBO`
4. `Outdoor °C` *(ADC conversion)*
5. `Leakage`
6. `2nd floor °C` *(ADC conversion)*
7. `Fireplace heat`
8. `Level UGBO`
9. `Garage gate`
10. `Garage vibro`
11. `Garage smoke`
12. `1st floor °C` *(ADC conversion)*
13–16. `Input 13`–`Input 16`

Each input can be customized with custom names or hidden from the chart in Settings.
