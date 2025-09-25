package com.andyha.p2p.streaming.kit.data.repository.socket.streaming

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.wifi.WifiManager
import com.andyha.coreutils.FileUtils.bitmapToByteArray
import com.andyha.p2p.streaming.kit.util.Constants
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import timber.log.Timber
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
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

    override suspend fun sendBitmap(ipAddress: String, bitmap: Bitmap) {
        if (ipAddress.isBlank() || ipAddress == "0.0.0.0") {
            Timber.e("Skip send: invalid subscriber IP '$ipAddress'")
            return
        }

        val payload: ByteArray = bitmapToByteArray(bitmap, 1)
        if (payload.isEmpty()) return

        val frameId = nextFrameId++
        val totalChunks = ceil(payload.size / CHUNK_SIZE.toDouble()).toInt()

        try {
            DatagramSocket().use { socket ->
                // 네트워크 최적화 추가
                socket.setSendBufferSize(128000) // 128KB 송신 버퍼
                socket.setTrafficClass(0x04) // 최저 지연 우선순위
                socket.reuseAddress = true

                val addr = InetAddress.getByName(ipAddress)

                var offset = 0
                for (seq in 0 until totalChunks) {
                    val remain = payload.size - offset
                    val take = min(remain, CHUNK_SIZE)
                    val header = buildHeader(frameId, totalChunks, seq)
                    val packetBytes = ByteArray(header.size + take)
                    System.arraycopy(header, 0, packetBytes, 0, header.size)
                    System.arraycopy(payload, offset, packetBytes, header.size, take)
                    offset += take

                    val packet = DatagramPacket(packetBytes, packetBytes.size, addr, Constants.STREAMING_PORT)
                    socket.send(packet)
                }

                sentBitmapCount++
                if (sentBitmapCount % 10 == 0) {
                    Timber.d("Bitmap(frameId=$frameId) sent in $totalChunks chunks, total=${payload.size}B, count=$sentBitmapCount")
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "sendBitmap failed (frameId=$frameId, bytes=${payload.size})")
        }
    }

    // --------- RECEIVE (reassembly) ---------
    override suspend fun receiveStreaming(): Flow<Bitmap> = flow {
        // receiveStreaming()에서 소켓 설정 부분
        val socket = synchronized(lock) {
            receiveSocket ?: DatagramSocket(null).apply {
                reuseAddress = true
                soTimeout = 0
                receiveBufferSize = 256000 // 256KB 수신 버퍼
                setTrafficClass(0x04) // 최저 지연 우선순위
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

        try {
            while (true) {
                val dp = DatagramPacket(buf, buf.size)
                socket.receive(dp)

                // 최소 헤더 길이 검사
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
                    // 디코딩 최적화 옵션 추가
                    val options = BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.RGB_565 // 메모리 50% 절약
                        inMutable = false
                        inSampleSize = 1
                    }

                    val bmp = BitmapFactory.decodeByteArray(ready, 0, ready.size, options)
                    if (bmp != null) {
                        receivedBitmapCount++
                        Timber.d("Bitmap received (frameId=$frameId, bytes=${ready.size}) count=$receivedBitmapCount from ${dp.address?.hostAddress}:${dp.port}")
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
                // 프레임 총 조각 수가 다르면 새로 시작
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
            // 모두 도착 → 합치기
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
        // UDP datagram 실한계(65507)보다 여유 두고 60KB로 분할
        private const val CHUNK_SIZE = 60_000
        // 수신 버퍼는 여유있게 (조각 + 헤더)
        private const val MAX_UDP_PACKET = CHUNK_SIZE + 64

        // 헤더: MAGIC(4) + frameId(4) + total(2) + seq(2) = 12 bytes
        private const val HEADER_SIZE = 12
        private const val MAGIC = 0x50325053 // 'P2PS'
        // 미완성 프레임 정리 타임아웃
        private const val STALE_MS = 2_000L
    }
}
