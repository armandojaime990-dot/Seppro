package com.seppro.movil

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * Base de PedidosFragment e HistorialFragment: descarga los pedidos del
 * usuario y muestra solo los que cumplen el filtro de cada pestaña.
 */
abstract class ListaPedidosFragment : Fragment() {

    protected abstract val titulo: String
    protected abstract val textoVacio: String
    protected abstract fun incluir(estado: String): Boolean
    protected open val onVerificar: ((Int) -> Unit)? = null

    /** Abre el formulario de reporte de falla con el pedido ya elegido (Práctica 7). */
    private fun reportarFalla(pedidoId: Int) {
        val ctx = context ?: return
        startActivity(NuevoReporteActivity.intent(ctx, pedidoId = pedidoId, tipo = "falla"))
    }

    private var lista: LinearLayout? = null
    private var mensaje: TextView? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()

        val contenido = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(16), ctx.dp(16), ctx.dp(16), ctx.dp(16))
        }

        // Encabezado: título + botón Actualizar
        val encabezado = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, ctx.dp(12))
        }
        encabezado.addView(TextView(ctx).apply {
            text = titulo
            textSize = 20f
            setTextColor(COLOR_PRIMARY)
            setTypeface(null, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        encabezado.addView(Button(ctx).apply {
            text = "Actualizar"
            isAllCaps = false
            setTextColor(COLOR_ACCENT)
            background = fondoRedondeado(ctx, COLOR_GRIS_CLARO, 8)
            setPadding(ctx.dp(12), 0, ctx.dp(12), 0)
            setOnClickListener { cargar() }
        })
        contenido.addView(encabezado)

        mensaje = TextView(ctx).apply {
            textSize = 14f
            setTextColor(0xFF94A3B8.toInt())
            gravity = Gravity.CENTER
            setPadding(0, ctx.dp(24), 0, ctx.dp(24))
        }
        contenido.addView(mensaje)

        lista = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        contenido.addView(lista)

        return ScrollView(ctx).apply {
            setBackgroundColor(0xFFFFFFFF.toInt())
            addView(contenido)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        cargar()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        lista = null
        mensaje = null
    }

    private fun cargar() {
        mensaje?.apply { text = "Cargando..."; visibility = View.VISIBLE }
        lista?.removeAllViews()

        thread {
            try {
                val pedidos = PedidosApi.obtenerPedidos()
                val filtrados = mutableListOf<JSONObject>()
                for (i in 0 until pedidos.length()) {
                    val pedido = pedidos.getJSONObject(i)
                    if (incluir(pedido.optString("estado", "pendiente").lowercase())) filtrados.add(pedido)
                }
                activity?.runOnUiThread { mostrar(filtrados) }
            } catch (e: SesionExpiradaException) {
                activity?.runOnUiThread { sesionExpirada() }
            } catch (e: Exception) {
                android.util.Log.w("SEPPRO_Pedidos", "Error al cargar pedidos: ${e.javaClass.simpleName}")
                activity?.runOnUiThread { mostrarMensaje("Error de red. Toca Actualizar.") }
            }
        }
    }

    private fun mostrar(pedidos: List<JSONObject>) {
        val l = lista ?: return // la vista ya no existe
        val ctx = context ?: return
        l.removeAllViews()
        if (pedidos.isEmpty()) {
            mostrarMensaje(textoVacio)
            return
        }
        mensaje?.visibility = View.GONE
        pedidos.forEach { l.addView(crearTarjetaPedido(ctx, it, onVerificar, ::reportarFalla)) }
    }

    private fun mostrarMensaje(texto: String) {
        mensaje?.apply { text = texto; visibility = View.VISIBLE }
    }

    private fun sesionExpirada() {
        val act = activity ?: return
        SupabaseClient.cerrarSesion()
        startActivity(Intent(act, LoginActivity::class.java))
        act.finish()
    }
}
