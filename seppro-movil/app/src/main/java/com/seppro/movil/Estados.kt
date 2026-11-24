package com.seppro.movil

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

/**
 * Estados del pedido: mismos valores que Supabase (pedidos_estado_check),
 * Angular (supabase.service.ts) y el reloj (seppro-wearos/MainActivity).
 */
object Estados {
    /** Pedidos cerrados: van al Historial; el resto se muestra en Pedidos. */
    val CERRADOS = setOf("entregado", "entregado_verificado", "entregado_con_danos", "rechazado")

    /** Estados en los que el sensor detectó un problema: se puede abrir un reporte de falla. */
    val CON_DANO = setOf("entregado_con_danos", "alerta_integridad")

    // Etiquetas iguales a la web (mis-pedidos)
    fun etiqueta(estado: String): String = when (estado) {
        "pendiente" -> "Pendiente"
        "pagado" -> "Pagado"
        "enviado" -> "Enviado"
        "en_camino" -> "En Camino"
        "entregado" -> "Entregado"
        "en_puerta" -> "En puerta (listo para verificar)"
        "entregado_verificado" -> "Entregado y verificado"
        "entregado_con_danos" -> "Entregado con daños"
        "alerta_integridad" -> "Alerta de integridad"
        "rechazado" -> "Equipo rechazado"
        else -> estado
    }

    // Íconos iguales al reloj
    fun icono(estado: String): String = when (estado) {
        "pagado" -> "💳"
        "enviado" -> "📦"
        "en_camino" -> "🚚"
        "entregado" -> "✅"
        "en_puerta" -> "📍"
        "entregado_verificado" -> "✅"
        "entregado_con_danos" -> "⚠️"
        "alerta_integridad" -> "🚨"
        "rechazado" -> "⛔"
        else -> "🕒"
    }

    // Colores iguales al reloj
    fun color(estado: String): Int = when (estado) {
        "pagado" -> 0xFF4CAF50.toInt()
        "enviado" -> 0xFF2196F3.toInt()
        "en_camino" -> 0xFF9C27B0.toInt()
        "entregado" -> 0xFF0072C6.toInt()
        "en_puerta" -> 0xFF009688.toInt()
        "entregado_verificado" -> 0xFF2E7D32.toInt()
        "entregado_con_danos" -> 0xFFEF5350.toInt() // Rojo
        "alerta_integridad" -> 0xFFEF5350.toInt() // Rojo
        "rechazado" -> 0xFF6D4C41.toInt() // Café: devolución en proceso
        else -> 0xFFFFA000.toInt() // Naranja (pendiente)
    }
}

/** SharedPreferences "seppro_prefs". Nunca se guarda la contraseña. */
object Prefs {
    const val NOMBRE = "seppro_prefs"
    const val ULTIMO_EMAIL = "ultimo_email"
    const val ULTIMO_DISPOSITIVO = "ultimo_dispositivo"

    fun get(context: Context) = context.getSharedPreferences(NOMBRE, Context.MODE_PRIVATE)
}

// Colores de la marca (iguales a la web y al reloj)
const val COLOR_PRIMARY = 0xFF003D7A.toInt()
const val COLOR_ACCENT = 0xFF0072C6.toInt()
const val COLOR_TEXTO = 0xFF0F172A.toInt()
const val COLOR_TEXTO_SUAVE = 0xFF64748B.toInt()
const val COLOR_GRIS_CLARO = 0xFFF1F5F9.toInt()
const val COLOR_ERROR = 0xFFEF5350.toInt()

fun Context.dp(valor: Int): Int = (valor * resources.displayMetrics.density).toInt()

fun fondoRedondeado(context: Context, color: Int, radioDp: Int = 12): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = context.dp(radioDp).toFloat()
    }

/**
 * Tarjeta de un pedido. Si el pedido está "en_puerta" y se pasa onVerificar,
 * se agrega el botón "Verificar paquete".
 */
fun crearTarjetaPedido(
    context: Context,
    pedido: JSONObject,
    onVerificar: ((Int) -> Unit)?,
    onReportar: ((Int) -> Unit)? = null
): View {
    val id = pedido.optInt("id", 0)
    val estado = pedido.optString("estado", "pendiente").lowercase()

    // Nombre del producto real (igual que en el reloj)
    var nombreProducto = "Pedido #$id"
    val items = pedido.optJSONArray("pedido_items")
    if (items != null && items.length() > 0) {
        nombreProducto = items.getJSONObject(0).optString("nombre_producto", "Producto")
        if (items.length() > 1) nombreProducto += " y ${items.length() - 1} más"
    }

    val card = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = fondoRedondeado(context, COLOR_GRIS_CLARO)
        setPadding(context.dp(16), context.dp(14), context.dp(16), context.dp(14))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, context.dp(12)) }
    }

    card.addView(TextView(context).apply {
        text = nombreProducto
        textSize = 16f
        setTextColor(COLOR_TEXTO)
        setTypeface(null, Typeface.BOLD)
        maxLines = 2
    })

    val total = pedido.optDouble("total", 0.0)
    card.addView(TextView(context).apply {
        text = "Pedido #$id  ·  $%,.2f".format(total)
        textSize = 13f
        setTextColor(COLOR_TEXTO_SUAVE)
        setPadding(0, context.dp(2), 0, context.dp(8))
    })

    // Insignia de estado con el color del estado
    val color = Estados.color(estado)
    card.addView(TextView(context).apply {
        text = "${Estados.icono(estado)}  ${Estados.etiqueta(estado)}"
        textSize = 13f
        setTextColor(color)
        setTypeface(null, Typeface.BOLD)
        background = fondoRedondeado(context, (color and 0x00FFFFFF) or 0x1F000000, 16)
        setPadding(context.dp(10), context.dp(4), context.dp(10), context.dp(4))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    })

    if (estado == "en_puerta" && onVerificar != null) {
        card.addView(Button(context).apply {
            text = "Verificar paquete"
            isAllCaps = false
            setTextColor(0xFFFFFFFF.toInt())
            background = fondoRedondeado(context, COLOR_ACCENT, 8)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, context.dp(12), 0, 0) }
            setOnClickListener { onVerificar(id) }
        })
    }

    // Práctica 7: si el Smart Tag registró un golpe, el cliente abre un reporte de falla
    if (estado in Estados.CON_DANO && onReportar != null) {
        card.addView(Button(context).apply {
            text = "Reportar falla"
            isAllCaps = false
            setTextColor(0xFFFFFFFF.toInt())
            background = fondoRedondeado(context, COLOR_ERROR, 8)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, context.dp(12), 0, 0) }
            setOnClickListener { onReportar(id) }
        })
    }

    return card
}
