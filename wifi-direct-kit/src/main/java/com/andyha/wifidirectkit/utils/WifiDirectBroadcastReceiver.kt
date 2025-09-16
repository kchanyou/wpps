package com.andyha.wifidirectkit.utils

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import timber.log.Timber

interface DirectActionListener : WifiP2pManager.ChannelListener {
    fun wifiP2pEnabled(enabled: Boolean)
    fun onConnectionInfoAvailable(wifiP2pInfo: WifiP2pInfo)
    fun onDisconnection()
    fun onSelfDeviceAvailable(wifiP2pDevice: WifiP2pDevice)
    fun onPeersAvailable(wifiP2pDeviceList: Collection<WifiP2pDevice>)
}

class WifiDirectBroadcastReceiver(
    private val wifiP2pManager: WifiP2pManager,
    private val wifiP2pChannel: WifiP2pManager.Channel,
    private val directActionListener: DirectActionListener
) : BroadcastReceiver() {

    companion object {
        fun getIntentFilter(): IntentFilter {
            return IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
            }
        }
    }

    // 같은 내용의 ConnectionInfo가 연속으로 들어올 때 중복 통지 방지
    private var lastGroupFormed = false
    private var lastIsGo = false
    private var lastGoAddr: String? = null

    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                val enabled = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) ==
                        WifiP2pManager.WIFI_P2P_STATE_ENABLED
                Timber.d("WIFI_P2P_STATE_CHANGED_ACTION: $enabled")
                directActionListener.wifiP2pEnabled(enabled)
                if (!enabled) {
                    // P2P 꺼지면 확실히 정리
                    directActionListener.onDisconnection()
                    directActionListener.onPeersAvailable(emptyList())
                    resetLastConnectionInfo()
                }
            }

            WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                Timber.d("WIFI_P2P_PEERS_CHANGED_ACTION")
                wifiP2pManager.requestPeers(wifiP2pChannel) { peers ->
                    directActionListener.onPeersAvailable(peers.deviceList)
                    // peers 콜백은 잦으므로 여기서도 ConnectionInfo 조회는 하되
                    // safeRequestConnectionInfo() 내부에서 디바운스
                    safeRequestConnectionInfo()
                }
            }

            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                Timber.d("WIFI_P2P_CONNECTION_CHANGED_ACTION")
                safeRequestConnectionInfo()
            }

            WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                val self =
                    intent.getParcelableExtra<WifiP2pDevice>(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                Timber.d("WIFI_P2P_THIS_DEVICE_CHANGED_ACTION: $self")
                if (self != null) directActionListener.onSelfDeviceAvailable(self)
            }
        }
    }

    private fun safeRequestConnectionInfo() {
        wifiP2pManager.requestConnectionInfo(wifiP2pChannel) { info ->
            if (info == null) {
                Timber.d("requestConnectionInfo: null")
                return@requestConnectionInfo
            }

            val go = info.groupOwnerAddress?.hostAddress
            Timber.d(
                "onConnectionInfoAvailable: groupFormed=${info.groupFormed}, " +
                        "isGO=${info.isGroupOwner}, go=$go"
            )

            // 이전과 동일하면 통지 생략 (중복 시작 방지의 핵심)
            val same = (lastGroupFormed == info.groupFormed) &&
                    (lastIsGo == info.isGroupOwner) &&
                    (lastGoAddr == go)
            if (same) {
                Timber.d("ConnectionInfo unchanged; skip notifying")
                return@requestConnectionInfo
            }

            // 상태 갱신
            lastGroupFormed = info.groupFormed
            lastIsGo = info.isGroupOwner
            lastGoAddr = go

            if (info.groupFormed) {
                directActionListener.onConnectionInfoAvailable(info)
            } else {
                directActionListener.onDisconnection()
            }
        }
    }

    private fun resetLastConnectionInfo() {
        lastGroupFormed = false
        lastIsGo = false
        lastGoAddr = null
    }
}
