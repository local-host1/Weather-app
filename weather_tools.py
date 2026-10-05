"""Current weather estimates from Open-Meteo, without an API key."""

import requests
import math
import sys
if getattr(sys,'frozen',False):
    class _DesktopTool:
        def __init__(self,function):
            self.function=function
        def invoke(self,arguments):
            return self.function(**arguments)
    def tool(function):
        return _DesktopTool(function)
else:
    from langchain_core.tools import tool


WEATHER_CODES = {
    0: "Clear sky", 1: "Mainly clear", 2: "Partly cloudy", 3: "Overcast",
    45: "Fog", 48: "Depositing rime fog",
    51: "Light drizzle", 53: "Moderate drizzle", 55: "Dense drizzle",
    56: "Light freezing drizzle", 57: "Dense freezing drizzle",
    61: "Slight rain", 63: "Moderate rain", 65: "Heavy rain",
    66: "Light freezing rain", 67: "Heavy freezing rain",
    71: "Slight snow", 73: "Moderate snow", 75: "Heavy snow", 77: "Snow grains",
    80: "Slight rain showers", 81: "Moderate rain showers", 82: "Violent rain showers",
    85: "Slight snow showers", 86: "Heavy snow showers",
    95: "Thunderstorm", 96: "Thunderstorm with slight hail",
    99: "Thunderstorm with heavy hail",
}


@tool
def current_weather(location: str) -> dict:
    """Get current weather for a city name or 'latitude, longitude' coordinates.

    Use this tool for current weather questions instead of web search.
    Returns the resolved location and timestamp with Open-Meteo model estimates.
    """
    try:
        location = location.strip()
        if not location:
            return {"error": "Enter a city name or latitude, longitude."}
        parts = location.split(',')
        coordinates = None
        if len(parts) == 2:
            try:
                coordinates = tuple(float(part.strip()) for part in parts)
            except ValueError:
                pass
        if coordinates is not None:
            latitude, longitude = coordinates
            if not (math.isfinite(latitude) and math.isfinite(longitude)
                    and -90 <= latitude <= 90 and -180 <= longitude <= 180):
                return {"error": "Latitude must be between -90 and 90; longitude between -180 and 180."}
            location_name = f"Coordinates {latitude}, {longitude}"
        else:
            location_response = requests.get(
                "https://geocoding-api.open-meteo.com/v1/search",
                params={"name": location, "count": 1, "language": "en", "format": "json"},
                timeout=20,
            )
            location_response.raise_for_status()
            locations = location_response.json().get("results", [])
            if not locations:
                return {"error": f"No location found for {location}. Try a city name or coordinates."}
            resolved_location = locations[0]
            latitude, longitude = resolved_location["latitude"], resolved_location["longitude"]
            location_name = ", ".join(filter(None, [
                resolved_location["name"], resolved_location.get("admin1"), resolved_location.get("country"),
            ]))
        weather_response = requests.get(
            "https://api.open-meteo.com/v1/forecast",
            params={
                "latitude": latitude, "longitude": longitude,
                "current": "temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m,wind_direction_10m,surface_pressure",
                "hourly": "visibility,temperature_2m,weather_code,precipitation_probability,is_day",
                "daily": "sunrise,sunset,uv_index_max,weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max",
                "forecast_days": 7,
                "timezone": "auto",
            },
            timeout=20,
        )
        weather_response.raise_for_status()
        weather = weather_response.json()
        current = weather["current"]
        hourly=weather.get('hourly',{})
        times=hourly.get('time',[])
        hour=current['time'][:13]+':00'
        index=times.index(hour) if hour in times else None
        visibility=hourly.get('visibility',[])
        daily=weather.get('daily',{})
        def today(key):
            values=daily.get(key,[])
            return values[0] if values else None
        def at(data,key,index):
            values=data.get(key,[])
            return values[index] if index<len(values) else None
        hours=[{'time':stamp,'temperature_c':at(hourly,'temperature_2m',i),
                'weather_code':at(hourly,'weather_code',i),
                'rain_percent':at(hourly,'precipitation_probability',i),
                'is_day':at(hourly,'is_day',i)}
               for i,stamp in enumerate(times) if stamp>=hour][:24]
        days=[{'date':stamp,'high_c':at(daily,'temperature_2m_max',i),
               'low_c':at(daily,'temperature_2m_min',i),
               'weather_code':at(daily,'weather_code',i),
               'rain_percent':at(daily,'precipitation_probability_max',i)}
              for i,stamp in enumerate(daily.get('time',[]))][:7]
        air={}
        try:
            response=requests.get('https://air-quality-api.open-meteo.com/v1/air-quality',
                params={'latitude':latitude,'longitude':longitude,'current':'us_aqi','timezone':'auto'},timeout=10)
            response.raise_for_status()
            air=response.json().get('current',{})
        except (requests.RequestException,ValueError):
            pass  # Air quality failure must not discard available weather.
        return {
            "location": location_name, "latitude": latitude, "longitude": longitude,
            "time": current["time"], "timezone": weather["timezone"],
            "temperature_c": current["temperature_2m"],
            "feels_like_c": current["apparent_temperature"],
            "conditions": WEATHER_CODES.get(current["weather_code"], "Unknown"),
            "humidity_percent": current["relative_humidity_2m"],
            "wind_kmh": current["wind_speed_10m"],
            "wind_direction":current.get('wind_direction_10m'),
            "pressure_hpa":current.get('surface_pressure'),
            "visibility_km":visibility[index]/1000 if index is not None and index<len(visibility) and visibility[index] is not None else None,
            "sunrise":today('sunrise'),"sunset":today('sunset'),
            "uv_index_max":today('uv_index_max'),
            "air_quality_us_aqi":air.get('us_aqi'),"air_quality_time":air.get('time'),
            "hourly_forecast":hours,"daily_forecast":days,
            "source": "Open-Meteo (current model estimate)",
            "source_url": "https://open-meteo.com/",
        }
    except (requests.RequestException, ValueError, KeyError, IndexError) as error:
        return {"error": f"Weather data unavailable: {error}. Do not invent weather values."}
