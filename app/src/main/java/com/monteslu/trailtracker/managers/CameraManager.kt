package com.monteslu.trailtracker.managers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import android.util.Size
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.*
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
    private val processingScope = CoroutineScope(Dispatchers.IO.limitedParallelism(2) + SupervisorJob())
    
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
            
            // High-speed ImageAnalysis for true 30fps frame extraction - explicit 1080p resolution
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
            
            // Select back camera as default
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            
            try {
                // Unbind use cases before rebinding
                cameraProvider?.unbindAll()
                
                // Bind use cases to camera
                cameraProvider?.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )
                
                onCameraReady()
            } catch (exc: Exception) {
                // Handle error
            }
        }, ContextCompat.getMainExecutor(context))
    }
    
    fun startCapture(outputDir: File, onFrameCaptured: (Long) -> Unit, onFpsUpdate: (Float) -> Unit) {
        outputDirectory = outputDir
        frameCallback = onFrameCaptured
        fpsCallback = onFpsUpdate
        frameCount.set(0)
        lastFpsTime = System.currentTimeMillis()
        isCapturing.set(true)
        Log.d("CameraManager", "Started video frame capture")
    }
    
    // Android equivalent of requestVideoFrameCallback - processes each video frame
    private fun processVideoFrame(imageProxy: ImageProxy) {
        val timestamp = System.currentTimeMillis()
        val outputDir = outputDirectory
        
        if (outputDir != null) {
            // Drop frames if processing queue is backed up (maintain real-time performance)
            val currentProcessingCount = activeProcessingCount.get()
            if (currentProcessingCount >= 2) {
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
                    
                    // Calculate FPS every 30 frames
                    if (currentFrameCount % 30L == 0L) {
                        val now = System.currentTimeMillis()
                        val elapsed = (now - lastFpsTime) / 1000.0f
                        val fps = 30f / elapsed
                        lastFpsTime = now
                        
                        Log.d("CameraManager", "Video frame FPS: $fps")
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
        
        // Crop to exact 1920x1080 from whatever camera provides
        val cropRect = if (imageProxy.width == imageProxy.height) {
            // Square image (like 1920x1920) - crop top and bottom to get 16:9
            val cropHeight = (imageProxy.width * 9) / 16  // 16:9 ratio
            val yOffset = (imageProxy.height - cropHeight) / 2
            android.graphics.Rect(0, yOffset, imageProxy.width, yOffset + cropHeight)
        } else {
            // Use full image if already correct ratio
            android.graphics.Rect(0, 0, imageProxy.width, imageProxy.height)
        }
        
        FileOutputStream(outputFile).use { out ->
            yuvImage.compressToJpeg(cropRect, 60, out)
        }
        
        // Add EXIF data if GPS is available
        try {
            val exif = ExifInterface(outputFile.absolutePath)
            
            // Add timestamp
            val dateFormat = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
            exif.setAttribute(ExifInterface.TAG_DATETIME, dateFormat.format(Date(timestamp)))
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dateFormat.format(Date(timestamp)))
            
            // Add GPS data if available
            currentGpsPoint?.let { gps ->
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
            // Note: ExifInterface may not have TAG_GPS_IMG_DIRECTION constant, using string directly
            exif.setAttribute("GPSImgDirection", currentCompass.toString())
            exif.setAttribute("GPSImgDirectionRef", "M") // Magnetic North
            
            // Debug log
            Log.d("CameraManager", "EXIF: GPS=${currentGpsPoint?.lat},${currentGpsPoint?.lon} Compass=$currentCompass")
            
            exif.saveAttributes()
        } catch (e: Exception) {
            Log.e("CameraManager", "Error writing EXIF data", e)
        }
        
        // Add XMP metadata with full precision data
        try {
            XmpWriter.addXmpToJpeg(outputFile, currentGpsPoint, currentCompass, timestamp)
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
    
    fun shutdown() {
        stopCapture()
        processingScope.cancel()
        cameraExecutor.shutdown()
        cameraProvider?.unbindAll()
    }
}