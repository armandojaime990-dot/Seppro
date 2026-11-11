package com.seppro.movil

import org.json.JSONArray

/**
 * Consulta de pedidos del usuario (misma petición que el reloj).
 * Se filtra por usuario_id porque, por RLS, el administrador ve los pedidos de todos.
 * Se llama desde un hilo secundario (thread { }).
 */
object PedidosApi {

    fun obtenerPedidos(): JSONArray {
        val uid = SupabaseClient.userId ?: throw SesionExpiradaException()
        return JSONArray(Api.get(
            "/rest/v1/pedidos?select=*,pedido_items(*)&usuario_id=eq.$uid&order=created_at.desc"
        ))
    }
}
