package com.usoft.weather

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.PersistableBundle
import android.widget.RemoteViews
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer

private const val PERIODIC_WIDGET_JOB = 73104
private const val ON_DEMAND_WIDGET_JOB = 73105
private const val WIDGET_REFRESH_ACTION = "com.usoft.weather.action.REFRESH_WIDGET"
private const val WIDGET_PREFERENCES = "weather_preferences"
private const val WEATHER_CACHE = "widget_weather_cache"
private const val LOCATION_AGE_LIMIT_MS = 30L * 60L * 1000L
private const val WIDGET_UPDATE_INTERVAL_MS = 15L * 60L * 1000L

class WeatherWidgetProvider : AppWidgetProvider() {
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WeatherWidgetJobs.schedulePeriodic(context)
        WeatherWidgetJobs.scheduleImmediate(context)
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, widgetIds: IntArray) {
        widgetIds.forEach { WeatherWidgetUi.update(context, manager, it) }
        WeatherWidgetJobs.schedulePeriodic(context)
        WeatherWidgetJobs.scheduleImmediate(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == WIDGET_REFRESH_ACTION) {
            WeatherWidgetUi.showStatus(context, "Updating weather…")
            WeatherWidgetJobs.scheduleImmediate(context)
        }
    }

    override fun onDisabled(context: Context) {
        WeatherWidgetJobs.cancel(context)
        super.onDisabled(context)
    }
}

internal object WeatherWidgetJobs {
    fun schedulePeriodic(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        val component = ComponentName(context, WeatherWidgetRefreshJob::class.java)
        val job = JobInfo.Builder(PERIODIC_WIDGET_JOB, component)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .setPeriodic(WIDGET_UPDATE_INTERVAL_MS)
            .build()
        scheduler.schedule(job)
    }

    fun scheduleImmediate(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val widgetComponent = ComponentName(context, WeatherWidgetProvider::class.java)
        if (manager.getAppWidgetIds(widgetComponent).isEmpty()) return
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        val component = ComponentName(context, WeatherWidgetRefreshJob::class.java)
        val extras = PersistableBundle().apply { putBoolean("one_time", true) }
        val job = JobInfo.Builder(ON_DEMAND_WIDGET_JOB, component)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setExtras(extras)
            .setMinimumLatency(0L)
            .setOverrideDeadline(5_000L)
            .build()
        scheduler.schedule(job)
    }

    fun cancel(context: Context) {
        context.getSystemService(JobScheduler::class.java)?.apply {
            cancel(PERIODIC_WIDGET_JOB)
            cancel(ON_DEMAND_WIDGET_JOB)
        }
    }
}

class WeatherWidgetRefreshJob : android.app.job.JobService() {
    private val executor = Executors.newSingleThreadExecutor()

