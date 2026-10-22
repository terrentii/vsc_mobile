package ru.mobile.mys.central

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject

/** HTTP-контракт сервера и маппинг статусов в ошибки. О хранилище не знает. */
class RestClient(baseUrl: String, var token: String? = null, private val http: OkHttpClient = OkHttpClient()) {
    val baseUrl = baseUrl.trimEnd('/')
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    suspend fun register(username: String, password: String) = session(post("/api/auth/register", credentials(username, password), auth = false))
    suspend fun login(username: String, password: String) = session(post("/api/auth/login", credentials(username, password), auth = false))

    suspend fun logout() {
        try { post("/api/auth/logout", JSONObject()) } finally { token = null }
    }

    suspend fun listRooms(): List<Room> {
        val o = get("/api/rooms")
        return parse("rooms") {
            val arr = o.getJSONArray("rooms")
            List(arr.length()) { parseRoom(arr.getJSONObject(it)) }
        }
    }

    suspend fun createRoom(name: String): Room = post("/api/rooms", JSONObject().put("name", name)).let { parse("room") { parseRoom(it) } }

    /** Войти по 10-значному коду. ServerException: bad_request / not_found / forbidden. */
    suspend fun joinRoom(code: String): Room = post("/api/rooms/join", JSONObject().put("code", code)).let { parse("room") { parseRoom(it) } }

    /** (сообщения, next_cursor). */
    suspend fun getMessages(roomId: Long, after: Long? = null, limit: Int? = null): Pair<List<RemoteMessage>, Long?> {
        val u = "$baseUrl/api/rooms/$roomId/messages".toHttpUrl().newBuilder().apply {
            after?.let { addQueryParameter("after", it.toString()) }
            limit?.let { addQueryParameter("limit", it.toString()) }
        }.build()
        val o = json(call(Request.Builder().url(u)))
        return parse("messages") {
            val arr = o.getJSONArray("messages")
            List(arr.length()) { parseMessage(arr.getJSONObject(it), roomId) } to
                (if (o.has("next_cursor") && !o.isNull("next_cursor")) o.getLong("next_cursor") else null)
        }
    }

    suspend fun postMessage(roomId: Long, body: String, clientMsgId: String, media: String? = null, replyTo: Long? = null): RemoteMessage {
        val p = JSONObject().put("room_id", roomId).put("body", body).put("client_msg_id", clientMsgId)
        media?.let { p.put("media", it) }
        replyTo?.let { p.put("reply_to", it) }
        return post("/api/messages", p).let { parse("message") { parseMessage(it, roomId) } }
    }

    suspend fun editMessage(messageId: Long, body: String) { post("/api/messages/$messageId/edit", JSONObject().put("body", body)) }

    suspend fun deleteMessage(messageId: Long) { post("/api/messages/$messageId/delete", JSONObject()) }

    suspend fun uploadMedia(roomId: Long, filename: String, data: ByteArray, mimeType: String): UploadedMedia {
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", filename, data.toRequestBody(mimeType.toMediaTypeOrNull())).build()
        val o = json(call(Request.Builder().url("$baseUrl/api/rooms/$roomId/media").post(form)))
        return parse("upload") { UploadedMedia(o.getString("filename"), o.str("mime_type"), if (o.has("size")) o.getLong("size") else null) }
    }

    suspend fun downloadMedia(roomId: Long, filename: String): ByteArray =
        call(Request.Builder().url("$baseUrl/api/rooms/$roomId/media/$filename"))

    private fun credentials(u: String, p: String) = JSONObject().put("username", u).put("password", p)

    private fun session(o: JSONObject): Session = parse("auth") {
        val user = o.getJSONObject("user")
        Session(baseUrl, user.getString("username"), user.getLong("id"), o.getString("token")).also { token = it.token }
    }

    private suspend fun get(path: String) = json(call(Request.Builder().url(baseUrl + path)))

    private suspend fun post(path: String, body: JSONObject, auth: Boolean = true) =
        json(call(Request.Builder().url(baseUrl + path).post(body.toString().toRequestBody(jsonType)), auth))

    // ponytail: блокирующий execute на Dispatchers.IO — отмена корутины не рвёт запрос;
    // при нужде — okhttp Call.enqueue + suspendCancellableCoroutine.
    private suspend fun call(b: Request.Builder, auth: Boolean = true): ByteArray = withContext(Dispatchers.IO) {
        if (auth) token?.let { b.header("Authorization", "Bearer $it") }
        val resp = try { http.newCall(b.build()).execute() } catch (e: IOException) {
            throw NetworkException(e.message ?: "сеть недоступна", e)
        }
        resp.use { r ->
            val bytes = r.body?.bytes() ?: ByteArray(0)
            when {
                r.code == 401 -> throw AuthException()
                r.code >= 500 -> throw NetworkException("server status ${r.code}")
                r.code >= 400 -> throw ServerException(runCatching { JSONObject(bytes.decodeToString()).str("error") }.getOrNull() ?: "http ${r.code}")
            }
            bytes
        }
    }

    private fun json(b: ByteArray): JSONObject =
        try { JSONObject(b.decodeToString()) } catch (e: JSONException) { throw ProtocolException("invalid json in response", e) }

    private inline fun <T> parse(what: String, block: () -> T): T =
        try { block() } catch (e: JSONException) { throw ProtocolException("malformed $what response", e) }
}
