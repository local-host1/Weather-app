package com.usoft.weather

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.Switch
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

private const val BACKGROUND = 0xFF09111B.toInt()
private const val SURFACE = 0xFF111C29.toInt()
private const val SURFACE_ALT = 0xFF192636.toInt()
private const val TEXT = 0xFFF3F6FA.toInt()
private const val MUTED = 0xFF9BAABD.toInt()
private const val ACCENT = 0xFF00DFB3.toInt()
private const val REQUEST_FOREGROUND_LOCATION = 4101
private const val REQUEST_BACKGROUND_LOCATION = 4102

private data class Palette(
    val background: Int,
    val surface: Int,
    val alternate: Int,
    val text: Int,
    val muted: Int,
    val accent: Int,
    val border: Int
)

private val PALETTES = mapOf(
    "Midnight" to Palette(BACKGROUND, SURFACE, SURFACE_ALT, TEXT, MUTED, ACCENT, 0xFF303640.toInt()),
    "Ocean" to Palette(0xFF081C2B.toInt(), 0xFF0C2537.toInt(), 0xFF17374C.toInt(), TEXT, MUTED, 0xFF55D7ED.toInt(), 0xFF35566C.toInt()),
    "Light" to Palette(0xFFF3F5F8.toInt(), 0xFFFFFFFF.toInt(), 0xFFE8EDF3.toInt(), 0xFF142131.toInt(), 0xFF566779.toInt(), 0xFF007F70.toInt(), 0xFFC8D1DD.toInt())
)

