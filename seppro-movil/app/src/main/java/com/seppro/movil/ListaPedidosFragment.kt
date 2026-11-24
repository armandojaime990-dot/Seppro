package com.seppro.movil

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
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
 * Base de PedidosFragment e HistorialFragment.
 *
 * Práctica 12 — optimización O1: carga los pedidos por páginas de 20 (filtro de la pestaña aplicado
 * en el servidor) y agrega "Cargar más". Antes descargaba TODOS los pedidos (cortados en 1 000 por
 * el servidor), los filtraba en el teléfono y creaba una tarjeta por cada uno en el hilo principal.
 */
abstract class ListaPedidosFragment : Fragment() {

    protected abstract val titulo: String
    protected abstract val textoVacio: String
    protected abstract val filtro: PedidosApi.Filtro
    protected open val onVerificar: ((Int) -> Unit)? = null

    /** Abre el formulario de reporte de falla con el pedido ya elegido (Práctica 7). */
    private fun reportarFalla(pedidoId: Int) {
        val ctx = context ?: return
        startActivity(NuevoReporteActivity.intent(ctx, pedidoId = pedidoId, tipo = "falla"))
    }

    private var lista: LinearLayout? = null
    private var mensaje: TextView? = null
    private var contador: TextView? = null
    private var botonMas: Button? = null

    // Estado de la paginación
    private var cargados = 0
    private var total: Int? = null
    private var cargando = false
    /** Cada "Actualizar" invalida las respuestas de cargas anteriores que lleguen tarde. */
    private var generacion = 0

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
            setPadding(0, 0, 0, ctx.dp(4))
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
            setOnClickListener { cargar(reiniciar = true) }
        })
        contenido.addView(encabezado)

        contador = TextView(ctx).apply {
            textSize = 13f
            setTextColor(COLOR_TEXTO_SUAVE)
            setPadding(0, 0, 0, ctx.dp(12))
        }
        contenido.addView(contador)

        mensaje = TextView(ctx).apply {
            textSize = 14f
            setTextColor(0xFF94A3B8.toInt())
            gravity = Gravity.CENTER
            setPadding(0, ctx.dp(24), 0, ctx.dp(24))
        }
        contenido.addView(mensaje)

        lista = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        contenido.addView(lista)

        botonMas = ctx.botonSecundario("Cargar más") { cargar(reiniciar = false) }.apply { visible(false) }
        contenido.addView(botonMas, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, ctx.dp(48)).apply { topMargin = ctx.dp(4) })

        return ScrollView(ctx).apply {
            setBackgroundColor(0xFFFFFFFF.toInt())
            addView(contenido)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        cargar(reiniciar = true)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        generacion++ // descarta respuestas pendientes
        lista = null
        mensaje = null
        contador = null
        botonMas = null
    }

    private fun cargar(reiniciar: Boolean) {
        if (cargando && !reiniciar) return // evita pedir la misma página dos veces
        if (reiniciar) {
            generacion++
            cargados = 0
            total = null
            lista?.removeAllViews()
            contador?.text = "" // no mostrar el conteo anterior mientras la lista está vacía
            mensaje?.apply { text = "Cargando..."; visibility = View.VISIBLE }
        }
        cargando = true
        botonMas?.apply { isEnabled = false; text = "Cargando..." }
        val miGeneracion = generacion
        val offset = cargados

        val t0 = SystemClock.elapsedRealtime()
        thread {
            try {
                val pagina = PedidosApi.obtenerPagina(filtro, offset)
                val t1 = SystemClock.elapsedRealtime()
                val pedidos = (0 until pagina.pedidos.length()).map { pagina.pedidos.getJSONObject(it) }
                val t2 = SystemClock.elapsedRealtime()
                activity?.runOnUiThread {
                    if (miGeneracion != generacion) return@runOnUiThread // respuesta vieja: se ignora
                    val t3 = SystemClock.elapsedRealtime()
                    mostrar(pedidos, pagina.total)
                    val t4 = SystemClock.elapsedRealtime()
                    // Práctica 12: tiempos de la operación (red, procesamiento, construcción de vistas en el hilo principal)
                    medirDibujo(titulo, t0, t1 - t0, t2 - t1, t4 - t3, pedidos.size, PedidosApi.ultimosBytes)
                }
            } catch (e: SesionExpiradaException) {
                activity?.runOnUiThread { sesionExpirada() }
            } catch (e: Exception) {
                android.util.Log.w("SEPPRO_Pedidos", "Error al cargar pedidos: ${e.javaClass.simpleName}")
                activity?.runOnUiThread {
                    if (miGeneracion != generacion) return@runOnUiThread
                    cargando = false
                    if (cargados == 0) {
                        mostrarMensaje("Error de red. Toca Actualizar.")
                        botonMas?.visible(false)
                    } else {
                        botonMas?.apply { isEnabled = true; text = "Reintentar cargar más" }
                    }
                }
            }
        }
    }

    private fun mostrar(pedidos: List<JSONObject>, totalServidor: Int?) {
        val l = lista ?: return // la vista ya no existe
        val ctx = context ?: return
        cargando = false
        total = totalServidor
        if (cargados == 0 && pedidos.isEmpty()) {
            mostrarMensaje(textoVacio)
            contador?.text = ""
            botonMas?.visible(false)
            return
        }
        mensaje?.visibility = View.GONE
        pedidos.forEach { l.addView(crearTarjetaPedido(ctx, it, onVerificar, ::reportarFalla)) }
        cargados += pedidos.size

        val t = total
        contador?.text = if (t != null) "Mostrando $cargados de $t pedidos" else "Mostrando $cargados pedidos"
        val hayMas = if (t != null) cargados < t else pedidos.size == PedidosApi.TAMANO_PAGINA
        botonMas?.apply { visible(hayMas); isEnabled = true; text = "Cargar más" }
    }

    /** Registra en Logcat (SEPPRO_Perf) los tiempos cuando la lista ya se dibujó en pantalla. */
    private fun medirDibujo(op: String, inicio: Long, redMs: Long, procesoMs: Long, uiMs: Long, filas: Int, bytes: Int) {
        val l = lista ?: return
        l.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                l.viewTreeObserver.removeOnPreDrawListener(this)
                val total = SystemClock.elapsedRealtime() - inicio
                android.util.Log.i("SEPPRO_Perf", "op=$op red_ms=$redMs proceso_ms=$procesoMs " +
                    "ui_hilo_principal_ms=$uiMs total_hasta_dibujo_ms=$total filas=$filas bytes=$bytes vistas=${l.childCount}")
                return true
            }
        })
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
