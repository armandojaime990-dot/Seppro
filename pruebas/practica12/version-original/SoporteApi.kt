package com.seppro.movil

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlin.concurrent.thread

/** Reporte de soporte (tabla reportes). */
data class Reporte(
    val id: Int,
    val folio: String,
    val tipo: String,
    val pedidoId: Int?,
    val equipo: String,
    val descripcion: String,
    val prioridad: String,
    val estado: String,
    val clienteId: String,
    val clienteNombre: String,
    val agenteId: String?,
    val agenteNombre: String?,
    val paraAdmin: Boolean,
    val creado: String
) {
    val activo: Boolean get() = estado == "abierto" || estado == "en_proceso"

    companion object {
        fun desdeJson(j: JSONObject) = Reporte(
            id = j.getInt("id"),
            folio = j.optString("folio"),
            tipo = j.optString("tipo"),
            pedidoId = if (j.isNull("pedido_id")) null else j.optInt("pedido_id"),
            equipo = j.optString("equipo").takeUnless { j.isNull("equipo") }.orEmpty(),
            descripcion = j.optString("descripcion"),
            prioridad = j.optString("prioridad", "media"),
            estado = j.optString("estado", "abierto"),
            clienteId = j.optString("cliente_id"),
            clienteNombre = j.optJSONObject("cliente")?.optString("nombre").orEmpty(),
            agenteId = if (j.isNull("agente_id")) null else j.optString("agente_id"),
            agenteNombre = j.optJSONObject("agente")?.optString("nombre"),
            paraAdmin = j.optBoolean("para_admin", false),
            creado = j.optString("created_at")
        )
    }
}

/** Mensaje de la conversación de un reporte (tabla mensajes). */
data class Mensaje(
    val id: Long,
    val emisorId: String,
    val emisorNombre: String,
    val emisorRol: String,
    val dispositivo: String,
    val texto: String,
    val adjunto: String?,
    val creado: String
) {
    companion object {
        fun desdeJson(j: JSONObject) = Mensaje(
            id = j.getLong("id"),
            emisorId = j.optString("emisor_id"),
            emisorNombre = j.optString("emisor_nombre").takeUnless { j.isNull("emisor_nombre") } ?: "Usuario",
            emisorRol = j.optString("emisor_rol").takeUnless { j.isNull("emisor_rol") } ?: Roles.USUARIO,
            dispositivo = j.optString("dispositivo").takeUnless { j.isNull("dispositivo") } ?: "Desconocido",
            texto = j.optString("texto"),
            adjunto = if (j.isNull("adjunto_path")) null else j.optString("adjunto_path"),
            creado = j.optString("created_at")
        )
    }
}

/** Agente (o admin) con su estado de conexión, para reasignar. */
data class AgenteEstado(val id: String, val nombre: String, val rol: String, val enLinea: Boolean, val activos: Int)

/**
 * Práctica 7: acceso a reportes, mensajes y evidencias en Supabase.
 * Lo que cada rol puede ver o cambiar lo decide el servidor (RLS + triggers de supabase-soporte.sql).
 * Se llama desde un hilo secundario.
 */
object SoporteApi {

    private const val SELECT_REPORTE =
        "*,cliente:perfiles!reportes_cliente_id_fkey(nombre),agente:perfiles!reportes_agente_id_fkey(nombre)"

    const val BUCKET = "evidencias"
    const val URL_FIRMADA_SEGUNDOS = 600 // la URL de la foto expira en 10 minutos

    // ===== Reportes =====

    /** RLS ya filtra: el cliente ve los suyos, el agente los asignados y el admin todos. */
    fun reportes(): List<Reporte> {
        val arr = JSONArray(Api.get("/rest/v1/reportes?select=$SELECT_REPORTE&order=updated_at.desc"))
        return (0 until arr.length()).map { Reporte.desdeJson(arr.getJSONObject(it)) }
    }

