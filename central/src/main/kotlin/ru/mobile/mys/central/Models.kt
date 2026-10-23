package ru.mobile.mys.central

import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import org.json.JSONObject

open class CentralException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Неверные учётные данные или протухший токен (HTTP 401) — терминально. */
class AuthException(message: String = "unauthorized") : CentralException(message)

/** 4xx кроме 401; [code] — код ошибки сервера: not_found, forbidden, rate_limited… */
class ServerException(val code: String) : CentralException(code)

/** Сеть недоступна, обрыв, 5xx. */
class NetworkException(message: String, cause: Throwable? = null) : CentralException(message, cause)

/** Ответ сервера не по контракту. */
class ProtocolException(message: String, cause: Throwable? = null) : CentralException(message, cause)

class Session(val serverUrl: String, val username: String, val userId: Long, val token: String)

class Room(
    val id: Long, val name: String?, val isDirect: Boolean = false, val updatedAt: String? = null, val code: String? = null,
    val meta: RoomMeta? = null,
)

class Member(val login: String, val role: String)

/** Комната глазами пользователя (vsc_web/dialogs.py room_view): диалог, название, аватар, собеседник. */
data class RoomMeta(
    val id: Long, val code: String?, val name: String?, val isDirect: Boolean, val isOpen: Boolean,
    val isDialog: Boolean, val dialogProposedBy: String?, val pendingInvite: Boolean, val role: String?,
    val title: String?, val avatarRev: Int, val peer: String?, val peerRead: Long?, val peerOnline: Boolean?,
    val members: Int, val memberList: List<Member>? = null,
)

/** Готовая цитата от сервера: {id, sender, body ≤ 60}. */
class Reply(val id: Long, val sender: String, val body: String)

class RemoteMessage(
    val id: Long, val roomId: Long, val sender: String, val body: String, val createdAt: String,
    val clientMsgId: String? = null, val media: String? = null, val reply: Reply? = null,
)

class UploadedMedia(val filename: String, val mimeType: String?, val size: Long?)

internal fun JSONObject.str(key: String): String? = if (!has(key) || isNull(key)) null else getString(key)

fun parseRoom(o: JSONObject) =
    Room(o.getLong("id"), o.str("name"), o.optBoolean("is_direct", false), o.str("updated_at"), o.str("code"), parseMeta(o))

fun parseMeta(o: JSONObject) = RoomMeta(
    id = o.getLong("id"), code = o.str("code"), name = o.str("name"), isDirect = o.optBoolean("is_direct", false),
    isOpen = o.optBoolean("is_open", true), isDialog = o.optBoolean("is_dialog", false),
    dialogProposedBy = o.str("dialog_proposed_by"), pendingInvite = o.optBoolean("pending_invite", false),
    role = o.str("role"), title = o.str("title"), avatarRev = o.optInt("avatar_rev", 0), peer = o.str("peer"),
    peerRead = if (o.has("peer_read") && !o.isNull("peer_read")) o.getLong("peer_read") else null,
    peerOnline = if (o.has("peer_online") && !o.isNull("peer_online")) o.getBoolean("peer_online") else null,
    members = o.optInt("members", 0),
    memberList = o.optJSONArray("member_list")?.let { a -> List(a.length()) { Member(a.getJSONObject(it).getString("login"), a.getJSONObject(it).optString("role")) } },
)

fun parseMessage(o: JSONObject, roomId: Long) = RemoteMessage(
    id = o.getLong("id"),
    roomId = if (o.has("room_id") && !o.isNull("room_id")) o.getLong("room_id") else roomId,
    sender = o.getString("sender"),
    body = o.str("body") ?: "",
    createdAt = o.getString("created_at"),
    clientMsgId = o.str("client_msg_id"),
    media = o.str("media"),
    reply = o.optJSONObject("reply_to")?.let { Reply(it.getLong("id"), it.str("sender") ?: "", it.str("body") ?: "") },
)

/** `https://host` → `wss://host/ws`; нераспознанная схема — null (без live-канала). */
fun wsUrlFor(serverUrl: String): String? {
    for ((http, ws) in listOf("https://" to "wss://", "http://" to "ws://")) {
        if (serverUrl.startsWith(http)) return ws + serverUrl.removePrefix(http).trimEnd('/') + "/ws"
    }
    return null
}

/** Серверный ISO `created_at` (наивный = UTC) → epoch ms; непарсибельное — null. */
fun epochMs(createdAt: String): Long? =
    runCatching { OffsetDateTime.parse(createdAt).toInstant().toEpochMilli() }.getOrNull()
        ?: runCatching { LocalDateTime.parse(createdAt).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()

const val MAX_MEDIA_SIZE = 50 * 1024 * 1024 // защита памяти телефона; сервер лимитов не держит

private val IMAGE_EXTS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "tiff", "tif")

fun kindForFilename(name: String) = if (name.substringAfterLast('.', "").lowercase() in IMAGE_EXTS) "image" else "file"

/** Серверное имя `<uuid32hex>_<original>` → отображаемое (паритет с вебом). */
fun displayName(mediaRef: String) = mediaRef.substringAfter('_', "").ifEmpty { mediaRef }
