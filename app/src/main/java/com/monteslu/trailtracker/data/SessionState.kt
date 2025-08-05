package com.monteslu.trailtracker.data

data class SessionState(
    val routeName: String,
    val isRecording: Boolean,
    val frameCount: Long,
    val startTime: Long,
    val duration: Long
)

data class LastSession(
    val routeName: String,
    val isRecording: Boolean,
    val startTime: Long
)