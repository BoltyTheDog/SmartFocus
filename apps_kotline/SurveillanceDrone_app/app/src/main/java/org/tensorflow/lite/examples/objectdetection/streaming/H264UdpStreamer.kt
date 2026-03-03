package org.tensorflow.lite.examples.objectdetection.streaming

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.Executors

class H264UdpStreamer(private val port: Int = 5012) {
    private val TAG = "H264UdpStreamer"
    private var mediaCodec: MediaCodec? = null
    private var udpSocket: DatagramSocket? = null
    private var targetAddress: InetAddress? = null
    
    private var isRunning = false
    private var inputSurface: Surface? = null
    private var executor: java.util.concurrent.ExecutorService? = null
    
    private val MTU = 1400 // Safe MTU for UDP
    private var frameIndex = 0

    fun start(targetIp: String, w: Int, h: Int) {
        if (isRunning) stop()
        
        try {
            targetAddress = InetAddress.getByName(targetIp)
            udpSocket = DatagramSocket()
            
            // Final resolution will be width*2 x height (Side-by-Side RGB + Mask)
            val streamWidth = 2048
            val streamHeight = 768
            
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, streamWidth, streamHeight)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            format.setInteger(MediaFormat.KEY_BIT_RATE, 4000000) 
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = mediaCodec?.createInputSurface()
            mediaCodec?.start()
            
            isRunning = true
            executor = Executors.newSingleThreadExecutor()

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
                                sendUdpPackets(outputBuffer, bufferInfo)
                            }
                            codec.releaseOutputBuffer(index, false)
                        }
                    } catch (e: Exception) {
                        if (isRunning) Log.e(TAG, "Output thread error: ${e.message}")
                        break
                    }
                }
            }.start()

            Log.d(TAG, "H264 UDP Streamer started targeting $targetIp:$port")
        } catch (e: Exception) {
            Log.e(TAG, "H264 UDP Streamer failed: ${e.message}")
        }
    }

    private fun sendUdpPackets(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        val size = info.size
        var data = ByteArray(size)
        buffer.get(data)
        
        // 1. Ensure Annex-B Start Code (00 00 00 01)
        // MediaCodec sometimes provides raw NALs or already has start codes. 
        // We ensure a 4-byte start code is present for the decoder.
        val hasStartCode = size >= 4 && data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 0.toByte() && data[3] == 1.toByte()
        
        if (!hasStartCode) {
            val newData = ByteArray(size + 4)
            newData[0] = 0; newData[1] = 0; newData[2] = 0; newData[3] = 1
            System.arraycopy(data, 0, newData, 4, size)
            data = newData
        }

        val totalSize = data.size
        val totalPackets = (totalSize + MTU - 1) / MTU
        
        frameIndex++
        
        // Log setup for critical packets
        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
            Log.d(TAG, "Sending Codec Config (SPS/PPS) - ${data.size} bytes")
        }

        for (i in 0 until totalPackets) {
            val offset = i * MTU
            val len = Math.min(MTU, totalSize - offset)
            
            val packetData = ByteArray(len + 12)
            // Magic: UDPH
            packetData[0] = 0x55; packetData[1] = 0x44; packetData[2] = 0x50; packetData[3] = 0x48
            
            // Sequence (4 bytes)
            packetData[4] = (frameIndex shr 24).toByte()
            packetData[5] = (frameIndex shr 16).toByte()
            packetData[6] = (frameIndex shr 8).toByte()
            packetData[7] = frameIndex.toByte()
            
            // Indices
            packetData[8] = i.toByte()
            packetData[9] = totalPackets.toByte()
            
            // Payload Size (2 bytes)
            packetData[10] = (len shr 8).toByte()
            packetData[11] = len.toByte()
            
            // Payload
            System.arraycopy(data, offset, packetData, 12, len)
            
            try {
                val packet = DatagramPacket(packetData, packetData.size, targetAddress, port)
                udpSocket?.send(packet)
            } catch (e: Exception) {
                // Ignore transient send errors
            }
        }
    }

    fun pushFrame(rgbBitmap: Bitmap, maskBitmap: Bitmap) {
        if (!isRunning || inputSurface == null) return
        
        try {
            val canvas = inputSurface?.lockCanvas(null) ?: return
            try {
                canvas.drawColor(Color.BLACK)
                canvas.drawBitmap(rgbBitmap, 0f, 0f, null)
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
        
        try { mediaCodec?.stop() } catch (e: Exception) {}
        try { mediaCodec?.release() } catch (e: Exception) {}
        mediaCodec = null
        
        try { inputSurface?.release() } catch (e: Exception) {}
        inputSurface = null
        
        try { udpSocket?.close() } catch (e: Exception) {}
        udpSocket = null
    }
}
