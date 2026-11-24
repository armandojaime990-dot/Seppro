package com.seppro.movil

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException

// =====================================================================================
// Entregable 2: verificación de la entrega con el Smart Tag.
//   1. El teléfono se conecta al Smart Tag por GATT y lee la telemetría (paquete + estatus).
//   2. Llama a la RPC verificar_entrega de Supabase (API REST del backend existente).
//   3. El ViewModel publica cada paso en un StateFlow y avisa el resultado al reloj.
// Entregable 3: si hubo golpe -> "Alerta de integridad" con opción de rechazar el equipo;
// si no -> garantía activada y certificado enviado al correo del cliente.
// =====================================================================================

/** Respuesta de verificar_entrega. */
data class ResultadoVerificacion(
    val pedidoId: Int,
    val estado: String,
    val golpe: Boolean,
    val garantiaFolio: String?,
    val garantiaVence: String?,
    val repetido: Boolean
) {
    companion object {
        fun desdeJson(j: JSONObject) = ResultadoVerificacion(
            pedidoId = j.optInt("pedido_id"),
            estado = j.optString("estado"),
            golpe = j.optBoolean("golpe"),
            garantiaFolio = j.optString("garantia_folio").takeUnless { j.isNull("garantia_folio") || it.isBlank() },
            garantiaVence = j.optString("garantia_vence").takeUnless { j.isNull("garantia_vence") || it.isBlank() },
            repetido = j.optBoolean("repetido")
        )
    }
}

/** Respuesta de resolver_alerta. */
data class ResultadoAlerta(val pedidoId: Int, val estado: String, val reporteId: Int?, val reporteFolio: String?) {
    companion object {
        fun desdeJson(j: JSONObject) = ResultadoAlerta(
            pedidoId = j.optInt("pedido_id"),
            estado = j.optString("estado"),
            reporteId = if (j.isNull("reporte_id") || !j.has("reporte_id")) null else j.optInt("reporte_id"),
            reporteFolio = j.optString("reporte_folio").takeUnless { j.isNull("reporte_folio") || it.isBlank() }
        )
    }
}

/** Fuente de la telemetría: el Smart Tag real (GATT) o el simulador. */
interface LectorTelemetria {
    suspend fun leer(direccion: String): Telemetria
}

/** Operaciones del backend (Supabase) que usa la verificación. */
interface RepositorioVerificacion {
    suspend fun verificar(pedidoId: Int, t: Telemetria, sensor: String?): ResultadoVerificacion
    suspend fun resolverAlerta(pedidoId: Int, accion: String, motivo: String?): ResultadoAlerta
    /** true = correo enviado; false = el servicio de correo no está disponible. */
    suspend fun enviarCertificado(pedidoId: Int): Boolean
}

/** Aviso del resultado al reloj (Wear OS Data Layer). */
fun interface NotificadorReloj {
    fun notificar(pedidoId: Int, estado: String, detalle: String)
}

/** Implementación real con la API REST de Supabase (Api.kt). */
object VerificacionApi : RepositorioVerificacion {

    override suspend fun verificar(pedidoId: Int, t: Telemetria, sensor: String?) = withContext(Dispatchers.IO) {
        ResultadoVerificacion.desdeJson(JSONObject(Api.rpc("verificar_entrega", JSONObject().apply {
            put("p_pedido", pedidoId)
            put("p_paquete", t.paqueteId)
            put("p_estatus", t.estatus)
            put("p_sensor", sensor ?: JSONObject.NULL)
        })))
    }

    override suspend fun resolverAlerta(pedidoId: Int, accion: String, motivo: String?) = withContext(Dispatchers.IO) {
        ResultadoAlerta.desdeJson(JSONObject(Api.rpc("resolver_alerta", JSONObject().apply {
            put("p_pedido", pedidoId)
            put("p_accion", accion)
            put("p_motivo", motivo ?: JSONObject.NULL)
        })))
    }

    override suspend fun enviarCertificado(pedidoId: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            Api.post("/functions/v1/enviar-certificado", JSONObject().put("pedido_id", pedidoId).toString())
            true
        } catch (e: ApiException) {
            false // 404/503: función no desplegada o correo sin configurar; la garantía ya existe
        }
    }
}

/** Lector de demostración: simula el Smart Tag cuando no hay hardware a la mano. */
class SimuladorTag(private val pedidoId: Int, private val estatus: Int, private val esperaMs: Long = 1_200) : LectorTelemetria {
    override suspend fun leer(direccion: String): Telemetria {
        delay(esperaMs) // tiempo típico de conexión + lectura GATT
        return TelemetriaTag.leer(TelemetriaTag.codificar(Telemetria("SEPPRO-$pedidoId", estatus)))
    }
}

