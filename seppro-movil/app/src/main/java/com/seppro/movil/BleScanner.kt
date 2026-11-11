package com.seppro.movil

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat

/** Dispositivo BLE encontrado (sin duplicados: la clave es la dirección). */
data class DispositivoBle(val nombre: String?, val direccion: String, val rssi: Int) {
    val nombreVisible: String get() = if (nombre.isNullOrBlank()) "Sin nombre" else nombre
}

/**
 * BleScanner - Escaneo Bluetooth Low Energy con la API nativa (BluetoothLeScanner).
 * Se detiene solo a los 10 segundos o antes con detener().
 * Todos los avisos al Listener llegan en el hilo principal.
 */
class BleScanner(context: Context, private val listener: Listener) {

    enum class Estado { INACTIVO, ESCANEANDO, COMPLETADO, SIN_RESULTADOS, PERMISO_DENEGADO, BLUETOOTH_APAGADO, UBICACION_APAGADA, ERROR }

    interface Listener {
        fun onDispositivos(dispositivos: List<DispositivoBle>)
        fun onEstado(estado: Estado, mensaje: String? = null)
    }

    private val appContext = context.applicationContext
    private val adapter: BluetoothAdapter? =
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private val handler = Handler(Looper.getMainLooper())
    private val dispositivos = LinkedHashMap<String, DispositivoBle>()
    private val detenerRunnable = Runnable { detener() }
    private var avisoPendiente = false

    var escaneando = false
        private set

    // ===== Comprobaciones previas =====

    fun soportaBle(): Boolean =
        adapter != null && appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)

    fun bluetoothEncendido(): Boolean = adapter?.isEnabled == true

    fun tienePermisos(): Boolean = permisosNecesarios().all {
        ContextCompat.checkSelfPermission(appContext, it) == PackageManager.PERMISSION_GRANTED
    }

    /** Hasta Android 11 (y sin neverForLocation en 12+), el escaneo BLE no devuelve nada con la ubicación apagada. */
    fun ubicacionEncendida(): Boolean {
        val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return true
        return LocationManagerCompat.isLocationEnabled(lm)
    }

    // ===== Escaneo =====

    @SuppressLint("MissingPermission") // tienePermisos() se revisa antes de escanear
    fun iniciar() {
        if (escaneando) return
        if (!soportaBle()) { listener.onEstado(Estado.ERROR, "Este teléfono no tiene Bluetooth LE"); return }
        if (!tienePermisos()) { listener.onEstado(Estado.PERMISO_DENEGADO); return }
        if (!bluetoothEncendido()) { listener.onEstado(Estado.BLUETOOTH_APAGADO); return }
        if (!ubicacionEncendida()) { listener.onEstado(Estado.UBICACION_APAGADA); return }

        // Es null si el Bluetooth se apagó justo ahora
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) { listener.onEstado(Estado.BLUETOOTH_APAGADO); return }

        dispositivos.clear()
        listener.onDispositivos(emptyList())

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            scanner.startScan(null, settings, callback)
        } catch (e: SecurityException) {
            listener.onEstado(Estado.PERMISO_DENEGADO)
            return
        } catch (e: Exception) {
            listener.onEstado(Estado.ERROR, "No se pudo iniciar el escaneo: ${e.message}")
            return
        }

        escaneando = true
        listener.onEstado(Estado.ESCANEANDO)
        handler.postDelayed(detenerRunnable, DURACION_MS)
    }

    @SuppressLint("MissingPermission")
    fun detener() {
        handler.removeCallbacks(detenerRunnable)
        if (!escaneando) return
        escaneando = false
        try {
            adapter?.bluetoothLeScanner?.stopScan(callback)
        } catch (e: Exception) {
            // Bluetooth apagado o permiso revocado durante el escaneo: ya está detenido
        }
        enviarDispositivos()
        listener.onEstado(if (dispositivos.isEmpty()) Estado.SIN_RESULTADOS else Estado.COMPLETADO)
    }

    /** Detiene el escaneo y cancela avisos pendientes (al destruir la vista). */
    fun liberar() {
        detener()
        handler.removeCallbacksAndMessages(null)
    }

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handler.post { agregar(result) }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            handler.post { results.forEach { agregar(it) } }
        }

        override fun onScanFailed(errorCode: Int) {
            handler.post {
                handler.removeCallbacks(detenerRunnable)
                escaneando = false
                listener.onEstado(Estado.ERROR, "El escaneo falló (código $errorCode). Intenta de nuevo.")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun agregar(result: ScanResult) {
        if (!escaneando) return
        val direccion = result.device?.address ?: return
        // Primero el nombre anunciado; si no, el nombre del sistema (necesita BLUETOOTH_CONNECT en 12+)
        val nombre = result.scanRecord?.deviceName
            ?: (try { result.device.name } catch (e: SecurityException) { null })
            ?: dispositivos[direccion]?.nombre
        dispositivos[direccion] = DispositivoBle(nombre, direccion, result.rssi)

        // Agrupar avisos para no redibujar la lista en cada paquete recibido
        if (!avisoPendiente) {
            avisoPendiente = true
            handler.postDelayed({ avisoPendiente = false; enviarDispositivos() }, 400)
        }
    }

    private fun enviarDispositivos() {
        listener.onDispositivos(dispositivos.values.sortedByDescending { it.rssi })
    }

    companion object {
        const val DURACION_MS = 10_000L

        /**
         * ACCESS_FINE_LOCATION siempre; en Android 12+ también BLUETOOTH_SCAN y BLUETOOTH_CONNECT.
         * ACCESS_COARSE_LOCATION va junto con FINE porque Android 12+ ignora la
         * petición de ubicación precisa si se pide sola.
         */
        fun permisosNecesarios(): Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
                )
            } else {
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            }
    }
}
