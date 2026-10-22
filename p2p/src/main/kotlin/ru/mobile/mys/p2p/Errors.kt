package ru.mobile.mys.p2p

open class P2PException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Неверная фраза или MITM: key-confirmation не сошёлся. */
class PakeException(message: String, cause: Throwable? = null) : P2PException(message, cause)
class RendezvousException(message: String, cause: Throwable? = null) : P2PException(message, cause)
class TransportException(message: String, cause: Throwable? = null) : P2PException(message, cause)

/** Собеседник не вошёл в комнату за отведённое время. */
class PeerUnavailable(message: String) : P2PException(message)