    fun reporte(id: Int): Reporte? {
        val arr = JSONArray(Api.get("/rest/v1/reportes?select=$SELECT_REPORTE&id=eq.$id"))
        return if (arr.length() == 0) null else Reporte.desdeJson(arr.getJSONObject(0))
    }

    fun crearReporte(tipo: String, pedidoId: Int?, equipo: String, descripcion: String,
                     prioridad: String, paraAdmin: Boolean): Reporte {
        val cuerpo = JSONObject().apply {
            put("tipo", tipo)
            put("pedido_id", pedidoId ?: JSONObject.NULL)
            put("equipo", equipo.ifBlank { null } ?: JSONObject.NULL)
            put("descripcion", descripcion)
            put("prioridad", prioridad)
            put("para_admin", paraAdmin)
        }
        val arr = JSONArray(Api.post("/rest/v1/reportes?select=id", cuerpo.toString(),
            mapOf("Prefer" to "return=representation")))
        return reporte(arr.getJSONObject(0).getInt("id"))
            ?: throw ApiException(404, "No se pudo leer el reporte creado")
    }

    fun actualizarReporte(id: Int, cambios: JSONObject) {
        Api.patch("/rest/v1/reportes?id=eq.$id", cambios.toString())
    }

    fun reasignar(reporteId: Int, agenteId: String?) {
        Api.rpc("reasignar_reporte", JSONObject().apply {
            put("p_reporte", reporteId)
            put("p_agente", agenteId ?: JSONObject.NULL)
        })
    }

    fun agentes(): List<AgenteEstado> {
        val arr = JSONArray(Api.rpc("agentes_estado"))
        return (0 until arr.length()).map {
            val j = arr.getJSONObject(it)
            AgenteEstado(j.getString("id"), j.optString("nombre"), j.optString("rol"),
                j.optBoolean("en_linea"), j.optInt("reportes_activos"))
        }
    }

    // ===== Mensajes =====

    fun mensajes(reporteId: Int): List<Mensaje> {
        val arr = JSONArray(Api.get("/rest/v1/mensajes?select=*&reporte_id=eq.$reporteId&order=id.asc"))
        return (0 until arr.length()).map { Mensaje.desdeJson(arr.getJSONObject(it)) }
    }

    fun enviarMensaje(reporteId: Int, texto: String, adjunto: String? = null): Mensaje {
        val cuerpo = JSONObject().apply {
            put("reporte_id", reporteId)
            put("texto", texto)
            put("dispositivo", nombreDispositivo())
            put("adjunto_path", adjunto ?: JSONObject.NULL)
        }
        val arr = JSONArray(Api.post("/rest/v1/mensajes", cuerpo.toString(),
            mapOf("Prefer" to "return=representation")))
        return Mensaje.desdeJson(arr.getJSONObject(0))
    }

    // ===== Evidencias (Supabase Storage, bucket privado) =====

    /** Sube la foto a evidencias/<reporte>/<uuid>.jpg y devuelve la ruta. */
    fun subirEvidencia(reporteId: Int, jpeg: ByteArray): String {
        val ruta = "$reporteId/${UUID.randomUUID()}.jpg"
        Api.subir("/storage/v1/object/$BUCKET/$ruta", jpeg, "image/jpeg")
        return ruta
    }

    /** URL firmada (preasignada) que expira: la foto nunca queda pública. */
    fun urlFirmada(ruta: String): String {
        val resp = JSONObject(Api.post("/storage/v1/object/sign/$BUCKET/$ruta",
            JSONObject().put("expiresIn", URL_FIRMADA_SEGUNDOS).toString()))
        val firmada = resp.optString("signedURL", resp.optString("signedUrl"))
        if (firmada.isBlank()) throw ApiException(500, "No se pudo generar el enlace")
        return if (firmada.startsWith("http")) firmada else "${SupabaseClient.URL}/storage/v1$firmada"
    }

    // ===== Utilidades =====

    /** Nombre del teléfono o emulador (para identificar cada dispositivo en el chat). */
    fun nombreDispositivo(): String {
        val fabricante = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val modelo = Build.MODEL
        val nombre = if (modelo.startsWith(fabricante, ignoreCase = true)) modelo else "$fabricante $modelo"
        return nombre.take(80)
    }

