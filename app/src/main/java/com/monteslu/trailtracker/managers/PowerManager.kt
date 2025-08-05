package com.monteslu.trailtracker.managers

import android.content.Context
import android.os.PowerManager

class PowerManager(private val context: Context) {
    private var wakeLock: PowerManager.WakeLock? = null
    
    fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "TrailTracker::RecordingWakeLock"
            )
        }
        
        wakeLock?.let { lock ->
            if (!lock.isHeld) {
                lock.acquire(10 * 60 * 1000L) // 10 minutes timeout for safety
            }
        }
    }
    
    fun releaseWakeLock() {
        wakeLock?.let { lock ->
            if (lock.isHeld) {
                lock.release()
            }
        }
    }
    
    fun cleanup() {
        releaseWakeLock()
        wakeLock = null
    }
}