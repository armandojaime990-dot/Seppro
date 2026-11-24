package com.seppro.movil

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** El token ya no es válido (HTTP 401): hay que volver a iniciar sesión. */
class SesionExpiradaException : Exception("Sesión expirada")

/** Error que devolvió Supabase (RLS, trigger, validación) con un mensaje para mostrar. */
class ApiException(val codigo: Int, mensaje: String) : IOException(mensaje)

/**
 * Peticiones HTTPS a Supabase (REST, RPC y Storage) con el JWT del usuario.
 * Siempre se llama desde un hilo secundario (thread { }).
 */
object Api {

    /** Un solo cliente para toda la app (también lo usa el WebSocket de Realtime). */
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json".toMediaType()

    fun get(ruta: String): String = request("GET", ruta, null)

    fun post(ruta: String, cuerpo: String, extra: Map<String, String> = emptyMap()): String =
        request("POST", ruta, cuerpo.toRequestBody(JSON), extra)

    fun patch(ruta: String, cuerpo: String): String =
        request("PATCH", ruta, cuerpo.toRequestBody(JSON), mapOf("Prefer" to "return=representation"))

    /** Llama a una función SQL: POST /rest/v1/rpc/<nombre> */
    fun rpc(nombre: String, params: JSONObject = JSONObject()): String =
        post("/rest/v1/rpc/$nombre", params.toString())

    /** Sube un archivo binario a Supabase Storage. */
    fun subir(ruta: String, bytes: ByteArray, contentType: String): String =
        request("POST", ruta, bytes.toRequestBody(contentType.toMediaType()), mapOf("x-upsert" to "false"))

    fun enc(valor: String): String = URLEncoder.encode(valor, "UTF-8")

    private fun request(metodo: String, ruta: String, cuerpo: RequestBody?, extra: Map<String, String> = emptyMap()): String {
        val token = SupabaseClient.accessToken ?: throw SesionExpiradaException()
        val builder = Request.Builder()
            .url(SupabaseClient.URL + ruta)
            .method(metodo, cuerpo)
            .header("apikey", SupabaseClient.ANON_KEY)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
        extra.forEach { (k, v) -> builder.header(k, v) }

        http.newCall(builder.build()).execute().use { resp ->
            val texto = resp.body?.string().orEmpty()
            if (resp.code == 401) throw SesionExpiradaException()
            if (!resp.isSuccessful) throw ApiException(resp.code, mensajeDeError(texto, resp.code))
            return texto
        }
    }

    /** Toma el "message" de PostgREST/Storage (p. ej. el RAISE EXCEPTION de un trigger). */
    private fun mensajeDeError(cuerpo: String, codigo: Int): String {
        val msg = try {
            val json = JSONObject(cuerpo)
            json.optString("message", json.optString("error", ""))
        } catch (e: Exception) {
            ""
        }
        // Storage responde 400/403 con "jwt expired" cuando vence el token
        if (msg.contains("jwt", ignoreCase = true) && msg.contains("expired", ignoreCase = true)) {
            throw SesionExpiradaException()
        }
        return msg.ifBlank { "Error del servidor ($codigo)" }
    }
}
