package com.monteslu.trailtracker.ui

import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.monteslu.trailtracker.data.GpsPoint
import com.monteslu.trailtracker.data.SessionState
import com.monteslu.trailtracker.ui.components.MenuDialog
import com.monteslu.trailtracker.ui.components.NewSessionDialog
import com.monteslu.trailtracker.ui.components.ResumeSessionDialog
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun MainScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by viewModel.uiState.collectAsState()
    val gpsPoint by viewModel.gpsPoint.collectAsState()
    val compass by viewModel.compass.collectAsState()
    val fps by viewModel.fps.collectAsState()
    val allRoutes by viewModel.routes.collectAsState()
    val sessionState by viewModel.sessionState.collectAsState()
    
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var cameraReady by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showNewSessionDialog by remember { mutableStateOf(false) }
    var showResumeDialog by remember { mutableStateOf(false) }
    
    Box(modifier = Modifier.fillMaxSize()) {
        // Camera Preview
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).also { preview ->
                    previewView = preview
                    viewModel.getCameraManager().setupCamera(
                        lifecycleOwner = lifecycleOwner,
                        surfaceProvider = preview.surfaceProvider,
                        onCameraReady = { cameraReady = true }
                    )
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        
        // GPS Overlay - moved right to avoid menu button overlap
        GpsOverlay(
            gpsPoint = gpsPoint,
            compass = compass,
            fps = fps,
            isRecording = uiState.isRecording,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 88.dp, top = 16.dp, end = 16.dp, bottom = 16.dp)
        )
        
        // Storage Warning
        viewModel.getStorageWarning()?.let { warning ->
            Card(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Red.copy(alpha = 0.9f))
            ) {
                Text(
                    text = warning,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
        
        // Session Info
        if (uiState.hasActiveSession && sessionState != null) {
            SessionInfoOverlay(
                sessionState = sessionState,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
            )
        }
        
        // Menu Button
        FloatingActionButton(
            onClick = { showMenu = true },
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
                .size(56.dp),
            containerColor = MaterialTheme.colorScheme.primary
        ) {
            Icon(Icons.Default.Menu, contentDescription = "Menu")
        }
        
        // Control Buttons
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(32.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (!uiState.hasActiveSession) {
                Button(
                    onClick = { showNewSessionDialog = true },
                    modifier = Modifier.height(56.dp)
                ) {
                    Text("▶ Start New Session")
                }
                
                Button(
                    onClick = { showResumeDialog = true },
                    modifier = Modifier.height(56.dp),
                    enabled = allRoutes.isNotEmpty()
                ) {
                    Text("📂 Resume Session")
                }
            } else {
                // Recording controls
                FloatingActionButton(
                    onClick = {
                        if (uiState.isRecording) {
                            viewModel.pauseRecording()
                        } else {
                            viewModel.startRecording()
                        }
                    },
                    modifier = Modifier.size(72.dp),
                    containerColor = if (uiState.isRecording) Color.Red else Color.Green
                ) {
                    if (uiState.isRecording) {
                        Text("⏸", fontSize = 32.sp)
                    } else {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Start",
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }
        }
        
        // Dialogs
        MenuDialog(
            isVisible = showMenu,
            onDismiss = { showMenu = false },
            onStartNewSession = { 
                showMenu = false
                showNewSessionDialog = true 
            },
            onResumeSession = { 
                showMenu = false
                showResumeDialog = true 
            },
            onDeleteRoute = { routeName ->
                viewModel.deleteRoute(routeName)
            },
            onQuitApp = {
                viewModel.quitApplication()
            },
            routes = allRoutes
        )
        
        NewSessionDialog(
            isVisible = showNewSessionDialog,
            onDismiss = { showNewSessionDialog = false },
            onCreateSession = { routeName ->
                viewModel.startSession(routeName)
                showNewSessionDialog = false
            }
        )
        
        ResumeSessionDialog(
            isVisible = showResumeDialog,
            routes = allRoutes,
            onDismiss = { showResumeDialog = false },
            onResumeSession = { routeName ->
                viewModel.startSession(routeName)
                showResumeDialog = false
            }
        )
    }
}

@Composable
fun GpsOverlay(
    gpsPoint: GpsPoint?,
    compass: Float,
    fps: Float,
    isRecording: Boolean,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = Color.Black.copy(alpha = 0.7f)
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            if (gpsPoint != null) {
                Text(
                    text = "Lat: ${String.format("%.6f", gpsPoint.lat)}",
                    color = Color.White,
                    fontSize = 18.sp
                )
                Text(
                    text = "Lon: ${String.format("%.6f", gpsPoint.lon)}",
                    color = Color.White,
                    fontSize = 18.sp
                )
                Text(
                    text = "Alt: ${String.format("%.1f", gpsPoint.alt)}m",
                    color = Color.White,
                    fontSize = 18.sp
                )
                Text(
                    text = "Speed: ${String.format("%.1f", gpsPoint.speed)} m/s",
                    color = Color.White,
                    fontSize = 18.sp
                )
                Text(
                    text = "Accuracy: ${String.format("%.1f", gpsPoint.accuracy)}m",
                    color = Color.White,
                    fontSize = 18.sp
                )
            } else {
                Text(
                    text = "GPS: No Fix",
                    color = Color.Red,
                    fontSize = 18.sp
                )
            }
            Text(
                text = "Compass: ${String.format("%.1f", compass)}°",
                color = Color.White,
                fontSize = 18.sp
            )
            
            if (isRecording) {
                Text(
                    text = "FPS: ${String.format("%.1f", fps)}",
                    color = if (fps >= 25f) Color.Green else Color.Red,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun SessionInfoOverlay(
    sessionState: SessionState?,
    modifier: Modifier = Modifier
) {
    if (sessionState == null) return
    
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = Color.Black.copy(alpha = 0.7f)
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Text(
                text = sessionState.routeName,
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Frames: ${sessionState.frameCount}",
                color = Color.White,
                fontSize = 18.sp
            )
            Text(
                text = "Duration: ${formatDuration(sessionState.duration)}",
                color = Color.White,
                fontSize = 18.sp
            )
            Text(
                text = if (sessionState.isRecording) "🔴 Recording" else "⏸ Paused",
                color = if (sessionState.isRecording) Color.Red else Color.Yellow,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

private fun formatDuration(millis: Long): String {
    val seconds = millis / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    return String.format("%02d:%02d:%02d", hours, minutes % 60, seconds % 60)
}