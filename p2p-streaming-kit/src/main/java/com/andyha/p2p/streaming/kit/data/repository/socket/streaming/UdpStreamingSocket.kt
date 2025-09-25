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
import kotlin.math.ceil
import kotlin.math.min

class UdpStreamingSocket(private val context: Context) : StreamingSocket {

    // MTU를 고려한 안전한 청크 크기
    private val CHUNK_SIZE = 65_000  // 65KB (안전한 크기)
    private val MAX_UDP_PACKET = CHUNK_SIZE + 64
    private var nextFrameId = 1

    private val OPTIMAL_QUALITY = 100

    // 30 FPS 제한
    private var lastSendTime = 0L
    private val MIN_SEND_INTERVAL = 33L
    private var skipFrameCount = 0
    private var processFrameCount = 0
    private var singlePacketCount = 0
    private var chunkedPacketCount = 0

    // 소켓 풀
    private var socketPool = mutableListOf<DatagramSocket>()
    private val poolLock = Any()

    private val compressOptions = BitmapFactory.Options().apply {
        inPreferredConfig = Bitmap.Config.RGB_565
        inSampleSize = 1
        inJustDecodeBounds = false
        inMutable = false
    }

    private fun getPooledSocket(): DatagramSocket {
        synchronized(poolLock) {
            if (socketPool.isNotEmpty()) {
                return socketPool.removeAt(0)
            }
        }

        return DatagramSocket().apply {
            setSendBufferSize(CHUNK_SIZE * 4)
            setReceiveBufferSize(CHUNK_SIZE * 4)
            setTrafficClass(0x04)
            reuseAddress = true
        }
    }

    private fun returnSocketToPool(socket: DatagramSocket) {
        synchronized(poolLock) {
            if (socketPool.size < 3 && !socket.isClosed) {
                socketPool.add(socket)
            } else {
                socket.close()
            }
        }
    }

    private fun compressToJpegOptimal(bitmap: Bitmap, quality: Int): ByteArray {
        val outputStream = ByteArrayOutputStream(120_000)
        val success = bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
        if (!success) {
            Timber.e("Bitmap compression failed!")
            return ByteArray(0)
        }
        return outputStream.toByteArray()
    }

    override suspend fun sendBitmap(ipAddress: String, bitmap: Bitmap) = withContext(Dispatchers.IO) {
        val currentTime = System.currentTimeMillis()

        if (currentTime - lastSendTime < MIN_SEND_INTERVAL) {
            skipFrameCount++
            return@withContext
        }

        try {
            val startTime = System.currentTimeMillis()

            val data = compressToJpegOptimal(bitmap, OPTIMAL_QUALITY)
            val compressTime = System.currentTimeMillis() - startTime

            val sendTime = System.currentTimeMillis()
            if (data.size <= CHUNK_SIZE) {
                sendSinglePacketUltraFast(ipAddress, data)
                singlePacketCount++
            } else {
                sendChunkedPacketsOptimized(ipAddress, data)
                chunkedPacketCount++
            }
            val networkTime = System.currentTimeMillis() - sendTime

            lastSendTime = currentTime
            processFrameCount++

            if (currentTime % 2000 < 100) {
                val totalFrames = processFrameCount + skipFrameCount
                val processRate = if (totalFrames > 0) (processFrameCount * 100) / totalFrames else 0
                val singleRate = if (processFrameCount > 0) (singlePacketCount * 100) / processFrameCount else 0
                val avgSize = data.size

                Timber.d("SEND 65K - Process: $processFrameCount, Skip: $skipFrameCount, Rate: $processRate%")
                Timber.d("PACKET SPLIT - Single: $singlePacketCount, Chunked: $chunkedPacketCount, Single Rate: $singleRate%")
                Timber.d("SIZE & TIMING - Avg: ${avgSize}B, Compress: ${compressTime}ms, Network: ${networkTime}ms")

                processFrameCount = 0
                skipFrameCount = 0
                singlePacketCount = 0
                chunkedPacketCount = 0
            }

        } catch (e: Exception) {
            Timber.e(e, "65K send failed")
        }
    }

    private suspend fun sendSinglePacketUltraFast(ipAddress: String, data: ByteArray) {
        var socket: DatagramSocket? = null
        try {
            socket = getPooledSocket()

            // 단일 패킷은 헤더 없이 직접 전송
            val packet = DatagramPacket(data, data.size, InetAddress.getByName(ipAddress), Constants.STREAMING_PORT)
            socket.send(packet)

        } catch (e: Exception) {
            Timber.e(e, "Ultra fast single send failed")
        } finally {
            socket?.let { returnSocketToPool(it) }
        }
    }

