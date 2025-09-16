# wifi-p2p-streaming

**한국어** | [English](./README.md)
<h3> 핵심 변경사항 (수정 마무리 단계) </h3>

<h4> 등록(클라이언트/서버) — P2P IP 취득 & 신뢰 </h4>

- 클라이언트: DatagramSocket.connect(serverIp, port) 후 socket.localAddress.hostAddress로 실제 라우팅 경로의 P2P IP를 취득합니다. 실패 시 WifiManager를 통한 IP로 폴백합니다.

- 서버: 등록 패킷의 페이로드가 비어있거나 0.0.0.0이면 DatagramPacket.address.hostAddress로 대체/저장합니다. 수신은 모든 인터페이스(0.0.0.0:CLIENT_INFO_PORT)에 바인딩합니다.

<h4> UDP 스트리밍 안정화 </h4>

- 프레임 분할/재조립: 한 프레임을 ≤ 60KB 청크로 분할하여 전송합니다. 헤더(12B)는 MAGIC('P2PS',4) | frameId(int32) | totalChunks(int16) | seq(int16) 구조(빅 엔디안)를 가집니다. 수신 측은 frameId를 기준으로 프레임을 재조립하여 완성될 때만 디코드를 진행합니다.

- 수신 소켓 단일 바인딩/재사용: DatagramSocket(null) 생성 후 reuseAddress=true를 설정하고, bind(0.0.0.0:STREAMING_PORT)를 한 번만 수행합니다. soTimeout=0(블로킹)으로 설정하여 불필요한 재바인딩을 방지합니다.

- 디코드 정확도: BitmapFactory.decodeByteArray(dp.data, 0, dp.length)를 사용하여 실제 수신된 데이터 길이만큼만 정확히 디코드합니다.

- 로그/계측 강화: 전송/수신 프레임 카운터, 바이트/청크 수, 엔드포인트 IP:PORT 등 상세한 로그를 추가하여 디버깅 효율을 높입니다.
