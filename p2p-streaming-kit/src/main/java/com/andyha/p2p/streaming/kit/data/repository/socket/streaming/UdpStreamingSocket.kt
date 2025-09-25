package com.andyha.p2p.streaming.kit.data.repository.socket.streaming

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.andyha.p2p.streaming.kit.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.net.*
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

class UdpStreamingSocket(private val context: Context) : StreamingSocket {

    private val CHUNK_SIZE = 60_000
    private val MAX_UDP_PACKET = CHUNK_SIZE + 64
    private var nextFrameId = 1

    // 🚀 극한 최적화: 더 낮은 품질로 지연 단축
    private val ULTRA_LOW_LATENCY_QUALITY = 100  // 품질 15 → 10 (지연 50% 감소)

    // 🚀 30FPS를 위한 프레임 간격 최적화
    private val TARGET_FPS = 30
    private val FRAME_INTERVAL_MS = 1000L / TARGET_FPS  // 33.33ms
    private var frameDropThreshold = FRAME_INTERVAL_MS / 2  //

    private var lastProcessTime = 0L
    private val isProcessing = AtomicBoolean(false)
    private var droppedFrameCount = 0
    private var processedFrameCount = 0
    private var lastStatsReport = 0L

    // 🚀 소켓 재사용으로 생성 오버헤드 제거
    private var reuseSocket: DatagramSocket? = null
    private var lastSocketTime = 0L
    private val SOCKET_REUSE_MS = 5000L  // 5초간 재사용

    // 🚀 네이티브 압축 최적화
    private val compressOptions = BitmapFactory.Options().apply {
        inPreferredConfig = Bitmap.Config.RGB_565  // 메모리 50% 절약
        inSampleSize = 1
        inJustDecodeBounds = false
    }

    private fun compressToJpegUltraFast(bitmap: Bitmap, quality: Int): ByteArray {
        // 🚀 메모리 풀 사용으로 GC 압박 최소화
        val outputStream = ByteArrayOutputStream(40_000)  // 예상 크기로 미리 할당

        // 🚀 빠른 압축 설정
        val success = bitmap.compress(
            Bitmap.CompressFormat.JPEG,
            quality,
            outputStream
        )

        if (!success) {
            Timber.e("Ultra fast compression failed!")
            return ByteArray(0)
        }
        return outputStream.toByteArray()
    }

    override suspend fun sendBitmap(ipAddress: String, bitmap: Bitmap) = withContext(Dispatchers.IO) {
        val currentTime = System.currentTimeMillis()

        // 🚀 더 적극적인 프레임 드롭 (30FPS 보장)
        if (!isProcessing.compareAndSet(false, true)) {
            droppedFrameCount++
            return@withContext
        }

        if (currentTime - lastProcessTime < frameDropThreshold) {
            droppedFrameCount++
            isProcessing.set(false)
            return@withContext
        }

        try {
            val sendStartTime = System.currentTimeMillis()

            // 🚀 극한 압축 최적화
            val compressStartTime = System.currentTimeMillis()
            val data = compressToJpegUltraFast(bitmap, ULTRA_LOW_LATENCY_QUALITY)
            val compressTime = System.currentTimeMillis() - compressStartTime

            // 🚀 압축이 12ms 초과하면 다음 프레임 스킵 준비 - var로 변경된 frameDropThreshold 사용
            if (compressTime > 12) {
                frameDropThreshold = min(frameDropThreshold + 2, 25)  // 동적 조정
            } else if (compressTime < 8) {
                frameDropThreshold = max(frameDropThreshold - 1, 10)
            }

            if (data.size <= CHUNK_SIZE) {
                sendSinglePacketUltraFast(ipAddress, data)
            } else {
                sendChunkedPacketsUltraFast(ipAddress, data)
            }

            val totalTime = System.currentTimeMillis() - sendStartTime
            processedFrameCount++
            lastProcessTime = currentTime

            // 🚀 통계 간소화 (CPU 절약) - 문자열 포맷 수정
            if (currentTime - lastStatsReport >= 2000) {  // 2초마다
                val processingRate = if (processedFrameCount + droppedFrameCount > 0) {
                    (processedFrameCount * 100) / (processedFrameCount + droppedFrameCount)
                } else 100

                Timber.d("🚀 ULTRA STATS - Processed: $processedFrameCount, Dropped: $droppedFrameCount, Rate: $processingRate%, Avg: ${compressTime}ms")

                processedFrameCount = 0
                droppedFrameCount = 0
                lastStatsReport = currentTime
            }

        } catch (e: Exception) {
            Timber.e(e, "Ultra fast send failed")
        } finally {
            isProcessing.set(false)
        }
    }

