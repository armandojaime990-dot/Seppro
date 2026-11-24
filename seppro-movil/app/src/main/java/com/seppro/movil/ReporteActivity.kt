package com.seppro.movil

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread

/**
 * Práctica 7: detalle de un reporte y su conversación en tiempo real.
 *
 * - Mensajes en vivo con Supabase Realtime (RealtimeCanal).
 * - Cada mensaje muestra emisor, rol, dispositivo y hora; hay un contador por dispositivo.
 * - Toast al enviar y al recibir; se rechazan los mensajes vacíos.
 * - "Limpiar historial" solo oculta los mensajes en este teléfono (en la base se conservan).
 * - Fotos de evidencia en Supabase Storage, abiertas con una URL firmada que expira.
 * - Acciones según el rol: el agente cambia el estado, el admin además reasigna,
 *   y el cliente edita o cierra su reporte.
 */
class ReporteActivity : AppCompatActivity(), RealtimeCanal.Listener {

    private var reporteId = 0
    private var reporte: Reporte? = null
    private val mensajes = mutableListOf<Mensaje>()
    private var limpiadoHasta = 0L
    /** Último envío que falló: (texto, idCliente) para reintentarlo sin duplicar. */
    private var envioPendiente: Pair<String, String>? = null
    private lateinit var canal: RealtimeCanal

    private lateinit var cabecera: LinearLayout
    private lateinit var acciones: LinearLayout
    private lateinit var estadoConexion: TextView
    private lateinit var listaMensajes: LinearLayout
    private lateinit var scrollMensajes: ScrollView
    private lateinit var entrada: EditText
    private lateinit var errorEntrada: TextView
    private lateinit var botonEnviar: Button
    private lateinit var botonAdjuntar: Button

    private val uid get() = SupabaseClient.userId.orEmpty()
    private val claveLimpiado get() = "limpiado_${uid}_$reporteId"

