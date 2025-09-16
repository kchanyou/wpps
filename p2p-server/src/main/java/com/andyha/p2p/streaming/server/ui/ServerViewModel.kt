package com.andyha.p2p.streaming.server.ui

import android.graphics.Bitmap
import androidx.lifecycle.viewModelScope
import com.andyha.coreui.base.viewModel.BaseViewModel
import com.andyha.p2p.streaming.kit.data.repository.server.ServerRepository
import com.andyha.wifidirectkit.manager.WifiDirectManager
import com.andyha.wifidirectkit.model.ConnectionState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject


@HiltViewModel
class ServerViewModel @Inject constructor(
    private val serverRepository: ServerRepository,
    private val wifiDirectManager: WifiDirectManager,
) : BaseViewModel() {

    // IP addresses of subscribers
    private val subscribers = mutableSetOf<String>()

    private var connectionState: ConnectionState = ConnectionState.Disconnected

    val groupFormed by lazy { wifiDirectManager.groupFormed }

    private val _uiState = MutableStateFlow(ServerUiState(connectionState))
    val uiState = _uiState.asStateFlow()

    // --- (변경) ---
    // 클라이언트 리스너가 실행 중인지 확인하는 플래그
    private var isSubscriberListenerRunning = false

    // --- (변경) ---
    // ViewModel이 생성될 때 바로 상태 감지를 시작
    init {
        listenToGroupFormed()
        listenToConnectionState()
    }

    // --- (변경) ---
    // UI(Fragment/Activity)에서 이 함수를 호출하여 서버 역할을 시작
    fun startServer() {
        viewModelScope.launch {
            // 그룹이 이미 생성된 경우를 대비해 기존 그룹을 제거하고 새로 생성
            wifiDirectManager.createGroup()
        }
    }

    private fun listenToGroupFormed() {
        viewModelScope.launch(Dispatchers.IO) {
            wifiDirectManager
                .groupFormed
                .catch { Timber.e(it) }
                .collect { wifiP2pInfo ->
                    Timber.d("collect GroupFormed: $wifiP2pInfo")

                    // --- (변경) ---
                    // 그룹이 형성되었고, 리스너가 아직 실행 중이 아닐 때만 listenToSubscribers 호출
                    if (wifiP2pInfo != null && wifiP2pInfo.groupFormed && !isSubscriberListenerRunning) {
                        isSubscriberListenerRunning = true
                        subscribers.clear() // 새 그룹이므로 구독자 목록 초기화
                        wifiP2pInfo.groupOwnerAddress?.hostAddress?.let { serverAddress ->
                            listenToSubscribers(serverAddress)
                        }
                    }
                    // 그룹 연결이 끊어졌을 때 플래그를 리셋하여 다음 연결을 준비
                    else if (wifiP2pInfo == null || !wifiP2pInfo.groupFormed) {
                        isSubscriberListenerRunning = false
                        subscribers.clear()
                    }
                }
        }
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

    private fun emitUiState() {
        _uiState.tryEmit(ServerUiState(connectionState))
    }

    fun toggleGroup() {
        viewModelScope.launch(Dispatchers.IO) {
            wifiDirectManager.toggleGroup()
        }
    }

    fun sendCameraFrame(bitmap: Bitmap) {
        // subscribers 목록이 비어있지 않을 때만 전송
        if (subscribers.isNotEmpty()) {
            Timber.d("sendCameraFrame to: $subscribers")
            for (subscriber in subscribers) {
                viewModelScope.launch(Dispatchers.IO) {
                    serverRepository.sendBitmap(subscriber, bitmap)
                }
            }
        }
    }

    private fun listenToSubscribers(serverAddress: String) {
        Timber.d("Start listening to subscribers: $serverAddress")
        viewModelScope.launch(Dispatchers.IO) {
            serverRepository
                .receiveSubscribers(serverAddress)
                .catch { Timber.e(it) }
                .collect {
                    Timber.d("collectSubscriber: $it")
                    subscribers.add(it)
                }
        }
    }

    // --- (제거) ---
    // `initWifiDirect`와 `onCleared`는 Application 레벨에서 관리하므로 ViewModel에서 제거합니다.
}