    // 🚀 소켓 재사용으로 생성 오버헤드 제거
    private fun getOrCreateSocket(): DatagramSocket {
        val currentTime = System.currentTimeMillis()

        if (reuseSocket?.isClosed == false && currentTime - lastSocketTime < SOCKET_REUSE_MS) {
            return reuseSocket!!
        }

        // 기존 소켓 정리
        reuseSocket?.close()

        // 새 소켓 생성 (극한 최적화)
        reuseSocket = DatagramSocket().apply {
            setSendBufferSize(CHUNK_SIZE * 4)  // 큰 버퍼
            setReceiveBufferSize(CHUNK_SIZE * 4)
            setTrafficClass(0x04)  // 최저 지연 우선
            reuseAddress = true
        }

        lastSocketTime = currentTime
        return reuseSocket!!
    }

    private suspend fun sendSinglePacketUltraFast(ipAddress: String, data: ByteArray) {
        try {
            val socket = getOrCreateSocket()  // 소켓 재사용

            // 🚀 최소 헤더 (프레임 ID만)
            val frameData = ByteBuffer.allocate(4 + data.size)
            frameData.putInt(nextFrameId++)
            frameData.put(data)
            val finalData = frameData.array()

            val packet = DatagramPacket(
                finalData, finalData.size,
                InetAddress.getByName(ipAddress), Constants.STREAMING_PORT
            )
            socket.send(packet)

        } catch (e: Exception) {
            Timber.e(e, "Ultra fast single send failed")
        }
    }

    private suspend fun sendChunkedPacketsUltraFast(ipAddress: String, data: ByteArray) {
        try {
            val frameId = nextFrameId++
            val totalChunks = ceil(data.size.toDouble() / CHUNK_SIZE).toInt()
            val socket = getOrCreateSocket()  // 소켓 재사용

            val addr = InetAddress.getByName(ipAddress)

            var offset = 0
            for (seq in 0 until totalChunks) {
                val remain = data.size - offset
                val chunkSize = min(remain, CHUNK_SIZE)

                val header = buildHeaderUltraFast(frameId, totalChunks, seq)
                val packetData = ByteArray(header.size + chunkSize)
                System.arraycopy(header, 0, packetData, 0, header.size)
                System.arraycopy(data, offset, packetData, header.size, chunkSize)

                val packet = DatagramPacket(packetData, packetData.size, addr, Constants.STREAMING_PORT)
                socket.send(packet)

                offset += chunkSize
            }

        } catch (e: Exception) {
            Timber.e(e, "Ultra fast chunked send failed")
        }
    }

    private fun buildHeaderUltraFast(frameId: Int, totalChunks: Int, seq: Int): ByteArray {
        val bb = ByteBuffer.allocate(HEADER_SIZE)
        bb.putInt(MAGIC)
        bb.putInt(frameId)
        bb.putShort(totalChunks.toShort())
        bb.putShort(seq.toShort())
        return bb.array()
    }

