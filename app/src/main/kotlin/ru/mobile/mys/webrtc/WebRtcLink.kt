package ru.mobile.mys.webrtc

import android.content.Context
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import ru.mobile.mys.p2p.DirectLink
import ru.mobile.mys.p2p.Transport
import ru.mobile.mys.p2p.TransportException

/**
 * Прямой канал WebRTC (org.webrtc) — DirectLink телефона. Формат сигналов общий с
 * десктопом (mys_decentralized/webrtc.py): offer/answer с SDP, trickle `cand`.
 * Все операции над PeerConnection — в одном потоке [exec]; каждая попытка
 * (offer у инициатора, приход offer у ответчика) — новый PeerConnection.
 */
class WebRtcLink(context: Context, private val iceServers: List<String>) : DirectLink {
    private val factory = factory(context)
    private val exec = Executors.newSingleThreadExecutor()
    private lateinit var signal: (String) -> Unit
    private lateinit var opened: (Transport) -> Unit
    private lateinit var closed: () -> Unit

    private var pc: PeerConnection? = null
    private var attempt = 0
    private var openedFor = -1
    private var closedFor = -1
    private var remoteSet = false
    private var localSent = false
    private val pendingRemote = mutableListOf<IceCandidate>() // чужие кандидаты до удалённого SDP
    private val pendingLocal = mutableListOf<String>() // свои — до отправки offer/answer

    override fun start(initiator: Boolean, signal: (String) -> Unit, opened: (Transport) -> Unit, closed: () -> Unit) {
        this.signal = signal
        this.opened = opened
        this.closed = closed
        if (initiator) exec.execute { offer() }
    }

    override fun onSignal(json: String) = exec.execute { handle(JSONObject(json)) }

    override fun restart() = exec.execute { offer() }

    override fun close() = exec.execute {
        attempt++ // колбэки старой попытки больше не действуют
        pc?.close()
        pc = null
    }

