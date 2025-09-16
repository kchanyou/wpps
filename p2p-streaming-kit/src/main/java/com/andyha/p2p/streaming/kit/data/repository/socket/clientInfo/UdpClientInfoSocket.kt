package com.andyha.p2p.streaming.kit.data.repository.socket.clientInfo

import android.content.Context
import android.net.wifi.WifiManager
import com.andyha.p2p.streaming.kit.util.Constants
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import timber.log.Timber
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress

class UdpClientInfoSocket(private val context: Context) : ClientInfoSocket {

    private val wm by lazy { context.getSystemService(Context.WIFI_SERVICE) as WifiManager }

    override fun sendSelfIpAddress(serverIpAdress: String) {
        // 라우팅 기반으로 내 P2P IPv4 추출
        val myIp = resolveLocalP2pIpv4For(serverIpAdress, Constants.CLIENT_INFO_PORT)
        Timber.d("sendSelfIpAddress: resolved my P2P IP = $myIp (server=$serverIpAdress)")

        val data: ByteArray = myIp.encodeToByteArray()
        try {
            DatagramSocket().use { socket ->
                val dp = DatagramPacket(
                    data,
                    data.size,
                    InetAddress.getByName(serverIpAdress),
                    Constants.CLIENT_INFO_PORT
                )
                socket.send(dp)
            }
        } catch (e: Exception) {
            Timber.e(e, "sendSelfIpAddress failed")
        }
    }

    override suspend fun receiveSubscribers(localServerAddress: String): Flow<String> {
        return flow {
            // 서버는 와일드카드 바인드로 어떤 NIC로 와도 수신
            val buf = ByteArray(256)
            try {
                DatagramSocket(null).use { socket ->
                    socket.reuseAddress = true
                    socket.soTimeout = 0
                    // 0.0.0.0:CLIENT_INFO_PORT 에 바인드
                    socket.bind(InetSocketAddress(Constants.CLIENT_INFO_PORT))
                    Timber.d(
                        "client-info listen bound on 0.0.0.0:${Constants.CLIENT_INFO_PORT} (server hint=$localServerAddress)"
                    )

                    while (true) {
                        val dp = DatagramPacket(buf, buf.size)
                        socket.receive(dp)
                        val claimed = String(dp.data, 0, dp.length).trim()
                        val remote = (dp.address as? Inet4Address)?.hostAddress ?: dp.address.hostAddress
                        // 0.0.0.0/빈값이면 발신자 주소로 대체
                        val useIp = if (claimed.isBlank() || claimed == "0.0.0.0") remote else claimed
                        Timber.d("receive subscriber IP: claimed=$claimed remote=$remote -> use=$useIp")
                        emit(useIp)
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "receiveSubscribers failed")
            }
        }
    }

    private fun resolveLocalP2pIpv4For(serverIp: String, port: Int): String {
        return try {
            DatagramSocket().use { s ->
                // 실제 송신 없이 라우팅만 계산 → localAddress가 해당 목적지로 나갈 NIC의 IP가 됨
                s.connect(InetAddress.getByName(serverIp), port)
                val a = s.localAddress
                if (a is Inet4Address && !a.isLoopbackAddress && !a.isLinkLocalAddress) {
                    a.hostAddress
                } else {
                    "0.0.0.0"
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "resolveLocalP2pIpv4For failed")
            "0.0.0.0"
        }
    }
}
