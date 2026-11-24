package com.seppro.movil

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject

/**
 * Canal de Supabase Realtime para un reporte (Práctica 7).
 *
 * Abre un WebSocket (protocolo Phoenix) y se suscribe a:
 *  - INSERT en mensajes con reporte_id = X  -> mensaje nuevo en vivo
 *  - UPDATE en reportes con id = X          -> cambio de estado/agente en vivo
 * El JWT va en el join, así que Realtime aplica las mismas políticas RLS.
 * Si la conexión se cae, reintenta con espera creciente (2 s, 4 s, ... hasta 30 s).
 * Todos los avisos al Listener llegan en el hilo principal.
 */
class RealtimeCanal(private val reporteId: Int, private val listener: Listener) {

    interface Listener {
        fun onMensajeNuevo(mensaje: Mensaje)
        fun onReporteActualizado()
        /** conectado = true cuando el canal quedó suscrito (también tras reconectar). */
        fun onConexion(conectado: Boolean)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var socket: WebSocket? = null
    private var ref = 0
    private var activo = false
    private var intentos = 0
    private val topic = "realtime:reporte-$reporteId"

    private val heartbeat = object : Runnable {
        override fun run() {
            enviar("phoenix", "heartbeat", JSONObject())
            handler.postDelayed(this, 25_000)
        }
    }

    fun conectar() {
        if (activo) return
        activo = true
        abrir()
    }

    fun cerrar() {
        activo = false
        handler.removeCallbacksAndMessages(null)
        socket?.close(1000, "fin")
        socket = null
    }

    private fun abrir() {
        val token = SupabaseClient.accessToken ?: return
        val wsUrl = SupabaseClient.URL.replaceFirst("https://", "wss://") +
            "/realtime/v1/websocket?apikey=${SupabaseClient.ANON_KEY}&vsn=1.0.0"

        socket = Api.http.newWebSocket(Request.Builder().url(wsUrl).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val cambios = JSONArray()
                    .put(JSONObject().put("event", "INSERT").put("schema", "public")
                        .put("table", "mensajes").put("filter", "reporte_id=eq.$reporteId"))
                    .put(JSONObject().put("event", "UPDATE").put("schema", "public")
                        .put("table", "reportes").put("filter", "id=eq.$reporteId"))
                val payload = JSONObject()
                    .put("config", JSONObject()
                        .put("broadcast", JSONObject().put("self", false))
                        .put("presence", JSONObject().put("key", ""))
                        .put("postgres_changes", cambios))
                    .put("access_token", token)
                handler.post {
                    enviar(topic, "phx_join", payload)
                    handler.removeCallbacks(heartbeat)
                    handler.postDelayed(heartbeat, 25_000)
                }
            }

            // Se ignoran los avisos de sockets viejos (ya reemplazados o cancelados)
            override fun onMessage(webSocket: WebSocket, text: String) {
                handler.post { if (webSocket === socket) procesar(text) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                handler.post { if (webSocket === socket) caida() }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "Realtime desconectado: ${t.javaClass.simpleName}")
                handler.post { if (webSocket === socket) caida() }
            }
        })
    }

    private fun procesar(texto: String) {
        val msg = try { JSONObject(texto) } catch (e: Exception) { return }
        if (msg.optString("topic") != topic) return
        val payload = msg.optJSONObject("payload") ?: return

        when (msg.optString("event")) {
            "phx_reply" -> {
                // Respuesta al join
                if (payload.optString("status") == "ok" && payload.optJSONObject("response")?.has("postgres_changes") == true) {
                    intentos = 0
                    listener.onConexion(true)
                } else if (payload.optString("status") == "error") {
                    Log.w(TAG, "Realtime rechazó la suscripción")
                    listener.onConexion(false)
                }
            }
            "postgres_changes" -> {
                val data = payload.optJSONObject("data") ?: return
                val record = data.optJSONObject("record") ?: return
                when (data.optString("table")) {
                    "mensajes" -> try {
                        listener.onMensajeNuevo(Mensaje.desdeJson(record))
                    } catch (e: Exception) { /* registro incompleto: se ignora */ }
                    "reportes" -> listener.onReporteActualizado()
                }
            }
            "phx_error", "phx_close" -> caida()
        }
    }

    private fun caida() {
        handler.removeCallbacks(heartbeat)
        socket?.cancel()
        socket = null
        if (!activo) return
        listener.onConexion(false)
        intentos++
        val espera = esperaReintento(intentos)
        handler.postDelayed({ if (activo && socket == null) abrir() }, espera)
    }

    private fun enviar(topic: String, evento: String, payload: JSONObject) {
        ref++
        val json = JSONObject()
            .put("topic", topic)
            .put("event", evento)
            .put("payload", payload)
            .put("ref", ref.toString())
        if (evento == "phx_join") json.put("join_ref", ref.toString())
        socket?.send(json.toString())
    }

    companion object {
        private const val TAG = "SEPPRO_Realtime"

        /** Espera antes del reintento n (1, 2, 3...): 2 s, 4 s, 8 s, 16 s y luego 30 s como máximo. */
        fun esperaReintento(intento: Int): Long {
            if (intento < 1) return 2_000L
            return (2_000L shl (intento - 1).coerceAtMost(4)).coerceAtMost(30_000L)
        }
    }
}