/** Estados de la pantalla Verificar. */
sealed class EstadoVerificacion {
    object Inactivo : EstadoVerificacion()
    data class Conectando(val dispositivo: String) : EstadoVerificacion()
    data class Registrando(val telemetria: Telemetria) : EstadoVerificacion()
    data class Verificado(val resultado: ResultadoVerificacion, val correoEnviado: Boolean?) : EstadoVerificacion()
    data class Alerta(val pedidoId: Int, val telemetria: Telemetria?) : EstadoVerificacion()
    data class Resolviendo(val pedidoId: Int, val accion: String) : EstadoVerificacion()
    data class Resuelto(val resultado: ResultadoAlerta) : EstadoVerificacion()
    data class Error(val mensaje: String, val sesionExpirada: Boolean = false) : EstadoVerificacion()
}

/**
 * ViewModel con StateFlow (Entregable 2). Sobrevive a la rotación: la lectura GATT y la llamada
 * al servidor no se repiten si la pantalla se recrea.
 */
class VerificacionViewModel(
    private val repositorio: RepositorioVerificacion,
    private val reloj: NotificadorReloj
) : ViewModel() {

    private val _estado = MutableStateFlow<EstadoVerificacion>(EstadoVerificacion.Inactivo)
    val estado: StateFlow<EstadoVerificacion> = _estado.asStateFlow()

    private var trabajo: Job? = null

    val ocupado: Boolean
        get() = _estado.value is EstadoVerificacion.Conectando ||
            _estado.value is EstadoVerificacion.Registrando || _estado.value is EstadoVerificacion.Resolviendo

    /** Lee el Smart Tag y registra el resultado en Supabase. */
    fun verificar(pedidoId: Int, lector: LectorTelemetria, direccion: String, nombreSensor: String?) {
        if (ocupado) return // evita dobles toques
        if (pedidoId <= 0) {
            _estado.value = EstadoVerificacion.Error("Elige un pedido \"En puerta\" en la pestaña Pedidos")
            return
        }
        trabajo = viewModelScope.launch {
            _estado.value = EstadoVerificacion.Conectando(nombreSensor ?: direccion)
            try {
                val t = lector.leer(direccion)
                if (!TelemetriaTag.correspondeAPedido(t, pedidoId)) {
                    _estado.value = EstadoVerificacion.Error(
                        "Este Smart Tag pertenece al paquete ${t.paqueteId}, no al pedido #$pedidoId")
                    return@launch
                }
                _estado.value = EstadoVerificacion.Registrando(t)
                val r = repositorio.verificar(pedidoId, t, nombreSensor ?: direccion)
                if (r.golpe) {
                    _estado.value = EstadoVerificacion.Alerta(pedidoId, t)
                    reloj.notificar(pedidoId, r.estado, "Golpe fuerte detectado: revisa el equipo antes de aceptarlo")
                } else {
                    _estado.value = EstadoVerificacion.Verificado(r, correoEnviado = null)
                    reloj.notificar(pedidoId, r.estado, "Entrega verificada. Garantía ${r.garantiaFolio ?: "activada"}")
                    val enviado = try { repositorio.enviarCertificado(pedidoId) } catch (e: IOException) { false }
                    _estado.value = EstadoVerificacion.Verificado(r, correoEnviado = enviado)
                }
            } catch (e: Exception) {
                _estado.value = error(e)
            }
        }
    }

    fun aceptarConDanos(pedidoId: Int) = resolver(pedidoId, "aceptar", null)

    fun rechazarEquipo(pedidoId: Int, motivo: String?) = resolver(pedidoId, "rechazar", motivo?.trim()?.take(500))

    private fun resolver(pedidoId: Int, accion: String, motivo: String?) {
        if (ocupado) return
        trabajo = viewModelScope.launch {
            _estado.value = EstadoVerificacion.Resolviendo(pedidoId, accion)
            try {
                val r = repositorio.resolverAlerta(pedidoId, accion, motivo)
                _estado.value = EstadoVerificacion.Resuelto(r)
                reloj.notificar(pedidoId, r.estado,
                    if (accion == "rechazar") "Equipo rechazado. Reporte ${r.reporteFolio ?: "creado"}"
                    else "Equipo aceptado con daños")
            } catch (e: Exception) {
                _estado.value = error(e)
            }
        }
    }

    fun reiniciar() {
        trabajo?.cancel()
        _estado.value = EstadoVerificacion.Inactivo
    }

    private fun error(e: Exception): EstadoVerificacion.Error = when (e) {
        is SesionExpiradaException -> EstadoVerificacion.Error("Tu sesión expiró. Inicia sesión de nuevo.", sesionExpirada = true)
        is TelemetriaInvalidaException -> EstadoVerificacion.Error("Lectura inválida del Smart Tag: ${e.message}")
        is TimeoutCancellationException -> EstadoVerificacion.Error("El Smart Tag no respondió. Acércate a la caja e intenta de nuevo.")
        is ApiException -> EstadoVerificacion.Error(e.message ?: "Error del servidor")
        is SecurityException -> EstadoVerificacion.Error("Falta el permiso de Bluetooth para conectarse al sensor")
        is IOException -> EstadoVerificacion.Error("Sin conexión. Revisa tu Internet e intenta de nuevo.")
        else -> EstadoVerificacion.Error(e.message ?: "Algo salió mal")
    }
}
