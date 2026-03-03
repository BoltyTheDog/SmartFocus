package com.example.msdksample

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.interfaces.ICameraStreamManager
import dji.v5.ux.core.widget.fpv.FPVWidget
import dji.v5.utils.common.LogUtils
import android.view.View
import dji.v5.ux.core.util.ViewUtil
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.util.Log

import android.graphics.Bitmap
import android.view.TextureView
import android.view.ViewGroup
import android.os.Handler
import android.os.Looper
import android.graphics.ImageFormat
import android.graphics.YuvImage
import java.io.ByteArrayOutputStream
import android.graphics.BitmapFactory
import dji.v5.manager.interfaces.ICameraStreamManager.FrameFormat
import org.tensorflow.lite.examples.objectdetection.streaming.StreamerManager
import org.tensorflow.lite.examples.objectdetection.ObjectDetectorHelper
import org.tensorflow.lite.examples.objectdetection.detectors.ObjectDetection
import android.graphics.Rect
import android.widget.Toast
import java.util.concurrent.Executors
import com.google.android.material.floatingactionbutton.FloatingActionButton

class DashboardActivity : AppCompatActivity(), ObjectDetectorHelper.DetectorListener {

    private val TAG = LogUtils.getTag(this)
    private lateinit var primaryFpvWidget: FPVWidget
    private lateinit var secondaryFpvWidget: FPVWidget
    
    private lateinit var streamerManager: StreamerManager
    private lateinit var objectDetectorHelper: ObjectDetectorHelper
    private val detectionExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var isDetecting = false

    private lateinit var fabSettings: com.google.android.material.floatingactionbutton.FloatingActionButton
    private lateinit var fabPlayStop: com.google.android.material.floatingactionbutton.FloatingActionButton
    private lateinit var imgCapturePreview: android.widget.ImageView
    private var frameCount = 0
    private var isFabInStopState = false
    private var targetIp = "10.188.226.28" // Default PC IP
    private var targetClasses = "bottle, person" // Default detection filter
    private var isProcessingFrameDirect = false

    private val mainFrameListener = createCameraFrameListener("MAIN")
    private val fpvFrameListener = createCameraFrameListener("FPV")

    private fun createCameraFrameListener(sourceLabel: String) = ICameraStreamManager.CameraFrameListener { frameData, offset, length, width, height, format ->
        if (isProcessingFrameDirect || !isFabInStopState) return@CameraFrameListener
        isProcessingFrameDirect = true
        
        try {
            if (frameCount % 60 == 0) {
                Log.d(TAG, "[$sourceLabel] onFrame: ${width}x${height} format: $format")
            }
            val bitmap = decodeFrame(frameData, offset, length, width, height, format)
            if (bitmap != null) {
                processCapturedBitmap(bitmap)
            }
        } catch (e: Exception) {
            Log.e(TAG, "[$sourceLabel] Frame listener error: ${e.message}")
        } finally {
            isProcessingFrameDirect = false
        }
    }

