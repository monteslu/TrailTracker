package com.monteslu.trailtracker.data

data class GpsPoint(
    val timestamp: Long,
    val lat: Double,
    val lon: Double,
    val alt: Double,
    val speed: Float,
    val accuracy: Float,
    val compass: Float
)