package com.seppro.movil

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Falla de la conexión GATT con el Smart Tag (el mensaje se muestra al usuario). */
class GattException(mensaje: String) : Exception(mensaje)

/**
 * Entregable 2: conexión con el Smart Tag y lectura de la telemetría por GATT.
 * connectGatt -> discoverServices -> readCharacteristic(TelemetriaTag.CARACTERISTICA) -> close.
 * Todo con tiempo límite: si el sensor no responde en 15 s se cancela y se cierra la conexión.
 */
class GattLector(context: Context) : LectorTelemetria {

    private val appContext = context.applicationContext

    @SuppressLint("MissingPermission") // VerificarFragment revisa BLUETOOTH_CONNECT antes de llamar
    override suspend fun leer(direccion: String): Telemetria = withTimeout(TIEMPO_LIMITE_MS) {
        val adapter = (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: throw GattException("Este teléfono no tiene Bluetooth")
        if (!adapter.isEnabled) throw GattException("Bluetooth apagado. Enciéndelo para leer el sensor.")
        val dispositivo = try {
            adapter.getRemoteDevice(direccion)
        } catch (e: IllegalArgumentException) {
            throw GattException("Dirección de sensor inválida")
        }

        suspendCancellableCoroutine { cont ->
            var gatt: BluetoothGatt? = null
            fun terminar(resultado: Result<Telemetria>) {
                if (!cont.isActive) return
                try { gatt?.disconnect(); gatt?.close() } catch (e: Exception) { /* ya cerrada */ }
                resultado.fold({ cont.resume(it) }, { cont.resumeWithException(it) })
            }

            val callback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(g: BluetoothGatt, status: Int, nuevoEstado: Int) {
                    when {
                        status != BluetoothGatt.GATT_SUCCESS ->
                            terminar(Result.failure(GattException("No se pudo conectar con el Smart Tag (código $status)")))
                        nuevoEstado == BluetoothProfile.STATE_CONNECTED -> {
                            Log.i(TAG, "Conectado; descubriendo servicios")
                            if (!g.discoverServices()) terminar(Result.failure(GattException("No se pudieron leer los servicios del sensor")))
                        }
                        nuevoEstado == BluetoothProfile.STATE_DISCONNECTED ->
                            terminar(Result.failure(GattException("El Smart Tag se desconectó antes de enviar la lectura")))
                    }
                }

                override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                    val caracteristica = g.getService(TelemetriaTag.SERVICIO)?.getCharacteristic(TelemetriaTag.CARACTERISTICA)
                    if (status != BluetoothGatt.GATT_SUCCESS || caracteristica == null) {
                        terminar(Result.failure(GattException("Este dispositivo no es un Smart Tag SEPPRO")))
                    } else if (!g.readCharacteristic(caracteristica)) {
                        terminar(Result.failure(GattException("El sensor rechazó la lectura")))
                    }
                }

                // Android 13+ entrega el valor como parámetro
                override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, valor: ByteArray, status: Int) =
                    recibir(valor, status)

                // Android 12 o anterior
                @Deprecated("API < 33")
                override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        @Suppress("DEPRECATION") recibir(c.value, status)
                    }
                }

                private fun recibir(valor: ByteArray?, status: Int) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        terminar(Result.failure(GattException("Error al leer el sensor (código $status)")))
                        return
                    }
                    terminar(runCatching { TelemetriaTag.leer(valor) })
                }
            }

            gatt = dispositivo.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
            if (gatt == null) terminar(Result.failure(GattException("No se pudo iniciar la conexión GATT")))
            cont.invokeOnCancellation { try { gatt?.disconnect(); gatt?.close() } catch (e: Exception) { } }
        }
    }

    companion object {
        private const val TAG = "SEPPRO_Gatt"
        const val TIEMPO_LIMITE_MS = 15_000L
    }
}

/**
 * Entregable 2: notificación del resultado al reloj con Wear OS Data Layer (MessageClient).
 * El reloj (seppro-wearos) recibe el mensaje en /seppro/verificacion y muestra una notificación.
 * Si no hay reloj vinculado no pasa nada (el reloj también ve el estado nuevo en Supabase).
 */
class RelojDataLayer(context: Context) : NotificadorReloj {
    private val appContext = context.applicationContext

    override fun notificar(pedidoId: Int, estado: String, detalle: String) {
        val datos = JSONObject().put("pedido_id", pedidoId).put("estado", estado).put("detalle", detalle)
            .toString().toByteArray(Charsets.UTF_8)
        try {
            Wearable.getNodeClient(appContext).connectedNodes.addOnSuccessListener { nodos ->
                nodos.forEach { nodo ->
                    Wearable.getMessageClient(appContext).sendMessage(nodo.id, RUTA, datos)
                        .addOnFailureListener { Log.w(TAG, "No se envió al reloj: ${it.javaClass.simpleName}") }
                }
                Log.i(TAG, "Resultado enviado a ${nodos.size} reloj(es)")
            }.addOnFailureListener { Log.w(TAG, "Wear OS no disponible: ${it.javaClass.simpleName}") }
        } catch (e: Exception) {
            Log.w(TAG, "Wear OS no disponible: ${e.javaClass.simpleName}") // teléfono sin Google Play Services
        }
    }

    companion object {
        const val RUTA = "/seppro/verificacion"
        private const val TAG = "SEPPRO_Reloj"
    }
}
