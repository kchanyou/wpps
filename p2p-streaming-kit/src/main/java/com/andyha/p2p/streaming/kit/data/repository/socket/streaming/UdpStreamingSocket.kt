package com.andyha.p2p.streaming.kit.data.repository.socket.streaming

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.wifi.WifiManager
import android.text.format.Formatter
import com.andyha.coreutils.FileUtils.bitmapToByteArray
import com.andyha.p2p.streaming.kit.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.IOException
import java.net.*

class UdpStreamingSocket(private val context: Context) : StreamingSocket {

    // ## 화질 설정 (이 값을 바꿔가며 테스트하세요) ##
    // 1 (최저화질) ~ 100 (최고화질)
    // 추천 시작 값: 30
    private val COMPRESSION_QUALITY = 1

    // UDP 패킷의 최대 크기. (65507이 이론상 최대값)
    // 너무 크면 단편화로 유실될 수 있으므로 60KB로 제한합니다.
    private val MAX_UDP_PACKET_SIZE = 60_000

    override suspend fun sendBitmap(ipAddress: String, bitmap: Bitmap) = withContext(Dispatchers.IO) {
        try {
            // 1. 비트맵을 지정된 품질로 압축합니다.
            val data: ByteArray = bitmapToByteArray(bitmap, COMPRESSION_QUALITY)

            // 2. 압축된 데이터가 UDP 최대 크기를 초과하는지 확인합니다.
            if (data.size > MAX_UDP_PACKET_SIZE) {
                Timber.w("Image size too large (${data.size} bytes), skipping frame. Try lowering quality.")
                return@withContext
            }

            // 3. 단일 UDP 패킷으로 전송합니다.
            DatagramSocket().use { socket ->
                val packet = DatagramPacket(
                    data,
                    data.size,
                    InetAddress.getByName(ipAddress),
                    Constants.STREAMING_PORT
                )
                socket.send(packet)
                Timber.d("Bitmap sent (${data.size} bytes, quality=$COMPRESSION_QUALITY)")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to send bitmap")
        }
    }

    override suspend fun receiveStreaming(): Flow<Bitmap> = flow {
        // DatagramSocket은 한 번만 생성하여 재사용합니다.
        val socket = DatagramSocket(Constants.STREAMING_PORT).apply {
            // 버퍼 크기를 넉넉하게 설정합니다.
            receiveBufferSize = MAX_UDP_PACKET_SIZE + 1024
            // 타임아웃을 설정하여 무한 대기를 방지할 수 있습니다. (0은 무한대기)
            soTimeout = 0
        }

        // 수신용 버퍼
        val buffer = ByteArray(MAX_UDP_PACKET_SIZE)

        Timber.d("UDP Receiver started on port ${Constants.STREAMING_PORT}")

        try {
            while (true) {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet) // 데이터 수신 대기

                // [중요 버그 수정] dp.data.size가 아닌 실제 수신한 데이터 길이(dp.length)를 사용해야 합니다.
                val receivedBitmap = BitmapFactory.decodeByteArray(packet.data, 0, packet.length)

                if (receivedBitmap != null) {
                    emit(receivedBitmap)
                    Timber.d("Bitmap received (${packet.length} bytes)")
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