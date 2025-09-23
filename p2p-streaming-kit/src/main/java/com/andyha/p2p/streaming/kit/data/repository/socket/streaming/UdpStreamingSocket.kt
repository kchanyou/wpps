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

    // [추가] FPS 계산을 위한 변수들
    private var lastSendTimestamp: Long = 0L
    private var sentFrameCount: Int = 0
    private var lastReceiveTimestamp: Long = 0L
    private var receivedFrameCount: Int = 0

    private val COMPRESSION_QUALITY = 1
    private val MAX_UDP_PACKET_SIZE = 60_000

    override suspend fun sendBitmap(ipAddress: String, bitmap: Bitmap) = withContext(Dispatchers.IO) {
        try {
            // [추가] 해상도 로그를 위해 비트맵 크기 확인
            val resolution = "${bitmap.width}x${bitmap.height}"

            val data: ByteArray = bitmapToByteArray(bitmap, COMPRESSION_QUALITY)

            if (data.size > MAX_UDP_PACKET_SIZE) {
                //Timber.w("Image size too large (${data.size} bytes), skipping frame. Try lowering quality.")
                return@withContext
            }

            DatagramSocket().use { socket ->
                val packet = DatagramPacket(
                    data,
                    data.size,
                    InetAddress.getByName(ipAddress),
                    Constants.STREAMING_PORT
                )
                socket.send(packet)

                // [수정] 기존 로그에 해상도 정보 추가
                Timber.d("Bitmap sent (${data.size} bytes, quality=$COMPRESSION_QUALITY, resolution=$resolution)")

                // [추가] 1초마다 보내는 FPS(Frames Per Second)를 로그로 출력
                sentFrameCount++
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastSendTimestamp >= 1000) {
                    //Timber.d("🚀 Sending FPS: $sentFrameCount")
                    sentFrameCount = 0
                    lastSendTimestamp = currentTime
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to send bitmap")
        }
    }

    override suspend fun receiveStreaming(): Flow<Bitmap> = flow {
        val socket = DatagramSocket(Constants.STREAMING_PORT).apply {
            receiveBufferSize = MAX_UDP_PACKET_SIZE + 1024
            soTimeout = 0
        }

        val buffer = ByteArray(MAX_UDP_PACKET_SIZE)
        Timber.d("UDP Receiver started on port ${Constants.STREAMING_PORT}")

        try {
            while (true) {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)

                val receivedBitmap = BitmapFactory.decodeByteArray(packet.data, 0, packet.length)

                if (receivedBitmap != null) {
                    // [추가] 해상도 로그를 위해 비트맵 크기 확인
                    val resolution = "${receivedBitmap.width}x${receivedBitmap.height}"

                    // [수정] 기존 로그에 해상도 정보 추가
                    Timber.d("Bitmap received (${packet.length} bytes, resolution=$resolution)")

                    // [추가] 1초마다 받는 FPS(Frames Per Second)를 로그로 출력
                    receivedFrameCount++
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastReceiveTimestamp >= 1000) {
                        //Timber.d("✅ Receiving FPS: $receivedFrameCount")
                        receivedFrameCount = 0
                        lastReceiveTimestamp = currentTime
                    }

                    emit(receivedBitmap)
                } else {
                    Timber.w("Failed to decode received bitmap (${packet.length} bytes)")
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error in receive loop")
        } finally {
            socket.close()
            Timber.d("UDP Receiver stopped.")
        }
    }.flowOn(Dispatchers.IO)
}