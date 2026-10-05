# Usoft Weather

A compact Windows desktop weather app with saved locations, hourly and seven-day forecasts, location news from the last seven days, and Midnight, Ocean, and Light themes.

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

## Typography and attribution

The distributable uses Windows' installed Century Gothic Bold for its wordmark. The Zegion personal-use demo is not distributed. For a licensed personal installation, a compatible font can be placed at `weather_assets/ZegionDemo-Regular.ttf` when running from source.

- Weather and forecasts: [Open-Meteo](https://open-meteo.com/).
- Air quality: Open-Meteo/CAMS model estimates.
- News: Google News RSS, with publisher links and a strict seven-day filter.
- Nearby news areas: [OpenStreetMap contributors](https://www.openstreetmap.org/copyright), via Nominatim.

## Verify the packaged executable

```powershell
.\Weather.exe --self-test "$PWD\smoke-report.json"
.\Weather.exe --self-test-live "$PWD\live-report.json"
```

The second command checks live forecasts and news. Reports and test settings stay local. `SHA256SUMS.txt` records the executable's build checksum.
