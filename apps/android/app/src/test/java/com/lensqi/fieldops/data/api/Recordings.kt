package com.lensqi.fieldops.data.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** Responses recorded from the real API by scripts/record_api.py (fixtures/api-recordings). */
object Recordings {
    val dir: File = File(requireNotNull(System.getProperty("recordings.dir")) { "recordings.dir system property not set" })

    data class Call(val file: String, val method: String, val path: String, val status: Int, val label: String)

    val calls: List<Call> by lazy {
        Json.parseToJsonElement(File(dir, "index.json").readText()).jsonObject["calls"]!!.jsonArray.map {
            val o = it.jsonObject
            Call(o["file"]!!.jsonPrimitive.content, o["method"]!!.jsonPrimitive.content, o["path"]!!.jsonPrimitive.content,
                o["status"]!!.jsonPrimitive.content.toInt(), o["label"]!!.jsonPrimitive.content)
        }
    }

    fun text(name: String): String = File(dir, calls.single { it.file.endsWith("-$name.json") }.file).readText()
    fun call(name: String): Call = calls.single { it.file.endsWith("-$name.json") }
    fun named(prefix: String): List<Call> = calls.filter { it.file.substringAfter('-').startsWith(prefix) }
    fun obj(name: String): JsonObject = Json.parseToJsonElement(text(name)).jsonObject
    fun array(name: String): JsonArray = Json.parseToJsonElement(text(name)).jsonArray
}
