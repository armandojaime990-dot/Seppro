package com.seppro.movil

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.widget.Toolbar

/** Piezas de interfaz reutilizables (Views creadas por código, sin XML). */

fun Context.titulo(texto: String, tamano: Float = 20f) = TextView(this).apply {
    text = texto
    textSize = tamano
    setTextColor(COLOR_PRIMARY)
    setTypeface(null, Typeface.BOLD)
}

fun Context.etiqueta(texto: String) = TextView(this).apply {
    text = texto
    textSize = 13f
    setTextColor(COLOR_TEXTO_SUAVE)
    setPadding(0, dp(12), 0, dp(4))
}

fun Context.campo(hint: String, max: Int, multilinea: Boolean = false) = EditText(this).apply {
    this.hint = hint
    textSize = 15f
    setTextColor(COLOR_TEXTO)
    setHintTextColor(0xFF94A3B8.toInt())
    filters = arrayOf(InputFilter.LengthFilter(max))
    background = fondoRedondeado(this@campo, COLOR_GRIS_CLARO, 8)
    setPadding(dp(14), dp(12), dp(14), dp(12))
    if (multilinea) {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        minLines = 3
        maxLines = 6
        gravity = android.view.Gravity.TOP
    } else {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        setSingleLine()
    }
}

fun Context.selector(opciones: List<String>) = Spinner(this).apply {
    adapter = ArrayAdapter(this@selector, android.R.layout.simple_spinner_dropdown_item, opciones)
    background = fondoRedondeado(this@selector, COLOR_GRIS_CLARO, 8)
    setPadding(dp(8), dp(8), dp(8), dp(8))
}

fun Context.botonPrincipal(texto: String, color: Int = COLOR_ACCENT, onClick: () -> Unit) = Button(this).apply {
    text = texto
    textSize = 15f
    isAllCaps = false
    setTextColor(0xFFFFFFFF.toInt())
    background = fondoRedondeado(this@botonPrincipal, color, 8)
    setOnClickListener { onClick() }
}

fun Context.botonSecundario(texto: String, onClick: () -> Unit) = Button(this).apply {
    text = texto
    textSize = 14f
    isAllCaps = false
    setTextColor(COLOR_ACCENT)
    background = fondoRedondeado(this@botonSecundario, COLOR_GRIS_CLARO, 8)
    setPadding(dp(12), 0, dp(12), 0)
    setOnClickListener { onClick() }
}

/** Insignia de color (estado, prioridad, rol). */
fun Context.insignia(texto: String, color: Int) = TextView(this).apply {
    text = texto
    textSize = 12f
    setTextColor(color)
    setTypeface(null, Typeface.BOLD)
    background = fondoRedondeado(this@insignia, (color and 0x00FFFFFF) or 0x1F000000, 16)
    setPadding(dp(10), dp(3), dp(10), dp(3))
}

fun anchoCompleto(margenSuperior: Int = 0) = LinearLayout.LayoutParams(
    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
).apply { topMargin = margenSuperior }

fun ajustado(margenDerecho: Int = 0) = LinearLayout.LayoutParams(
    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
).apply { rightMargin = margenDerecho }

/** Toolbar con flecha de regreso para pantallas secundarias. */
fun Activity.barraConRegreso(tituloBarra: String): Toolbar = Toolbar(this).apply {
    title = tituloBarra
    setTitleTextColor(0xFFFFFFFF.toInt())
    setBackgroundColor(COLOR_PRIMARY)
    setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
    navigationIcon?.setTint(0xFFFFFFFF.toInt())
    setNavigationOnClickListener { finish() }
}

/** Si la sesión venció: limpiar y volver al login (sin poder regresar con "atrás"). */
fun Activity.irAlLogin() {
    SupabaseClient.cerrarSesion()
    startActivity(Intent(this, LoginActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
    finish()
}

fun View.visible(si: Boolean) {
    visibility = if (si) View.VISIBLE else View.GONE
}
