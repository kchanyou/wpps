package com.andyha.camerakit.processor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import com.andyha.p2p.streaming.kit.network.NetworkManager
import androidx.camera.core.ImageProxy
import com.andyha.camerakit.utils.BitmapUtils
import com.andyha.coreutils.FileUtils.bitmapToByteArray
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import timber.log.Timber
import java.util.concurrent.LinkedBlockingQueue

@androidx.camera.core.ExperimentalGetImage
class FramePipelineProcessor {
    private val processingScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val networkScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // MediaCodec JPEG 인코더
    private var jpegEncoder: MediaCodec? = null
    private var inputSurface: Surface? = null

    // 파이프라인 큐들
    private val encodedDataQueue = LinkedBlockingQueue<ByteArray>(3)

    fun startProcessing(
        imageQueue: LinkedBlockingQueue<ImageProxy>,
        outputFlow: MutableSharedFlow<Bitmap>
    ) {
        startMediaCodecEncoder()
        startProcessingPipeline(imageQueue, outputFlow)
        startNetworkPipeline()
    }

    private fun startMediaCodecEncoder() {
        try {
            jpegEncoder = MediaCodec.createEncoderByType("image/jpeg").apply {
                val format = MediaFormat.createVideoFormat("image/jpeg", 1920, 1080).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, 8000000) // 8Mbps
                    setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                }
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                inputSurface = createInputSurface()
                start()
            }
            Timber.d("MediaCodec JPEG encoder initialized")
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize MediaCodec encoder")
            // Fallback to BitmapUtils
        }
    }

    private fun startProcessingPipeline(
        imageQueue: LinkedBlockingQueue<ImageProxy>,
        outputFlow: MutableSharedFlow<Bitmap>
    ) {
        processingScope.launch {
            while (isActive) {
                try {
                    val imageProxy = withContext(Dispatchers.IO) {
                        imageQueue.take()
                    }

                    val startTime = System.currentTimeMillis()

                    if (jpegEncoder != null) {
                        // MediaCodec 경로
                        val encodedData = encodeWithMediaCodec(imageProxy)
                        if (encodedData != null) {
                            encodedDataQueue.offer(encodedData)
                            // 디코딩해서 Bitmap으로 변환 (디스플레이용)
                            val bitmap = BitmapFactory.decodeByteArray(encodedData, 0, encodedData.size)
                            outputFlow.tryEmit(bitmap)
                        }
                    } else {
                        // Fallback: BitmapUtils 경로
                        val bitmap = BitmapUtils.getBitmap(imageProxy)
                        if (bitmap != null) {
                            val encodedData = bitmapToByteArray(bitmap, 1)
                            encodedDataQueue.offer(encodedData)
                            outputFlow.tryEmit(bitmap)
                        }
                    }

                    val processTime = System.currentTimeMillis() - startTime
                    if (processTime > 30) {
                        Timber.w("SLOW PIPELINE PROCESSING: ${processTime}ms")
                    }

                    imageProxy.close()

                } catch (e: Exception) {
                    Timber.e(e, "Processing pipeline error")
                }
            }
        }
    }

    private fun startNetworkPipeline() {
        networkScope.launch {
            while (isActive) {
                try {
                    val encodedData = withContext(Dispatchers.IO) {
                        encodedDataQueue.take()
                    }

                    // UdpStreamingSocket에 전달
                    NetworkManager.sendEncodedData(encodedData)

                } catch (e: Exception) {
                    Timber.e(e, "Network pipeline error")
                }
            }
        }
    }

    private suspend fun encodeWithMediaCodec(imageProxy: ImageProxy): ByteArray? {
        return withContext(Dispatchers.Default) {
            try {
                // ImageProxy를 Surface에 렌더링
                // MediaCodec에서 인코딩된 데이터 추출
                // 실제 구현은 복잡하므로 단계적 접근 필요
                null // 임시
            } catch (e: Exception) {
                Timber.e(e, "MediaCodec encoding failed")
                null
            }
        }
    }
}