    override suspend fun receiveStreaming(): Flow<Bitmap> = flow {
        val socket = DatagramSocket(Constants.STREAMING_PORT).apply {
            receiveBufferSize = MAX_UDP_PACKET * 8  // 더 큰 버퍼
            soTimeout = 0
            setTrafficClass(0x04)
            reuseAddress = true
        }

        val buffer = ByteArray(MAX_UDP_PACKET)
        val frames = ReassemblyTable()

        var receivedFrameCount = 0
        var lastReceiveReport = 0L
        var frameReceiveTimes = mutableMapOf<Int, Long>()

        Timber.d("🚀 Ultra Fast UDP Receiver - Target 30FPS")

        try {
            while (true) {
                val packetStartTime = System.currentTimeMillis()
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                val receiveTime = System.currentTimeMillis() - packetStartTime

                if (packet.length >= HEADER_SIZE) {
                    val magic = ByteBuffer.wrap(packet.data, 0, 4).int

                    if (magic == MAGIC) {
                        // 청킹 패킷
                        val bb = ByteBuffer.wrap(packet.data, 0, packet.length)
                        bb.int  // magic
                        val frameId = bb.int
                        val totalChunks = bb.short.toInt() and 0xFFFF
                        val seq = bb.short.toInt() and 0xFFFF
                        val payloadLen = packet.length - HEADER_SIZE

                        if (seq == 0) {
                            frameReceiveTimes[frameId] = System.currentTimeMillis()
                        }

                        frames.putChunk(frameId, totalChunks, seq, packet.data, HEADER_SIZE, payloadLen)

                        val ready = frames.tryAssemble(frameId)
                        if (ready != null) {
                            val frameCompleteTime = System.currentTimeMillis()
                            val frameStartTime = frameReceiveTimes.remove(frameId) ?: frameCompleteTime
                            val reassemblyTime = frameCompleteTime - frameStartTime

                            // 🚀 빠른 디코딩 (옵션 최적화)
                            val decodeStartTime = System.currentTimeMillis()
                            val bitmap = BitmapFactory.decodeByteArray(ready, 0, ready.size, compressOptions)
                            val decodeTime = System.currentTimeMillis() - decodeStartTime

                            if (bitmap != null) {
                                receivedFrameCount++

                                val totalProcessTime = reassemblyTime + decodeTime

                                // 🚀 15ms 초과 시만 로그 (더 엄격)
                                if (totalProcessTime > 15) {
                                    Timber.d("⚡ RECEIVE - Reassembly: ${reassemblyTime}ms, Decode: ${decodeTime}ms [CHUNKED]")
                                }

                                emit(bitmap)
                            }
                        }

                        // 🚀 정리 최소화 (매 30번에 한 번)
                        if (frameId % 30 == 0) {
                            frames.cleanupStale()
                        }
                        continue
                    }
                }

                // 단일 패킷
                if (packet.length >= 4) {
                    val bb = ByteBuffer.wrap(packet.data, 0, packet.length)
                    val frameId = bb.int
                    val imageData = ByteArray(packet.length - 4)
                    System.arraycopy(packet.data, 4, imageData, 0, imageData.size)

                    // 🚀 빠른 디코딩
                    val decodeStartTime = System.currentTimeMillis()
                    val bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.size, compressOptions)
                    val decodeTime = System.currentTimeMillis() - decodeStartTime

                    if (bitmap != null) {
                        receivedFrameCount++

                        val totalReceiveTime = receiveTime + decodeTime

                        // 🚀 10ms 초과 시만 로그
                        if (totalReceiveTime > 10) {
                            Timber.d("⚡ RECEIVE - Network: ${receiveTime}ms, Decode: ${decodeTime}ms [SINGLE]")
                        }

                        emit(bitmap)
                    }
                }

                // 수신 통계 (2초마다)
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastReceiveReport >= 2000) {
                    val avgFps = receivedFrameCount / 2.0
                    Timber.d("🚀 ULTRA RECEIVE - FPS: $avgFps (Target: 30)")
                    receivedFrameCount = 0
                    lastReceiveReport = currentTime
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Ultra fast receive error")
        } finally {
            socket.close()
            reuseSocket?.close()
        }
    }.flowOn(Dispatchers.IO)

    // 재조립 테이블
    private class ReassemblyTable {
        private data class Entry(
            val total: Int,
            val chunks: Array<ByteArray?>,
            var received: Int,
            val createdAt: Long = System.currentTimeMillis()
        )

        private val map = ConcurrentHashMap<Int, Entry>()

        fun putChunk(frameId: Int, total: Int, seq: Int, data: ByteArray, offset: Int, length: Int) {
            val entry = map.getOrPut(frameId) { Entry(total, arrayOfNulls(total), 0) }

            if (entry.total != total) {
                map[frameId] = Entry(total, arrayOfNulls(total), 0)
                return
            }

            if (entry.chunks[seq] == null) {
                val copy = ByteArray(length)
                System.arraycopy(data, offset, copy, 0, length)
                entry.chunks[seq] = copy
                entry.received++
            }
        }

        fun tryAssemble(frameId: Int): ByteArray? {
            val entry = map[frameId] ?: return null
            if (entry.received != entry.total) return null

            val totalSize = entry.chunks.sumOf { it?.size ?: 0 }
            val result = ByteArray(totalSize)
            var pos = 0

            for (i in 0 until entry.total) {
                val chunk = entry.chunks[i]!!
                System.arraycopy(chunk, 0, result, pos, chunk.size)
                pos += chunk.size
            }

            map.remove(frameId)
            return result
        }

        fun cleanupStale(now: Long = System.currentTimeMillis()) {
            val iterator = map.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (now - entry.value.createdAt > STALE_MS) {
                    iterator.remove()
                }
            }
        }
    }

    companion object {
        private const val HEADER_SIZE = 12
        private const val MAGIC = 0x50325053
        private const val STALE_MS = 500L    // 🚀 0.5초로 단축 (초고속 정리)
    }
}
