package ru.mobile.mys.p2p

import ru.mobile.mys.crypto.RatchetState

/** Хранилище бесед P2P-режима (vault в :app, память в тестах). */
interface P2PStore {
    /** Найти или завести беседу по room_id. */
    fun conversationFor(roomId: ByteArray): Long
    fun setPrs(conversationId: Long, prs: ByteArray)
    /** (room_id, prs) для реконнекта без фразы; null — фраза не сохранена. */
    fun roomAndPrs(conversationId: Long): kotlin.Pair<ByteArray, ByteArray>?
    fun session(conversationId: Long): SessionStore
}

/** Хранилище одной беседы для [Session]. */
interface SessionStore {
    fun loadRatchet(): RatchetState?
    fun saveRatchet(state: RatchetState)
    fun addOutgoing(body: ByteArray, status: String, kind: String = "text", filename: String? = null, mimeType: String? = null): Long
    fun setStatus(messageId: Long, status: String)
    /** Входящее сообщение и новое состояние ratchet — атомарно; вернуть id сообщения. */
    fun receive(body: ByteArray, state: RatchetState, kind: String, filename: String?, mimeType: String?): Long
}
