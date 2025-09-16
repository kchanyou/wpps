package com.andyha.p2p.streaming.kit.data.repository.socket.streaming

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.andyha.coreutils.FileUtils.bitmapToByteArray
import com.andyha.p2p.streaming.kit.util.Constants
import com.andyha.p2p.streaming.kit.util.Constants.STREAMING_PORT
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import timber.log.Timber
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.*

class TcpStreamingSocket : StreamingSocket {

    private var sentBitmapCount = 0
    private var senderSocket: Socket? = null
    private var dataOutputStream: DataOutputStream? = null

    private var serverSocket: ServerSocket? = null
    private var receiverSocket: Socket? = null
    private var dataInputStream: DataInputStream? = null
    private var bitmapAvgReceivedLength = 0
    private var receivedBitmapCount = 0

    override suspend fun sendBitmap(ipAddress: String, bitmap: Bitmap) {
        val data: ByteArray = bitmapToByteArray(bitmap, 1)
        Timber.d("bitmapToByteArray: $data")
        if (data.isEmpty()) return

        try {
            val target = ipAddress.trim()
            Timber.d("ipAddress: $target")

            // 목적지 IP 가드(0.0.0.0/루프백/멀티캐스트/와일드카드 차단)
            val inet = runCatching { InetAddress.getByName(target) }.getOrNull()
            if (inet == null || !inet.isRoutableV4()) {
                Timber.w("Invalid/unroutable target IP: $target – skip sending")
                // 잘못된 주소로 무한 재시도 방지
                closeQuietly(dataOutputStream, senderSocket)
                dataOutputStream = null
                senderSocket = null
                return
            }

            if (senderSocket == null || senderSocket?.isClosed == true || senderSocket?.isConnected != true) {
                senderSocket = Socket().apply {
                    reuseAddress = true
                    tcpNoDelay = true
                    soTimeout = 30_000
                    connect(InetSocketAddress(inet, STREAMING_PORT), /*connectTimeout*/ 10_000)
                }
                dataOutputStream = DataOutputStream(senderSocket!!.getOutputStream())
            }

            // [프레임 길이][프레임 바이트] 순으로 송신
            dataOutputStream!!.writeInt(data.size)
            Timber.d("Sent length: ${data.size}")
            dataOutputStream!!.write(data)
            dataOutputStream!!.flush()
            sentBitmapCount++
            Timber.d("Bitmap sent: $sentBitmapCount")
        } catch (e: SocketException) {
            Timber.e(e, "sendBitmap SocketException – will reset socket")
            closeQuietly(dataOutputStream, senderSocket)
            dataOutputStream = null
            senderSocket = null
        } catch (e: UnknownHostException) {
            Timber.e(e, "sendBitmap UnknownHostException")
        } catch (e: IOException) {
            Timber.e(e, "sendBitmap IOException")
            closeQuietly(dataOutputStream, senderSocket)
            dataOutputStream = null
            senderSocket = null
        }
    }

    override suspend fun receiveStreaming(): Flow<Bitmap> = flow {
        try {
            // 섀도잉 버그 수정: 클래스 필드에 대입해야 함
            if (serverSocket == null) {
                serverSocket = ServerSocket(Constants.STREAMING_PORT).apply {
                    reuseAddress = true
                    soTimeout = 0 // 수신 측은 차단 모드가 자연스러움
                }
            }
            if (receiverSocket == null || receiverSocket?.isClosed == true) {
                Timber.d("Waiting for sender to connect on port ${Constants.STREAMING_PORT}")
                receiverSocket = serverSocket!!.accept().apply {
                    reuseAddress = true
                    tcpNoDelay = true
                    soTimeout = 0
                }
                dataInputStream = DataInputStream(receiverSocket!!.getInputStream())
                Timber.d("Sender connected from ${receiverSocket!!.inetAddress.hostAddress}")
            }

            val din = dataInputStream!!

            // available() 폴링 없이 프레이밍 블로킹 읽기
            while (true) {
                val length = runCatching { din.readInt() }.getOrElse {
                    throw IOException("Failed to read frame length", it)
                }
                if (length <= 0) continue

                Timber.d("Length received: $length (avg=$bitmapAvgReceivedLength)")
                if (bitmapAvgReceivedLength > 0) {
                    val ratio = length.toDouble() / bitmapAvgReceivedLength
                    Timber.d("avg ratio: $ratio")
                    if (ratio > UNUSUAL_BIG_BITMAP_FACTOR) continue
                }

                val data = ByteArray(length)
                din.safeReadFully(data, 0, length)

                val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
                if (bitmap != null) {
                    bitmapAvgReceivedLength =
                        (bitmapAvgReceivedLength * receivedBitmapCount + length) / (receivedBitmapCount + 1)
                    receivedBitmapCount++
                    Timber.d("Bitmap received: $receivedBitmapCount")
                    emit(bitmap)
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "receiveStreaming error – will tear down sockets")
            closeQuietly(dataInputStream, receiverSocket, serverSocket)
            dataInputStream = null
            receiverSocket = null
            serverSocket = null
            throw e
        }
    }

    @Throws(IOException::class)
    fun InputStream.safeReadFully(b: ByteArray, off: Int, len: Int): Int {
        var n = 0
        while (n < len) {
            val count: Int = read(b, off + n, len - n)
            if (count < 0) throw IOException("Stream closed while reading ($n/$len)")
            n += count
        }
        return n
    }

    private fun InetAddress.isRoutableV4(): Boolean {
        return (this is Inet4Address)
                && !isAnyLocalAddress // 0.0.0.0
                && !isLoopbackAddress // 127.0.0.1
                && !isMulticastAddress
                && hostAddress != "255.255.255.255"
    }

    private fun closeQuietly(vararg closeables: Any?) {
        closeables.forEach {
            try {
                when (it) {
                    is Closeable -> it.close()
                    is Socket -> it.close()
                    is ServerSocket -> it.close()
                }
            } catch (_: Exception) {}
        }
    }

    companion object {
        const val UNUSUAL_BIG_BITMAP_FACTOR = 5
    }
}
