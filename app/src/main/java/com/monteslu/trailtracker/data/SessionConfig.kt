package com.monteslu.trailtracker.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

@Serializable
data class SessionConfig(
    val frameSkip: Int = 1,  // 1=every frame (30fps), 2=every other (15fps), etc
    val targetFPS: Int? = null, // Legacy field - use frameSkip instead  
    val sessionName: String = "",
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        private val json = Json { 
            prettyPrint = true
            ignoreUnknownKeys = true
        }
        
        fun toJson(config: SessionConfig): String = json.encodeToString(config)
        
        fun fromJson(jsonString: String): SessionConfig = 
            json.decodeFromString<SessionConfig>(jsonString)
    }
}