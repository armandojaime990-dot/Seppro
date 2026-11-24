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

    /** URL del servidor. Las pruebas unitarias la cambian por un servidor simulado (MockWebServer). */
    internal var baseUrl: String = SupabaseClient.URL

    fun get(ruta: String): String = request("GET", ruta, null).cuerpo

    /** Respuesta con el total de filas (Prefer: count=exact -> Content-Range "0-19/3005"). */
    class ConTotal(val cuerpo: String, val total: Int?)

    fun getConTotal(ruta: String): ConTotal {
        val r = request("GET", ruta, null, mapOf("Prefer" to "count=exact"))
        val total = r.contentRange?.substringAfter('/', "")?.toIntOrNull()
        return ConTotal(r.cuerpo, total)
    }

    fun post(ruta: String, cuerpo: String, extra: Map<String, String> = emptyMap()): String =
        request("POST", ruta, cuerpo.toRequestBody(JSON), extra).cuerpo

    fun patch(ruta: String, cuerpo: String): String =
        request("PATCH", ruta, cuerpo.toRequestBody(JSON), mapOf("Prefer" to "return=representation")).cuerpo

    /** Llama a una función SQL: POST /rest/v1/rpc/<nombre> */
    fun rpc(nombre: String, params: JSONObject = JSONObject()): String =
        post("/rest/v1/rpc/$nombre", params.toString())

    /** Sube un archivo binario a Supabase Storage. */
    fun subir(ruta: String, bytes: ByteArray, contentType: String): String =
        request("POST", ruta, bytes.toRequestBody(contentType.toMediaType()), mapOf("x-upsert" to "false")).cuerpo

    fun enc(valor: String): String = URLEncoder.encode(valor, "UTF-8")

    private class Respuesta(val cuerpo: String, val contentRange: String?)

    private fun request(metodo: String, ruta: String, cuerpo: RequestBody?, extra: Map<String, String> = emptyMap()): Respuesta {
        val token = SupabaseClient.accessToken ?: throw SesionExpiradaException()
        val builder = Request.Builder()
            .url(baseUrl + ruta)
            .method(metodo, cuerpo)
            .header("apikey", SupabaseClient.ANON_KEY)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
        extra.forEach { (k, v) -> builder.header(k, v) }

        http.newCall(builder.build()).execute().use { resp ->
            val texto = resp.body?.string().orEmpty()
            if (resp.code == 401) throw SesionExpiradaException()
            if (!resp.isSuccessful) throw ApiException(resp.code, mensajeDeError(texto, resp.code))
            return Respuesta(texto, resp.header("Content-Range"))
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
