# Usoft Weather

A real-time weather app for Windows and Android with saved locations, hourly and seven-day forecasts, local news, and Midnight, Ocean, and Light themes.

## Android

Install `Weather-Android.apk` on Android 6.0 (API 23) or newer. It uses the native Android interface shown below and includes a resizable 2×3 home-screen widget. See [ANDROID.md](ANDROID.md) for build and widget details.

## Windows

Double-click `Weather.exe`. Python, an API key, and Ollama are not required. The app needs an internet connection for live weather and news. Click the Weather title for local news, the arrow beside conditions for weather details, and the menu for settings.

## App screenshots

### Desktop and home screen widgets

<p align="center">
  <img src="Screenshot 2026-10-05 224605.png" width="30%" alt="Widget Dark Mode" />
  <img src="Screenshot 2026-10-05 224620.png" width="30%" alt="Widget Blue Accent" />
  <img src="Screenshot 2026-10-05 224631.png" width="30%" alt="Widget Light Mode" />
</p>

### Main detail views

<p align="center">
  <img src="Screenshot 2026-10-05 224724.png" width="18%" alt="Weather News Feed" />
  <img src="Screenshot 2026-10-05 224705.png" width="13.7%" alt="Weather Metrics" />
  <img src="Screenshot 2026-10-05 224758.png" width="18%" alt="General Preferences" />
  <img src="Screenshot 2026-10-05 225007.png" width="18%" alt="Locations" />
</p>

### Location views

<p align="center">
  <img src="Screenshot 2026-10-05 224951.png" width="18%" alt="Colombo forecast" />
  <img src="Screenshot 2026-10-05 225035.png" width="18%" alt="Saved cities" />
  <img src="Screenshot 2026-10-05 225059.png" width="18%" alt="Location directory" />
</p>

## Build Windows executable

Use Windows x64 and Python 3.13.

```powershell
py -3.13 -m venv .venv-langchain313
.\.venv-langchain313\Scripts\python.exe -m pip install -r requirements-build.txt
.\Build-Weather.ps1
```

`SHA256SUMS.txt` records the packaged executable and APK checksums.
