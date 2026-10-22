package ru.mobile.mys.central

/** Сессия и настройки «Центра» в зашифрованном хранилище — ключи как у десктопа. */
internal object Account {
    const val K_SERVER = "central.server_url"
    const val K_USER = "central.username"
    const val K_UID = "central.user_id"
    const val K_TOKEN = "central.token"
    const val K_WIPE = "central.wipe_on_logout"
    const val CURSOR_PREFIX = "central.cursor."
    const val CODE_PREFIX = "central.code."

    fun save(store: CentralStore, s: Session) {
        store.setSetting(K_SERVER, s.serverUrl)
        store.setSetting(K_USER, s.username)
        store.setSetting(K_UID, s.userId.toString())
        store.setSetting(K_TOKEN, s.token)
    }

    fun load(store: CentralStore): Session? {
        val token = store.setting(K_TOKEN) ?: return null
        return Session(store.setting(K_SERVER) ?: return null, store.setting(K_USER) ?: return null,
            store.setting(K_UID)?.toLongOrNull() ?: return null, token)
    }

    fun clear(store: CentralStore) = listOf(K_SERVER, K_USER, K_UID, K_TOKEN).forEach(store::deleteSetting)

    /** Беседы, сообщения, курсоры и коды «Центра»; сессию чистит [clear]. */
    fun wipeCache(store: CentralStore) {
        store.wipeConversations()
        store.deleteSettings(CURSOR_PREFIX)
        store.deleteSettings(CODE_PREFIX)
    }
}
