package ru.mobile.mys.storage

import ru.mobile.mys.crypto.RatchetState
import ru.mobile.mys.p2p.P2PStore
import ru.mobile.mys.p2p.SessionStore

/** P2P-беседы в зашифрованном vault (режим "decentralized", как у десктопа). */
class VaultP2PStore(private val vault: Vault) : P2PStore {
    override fun conversationFor(roomId: ByteArray): Long =
        vault.conversations.getByRoomId(roomId, MODE)?.id ?: vault.conversations.add(MODE, roomId)

    override fun setPrs(conversationId: Long, prs: ByteArray) = vault.conversations.setPrs(conversationId, prs)

    override fun roomAndPrs(conversationId: Long): Pair<ByteArray, ByteArray>? {
        val c = vault.conversations.get(conversationId) ?: return null
        val room = c.roomId ?: return null
        val prs = c.p2pPrs ?: return null
        return room to prs
    }

    override fun session(conversationId: Long): SessionStore = object : SessionStore {
        override fun loadRatchet() = vault.ratchet.load(conversationId)
        override fun saveRatchet(state: RatchetState) = vault.ratchet.save(conversationId, state)
        override fun addOutgoing(body: ByteArray, status: String, kind: String, filename: String?, mimeType: String?) =
            vault.messages.add(conversationId, "out", body, status, kind, filename, mimeType)
        override fun setStatus(messageId: Long, status: String) = vault.messages.setStatus(messageId, status)
        override fun receive(body: ByteArray, state: RatchetState, kind: String, filename: String?, mimeType: String?) =
            vault.receiveMessage(conversationId, body, state, kind, filename, mimeType)
    }

    private companion object {
        const val MODE = "decentralized"
    }
}