    private fun decodeFrame(frameData: ByteArray, offset: Int, length: Int, width: Int, height: Int, format: FrameFormat): Bitmap? {
        // NV21 is common for MSDK v5 FrameFormat.NV21
        val yuvImage = YuvImage(frameData, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), 90, out)
        val imageBytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }

    private fun processCapturedBitmap(bitmap: Bitmap) {
        frameCount++
        Log.d(TAG, "Captured frame: ${bitmap.width}x${bitmap.height}")
        val cloneForStreamer = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        streamerManager.onNewFrame(cloneForStreamer) 
        
        // Update on-phone preview every 10 frames
        if (frameCount % 10 == 0) {
            val previewBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
            runOnUiThread {
                imgCapturePreview.setImageBitmap(previewBitmap)
            }
        }
        
        if (streamerManager.currentMode == StreamerManager.Mode.VISION && !isDetecting) {
            isDetecting = true
            val detectionBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
            detectionExecutor.execute {
                try {
                    objectDetectorHelper.detect(detectionBitmap, 0)
                } finally {
                    isDetecting = false
                    detectionBitmap.recycle()
                }
            }
        }
        bitmap.recycle()
    }


    private val availableCameraUpdatedListener = object : ICameraStreamManager.AvailableCameraUpdatedListener {
        override fun onAvailableCameraUpdated(availableCameraList: MutableList<ComponentIndexType>) {
            updateFPVWidgetSource(availableCameraList)
        }

        override fun onCameraStreamEnableUpdate(cameraStreamEnableMap: MutableMap<ComponentIndexType, Boolean>) {
            // Unused
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Modern way to make it full screen (API 30+)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))

        setContentView(R.layout.activity_dashboard)

        primaryFpvWidget = findViewById(R.id.widget_primary_fpv)
        secondaryFpvWidget = findViewById(R.id.widget_secondary_fpv)
        fabSettings = findViewById(R.id.fab_settings)
        fabPlayStop = findViewById(R.id.fab_play_stop)
        imgCapturePreview = findViewById(R.id.img_capture_preview)

        MediaDataCenter.getInstance().cameraStreamManager.addAvailableCameraUpdatedListener(availableCameraUpdatedListener)
        
        objectDetectorHelper = ObjectDetectorHelper(
            context = applicationContext,
            objectDetectorListener = this,
            labelAllowlist = null // Use all initially, streamerManager handles filtering
        )

        // Initialize AI and WebRTC Streamer
        streamerManager = StreamerManager(applicationContext)

        streamerManager.onLabelAllowlistChanged = { newList ->
            objectDetectorHelper.labelAllowlist = newList
            objectDetectorHelper.clearObjectDetector() // Force re-init with new classes
            Log.d(TAG, "Detector allowlist updated via WebRTC: $newList")
        }
        
        // Setup FABs
        fabSettings.setOnClickListener {
            showSettingsDialog()
        }

        updateFabUi()
        fabPlayStop.setOnClickListener {
            isFabInStopState = !isFabInStopState
            updateFabUi()
            
            if (isFabInStopState) {
                // User pressed Play (FAB turns Red/Stop)
                Toast.makeText(this, "Streaming started to $targetIp!", Toast.LENGTH_LONG).show()
                streamerManager.start(targetIp, 1920, 1080)
            } else {
                // User pressed Stop (FAB turns Green/Play)
                streamerManager.stop()
            }
        }
    }

    private fun registerFrameListeners() {
        Log.d(TAG, "Registering frame listeners...")
        val manager = MediaDataCenter.getInstance().cameraStreamManager
        
        // Defensive: Always remove before adding to prevent "listener already added" errors
        manager.removeFrameListener(mainFrameListener)
        manager.removeFrameListener(fpvFrameListener)

        manager.addFrameListener(ComponentIndexType.LEFT_OR_MAIN, FrameFormat.NV21, mainFrameListener)
        manager.addFrameListener(ComponentIndexType.FPV, FrameFormat.NV21, fpvFrameListener)
        Log.d(TAG, "Frame listeners registered for MAIN and FPV")
    }

    private fun unregisterFrameListeners() {
        Log.d(TAG, "Unregistering frame listeners...")
        val manager = MediaDataCenter.getInstance().cameraStreamManager
        manager.removeFrameListener(mainFrameListener)
        manager.removeFrameListener(fpvFrameListener)
    }

    override fun onResume() {
        super.onResume()
        ViewUtil.setKeepScreen(this, true)
        registerFrameListeners()
    }

    override fun onPause() {
        super.onPause()
        ViewUtil.setKeepScreen(this, false)
        unregisterFrameListeners()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
    }

    override fun onLowMemory() {
        super.onLowMemory()
    }

    private fun updateFPVWidgetSource(availableCameraList: List<ComponentIndexType>?) {
        if (availableCameraList == null || availableCameraList.isEmpty()) {
            return
        }

        runOnUiThread {
            // Set primary source to the first available camera (usually Main)
            primaryFpvWidget.updateVideoSource(availableCameraList[0])
            
            // If there's a second camera, show it in the secondary widget
            if (availableCameraList.size > 1) {
                secondaryFpvWidget.updateVideoSource(availableCameraList[1])
                secondaryFpvWidget.visibility = android.view.View.VISIBLE
            } else {
                secondaryFpvWidget.visibility = android.view.View.GONE
            }
        }
    }

    private fun showSettingsDialog() {
        val dialog = SettingsDialogFragment.newInstance(
            objectDetectorHelper.threshold,
            objectDetectorHelper.maxResults,
            objectDetectorHelper.numThreads,
            objectDetectorHelper.currentDelegate,
            targetIp,
            targetClasses
        )
        dialog.setSettingsChangeListener(object : SettingsDialogFragment.SettingsChangeListener {
            override fun onSettingsChanged(
                threshold: Float,
                maxResults: Int,
                numThreads: Int,
                delegate: Int,
                newTargetIp: String,
                newTargetClasses: String
            ) {
                objectDetectorHelper.threshold = threshold
                objectDetectorHelper.maxResults = maxResults
                objectDetectorHelper.numThreads = numThreads
                objectDetectorHelper.currentDelegate = delegate
                targetIp = newTargetIp
                targetClasses = newTargetClasses
                
                // Parse classes and update detector
                val classList = if (targetClasses.isBlank()) null 
                                else targetClasses.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                objectDetectorHelper.labelAllowlist = classList
                
                // Update streamer with new IP if already started
                streamerManager.updateTargetIp(targetIp, 1280, 720)
                
                objectDetectorHelper.clearObjectDetector()
            }
        })
        dialog.show(supportFragmentManager, "settings")
    }

    private fun updateFabUi() {
        if (isFabInStopState) {
            fabPlayStop.setImageResource(R.drawable.ic_stop)
            fabPlayStop.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.RED)
        } else {
            fabPlayStop.setImageResource(R.drawable.ic_play)
            fabPlayStop.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.GREEN)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterFrameListeners()
        streamerManager.stop()
        detectionExecutor.shutdown()
        MediaDataCenter.getInstance().cameraStreamManager.removeAvailableCameraUpdatedListener(availableCameraUpdatedListener)
    }

    override fun onResults(
        results: List<ObjectDetection>,
        inferenceTime: Long,
        imageHeight: Int,
        imageWidth: Int
    ) {
        val rois = results.map { 
            StreamerManager.DetectionROI(
                Rect(it.boundingBox.left.toInt(), it.boundingBox.top.toInt(), it.boundingBox.right.toInt(), it.boundingBox.bottom.toInt()),
                it.category.label
            )
        }
        streamerManager.updateRois(rois)
    }

    override fun onError(error: String) {
        Log.e(TAG, "Detector Error: $error")
    }
}
