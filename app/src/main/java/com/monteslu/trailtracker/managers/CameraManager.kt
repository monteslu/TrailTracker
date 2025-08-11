package com.monteslu.trailtracker.managers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import android.util.Size
import androidx.camera.core.*
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.*
import kotlinx.coroutines.delay
import kotlin.coroutines.suspendCoroutine
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.monteslu.trailtracker.data.GpsPoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.monteslu.trailtracker.utils.XmpWriter

class CameraManager(private val context: Context) {
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var preview: Preview? = null
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val processingScope = CoroutineScope(Dispatchers.IO.limitedParallelism(6) + SupervisorJob())
    private val metadataScope = CoroutineScope(Dispatchers.IO.limitedParallelism(4) + SupervisorJob())
    
    private var isCapturing = AtomicBoolean(false)
    private var outputDirectory: File? = null
    private var frameCallback: ((Long) -> Unit)? = null
    private var currentGpsPoint: GpsPoint? = null
    private var currentCompass: Float = 0f
    
    // FPS tracking
    private var frameCount = AtomicLong(0)
    private var lastFpsTime = System.currentTimeMillis()
    private var fpsCallback: ((Float) -> Unit)? = null
    private var activeProcessingCount = AtomicLong(0)
    
    // Frame skip control (1 = every frame, 2 = every other, 3 = every 3rd, etc)
    private var frameSkip: Int = 1
    private var frameCounter = AtomicLong(0)
    private val baseCameraFps = 30f // Camera delivers at ~30 FPS natively
    
    fun setupCamera(
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider,
        onCameraReady: () -> Unit
    ) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            
            // Preview use case - explicit 1080p resolution
            preview = Preview.Builder()
                .setTargetResolution(Size(1920, 1080))
                .setTargetRotation(android.view.Surface.ROTATION_0) // Lock to landscape
                .build()
                .also {
                    it.setSurfaceProvider(surfaceProvider)
                }
            
