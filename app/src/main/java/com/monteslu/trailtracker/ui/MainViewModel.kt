package com.monteslu.trailtracker.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.monteslu.trailtracker.data.GpsPoint
import com.monteslu.trailtracker.data.SessionState
import com.monteslu.trailtracker.managers.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val locationManager = LocationManager(application)
    private val compassManager = CompassManager(application)
    private val cameraManager = CameraManager(application)
    private val sessionManager = SessionManager(application)
    private val powerManager = PowerManager(application)
    
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
    
    init {
        checkForExistingSession()
        startSensorUpdates()
        refreshRoutes()
    }
    
    private fun checkForExistingSession() {
        val lastSession = sessionManager.getLastSession()
        if (lastSession != null && lastSession.isRecording) {
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
            }
        }
    }
    
    fun startLocationUpdates() {
        viewModelScope.launch {
            locationManager.getLocationUpdates { _compass.value }.collect { gpsPoint ->
                _gpsPoint.value = gpsPoint
                if (_uiState.value.isRecording) {
                    sessionManager.logGpsPoint(gpsPoint)
                }
            }
        }
    }
    
    fun startSession(routeName: String) {
        val success = sessionManager.startOrResumeSession(routeName)
        if (success) {
            _uiState.update { it.copy(
                currentRoute = routeName,
                hasActiveSession = true,
                isRecording = false
            )}
            refreshRoutes() // Refresh routes when starting new session
        }
    }
    
    fun startRecording() {
        val routeName = _uiState.value.currentRoute
        if (routeName.isNotEmpty()) {
            powerManager.acquireWakeLock()
            sessionManager.startCapture(routeName, cameraManager, viewModelScope) { fps ->
                _fps.value = fps
            }
            _uiState.update { it.copy(isRecording = true) }
        }
    }
    
    fun pauseRecording() {
        powerManager.releaseWakeLock()
        sessionManager.stopCapture(cameraManager)
        _uiState.update { it.copy(isRecording = false) }
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
    
    fun getCameraManager(): CameraManager = cameraManager
    
    fun quitApplication() {
        // Stop all active managers
        locationManager.stopLocationUpdates()
        compassManager.stopCompass()
        cameraManager.shutdown()
        powerManager.cleanup()
        
        // Exit application
        android.os.Process.killProcess(android.os.Process.myPid())
    }
    
    override fun onCleared() {
        super.onCleared()
        locationManager.stopLocationUpdates()
        compassManager.stopCompass()
        cameraManager.shutdown()
        powerManager.cleanup()
    }
}

data class MainUiState(
    val currentRoute: String = "",
    val hasActiveSession: Boolean = false,
    val isRecording: Boolean = false,
    val showMenu: Boolean = false
)