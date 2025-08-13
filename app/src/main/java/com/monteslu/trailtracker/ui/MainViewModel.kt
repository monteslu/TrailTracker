package com.monteslu.trailtracker.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.monteslu.trailtracker.data.GpsPoint
import com.monteslu.trailtracker.data.SessionState
import com.monteslu.trailtracker.data.SessionConfig
import com.monteslu.trailtracker.managers.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val locationManager = LocationManager(application)
    private val compassManager = CompassManager(application)
    private val cameraManager = CameraManager(application)
    private val sessionManager = SessionManager(application)
    private val powerManager = PowerManager(application)
    private val batteryManager = BatteryManager(application)
    private val weatherManager = WeatherManager()
    
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()
    
    private val _compass = MutableStateFlow(0f)
    val compass: StateFlow<Float> = _compass.asStateFlow()
    
    private val _gpsPoint = MutableStateFlow<GpsPoint?>(null)
    val gpsPoint: StateFlow<GpsPoint?> = _gpsPoint.asStateFlow()
    
    private val _fps = MutableStateFlow(0f)
    val fps: StateFlow<Float> = _fps.asStateFlow()
    
    private val _routes = MutableStateFlow<List<String>>(emptyList())
    val routes: StateFlow<List<String>> = _routes.asStateFlow()
    
    private val _sessionState = MutableStateFlow<SessionState?>(null)
    val sessionState: StateFlow<SessionState?> = _sessionState.asStateFlow()
    
    private val _frameSkip = MutableStateFlow(1)
    val frameSkip: StateFlow<Int> = _frameSkip.asStateFlow()
    
    private val _batteryLevel = MutableStateFlow(100)
    val batteryLevel: StateFlow<Int> = _batteryLevel.asStateFlow()
    
    private val _currentWeather = MutableStateFlow<WeatherData?>(null)
    val currentWeather: StateFlow<WeatherData?> = _currentWeather.asStateFlow()
    
    init {
        checkForExistingSession()
        startSensorUpdates()
        refreshRoutes()
        startSessionStateUpdates()
        startBatteryMonitoring()
    }
    
    private fun checkForExistingSession() {
        val lastSession = sessionManager.getLastSession()
        if (lastSession != null && lastSession.isRecording) {
            // Load config for existing session
            val config = sessionManager.getSessionConfig(lastSession.routeName)
            if (config != null) {
                _frameSkip.value = config.frameSkip ?: 1
            }
            _uiState.update { it.copy(
                currentRoute = lastSession.routeName,
                hasActiveSession = true
            )}
        }
    }
    
    private fun startSensorUpdates() {
        viewModelScope.launch {
            compassManager.getCompassUpdates().collect { heading ->
                _compass.value = heading
                // Update camera manager with latest compass data
                cameraManager.updateGpsData(_gpsPoint.value, heading)
            }
        }
    }
    
    private fun startBatteryMonitoring() {
        batteryManager.startMonitoring()
        viewModelScope.launch {
            batteryManager.batteryLevel.collect { level ->
                _batteryLevel.value = level
            }
        }
    }
    
    
    fun startLocationUpdates() {
        viewModelScope.launch {
            locationManager.getLocationUpdates { _compass.value }.collect { gpsPoint ->
                _gpsPoint.value = gpsPoint
                // Update camera manager with latest GPS data
                cameraManager.updateGpsData(gpsPoint, _compass.value)
                
                // Fetch weather when we have GPS location
                if (gpsPoint != null && weatherManager.shouldFetchWeather()) {
                    viewModelScope.launch {
                        val weather = weatherManager.fetchWeather(gpsPoint.lat, gpsPoint.lon)
                        _currentWeather.value = weather
                        cameraManager.updateWeatherData(weather)
                    }
                }
                
                if (_uiState.value.isRecording) {
                    sessionManager.logGpsPoint(gpsPoint)
                }
            }
        }
    }
    
    fun startSession(routeName: String, frameSkip: Int = 1) {
        val config = sessionManager.startOrResumeSession(routeName, frameSkip)
        _frameSkip.value = config.frameSkip
        _uiState.update { it.copy(
            currentRoute = routeName,
            hasActiveSession = true,
            isRecording = false
        )}
        refreshRoutes() // Refresh routes when starting new session
    }
    
    fun startRecording() {
        val routeName = _uiState.value.currentRoute
        if (routeName.isNotEmpty()) {
            powerManager.acquireWakeLock()
            sessionManager.startCapture(routeName, cameraManager, viewModelScope, { fps ->
                _fps.value = fps
            }, _frameSkip.value)
            _uiState.update { it.copy(isRecording = true) }
            updateSessionState()
        }
    }
    
    fun pauseRecording() {
        powerManager.releaseWakeLock()
        sessionManager.stopCapture(cameraManager)
        _uiState.update { it.copy(isRecording = false) }
        updateSessionState()
    }
    
    
    fun getAllRoutes(): List<String> = sessionManager.getAllRoutes()
    
    fun getSessionState(): SessionState? {
        val routeName = _uiState.value.currentRoute
        return if (routeName.isNotEmpty()) {
            sessionManager.getCurrentSessionState(routeName)
        } else null
    }
    
    fun getStorageWarning(): String? = sessionManager.getStorageWarning()
    
    fun deleteRoute(routeName: String) {
        sessionManager.deleteRoute(routeName)
        refreshRoutes() // Refresh the list after deletion
    }
    
    private fun refreshRoutes() {
        _routes.value = sessionManager.getAllRoutes()
    }
    
    private fun startSessionStateUpdates() {
        viewModelScope.launch {
            while (true) {
                val currentRoute = _uiState.value.currentRoute
                if (currentRoute.isNotEmpty()) {
                    _sessionState.value = sessionManager.getCurrentSessionState(currentRoute)
                } else {
                    _sessionState.value = null
                }
                kotlinx.coroutines.delay(100) // Update every 100ms
            }
        }
    }
    
    private fun updateSessionState() {
        val currentRoute = _uiState.value.currentRoute
        if (currentRoute.isNotEmpty()) {
            _sessionState.value = sessionManager.getCurrentSessionState(currentRoute)
        }
    }
    
    fun getCameraManager(): CameraManager = cameraManager
    
    fun triggerCameraFocus() {
        cameraManager.triggerAutoFocus()
    }
    
    
    fun quitApplication() {
        // Stop all active managers
        locationManager.stopLocationUpdates()
        compassManager.stopCompass()
        cameraManager.shutdown()
        powerManager.cleanup()
        batteryManager.stopMonitoring()
        weatherManager.stopPeriodicFetch()
        
        // Exit application
        android.os.Process.killProcess(android.os.Process.myPid())
    }
    
    override fun onCleared() {
        super.onCleared()
        locationManager.stopLocationUpdates()
        compassManager.stopCompass()
        cameraManager.shutdown()
        powerManager.cleanup()
        batteryManager.stopMonitoring()
        weatherManager.stopPeriodicFetch()
    }
}

data class MainUiState(
    val currentRoute: String = "",
    val hasActiveSession: Boolean = false,
    val isRecording: Boolean = false,
    val showMenu: Boolean = false
)