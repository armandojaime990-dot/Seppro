package com.seppro.movil

import org.json.JSONArray

/** Una página de pedidos y el total que existe en la base de datos (cabecera Content-Range). */
class PaginaPedidos(val pedidos: JSONArray, val total: Int?)

/**
 * Consulta de pedidos del usuario.
 *
 * Práctica 12 — optimización O1 (antes: una sola petición con select=* y SIN límite, que el
 * servidor cortaba en 1 000 filas sin avisar; 512 KB y ~900 ms para un cliente con 3 005 pedidos):
 *  - Paginación: 20 pedidos por página (limit/offset) y botón "Cargar más".
 *  - Solo los campos que usa la tarjeta (id, total, estado, fecha y nombre del producto).
 *  - El filtro de la pestaña (activos / historial) lo aplica el servidor, no la app.
 *  - Orden estable (created_at, id) para que las páginas no repitan ni salten pedidos.
 * Se filtra por usuario_id porque, por RLS, el administrador ve los pedidos de todos.
 * Se llama desde un hilo secundario (thread { }).
 */
object PedidosApi {

    const val TAMANO_PAGINA = 20
    private const val CAMPOS = "id,total,estado,created_at,pedido_items(nombre_producto)"
    private val CERRADOS = Estados.CERRADOS.joinToString(",")

    /** Práctica 12: tamaño de la última respuesta (para la instrumentación SEPPRO_Perf). */
    @Volatile var ultimosBytes = 0
        private set

    /** Filtro de PostgREST por pestaña. */
    enum class Filtro(val consulta: String) {
        ACTIVOS("estado=not.in.($CERRADOS)"),
        HISTORIAL("estado=in.($CERRADOS)"),
        TODOS("")
    }

    fun obtenerPagina(filtro: Filtro, offset: Int, limite: Int = TAMANO_PAGINA): PaginaPedidos {
        val uid = SupabaseClient.userId ?: throw SesionExpiradaException()
        val ruta = buildString {
            append("/rest/v1/pedidos?select=$CAMPOS&usuario_id=eq.$uid")
            if (filtro.consulta.isNotEmpty()) append("&").append(filtro.consulta)
            append("&order=created_at.desc,id.desc&limit=$limite&offset=$offset")
        }
        val r = Api.getConTotal(ruta)
        ultimosBytes = r.cuerpo.toByteArray().size
        return PaginaPedidos(JSONArray(r.cuerpo), r.total)
    }

    /** Todos los pedidos (sin paginar), para el selector del formulario de reportes. */
    fun obtenerPedidos(): JSONArray = obtenerPagina(Filtro.TODOS, 0, 100).pedidos
}
