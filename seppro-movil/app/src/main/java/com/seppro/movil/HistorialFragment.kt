package com.seppro.movil

/**
 * Pestaña "Historial": pedidos cerrados
 * (entregado, entregado_verificado, entregado_con_danos).
 */
class HistorialFragment : ListaPedidosFragment() {

    override val titulo = "Historial"
    override val textoVacio = "Aún no tienes pedidos entregados."

    override fun incluir(estado: String) = estado in Estados.CERRADOS
}
