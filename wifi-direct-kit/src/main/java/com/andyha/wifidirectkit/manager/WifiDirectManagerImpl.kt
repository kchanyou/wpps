package com.andyha.wifidirectkit.manager

import android.content.BroadcastReceiver
import android.content.Context
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import com.andyha.wifidirectkit.model.ConnectionState
import com.andyha.wifidirectkit.utils.DirectActionListener
import com.andyha.wifidirectkit.utils.WifiDirectBroadcastReceiver
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class WifiDirectManagerImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : WifiDirectManager {
    private lateinit var wifiP2pManager: WifiP2pManager

    private lateinit var wifiP2pChannel: WifiP2pManager.Channel

    private var wifiP2pEnabled = false

    private val _groupFormed = MutableSharedFlow<WifiP2pInfo?>(replay = 1)
    override val groupFormed = _groupFormed.asSharedFlow()

    private val _availablePeers = MutableSharedFlow<List<WifiP2pDevice>>(replay = 1)
    override val availablePeers = _availablePeers.asSharedFlow()

    private val _connectedState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override var connectionState = _connectedState.asStateFlow()

    private var connectedPeer: WifiP2pDevice? = null

    private var broadcastReceiver: BroadcastReceiver? = null

    private val directActionListener = object : DirectActionListener {

        override fun wifiP2pEnabled(enabled: Boolean) {
            Timber.d("wifiP2pEnabled: $enabled")
            wifiP2pEnabled = enabled
        }

        override fun onConnectionInfoAvailable(wifiP2pInfo: WifiP2pInfo) {
            Timber.d("onConnectionInfoAvailable: $wifiP2pInfo")
            if (wifiP2pInfo.groupFormed) {
                _groupFormed.tryEmit(wifiP2pInfo)
                connectedPeer?.let {
                    _connectedState.tryEmit(ConnectionState.Connected(wifiP2pInfo, it))
                }
            }
        }

        override fun onDisconnection() {
            Timber.d("onDisconnection")
            _connectedState.tryEmit(ConnectionState.Disconnected)
        }

        override fun onSelfDeviceAvailable(wifiP2pDevice: WifiP2pDevice) {
            Timber.d("onSelfDeviceAvailable: $wifiP2pDevice")
        }

        override fun onPeersAvailable(wifiP2pDeviceList: Collection<WifiP2pDevice>) {
            Timber.d("onPeersAvailable: $wifiP2pDeviceList")
            connectedPeer = wifiP2pDeviceList.firstOrNull { it.status == WifiP2pDevice.CONNECTED }
            _availablePeers.tryEmit(wifiP2pDeviceList.toList())
        }

        override fun onChannelDisconnected() {
            Timber.d("onChannelDisconnected")
        }
    }

    override fun init() {
        // lateinit var 속성이 초기화되었는지 확인하여 중복 실행 방지
        if (::wifiP2pManager.isInitialized) return

        wifiP2pManager =
            context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager ?: return
        wifiP2pChannel =
            wifiP2pManager.initialize(context, context.mainLooper, directActionListener)
        broadcastReceiver = WifiDirectBroadcastReceiver(
            wifiP2pManager = wifiP2pManager,
            wifiP2pChannel = wifiP2pChannel,
            directActionListener = directActionListener
        )
        context.registerReceiver(broadcastReceiver, WifiDirectBroadcastReceiver.getIntentFilter())
        discoverPeers()
    }

    override fun discoverPeers() {
        if (!::wifiP2pManager.isInitialized) return
        wifiP2pManager.discoverPeers(wifiP2pChannel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Timber.d("discoverPeers successfully")
            }

            override fun onFailure(reasonCode: Int) {
                Timber.d("discoverPeers failed：$reasonCode")
            }
        })
    }

    override fun connect(wifiP2pDevice: WifiP2pDevice) {
        if (!::wifiP2pManager.isInitialized) return
        Timber.d("Try to connect: $wifiP2pDevice")
        val wifiP2pConfig = WifiP2pConfig()
        wifiP2pConfig.deviceAddress = wifiP2pDevice.deviceAddress
        wifiP2pConfig.wps.setup = WpsInfo.PBC
        wifiP2pManager.connect(wifiP2pChannel, wifiP2pConfig,
            object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Timber.d("connect successfully")
                }

                override fun onFailure(reason: Int) {
                    Timber.d("Connect failed: $reason")
                }
            })
    }

    override fun disconnect() {
        if (!::wifiP2pManager.isInitialized) return
        wifiP2pManager.cancelConnect(wifiP2pChannel, object : WifiP2pManager.ActionListener {
            override fun onFailure(reasonCode: Int) {
                Timber.d("cancelConnect failed: $reasonCode")
            }

            override fun onSuccess() {
                Timber.d("cancelConnect successfully")
            }
        })
        wifiP2pManager.removeGroup(wifiP2pChannel, null)
    }

    // --- (변경) ---
    override suspend fun createGroup() {
        if (!::wifiP2pManager.isInitialized) return
        // 기존 그룹을 무조건 삭제하는 대신, 그룹이 없을 때만 생성하도록 수정
        return suspendCancellableCoroutine { continuation ->
            wifiP2pManager.requestGroupInfo(wifiP2pChannel) { group ->
                if (group == null) {
                    // 현재 생성된 그룹이 없으므로, 새로 생성
                    createGroupInternal { continuation.resume(Unit) }
                } else {
                    // 이미 그룹이 있으므로 아무것도 하지 않고 종료
                    Timber.d("Group already exists.")
                    continuation.resume(Unit)
                }
            }
        }
    }

    // --- (변경) ---
    // 비동기 작업 완료를 알리기 위해 onComplete 콜백 추가
    private fun createGroupInternal(onComplete: (() -> Unit)? = null) {
        wifiP2pManager.createGroup(wifiP2pChannel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Timber.d("createGroup successfully")
                onComplete?.invoke()
            }

            override fun onFailure(reason: Int) {
                Timber.d("createGroup failed: $reason")
                onComplete?.invoke()
            }
        })
    }

    override suspend fun removeGroup() {
        if (!::wifiP2pManager.isInitialized) return
        return suspendCancellableCoroutine { continuation ->
            wifiP2pManager.requestGroupInfo(wifiP2pChannel) { group ->
                if (group == null) {
                    continuation.resume(value = Unit)
                } else {
                    removeGroupInternal { continuation.resume(value = Unit) }
                }
            }
        }
    }

    private fun removeGroupInternal(onComplete: (() -> Unit)? = null) {
        wifiP2pManager.removeGroup(wifiP2pChannel,
            object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Timber.d("removeGroup successfully")
                    onComplete?.invoke()
                    _groupFormed.tryEmit(null)
                }

                override fun onFailure(reason: Int) {
                    Timber.d("removeGroup failed: $reason")
                    onComplete?.invoke()
                }
            })
    }

    override suspend fun toggleGroup() {
        if (!::wifiP2pManager.isInitialized) return
        wifiP2pManager.requestGroupInfo(wifiP2pChannel) { group ->
            if (group == null) {
                createGroupInternal()
            } else {
                removeGroupInternal()
            }
        }
    }

    override fun close() {
        if (!::wifiP2pManager.isInitialized) return
        // 앱 종료 시 broadcastReceiver 등록 해제 추가
        try {
            context.unregisterReceiver(broadcastReceiver)
        } catch (e: Exception) {
            Timber.e(e, "broadcastReceiver already unregistered")
        }
        disconnect()
    }
}