class MainActivity : Activity() {
    private lateinit var content: LinearLayout
    private lateinit var shell: FrameLayout
    private lateinit var executor: ExecutorService
    private val handler = Handler(Looper.getMainLooper())
    private var requestNumber = 0
    private var page = "weather"
    private var detailsTab = "Overview"
    private var locationDraft = ""
    private var labelDraft = ""
    private var statusMessage = "Loading weather…"
    private var currentQuery = "Colombo"
    private var weather: Weather? = null
    private var newsLoading = false
    private var newsError: String? = null
    private var newsStories: List<NewsStory> = emptyList()
    private var currentNewsKey = ""
    private var startupWeatherPending = true
    private val expandedStories = mutableSetOf<String>()
    private var autoRefreshTask: Runnable? = null
    private var awaitingBackgroundPermissionReturn = false
    private val prefs by lazy { getSharedPreferences("weather_preferences", MODE_PRIVATE) }
    private val palette: Palette
        get() = PALETTES[prefs.getString("theme", "Midnight")] ?: PALETTES.getValue("Midnight")
    private val temperatureUnit: String
        get() = if (prefs.getBoolean("fahrenheit", false)) "°F" else "°C"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        executor = Executors.newSingleThreadExecutor()
        val remember = prefs.getBoolean("remember", true)
        currentQuery = if (remember) prefs.getString("location", "Colombo") ?: "Colombo" else "Colombo"
        locationDraft = currentQuery
        if (prefs.getBoolean("keep_awake", false)) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        buildShell()
        loadWeather(currentQuery)
    }

    override fun onResume() {
        super.onResume()
        if (awaitingBackgroundPermissionReturn) {
            awaitingBackgroundPermissionReturn = false
            if (hasLocationPermission(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
                enableLiveWidgetLocation()
            } else {
                prefs.edit().putBoolean("live_location", false).apply()
                statusMessage = "Allow background location in Android settings to enable live widget location."
                renderPage()
            }
        }
        scheduleAutoRefresh()
    }

    override fun onPause() {
        autoRefreshTask?.let(handler::removeCallbacks)
        autoRefreshTask = null
        super.onPause()
    }

    override fun onDestroy() {
        autoRefreshTask?.let(handler::removeCallbacks)
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun buildShell() {
        window.statusBarColor = palette.background
        window.navigationBarColor = palette.background
        window.decorView.systemUiVisibility = if (prefs.getString("theme", "Midnight") == "Light") {
            android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        } else 0
        shell = FrameLayout(this).apply {
            setBackgroundColor(palette.background)
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(palette.background)
            isFillViewport = true
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(26))
        }
        scroll.addView(content)
        shell.addView(scroll)
        setContentView(shell)
        renderPage()
    }

    private fun renderPage() {
        val colors = palette
        window.statusBarColor = colors.background
        window.navigationBarColor = colors.background
        window.decorView.systemUiVisibility = if (prefs.getString("theme", "Midnight") == "Light") {
            android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        } else 0
        content.setBackgroundColor(colors.background)
        content.removeAllViews()
        if (page == "weather") {
            addReferenceHeader("Weather", true)
            renderReferenceDashboard()
            addReferenceFooter()
            return
        }
        val referenceTitle = when (page) {
            "details" -> weather?.place ?: "Weather"
            "locations" -> "Locations"
            "news" -> "News Feed"
            "settings" -> "General"
            else -> "About Weather"
        }
        addReferenceHeader(referenceTitle, false)
        when (page) {
            "details" -> renderDetails()
            "locations" -> renderLocations()
            "news" -> renderNews()
            "settings" -> renderSettings()
            else -> renderAbout()
        }
        addReferenceFooter()
        return
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        toolbar.addView(button("☰") { openNavigationDrawer() },
            LinearLayout.LayoutParams(dp(48), dp(44)))
        toolbar.addView(text("Weather", 21f, true).apply {
            setPadding(dp(10), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(toolbar)
        content.addView(text(
            when (page) {
                "details" -> "Weather details"
                "locations" -> "Your locations"
                "news" -> "News Feed"
                "settings" -> "Settings"
                "about" -> "About Weather"
                else -> "Your forecast, at a glance"
            }, 24f, true
        ).apply { setPadding(0, dp(5), 0, dp(12)) })
        addNavigation()
        when (page) {
            "details" -> renderDetails()
            "locations" -> renderLocations()
            "news" -> renderNews()
            "settings" -> renderSettings()
            "about" -> renderAbout()
            else -> renderDashboard()
        }
    }

    private fun addNavigation() {
        content.addView(statusView(statusMessage))
    }

    private fun openNavigationDrawer() {
        if (shell.findViewWithTag<View>("navigation_drawer_overlay") != null) return
        val overlay = FrameLayout(this).apply {
            tag = "navigation_drawer_overlay"
            setBackgroundColor(0x99000000.toInt())
            setOnClickListener { shell.removeView(this) }
        }
        val drawer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(28), dp(18), dp(18))
            setBackgroundColor(palette.background)
            setOnClickListener { }
        }
        drawer.addView(text("Weather", 24f, true, palette.accent).apply {
            setPadding(dp(4), dp(12), 0, dp(4))
        })
        drawer.addView(text(
            weather?.place ?: "Your local forecast",
            13f,
            false,
            palette.muted
        ).apply { setPadding(dp(4), 0, 0, dp(20)) })
        listOf(
            "Current weather" to "weather",
            "Forecast details" to "details",
            "News Feed" to "news",
            "Locations" to "locations",
            "Settings" to "settings",
            "About" to "about"
        ).forEach { (label, target) ->
            drawer.addView(button(label, selected = page == target) {
                shell.removeView(overlay)
                page = target
                if (target == "details") detailsTab = "Overview"
                renderPage()
                if (target == "news") loadNews(force = false)
            }, fullButtonParams(top = 8))
        }
        drawer.addView(button("Close menu", selected = false) {
            shell.removeView(overlay)
        }, fullButtonParams(top = 18))
        overlay.addView(drawer, FrameLayout.LayoutParams(
            minOf(dp(300), (resources.displayMetrics.widthPixels * 0.84f).toInt()),
            FrameLayout.LayoutParams.MATCH_PARENT,
            Gravity.START
        ))
        shell.addView(overlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
    }

    private fun addReferenceHeader(title: String, showMenu: Boolean) {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(10), dp(10))
            background = rounded(palette.surface, 15, palette.border)
        }
        header.addView(compactIcon(if (showMenu) "☰" else "‹") {
            if (showMenu) openNavigationDrawer() else { page = "weather"; renderPage() }
        })
        header.addView(text(title, 22f, true).apply {
            setPadding(dp(8), 0, 0, 0)
            if (showMenu) setOnClickListener { page = "news"; renderPage(); loadNews(false) }
        }, LinearLayout.LayoutParams(0, dp(42), 1f))
        header.addView(compactIcon("×") { page = "weather"; renderPage() })
        content.addView(header, fullButtonParams())
    }

    private fun addReferenceFooter() {
        content.addView(text("◯  ${weather?.time?.takeAfterT() ?: "--:--"}  · Open-Meteo                         Usoft®", 10f, false, palette.accent).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(5), dp(8), dp(5))
            background = rounded(palette.background, 14, palette.border)
        }, fullButtonParams(top = 8))
    }

    private fun compactIcon(label: String, action: () -> Unit): TextView = text(label, 20f, false, palette.muted).apply {
        gravity = Gravity.CENTER
        setPadding(dp(5), 0, dp(5), 0)
        setOnClickListener { action() }
    }

    private fun renderReferenceDashboard() {
        val data = weather
        if (data == null) {
            content.addView(text("Loading weather…", 14f, false, palette.muted).apply { setPadding(dp(12), dp(20), 0, dp(20)) })
            return
        }
        val condition = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(11), dp(10), dp(8)) }
        condition.addView(text("CURRENT", 10f, true, palette.accent))
        condition.addView(text(data.condition, 12f, false, palette.muted).apply { setPadding(dp(28), 0, 0, 0) })
        condition.addView(text("›", 21f, false, palette.muted).apply { setPadding(dp(9), 0, 0, 0) })
        content.addView(condition)

        val hero = LinearLayout(this).apply { gravity = Gravity.TOP; setPadding(dp(12), 0, dp(12), dp(3)) }
        val location = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        location.addView(text(data.place, 20f, true))
        location.addView(text(data.timezone.ifBlank { "Saved location" }, 11f, false, palette.muted).apply { setPadding(0, dp(2), 0, 0) })
        location.addView(text("${data.latitude.formatCoord()}, ${data.longitude.formatCoord()}", 11f, false, palette.muted).apply { setPadding(0, dp(7), 0, 0) })
        hero.addView(location, LinearLayout.LayoutParams(0, -2, 1f))
        val temperature = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        temperature.addView(text("${data.temperature.asTemperature()}$temperatureUnit", 18f, true, palette.background).apply {
            gravity = Gravity.CENTER
            background = rounded(0xFFF5F7FA.toInt(), 38)
        }, LinearLayout.LayoutParams(dp(72), dp(72)))
        temperature.addView(text("Feels ${data.feelsLike.asTemperature()}$temperatureUnit", 9f, false, palette.muted).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) })
        hero.addView(temperature, LinearLayout.LayoutParams(dp(82), -2))
        content.addView(hero)

        val metrics = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(13), dp(7), dp(13), dp(8)) }
        metrics.addView(text("♧  Humidity  ${data.humidity.asWhole()}%", 12f, true), LinearLayout.LayoutParams(0, -2, 1f))
        metrics.addView(text("≋  Wind  ${data.wind.asWhole()} km/h", 12f, true), LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(metrics)

        val actions = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(palette.background, 0, palette.border)
            setPadding(dp(12), dp(7), dp(10), dp(7))
        }
        actions.addView(text("LOCATIONS\n${savedLocations().size} saved", 10f, true, palette.muted).apply { setOnClickListener { page = "locations"; renderPage() } }, LinearLayout.LayoutParams(0, -2, 1f))
        listOf("⌖" to "locations", "+" to "locations", "⚙" to "settings", "ⓘ" to "about", "⌄" to "details").forEach { pair ->
            actions.addView(compactIcon(pair.first) { page = pair.second; if (pair.second == "details") detailsTab = "Overview"; renderPage() }, LinearLayout.LayoutParams(dp(37), dp(38)))
        }
        content.addView(actions)
    }

    private fun renderDashboard() {
        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val search = input("City or latitude, longitude", locationDraft).apply {
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    searchWeather(text.toString())
                    true
                } else false
            }
            setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) locationDraft = text.toString()
            }
        }
        searchRow.addView(search, LinearLayout.LayoutParams(0, dp(52), 1f))
        searchRow.addView(button("Search") { searchWeather(search.text.toString()) }, LinearLayout.LayoutParams(dp(88), dp(50)).apply {
            leftMargin = dp(8)
        })
        content.addView(searchRow)
        content.addView(button("Add or manage saved locations") {
            locationDraft = search.text.toString()
            page = "locations"
            renderPage()
        }, fullButtonParams(top = 8))
        content.addView(button("Refresh weather", selected = false) {
            loadWeather(currentQuery)
        }, fullButtonParams(top = 7))

        val data = weather ?: return
        val hero = card()
        hero.addView(text(data.place, 21f, true))
        hero.addView(text(data.condition, 14f, false, palette.muted).apply { setPadding(0, dp(4), 0, 0) })
        val tempRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        tempRow.addView(text(weatherSymbol(data.code), 42f, false, palette.accent))
        tempRow.addView(text("${data.temperature.asTemperature()}$temperatureUnit", 54f, true).apply {
            setPadding(dp(10), 0, 0, 0)
        })
        hero.addView(tempRow)
        hero.addView(text(
            "Feels like ${data.feelsLike.asTemperature()}°   ·   ${data.timezone.ifBlank { "Local time" }}",
            13f, false, palette.muted
        ))
        if (prefs.getBoolean("show_coordinates", false)) {
            hero.addView(text("${data.latitude.formatCoord()}, ${data.longitude.formatCoord()}", 12f, false, palette.muted)
                .apply { setPadding(0, dp(5), 0, 0) })
        }
        content.addView(hero, fullButtonParams(top = 12))

        val metrics = listOf(
            Metric("Humidity", "${data.humidity.asWhole()}%", "Relative humidity"),
            Metric("Wind", "${data.wind.asWhole()} km/h ${data.direction.asCompass()}", "Current wind"),
            Metric("Pressure", "${data.pressure.asWhole()} hPa", "At surface"),
            Metric("Updated", data.time.takeAfterT(), "Local time")
        )
        metrics.chunked(2).forEach { pair -> addMetricRow(pair) }

        content.addView(text("NEXT HOURS", 13f, true, palette.muted).apply {
            setPadding(2, dp(17), 0, dp(8))
        })
        val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        data.hours.take(6).forEachIndexed { index, hour ->
            val item = card(palette.alternate).apply {
                gravity = Gravity.CENTER
                addView(text(if (index == 0) "Now" else hour.time.takeLast(5), 11f, false, palette.muted))
                addView(text(weatherSymbol(hour.code), 22f, false, palette.accent).apply {
                    setPadding(0, dp(6), 0, dp(6))
                })
                addView(text("${hour.temperature.asTemperature()}$temperatureUnit", 14f, true))
            }
            strip.addView(item, LinearLayout.LayoutParams(dp(82), dp(96)).apply { rightMargin = dp(7) })
        }
        content.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(strip)
        })
        content.addView(button("View overview, hourly and 7-day forecasts") {
            detailsTab = "Overview"
            page = "details"
            renderPage()
        }, fullButtonParams(top = 10))
        if (prefs.getBoolean("auto_refresh", false)) {
            content.addView(text("Weather refreshes automatically every 10 minutes.", 11f, false, palette.muted)
                .apply { setPadding(2, dp(12), 0, 0) })
        }
    }

    private fun renderDetails() {
        val data = weather ?: return
        content.addView(text("${data.place}  ·  ${data.condition}", 15f, true).apply {
            setPadding(2, dp(10), 0, dp(10))
        })
        content.addView(text(
            "${data.temperature.asTemperature()}$temperatureUnit  ·  Feels like ${data.feelsLike.asTemperature()}$temperatureUnit",
            19f, true
        ).apply { setPadding(2, 0, 0, dp(10)) })
        val tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Overview", "Hourly", "7 Days").forEach { tab ->
            tabRow.addView(button(tab, selected = detailsTab == tab) {
                detailsTab = tab
                renderPage()
            }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { rightMargin = dp(5) })
        }
        content.addView(tabRow)
        when (detailsTab) {
            "Hourly" -> renderHourly(data)
            "7 Days" -> renderDaily(data)
            else -> renderOverview(data)
        }
        content.addView(text("${data.timezone.ifBlank { "Local time" }} · Weather estimates from Open-Meteo",
            11f, false, palette.muted).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(8))
        })
    }

    private fun renderOverview(data: Weather) {
        val airLevel = data.airQuality?.let { aqi ->
            when {
                aqi <= 50 -> "Good"
                aqi <= 100 -> "Moderate"
                aqi <= 150 -> "Sensitive groups"
                aqi <= 200 -> "Unhealthy"
                aqi <= 300 -> "Very unhealthy"
                else -> "Hazardous"
            }
        } ?: "No estimate available"
        val uvLevel = data.uvIndex?.let {
            when {
                it < 3 -> "Low"
                it < 6 -> "Moderate"
                it < 8 -> "High"
                it < 11 -> "Very high"
                else -> "Extreme"
            }
        } ?: "No estimate available"
        val values = listOf(
            Metric("Wind", data.wind.asOneDecimal(), "km/h · ${data.direction.asCompass()}"),
            Metric("Humidity", "${data.humidity.asWhole()}%", "Relative humidity"),
            Metric("UV Index", data.uvIndex?.asOneDecimal() ?: "Unavailable", "Daily max · $uvLevel"),
            Metric("Air Quality", data.airQuality?.toString() ?: "Unavailable", "US AQI · $airLevel"),
            Metric("Visibility", data.visibility?.let { it.asOneDecimal() } ?: "Unavailable", "km · hourly estimate"),
            Metric("Pressure", data.pressure.asOneDecimal(), "hPa · at surface")
        )
        values.chunked(2).forEach { pair -> addMetricRow(pair) }
        val sun = card()
        sun.addView(text("Sunrise & Sunset", 14f, true).apply { setPadding(0, 0, 0, dp(6)) })
        sun.addView(text("☀", 32f, true, 0xFFFFD36B.toInt()).apply { gravity = Gravity.CENTER })
        sun.addView(text("Rise ${data.sunrise.formatClock()}     Set ${data.sunset.formatClock()}",
            13f, false, palette.muted).apply { gravity = Gravity.CENTER })
        content.addView(sun, fullButtonParams(top = 8))
        content.addView(text("Air quality: CAMS model estimate", 11f, false, palette.muted).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(4))
        })
    }

    private fun renderHourly(data: Weather) {
        content.addView(text("HOURLY FORECAST · NEXT 24 HOURS", 13f, true, palette.muted).apply {
            setPadding(2, dp(14), 0, dp(9))
        })
        if (data.hours.isEmpty()) {
            content.addView(text("Hourly forecast unavailable. Refresh weather.", 13f, false, palette.muted))
        }
        data.hours.forEachIndexed { index, hour ->
            val row = card().apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
            }
            row.addView(text(if (index == 0) "Now" else hour.time.takeLast(5), 13f, true,
                palette.text), LinearLayout.LayoutParams(dp(62), -2))
            row.addView(text(weatherSymbol(hour.code), 24f, false, palette.accent),
                LinearLayout.LayoutParams(dp(38), -2))
            row.            addView(text("${hour.temperature.asTemperature()}$temperatureUnit", 18f, true),
                LinearLayout.LayoutParams(dp(70), -2))
            row.addView(text("${hour.rain?.let { "$it% rain" } ?: "—"}\n${weatherDescription(hour.code)}",
                11f, false, palette.muted), LinearLayout.LayoutParams(0, -2, 1f))
            content.addView(row, fullButtonParams(top = 5))
        }
    }

    private fun renderDaily(data: Weather) {
        content.addView(text("7-DAY FORECAST", 13f, true, palette.muted).apply {
            setPadding(2, dp(14), 0, dp(9))
        })
        data.days.forEachIndexed { index, day ->
            val row = card().apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
            }
            row.addView(text(if (index == 0) "Today" else day.date.toForecastDay(), 13f, true),
                LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(text(weatherSymbol(day.code), 24f, false, palette.accent),
                LinearLayout.LayoutParams(dp(42), -2))
            row.addView(text("${day.high.asTemperature()}$temperatureUnit / ${day.low.asTemperature()}$temperatureUnit", 14f, true))
            content.addView(row, fullButtonParams(top = 5))
            content.addView(text("${weatherDescription(day.code)}  ·  ${day.rain?.let { "$it% rain" } ?: "Rain unavailable"}",
                11f, false, palette.muted).apply { setPadding(dp(14), dp(3), dp(14), dp(7)) })
        }
    }

    private fun renderLocations() {
        content.addView(text("Add Location", 17f, true).apply { setPadding(2, dp(12), 0, dp(4)) })
        content.addView(text("Search a city or enter latitude, longitude.", 12f, false, palette.muted))
        val query = input("City or latitude, longitude", locationDraft).apply {
            setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) locationDraft = text.toString() }
            imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        content.addView(query, fullButtonParams(top = 9))
        val label = input("Label (optional, e.g. Home)", labelDraft).apply {
            setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) labelDraft = text.toString() }
        }
        content.addView(label, fullButtonParams(top = 8))
        content.addView(button("Save location") {
            locationDraft = query.text.toString().trim()
            labelDraft = label.text.toString().trim()
            saveLocation(locationDraft, labelDraft)
        }, fullButtonParams(top = 8))
        content.addView(text("SAVED LOCATIONS", 12f, true, palette.muted).apply {
            setPadding(2, dp(22), 0, dp(7))
        })
        val locations = savedLocations()
        if (locations.isEmpty()) {
            content.addView(text("No saved locations yet.", 13f, false, palette.muted))
        }
        locations.forEach { location ->
            val card = card(palette.surface)
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                orientation = LinearLayout.HORIZONTAL
            }
            val select = button(
                (if (location.query == currentQuery) "●  " else "○  ") + location.name,
                selected = location.query == currentQuery
            ) {
                currentQuery = location.query
                locationDraft = location.query
                page = "weather"
                loadWeather(location.query)
            }.apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL }
            row.addView(select, LinearLayout.LayoutParams(0, dp(48), 1f))
            row.addView(button("Delete", selected = false) { deleteLocation(location) },
                LinearLayout.LayoutParams(dp(78), dp(44)).apply { leftMargin = dp(6) })
            card.addView(row)
            content.addView(card, fullButtonParams(top = 6))
        }
        content.addView(button("Back to weather") {
            page = "weather"
            renderPage()
        }, fullButtonParams(top = 12))
    }

    private fun renderNews() {
        val selected = weather
        if (selected == null) {
            content.addView(text("Load weather for a location to see local news.", 13f, false, palette.muted))
            return
        }
        content.addView(text(
            if (newsLoading) "Finding local news from the past 7 days…" else newsError ?: "${selected.place} · Past 7 days",
            12f, false, palette.muted
        ).apply { setPadding(2, dp(8), 2, dp(12)) })
        newsStories.forEach { story ->
            val article = card(palette.surface)
            val title = button(story.title, selected = false) {
                if (expandedStories.contains(story.url)) expandedStories.remove(story.url)
                else expandedStories.add(story.url)
                renderPage()
            }.apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                textSize = 14f
                setPadding(dp(5), dp(5), dp(5), dp(5))
            }
            article.addView(title)
            article.addView(text(story.published.formatNewsDate(), 12f, false, palette.muted)
                .apply { setPadding(dp(5), dp(5), 0, dp(3)) })
            if (expandedStories.contains(story.url)) {
                article.addView(text(story.title, 13f, true).apply {
                    setPadding(dp(5), dp(8), 0, dp(3))
                })
                article.addView(text(story.source, 12f, false, palette.muted).apply {
                    setPadding(dp(5), dp(3), 0, dp(7))
                })
                article.addView(button("Read article ↗") { openArticle(story.url) })
            }
            content.addView(article, fullButtonParams(top = 5))
        }
        if (!newsLoading && newsError == null && newsStories.isEmpty()) {
            content.addView(text("No matching news from the past 7 days.", 13f, false, palette.muted)
                .apply { setPadding(2, dp(18), 0, dp(12)) })
        }
        content.addView(button(if (newsLoading) "Loading…" else "Refresh news", selected = false) {
            loadNews(force = true)
        }, fullButtonParams(top = 10))
        content.addView(text("Google News RSS · Area lookup © OpenStreetMap", 10f, false, palette.muted)
            .apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0) })
    }

    private fun renderSettings() {
        content.addView(text("Appearance", 16f, true).apply { setPadding(2, dp(12), 0, dp(4)) })
        content.addView(text("Choose your app theme.", 12f, false, palette.muted))
        val themeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        PALETTES.keys.forEach { theme ->
            themeRow.addView(button(theme, selected = prefs.getString("theme", "Midnight") == theme) {
                prefs.edit().putString("theme", theme).apply()
                renderPage()
            }, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                rightMargin = dp(5)
                topMargin = dp(8)
            })
        }
        content.addView(themeRow)
        content.addView(text("Home-screen widget", 17f, true).apply {
            setPadding(2, dp(20), 0, dp(4))
        })
        content.addView(text(
            "Add Weather from your launcher’s Widgets list. It refreshes periodically; Android may delay background updates.",
            12f, false, palette.muted
        ).apply { setPadding(2, 0, 2, dp(6)) })
        val widgetAppearance = card()
        val transparentWidget = Switch(this).apply {
            text = "Transparent widget background"
            textSize = 15f
            setTextColor(palette.text)
            isChecked = prefs.getBoolean("transparent_widget", false)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("transparent_widget", checked).apply()
                WeatherWidgetUi.updateAll(this@MainActivity)
            }
        }
        widgetAppearance.addView(transparentWidget)
        widgetAppearance.addView(text(
            "Show the home-screen widget without its solid card background.",
            12f, false, palette.muted
        ).apply { setPadding(0, dp(3), 0, dp(5)) })
        content.addView(widgetAppearance, fullButtonParams(top = 8))
        content.addView(button("Refresh widget now", selected = false) {
            WeatherWidgetUi.showStatus(this, "Updating weather…")
            WeatherWidgetJobs.scheduleImmediate(this)
        }, fullButtonParams(top = 8))
        val liveCard = card()
        val liveSwitch = Switch(this).apply {
            text = getString(R.string.live_location_toggle)
            textSize = 15f
            setTextColor(palette.text)
            isChecked = prefs.getBoolean("live_location", false)
            setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    isChecked = false
                    requestLiveWidgetLocation()
                } else {
                    prefs.edit().putBoolean("live_location", false).apply()
                    WeatherWidgetJobs.scheduleImmediate(this@MainActivity)
                }
            }
        }
        liveCard.addView(liveSwitch)
        liveCard.addView(text(
            "Background location is used only to update the widget and requires Android’s “Allow all the time” permission. No ongoing notification is used; Android limits update frequency to protect battery.",
            12f, false, palette.muted
        ).apply { setPadding(0, dp(3), 0, dp(5)) })
        content.addView(liveCard, fullButtonParams(top = 8))
        addPreferenceSwitch("Remember Location", "Open with your last selected location.", "remember", true)
        addPreferenceSwitch("Start Compact", "Open the simple weather dashboard first.", "compact_start", true)
        addPreferenceSwitch("Keep Screen Awake", "Keep Weather visible while you use it (Android equivalent).", "keep_awake", false)
        addPreferenceSwitch("Auto Refresh", "Refresh weather every 10 minutes.", "auto_refresh", false)
        addPreferenceSwitch("Show Coordinates", "Show latitude and longitude on the dashboard.", "show_coordinates", false)
        addPreferenceSwitch("Use Fahrenheit", "Display temperatures in Fahrenheit instead of Celsius.", "fahrenheit", false)
        content.addView(text("Preferences are saved automatically.", 12f, false, palette.muted).apply {
            setPadding(2, dp(16), 0, 0)
        })
    }

    private fun renderAbout() {
        val version = packageManager.getPackageInfo(packageName, 0).versionName ?: "Unknown"
        val about = card()
        about.addView(text("Weather", 22f, true, palette.accent))
        about.addView(text("Version $version", 13f, false, palette.muted).apply {
            setPadding(0, dp(5), 0, dp(12))
        })
        about.addView(text(
            "A simple forecast app with current conditions, hourly and 7-day forecasts, saved locations, and local news.",
            14f, false
        ))
        content.addView(about, fullButtonParams(top = 10))
        content.addView(text("Weather data · Open-Meteo", 12f, false, palette.muted)
            .apply { setPadding(dp(4), dp(14), 0, 0) })
        content.addView(text("Map and place names · OpenStreetMap", 12f, false, palette.muted)
            .apply { setPadding(dp(4), dp(8), 0, 0) })
        content.addView(text("Local headlines · Google News RSS", 12f, false, palette.muted)
            .apply { setPadding(dp(4), dp(8), 0, 0) })
    }

    private fun addPreferenceSwitch(title: String, description: String, key: String, default: Boolean) {
        val container = card()
        val control = Switch(this).apply {
            text = title
            textSize = 15f
            setTextColor(palette.text)
            isChecked = prefs.getBoolean(key, default)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(key, checked).apply()
                if (key == "keep_awake") {
                    if (checked) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                if (key == "remember" && !checked) prefs.edit().remove("location").apply()
                if (key == "auto_refresh") scheduleAutoRefresh()
                if (key == "fahrenheit") WeatherWidgetUi.updateAll(this@MainActivity)
                renderPage()
            }
        }
        container.addView(control)
        container.addView(text(description, 12f, false, palette.muted).apply {
            setPadding(0, dp(3), 0, dp(5))
        })
        content.addView(container, fullButtonParams(top = 8))
    }

    private fun addMetricRow(values: List<Metric>) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        values.forEach { value ->
            val metric = card(palette.alternate)
            metric.addView(text(value.title, 12f, true, palette.muted))
            metric.addView(text(value.value, 22f, true).apply { setPadding(0, dp(5), 0, dp(2)) })
            metric.addView(text(value.note, 10f, false, palette.muted))
            row.addView(metric, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(4)
                rightMargin = dp(4)
                bottomMargin = dp(7)
            })
        }
        content.addView(row, fullButtonParams(top = 7))
    }

    private fun card(color: Int = palette.surface): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(13), dp(14), dp(13))
        background = rounded(color, 16, palette.border)
    }

    private fun statusView(value: String) = text(value, 12f, false, palette.muted).apply {
        setPadding(2, dp(9), 2, dp(8))
    }

    private fun input(hintText: String, value: String): EditText = EditText(this).apply {
        setSingleLine(true)
        hint = hintText
        setHintTextColor(palette.muted)
        setTextColor(palette.text)
        textSize = 15f
        setPadding(dp(13), 0, dp(10), 0)
        background = rounded(palette.surface, 13, palette.border)
        setText(value)
    }

    private fun text(value: String, size: Float, bold: Boolean, color: Int = palette.text): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun button(label: String, selected: Boolean = false, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 12f
            isAllCaps = false
            setTextColor(if (selected) palette.background else palette.text)
            background = rounded(if (selected) palette.accent else palette.surface, 12, palette.border)
            minHeight = 0
            minimumHeight = 0
            stateListAnimator = null
            setPadding(dp(5), dp(2), dp(5), dp(2))
            setOnClickListener { action() }
        }

    private fun rounded(color: Int, radiusDp: Int, border: Int = color): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(dp(1), border)
        }

    private fun fullButtonParams(top: Int = 0) =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { if (top > 0) topMargin = dp(top) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun searchWeather(query: String) {
        val value = query.trim()
        if (value.isEmpty()) {
            statusMessage = "Enter a city name or coordinates."
            renderPage()
            return
        }
        locationDraft = value
        currentQuery = value
        page = "weather"
        loadWeather(value)
    }

    private fun loadWeather(query: String) {
        val currentRequest = ++requestNumber
        statusMessage = "Finding weather for $query…"
        if (::content.isInitialized) renderPage()
        executor.execute {
            try {
                val result = fetchWeather(query)
                runOnUiThread {
                    if (currentRequest == requestNumber && !isFinishing) {
                        val nextNewsKey = "${result.latitude},${result.longitude}"
                        if (currentNewsKey != nextNewsKey) {
                            newsStories = emptyList()
                            newsError = null
                            newsFetchedAt = 0L
                            expandedStories.clear()
                        }
                        currentNewsKey = nextNewsKey
                        val savedName = savedLocations().firstOrNull { it.query == query }?.name
                        weather = if (savedName != null) result.copy(place = savedName) else result
                        prefs.edit()
                            .putString("widget_location", query)
                            .putString("widget_place", weather?.place ?: result.place)
                            .apply()
                        WeatherWidgetJobs.scheduleImmediate(this@MainActivity)
                        statusMessage = "Weather updated · ${result.time.takeAfterT()}"
                        if (prefs.getBoolean("remember", true)) rememberCurrentLocation()
                        if (startupWeatherPending && !prefs.getBoolean("compact_start", true) && page == "weather") {
                            page = "details"
                        }
                        startupWeatherPending = false
                        renderPage()
                        if (page == "news") loadNews(force = false)
                    }
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (currentRequest == requestNumber && !isFinishing) {
                        statusMessage = error.message ?: "Weather data is unavailable. Try again."
                        renderPage()
                    }
                }
            }
        }
    }

    private fun fetchWeather(query: String): Weather {
        val place = resolveLocation(query)
        val forecastUrl = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=${place.latitude}&longitude=${place.longitude}" +
            "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m,wind_direction_10m,surface_pressure" +
            "&hourly=temperature_2m,weather_code,precipitation_probability,is_day,visibility" +
            "&daily=sunrise,sunset,uv_index_max,weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&forecast_days=7&timezone=auto"
        val data = JSONObject(request(forecastUrl))
        val current = data.getJSONObject("current")
        val hourly = data.getJSONObject("hourly")
        val daily = data.getJSONObject("daily")
        val hourTimes = hourly.getJSONArray("time")
        val nowHour = current.getString("time").take(13) + ":00"
        var firstHour = 0
        for (index in 0 until hourTimes.length()) {
            if (hourTimes.getString(index) >= nowHour) {
                firstHour = index
                break
            }
        }
        val hours = (firstHour until minOf(firstHour + 24, hourTimes.length())).map { index ->
            Hour(
                time = hourTimes.getString(index),
                temperature = hourly.numberAt("temperature_2m", index),
                code = hourly.intAt("weather_code", index),
                rain = hourly.intAt("precipitation_probability", index).takeIf { it >= 0 },
                isDay = hourly.intAt("is_day", index) != 0
            )
        }
        val days = (0 until minOf(7, daily.getJSONArray("time").length())).map { index ->
            Day(
                date = daily.getJSONArray("time").getString(index),
                high = daily.numberAt("temperature_2m_max", index),
                low = daily.numberAt("temperature_2m_min", index),
                code = daily.intAt("weather_code", index),
                rain = daily.intAt("precipitation_probability_max", index).takeIf { it >= 0 }
            )
        }
        val visibility = hourly.numberAt("visibility", firstHour).takeIf { it.isFinite() }?.div(1000.0)
        val airQuality = try {
            val airUrl = "https://air-quality-api.open-meteo.com/v1/air-quality" +
                "?latitude=${place.latitude}&longitude=${place.longitude}&current=us_aqi&timezone=auto"
            JSONObject(request(airUrl)).optJSONObject("current")?.let { current ->
                if (current.has("us_aqi") && !current.isNull("us_aqi")) current.optInt("us_aqi") else null
            }
        } catch (_: Exception) {
            null
        }
        return Weather(
            place = place.name,
            latitude = place.latitude,
            longitude = place.longitude,
            timezone = data.optString("timezone", ""),
            time = current.optString("time", ""),
            temperature = current.number("temperature_2m"),
            feelsLike = current.number("apparent_temperature"),
            humidity = current.number("relative_humidity_2m"),
            wind = current.number("wind_speed_10m"),
            direction = current.number("wind_direction_10m"),
            pressure = current.number("surface_pressure"),
            condition = weatherDescription(current.optInt("weather_code", -1)),
            code = current.optInt("weather_code", -1),
            uvIndex = daily.numberAt("uv_index_max", 0).takeIf { it.isFinite() },
            airQuality = airQuality,
            visibility = visibility,
            sunrise = daily.stringAt("sunrise", 0),
            sunset = daily.stringAt("sunset", 0),
            hours = hours,
            days = days
        )
    }

    private fun resolveLocation(query: String): ResolvedPlace {
        val coordinates = query.split(',')
        if (coordinates.size == 2) {
            val latitude = coordinates[0].trim().toDoubleOrNull()
            val longitude = coordinates[1].trim().toDoubleOrNull()
            if (latitude != null && longitude != null) {
                require(latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0) {
                    "Latitude must be -90 to 90 and longitude -180 to 180."
                }
                return ResolvedPlace(latitude, longitude, "Coordinates")
            }
        }
        val encoded = URLEncoder.encode(query, "UTF-8")
        val results = JSONObject(
            request("https://geocoding-api.open-meteo.com/v1/search?name=$encoded&count=1&language=en&format=json")
        ).optJSONArray("results")
        if (results == null || results.length() == 0) {
            throw IllegalArgumentException("No location found for “$query”. Try another city.")
        }
        val result = results.getJSONObject(0)
        val name = listOf(
            result.optString("name"),
            result.optString("admin1").takeIf { it.isNotBlank() },
            result.optString("country").takeIf { it.isNotBlank() }
        ).filterNotNull().filter { it.isNotBlank() }.distinct().joinToString(", ")
        return ResolvedPlace(result.getDouble("latitude"), result.getDouble("longitude"), name)
    }

    private fun request(address: String): String {
        val connection = URL(address).openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 20000
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/json, application/rss+xml, application/xml")
        connection.setRequestProperty("User-Agent", "UsoftWeatherAndroid/1.0")
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("Weather or news service returned HTTP $code.")
            return body
        } finally {
            connection.disconnect()
        }
    }

    private fun saveLocation(query: String, customLabel: String) {
        if (query.isBlank()) {
            statusMessage = "Enter a city name or coordinates."
            renderPage()
            return
        }
        statusMessage = "Resolving location…"
        renderPage()
        executor.execute {
            try {
                val resolved = resolveLocation(query)
                val entry = SavedLocation(
                    name = customLabel.ifBlank { resolved.name },
                    query = "${resolved.latitude}, ${resolved.longitude}",
                    latitude = resolved.latitude,
                    longitude = resolved.longitude
                )
                runOnUiThread {
                    val locations = savedLocations().toMutableList()
                    val existingIndex = locations.indexOfFirst { it.query == entry.query }
                    if (existingIndex >= 0) locations[existingIndex] = entry else locations.add(entry)
                    storeLocations(locations)
                    locationDraft = entry.query
                    labelDraft = ""
                    currentQuery = entry.query
                    prefs.edit()
                        .putString("widget_location", entry.query)
                        .putString("widget_place", entry.name)
                        .apply()
                    page = "weather"
                    loadWeather(entry.query)
                }
            } catch (error: Exception) {
                runOnUiThread {
                    statusMessage = error.message ?: "Could not save this location."
                    renderPage()
                }
            }
        }
    }

    private fun savedLocations(): List<SavedLocation> {
        val json = prefs.getString("saved_locations", "[]") ?: "[]"
        return try {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val query = item.optString("query")
                if (query.isBlank()) return@mapNotNull null
                SavedLocation(
                    name = item.optString("name", query),
                    query = query,
                    latitude = item.optDouble("latitude", Double.NaN),
                    longitude = item.optDouble("longitude", Double.NaN)
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun storeLocations(locations: List<SavedLocation>) {
        val array = JSONArray()
        locations.forEach { location ->
            array.put(JSONObject()
                .put("name", location.name)
                .put("query", location.query)
                .put("latitude", location.latitude)
                .put("longitude", location.longitude))
        }
        prefs.edit().putString("saved_locations", array.toString()).apply()
    }

    private fun deleteLocation(location: SavedLocation) {
        val remaining = savedLocations().filterNot { it.query == location.query }
        storeLocations(remaining)
        if (currentQuery == location.query) {
            val next = remaining.firstOrNull()
            currentQuery = next?.query ?: "Colombo"
            locationDraft = currentQuery
            rememberCurrentLocation()
            loadWeather(currentQuery)
        } else renderPage()
    }

    private fun rememberCurrentLocation() {
        val editor = prefs.edit()
        if (prefs.getBoolean("remember", true)) editor.putString("location", currentQuery)
        else editor.remove("location")
        editor.apply()
    }

    private fun loadNews(force: Boolean) {
        val data = weather ?: return
        if (newsLoading) return
        if (!force && newsFetchedAt > 0 && System.currentTimeMillis() - newsFetchedAt < 300_000L) return
        newsLoading = true
        newsError = null
        renderPage()
        executor.execute {
            try {
                val area = reverseGeocode(data.latitude, data.longitude)
                val query = URLEncoder.encode("\"${area.first}\" \"${area.second}\" when:7d", "UTF-8")
                val url = "https://news.google.com/rss/search?q=$query&hl=en-US&gl=US&ceid=US:en"
                val stories = parseNews(request(url)).take(30)
                runOnUiThread {
                    newsStories = stories
                    newsFetchedAt = System.currentTimeMillis()
                    newsLoading = false
                    newsError = null
                    renderPage()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    newsLoading = false
                    newsError = error.message ?: "News unavailable. Check your connection and try again."
                    renderPage()
                }
            }
        }
    }

    private var newsFetchedAt = 0L

    private fun reverseGeocode(latitude: Double, longitude: Double): Pair<String, String> {
        val url = "https://nominatim.openstreetmap.org/reverse?lat=$latitude&lon=$longitude&format=jsonv2&zoom=10&accept-language=en"
        val address = JSONObject(request(url)).optJSONObject("address")
            ?: throw IllegalArgumentException("No nearby news area found for this location.")
        val city = listOf("city", "town", "village", "municipality", "county", "state")
            .firstNotNullOfOrNull { key -> address.optString(key).takeIf { it.isNotBlank() } }
            ?: throw IllegalArgumentException("No nearby news area found for this location.")
        return city to address.optString("country")
    }

    private fun parseNews(xml: String): List<NewsStory> {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(xml.reader())
        val stories = mutableListOf<NewsStory>()
        var inItem = false
        var title = ""
        var url = ""
        var publication = ""
        var source = "Publisher"
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT && stories.size < 60) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "item" -> {
                        inItem = true
                        title = ""
                        url = ""
                        publication = ""
                        source = "Publisher"
                    }
                    "title" -> if (inItem) title = parser.nextText().trim()
                    "link" -> if (inItem) url = parser.nextText().trim()
                    "pubDate" -> if (inItem) publication = parser.nextText().trim()
                    "source" -> if (inItem) source = parser.nextText().trim().ifBlank { "Publisher" }
                }
                XmlPullParser.END_TAG -> if (parser.name == "item" && inItem) {
                    val parsedDate = parseRssDate(publication)
                    val isSecureLink = url.startsWith("https://", ignoreCase = true)
                    if (title.isNotBlank() && isSecureLink && parsedDate != null &&
                        System.currentTimeMillis() - parsedDate.time in 0..604_800_000L &&
                        stories.none { it.title.equals(title, ignoreCase = true) }) {
                        stories.add(NewsStory(title, url, source, publication))
                    }
                    inItem = false
                }
            }
            event = parser.next()
        }
        return stories.sortedByDescending { parseRssDate(it.published)?.time ?: 0L }
    }

    private fun parseRssDate(value: String): Date? = try {
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US).parse(value)
    } catch (_: Exception) {
        null
    }

    private fun openArticle(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(this, "No browser is available to open this story.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun scheduleAutoRefresh() {
        autoRefreshTask?.let(handler::removeCallbacks)
        autoRefreshTask = null
        if (!prefs.getBoolean("auto_refresh", false) || isFinishing) return
        val task = object : Runnable {
            override fun run() {
                if (prefs.getBoolean("auto_refresh", false) && !isFinishing) {
                    loadWeather(currentQuery)
                    handler.postDelayed(this, 600_000L)
                }
            }
        }
        autoRefreshTask = task
        handler.postDelayed(task, 600_000L)
    }

    private fun requestLiveWidgetLocation() {
        if (!hasLocationPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) &&
            !hasLocationPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)) {
            requestPermissions(
                arrayOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                REQUEST_FOREGROUND_LOCATION
            )
            return
        }
        requestBackgroundWidgetLocation()
    }

    private fun requestBackgroundWidgetLocation() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
            enableLiveWidgetLocation()
            return
        }
        if (hasLocationPermission(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
            enableLiveWidgetLocation()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Allow live widget location")
            .setMessage(
                "Weather uses your location in the background only to refresh its home-screen widget when you move. Android may delay updates to save battery. No location service notification will be shown, so continuous tracking cannot be guaranteed."
            )
            .setNegativeButton("Not now") { _, _ ->
                prefs.edit().putBoolean("live_location", false).apply()
                renderPage()
            }
            .setPositiveButton("Continue") { _, _ ->
                if (android.os.Build.VERSION.SDK_INT == android.os.Build.VERSION_CODES.Q) {
                    requestPermissions(
                        arrayOf(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                        REQUEST_BACKGROUND_LOCATION
                    )
                } else {
                    awaitingBackgroundPermissionReturn = true
                    val settingsIntent = Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:$packageName")
                    )
                    Toast.makeText(
                        this,
                        "On the app permissions page, choose Location, then Allow all the time.",
                        Toast.LENGTH_LONG
                    ).show()
                    startActivity(settingsIntent)
                }
            }
            .show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_FOREGROUND_LOCATION) {
            val granted = grantResults.any { it == PackageManager.PERMISSION_GRANTED }
            if (granted) requestBackgroundWidgetLocation() else {
                prefs.edit().putBoolean("live_location", false).apply()
                statusMessage = "Location permission was not granted. The widget can use your saved location instead."
                renderPage()
            }
        } else if (requestCode == REQUEST_BACKGROUND_LOCATION) {
            if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
                enableLiveWidgetLocation()
            } else {
                prefs.edit().putBoolean("live_location", false).apply()
                statusMessage = "Background location was not granted. The widget will use your saved location."
                renderPage()
            }
        }
    }

    private fun hasLocationPermission(permission: String): Boolean =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun enableLiveWidgetLocation() {
        prefs.edit().putBoolean("live_location", true).apply()
        statusMessage = "Live device location enabled for the home-screen widget."
        WeatherWidgetJobs.scheduleImmediate(this)
        renderPage()
    }

    private fun weatherSymbol(code: Int): String = when {
        code < 0 -> "—"
        code <= 1 -> "☀"
        code <= 3 -> "☁"
        code == 45 || code == 48 -> "≋"
        code >= 95 -> "ϟ"
        code in listOf(71, 73, 75, 77, 85, 86) -> "❄"
        else -> "☂"
    }

    private fun weatherDescription(code: Int): String = when (code) {
        0 -> "Clear sky"
        1 -> "Mainly clear"
        2 -> "Partly cloudy"
        3 -> "Overcast"
        45 -> "Fog"
        48 -> "Depositing rime fog"
        51 -> "Light drizzle"
        53 -> "Moderate drizzle"
        55 -> "Dense drizzle"
        56 -> "Light freezing drizzle"
        57 -> "Dense freezing drizzle"
        61 -> "Slight rain"
        63 -> "Moderate rain"
        65 -> "Heavy rain"
        66 -> "Light freezing rain"
        67 -> "Heavy freezing rain"
        71 -> "Slight snow"
        73 -> "Moderate snow"
        75 -> "Heavy snow"
        77 -> "Snow grains"
        80 -> "Slight rain showers"
        81 -> "Moderate rain showers"
        82 -> "Violent rain showers"
        85 -> "Slight snow showers"
        86 -> "Heavy snow showers"
        95 -> "Thunderstorm"
        96 -> "Thunderstorm with slight hail"
        99 -> "Thunderstorm with heavy hail"
        else -> "Unknown conditions"
    }

    private fun Double.asTemperature(): String {
        if (!isFinite()) return "—"
        val value = if (prefs.getBoolean("fahrenheit", false)) this * 9.0 / 5.0 + 32.0 else this
        return String.format(Locale.getDefault(), "%.0f", value)
    }

    private fun Double.asWhole(): String =
        if (isFinite()) String.format(Locale.getDefault(), "%.0f", this) else "—"

    private fun Double.asOneDecimal(): String =
        if (isFinite()) String.format(Locale.getDefault(), "%.1f", this) else "Unavailable"

    private fun Double.asCompass(): String {
        if (!isFinite()) return "—"
        val compass = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        return compass[((this / 45.0).roundToInt() + 8) % 8]
    }

    private fun Double.formatCoord(): String = String.format(Locale.US, "%.5f", this)
    private fun String.takeAfterT(): String = substringAfter('T', this).take(5).ifBlank { "—" }

    private fun String?.formatClock(): String {
        if (this.isNullOrBlank()) return "Unavailable"
        return try {
            val time = substringAfter('T')
            val parsed = SimpleDateFormat("HH:mm", Locale.US).parse(time)
            if (parsed == null) "Unavailable" else SimpleDateFormat("hh:mm a", Locale.US).format(parsed)
        } catch (_: Exception) {
            "Unavailable"
        }
    }

    private fun String.toForecastDay(): String = try {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(this)
        if (date == null) this else SimpleDateFormat("EEE, dd MMM", Locale.getDefault()).format(date)
    } catch (_: Exception) {
        this
    }

    private fun String.formatNewsDate(): String {
        val date = parseRssDate(this) ?: return this
        return SimpleDateFormat("EEEE, MMMM dd, yyyy", Locale.getDefault()).format(date)
    }
}

