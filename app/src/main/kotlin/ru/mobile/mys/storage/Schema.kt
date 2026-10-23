package ru.mobile.mys.storage

import android.content.ContentValues
import android.database.Cursor
import net.zetetic.database.sqlcipher.SQLiteDatabase

/*
 * Схема = итоговая схема десктопа (mys_storage/schema.py, main v7) одной миграцией:
 * БД телефона с десктопом не обменивается. Следующие изменения — новым элементом списка.
 */
internal val MIGRATIONS: List<List<String>> = listOf(
    listOf(
        "CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
        "CREATE TABLE conversations (" +
            " id INTEGER PRIMARY KEY, mode TEXT NOT NULL, room_id BLOB, title TEXT," +
            " created_at INTEGER NOT NULL, p2p_prs BLOB)",
        "CREATE TABLE messages (" +
            " id INTEGER PRIMARY KEY," +
            " conversation_id INTEGER NOT NULL REFERENCES conversations(id)," +
            " direction TEXT NOT NULL, body BLOB, status TEXT NOT NULL," +
            " kind TEXT NOT NULL DEFAULT 'text', filename TEXT, mime_type TEXT," +
            " sender TEXT, wire_seq INTEGER, client_msg_id TEXT, media_ref TEXT," +
            " reply_author TEXT, reply_snippet TEXT, sent_at INTEGER, received_at INTEGER)",
        "CREATE INDEX idx_messages_conv ON messages(conversation_id, id)",
        "CREATE INDEX idx_messages_conv_wire ON messages(conversation_id, wire_seq)",
        "CREATE TABLE ratchet_state (" +
            " conversation_id INTEGER PRIMARY KEY REFERENCES conversations(id)," +
            " state_blob BLOB NOT NULL, updated_at INTEGER NOT NULL)",
    ),
    // v2: закрепление — только на этом телефоне, с сервером и десктопом не синхронизируется
    listOf("ALTER TABLE conversations ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0"),
)

internal fun migrate(db: SQLiteDatabase) {
    for (v in db.version until MIGRATIONS.size) db.tx {
        MIGRATIONS[v].forEach { db.execSQL(it) }
        db.version = v + 1
    }
}

internal inline fun <T> SQLiteDatabase.tx(block: () -> T): T {
    beginTransaction()
    try {
        return block().also { setTransactionSuccessful() }
    } finally {
        endTransaction()
    }
}

// rawQuery(String, Object...) — Java varargs: массив без spread ушёл бы ОДНИМ аргументом.
internal fun <T> SQLiteDatabase.select(sql: String, vararg args: Any?, row: (Cursor) -> T): List<T> =
    rawQuery(sql, *args).use { c -> buildList { while (c.moveToNext()) add(row(c)) } }

internal fun SQLiteDatabase.insertRow(table: String, vararg values: Pair<String, Any?>): Long {
    val cv = ContentValues()
    for ((k, v) in values) when (v) {
        null -> cv.putNull(k)
        is ByteArray -> cv.put(k, v)
        is String -> cv.put(k, v)
        is Long -> cv.put(k, v)
        is Int -> cv.put(k, v)
        else -> error("неподдерживаемый тип ${v::class}")
    }
    return insert(table, SQLiteDatabase.CONFLICT_ABORT, cv).also { check(it != -1L) { "insert в $table" } }
}

internal fun Cursor.blobOrNull(i: Int): ByteArray? = if (isNull(i)) null else getBlob(i)
internal fun Cursor.stringOrNull(i: Int): String? = if (isNull(i)) null else getString(i)
internal fun Cursor.longOrNull(i: Int): Long? = if (isNull(i)) null else getLong(i)
