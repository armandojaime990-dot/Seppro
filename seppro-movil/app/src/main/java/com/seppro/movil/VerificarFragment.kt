package com.seppro.movil

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import android.content.pm.PackageManager
import org.json.JSONObject

/**
 * Pestaña "Verificar": escaneo BLE de dispositivos cercanos para encontrar
 * el sensor (Smart Tag) del paquete. En este entregable solo se escanea y
 * se selecciona el dispositivo; todavía no se lee su contenido.
 */
class VerificarFragment : Fragment(), BleScanner.Listener {

    private lateinit var scanner: BleScanner

    private var textoEstado: TextView? = null
    private var botonEscanear: Button? = null
    private var botonAccion: Button? = null
    private var textoSeleccionado: TextView? = null
    private var listaDispositivos: LinearLayout? = null

    private var estadoActual = BleScanner.Estado.INACTIVO
    private var ultimaLista: List<DispositivoBle> = emptyList()
    private var direccionSeleccionada: String? = null
    private var permisoBloqueado = false

    // Permisos en tiempo de ejecución (registerForActivityResult)
    private val permisoLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (scanner.tienePermisos()) {
            permisoBloqueado = false
            iniciarEscaneo()
        } else {
            // Si el sistema ya no muestra el diálogo, hay que ir a Ajustes
            permisoBloqueado = BleScanner.permisosNecesarios().any { permiso ->
                ContextCompat.checkSelfPermission(requireContext(), permiso) != PackageManager.PERMISSION_GRANTED &&
                    !shouldShowRequestPermissionRationale(permiso)
            }
            onEstado(BleScanner.Estado.PERMISO_DENEGADO)
        }
    }

    // Diálogo del sistema para encender el Bluetooth (ACTION_REQUEST_ENABLE)
    private val bluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (scanner.bluetoothEncendido()) iniciarEscaneo()
        else onEstado(BleScanner.Estado.BLUETOOTH_APAGADO)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scanner = BleScanner(requireContext(), this)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val pedidoId = arguments?.getInt(ARG_PEDIDO_ID, 0) ?: 0

        val contenido = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(16), ctx.dp(16), ctx.dp(16), ctx.dp(16))
        }

        contenido.addView(TextView(ctx).apply {
            text = "Verificar paquete"
            textSize = 20f
            setTextColor(COLOR_PRIMARY)
            setTypeface(null, Typeface.BOLD)
        })

        // Pedido seleccionado desde la pestaña Pedidos
        contenido.addView(TextView(ctx).apply {
            text = if (pedidoId > 0) "📍 Pedido #$pedidoId seleccionado"
            else "Sin pedido seleccionado. Usa \"Verificar paquete\" en un pedido En puerta."
            textSize = 14f
            setTextColor(if (pedidoId > 0) Estados.color("en_puerta") else COLOR_TEXTO_SUAVE)
            setPadding(0, ctx.dp(4), 0, ctx.dp(16))
        })

        // Estado del escaneo
        textoEstado = TextView(ctx).apply {
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            background = fondoRedondeado(ctx, COLOR_GRIS_CLARO, 8)
            setPadding(ctx.dp(12), ctx.dp(10), ctx.dp(12), ctx.dp(10))
        }
        contenido.addView(textoEstado, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // Botón Escanear / Detener
        botonEscanear = Button(ctx).apply {
            textSize = 16f
            isAllCaps = false
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener {
                if (scanner.escaneando) scanner.detener() else iniciarEscaneo()
            }
        }
        contenido.addView(botonEscanear, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, ctx.dp(52)
        ).apply { setMargins(0, ctx.dp(12), 0, 0) })

        // Acción para resolver el problema (permisos, Bluetooth, ubicación)
        botonAccion = Button(ctx).apply {
            isAllCaps = false
            setTextColor(COLOR_ACCENT)
            background = fondoRedondeado(ctx, COLOR_GRIS_CLARO, 8)
            visibility = View.GONE
        }
        contenido.addView(botonAccion, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, ctx.dp(8), 0, 0) })

        // Dispositivo seleccionado (último guardado en seppro_prefs)
        textoSeleccionado = TextView(ctx).apply {
            textSize = 14f
            setTextColor(COLOR_TEXTO)
            setPadding(0, ctx.dp(16), 0, ctx.dp(8))
        }
        contenido.addView(textoSeleccionado)
        cargarUltimoDispositivo()

        contenido.addView(TextView(ctx).apply {
            text = "Dispositivos cercanos (mayor señal primero)"
            textSize = 13f
            setTextColor(COLOR_TEXTO_SUAVE)
            setPadding(0, ctx.dp(8), 0, ctx.dp(8))
        })

        listaDispositivos = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        contenido.addView(listaDispositivos)

        return ScrollView(ctx).apply {
            setBackgroundColor(0xFFFFFFFF.toInt())
            addView(contenido)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        onEstado(BleScanner.Estado.INACTIVO)
    }

    override fun onResume() {
        super.onResume()
        // Al volver de Ajustes, limpiar el aviso si el problema ya se resolvió
        val resuelto = when (estadoActual) {
            BleScanner.Estado.UBICACION_APAGADA -> scanner.ubicacionEncendida()
            BleScanner.Estado.BLUETOOTH_APAGADO -> scanner.bluetoothEncendido()
            BleScanner.Estado.PERMISO_DENEGADO -> scanner.tienePermisos()
            else -> false
        }
        if (resuelto) onEstado(BleScanner.Estado.INACTIVO)
    }

    override fun onPause() {
        super.onPause()
        scanner.detener() // nunca seguir escaneando en segundo plano
    }

    override fun onDestroyView() {
        super.onDestroyView()
        scanner.liberar()
        textoEstado = null
        botonEscanear = null
        botonAccion = null
        textoSeleccionado = null
        listaDispositivos = null
    }

    // ===== Flujo de escaneo =====

    private fun iniciarEscaneo() {
        if (!scanner.soportaBle()) {
            onEstado(BleScanner.Estado.ERROR, "Este teléfono no tiene Bluetooth LE")
        } else if (!scanner.tienePermisos()) {
            permisoLauncher.launch(BleScanner.permisosNecesarios())
        } else {
            scanner.iniciar() // revisa Bluetooth y ubicación y avisa con onEstado
        }
    }

    private fun pedirEncenderBluetooth() {
        try {
            bluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } catch (e: SecurityException) {
            onEstado(BleScanner.Estado.PERMISO_DENEGADO)
        } catch (e: Exception) {
            onEstado(BleScanner.Estado.ERROR, "No se pudo abrir el ajuste de Bluetooth")
        }
    }

    private fun abrirAjustes(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            onEstado(BleScanner.Estado.ERROR, "No se pudieron abrir los Ajustes")
        }
    }

    // ===== BleScanner.Listener =====

    override fun onEstado(estado: BleScanner.Estado, mensaje: String?) {
        estadoActual = estado
        val ctx = context ?: return
        val estadoView = textoEstado ?: return

        val (texto, color) = when (estado) {
            BleScanner.Estado.INACTIVO -> "Inactivo. Toca Escanear para buscar el sensor." to COLOR_TEXTO_SUAVE
            BleScanner.Estado.ESCANEANDO -> "🔎 Escaneando dispositivos cercanos (10 s)..." to COLOR_ACCENT
            BleScanner.Estado.COMPLETADO -> "Escaneo terminado: ${ultimaLista.size} dispositivo(s). Toca uno para seleccionarlo." to 0xFF2E7D32.toInt()
            BleScanner.Estado.SIN_RESULTADOS -> "Sin resultados: no se encontraron dispositivos BLE cercanos." to 0xFFE65100.toInt()
            BleScanner.Estado.PERMISO_DENEGADO -> "Permiso denegado: se necesitan Bluetooth y Ubicación para escanear." to COLOR_ERROR
            BleScanner.Estado.BLUETOOTH_APAGADO -> "Bluetooth apagado. Enciéndelo para escanear." to COLOR_ERROR
            BleScanner.Estado.UBICACION_APAGADA -> "Ubicación apagada. Android la necesita para escanear BLE." to COLOR_ERROR
            BleScanner.Estado.ERROR -> "Error: ${mensaje ?: "algo salió mal"}" to COLOR_ERROR
        }
        estadoView.text = texto
        estadoView.setTextColor(color)

        botonEscanear?.apply {
            val activo = estado == BleScanner.Estado.ESCANEANDO
            text = if (activo) "Detener" else "Escanear"
            background = fondoRedondeado(ctx, if (activo) COLOR_ERROR else COLOR_ACCENT, 8)
        }

        botonAccion?.apply {
            when (estado) {
                BleScanner.Estado.PERMISO_DENEGADO -> {
                    text = if (permisoBloqueado) "Abrir ajustes de la app" else "Conceder permisos"
                    setOnClickListener {
                        if (permisoBloqueado) {
                            abrirAjustes(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", ctx.packageName, null)))
                        } else {
                            permisoLauncher.launch(BleScanner.permisosNecesarios())
                        }
                    }
                    visibility = View.VISIBLE
                }
                BleScanner.Estado.BLUETOOTH_APAGADO -> {
                    text = "Activar Bluetooth"
                    setOnClickListener { pedirEncenderBluetooth() }
                    visibility = View.VISIBLE
                }
                BleScanner.Estado.UBICACION_APAGADA -> {
                    text = "Activar ubicación"
                    setOnClickListener { abrirAjustes(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                    visibility = View.VISIBLE
                }
                else -> visibility = View.GONE
            }
        }
    }

    override fun onDispositivos(dispositivos: List<DispositivoBle>) {
        ultimaLista = dispositivos
        dibujarLista()
    }

    // ===== Lista y selección =====

    private fun dibujarLista() {
        val ctx = context ?: return
        val lista = listaDispositivos ?: return
        lista.removeAllViews()

        ultimaLista.forEach { d ->
            val seleccionado = d.direccion == direccionSeleccionada
            val fila = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = fondoRedondeado(ctx, if (seleccionado) 0xFFDBEAFE.toInt() else COLOR_GRIS_CLARO, 8)
                setPadding(ctx.dp(14), ctx.dp(10), ctx.dp(14), ctx.dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, ctx.dp(8)) }
                isClickable = true
                setOnClickListener { seleccionar(d) }
            }
            fila.addView(TextView(ctx).apply {
                text = (if (seleccionado) "✔ " else "") + d.nombreVisible
                textSize = 15f
                setTextColor(COLOR_TEXTO)
                setTypeface(null, Typeface.BOLD)
            })
            fila.addView(TextView(ctx).apply {
                text = "${d.direccion}   ·   RSSI ${d.rssi} dBm"
                textSize = 13f
                setTextColor(COLOR_TEXTO_SUAVE)
            })
            lista.addView(fila)
        }

        if (ultimaLista.isEmpty()) {
            lista.addView(TextView(ctx).apply {
                text = if (scanner.escaneando) "Buscando..." else "Aún no hay dispositivos en la lista."
                textSize = 13f
                setTextColor(0xFF94A3B8.toInt())
                gravity = Gravity.CENTER
                setPadding(0, ctx.dp(16), 0, ctx.dp(16))
            })
        }

        // Mantener el conteo del mensaje "Escaneo terminado" al día
        if (estadoActual == BleScanner.Estado.COMPLETADO) onEstado(estadoActual)
    }

    private fun seleccionar(d: DispositivoBle) {
        direccionSeleccionada = d.direccion
        textoSeleccionado?.text = "Seleccionado: ${d.nombreVisible}"

        // ultimo_dispositivo = nombre y dirección (JSON)
        val json = JSONObject().apply {
            put("nombre", d.nombreVisible)
            put("direccion", d.direccion)
        }
        context?.let { Prefs.get(it).edit().putString(Prefs.ULTIMO_DISPOSITIVO, json.toString()).apply() }
        dibujarLista()
    }

    private fun cargarUltimoDispositivo() {
        val guardado = Prefs.get(requireContext()).getString(Prefs.ULTIMO_DISPOSITIVO, null)
        if (guardado == null) {
            textoSeleccionado?.text = "Seleccionado: ninguno"
            return
        }
        try {
            val json = JSONObject(guardado)
            direccionSeleccionada = json.optString("direccion")
            textoSeleccionado?.text = "Seleccionado: ${json.optString("nombre")} (${json.optString("direccion")})"
        } catch (e: Exception) {
            textoSeleccionado?.text = "Seleccionado: ninguno"
        }
    }

    companion object {
        private const val ARG_PEDIDO_ID = "pedido_id"

        fun newInstance(pedidoId: Int) = VerificarFragment().apply {
            arguments = Bundle().apply { putInt(ARG_PEDIDO_ID, pedidoId) }
        }
    }
}
