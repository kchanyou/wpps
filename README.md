# wifi-p2p-streaming-bugfix-version

[한국어](./README.ko.md) | **English**
<h3> Key Changes </h3>

<h4> Registration (Client/Server) — Obtain & Trust P2P IP </h4>

- Client: call DatagramSocket.connect(serverIp, port) and use socket.localAddress.hostAddress to get the actual routed P2P IP; fall back to WifiManager IP only if needed.

- Server: if the registration payload is empty or 0.0.0.0, replace it with DatagramPacket.address.hostAddress. Bind listener to 0.0.0.0:CLIENT_INFO_PORT (all interfaces).

<h4> UDP Streaming Hardening </h4>

- Frame chunking & reassembly: split each frame into ≤ 60KB chunks. 12-byte header: MAGIC('P2PS',4) | frameId(int32) | totalChunks(int16) | seq(int16) (big-endian). Receiver buffers by frameId and decodes only when complete.

- Single bind & reuse for the receive socket: use DatagramSocket(null), set reuseAddress=true, then bind(0.0.0.0:STREAMING_PORT) once; keep soTimeout=0 (blocking) to avoid rebind loops.

- Accurate decoding: call BitmapFactory.decodeByteArray(dp.data, 0, dp.length) to decode only the received length.

- Enhanced logging/metrics: counters, byte/chunk sizes, and endpoint IP:PORT for TX/RX.