            // High-speed ImageAnalysis for true 30fps frame extraction
            imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(1920, 1080))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .setTargetRotation(android.view.Surface.ROTATION_0) // Lock to landscape
                .setImageQueueDepth(1) // Minimize buffer queue
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        if (isCapturing.get()) {
                            processVideoFrame(imageProxy)
                        } else {
                            imageProxy.close()
                        }
                    }
                }
            
            // Select back camera with 16:9 aspect ratio preference
            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build()
            
            try {
                // Unbind use cases before rebinding
                cameraProvider?.unbindAll()
                
                // Bind use cases to camera
                val camera = cameraProvider?.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )
                
                // Set zoom ratio to 1.0x to ensure main Wide camera stays active
                // Values below 1.0 trigger ultrawide which has poor performance
                camera?.cameraControl?.setZoomRatio(1.0f)
                
                // Initial focus, then lock after 2 seconds
                val focusPoint = SurfaceOrientedMeteringPointFactory(1.0f, 1.0f)
                    .createPoint(0.5f, 0.5f) // Center focus
                
                // First, do a normal autofocus
                val initialFocus = FocusMeteringAction.Builder(focusPoint)
                    .setAutoCancelDuration(2, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                camera?.cameraControl?.startFocusAndMetering(initialFocus)
                
                // After 2.5 seconds, lock focus to prevent hunting
                CoroutineScope(Dispatchers.Main).launch {
                    delay(2500)
                    val lockFocus = FocusMeteringAction.Builder(focusPoint)
                        .disableAutoCancel() // Lock focus permanently
                        .build()
                    camera?.cameraControl?.startFocusAndMetering(lockFocus)
                    Log.d("CameraManager", "Focus locked after initial autofocus")
                }
                
                onCameraReady()
            } catch (exc: Exception) {
                // Handle error
            }
        }, ContextCompat.getMainExecutor(context))
    }
    
    fun startCapture(outputDir: File, onFrameCaptured: (Long) -> Unit, onFpsUpdate: (Float) -> Unit, frameSkipValue: Int = 1) {
        outputDirectory = outputDir
        frameCallback = onFrameCaptured
        fpsCallback = onFpsUpdate
        
        // Frame skip value directly specifies how many frames to skip
        // 1 = every frame (30fps), 2 = every other (15fps), 3 = every 3rd (10fps), etc
        frameSkip = frameSkipValue
        
        frameCount.set(0)
        frameCounter.set(0)
        lastFpsTime = System.currentTimeMillis()
        isCapturing.set(true)
        
        val actualFps = baseCameraFps / frameSkip
        Log.d("CameraManager", "Started capture: skip every $frameSkip frames, actual ~${actualFps}fps")
    }
    
    // Android equivalent of requestVideoFrameCallback - processes each video frame
    private fun processVideoFrame(imageProxy: ImageProxy) {
        val timestamp = System.currentTimeMillis()
        val outputDir = outputDirectory
        
        if (outputDir != null) {
            // Frame skip logic - only process every Nth frame based on frameSkip
            val currentFrame = frameCounter.incrementAndGet()
            if ((currentFrame - 1) % frameSkip != 0L) {
                imageProxy.close()
                return
            }
            
            // Drop frames if processing queue is severely backed up
            val currentProcessingCount = activeProcessingCount.get()
            if (currentProcessingCount >= 6) {
                if (frameCount.get() % 30L == 0L) {
                    Log.w("CameraManager", "Dropping frame - processing queue backed up (count: $currentProcessingCount)")
                }
                imageProxy.close()
                return
            }
            
            // Process frame asynchronously - optimized direct save
            val processingCount = activeProcessingCount.incrementAndGet()
            if (processingCount > 3) {
                Log.d("CameraManager", "High processing queue: $processingCount active")
            }
            
            processingScope.launch {
                val startTime = System.currentTimeMillis()
                try {
                    val outputFile = File(outputDir, "$timestamp.jpg")
                    
                    // Fast JPEG save to maintain 30fps
                    saveImageProxyDirectly(imageProxy, outputFile, timestamp)
                    
                    val saveTime = System.currentTimeMillis() - startTime
                    if (saveTime > 50) {
                        Log.w("CameraManager", "Slow save: ${saveTime}ms for frame")
                    }
                    
                    val currentFrameCount = frameCount.incrementAndGet()
                    
                    // Calculate actual FPS every second
                    val now = System.currentTimeMillis()
                    val elapsed = now - lastFpsTime
                    if (elapsed >= 1000) {
                        val fps = (currentFrameCount * 1000.0f) / elapsed
                        frameCount.set(0)
                        lastFpsTime = now
                        
                        val expectedFps = baseCameraFps / frameSkip
                        Log.d("CameraManager", "Video frame FPS: $fps (expected: ~$expectedFps, skip: $frameSkip)")
                        fpsCallback?.invoke(fps)
                    }
                    
                    frameCallback?.invoke(timestamp)
                } catch (e: Exception) {
                    Log.e("CameraManager", "Error processing video frame", e)
                } finally {
                    activeProcessingCount.decrementAndGet()
                    imageProxy.close()
                }
            }
        } else {
            imageProxy.close()
        }
    }
    
    // Optimized: Direct JPEG save for maximum speed
    private fun saveImageProxyDirectly(imageProxy: ImageProxy, outputFile: File, timestamp: Long) {
        val saveStartTime = System.currentTimeMillis()
        
        // Log dimensions only occasionally to reduce overhead
        if (frameCount.get() % 300L == 0L) {
            Log.d("CameraManager", "ImageProxy dimensions: ${imageProxy.width}x${imageProxy.height}")
        }
        
        val planes = imageProxy.planes
        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer  
        val vBuffer = planes[2].buffer
        
        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()
        
        // Reuse byte array if possible to reduce GC pressure
        val totalSize = ySize + uSize + vSize
        val nv21 = ByteArray(totalSize)
        
        // Direct buffer operations
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)
        
        val yuvImage = android.graphics.YuvImage(
            nv21, 
            ImageFormat.NV21, 
            imageProxy.width, 
            imageProxy.height, 
            null
        )
        
        // Crop to 16:9 if we have square input
        val cropRect = if (imageProxy.width == imageProxy.height) {
            // Square image - crop to 16:9 (1920x1080 from 1920x1920)
            val cropHeight = 1080
            val yOffset = (imageProxy.height - cropHeight) / 2
            android.graphics.Rect(0, yOffset, imageProxy.width, yOffset + cropHeight)
        } else {
            android.graphics.Rect(0, 0, imageProxy.width, imageProxy.height)
        }
        
        val jpegStartTime = System.currentTimeMillis()
        FileOutputStream(outputFile).use { out ->
            yuvImage.compressToJpeg(cropRect, 85, out) // High quality for trail recording
        }
        val jpegTime = System.currentTimeMillis() - jpegStartTime
        
        // Log timing for JPEG only
        val jpegOnlyTime = System.currentTimeMillis() - saveStartTime
        if (frameCount.get() % 30L == 0L) {
            Log.d("CameraManager", "JPEG save: ${jpegTime}ms")
        }
        if (jpegOnlyTime > 33) {
            Log.w("CameraManager", "SLOW JPEG: ${jpegOnlyTime}ms")
        }
        
        // Write metadata asynchronously without blocking
        val gpsData = currentGpsPoint
        val compassData = currentCompass
        metadataScope.launch {
            try {
                writeMetadata(outputFile, gpsData, compassData, timestamp)
            } catch (e: Exception) {
                // Silently ignore metadata errors to maintain performance
            }
        }
    }
    
    private fun writeMetadata(outputFile: File, gpsData: GpsPoint?, compassData: Float, timestamp: Long) {
        // Add EXIF data if GPS is available
        try {
            val exif = ExifInterface(outputFile.absolutePath)
            
            // Add timestamp
            val dateFormat = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
            exif.setAttribute(ExifInterface.TAG_DATETIME, dateFormat.format(Date(timestamp)))
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dateFormat.format(Date(timestamp)))
            
            // Add GPS data if available
            gpsData?.let { gps ->
                exif.setLatLong(gps.lat, gps.lon)
                exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE, gps.alt.toString())
                exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF, if (gps.alt >= 0) "0" else "1")
                
                // Add speed if available
                if (gps.speed > 0) {
                    exif.setAttribute(ExifInterface.TAG_GPS_SPEED, gps.speed.toString())
                    exif.setAttribute(ExifInterface.TAG_GPS_SPEED_REF, "M") // meters/second
                }
                
                // GPS timestamp
                exif.setAttribute(ExifInterface.TAG_GPS_TIMESTAMP, dateFormat.format(Date(gps.timestamp)))
            }
            
            // Add compass direction
            exif.setAttribute("GPSImgDirection", compassData.toString())
            exif.setAttribute("GPSImgDirectionRef", "M") // Magnetic North
            
            exif.saveAttributes()
        } catch (e: Exception) {
            Log.e("CameraManager", "Error writing EXIF data", e)
        }
        
        // Add XMP metadata with full precision data
        try {
            XmpWriter.addXmpToJpeg(outputFile, gpsData, compassData, timestamp)
        } catch (e: Exception) {
            Log.e("CameraManager", "Error writing XMP data", e)
        }
    }
    
    fun stopCapture() {
        isCapturing.set(false)
        frameCallback = null
        fpsCallback = null
    }
    
    fun updateGpsData(gpsPoint: GpsPoint?, compass: Float) {
        currentGpsPoint = gpsPoint
        currentCompass = compass
    }
    
    
    
    fun getPreview(): Preview? = preview
    
    fun getExpectedFps(): Float = baseCameraFps / frameSkip
    
    fun getFrameSkipDescription(): String = when(frameSkip) {
        1 -> "Every frame"
        2 -> "Every 2nd frame"
        3 -> "Every 3rd frame"
        else -> "Every ${frameSkip}th frame"
    }
    
    fun shutdown() {
        stopCapture()
        processingScope.cancel()
        metadataScope.cancel()
        cameraExecutor.shutdown()
        cameraProvider?.unbindAll()
    }
}