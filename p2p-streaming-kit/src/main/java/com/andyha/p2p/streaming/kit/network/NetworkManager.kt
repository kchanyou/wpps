package com.andyha.p2p.streaming.kit.network

object NetworkManager {
    var ipAddress: String? = null

    suspend fun sendEncodedData(encodedData: ByteArray) {
        ipAddress?.let { ip ->
            com.andyha.p2p.streaming.kit.data.repository.socket.streaming.UdpStreamingSocket.sendData(ip, encodedData)
        }
    }
}