private data class Metric(val title: String, val value: String, val note: String)
private data class ResolvedPlace(val latitude: Double, val longitude: Double, val name: String)
private data class SavedLocation(val name: String, val query: String, val latitude: Double, val longitude: Double)
private data class NewsStory(val title: String, val url: String, val source: String, val published: String)

private data class Weather(
    val place: String,
    val latitude: Double,
    val longitude: Double,
    val timezone: String,
    val time: String,
    val temperature: Double,
    val feelsLike: Double,
    val humidity: Double,
    val wind: Double,
    val direction: Double,
    val pressure: Double,
    val condition: String,
    val code: Int,
    val uvIndex: Double?,
    val airQuality: Int?,
    val visibility: Double?,
    val sunrise: String?,
    val sunset: String?,
    val hours: List<Hour>,
    val days: List<Day>
)

private data class Hour(
    val time: String,
    val temperature: Double,
    val code: Int,
    val rain: Int?,
    val isDay: Boolean
)

private data class Day(
    val date: String,
    val high: Double,
    val low: Double,
    val code: Int,
    val rain: Int?
)

private fun JSONObject.number(key: String): Double {
    val value = optDouble(key, Double.NaN)
    return if (value.isFinite()) value else Double.NaN
}

private fun JSONObject.numberAt(key: String, index: Int): Double {
    val values = optJSONArray(key) ?: return Double.NaN
    return if (index in 0 until values.length()) values.optDouble(index, Double.NaN) else Double.NaN
}

private fun JSONObject.intAt(key: String, index: Int): Int {
    val values: JSONArray = optJSONArray(key) ?: return -1
    return if (index in 0 until values.length()) values.optInt(index, -1) else -1
}

private fun JSONObject.stringAt(key: String, index: Int): String? {
    val values = optJSONArray(key) ?: return null
    return if (index in 0 until values.length()) values.optString(index).takeIf { it.isNotBlank() } else null
}
