package com.andyha.p2p.streaming.client.ui.main

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaFormat
import android.os.Environment
import androidx.lifecycle.viewModelScope
import com.andyha.coreui.base.viewModel.BaseViewModel
import com.andyha.coreutils.FileUtils
import com.andyha.coreutils.time.TimeFormatter
import com.andyha.p2p.streaming.kit.data.repository.client.ClientRepository
import com.andyha.p2p.streaming.kit.encoder.Muxer
import com.andyha.p2p.streaming.kit.encoder.MuxerConfig
import com.andyha.p2p.streaming.kit.encoder.MuxingError
import com.andyha.p2p.streaming.kit.encoder.MuxingSuccess
import com.andyha.wifidirectkit.manager.WifiDirectManager
import com.andyha.wifidirectkit.model.ConnectionState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

@HiltViewModel
class ClientViewModel @Inject constructor(
    private val clientRepository: ClientRepository,
    private val wifiDirectManager: WifiDirectManager
) : BaseViewModel() {

    private var connectionState: ConnectionState = ConnectionState.Disconnected
    private var recordingState: RecordingState = RecordingState.None

    private val _uiState = MutableStateFlow(ClientUiState(connectionState, recordingState))
    val uiState = _uiState.asStateFlow()

    private val _bitmap = MutableSharedFlow<Bitmap>(replay = 1)
    val bitmap = _bitmap.asSharedFlow()

    private val recordingBitmapList = mutableListOf<Bitmap>()
    private var savingJob: Job? = null
    private var timerJob: Job? = null
    private var streamingJob: Job? = null

    private var mimeType = MediaFormat.MIMETYPE_VIDEO_AVC

    // 중복 수신 루프 방지
    private val isListening = AtomicBoolean(false)
    private val ipSent = AtomicBoolean(false)

    init {
        listenToGroupState()
        listenToConnectionState()
    }

    private fun listenToConnectionState() {
        viewModelScope.launch(Dispatchers.IO) {
            wifiDirectManager
                .connectionState
                .catch { Timber.e(it) }
                .collect {
                    Timber.d("collect ConnectionState: $it")
                    connectionState = it
                    emitUiState()
                }
        }
    }

    private fun listenToGroupState() {
        viewModelScope.launch(Dispatchers.IO) {
            wifiDirectManager
                .groupFormed
                // 같은 정보가 연속으로 들어오면 무시 (스팸 방지)
                .distinctUntilChanged { old, new ->
                    val of = old?.groupFormed ?: false
                    val nf = new?.groupFormed ?: false
                    val og = old?.isGroupOwner ?: false
                    val ng = new?.isGroupOwner ?: false
                    val oa = old?.groupOwnerAddress?.hostAddress
                    val na = new?.groupOwnerAddress?.hostAddress
                    (of == nf) && (og == ng) && (oa == na)
                }
                .catch { Timber.e(it) }
                .collect { group ->
                    if (group != null && group.groupFormed) {
                        val serverAddress = group.groupOwnerAddress?.hostAddress
                        Timber.d("Group formed, server=$serverAddress, isGO=${group.isGroupOwner}")

                        // 서버 IP 전송은 세션당 1회만 (필요한 경우에만)
                        if (serverAddress != null && ipSent.compareAndSet(false, true)) {
                            sendSelfIpAddress(serverAddress)
                        }

                        // 스트리밍 수신은 한 번만 시작
                        startListeningIfNeeded()
                    } else {
                        Timber.d("Group disconnected or not formed")
                        ipSent.set(false)
                        stopListeningIfNeeded()
                    }
                }
        }
    }

    private fun startListeningIfNeeded() {
        if (!isListening.compareAndSet(false, true)) {
            Timber.d("listen already started; skip")
            return
        }
        Timber.d("start listening bitmaps")

        streamingJob = viewModelScope.launch(Dispatchers.IO) {
            clientRepository
                .receiveStreaming()
                .onCompletion {
                    Timber.d("bitmap listening completed")
                    isListening.set(false)
                }
                .catch { e ->
                    Timber.e(e, "error while listening bitmaps")
                    isListening.set(false)
                }
                .collect { bmp ->
                    _bitmap.tryEmit(bmp)
                    if (recordingState is RecordingState.Recording) {
                        recordingBitmapList.add(bmp)
                    }
                }
        }
    }

    private fun stopListeningIfNeeded() {
        if (isListening.compareAndSet(true, false)) {
            Timber.d("stop listening bitmaps")
            streamingJob?.cancel()
            streamingJob = null
            // (선택) Repository에 소켓 정리 API가 있으면 호출하세요.
            // runCatching { clientRepository.stopStreaming() }
        }
    }

    private fun emitUiState() {
        _uiState.tryEmit(ClientUiState(connectionState, recordingState))
    }

    private fun sendSelfIpAddress(serverAddress: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                clientRepository.sendSelfIpAddress(serverAddress)
            }.onFailure { e ->
                Timber.w(e, "sendSelfIpAddress failed")
                // 실패해도 스트리밍은 진행 가능(UDP는 상대 패킷 주소로 파악 가능)
            }
        }
    }

    fun toggleRecording(context: Context) {
        if (recordingState == RecordingState.None) {
            if (savingJob?.isActive == true || timerJob?.isActive == true) return
            var duration = 0
            timerJob = flow {
                while (true) {
                    emit(duration)
                    duration += 1000
                    delay(1000L)
                }
            }
                .map { TimeFormatter.formatDurations(it / 1000) }
                .onEach {
                    recordingState = RecordingState.Recording(it)
                    emitUiState()
                }
                .launchIn(viewModelScope)

        } else if (recordingState is RecordingState.Recording) {
            if (recordingBitmapList.isEmpty()) return

            timerJob?.cancel()
            recordingState = RecordingState.SavingVideo
            emitUiState()

            createVideo(context)
        }
    }

    private fun createVideo(context: Context) {
        savingJob?.cancel()
        savingJob = null
        savingJob = viewModelScope.launch(Dispatchers.Default) {
            val videoFile =
                FileUtils.getFileInPublicExternalStorage(
                    Environment.DIRECTORY_DOWNLOADS,
                    P2P_STREAMING_FOLDER,
                    "p2p-recording-${System.currentTimeMillis() * 1000}.mp4"
                )
            videoFile.run {
                val muxerConfig = MuxerConfig(
                    this,
                    recordingBitmapList[0].width,
                    recordingBitmapList[0].height,
                    mimeType
                )
                val muxer = Muxer(context, muxerConfig)

                when (val result = muxer.muxAsync(recordingBitmapList)) {
                    is MuxingSuccess -> {
                        Timber.d("Video muxed - file path: ${result.file.absolutePath}")
                        recordingState = RecordingState.SaveSuccessfully
                        emitUiState()
                    }
                    is MuxingError -> {
                        Timber.d("There was an error muxing the video")
                        recordingState = RecordingState.SaveFailed
                        emitUiState()
                    }
                }
            }
            savingJob?.invokeOnCompletion {
                recordingState = RecordingState.None
                recordingBitmapList.clear()
                emitUiState()
            }
        }
    }

    companion object {
        const val P2P_STREAMING_FOLDER = "P2P-Streaming"
    }
}
