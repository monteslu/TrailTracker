package com.monteslu.trailtracker.managers

import android.content.Context
import android.os.Environment
import android.os.StatFs
import com.google.gson.Gson
import com.monteslu.trailtracker.data.GpsPoint
import com.monteslu.trailtracker.data.LastSession
import com.monteslu.trailtracker.data.SessionState
import com.monteslu.trailtracker.data.SessionConfig
import kotlinx.coroutines.*
import java.io.File
import java.io.FileWriter

class SessionManager(private val context: Context) {
    private val gson = Gson()
    private val baseDir = File(context.getExternalFilesDir(null), "TrailTracker")
    private val lastSessionFile = File(baseDir, "lastSession.json")
    
    private var frameCount = 0L
    private var startTime = 0L
    private var captureJob: Job? = null
    private var gpsWriter: FileWriter? = null
    private var isRecording = false
    
    init {
        baseDir.mkdirs()
    }
    
    fun getAllRoutes(): List<String> {
        if (!baseDir.exists()) return emptyList()
        
        return baseDir.listFiles()?.filter { dir ->
            dir.isDirectory
        }?.map { it.name } ?: emptyList()
    }
    
    fun startOrResumeSession(routeName: String, frameSkip: Int = 1): SessionConfig {
        val routeDir = File(baseDir, routeName)
        
        val configFile = File(routeDir, "config.json")
        val config: SessionConfig
        
        if (!routeDir.exists()) {
            // New session
            routeDir.mkdirs()
            startTime = System.currentTimeMillis()
            frameCount = 0
            
            // Create config for new session
            config = SessionConfig(
                frameSkip = frameSkip,
                sessionName = routeName,
                createdAt = startTime
            )
            configFile.writeText(SessionConfig.toJson(config))
        } else {
            // Resume existing session - count existing frames ONCE
            startTime = System.currentTimeMillis()
            frameCount = routeDir.listFiles { _, name -> 
                name.endsWith(".webp") || name.endsWith(".jpg") 
            }?.size?.toLong() ?: 0L
            
            // Load existing config or create default
            config = if (configFile.exists()) {
                try {
                    val existingConfig = SessionConfig.fromJson(configFile.readText())
                    // Handle legacy configs that use targetFPS
                    if (existingConfig.targetFPS != null && existingConfig.frameSkip == 1) {
                        // Convert old targetFPS to frameSkip
                        val convertedFrameSkip = when(existingConfig.targetFPS) {
                            30 -> 1
                            15 -> 2
                            10 -> 3
                            5 -> 6
                            else -> Math.max(1, (30 / existingConfig.targetFPS!!))
                        }
                        existingConfig.copy(frameSkip = convertedFrameSkip)
                    } else {
                        existingConfig
                    }
                } catch (e: Exception) {
                    // Fallback to default if config is corrupted
                    SessionConfig(frameSkip = 1, sessionName = routeName)
                }
            } else {
                // Create config for old sessions that don't have one
                val newConfig = SessionConfig(frameSkip = 1, sessionName = routeName)
                configFile.writeText(SessionConfig.toJson(newConfig))
                newConfig
            }
        }
        
        // Save current session state (always paused initially)
        val lastSession = LastSession(routeName, false, startTime)
        saveLastSession(lastSession)
        
        // Initialize GPS writer in append mode
        val gpsFile = File(routeDir, "points.jsonl")
        gpsWriter = FileWriter(gpsFile, true)
        
        return config
    }
    
    fun startCapture(
        routeName: String,
        cameraManager: CameraManager,
        scope: CoroutineScope,
        onFpsUpdate: (Float) -> Unit,
        frameSkip: Int = 1
    ) {
        val routeDir = File(baseDir, routeName)
        isRecording = true
        
        // Start high-speed camera capture
        cameraManager.startCapture(
            outputDir = routeDir,
            onFrameCaptured = { timestamp ->
                frameCount++
                
                // Check storage space periodically (every 100 frames)
                if (frameCount % 100L == 0L) {
                    if (getAvailableStorageGB() < 2.0) {
                        pauseSession()
                    }
                }
            },
            onFpsUpdate = onFpsUpdate,
            frameSkipValue = frameSkip
        )
    }
    
    fun logGpsPoint(gpsPoint: GpsPoint) {
        gpsWriter?.let { writer ->
            try {
                writer.write(gson.toJson(gpsPoint) + "\n")
                writer.flush()
            } catch (e: Exception) {
                // Handle error
            }
        }
    }
    
    fun pauseSession() {
        captureJob?.cancel()
        // Keep GPS writer open and lastSession.json intact for resuming
    }
    
    fun stopCapture(cameraManager: CameraManager) {
        isRecording = false
        cameraManager.stopCapture()
        pauseSession()
    }
    
    fun getLastSession(): LastSession? {
        if (!lastSessionFile.exists()) return null
        
        return try {
            val json = lastSessionFile.readText()
            gson.fromJson(json, LastSession::class.java)
        } catch (e: Exception) {
            null
        }
    }
    
    fun getSessionConfig(routeName: String): SessionConfig? {
        val routeDir = File(baseDir, routeName)
        val configFile = File(routeDir, "config.json")
        
        return if (configFile.exists()) {
            try {
                SessionConfig.fromJson(configFile.readText())
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
    }
    
    fun getCurrentSessionState(routeName: String): SessionState {
        // Use in-memory frame count - no file system scanning
        val totalFrames = frameCount
        
        // Calculate duration based on frame count (assuming ~30fps)
        val estimatedDuration = if (totalFrames > 0) {
            (totalFrames * 1000L) / 30L // milliseconds
        } else if (startTime > 0 && isRecording) {
            // Only calculate live duration when actively recording
            System.currentTimeMillis() - startTime
        } else 0L
        
        return SessionState(
            routeName = routeName,
            isRecording = isRecording,
            frameCount = totalFrames,
            startTime = startTime,
            duration = estimatedDuration
        )
    }
    
    private fun saveLastSession(lastSession: LastSession) {
        try {
            val json = gson.toJson(lastSession)
            lastSessionFile.writeText(json)
        } catch (e: Exception) {
            // Handle error
        }
    }
    
    private fun getAvailableStorageGB(): Double {
        val stat = StatFs(baseDir.path)
        val bytesAvailable = stat.blockSizeLong * stat.availableBlocksLong
        return bytesAvailable / (1024.0 * 1024.0 * 1024.0)
    }
    
    fun getStorageWarning(): String? {
        val availableGB = getAvailableStorageGB()
        return when {
            availableGB < 2.0 -> "Critical: Less than 2GB storage remaining!"
            availableGB < 10.0 -> "Warning: Less than 10GB storage remaining"
            else -> null
        }
    }
    
    fun deleteRoute(routeName: String) {
        val routeDir = File(baseDir, routeName)
        routeDir.deleteRecursively()
    }
}