    private suspend fun sendChunkedPacketsOptimized(ipAddress: String, data: ByteArray) {
        var socket: DatagramSocket? = null
        try {
            val frameId = nextFrameId++
            val totalChunks = ceil(data.size.toDouble() / CHUNK_SIZE).toInt()
            socket = getPooledSocket()

            val addr = InetAddress.getByName(ipAddress)

            var offset = 0
            for (seq in 0 until totalChunks) {
                val remain = data.size - offset
                val chunkSize = min(remain, CHUNK_SIZE)

                val header = buildHeaderOptimized(frameId, totalChunks, seq)
                val packetData = ByteArray(header.size + chunkSize)
                System.arraycopy(header, 0, packetData, 0, header.size)
                System.arraycopy(data, offset, packetData, header.size, chunkSize)

                val packet = DatagramPacket(packetData, packetData.size, addr, Constants.STREAMING_PORT)
                socket.send(packet)

                offset += chunkSize
            }

        } catch (e: Exception) {
            Timber.e(e, "65K chunked send failed")
        } finally {
            socket?.let { returnSocketToPool(it) }
        }
    }

    private fun buildHeaderOptimized(frameId: Int, totalChunks: Int, seq: Int): ByteArray {
        val bb = ByteBuffer.allocate(HEADER_SIZE)
        bb.putInt(MAGIC)
        bb.putInt(frameId)
        bb.putShort(totalChunks.toShort())
        bb.putShort(seq.toShort())
        return bb.array()
    }

    override suspend fun receiveStreaming(): Flow<Bitmap> = flow {
        val socket = DatagramSocket(Constants.STREAMING_PORT).apply {
            receiveBufferSize = MAX_UDP_PACKET * 8
            soTimeout = 0
            setTrafficClass(0x04)
            reuseAddress = true
        }

        val buffer = ByteArray(MAX_UDP_PACKET)
        val frames = ReassemblyTable()
        var receivedCount = 0
        var singlePacketReceived = 0
        var chunkedPacketReceived = 0
        var lastReportTime = 0L
        var frameReceiveTimes = mutableMapOf<Int, Long>()

        Timber.d("65K UDP Receiver started")

        try {
            while (true) {
                val receiveStartTime = System.currentTimeMillis()
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                val receiveTime = System.currentTimeMillis() - receiveStartTime

                if (packet.length >= HEADER_SIZE) {
                    val magic = ByteBuffer.wrap(packet.data, 0, 4).int

                    if (magic == MAGIC) {
                        // 청킹 패킷 처리
                        val bb = ByteBuffer.wrap(packet.data, 0, packet.length)
                        bb.int // magic 건너뛰기
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

                            val decodeStartTime = System.currentTimeMillis()
                            val bitmap = BitmapFactory.decodeByteArray(ready, 0, ready.size, compressOptions)
                            val decodeTime = System.currentTimeMillis() - decodeStartTime

                            if (bitmap != null) {
                                receivedCount++
                                chunkedPacketReceived++

                                val totalProcessTime = reassemblyTime + decodeTime

                                if (totalProcessTime > 15) {
                                    Timber.d("RECEIVE 65K - Reassembly: ${reassemblyTime}ms, Decode: ${decodeTime}ms, Size: ${ready.size}B [CHUNKED]")
                                }

                                emit(bitmap)
                            }
                        }

                        if (frameId % 30 == 0) {
                            frames.cleanupStale()
                        }
                        continue
                    }
                }

                // 단일 패킷 처리 (헤더 없음)
                if (packet.length > 0) {
                    val decodeStartTime = System.currentTimeMillis()
                    val bitmap = BitmapFactory.decodeByteArray(packet.data, 0, packet.length, compressOptions)
                    val decodeTime = System.currentTimeMillis() - decodeStartTime

                    if (bitmap != null) {
                        receivedCount++
                        singlePacketReceived++

                        val totalTime = receiveTime + decodeTime
                        if (totalTime > 10) {
                            Timber.d("RECEIVE 65K - Network: ${receiveTime}ms, Decode: ${decodeTime}ms, Size: ${packet.length}B [SINGLE]")
                        }

                        emit(bitmap)
                    }
                }

                val currentTime = System.currentTimeMillis()
                if (currentTime - lastReportTime >= 2000) {
                    val fps = receivedCount / 2.0
                    val singleRate = if (receivedCount > 0) (singlePacketReceived * 100) / receivedCount else 0
                    Timber.d("RECEIVE 65K - FPS: $fps")
                    Timber.d("PACKET MIX - Single: $singlePacketReceived, Chunked: $chunkedPacketReceived, Single Rate: $singleRate%")

                    receivedCount = 0
                    singlePacketReceived = 0
                    chunkedPacketReceived = 0
                    lastReportTime = currentTime
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "65K receive error")
        } finally {
            socket.close()
            synchronized(poolLock) {
                socketPool.forEach { it.close() }
                socketPool.clear()
            }
        }
    }.flowOn(Dispatchers.IO)

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
        private const val STALE_MS = 1000L
    }
}
