package org.tensorflow.lite.examples.objectdetection.streaming

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import io.getstream.webrtc.android.ktx.createSessionDescription
import io.getstream.webrtc.android.ktx.suspendSdpObserver
import kotlinx.coroutines.*
import org.webrtc.*
import java.nio.ByteBuffer

class WebRtcStreamer(private val context: Context) {
    private val TAG = "WebRtcStreamer"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var rootEglBase: EglBase? = null
    private var contextVideoSource: VideoSource? = null
    private var sourceVideoSource: VideoSource? = null
    private var contextTrack: VideoTrack? = null
    private var sourceTrack: VideoTrack? = null
    private var dataChannel: DataChannel? = null

    var onCommandReceived: ((String) -> Unit)? = null

    private val signalingServer = WebRtcSignalingServer(8888) { offerSdp ->
        handleOfferSync(offerSdp)
    }

    init {
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)
    }

    fun start() {
        rootEglBase = EglBase.create()
        val eglContext = rootEglBase?.eglBaseContext

        val encoderFactory = DefaultVideoEncoderFactory(eglContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglContext)

        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()

        contextVideoSource = factory?.createVideoSource(false)
        sourceVideoSource = factory?.createVideoSource(false)

        contextTrack = factory?.createVideoTrack("context_track", contextVideoSource)
        sourceTrack = factory?.createVideoTrack("source_track", sourceVideoSource)

        signalingServer.start()
        Log.d(TAG, "WebRtcStreamer and Signaling started (2 tracks)")
    }

    private fun handleOfferSync(offerSdp: String): String? = runBlocking {
        handleOffer(offerSdp)
    }

    private suspend fun handleOffer(offerSdp: String): String? {
        Log.d(TAG, "Handling new SDP offer")
        
        // Dispose old connection if exists
        peerConnection?.dispose()
        
        val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        
        val observer = object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate?) {
                Log.d(TAG, "New ICE candidate: ${candidate?.sdp}")
            }
            override fun onDataChannel(dc: DataChannel?) {
                Log.d(TAG, "DataChannel received from remote")
                dataChannel = dc
                dc?.registerObserver(object : DataChannel.Observer {
                    override fun onBufferedAmountChange(p0: Long) {}
                    override fun onStateChange() {
                        Log.d(TAG, "DataChannel State: ${dc.state()}")
                    }
                    override fun onMessage(buffer: DataChannel.Buffer) {
                        val data = buffer.data
                        val bytes = ByteArray(data.remaining())
                        data.get(bytes)
                        val message = String(bytes)
                        Log.d(TAG, "DataChannel Message: $message")
                        onCommandReceived?.invoke(message)
                    }
                })
            }
            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                Log.d(TAG, "ICE Connection state: $newState")
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {}
            override fun onSignalingChange(newState: PeerConnection.SignalingState?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
        }

        val pc = factory?.createPeerConnection(rtcConfig, observer) ?: return null
        peerConnection = pc

        // Add 2 tracks
        contextTrack?.let { pc.addTrack(it, listOf("stream1")) }
        sourceTrack?.let { pc.addTrack(it, listOf("stream1")) }

        val offer = SessionDescription(SessionDescription.Type.OFFER, offerSdp)
        Log.d(TAG, "Setting remote description (Offer Length: ${offerSdp.length})")
        
        // Use getstream ktx extensions
        val setRemoteResult = suspendSdpObserver { pc.setRemoteDescription(it, offer) }
        if (setRemoteResult.isFailure) {
            Log.e(TAG, "Remote SDP set failure: ${setRemoteResult.exceptionOrNull()?.message}")
            return null
        }

        Log.d(TAG, "Creating SDP answer")
        val createAnswerResult = createSessionDescription { pc.createAnswer(it, MediaConstraints()) }
        if (createAnswerResult.isFailure) {
            Log.e(TAG, "Answer creation failure: ${createAnswerResult.exceptionOrNull()?.message}")
            return null
        }
        
        val answer = createAnswerResult.getOrNull() ?: run {
            Log.e(TAG, "CreateAnswer returned null SessionDescription")
            return null
        }
        
        Log.d(TAG, "Original Answer SDP Length: ${answer.description?.length ?: 0}")
        
        // Munge SDP to force higher bitrate (e.g., 8Mbps)
        val mungedDescription = mungeSdpBitrate(answer.description ?: "")
        if (mungedDescription.isEmpty()) {
            Log.e(TAG, "Munged description is empty!")
        }
        
        val mungedAnswer = SessionDescription(answer.type, mungedDescription)
        Log.d(TAG, "Setting local description (Munged Answer Length: ${mungedDescription.length})")

        val setLocalResult = suspendSdpObserver { pc.setLocalDescription(it, mungedAnswer) }
        if (setLocalResult.isFailure) {
            Log.e(TAG, "Local SDP set failure: ${setLocalResult.exceptionOrNull()?.message}")
            return null
        }

        // Apply quality parameters after local description is set
        setHighQualityParameters(pc)

        Log.d(TAG, "Munged Answer SDP sent to client")
        return mungedAnswer.description
    }

    private fun mungeSdpBitrate(sdp: String): String {
        return try {
            val lines = sdp.split("\n")
            val newSdp = StringBuilder()
            for (line in lines) {
                val trimmedLine = line.trim()
                if (trimmedLine.isEmpty()) continue
                newSdp.append(trimmedLine).append("\r\n")
                if (trimmedLine.startsWith("m=video")) {
                    newSdp.append("b=AS:8000\r\n")
                }
            }
            newSdp.toString()
        } catch (e: Exception) {
            Log.e(TAG, "SDP munge error: ${e.message}")
            sdp
        }
    }

    private fun setHighQualityParameters(pc: PeerConnection) {
        for (sender in pc.senders) {
            val track = sender.track() as? VideoTrack ?: continue
            val parameters = sender.parameters
            if (parameters.encodings.isNotEmpty()) {
                for (encoding in parameters.encodings) {
                    encoding.minBitrateBps = 1000000
                    encoding.maxBitrateBps = 8000000
                    encoding.maxFramerate = 30
                }
                parameters.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
                sender.parameters = parameters
                Log.d(TAG, "Applied high quality parameters to track ${track.id()}")
            }
        }
    }


    private class TrackBuffers(val width: Int, val height: Int) {
        val size = width * height
        val chromaSize = size / 4

        // Double buffer to avoid corruption while encoder is reading
        private class Buffers(width: Int, height: Int) {
            val y: ByteBuffer = ByteBuffer.allocateDirect(width * height)
            val u: ByteBuffer = ByteBuffer.allocateDirect((width * height) / 4)
            val v: ByteBuffer = ByteBuffer.allocateDirect((width * height) / 4)
            val rgba: ByteBuffer = ByteBuffer.allocateDirect(width * height * 4)
        }
        private val b1 = Buffers(width, height)
        private val b2 = Buffers(width, height)
        private var useB1 = true

        fun fillFrom(bitmap: Bitmap): VideoFrame.I420Buffer? {
            if (bitmap.width != width || bitmap.height != height) return null
            
            val buf = if (useB1) b1 else b2
            useB1 = !useB1

            // 1. Instantly copy pixels to direct off-heap buffer
            buf.rgba.clear()
            bitmap.copyPixelsToBuffer(buf.rgba)
            buf.rgba.rewind()

            buf.y.clear()
            buf.u.clear()
            buf.v.clear()

            // 2. Ultra-fast native C++ YUV conversion
            org.webrtc.YuvHelper.ABGRToI420(
                buf.rgba, width * 4,
                buf.y, width,
                buf.u, width / 2,
                buf.v, width / 2,
                width, height
            )

            // Adjust positions to 0 for the encoder
            buf.y.position(0)
            buf.u.position(0)
            buf.v.position(0)
            buf.y.limit(size)
            buf.u.limit(chromaSize)
            buf.v.limit(chromaSize)
            
            return JavaI420Buffer.wrap(width, height, buf.y, width, buf.u, width / 2, buf.v, width / 2, null)
        }
    }

    private var contextBuffers: TrackBuffers? = null
    private var sourceBuffers: TrackBuffers? = null

    fun pushContextFrame(bitmap: Bitmap) {
        if (contextBuffers == null || contextBuffers?.width != bitmap.width || contextBuffers?.height != bitmap.height) {
            contextBuffers = TrackBuffers(bitmap.width, bitmap.height)
        }
        pushFrame(contextVideoSource, bitmap, contextBuffers!!)
    }

    fun pushSourceFrame(bitmap: Bitmap) {
        if (sourceBuffers == null || sourceBuffers?.width != bitmap.width || sourceBuffers?.height != bitmap.height) {
            sourceBuffers = TrackBuffers(bitmap.width, bitmap.height)
        }
        pushFrame(sourceVideoSource, bitmap, sourceBuffers!!)
    }

    private fun pushFrame(source: VideoSource?, bitmap: Bitmap, buffers: TrackBuffers) {
        if (source == null) return
        val timestampNs = System.nanoTime()
        val capturerObserver = source.capturerObserver
        
        val i420Buffer = buffers.fillFrom(bitmap) ?: return
        val videoFrame = VideoFrame(i420Buffer, 0, timestampNs)
        capturerObserver.onFrameCaptured(videoFrame)
        videoFrame.release()
    }

    fun stop() {
        signalingServer.stop()
        peerConnection?.dispose()
        factory?.dispose()
        rootEglBase?.release()
        rootEglBase = null
        scope.cancel()
        contextBuffers = null
        sourceBuffers = null
    }

    open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(p0: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(p0: String?) { Log.e("WebRtcStreamer", "SDP Create Failure: $p0") }
        override fun onSetFailure(p0: String?) { Log.e("WebRtcStreamer", "SDP Set Failure: $p0") }
    }
}
