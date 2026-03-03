/*
 * Copyright 2022 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *             http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.tensorflow.lite.examples.objectdetection.fragments

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.Toast
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.Navigation
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.tensorflow.lite.examples.objectdetection.ObjectDetectorHelper
import org.tensorflow.lite.examples.objectdetection.R
import org.tensorflow.lite.examples.objectdetection.databinding.FragmentCameraBinding
import org.tensorflow.lite.examples.objectdetection.detectors.ObjectDetection
import org.tensorflow.lite.examples.objectdetection.streaming.StreamerManager
import android.graphics.Rect
import android.graphics.RectF
import java.util.LinkedList
import java.net.Inet4Address
import java.net.NetworkInterface

class CameraFragment : Fragment(), ObjectDetectorHelper.DetectorListener {

    private val TAG = "ObjectDetection"

    private var _fragmentCameraBinding: FragmentCameraBinding? = null

    private val fragmentCameraBinding
        get() = _fragmentCameraBinding!!

    private lateinit var objectDetectorHelper: ObjectDetectorHelper
    private lateinit var bitmapBuffer: Bitmap
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var isFabInStopState = false
    private var targetIp = "10.35.218.28" // Default PC IP
    private var targetClasses = "bottle, person" // Default detection filter
    private lateinit var streamerManager: StreamerManager

    /** Blocking camera operations are performed using this executor */
    private lateinit var cameraExecutor: ExecutorService

    override fun onResume() {
        super.onResume()
        // Make sure that all permissions are still present, since the
        // user could have removed them while the app was in paused state.
        if (!PermissionsFragment.hasPermissions(requireContext())) {
            Navigation.findNavController(requireActivity(), R.id.fragment_container)
                .navigate(CameraFragmentDirections.actionCameraToPermissions())
        }
    }

    override fun onDestroyView() {
        _fragmentCameraBinding = null
        streamerManager.stop()
        super.onDestroyView()

        // Shut down our background executor
        cameraExecutor.shutdown()
    }

    override fun onCreateView(
      inflater: LayoutInflater,
      container: ViewGroup?,
      savedInstanceState: Bundle?
    ): View {
        _fragmentCameraBinding = FragmentCameraBinding.inflate(inflater, container, false)

        return fragmentCameraBinding.root
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        objectDetectorHelper = ObjectDetectorHelper(
            context = requireContext(),
            objectDetectorListener = this,
            labelAllowlist = targetClasses.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        )
        streamerManager = StreamerManager(requireContext().applicationContext)

        // Initialize our background executor
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Wait for the views to be properly laid out
        fragmentCameraBinding.viewFinder.post {
            // Set up the camera and its use cases
            setUpCamera()
        }

        // Set up the Settings button
        fragmentCameraBinding.fabSettings.setOnClickListener {
            showSettingsDialog()
        }

        updateFabUi()
        fragmentCameraBinding.fabPlayStop.setOnClickListener {
            isFabInStopState = !isFabInStopState
            updateFabUi()
            
            if (isFabInStopState) {
                // User pressed Play (FAB turns Red/Stop)
                val deviceIp = getLocalIpAddress()
                Toast.makeText(requireContext(), "Streaming started! Device IP: $deviceIp", Toast.LENGTH_LONG).show()
                Log.d(TAG, "Streaming started. IP: $deviceIp")
                streamerManager.start(targetIp, 1920, 1080)
            } else {
                // User pressed Stop (FAB turns Green/Play)
                streamerManager.stop()
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
        dialog.show(childFragmentManager, "settings")
    }

    private fun updateFabUi() {
        if (isFabInStopState) {
            fragmentCameraBinding.fabPlayStop.setImageResource(R.drawable.ic_stop)
            fragmentCameraBinding.fabPlayStop.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.RED)
        } else {
            fragmentCameraBinding.fabPlayStop.setImageResource(R.drawable.ic_play)
            fragmentCameraBinding.fabPlayStop.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.GREEN)
        }
    }

    // Initialize CameraX, and prepare to bind the camera use cases
    private fun setUpCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener(
            {
                // CameraProvider
                cameraProvider = cameraProviderFuture.get()

                // Build and bind the camera use cases
                bindCameraUseCases()
            },
            ContextCompat.getMainExecutor(requireContext())
        )
    }

    // Declare and bind preview, capture and analysis use cases
    @SuppressLint("UnsafeOptInUsageError")
    private fun bindCameraUseCases() {

        // CameraProvider
        val cameraProvider =
            cameraProvider ?: throw IllegalStateException("Camera initialization failed.")

        // CameraSelector - makes assumption that we're only using the back camera
        val cameraSelector =
            CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK).build()

        // Preview. Using 1080p for native quality
        preview =
            Preview.Builder()
                .setTargetResolution(android.util.Size(1920, 1080))
                .setTargetRotation(fragmentCameraBinding.viewFinder.display.rotation)
                .build()

        // ImageAnalysis. Using 1080p to match
        imageAnalyzer =
            ImageAnalysis.Builder()
                .setTargetResolution(android.util.Size(1920, 1080))
                .setTargetRotation(fragmentCameraBinding.viewFinder.display.rotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(OUTPUT_IMAGE_FORMAT_RGBA_8888)
                //.setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
                // The analyzer can then be assigned to the instance
                .also {
                    it.setAnalyzer(cameraExecutor) { image ->
                        if (!::bitmapBuffer.isInitialized) {
                            Log.d(TAG, "Analysis resolution: ${image.width}x${image.height}")
                            // The image rotation and RGB image buffer are initialized only once
                            // the analyzer has started running
                            bitmapBuffer = Bitmap.createBitmap(
                                image.width,
                                image.height,
                                Bitmap.Config.ARGB_8888
                            )
                        }

                        detectObjects(image)
                    }
                }

        // Must unbind the use-cases before rebinding them
        cameraProvider.unbindAll()

        try {
            // A variable number of use-cases can be passed here -
            // camera provides access to CameraControl & CameraInfo
            camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)

            // Attach the viewfinder's surface provider to preview use case
            preview?.setSurfaceProvider(fragmentCameraBinding.viewFinder.surfaceProvider)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    private val detectionExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var isDetecting = false

    private fun detectObjects(image: ImageProxy) {
        // Copy out RGB bits to the shared bitmap buffer
        image.use {
            bitmapBuffer.copyPixelsFromBuffer(image.planes[0].buffer)
        }

        val imageRotation = image.imageInfo.rotationDegrees
        
        // Pass to streamer for processing (only if streaming is active)
        if (isFabInStopState) {
            // Streaming should happen every frame (30 FPS)
            // We pass a copy to avoid race conditions with the next frame's copyPixels
            val streamerBitmap = bitmapBuffer.copy(bitmapBuffer.config ?: Bitmap.Config.ARGB_8888, false)
            streamerManager.onNewFrame(streamerBitmap)
            
            // Sync dynamic labels from StreamerManager (received via WebRTC DataChannel)
            if (objectDetectorHelper.labelAllowlist != streamerManager.labelAllowlist) {
                objectDetectorHelper.labelAllowlist = streamerManager.labelAllowlist
                objectDetectorHelper.clearObjectDetector() // Reset to apply new labels
            }

            // Only run detection if we are in VISION mode AND not already busy
            if (streamerManager.currentMode == StreamerManager.Mode.VISION && !isDetecting) {
                isDetecting = true
                val detectionBitmap = bitmapBuffer.copy(bitmapBuffer.config ?: Bitmap.Config.ARGB_8888, false)
                detectionExecutor.execute {
                    try {
                        // Pass Bitmap and rotation to the object detector helper for processing and detection
                        objectDetectorHelper.detect(detectionBitmap, imageRotation)
                    } finally {
                        isDetecting = false
                        detectionBitmap.recycle()
                    }
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        imageAnalyzer?.targetRotation = fragmentCameraBinding.viewFinder.display.rotation
    }

    // Update UI after objects have been detected. Extracts original image height/width
    // to scale and place bounding boxes properly through OverlayView
    override fun onResults(
        results: List<ObjectDetection>,
        inferenceTime: Long,
        imageHeight: Int,
        imageWidth: Int
    ) {
        activity?.runOnUiThread {
            // 1. Update ROIs for streamer (all detections)
            val rois = results.map { 
                Rect(it.boundingBox.left.toInt(), it.boundingBox.top.toInt(), it.boundingBox.right.toInt(), it.boundingBox.bottom.toInt())
            }
            streamerManager.updateRois(rois)

            // 2. Pass necessary information to OverlayView for drawing on the canvas
            fragmentCameraBinding.overlay.setResults(
                results ?: LinkedList<ObjectDetection>(),
                imageHeight,
                imageWidth
            )

            // Force a redraw
            fragmentCameraBinding.overlay.invalidate()
        }
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: "Unknown"
                    }
                }
            }
        } catch (ex: Exception) {}
        return "Unknown"
    }

    override fun onError(error: String) {
        activity?.runOnUiThread {
            Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()
        }
    }
}
