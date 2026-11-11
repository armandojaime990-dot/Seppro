package com.seppro.movil

import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import kotlin.concurrent.thread

/**
 * Pestaña "Soporte" (Práctica 7). Muestra opciones distintas según perfiles.rol:
 *  - Cliente (usuario/invitado): crear reporte, solicitud directa al admin y sus reportes.
 *  - Agente: reportes que tiene asignados.
 *  - Administrador: agentes conectados/desconectados, reportes sin asignar,
 *    solicitudes directas y todos los reportes (puede reasignar desde el detalle).
 * Qué reportes llegan lo decide el servidor con RLS, no la app.
 */
class SoporteFragment : Fragment() {

    private var lista: LinearLayout? = null
    private var mensaje: TextView? = null

    private val rol get() = SupabaseClient.rol

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val contenido = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(16), ctx.dp(16), ctx.dp(16), ctx.dp(16))
        }

        val encabezado = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        encabezado.addView(ctx.titulo(when (rol) {
            Roles.ADMIN -> "Soporte · Administración"
            Roles.AGENTE -> "Soporte · Mis asignados"
            else -> "Soporte"
        }), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        encabezado.addView(ctx.botonSecundario("Actualizar") { cargar() })
        contenido.addView(encabezado)

        contenido.addView(TextView(ctx).apply {
            text = "Sesión: ${SupabaseClient.nombre.ifBlank { "usuario" }} · ${Roles.etiqueta(rol)}"
            textSize = 13f
            setTextColor(COLOR_TEXTO_SUAVE)
            setPadding(0, ctx.dp(4), 0, ctx.dp(12))
        })

        // Opciones del cliente
        if (!Roles.esStaff(rol)) {
            contenido.addView(ctx.botonPrincipal("Nuevo reporte") {
                startActivity(NuevoReporteActivity.intent(ctx))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ctx.dp(48)))
            contenido.addView(ctx.botonSecundario("Solicitud directa al administrador") {
                startActivity(NuevoReporteActivity.intent(ctx, tipo = "seguimiento", paraAdmin = true))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ctx.dp(48)).apply { topMargin = ctx.dp(8) })
        }

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

    // onResume: al volver del chat o del formulario se ven los cambios
    override fun onResume() {
        super.onResume()
        cargar()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        lista = null
        mensaje = null
    }

    private fun cargar() {
        mensaje?.apply { text = "Cargando..."; visible(true) }
        thread {
            try {
                val reportes = SoporteApi.reportes()
                val agentes = if (rol == Roles.ADMIN) SoporteApi.agentes() else emptyList()
                activity?.runOnUiThread { mostrar(reportes, agentes) }
            } catch (e: SesionExpiradaException) {
                activity?.runOnUiThread { activity?.irAlLogin() }
            } catch (e: ApiException) {
                Log.w(TAG, "Error del servidor (${e.codigo})")
                activity?.runOnUiThread {
                    mostrarMensaje(if (e.codigo == 404)
                        "El módulo de soporte no está instalado en la base de datos (supabase-soporte.sql)."
                    else "Error del servidor. Toca Actualizar.")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error de red: ${e.javaClass.simpleName}")
                activity?.runOnUiThread { mostrarMensaje("Sin conexión a Internet. Toca Actualizar.") }
            }
        }
    }

    private fun mostrar(reportes: List<Reporte>, agentes: List<AgenteEstado>) {
        val l = lista ?: return
        val ctx = context ?: return
        l.removeAllViews()
        mensaje?.visible(false)

        when (rol) {
            Roles.ADMIN -> {
                l.addView(ctx.etiqueta("Agentes de atención"))
                val soloAgentes = agentes.filter { it.rol == Roles.AGENTE }
                if (soloAgentes.isEmpty()) {
                    l.addView(textoVacio("No hay usuarios con rol agente. Asígnalo en la web (Admin > Usuarios)."))
                }
                soloAgentes.forEach { a ->
                    l.addView(TextView(ctx).apply {
                        text = "${if (a.enLinea) "🟢" else "⚪"} ${a.nombre} · " +
                            (if (a.enLinea) "Conectado" else "Desconectado") + " · ${a.activos} activos"
                        textSize = 14f
                        setTextColor(COLOR_TEXTO)
                        setPadding(0, ctx.dp(2), 0, ctx.dp(2))
                    })
                }

                val agentesDesconectados = soloAgentes.filter { !it.enLinea }.map { it.id }.toSet()
                seccion(l, "Sin asignar o con agente desconectado", reportes.filter {
                    it.activo && !it.paraAdmin && (it.agenteId == null || it.agenteId in agentesDesconectados)
                })
                seccion(l, "Solicitudes directas al administrador", reportes.filter { it.paraAdmin && it.activo })
                seccion(l, "Todos los reportes", reportes)
            }
            Roles.AGENTE -> {
                seccion(l, "Activos", reportes.filter { it.activo })
                seccion(l, "Resueltos y cerrados", reportes.filter { !it.activo })
            }
            else -> {
                if (reportes.isEmpty()) {
                    l.addView(textoVacio("No tienes reportes. Si tu bomba llegó dañada o tienes dudas de tu entrega, crea uno."))
                } else {
                    seccion(l, "Mis reportes", reportes)
                }
            }
        }
    }

    private fun seccion(l: LinearLayout, titulo: String, reportes: List<Reporte>) {
        val ctx = context ?: return
        l.addView(ctx.etiqueta("$titulo (${reportes.size})").apply {
            setTypeface(null, Typeface.BOLD)
            setPadding(0, ctx.dp(16), 0, ctx.dp(6))
        })
        if (reportes.isEmpty()) l.addView(textoVacio("Nada por aquí."))
        reportes.forEach { l.addView(tarjeta(it)) }
    }

    private fun tarjeta(r: Reporte): View {
        val ctx = requireContext()
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = fondoRedondeado(ctx, COLOR_GRIS_CLARO)
            setPadding(ctx.dp(14), ctx.dp(12), ctx.dp(14), ctx.dp(12))
            layoutParams = anchoCompleto().apply { bottomMargin = ctx.dp(10) }
            isClickable = true
            setOnClickListener { startActivity(ReporteActivity.intent(ctx, r.id)) }
        }
        card.addView(TextView(ctx).apply {
            text = "${r.folio} · ${SoporteApi.etiquetaTipo(r.tipo)}"
            textSize = 15f
            setTextColor(COLOR_TEXTO)
            setTypeface(null, Typeface.BOLD)
        })
        val partes = buildList {
            if (r.pedidoId != null) add("Pedido #${r.pedidoId}")
            if (r.equipo.isNotBlank()) add(r.equipo)
            if (Roles.esStaff(rol)) add("Cliente: ${r.clienteNombre.ifBlank { "—" }}")
            add("Atiende: " + (r.agenteNombre ?: if (r.paraAdmin) "Administrador" else "Sin asignar"))
        }
        card.addView(TextView(ctx).apply {
            text = partes.joinToString(" · ")
            textSize = 13f
            setTextColor(COLOR_TEXTO_SUAVE)
            setPadding(0, ctx.dp(2), 0, ctx.dp(6))
        })
        val fila = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        fila.addView(ctx.insignia(SoporteApi.etiquetaEstado(r.estado), SoporteApi.colorEstado(r.estado)), ajustado(ctx.dp(6)))
        fila.addView(ctx.insignia(SoporteApi.etiquetaPrioridad(r.prioridad), SoporteApi.colorPrioridad(r.prioridad)), ajustado())
        card.addView(fila)
        return card
    }

    private fun textoVacio(texto: String) = TextView(requireContext()).apply {
        text = texto
        textSize = 13f
        setTextColor(0xFF94A3B8.toInt())
        setPadding(0, requireContext().dp(4), 0, requireContext().dp(4))
    }

    private fun mostrarMensaje(texto: String) {
        mensaje?.apply { text = texto; visible(true) }
    }

    companion object {
        private const val TAG = "SEPPRO_Soporte"
    }
}
