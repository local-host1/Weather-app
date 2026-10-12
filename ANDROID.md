# Android build

`Weather-Android.apk` is a debug-signed installable Android package for testing. It supports Android 6.0 (API 23) and newer.

The app uses the Android-native implementation in `Android/`. It reproduces the Weather dashboard, expandable location list, locations editor, settings/themes, details with hourly and seven-day forecast tabs, News Feed, and a 2×3 home-screen widget that can be resized horizontally and vertically.

The widget refreshes with the device's saved weather location. Add it from the Android launcher’s Widgets screen, then resize it freely. Internet access is needed for weather, forecasts, and news.

## Build

Install Android Studio with Android SDK Platform 37 and JDK 17, then run:

```powershell
cd Android
.\gradlew.bat assembleDebug
```

The resulting file is `app/build/outputs/apk/debug/app-debug.apk`.

Android 6–12 asks for location permission while the app is open. Android 13 and newer also requires notification permission only if a future build adds notifications; this version does not request it.
