package com.seppro.movil

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread

/**
 * Práctica 7: formulario para abrir un reporte de soporte.
 * Casos: falla (bomba dañada / Alerta de integridad), seguimiento de la entrega
 * y recuperación de acceso. Si quien lo abre es un agente, es una consulta al administrador.
 */
class NuevoReporteActivity : AppCompatActivity() {

    private lateinit var tipoSpinner: Spinner
    private lateinit var pedidoSpinner: Spinner
    private lateinit var equipoInput: EditText
    private lateinit var descripcionInput: EditText
    private lateinit var prioridadSpinner: Spinner
    private lateinit var errorText: TextView
    private lateinit var enviarBtn: View

    private var paraAdmin = false
    /** Pedido del cliente en el selector. */
    private data class PedidoOpcion(val id: Int, val texto: String, val estado: String, val producto: String)

    /** null = "Sin pedido". */
    private val pedidos = mutableListOf<PedidoOpcion?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (SupabaseClient.accessToken == null) { irAlLogin(); return }

        // El administrador no crea reportes; el agente solo consulta al administrador
        if (SupabaseClient.rol == Roles.ADMIN) { finish(); return }
        paraAdmin = SupabaseClient.rol == Roles.AGENTE
        val tipoInicial = intent.getStringExtra(EXTRA_TIPO) ?: "falla"

