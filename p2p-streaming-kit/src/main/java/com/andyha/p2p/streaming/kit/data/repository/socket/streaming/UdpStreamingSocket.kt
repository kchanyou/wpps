package com.andyha.p2p.streaming.kit.data.repository.socket.streaming

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.wifi.WifiManager
import com.andyha.coreutils.FileUtils.bitmapToByteArray
import com.andyha.p2p.streaming.kit.util.Constants
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.min

class UdpStreamingSocket(context: Context) : StreamingSocket {

    private val wm by lazy { context.getSystemService(Context.WIFI_SERVICE) as WifiManager }

    private var receivedBitmapCount = 0
    private var sentBitmapCount = 0
    private var nextFrameId = 1

    // 수신 소켓은 재사용
    @Volatile private var receiveSocket: DatagramSocket? = null
    private val lock = Any()

    // 네트워크 전용 고우선 스레드풀
    private val networkExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable).apply {
            name = "NetworkThread"
            priority = Thread.MAX_PRIORITY
            isDaemon = true
        }
    }

    // ========== 기존 sendBitmap 제거 ==========

    // 새로운 메서드: 이미 인코딩된 데이터만 청킹 전송
    suspend fun sendEncodedData(ipAddress: String, encodedData: ByteArray) {
        if (ipAddress.isBlank() || ipAddress == "0.0.0.0") {
            Timber.e("Skip send: invalid subscriber IP '$ipAddress'")
            return
        }

        withContext(networkExecutor.asCoroutineDispatcher()) {
            val frameId = nextFrameId++
            val totalChunks = ceil(encodedData.size / CHUNK_SIZE.toDouble()).toInt()
            try {
                DatagramSocket().use { socket ->
                    socket.setSendBufferSize(128000)
                    socket.setTrafficClass(0x04)
                    socket.reuseAddress = true

                    val addr = InetAddress.getByName(ipAddress)

                    var offset = 0
                    for (seq in 0 until totalChunks) {
                        val remain = encodedData.size - offset
                        val take = min(remain, CHUNK_SIZE)
                        val header = buildHeader(frameId, totalChunks, seq)
                        val packetBytes = ByteArray(header.size + take)
                        System.arraycopy(header, 0, packetBytes, 0, header.size)
                        System.arraycopy(encodedData, offset, packetBytes, header.size, take)
                        offset += take

                        val packet = DatagramPacket(packetBytes, packetBytes.size, addr, Constants.STREAMING_PORT)
                        socket.send(packet)
                    }
                    sentBitmapCount++
                    if (sentBitmapCount % 10 == 0) {
                        Timber.d("Encoded(frameId=$frameId) sent in $totalChunks chunks, total=${encodedData.size}B, count=$sentBitmapCount")
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "sendEncodedData failed (frameId=$frameId, bytes=${encodedData.size})")
            }
        }
    }

    // --------- RECEIVE (reassembly, 기존과 동일) ---------
    override suspend fun receiveStreaming(): Flow<Bitmap> = flow {
        val socket = synchronized(lock) {
            receiveSocket ?: DatagramSocket(null).apply {
                reuseAddress = true
                soTimeout = 0
                receiveBufferSize = 256000
                setTrafficClass(0x04)
                bind(InetSocketAddress(Constants.STREAMING_PORT))
                receiveSocket = this
                runCatching {
                    @Suppress("DEPRECATION")
                    val ip = android.text.format.Formatter.formatIpAddress(wm.connectionInfo.ipAddress)
                    Timber.d("UDP receive bound 0.0.0.0:${Constants.STREAMING_PORT} (device ip hint=$ip)")
                }.onFailure {
                    Timber.d("UDP receive bound 0.0.0.0:${Constants.STREAMING_PORT}")
                }
            }
        }

        val buf = ByteArray(MAX_UDP_PACKET)
        val frames = ReassemblyTable()

        val bitmapOptions = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
            inMutable = false
            inSampleSize = 1
        }

        try {
            while (true) {
                val dp = DatagramPacket(buf, buf.size)
                socket.receive(dp)

                if (dp.length < HEADER_SIZE) {
                    Timber.w("Drop packet: too small (${dp.length})")
                    continue
                }

                val bb = ByteBuffer.wrap(dp.data, 0, dp.length)
                val magic = bb.int
                if (magic != MAGIC) {
                    Timber.w("Drop packet: bad magic=0x${magic.toString(16)} len=${dp.length}")
                    continue
                }

                val frameId = bb.int
                val totalChunks = bb.short.toInt() and 0xFFFF
                val seq = bb.short.toInt() and 0xFFFF
                val payloadLen = dp.length - HEADER_SIZE

                if (totalChunks <= 0 || seq >= totalChunks) {
                    Timber.w("Drop packet: invalid header frameId=$frameId total=$totalChunks seq=$seq")
                    continue
                }

                frames.putChunk(frameId, totalChunks, seq, dp.data, HEADER_SIZE, payloadLen)

                val ready = frames.tryAssemble(frameId)
                if (ready != null) {
                    val bmp = BitmapFactory.decodeByteArray(ready, 0, ready.size, bitmapOptions)
                    if (bmp != null) {
                        receivedBitmapCount++
                        if (receivedBitmapCount % 10 == 0) {
                            Timber.d("Bitmap received (frameId=$frameId, bytes=${ready.size}) count=$receivedBitmapCount from ${dp.address?.hostAddress}:${dp.port}")
                        }
                        emit(bmp)
                    } else {
                        Timber.w("BitmapFactory returned null (frameId=$frameId, bytes=${ready.size})")
                    }
                }

                frames.cleanupStale()
            }
        } catch (e: SocketTimeoutException) {
            Timber.w(e, "UDP receive timeout")
        } catch (e: Exception) {
            Timber.e(e, "UDP receive failed")
        } finally {
            synchronized(lock) {
                runCatching { socket.close() }
                if (receiveSocket === socket) receiveSocket = null
                Timber.d("UDP receive socket closed")
            }
            networkExecutor.shutdown()
        }
    }

    override suspend fun sendBitmap(ipAddress: String, bitmap: Bitmap) {
        // 안전성 체크
        if (bitmap.isRecycled) {
            Timber.w("Skip sending: bitmap already recycled")
            return
        }

        var shouldRecycle = false
        try {
            val data = bitmapToByteArray(bitmap, 1)
            shouldRecycle = true // 성공했으면 recycle 예약
            sendEncodedData(ipAddress, data)
        } catch (e: IllegalArgumentException) {
            if (e.message?.contains("recycled") == true) {
                Timber.w("Bitmap was recycled during processing")
            } else {
                Timber.e(e, "Failed to convert bitmap")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to send bitmap")
            shouldRecycle = true // 다른 예외여도 정리는 해야함
        } finally {
            // 안전하게 recycle
            if (shouldRecycle && !bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }

    // --------- helpers ---------
    private fun buildHeader(frameId: Int, totalChunks: Int, seq: Int): ByteArray {
        val bb = ByteBuffer.allocate(HEADER_SIZE)
        bb.putInt(MAGIC)
        bb.putInt(frameId)
        bb.putShort(totalChunks.toShort())
        bb.putShort(seq.toShort())
        return bb.array()
    }

    private class ReassemblyTable {
        private data class Entry(
            val total: Int,
            val chunks: Array<ByteArray?>,
            var received: Int,
            val createdAt: Long = System.currentTimeMillis()
        )

        private val map = ConcurrentHashMap<Int, Entry>()

        fun putChunk(frameId: Int, total: Int, seq: Int, data: ByteArray, offset: Int, length: Int) {
            val e = map.getOrPut(frameId) { Entry(total, arrayOfNulls(total), 0) }
            if (e.total != total) {
                map[frameId] = Entry(total, arrayOfNulls(total), 0)
            }
            if (e.chunks[seq] == null) {
                val copy = ByteArray(length)
                System.arraycopy(data, offset, copy, 0, length)
                e.chunks[seq] = copy
                e.received++
            }
        }

        fun tryAssemble(frameId: Int): ByteArray? {
            val e = map[frameId] ?: return null
            if (e.received != e.total) return null
            val totalSize = e.chunks.sumOf { it?.size ?: 0 }
            val out = ByteArray(totalSize)
            var pos = 0
            for (i in 0 until e.total) {
                val part = e.chunks[i]!!
                System.arraycopy(part, 0, out, pos, part.size)
                pos += part.size
            }
            map.remove(frameId)
            return out
        }

        fun cleanupStale(now: Long = System.currentTimeMillis()) {
            val it = map.entries.iterator()
            while (it.hasNext()) {
                val en = it.next()
                if (now - en.value.createdAt > STALE_MS) it.remove()
            }
        }
    }

    companion object {
        // UDP datagram (65507)보다 여유 두고
        private const val CHUNK_SIZE = 60_000
        private const val MAX_UDP_PACKET = CHUNK_SIZE + 64
        private const val HEADER_SIZE = 12
        private const val MAGIC = 0x50325053 // 'P2PS'
        private const val STALE_MS = 2_000L

        // NetworkManager에서 호출될 수 있도록 정적 메서드 추가
        private var instance: UdpStreamingSocket? = null

        fun initialize(context: Context) {
            instance = UdpStreamingSocket(context)
        }

        suspend fun sendData(ipAddress: String, data: ByteArray) {
            instance?.sendEncodedData(ipAddress, data)
        }
    }
}
