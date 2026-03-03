package org.tensorflow.lite.examples.objectdetection.streaming

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

class WebRtcSignalingServer(private val port: Int = 8888, private val onSdpReceived: (String) -> String?) {
    private val TAG = "SignalingServer"
    private var isRunning = false
    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newSingleThreadExecutor()

    fun start() {
        if (isRunning) return
        isRunning = true
        executor.execute {
            try {
                serverSocket = ServerSocket(port)
                Log.d(TAG, "Signaling server waiting on port $port")
                while (isRunning) {
                    val client = serverSocket?.accept() ?: break
                    handleClient(client)
                }
            } catch (e: Exception) {
                if (isRunning) Log.e(TAG, "Server error: ${e.message}")
            }
        }
    }

    private fun handleClient(socket: Socket) {
        Thread {
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val writer = PrintWriter(socket.getOutputStream(), true)

                // Simple HTTP-like POST handling for SDP
                var line: String? = reader.readLine()
                var contentLength = 0
                while (line != null && line != "") {
                    if (line.lowercase().startsWith("content-length:")) {
                        contentLength = line.substring(15).trim().toInt()
                    }
                    line = reader.readLine()
                }

                if (contentLength > 0) {
                    val buffer = CharArray(contentLength)
                    var totalRead = 0
                    while (totalRead < contentLength) {
                        val read = reader.read(buffer, totalRead, contentLength - totalRead)
                        if (read == -1) break
                        totalRead += read
                    }
                    val sdpOffer = String(buffer)
                    
                    Log.d(TAG, "Received SDP Offer")
                    val sdpAnswer = onSdpReceived(sdpOffer)
                    
                    if (sdpAnswer != null) {
                        writer.println("HTTP/1.1 200 OK")
                        writer.println("Content-Type: application/json")
                        writer.println("Content-Length: ${sdpAnswer.length}")
                        writer.println("Access-Control-Allow-Origin: *")
                        writer.println("")
                        writer.print(sdpAnswer)
                        writer.flush()
                        Log.d(TAG, "Sent SDP Answer")
                    } else {
                        writer.println("HTTP/1.1 500 Internal Server Error")
                        writer.println("")
                        writer.flush()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Client error: ${e.message}")
            } finally {
                try { socket.close() } catch (e: Exception) {}
            }
        }.start()
    }

    fun stop() {
        isRunning = false
        try { serverSocket?.close() } catch (e: Exception) {}
        serverSocket = null
        executor.shutdownNow()
    }
}
