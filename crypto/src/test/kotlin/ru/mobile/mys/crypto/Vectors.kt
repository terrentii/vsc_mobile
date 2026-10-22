package ru.mobile.mys.crypto

import org.json.JSONArray
import org.json.JSONObject

object Vectors {
    val root: JSONObject by lazy {
        JSONObject(Vectors::class.java.getResource("/vectors.json")!!.readText())
    }
    fun obj(name: String): JSONObject = root.getJSONObject(name)
    fun list(name: String): List<JSONObject> = root.getJSONArray(name).objects()
}

fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
fun String.unhex(): ByteArray = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
fun JSONObject.bytes(key: String): ByteArray = getString(key).unhex()