    private val formatoServidor = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX"
    )

    /** "2026-11-10T18:04:05.123456+00:00" -> hora local "12:04" (o fecha si no es de hoy). */
    fun horaLocal(iso: String, conFecha: Boolean = false): String {
        // Recortar microsegundos a milisegundos para SimpleDateFormat
        val limpio = iso.replace(Regex("(\\.\\d{3})\\d+"), "$1")
        for (patron in formatoServidor) {
            try {
                val p = SimpleDateFormat(patron, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                val fecha = p.parse(limpio) ?: continue
                val salida = if (conFecha) "dd/MM/yyyy HH:mm" else "HH:mm"
                return SimpleDateFormat(salida, Locale.getDefault()).format(fecha)
            } catch (e: Exception) { /* probar el siguiente formato */ }
        }
        return iso.take(16).replace('T', ' ')
    }

    fun etiquetaTipo(tipo: String) = when (tipo) {
        "falla" -> "Reporte de falla"
        "seguimiento" -> "Seguimiento de entrega"
        "acceso" -> "Recuperación de acceso"
        else -> tipo
    }

    fun etiquetaEstado(estado: String) = when (estado) {
        "abierto" -> "Abierto"
        "en_proceso" -> "En proceso"
        "resuelto" -> "Resuelto"
        "cerrado" -> "Cerrado"
        else -> estado
    }

    fun colorEstado(estado: String): Int = when (estado) {
        "abierto" -> 0xFFFFA000.toInt()
        "en_proceso" -> 0xFF2196F3.toInt()
        "resuelto" -> 0xFF2E7D32.toInt()
        else -> COLOR_TEXTO_SUAVE
    }

    fun etiquetaPrioridad(p: String) = when (p) {
        "alta" -> "Alta"
        "baja" -> "Baja"
        else -> "Media"
    }

    fun colorPrioridad(p: String): Int = when (p) {
        "alta" -> COLOR_ERROR
        "baja" -> COLOR_TEXTO_SUAVE
        else -> 0xFFFFA000.toInt()
    }
}

/**
 * Presencia del agente/admin: mientras la app está en primer plano envía un
 * "latido" cada minuto. Si deja de llegar por 2 minutos, el servidor lo considera
 * desconectado y el administrador puede reasignar sus reportes.
 */
object Presencia {
    private val handler = Handler(Looper.getMainLooper())
    private var pantallasVisibles = 0
    private const val INTERVALO_MS = 60_000L

    private val latido = object : Runnable {
        override fun run() {
            if (SupabaseClient.accessToken != null && Roles.esStaff(SupabaseClient.rol)) {
                thread {
                    try { Api.rpc("latido") } catch (e: Exception) {
                        Log.w("SEPPRO_Presencia", "Latido no enviado: ${e.javaClass.simpleName}")
                    }
                }
            }
            handler.postDelayed(this, INTERVALO_MS)
        }
    }

    /** Llamar en onStart de cada Activity. */
    fun activar() {
        pantallasVisibles++
        if (pantallasVisibles == 1) {
            handler.removeCallbacks(latido)
            handler.post(latido)
        }
    }

    /** Llamar en onStop de cada Activity. */
    fun pausar() {
        pantallasVisibles = (pantallasVisibles - 1).coerceAtLeast(0)
        if (pantallasVisibles == 0) handler.removeCallbacks(latido)
    }

    /** Al cerrar sesión: avisar de inmediato que ya no está conectado. */
    fun desconectar(alTerminar: () -> Unit) {
        handler.removeCallbacks(latido)
        thread {
            try {
                Api.rpc("desconectar")
                Api.post("/auth/v1/logout", "{}")
            } catch (e: Exception) {
                Log.w("SEPPRO_Presencia", "Cierre de sesión sin conexión: ${e.javaClass.simpleName}")
            }
            handler.post(alTerminar)
        }
    }
}
