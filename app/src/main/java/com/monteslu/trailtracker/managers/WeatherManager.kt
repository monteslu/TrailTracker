package com.monteslu.trailtracker.managers

import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.TimeZone
import java.util.Locale

data class WeatherData(
    val temperature: Float,
    val windSpeed: Float,
    val windDirection: Int,
    val weatherCode: Int,
    val isDay: Int,
    val interval: Int,
    val time: String,  // ISO8601 string from API
    val timeUnix: Long, // Unix timestamp of the measurement
    val elevation: Float?,
    val latitude: Double,
    val longitude: Double,
    val generationTimeMs: Float?,
    val utcOffsetSeconds: Int?,
    val timezone: String?,
    val timezoneAbbreviation: String?,
    val fetchedAt: Long  // Unix timestamp when we actually fetched this data
)

class WeatherManager {
    companion object {
        private const val TAG = "WeatherManager"
        private const val API_URL = "https://api.open-meteo.com/v1/forecast"
        private const val FETCH_INTERVAL = 60000L // 1 minute in milliseconds
    }
    
    private var lastFetchTime = 0L
    private var currentWeather: WeatherData? = null
    private var fetchJob: Job? = null
    
    fun getCurrentWeather(): WeatherData? = currentWeather
    
    suspend fun fetchWeather(latitude: Double, longitude: Double): WeatherData? {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("$API_URL?latitude=$latitude&longitude=$longitude&current_weather=true&windspeed_unit=kmh&temperature_unit=celsius")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                
                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val reader = BufferedReader(InputStreamReader(connection.inputStream))
                    val response = reader.use { it.readText() }
                    
                    val json = JSONObject(response)
                    val currentWeatherJson = json.getJSONObject("current_weather")
                    
                    // Get optional fields
                    val elevation = if (json.has("elevation")) {
                        json.getDouble("elevation").toFloat()
                    } else null
                    
                    val generationTimeMs = if (json.has("generationtime_ms")) {
                        json.getDouble("generationtime_ms").toFloat()
                    } else null
                    
                    val utcOffsetSeconds = if (json.has("utc_offset_seconds")) {
                        json.getInt("utc_offset_seconds")
                    } else null
                    
                    val timezone = if (json.has("timezone")) {
                        json.getString("timezone")
                    } else null
                    
                    val timezoneAbbreviation = if (json.has("timezone_abbreviation")) {
                        json.getString("timezone_abbreviation")
                    } else null
                    
                    // Parse the ISO8601 time string to Unix timestamp
                    val timeString = currentWeatherJson.getString("time")
                    val timeUnix = try {
                        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US)
                        sdf.timeZone = TimeZone.getTimeZone("UTC")
                        sdf.parse(timeString)?.time ?: System.currentTimeMillis()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing weather time: $timeString", e)
                        System.currentTimeMillis()
                    }
                    
                    val weather = WeatherData(
                        temperature = currentWeatherJson.getDouble("temperature").toFloat(),
                        windSpeed = currentWeatherJson.getDouble("windspeed").toFloat(),
                        windDirection = currentWeatherJson.getInt("winddirection"),
                        weatherCode = currentWeatherJson.getInt("weathercode"),
                        isDay = currentWeatherJson.getInt("is_day"),
                        interval = currentWeatherJson.getInt("interval"),
                        time = timeString,
                        timeUnix = timeUnix / 1000, // Convert to seconds for Unix timestamp
                        elevation = elevation,
                        latitude = json.getDouble("latitude"),
                        longitude = json.getDouble("longitude"),
                        generationTimeMs = generationTimeMs,
                        utcOffsetSeconds = utcOffsetSeconds,
                        timezone = timezone,
                        timezoneAbbreviation = timezoneAbbreviation,
                        fetchedAt = System.currentTimeMillis()
                    )
                    
                    currentWeather = weather
                    lastFetchTime = System.currentTimeMillis()
                    
                    val ageMinutes = (System.currentTimeMillis() - (weather.timeUnix * 1000)) / 60000
                    Log.d(TAG, "Weather fetched: ${weather.temperature}°C, ${weather.windSpeed}km/h, wind dir ${weather.windDirection}°, day=${weather.isDay}, measurement age: ${ageMinutes} minutes old")
                    weather
                } else {
                    Log.e(TAG, "Failed to fetch weather: HTTP $responseCode")
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching weather", e)
                null
            }
        }
    }
    
    fun startPeriodicFetch(latitude: Double, longitude: Double, scope: CoroutineScope) {
        stopPeriodicFetch()
        
        fetchJob = scope.launch {
            // Initial fetch
            fetchWeather(latitude, longitude)
            
            // Periodic fetches every minute
            while (isActive) {
                delay(FETCH_INTERVAL)
                fetchWeather(latitude, longitude)
            }
        }
    }
    
    fun stopPeriodicFetch() {
        fetchJob?.cancel()
        fetchJob = null
    }
    
    fun shouldFetchWeather(): Boolean {
        val currentTime = System.currentTimeMillis()
        return currentTime - lastFetchTime >= FETCH_INTERVAL
    }
    
    fun getWeatherDescription(weatherCode: Int): String {
        return when (weatherCode) {
            0 -> "Clear sky"
            1, 2, 3 -> "Partly cloudy"
            45, 48 -> "Foggy"
            51, 53, 55 -> "Drizzle"
            56, 57 -> "Freezing drizzle"
            61, 63, 65 -> "Rain"
            66, 67 -> "Freezing rain"
            71, 73, 75 -> "Snow"
            77 -> "Snow grains"
            80, 81, 82 -> "Rain showers"
            85, 86 -> "Snow showers"
            95 -> "Thunderstorm"
            96, 99 -> "Thunderstorm with hail"
            else -> "Unknown"
        }
    }
}