    override fun onStartJob(params: android.app.job.JobParameters): Boolean {
        executor.execute {
            try {
                WeatherWidgetData.refresh(this)
            } catch (error: Exception) {
                WeatherWidgetUi.showStatus(
                    this,
                    error.message?.take(100) ?: "Weather update failed. Tap refresh to retry."
                )
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: android.app.job.JobParameters): Boolean = true

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}

private object WeatherWidgetData {
    fun refresh(context: Context) {
        val prefs = context.getSharedPreferences(WIDGET_PREFERENCES, Context.MODE_PRIVATE)
        val liveEnabled = prefs.getBoolean("live_location", false)
        val position = if (liveEnabled) currentLocation(context) else null
        if (liveEnabled && position == null) {
            throw IllegalStateException("Live location unavailable. Check location permissions and device settings.")
        }

        val savedQuery = prefs.getString("widget_location", null)?.takeIf { it.isNotBlank() }
            ?: prefs.getString("location", "Colombo")?.takeIf { it.isNotBlank() }
            ?: "Colombo"
        val resolvedPlace = position?.let {
            Triple(it.latitude, it.longitude, "Current location")
        } ?: resolvePlace(savedQuery)
        val savedPlace = prefs.getString("widget_place", null)
            ?.takeIf { it.isNotBlank() && prefs.getString("widget_location", null) == savedQuery }
        val (latitude, longitude, place) = if (position == null && savedPlace != null) {
            Triple(resolvedPlace.first, resolvedPlace.second, savedPlace)
        } else resolvedPlace
        val address = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=$latitude&longitude=$longitude" +
            "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m" +
            "&timezone=auto"
        val response = JSONObject(get(address))
        val current = response.getJSONObject("current")
        val data = JSONObject()
            .put("place", place)
            .put("temperature", current.optDouble("temperature_2m", Double.NaN))
            .put("humidity", current.optInt("relative_humidity_2m", -1))
            .put("wind", current.optDouble("wind_speed_10m", Double.NaN))
            .put("condition", description(current.optInt("weather_code", -1)))
            .put("code", current.optInt("weather_code", -1))
            .put("time", current.optString("time", ""))
            .put("timezone", response.optString("timezone", ""))
            .put("fahrenheit", prefs.getBoolean("fahrenheit", false))
            .put("updated_at", System.currentTimeMillis())
        prefs.edit().putString(WEATHER_CACHE, data.toString()).apply()
        WeatherWidgetUi.updateAll(context)
    }

    private fun currentLocation(context: Context): Location? {
        if (!hasForegroundLocation(context) || !hasBackgroundLocation(context)) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val providers = try {
            manager.getProviders(true).filter {
                it == LocationManager.GPS_PROVIDER || it == LocationManager.NETWORK_PROVIDER || it == LocationManager.PASSIVE_PROVIDER
            }
        } catch (_: SecurityException) {
            return null
        }
        if (providers.isEmpty()) return null

        val cached = try {
            providers.mapNotNull { manager.getLastKnownLocation(it) }
                .filter { System.currentTimeMillis() - it.time <= LOCATION_AGE_LIMIT_MS }
                .maxByOrNull { it.time }
        } catch (_: SecurityException) {
            null
        }
        val orderedProviders = providers.sortedBy {
            when (it) {
                LocationManager.NETWORK_PROVIDER -> 0
                LocationManager.GPS_PROVIDER -> 1
                else -> 2
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            orderedProviders.forEach { provider ->
                val signal = CancellationSignal()
                val result = AtomicReference<Location?>()
                val latch = CountDownLatch(1)
                try {
                    manager.getCurrentLocation(provider, signal, context.mainExecutor, Consumer { location ->
                        result.set(location)
                        latch.countDown()
                    })
                    if (latch.await(12, TimeUnit.SECONDS)) result.get()?.let { return it }
                } catch (_: SecurityException) {
                    return cached
                } finally {
                    signal.cancel()
                }
            }
            return cached
        }

        orderedProviders.forEach { provider ->
            val result = AtomicReference<Location?>()
            val latch = CountDownLatch(1)
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    result.set(location)
                    latch.countDown()
                }

                @Deprecated("Deprecated by Android")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                override fun onProviderEnabled(provider: String) = Unit
                override fun onProviderDisabled(provider: String) = Unit
            }
            try {
                @Suppress("DEPRECATION")
                manager.requestSingleUpdate(provider, listener, android.os.Looper.getMainLooper())
                if (latch.await(8, TimeUnit.SECONDS)) result.get()?.let { return it }
            } catch (_: SecurityException) {
                return cached
            } finally {
                manager.removeUpdates(listener)
            }
        }
        return cached
    }

    private fun hasForegroundLocation(context: Context): Boolean =
        context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasBackgroundLocation(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            context.checkSelfPermission(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun resolvePlace(query: String): Triple<Double, Double, String> {
        val parts = query.split(',')
        val latitude = parts.getOrNull(0)?.trim()?.toDoubleOrNull()
        val longitude = parts.getOrNull(1)?.trim()?.toDoubleOrNull()
        if (latitude != null && longitude != null &&
            latitude.isFinite() && longitude.isFinite() &&
            latitude in -90.0..90.0 && longitude in -180.0..180.0) {
            return Triple(latitude, longitude, "Saved location")
        }
        val geocode = "https://geocoding-api.open-meteo.com/v1/search?name=" +
            URLEncoder.encode(query, "UTF-8") + "&count=1&language=en&format=json"
        val results = JSONObject(get(geocode)).optJSONArray("results")
        if (results == null || results.length() == 0) {
            throw IllegalArgumentException("No saved location found for $query.")
        }
        val result = results.getJSONObject(0)
        val place = listOf("name", "admin1", "country")
            .mapNotNull { result.optString(it).takeIf(String::isNotBlank) }
            .distinct().joinToString(", ")
        return Triple(result.getDouble("latitude"), result.getDouble("longitude"), place)
    }

    private fun get(address: String): String {
        val connection = URL(address).openConnection() as HttpURLConnection
        connection.connectTimeout = 12_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("Accept", "application/json")
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("Weather service returned HTTP $code.")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun description(code: Int): String = when (code) {
        0 -> "Clear sky"
        1 -> "Mainly clear"
        2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Fog"
        51 -> "Light drizzle"
        53 -> "Moderate drizzle"
        55 -> "Dense drizzle"
        56, 57 -> "Freezing drizzle"
        61 -> "Slight rain"
        63 -> "Moderate rain"
        65 -> "Heavy rain"
        66, 67 -> "Freezing rain"
        71, 73, 75, 77, 85, 86 -> "Snow"
        80, 81, 82 -> "Rain showers"
        95, 96, 99 -> "Thunderstorm"
        else -> "Unknown conditions"
    }
}

internal object WeatherWidgetUi {
    fun updateAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val component = ComponentName(context, WeatherWidgetProvider::class.java)
        manager.getAppWidgetIds(component).forEach { update(context, manager, it) }
    }

    fun showStatus(context: Context, message: String) {
        val prefs = context.getSharedPreferences(WIDGET_PREFERENCES, Context.MODE_PRIVATE)
        val cached = prefs.getString(WEATHER_CACHE, null)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
        val display = views(context, cached, message)
        val manager = AppWidgetManager.getInstance(context)
        val component = ComponentName(context, WeatherWidgetProvider::class.java)
        manager.getAppWidgetIds(component).forEach { manager.updateAppWidget(it, display) }
    }

    fun update(context: Context, manager: AppWidgetManager, widgetId: Int) {
        val prefs = context.getSharedPreferences(WIDGET_PREFERENCES, Context.MODE_PRIVATE)
        val cached = prefs.getString(WEATHER_CACHE, null)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
        val views = views(context, cached, null)
        manager.updateAppWidget(widgetId, views)
    }

    private fun views(context: Context, data: JSONObject?, transientStatus: String?): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.weather_widget)
        val prefs = context.getSharedPreferences(WIDGET_PREFERENCES, Context.MODE_PRIVATE)
        if (prefs.getBoolean("transparent_widget", false)) {
            views.setInt(R.id.widget_root, "setBackgroundColor", Color.TRANSPARENT)
        } else {
            views.setInt(R.id.widget_root, "setBackgroundResource", R.drawable.weather_widget_background)
        }
        val intent = Intent(context, WeatherWidgetProvider::class.java).setAction(WIDGET_REFRESH_ACTION)
        val refreshPendingIntent = PendingIntent.getBroadcast(
            context,
            8401,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_refresh, refreshPendingIntent)
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent(context, MainActivity::class.java)
        val launchPendingIntent = PendingIntent.getActivity(
            context,
            8402,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_location, launchPendingIntent)
        views.setOnClickPendingIntent(R.id.widget_temperature, launchPendingIntent)
        val isLive = prefs.getBoolean("live_location", false)
        val time = data?.optLong("updated_at", 0L) ?: 0L
        val livePlace = if (isLive) "Live location" else null
        views.setTextViewText(
            R.id.widget_location,
            data?.optString("place")?.takeIf(String::isNotBlank) ?: livePlace ?: "Weather"
        )
        val code = data?.optInt("code", -1) ?: -1
        views.setTextViewText(R.id.widget_symbol, symbol(code))
        val rawTemperature = data?.optDouble("temperature", Double.NaN) ?: Double.NaN
        val fahrenheit = data?.optBoolean("fahrenheit", false) ?: prefs.getBoolean("fahrenheit", false)
        val temperature = if (fahrenheit && rawTemperature.isFinite()) rawTemperature * 9 / 5 + 32 else rawTemperature
        views.setTextViewText(
            R.id.widget_temperature,
            if (temperature.isFinite()) {
                String.format(Locale.getDefault(), "%.0f°%s", temperature, if (fahrenheit) "F" else "C")
            } else "—°"
        )
        views.setTextViewText(
            R.id.widget_condition,
            transientStatus ?: data?.optString("condition")?.takeIf(String::isNotBlank)
                ?: if (isLive) "Allow background location in Weather settings" else "Tap refresh for weather"
        )
        val humidity = data?.optInt("humidity", -1) ?: -1
        val wind = data?.optDouble("wind", Double.NaN) ?: Double.NaN
        val windText = if (wind.isFinite()) String.format(Locale.getDefault(), "%.0f km/h", wind) else "— km/h"
        views.setTextViewText(
            R.id.widget_metrics,
            "Humidity ${if (humidity >= 0) "$humidity%" else "—%"}  ·  Wind $windText"
        )
        views.setTextViewText(
            R.id.widget_updated,
            if (time > 0L) {
                "Updated ${SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(time))} · refreshes about every 15 min"
            } else "Updates about every 15 minutes"
        )
        return views
    }

    private fun symbol(code: Int): String = when {
        code < 0 -> "☀"
        code <= 1 -> "☀"
        code <= 3 -> "☁"
        code == 45 || code == 48 -> "≋"
        code >= 95 -> "ϟ"
        code in listOf(71, 73, 75, 77, 85, 86) -> "❄"
        else -> "☂"
    }
}