    private fun newPc(): PeerConnection {
        pc?.close()
        val my = ++attempt
        remoteSet = false
        localSent = false
        pendingRemote.clear()
        pendingLocal.clear()
        val config = PeerConnection.RTCConfiguration(iceServers.map { PeerConnection.IceServer.builder(it).createIceServer() })
            .apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN }
        val observer = object : PeerConnection.Observer {
            override fun onIceCandidate(c: IceCandidate) = exec.execute {
                if (my != attempt) return@execute
                val j = JSONObject().put("t", "cand").put("c", c.sdp).put("mid", c.sdpMid).put("idx", c.sdpMLineIndex).toString()
                if (localSent) signal(j) else pendingLocal += j
            }
            override fun onDataChannel(dc: DataChannel) = exec.execute { if (my == attempt) wire(dc, my) }
            override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
                if (state == PeerConnection.PeerConnectionState.FAILED || state == PeerConnection.PeerConnectionState.CLOSED) {
                    exec.execute { fireClosed(my) }
                }
            }
            override fun onSignalingChange(s: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {}
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {}
            override fun onIceCandidatesRemoved(cs: Array<out IceCandidate>) {}
            override fun onAddStream(s: MediaStream) {}
            override fun onRemoveStream(s: MediaStream) {}
            override fun onRenegotiationNeeded() {}
        }
        return checkNotNull(factory.createPeerConnection(config, observer)) { "PeerConnection не создан" }.also { pc = it }
    }

    private fun offer() {
        val p = newPc()
        val my = attempt
        wire(p.createDataChannel("mys", DataChannel.Init().apply { ordered = true }), my)
        p.createOffer(sdp(my, onCreate = { d -> p.setLocalDescription(sdp(my, onSet = { sendLocal("offer", d) }), d) }), MediaConstraints())
    }

    private fun handle(j: JSONObject) {
        when (j.optString("t")) {
            "offer" -> {
                val p = newPc()
                val my = attempt
                p.setRemoteDescription(sdp(my, onSet = {
                    remoteSet = true
                    pendingRemote.forEach { p.addIceCandidate(it) }
                    pendingRemote.clear()
                    p.createAnswer(sdp(my, onCreate = { d -> p.setLocalDescription(sdp(my, onSet = { sendLocal("answer", d) }), d) }), MediaConstraints())
                }), SessionDescription(SessionDescription.Type.OFFER, j.getString("sdp")))
            }
            "answer" -> pc?.let { p ->
                val my = attempt
                p.setRemoteDescription(sdp(my, onSet = {
                    remoteSet = true
                    pendingRemote.forEach { p.addIceCandidate(it) }
                    pendingRemote.clear()
                }), SessionDescription(SessionDescription.Type.ANSWER, j.getString("sdp")))
            }
            "cand" -> {
                val line = j.optString("c")
                if (line.isEmpty()) return
                val c = IceCandidate(j.optString("mid", "0"), j.optInt("idx", 0), line)
                if (remoteSet) pc?.addIceCandidate(c) else pendingRemote += c
            }
        }
    }

    /** Свой SDP ушёл — теперь можно слать накопленные trickle-кандидаты. */
    private fun sendLocal(type: String, d: SessionDescription) {
        signal(JSONObject().put("t", type).put("sdp", d.description).toString())
        localSent = true
        pendingLocal.forEach(signal)
        pendingLocal.clear()
    }

    private fun wire(dc: DataChannel, my: Int) {
        val transport = DataChannelTransport(dc)
        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previous: Long) {}
            override fun onStateChange() = exec.execute { onState(dc, transport, my) }
            override fun onMessage(buffer: DataChannel.Buffer) {
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes) // буфер переиспользуется — копируем сразу
                transport.deliver(bytes)
            }
        })
        onState(dc, transport, my) // канал ответчика может прийти уже открытым
    }

    private fun onState(dc: DataChannel, transport: DataChannelTransport, my: Int) {
        when (runCatching { dc.state() }.getOrNull()) {
            DataChannel.State.OPEN -> if (my == attempt && openedFor != my) {
                openedFor = my
                opened(transport)
            }
            DataChannel.State.CLOSED, null -> {
                transport.closed()
                fireClosed(my)
            }
            else -> Unit
        }
    }

    /** Один «closed» на попытку и только для текущей. */
    private fun fireClosed(my: Int) {
        if (my != attempt || closedFor == my) return
        closedFor = my
        closed()
    }

    private fun sdp(my: Int, onCreate: (SessionDescription) -> Unit = {}, onSet: () -> Unit = {}) = object : SdpObserver {
        override fun onCreateSuccess(d: SessionDescription) = exec.execute { if (my == attempt) onCreate(d) }
        override fun onSetSuccess() = exec.execute { if (my == attempt) onSet() }
        override fun onCreateFailure(error: String?) = exec.execute { fireClosed(my) }
        override fun onSetFailure(error: String?) = exec.execute { fireClosed(my) }
    }

    companion object {
        @Volatile private var shared: PeerConnectionFactory? = null

        /** Одна фабрика на процесс (инициализация libwebrtc — однократно). */
        @Synchronized fun factory(context: Context): PeerConnectionFactory = shared ?: run {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions(),
            )
            PeerConnectionFactory.builder().createPeerConnectionFactory().also { shared = it }
        }
    }
}

/** Транспорт поверх DataChannel: один wire-кадр = одно бинарное сообщение. */
class DataChannelTransport(private val dc: DataChannel) : Transport {
    private val inbox = Channel<ByteArray>(Channel.UNLIMITED)

    internal fun deliver(bytes: ByteArray) { inbox.trySend(bytes) }

    internal fun closed() { inbox.close() }

    override suspend fun send(data: ByteArray) {
        try {
            if (dc.state() != DataChannel.State.OPEN) throw TransportException("прямой канал закрыт")
            while (dc.bufferedAmount() > MAX_BUFFERED) delay(10) // файл — сотни кадров подряд
            if (!dc.send(DataChannel.Buffer(ByteBuffer.wrap(data), true))) throw TransportException("прямой канал не принял кадр")
        } catch (e: IllegalStateException) {
            throw TransportException("прямой канал закрыт", e) // PeerConnection уже закрыт
        }
    }

    override suspend fun recv(): ByteArray =
        try { inbox.receive() } catch (e: ClosedReceiveChannelException) { throw TransportException("прямой канал закрыт") }

    override suspend fun close() {
        inbox.close()
        runCatching { dc.close() }
    }

    private companion object {
        const val MAX_BUFFERED = 4L * 1024 * 1024
    }
}