        val contenido = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }

        contenido.addView(TextView(this).apply {
            text = if (paraAdmin)
                "Tu consulta llegará directamente al administrador y te responderá en el chat."
            else
                "Un agente de atención revisará tu reporte y te responderá en el chat."
            textSize = 14f
            setTextColor(COLOR_TEXTO_SUAVE)
        })

        // Tipo, pedido y equipo solo aplican al cliente (la consulta del agente no lleva pedido)
        val camposCliente = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        camposCliente.addView(etiqueta("Tipo de reporte"))
        tipoSpinner = selector(TIPOS.map { SoporteApi.etiquetaTipo(it) })
        tipoSpinner.setSelection(TIPOS.indexOf(tipoInicial).coerceAtLeast(0))
        camposCliente.addView(tipoSpinner, anchoCompleto())

        camposCliente.addView(etiqueta("Pedido relacionado"))
        pedidoSpinner = selector(listOf("Cargando pedidos..."))
        camposCliente.addView(pedidoSpinner, anchoCompleto())

        camposCliente.addView(etiqueta("Equipo (obligatorio en reportes de falla)"))
        equipoInput = campo("Ej. Bomba Altamira KOR10", 120)
        camposCliente.addView(equipoInput, anchoCompleto())
        if (paraAdmin) camposCliente.visibility = View.GONE
        contenido.addView(camposCliente, anchoCompleto())

        contenido.addView(etiqueta(if (paraAdmin) "Tu pregunta (mínimo $MIN_DESC caracteres)"
            else "Descripción (mínimo $MIN_DESC caracteres)"))
        descripcionInput = campo(if (paraAdmin) "Ej. ¿Autorizo el cambio de equipo del pedido #16?"
            else "Describe qué pasó: estado de la caja, ruidos, golpes visibles...", MAX_DESC, multilinea = true)
        contenido.addView(descripcionInput, anchoCompleto())

        contenido.addView(etiqueta("Prioridad"))
        prioridadSpinner = selector(PRIORIDADES.map { SoporteApi.etiquetaPrioridad(it) })
        prioridadSpinner.setSelection(1) // media
        contenido.addView(prioridadSpinner, anchoCompleto())

        errorText = TextView(this).apply {
            textSize = 14f
            setTextColor(COLOR_ERROR)
            setPadding(0, dp(12), 0, 0)
        }
        contenido.addView(errorText)

        enviarBtn = botonPrincipal(if (paraAdmin) "Enviar consulta" else "Crear reporte") { enviar() }
        contenido.addView(enviarBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(12) })

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        root.addView(barraConRegreso(if (paraAdmin) "Consulta al administrador" else "Nuevo reporte"),
            anchoCompleto())
        root.addView(ScrollView(this).apply { addView(contenido) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        if (!paraAdmin) cargarPedidos(intent.getIntExtra(EXTRA_PEDIDO, 0))
    }

    override fun onStart() { super.onStart(); Presencia.activar() }
    override fun onStop() { super.onStop(); Presencia.pausar() }

    private fun cargarPedidos(preseleccion: Int) {
        thread {
            try {
                val arr = PedidosApi.obtenerPedidos()
                val lista = (0 until arr.length()).map { i ->
                    val p = arr.getJSONObject(i)
                    val id = p.optInt("id")
                    val estado = p.optString("estado", "pendiente")
                    val items = p.optJSONArray("pedido_items")
                    val producto = if (items != null && items.length() > 0)
                        items.getJSONObject(0).optString("nombre_producto", "") else ""
                    PedidoOpcion(id, "Pedido #$id · ${Estados.etiqueta(estado)}" +
                        if (producto.isNotBlank()) " · $producto" else "", estado, producto)
                }
                runOnUiThread { mostrarPedidos(lista, preseleccion) }
            } catch (e: SesionExpiradaException) {
                runOnUiThread { irAlLogin() }
            } catch (e: Exception) {
                Log.w(TAG, "No se cargaron los pedidos: ${e.javaClass.simpleName}")
                runOnUiThread { mostrarPedidos(emptyList(), 0) }
            }
        }
    }

    private fun mostrarPedidos(lista: List<PedidoOpcion>, preseleccion: Int) {
        if (isFinishing) return
        pedidos.clear()
        pedidos.add(null)
        pedidos.addAll(lista)
        pedidoSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            pedidos.map { it?.texto ?: "Sin pedido" })

        // Al elegir un pedido con daño detectado por el sensor, sugerir equipo y prioridad alta
        pedidoSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val p = pedidos.getOrNull(pos) ?: return
                if (p.estado in Estados.CON_DANO) prioridadSpinner.setSelection(2)
                if (equipoInput.text.isBlank() && p.producto.isNotBlank()) equipoInput.setText(p.producto.take(120))
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val indice = pedidos.indexOfFirst { it?.id == preseleccion }
        if (indice > 0) pedidoSpinner.setSelection(indice)
    }

    private fun enviar() {
        val tipo = if (paraAdmin) "consulta" else TIPOS[tipoSpinner.selectedItemPosition]
        val pedido = if (paraAdmin) null else pedidos.getOrNull(pedidoSpinner.selectedItemPosition)
        val equipo = if (paraAdmin) "" else equipoInput.text.toString().trim()
        val descripcion = descripcionInput.text.toString().trim()
        val prioridad = PRIORIDADES[prioridadSpinner.selectedItemPosition]

        // Validación de campos (RQ de la Práctica 8). El servidor vuelve a validar con CHECK.
        val error = when {
            descripcion.isEmpty() -> "Escribe una descripción"
            descripcion.length < MIN_DESC -> "La descripción debe tener al menos $MIN_DESC caracteres"
            tipo == "falla" && equipo.isEmpty() -> "Indica el equipo dañado"
            tipo == "falla" && pedido == null -> "Elige el pedido del equipo dañado"
            else -> null
        }
        if (error != null) {
            errorText.text = error
            return
        }

        errorText.text = ""
        enviarBtn.isEnabled = false
        thread {
            try {
                val reporte = SoporteApi.crearReporte(tipo, pedido?.id, equipo, descripcion, prioridad, paraAdmin)
                Log.i(TAG, "Reporte creado ${reporte.folio}")
                runOnUiThread {
                    val aviso = when {
                        paraAdmin -> "Consulta ${reporte.folio} enviada al administrador"
                        reporte.agenteId == null -> "Reporte ${reporte.folio} creado. No hay agentes conectados: el administrador lo asignará."
                        else -> "Reporte ${reporte.folio} creado y asignado a ${reporte.agenteNombre ?: "un agente"}"
                    }
                    Toast.makeText(this, aviso, Toast.LENGTH_LONG).show()
                    startActivity(ReporteActivity.intent(this, reporte.id))
                    finish()
                }
            } catch (e: SesionExpiradaException) {
                runOnUiThread { irAlLogin() }
            } catch (e: ApiException) {
                runOnUiThread {
                    errorText.text = "No se pudo crear el reporte: ${e.message}"
                    enviarBtn.isEnabled = true
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error al crear reporte: ${e.javaClass.simpleName}")
                runOnUiThread {
                    errorText.text = "Sin conexión. Revisa tu Internet e intenta de nuevo."
                    enviarBtn.isEnabled = true
                }
            }
        }
    }

    companion object {
        private const val TAG = "SEPPRO_Reporte"
        private const val EXTRA_PEDIDO = "pedido_id"
        private const val EXTRA_TIPO = "tipo"
        private const val EXTRA_PARA_ADMIN = "para_admin"
        const val MIN_DESC = 10
        const val MAX_DESC = 1000
        val TIPOS = listOf("falla", "seguimiento", "acceso")
        val PRIORIDADES = listOf("baja", "media", "alta")

        fun intent(ctx: Context, pedidoId: Int = 0, tipo: String = "falla", paraAdmin: Boolean = false) =
            Intent(ctx, NuevoReporteActivity::class.java)
                .putExtra(EXTRA_PEDIDO, pedidoId)
                .putExtra(EXTRA_TIPO, tipo)
                .putExtra(EXTRA_PARA_ADMIN, paraAdmin)
    }
}
