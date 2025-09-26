package com.andyha.camerakit.analyzer

import android.graphics.Bitmap
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.andyha.camerakit.processor.FramePipelineProcessor
import com.andyha.camerakit.utils.BitmapUtils
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import timber.log.Timber
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@ExperimentalGetImage
class PreviewFrameAnalyzer : ImageAnalysis.Analyzer {

    private val _output: MutableSharedFlow<Bitmap> = MutableSharedFlow(replay = 1)
    val output: SharedFlow<Bitmap> = _output.asSharedFlow()

    // 엄격한 30 FPS 제한
    private var lastProcessTime = 0L
    private val TARGET_FPS_INTERVAL = 33L // 30 FPS (33ms 간격)

    // 동시 처리 방지
    private val isProcessing = AtomicBoolean(false)

    // 성능 통계 추적
    private val processedCount = AtomicInteger(0)
    private val droppedCount = AtomicInteger(0)
    private val fpsDroppedCount = AtomicInteger(0) // FPS 제한으로 드롭된 프레임
    private val busyDroppedCount = AtomicInteger(0) // 처리 중으로 드롭된 프레임
    private val errorCount = AtomicInteger(0)
    private var totalProcessingTime = 0L
    private var lastStatsTime = 0L
    private var maxProcessingTime = 0L
    private var minProcessingTime = Long.MAX_VALUE

    private val imageQueue = LinkedBlockingQueue<ImageProxy>(2)
    private val pipelineProcessor = FramePipelineProcessor()

    override fun analyze(image: ImageProxy) {
        // 큐가 가득 차면 이전 프레임 드롭
        if (!imageQueue.offer(image)) {
            imageQueue.poll()?.close() // 드롭
            imageQueue.offer(image)
        }
    }

    init {
        pipelineProcessor.startProcessing(imageQueue, _output)
    }


    private fun logPerformanceStats() {
        val processed = processedCount.get()
        val fpsDropped = fpsDroppedCount.get()
        val busyDropped = busyDroppedCount.get()
        val errors = errorCount.get()
        val totalFrames = processed + fpsDropped + busyDropped + errors

        if (totalFrames > 0) {
            val processRate = (processed * 100) / totalFrames
            val fpsDropRate = (fpsDropped * 100) / totalFrames
            val busyDropRate = (busyDropped * 100) / totalFrames
            val errorRate = (errors * 100) / totalFrames
            val actualFPS = processed / 3.0

            val avgProcessingTime = if (processed > 0) totalProcessingTime / processed else 0

            Timber.d("FRAME ANALYZER STATS (3s):")
            Timber.d("  Processed: $processed ($processRate%) - FPS: ${String.format("%.1f", actualFPS)}")
            Timber.d("  Dropped - FPS limit: $fpsDropped ($fpsDropRate%), Busy: $busyDropped ($busyDropRate%)")
            Timber.d("  Errors: $errors ($errorRate%)")
            Timber.d("  Processing time - Avg: ${avgProcessingTime}ms, Max: ${maxProcessingTime}ms, Min: ${minProcessingTime}ms")

            // 성능 경고
            if (processRate < 80) {
                Timber.w("LOW PROCESSING RATE: $processRate% - Consider optimization")
            }

            if (avgProcessingTime > 30) {
                Timber.w("SLOW PROCESSING: ${avgProcessingTime}ms average - Target <30ms")
            }
        }
    }

    private fun resetStats() {
        processedCount.set(0)
        fpsDroppedCount.set(0)
        busyDroppedCount.set(0)
        errorCount.set(0)
        totalProcessingTime = 0L
        maxProcessingTime = 0L
        minProcessingTime = Long.MAX_VALUE
    }

    /**
     * 현재 실제 FPS 반환
     */
    fun getCurrentFPS(): Double {
        return processedCount.get() / 3.0
    }

    /**
     * 현재 처리율 반환 (0-100)
     */
    fun getProcessingRate(): Int {
        val processed = processedCount.get()
        val total = processed + fpsDroppedCount.get() + busyDroppedCount.get() + errorCount.get()
        return if (total > 0) (processed * 100) / total else 100
    }

    /**
     * 평균 처리 시간 반환 (ms)
     */
    fun getAverageProcessingTime(): Long {
        val processed = processedCount.get()
        return if (processed > 0) totalProcessingTime / processed else 0
    }

    /**
     * 통계 리셋 (필요시 외부에서 호출)
     */
    fun resetStatistics() {
        resetStats()
        lastStatsTime = System.currentTimeMillis()
    }

    /**
     * 현재 처리 상태 확인
     */
    fun isCurrentlyProcessing(): Boolean {
        return isProcessing.get()
    }
}
