package org.tensorflow.lite.examples.objectdetection.streaming

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.Executors

class H264TcpStreamer(private val port: Int = 5012) {
    private val TAG = "H264TcpStreamer"
    private var mediaCodec: MediaCodec? = null
    private var serverSocket: ServerSocket? = null
    private val clients = mutableListOf<Socket>()
    private var csdBuffer: ByteArray? = null // Store SPS/PPS
    
    private var width = 1280
    private var height = 720
    private var isRunning = false
    private var inputSurface: Surface? = null

    private var executor: java.util.concurrent.ExecutorService? = null

    fun start(w: Int, h: Int) {
        if (isRunning) stop()
        
        // Final resolution will be width*2 x height (Side-by-Side RGB + Mask)
        val streamWidth = 2048
        val streamHeight = 768
        
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, streamWidth, streamHeight)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            format.setInteger(MediaFormat.KEY_BIT_RATE, 3500000) // Increased for double width at higher quality
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = mediaCodec?.createInputSurface()
            mediaCodec?.start()
            
            isRunning = true
            executor = Executors.newSingleThreadExecutor()

            // TCP Server Thread
            executor?.execute {
                try {
                    serverSocket = ServerSocket(port)
                    Log.d(TAG, "H264 TCP Server started on port $port")
                    while (isRunning) {
                        val client = serverSocket?.accept() ?: break
                        Log.d(TAG, "New high-res client: ${client.inetAddress}")
                        
                        // Send CSD headers immediately if we have them
                        csdBuffer?.let { headers ->
                            try {
                                val os = client.getOutputStream()
                                os.write(headers)
                                os.flush()
                                Log.d(TAG, "Sent CSD headers to new client")
                            } catch (e: Exception) {}
                        }
                        
                        synchronized(clients) { clients.add(client) }
                    }
                } catch (e: Exception) {
                    if (isRunning) Log.e(TAG, "Server error: ${e.message}")
                }
            }
            
            // MediaCodec Output Thread
            Thread {
                val bufferInfo = MediaCodec.BufferInfo()
                while (isRunning) {
                    try {
                        val codec = mediaCodec ?: break
                        val index = codec.dequeueOutputBuffer(bufferInfo, 10000)
                        if (index >= 0) {
                            val outputBuffer = codec.getOutputBuffer(index)
                            if (outputBuffer != null) {
                                sendToClients(outputBuffer, bufferInfo)
                            }
                            codec.releaseOutputBuffer(index, false)
                        }
                    } catch (e: IllegalStateException) {
                        // This usually happens when the codec is stopped while dequeuing
                        break
                    } catch (e: Exception) {
                        if (isRunning) Log.e(TAG, "Output thread error: ${e.message}")
                        break
                    }
                }
            }.start()

        } catch (e: Exception) {
            Log.e(TAG, "H264 Tcp Streamer failed: ${e.message}")
        }
    }

    private fun sendToClients(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        val bytes = ByteArray(info.size)
        buffer.get(bytes)
        
        // Capture CSD-0 (SPS/PPS)
        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
            Log.d(TAG, "Captured CSD buffer (${info.size} bytes)")
            csdBuffer = bytes
        }
        
        synchronized(clients) {
            val iterator = clients.iterator()
            while (iterator.hasNext()) {
                val client = iterator.next()
                try {
                    val os = client.getOutputStream()
                    os.write(bytes)
                    os.flush()
                } catch (e: Exception) {
                    iterator.remove()
                    try { client.close() } catch (ex: Exception) {}
                }
            }
        }
    }

    fun pushFrame(rgbBitmap: Bitmap, maskBitmap: Bitmap) {
        if (!isRunning || inputSurface == null) return
        
        try {
            val canvas = inputSurface?.lockCanvas(null) ?: return
            try {
                canvas.drawColor(Color.BLACK)
                // RGB on left half
                canvas.drawBitmap(rgbBitmap, 0f, 0f, null)
                // Alpha mask on right half
                canvas.drawBitmap(maskBitmap, 1024f, 0f, null)
            } finally {
                inputSurface?.unlockCanvasAndPost(canvas)
            }
        } catch (e: Exception) {
            Log.e(TAG, "pushFrame error: ${e.message}")
        }
    }

    fun stop() {
        isRunning = false
        
        executor?.shutdownNow()
        executor = null
        
        try {
            serverSocket?.close()
        } catch (e: Exception) {}
        serverSocket = null
        
        try {
            mediaCodec?.stop()
        } catch (e: Exception) {}
        
        try {
            mediaCodec?.release()
        } catch (e: Exception) {}
        mediaCodec = null
        
        try {
            inputSurface?.release()
        } catch (e: Exception) {}
        inputSurface = null
        
        synchronized(clients) {
            clients.forEach { try { it.close() } catch (e: Exception) {} }
            clients.clear()
        }
    }
}
