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

class CameraManager(private val context: Context) {
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var preview: Preview? = null
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val processingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private var isCapturing = AtomicBoolean(false)
    private var outputDirectory: File? = null
    private var frameCallback: ((Long) -> Unit)? = null
    
    // FPS tracking
    private var frameCount = AtomicLong(0)
    private var lastFpsTime = System.currentTimeMillis()
    private var fpsCallback: ((Float) -> Unit)? = null
    
    fun setupCamera(
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider,
        onCameraReady: () -> Unit
    ) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            
            // Preview use case - force 16:9 aspect ratio
            preview = Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .setTargetRotation(android.view.Surface.ROTATION_0) // Lock to landscape
                .build()
                .also {
                    it.setSurfaceProvider(surfaceProvider)
                }
            
            // High-speed ImageAnalysis for true 30fps frame extraction - force 16:9 aspect ratio
            imageAnalysis = ImageAnalysis.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .setTargetRotation(android.view.Surface.ROTATION_0) // Lock to landscape
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
            // Process frame asynchronously - optimized direct save
            processingScope.launch {
                try {
                    val outputFile = File(outputDir, "$timestamp.jpg")
                    
                    // Direct JPEG save without bitmap conversion
                    saveImageProxyDirectly(imageProxy, outputFile)
                    
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
                    imageProxy.close()
                }
            }
        } else {
            imageProxy.close()
        }
    }
    
    // Optimized: Direct JPEG save without bitmap conversion
    private fun saveImageProxyDirectly(imageProxy: ImageProxy, outputFile: File) {
        Log.d("CameraManager", "ImageProxy dimensions: ${imageProxy.width}x${imageProxy.height}")
        
        val yBuffer = imageProxy.planes[0].buffer
        val uBuffer = imageProxy.planes[1].buffer  
        val vBuffer = imageProxy.planes[2].buffer
        
        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()
        
        val nv21 = ByteArray(ySize + uSize + vSize)
        
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
        
        // Direct JPEG save - no bitmap conversion
        FileOutputStream(outputFile).use { out ->
            yuvImage.compressToJpeg(
                android.graphics.Rect(0, 0, imageProxy.width, imageProxy.height), 
                92, 
                out
            )
        }
    }
    
    fun stopCapture() {
        isCapturing.set(false)
        frameCallback = null
        fpsCallback = null
    }
    
    
    
    fun getPreview(): Preview? = preview
    
    fun shutdown() {
        stopCapture()
        processingScope.cancel()
        cameraExecutor.shutdown()
        cameraProvider?.unbindAll()
    }
}