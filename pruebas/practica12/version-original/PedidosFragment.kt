package com.seppro.movil

/**
 * Pestaña "Pedidos": pedidos activos del cliente (todo menos los cerrados).
 * En un pedido "en_puerta" aparece el botón "Verificar paquete".
 */
class PedidosFragment : ListaPedidosFragment() {

    override val titulo = "Mis pedidos"
    override val textoVacio = "No tienes pedidos activos."

    override fun incluir(estado: String) = estado !in Estados.CERRADOS

    override val onVerificar: ((Int) -> Unit) = { pedidoId ->
        (activity as? MainActivity)?.abrirVerificar(pedidoId)
    }
}
