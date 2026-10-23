package ru.mobile.mys.mesh

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import org.json.JSONObject
import ru.mobile.mys.crypto.Ristretto
import ru.mobile.mys.p2p.Transport
import ru.mobile.mys.p2p.TransportException

val sodium by lazy { LazySodiumJava(SodiumJava()) }
val crypto by lazy { LinkCrypto(sodium, sodium) }
val ristretto by lazy { Ristretto(sodium) }

object Vectors {
    val root: JSONObject by lazy { JSONObject(Vectors::class.java.getResource("/vectors.json")!!.readText()) }
}

fun String.unhex(): ByteArray = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

/** MeshTransport → Transport P2P-сессии (в этапе 2 — в P2PService). */
class Adapter(private val t: MeshTransport) : Transport {
    override suspend fun send(data: ByteArray) = try { t.send(data) } catch (e: MeshClosed) { throw TransportException(e.message ?: "mesh") }
    override suspend fun recv(): ByteArray = try { t.recv() } catch (e: MeshClosed) { throw TransportException(e.message ?: "mesh") }
    override suspend fun close() = t.close()
}