    // Elegir foto de la galería o de archivos
    private val elegirFoto = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) subirFoto(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (SupabaseClient.accessToken == null) { irAlLogin(); return }

        reporteId = intent.getIntExtra(EXTRA_ID, 0)
        limpiadoHasta = Prefs.get(this).getLong(claveLimpiado, 0L)
        canal = RealtimeCanal(reporteId, this)
        construirVista()
        cargarTodo()
    }

    override fun onStart() {
        super.onStart()
        Presencia.activar()
        if (::canal.isInitialized) canal.conectar()
    }

    override fun onStop() {
        super.onStop()
        Presencia.pausar()
        if (::canal.isInitialized) canal.cerrar() // sin conexión abierta en segundo plano
    }

    // ===== Vista =====

    private fun construirVista() {
        val barra = barraConRegreso("Reporte")
        setSupportActionBar(barra)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        cabecera = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = fondoRedondeado(this@ReporteActivity, COLOR_GRIS_CLARO)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        acciones = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        estadoConexion = TextView(this).apply {
            textSize = 12f
            setPadding(0, dp(8), 0, 0)
        }

        val superior = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), 0)
            addView(cabecera, anchoCompleto())
            addView(acciones, anchoCompleto())
            addView(estadoConexion)
        }

        listaMensajes = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(12))
        }
        scrollMensajes = ScrollView(this).apply { addView(listaMensajes) }

        // Barra de escritura
        errorEntrada = TextView(this).apply {
            textSize = 12f
            setTextColor(COLOR_ERROR)
            setPadding(dp(12), 0, dp(12), 0)
            visible(false)
        }
        botonAdjuntar = Button(this).apply {
            text = "📎"
            textSize = 18f
            background = fondoRedondeado(this@ReporteActivity, COLOR_GRIS_CLARO, 8)
            setOnClickListener { elegirFoto.launch("image/*") }
        }
        entrada = campo("Escribe un mensaje", MAX_MENSAJE).apply {
            maxLines = 4
            isSingleLine = false
        }
        botonEnviar = botonPrincipal("Enviar") { enviarTexto() }

        val barraEscritura = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(8))
            setBackgroundColor(0xFFFFFFFF.toInt())
            elevation = dp(6).toFloat()
            addView(botonAdjuntar, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(entrada, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { leftMargin = dp(6); rightMargin = dp(6) })
            addView(botonEnviar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48)))
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFFFFFFF.toInt())
            addView(barra, anchoCompleto())
            addView(superior, anchoCompleto())
            addView(scrollMensajes, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(errorEntrada, anchoCompleto())
            addView(barraEscritura, anchoCompleto())
        }
        setContentView(root)
        onConexion(false)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, MENU_ACTUALIZAR, 0, "Actualizar")
        menu.add(Menu.NONE, MENU_LIMPIAR, 1, "Limpiar historial")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> { finish(); true }
        MENU_ACTUALIZAR -> { cargarTodo(); true }
        MENU_LIMPIAR -> { confirmarLimpiar(); true }
        else -> super.onOptionsItemSelected(item)
    }

    // ===== Carga de datos =====

    private fun cargarTodo() {
        thread {
            try {
                val r = SoporteApi.reporte(reporteId)
                val lista = if (r != null) SoporteApi.mensajes(reporteId) else emptyList()
                runOnUiThread {
                    if (r == null) {
                        // RLS ya no lo deja ver (p. ej. el admin lo reasignó a otro agente)
                        Toast.makeText(this, "Ya no tienes acceso a este reporte", Toast.LENGTH_LONG).show()
                        finish()
                        return@runOnUiThread
                    }
                    reporte = r
                    mensajes.clear()
                    mensajes.addAll(lista)
                    dibujarCabecera()
                    dibujarMensajes(desplazarAlFinal = true)
                }
            } catch (e: SesionExpiradaException) {
                runOnUiThread { irAlLogin() }
            } catch (e: Exception) {
                Log.w(TAG, "Error al cargar el reporte: ${e.javaClass.simpleName}")
                runOnUiThread {
                    Toast.makeText(this, "Sin conexión. Toca Actualizar en el menú.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun recargarReporte() {
        thread {
            try {
                val r = SoporteApi.reporte(reporteId)
                runOnUiThread {
                    if (r == null) {
                        Toast.makeText(this, "El reporte fue reasignado. Ya no tienes acceso.", Toast.LENGTH_LONG).show()
                        finish()
                    } else {
                        val anterior = reporte
                        reporte = r
                        dibujarCabecera()
                        if (anterior != null && anterior.estado != r.estado) {
                            Toast.makeText(this, "Estado: ${SoporteApi.etiquetaEstado(r.estado)}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: SesionExpiradaException) {
                runOnUiThread { irAlLogin() }
            } catch (e: Exception) {
                Log.w(TAG, "Error al recargar el reporte: ${e.javaClass.simpleName}")
            }
        }
    }

    // ===== Cabecera y acciones por rol =====

    private fun dibujarCabecera() {
        val r = reporte ?: return
        supportActionBar?.title = r.folio
        cabecera.removeAllViews()

        val fila = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fila.addView(insignia(SoporteApi.etiquetaEstado(r.estado), SoporteApi.colorEstado(r.estado)), ajustado(dp(6)))
        fila.addView(insignia("Prioridad ${SoporteApi.etiquetaPrioridad(r.prioridad)}", SoporteApi.colorPrioridad(r.prioridad)), ajustado(dp(6)))
        if (r.paraAdmin) fila.addView(insignia("Directo al admin", COLOR_PRIMARY), ajustado())
        cabecera.addView(fila)

        cabecera.addView(TextView(this).apply {
            text = "${r.folio} · ${SoporteApi.etiquetaTipo(r.tipo)}"
            textSize = 16f
            setTextColor(COLOR_TEXTO)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(2))
        })
        val detalles = buildList {
            if (r.pedidoId != null) add("Pedido #${r.pedidoId}")
            if (r.equipo.isNotBlank()) add("Equipo: ${r.equipo}")
            add("Cliente: ${r.clienteNombre.ifBlank { "—" }}")
            add("Atiende: " + when {
                r.agenteNombre != null -> r.agenteNombre
                r.paraAdmin -> "Administrador"
                else -> "Sin asignar"
            })
            add("Creado: ${SoporteApi.horaLocal(r.creado, conFecha = true)}")
        }
        cabecera.addView(TextView(this).apply {
            text = detalles.joinToString("\n")
            textSize = 13f
            setTextColor(COLOR_TEXTO_SUAVE)
        })
        cabecera.addView(TextView(this).apply {
            text = r.descripcion
            textSize = 14f
            setTextColor(COLOR_TEXTO)
            setPadding(0, dp(6), 0, 0)
        })

        // Acciones según el rol
        acciones.removeAllViews()
        val rol = SupabaseClient.rol
        val esAdmin = rol == Roles.ADMIN
        val esAgenteAsignado = r.agenteId == uid
        val esCliente = r.clienteId == uid

        if (esAdmin || esAgenteAsignado) {
            acciones.addView(botonSecundario("Cambiar estado") { elegirEstado() }, ajustado(dp(8)))
        }
        if (esAdmin) {
            acciones.addView(botonSecundario("Reasignar") { elegirAgente() }, ajustado(dp(8)))
        }
        if (esCliente && !esAdmin && r.activo) {
            acciones.addView(botonSecundario("Editar") { editarReporte() }, ajustado(dp(8)))
            acciones.addView(botonSecundario("Cerrar reporte") { cerrarReporte() }, ajustado())
        }
        acciones.visible(acciones.childCount > 0)
    }

    private fun elegirEstado() {
        val estados = listOf("abierto", "en_proceso", "resuelto", "cerrado")
        AlertDialog.Builder(this)
            .setTitle("Cambiar estado")
            .setItems(estados.map { SoporteApi.etiquetaEstado(it) }.toTypedArray()) { _, i ->
                guardarCambios(JSONObject().put("estado", estados[i]), "Estado actualizado")
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Admin: lista de agentes con su conexión. Si el agente se desconectó, se reasigna o lo atiende el admin. */
    private fun elegirAgente() {
        thread {
            try {
                val agentes = SoporteApi.agentes().filter { it.rol == Roles.AGENTE }
                runOnUiThread {
                    val opciones = mutableListOf("👤 Atenderlo yo (administrador)")
                    opciones += agentes.map {
                        "${if (it.enLinea) "🟢 Conectado" else "⚪ Desconectado"} · ${it.nombre} (${it.activos} activos)"
                    }
                    AlertDialog.Builder(this)
                        .setTitle("Reasignar ${reporte?.folio.orEmpty()}")
                        .setItems(opciones.toTypedArray()) { _, i ->
                            val destino = if (i == 0) null else agentes[i - 1]
                            if (destino != null && !destino.enLinea) {
                                Toast.makeText(this, "${destino.nombre} está desconectado; lo verá al volver.", Toast.LENGTH_LONG).show()
                            }
                            reasignar(destino?.id)
                        }
                        .setNegativeButton("Cancelar", null)
                        .show()
                }
            } catch (e: Exception) {
                mostrarError(e, "No se pudo cargar la lista de agentes")
            }
        }
    }

    private fun reasignar(agenteId: String?) {
        thread {
            try {
                SoporteApi.reasignar(reporteId, agenteId)
                runOnUiThread {
                    Toast.makeText(this, "Reporte reasignado", Toast.LENGTH_SHORT).show()
                    recargarReporte()
                }
            } catch (e: Exception) {
                mostrarError(e, "No se pudo reasignar")
            }
        }
    }

    /** Cliente: editar descripción y prioridad (los cambios persisten en Supabase). */
    private fun editarReporte() {
        val r = reporte ?: return
        val caja = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val desc = campo("Descripción", NuevoReporteActivity.MAX_DESC, multilinea = true).apply { setText(r.descripcion) }
        val prioridad = selector(NuevoReporteActivity.PRIORIDADES.map { SoporteApi.etiquetaPrioridad(it) })
        prioridad.setSelection(NuevoReporteActivity.PRIORIDADES.indexOf(r.prioridad).coerceAtLeast(0))
        caja.addView(etiqueta("Descripción"))
        caja.addView(desc, anchoCompleto())
        caja.addView(etiqueta("Prioridad"))
        caja.addView(prioridad, anchoCompleto())

        val dialogo = AlertDialog.Builder(this)
            .setTitle("Editar ${r.folio}")
            .setView(caja)
            .setPositiveButton("Guardar", null)
            .setNegativeButton("Cancelar", null)
            .create()
        dialogo.setOnShowListener {
            dialogo.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val texto = desc.text.toString().trim()
                if (texto.length < NuevoReporteActivity.MIN_DESC) {
                    desc.error = "Mínimo ${NuevoReporteActivity.MIN_DESC} caracteres"
                    return@setOnClickListener
                }
                guardarCambios(JSONObject()
                    .put("descripcion", texto)
                    .put("prioridad", NuevoReporteActivity.PRIORIDADES[prioridad.selectedItemPosition]),
                    "Reporte actualizado")
                dialogo.dismiss()
            }
        }
        dialogo.show()
    }

    private fun cerrarReporte() {
        AlertDialog.Builder(this)
            .setTitle("Cerrar reporte")
            .setMessage("¿Tu problema quedó resuelto? Ya no podrás editar el reporte.")
            .setPositiveButton("Cerrar reporte") { _, _ ->
                guardarCambios(JSONObject().put("estado", "cerrado"), "Reporte cerrado")
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun guardarCambios(cambios: JSONObject, aviso: String) {
        val leido = reporte?.actualizado ?: return
        thread {
            try {
                SoporteApi.actualizarReporte(reporteId, cambios, leido)
                runOnUiThread {
                    Toast.makeText(this, aviso, Toast.LENGTH_SHORT).show()
                    recargarReporte()
                }
            } catch (e: SoporteApi.ConflictoException) {
                // Práctica 12 (O4): otra persona guardó primero; no se pisa su cambio
                runOnUiThread {
                    Toast.makeText(this, "Otra persona modificó el reporte. Se cargó la versión más reciente; " +
                        "revisa y vuelve a intentarlo.", Toast.LENGTH_LONG).show()
                    recargarReporte()
                }
            } catch (e: Exception) {
                mostrarError(e, "No se pudo guardar")
            }
        }
    }

    // ===== Mensajes =====

    private fun enviarTexto() {
        val texto = entrada.text.toString().trim()
        if (texto.isEmpty()) {
            // Se rechazan mensajes vacíos o de solo espacios
            errorEntrada.text = "El mensaje no puede estar vacío"
            errorEntrada.visible(true)
            return
        }
        errorEntrada.visible(false)
        botonEnviar.isEnabled = false
        // Práctica 12 (O3): el mismo texto que falló se reenvía con el MISMO id -> no se duplica
        val idCliente = envioPendiente?.takeIf { it.first == texto }?.second ?: java.util.UUID.randomUUID().toString()
        envioPendiente = texto to idCliente
        thread {
            try {
                val m = SoporteApi.enviarMensaje(reporteId, texto, idCliente = idCliente)
                runOnUiThread {
                    envioPendiente = null
                    entrada.text.clear()
                    botonEnviar.isEnabled = true
                    agregarMensaje(m)
                    Toast.makeText(this, "Mensaje enviado", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread { botonEnviar.isEnabled = true }
                mostrarError(e, "No se pudo enviar el mensaje")
            }
        }
    }

    private fun subirFoto(uri: Uri) {
        botonAdjuntar.isEnabled = false
        Toast.makeText(this, "Subiendo foto...", Toast.LENGTH_SHORT).show()
        thread {
            try {
                val jpeg = comprimirImagen(uri) ?: throw ApiException(400, "No se pudo leer la imagen")
                val ruta = SoporteApi.subirEvidencia(reporteId, jpeg)
                val m = SoporteApi.enviarMensaje(reporteId, "📷 Foto de evidencia", ruta)
                runOnUiThread {
                    botonAdjuntar.isEnabled = true
                    agregarMensaje(m)
                    Toast.makeText(this, "Foto enviada", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread { botonAdjuntar.isEnabled = true }
                mostrarError(e, "No se pudo subir la foto")
            }
        }
    }

    /** Reduce la foto a máximo 1600 px y JPEG para no pasar del límite de 5 MB del bucket. */
    private fun comprimirImagen(uri: Uri): ByteArray? {
        val limites = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, limites) }
        if (limites.outWidth <= 0) return null
        var muestra = 1
        while (maxOf(limites.outWidth, limites.outHeight) / muestra > 1600) muestra *= 2
        val bitmap = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = muestra })
        } ?: return null
        var calidad = 85
        var bytes: ByteArray
        do {
            bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, calidad, it) }.toByteArray()
            calidad -= 15
        } while (bytes.size > 4_500_000 && calidad > 20)
        bitmap.recycle()
        return bytes
    }

    /** Pide una URL firmada (expira en 10 min) y abre la foto. */
    private fun abrirAdjunto(ruta: String) {
        thread {
            try {
                val url = SoporteApi.urlFirmada(ruta)
                runOnUiThread {
                    Toast.makeText(this, "Enlace temporal: expira en ${SoporteApi.URL_FIRMADA_SEGUNDOS / 60} minutos", Toast.LENGTH_SHORT).show()
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } catch (e: Exception) {
                        Toast.makeText(this, "No hay una app para abrir la imagen", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                mostrarError(e, "No se pudo abrir la foto")
            }
        }
    }

    /** Agrega sin duplicar (el mensaje propio llega por la respuesta y también por Realtime). */
    private fun agregarMensaje(m: Mensaje): Boolean {
        if (mensajes.any { it.id == m.id }) return false
        mensajes.add(m)
        mensajes.sortBy { it.id }
        dibujarMensajes(desplazarAlFinal = true)
        return true
    }

    private fun dibujarMensajes(desplazarAlFinal: Boolean) {
        listaMensajes.removeAllViews()
        val visibles = mensajes.filter { it.id > limpiadoHasta }

        if (visibles.isEmpty()) {
            listaMensajes.addView(TextView(this).apply {
                text = if (limpiadoHasta > 0) "Historial limpiado en este dispositivo." else "Aún no hay mensajes. Escribe el primero."
                textSize = 13f
                setTextColor(0xFF94A3B8.toInt())
                gravity = Gravity.CENTER
                setPadding(0, dp(24), 0, dp(24))
            }, anchoCompleto())
        }
        visibles.forEach { listaMensajes.addView(burbuja(it)) }

        if (desplazarAlFinal) scrollMensajes.post { scrollMensajes.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun burbuja(m: Mensaje): LinearLayout {
        val propio = m.emisorId == uid
        val caja = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = fondoRedondeado(this@ReporteActivity, if (propio) COLOR_ACCENT else COLOR_GRIS_CLARO, 12)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val colorTexto = if (propio) 0xFFFFFFFF.toInt() else COLOR_TEXTO
        val colorSuave = if (propio) 0xFFDBEAFE.toInt() else COLOR_TEXTO_SUAVE

        caja.addView(TextView(this).apply {
            text = "${if (propio) "Tú" else m.emisorNombre} · ${Roles.etiqueta(m.emisorRol)}"
            textSize = 11f
            setTextColor(colorSuave)
            setTypeface(null, Typeface.BOLD)
        })
        caja.addView(TextView(this).apply {
            text = m.texto
            textSize = 15f
            setTextColor(colorTexto)
        })
        if (m.adjunto != null) {
            caja.addView(TextView(this).apply {
                text = "Ver foto"
                textSize = 13f
                setTextColor(if (propio) 0xFFFFFFFF.toInt() else COLOR_ACCENT)
                paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
                setPadding(0, dp(4), 0, 0)
            })
            caja.setOnClickListener { abrirAdjunto(m.adjunto) }
        }
        caja.addView(TextView(this).apply {
            text = SoporteApi.horaLocal(m.creado)
            textSize = 11f
            setTextColor(colorSuave)
            gravity = Gravity.END
        }, anchoCompleto())

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (propio) Gravity.END else Gravity.START
            setPadding(if (propio) dp(48) else 0, dp(4), if (propio) 0 else dp(48), dp(4))
            addView(caja, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun confirmarLimpiar() {
        AlertDialog.Builder(this)
            .setTitle("Limpiar historial")
            .setMessage("Se ocultarán los mensajes en este dispositivo. En el servidor se conservan como evidencia.")
            .setPositiveButton("Limpiar") { _, _ ->
                limpiadoHasta = mensajes.maxOfOrNull { it.id } ?: 0L
                Prefs.get(this).edit().putLong(claveLimpiado, limpiadoHasta).apply()
                dibujarMensajes(desplazarAlFinal = false)
                Toast.makeText(this, "Historial limpiado", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ===== RealtimeCanal.Listener =====

    override fun onMensajeNuevo(mensaje: Mensaje) {
        if (agregarMensaje(mensaje) && mensaje.emisorId != uid) {
            // Notificación visual al recibir
            Toast.makeText(this, "Nuevo mensaje de ${mensaje.emisorNombre}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onReporteActualizado() = recargarReporte()

    override fun onConexion(conectado: Boolean) {
        estadoConexion.text = if (conectado) "● En vivo" else "○ Conectando al chat en tiempo real..."
        estadoConexion.setTextColor(if (conectado) 0xFF2E7D32.toInt() else 0xFFE65100.toInt())
        // Al (re)conectar, traer lo que llegó mientras no había conexión
        if (conectado && reporte != null) {
            thread {
                try {
                    val lista = SoporteApi.mensajes(reporteId)
                    runOnUiThread { lista.forEach { m -> if (mensajes.none { it.id == m.id }) mensajes.add(m) }
                        mensajes.sortBy { it.id }
                        dibujarMensajes(desplazarAlFinal = true) }
                } catch (e: Exception) { /* se reintenta con Actualizar */ }
            }
        }
    }

    // ===== Errores =====

    private fun mostrarError(e: Exception, prefijo: String) {
        Log.w(TAG, "$prefijo: ${e.javaClass.simpleName}")
        runOnUiThread {
            when (e) {
                is SesionExpiradaException -> irAlLogin()
                is ApiException -> Toast.makeText(this, "$prefijo: ${e.message}", Toast.LENGTH_LONG).show()
                else -> Toast.makeText(this, "$prefijo. Revisa tu conexión a Internet.", Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        private const val TAG = "SEPPRO_Chat"
        private const val EXTRA_ID = "reporte_id"
        private const val MENU_ACTUALIZAR = 1
        private const val MENU_LIMPIAR = 2
        const val MAX_MENSAJE = 1000

        fun intent(ctx: Context, reporteId: Int) =
            Intent(ctx, ReporteActivity::class.java).putExtra(EXTRA_ID, reporteId)
    }
}
