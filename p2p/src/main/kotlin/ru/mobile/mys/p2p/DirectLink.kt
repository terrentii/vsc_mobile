package ru.mobile.mys.p2p

/**
 * Попытка прямого канала для одной сессии (WebRTC DataChannel). Сигналинг —
 * непрозрачные JSON-строки, идут внутри E2E-сессии ([Session.sendSignal]).
 * Реализации: org.webrtc (Android, :app), aiortc (десктоп).
 */
interface DirectLink {
    /** INITIATOR сразу создаёт offer; RESPONDER ждёт offer в [onSignal]. */
    fun start(initiator: Boolean, signal: (String) -> Unit, opened: (Transport) -> Unit, closed: () -> Unit)

    fun onSignal(json: String)

    /** INITIATOR: новая попытка (новый PeerConnection и offer). */
    fun restart()

    fun close()
}
