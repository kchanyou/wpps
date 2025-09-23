package com.andyha.p2p.streaming.kit.data.repository.socket.streaming

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.andyha.coreutils.FileUtils.bitmapToByteArray
import com.andyha.p2p.streaming.kit.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.net.*

class UdpStreamingSocket(private val context: Context) : StreamingSocket {

    private val MAX_UDP_PACKET_SIZE = 50_000
    private var frameCounter = 0
    private val FRAME_SKIP = 1

    override suspend fun sendBitmap(ipAddress: String, bitmap: Bitmap) =
        withContext(Dispatchers.IO) {
            frameCounter++
            if (frameCounter % FRAME_SKIP != 0) {
                return@withContext
            }

            try {
                val originalResolution = "${bitmap.width}x${bitmap.height}"

                // 기존 함수 사용하되 관대한 품질 시도
                val qualities = when {
                    bitmap.width >= 1920 -> listOf(12, 10, 8, 6, 4, 2)  // 더 많은 옵션
                    bitmap.width >= 1280 -> listOf(30, 25, 20, 15, 10, 8)
                    else -> listOf(50, 40, 30, 25, 20, 15)
                }

                var sent = false

                for (quality in qualities) {
                    try {
                        val data = bitmapToByteArray(bitmap, quality)  // 원래 함수 사용
                        Timber.d("🔥 Quality $quality: ${data.size} bytes")

                        if (data.size <= MAX_UDP_PACKET_SIZE) {  // 최소 크기 제한 제거
                            sendPacket(ipAddress, data, originalResolution, quality)
                            Timber.d("✅ Successfully sent: ${data.size} bytes, quality=$quality")
                            sent = true
                            break
                        }
                    } catch (e: Exception) {
                        Timber.w(e, "Quality $quality failed, trying next...")
                        continue  // 실패해도 다음 품질 시도
                    }
                }

                if (!sent) {
                    // 모든 품질 실패 시 해상도 줄이기
                    Timber.w("All qualities failed, scaling down resolution")
                    try {
                        val scaledBitmap = Bitmap.createScaledBitmap(
                            bitmap,
                            bitmap.width / 2,  // 50%로 대폭 축소
                            bitmap.height / 2,
                            false
                        )

                        val scaledData = bitmapToByteArray(scaledBitmap, 20)  // 원래 함수 사용
                        val scaledResolution = "${scaledBitmap.width}x${scaledBitmap.height}"

                        sendPacket(ipAddress, scaledData, scaledResolution, 20)
                        scaledBitmap.recycle()
                        Timber.d("✅ Sent scaled: ${scaledData.size} bytes, resolution=$scaledResolution")
                    } catch (e: Exception) {
                        Timber.e(e, "Even scaled version failed")
                    }
                }

            } catch (e: Exception) {
                Timber.e(e, "Failed to send bitmap")
            }
        }

    private suspend fun sendPacket(
        ipAddress: String,
        data: ByteArray,
        resolution: String,
        quality: Int
    ) {
        try {
            DatagramSocket().use { socket ->
                val packet = DatagramPacket(
                    data,
                    data.size,
                    InetAddress.getByName(ipAddress),
                    Constants.STREAMING_PORT
                )
                socket.send(packet)
                Timber.d("📤 Packet sent: ${data.size} bytes, quality=$quality, resolution=$resolution")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to send packet")
        }
    }

    override suspend fun receiveStreaming(): Flow<Bitmap> = flow {
        val socket = DatagramSocket(Constants.STREAMING_PORT).apply {
            receiveBufferSize = MAX_UDP_PACKET_SIZE + 1024
            soTimeout = 0
        }

        val buffer = ByteArray(MAX_UDP_PACKET_SIZE)
        Timber.d("🔥 UDP Receiver started on port ${Constants.STREAMING_PORT}, buffer size: ${buffer.size}")

        try {
            while (true) {
                val packet = DatagramPacket(buffer, buffer.size)
                Timber.d("🔥 Waiting for packet...")

                socket.receive(packet)
                Timber.d("🔥 Packet received: ${packet.length} bytes from ${packet.address?.hostAddress}:${packet.port}")

                try {
                    val receivedBitmap =
                        BitmapFactory.decodeByteArray(packet.data, 0, packet.length)

                    if (receivedBitmap != null) {
                        val resolution = "${receivedBitmap.width}x${receivedBitmap.height}"
                        Timber.d("✅ Client received: ${packet.length} bytes → $resolution")
                        emit(receivedBitmap)
                    } else {
                        Timber.w("❌ Client decode failed: ${packet.length} bytes")
                        if (packet.length >= 10) {
                            val header = packet.data.sliceArray(0..9)
                            Timber.d("🔍 Header: ${header.joinToString(" ") { "%02x".format(it) }}")
                        }
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Client decode error: ${packet.length} bytes")
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Client receive error")
        } finally {
            socket.close()
            Timber.d("🔥 Client UDP Receiver stopped")
        }
    }.flowOn(Dispatchers.IO)
}

