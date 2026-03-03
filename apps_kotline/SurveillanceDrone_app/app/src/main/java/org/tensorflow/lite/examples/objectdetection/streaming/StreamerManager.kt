package org.tensorflow.lite.examples.objectdetection.streaming

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import java.util.concurrent.Executors

class StreamerManager(private val context: android.content.Context) {
    private val TAG = "StreamerManager"
    private var webRtcStreamer: WebRtcStreamer? = null
    
    enum class Mode { LOW, HIGH, VISION, HYBRID }
    @Volatile var currentMode = Mode.VISION
    @Volatile var labelAllowlist: List<String>? = null
    var onLabelAllowlistChanged: ((List<String>?) -> Unit)? = null
    @Volatile var hybridRect = RectF(0.2f, 0.2f, 0.8f, 0.8f) // Default 20% to 80%

    private val MAX_TRACKED_OBJECTS = 3
    private val ROI_PADDING = 40 // Pixels of context around the subject for feathering
    
    // Dedicated executor for frame processing to avoid blocking UI or CameraX
    private var processingExecutor: java.util.concurrent.ExecutorService? = null
    @Volatile private var isProcessingFrame = false
    
    // FPS tracking
    private var frameCount = 0
    private var lastFpsLogTime = 0L

    private var currentTargetIp: String = ""
    @Volatile private var isStreaming = false

    private var currentRois: List<Rect> = emptyList()
    
    private val MAX_LIFE = 30 // Frames to survive without detection (~1 sec)
    
    // Multi-object stabilization
    private class StabilizedROI(var current: RectF, val label: String) {
        var life = 30 
        fun update(target: RectF, alpha: Float) {
            current = RectF(
                current.left * (1 - alpha) + target.left * alpha,
                current.top * (1 - alpha) + target.top * alpha,
                current.right * (1 - alpha) + target.right * alpha,
                current.bottom * (1 - alpha) + target.bottom * alpha
            )
            life = 30
        }
    }
    private val stabilizedRois = mutableListOf<StabilizedROI>()
    private val smoothingFactor = 0.15f // Lower = smoother motion
    private var lowResBitmap: Bitmap? = null
    private var highResBitmap: Bitmap? = null
    private var lowResCanvas: Canvas? = null
    private var highResCanvas: Canvas? = null
    private var paint = android.graphics.Paint().apply { isFilterBitmap = true }
    
    // Fast-clear engine variables
    private var forceClear = true
    private val dirtyRects = mutableListOf<Rect>()
    private val blackPaint = android.graphics.Paint().apply { 
        color = android.graphics.Color.BLACK
        style = android.graphics.Paint.Style.FILL
    }

    @Synchronized
    fun start(targetIp: String, width: Int, height: Int) {
        if (isStreaming) {
            if (targetIp != currentTargetIp) {
                updateTargetIp(targetIp, width, height)
            }
            return
        }

        currentTargetIp = targetIp
        // Use a single thread for processing to avoid race conditions on shared Canvases
        processingExecutor = Executors.newSingleThreadExecutor()
        
        // Initialize and start WebRTC
        webRtcStreamer = WebRtcStreamer(context)
        webRtcStreamer?.onCommandReceived = { handleCommand(it) }
        webRtcStreamer?.start()
        
        isStreaming = true
        lastFpsLogTime = System.currentTimeMillis()
        frameCount = 0
        Log.d(TAG, "Streaming started (WebRTC Signaling 8888)")
    }

