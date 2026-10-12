# Usoft Weather

A compact Windows desktop weather app with saved locations, hourly and seven-day forecasts, location news from the last seven days, and Midnight, Ocean, and Light themes.

## Android

Install `Weather-Android.apk` on Android 6.0 (API 23) or newer. It includes the native mobile interface shown in `Screenshots/`, plus a resizable 2×3 home-screen widget. See [ANDROID.md](ANDROID.md) for build and widget details.

## Run

Double-click `Weather.exe`. Python, an API key, and Ollama are not required. An internet connection is required for weather and news.

Settings and locations are stored in `%LOCALAPPDATA%\Usoft\Weather\weather_locations.json`. Personal settings are not included in this repository.

Click the Weather title for local news, the arrow beside conditions for weather details, and the menu for settings. News counts appear when hovering over the title.

## Build from this repository

Use Windows x64 and Python **3.13**. The bundled interface snapshot requires this Python version.

```powershell
py -3.13 -m venv .venv-langchain313
.\.venv-langchain313\Scripts\python.exe -m pip install -r requirements-build.txt
.\Build-Weather.ps1
```

The executable is created at `dist\Weather.exe`. The frozen build omits LangChain and model runtimes; it calls the weather APIs directly. Source execution retains the LangChain tool interface.

The initial interface implementation is preserved as `weather_assets/weather_desktop-recovery.pyc` following a disk-full write interruption. The outline, window behavior, forecasts, themes, news, storage, and packaging changes are maintained in the included editable Python modules. Full source recovery of the initial interface remains outstanding.

## Verify the packaged executable

```powershell
.\Weather.exe --self-test "$PWD\smoke-report.json"
.\Weather.exe --self-test-live "$PWD\live-report.json"
```

The second command checks live forecasts and news. Reports and test settings stay local. `SHA256SUMS.txt` records the executable's build checksum.