    private fun handleCommand(json: String) {
        try {
            val obj = org.json.JSONObject(json)
            if (obj.has("mode")) {
                val modeStr = obj.getString("mode").uppercase()
                currentMode = Mode.valueOf(modeStr)
                Log.d(TAG, "Mode changed to $currentMode")
                forceClear = true
                
                // Clear high-res buffer on mode change to prevent "ghost" frames
                processingExecutor?.execute {
                    highResCanvas?.let { canvas ->
                        canvas.drawColor(android.graphics.Color.BLACK, android.graphics.PorterDuff.Mode.CLEAR)
                        canvas.drawColor(android.graphics.Color.BLACK)
                        highResBitmap?.let { bmp ->
                            webRtcStreamer?.pushSourceFrame(bmp)
                            // Push twice to flush double-buffered encoders
                            webRtcStreamer?.pushSourceFrame(bmp)
                        }
                    }
                }
            }
            if (obj.has("classes")) {
                val classesStr = obj.getString("classes")
                labelAllowlist = if (classesStr.isBlank()) null 
                                 else if (classesStr == "all") null
                                 else if (classesStr == "none") listOf("_none_")
                                 else classesStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                Log.d(TAG, "Classes updated to $labelAllowlist")
                onLabelAllowlistChanged?.invoke(labelAllowlist)
            }
            if (obj.has("hybrid_rect")) {
                val rect = obj.getJSONObject("hybrid_rect")
                hybridRect = RectF(
                    rect.getDouble("x1").toFloat() / 100f,
                    rect.getDouble("y1").toFloat() / 100f,
                    rect.getDouble("x2").toFloat() / 100f,
                    rect.getDouble("y2").toFloat() / 100f
                )
                Log.d(TAG, "Hybrid Rect updated to $hybridRect")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Command parse error: ${e.message}")
        }
    }

    fun updateTargetIp(targetIp: String, width: Int, height: Int) {
        if (targetIp == currentTargetIp) return
        currentTargetIp = targetIp
        Log.d(TAG, "Updating Target IP context to $targetIp")
    }

    /**
     * Called at 30 FPS from CameraFragment. 
     * The passed bitmap is a CLONE and must be recycled here.
     */
    fun onNewFrame(clonedBitmap: Bitmap) {
        if (!isStreaming) {
            clonedBitmap.recycle()
            return
        }

        if (isProcessingFrame) {
            // Drop frame if still processing to prevent OOM queue buildup
            clonedBitmap.recycle()
            return
        }
        isProcessingFrame = true
        
        val currentTime = System.currentTimeMillis()
        frameCount++
        if (currentTime - lastFpsLogTime >= 5000) {
            val fps = frameCount / 5.0
            Log.d(TAG, "Source Processing FPS: $fps (Mode: $currentMode)")
            frameCount = 0
            lastFpsLogTime = currentTime
        }

        try {
            processingExecutor?.execute {
                try {
                    val srcW = clonedBitmap.width
                    val srcH = clonedBitmap.height
                    
                    // Initialize reusable bitmaps if needed
                    if (lowResBitmap == null || lowResBitmap?.width != 240) {
                        lowResBitmap = Bitmap.createBitmap(240, 136, Bitmap.Config.ARGB_8888)
                        lowResCanvas = Canvas(lowResBitmap!!)
                        
                        highResBitmap = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
                        highResCanvas = Canvas(highResBitmap!!)
                    }

                    // 1. Send low-res frame (Now 30 FPS)
                    lowResCanvas?.drawBitmap(clonedBitmap, Rect(0, 0, srcW, srcH), Rect(0, 0, 240, 136), paint)
                    Log.v(TAG, "Pushing context frame")
                    webRtcStreamer?.pushContextFrame(lowResBitmap!!)

                    // 2. High-res stream processing
                    if (forceClear || currentMode == Mode.HIGH || currentMode == Mode.LOW) {
                        // Wipe entire memory 
                        highResCanvas?.drawColor(android.graphics.Color.BLACK)
                        forceClear = false
                        dirtyRects.clear()
                    } else if (dirtyRects.isNotEmpty()) {
                        // ULTRA-FAST CLEAR: Only overwrite the specific pixels modified in the previous frame
                        // Eliminates 1920x1080 array iteration on the JVM
                        for (rect in dirtyRects) {
                            highResCanvas?.drawRect(rect, blackPaint)
                        }
                        dirtyRects.clear()
                    }

                    when (currentMode) {
                        Mode.LOW -> {} 
                        Mode.HIGH -> {
                            // Full high-res frame
                            highResCanvas?.drawBitmap(clonedBitmap, Rect(0, 0, srcW, srcH), Rect(0, 0, 1920, 1080), paint)
                        }
                        Mode.VISION -> {
                            val scaleX = 1920.0 / srcW
                            val scaleY = 1080.0 / srcH

                            synchronized(stabilizedRois) {
                                for (stab in stabilizedRois) {
                                    val rSrc = Rect()
                                    stab.current.round(rSrc)
                                    
                                    val roiDest = Rect(
                                        (rSrc.left * scaleX).toInt(),
                                        (rSrc.top * scaleY).toInt(),
                                        (rSrc.right * scaleX).toInt(),
                                        (rSrc.bottom * scaleY).toInt()
                                    )

                                    val paddedSrc = Rect(rSrc).apply {
                                        inset(-ROI_PADDING, -ROI_PADDING)
                                        left = Math.max(0, left)
                                        top = Math.max(0, top)
                                        right = Math.min(srcW, right)
                                        bottom = Math.min(srcH, bottom)
                                    }
                                    
                                    val paddedDest = Rect(roiDest).apply {
                                        val padX = (ROI_PADDING * scaleX).toInt()
                                        val padY = (ROI_PADDING * scaleY).toInt()
                                        inset(-padX, -padY)
                                        left = Math.max(0, left)
                                        top = Math.max(0, top)
                                        right = Math.min(1920, right)
                                        bottom = Math.min(1080, bottom)
                                    }
                                    highResCanvas?.drawBitmap(clonedBitmap, paddedSrc, paddedDest, paint)
                                    dirtyRects.add(paddedDest)
                                }
                            }
                        }
                        Mode.HYBRID -> {
                            val cropSrc = Rect(
                                (hybridRect.left * srcW).toInt(),
                                (hybridRect.top * srcH).toInt(),
                                (hybridRect.right * srcW).toInt(),
                                (hybridRect.bottom * srcH).toInt()
                            )
                            val cropDest = Rect(
                                (hybridRect.left * 1920).toInt(),
                                (hybridRect.top * 1080).toInt(),
                                (hybridRect.right * 1920).toInt(),
                                (hybridRect.bottom * 1080).toInt()
                            )
                            highResCanvas?.drawBitmap(clonedBitmap, cropSrc, cropDest, paint)
                            dirtyRects.add(cropDest)
                        }
                    }
                    
                    // 3. Push source track
                    if (currentMode != Mode.LOW || frameCount % 30 == 0) {
                        webRtcStreamer?.pushSourceFrame(highResBitmap!!)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "onNewFrame processing error: ${e.message}")
                } finally {
                    // CRITICAL: Recycle the clone after background processing is finished
                    clonedBitmap.recycle()
                    isProcessingFrame = false
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Frame rejected during shutdown")
            clonedBitmap.recycle()
            isProcessingFrame = false
        }
    }

    private fun scaleRoi(roi: Rect, cropW: Int, cropH: Int, startX: Int, startY: Int): Rect {
        val r = Rect(roi)
        r.offset(-startX, -startY)
        if (!r.intersect(0, 0, cropW, cropH)) {
            return Rect(0,0,0,0)
        }
        val scaleX = 1024.0 / cropW
        val scaleY = 768.0 / cropH
        return Rect(
            (r.left * scaleX).toInt(),
            (r.top * scaleY).toInt(),
            (r.right * scaleX).toInt(),
            (r.bottom * scaleY).toInt()
        )
    }

    private fun unScaleRoi(roi: Rect, cropW: Int, cropH: Int): Rect {
        val scaleX = cropW.toDouble() / 1024.0
        val scaleY = cropH.toDouble() / 768.0
        return Rect(
            (roi.left * scaleX).toInt(),
            (roi.top * scaleY).toInt(),
            (roi.right * scaleX).toInt(),
            (roi.bottom * scaleY).toInt()
        )
    }

    data class DetectionROI(val rect: Rect, val label: String)

    fun updateRois(rois: List<DetectionROI>) {
        synchronized(stabilizedRois) {
            val newDetections = rois.toMutableList()
            
            // 1. Match new detections to existing tracked ROIs
            for (stab in stabilizedRois) {
                var bestMatchIdx = -1
                var maxIou = 0.15f // Lower threshold if label matches exactly
                
                for (i in newDetections.indices) {
                    // Stronger matching: prioritize label similarity
                    if (stab.label == newDetections[i].label) {
                        val iou = calculateIou(stab.current, RectF(newDetections[i].rect))
                        if (iou > maxIou) {
                            maxIou = iou
                            bestMatchIdx = i
                        }
                    }
                }
                
                if (bestMatchIdx != -1) {
                    stab.update(RectF(newDetections[bestMatchIdx].rect), smoothingFactor)
                    newDetections.removeAt(bestMatchIdx)
                } else {
                    stab.life--
                }
            }
            
            // 2. Start tracking new objects if we have space
            for (newRoi in newDetections) {
                if (stabilizedRois.size < MAX_TRACKED_OBJECTS) {
                    stabilizedRois.add(StabilizedROI(RectF(newRoi.rect), newRoi.label))
                }
            }
            
            // 3. Remove dead objects
            stabilizedRois.removeAll { it.life <= 0 }
        }
    }

    private fun calculateIou(rect1: RectF, rect2: RectF): Float {
        val intersection = RectF()
        if (!intersection.setIntersect(rect1, rect2)) return 0f
        
        val intersectionArea = intersection.width() * intersection.height()
        val unionArea = (rect1.width() * rect1.height() + 
                        rect2.width() * rect2.height() - 
                        intersectionArea)
        
        return if (unionArea > 0) intersectionArea / unionArea else 0f
    }

    @Synchronized
    fun stop() {
        if (!isStreaming) return
        isStreaming = false
        webRtcStreamer?.stop()
        webRtcStreamer = null
        processingExecutor?.shutdownNow()
        processingExecutor = null
        
        synchronized(stabilizedRois) {
            stabilizedRois.clear()
        }
        
        lowResBitmap?.recycle()
        highResBitmap?.recycle()
        lowResBitmap = null
        highResBitmap = null
    }
}
