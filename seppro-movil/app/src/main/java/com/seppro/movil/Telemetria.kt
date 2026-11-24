package com.seppro.movil

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/** Telemetría que guarda el Smart Tag de la caja: identificador del paquete y estatus del viaje. */
data class Telemetria(val paqueteId: String, val estatus: Int) {
    /** 1 = el acelerómetro registró un golpe fuerte durante el traslado. */
    val golpe: Boolean get() = estatus == TelemetriaTag.ESTATUS_GOLPE
}

/** La lectura del sensor no tiene el formato esperado (no se envía nada al servidor). */
class TelemetriaInvalidaException(mensaje: String) : Exception(mensaje)

/**
 * Protocolo GATT del Smart Tag SEPPRO (Entregable 2).
 *
 * Servicio:        5e1a0001-7c3b-4b8e-9a51-5e9970000001
 * Característica:  5e1a0002-7c3b-4b8e-9a51-5e9970000001 (READ, y NOTIFY opcional)
 *
 * El valor de la característica admite dos formatos:
 *  - Texto UTF-8:  "SEPPRO-42;0"  (identificador del paquete ; estatus)  — también acepta "," o "|"
 *  - Binario (5 bytes): uint32 little-endian con el número de pedido + 1 byte de estatus
 * Estatus: 0 = viaje sin incidentes, 1 = golpe fuerte. Cualquier otro valor es inválido.
 *
 * Kotlin puro (sin Android) para poder probarlo con JUnit (Prácticas 13 y 14).
 */
object TelemetriaTag {
    val SERVICIO: UUID = UUID.fromString("5e1a0001-7c3b-4b8e-9a51-5e9970000001")
    val CARACTERISTICA: UUID = UUID.fromString("5e1a0002-7c3b-4b8e-9a51-5e9970000001")

    const val ESTATUS_SIN_INCIDENTES = 0
    const val ESTATUS_GOLPE = 1
    const val MAX_BYTES = 64

    private val SEPARADORES = charArrayOf(';', ',', '|')
    private val PATRON_PAQUETE = Regex("^(SEPPRO-?)?\\d{1,9}$", RegexOption.IGNORE_CASE)

    /** Convierte los bytes leídos por GATT en Telemetria o lanza TelemetriaInvalidaException. */
    fun leer(bytes: ByteArray?): Telemetria {
        if (bytes == null || bytes.isEmpty()) throw TelemetriaInvalidaException("El Smart Tag no envió datos")
        if (bytes.size > MAX_BYTES) throw TelemetriaInvalidaException("La lectura del Smart Tag es demasiado larga")
        // Texto = solo ASCII imprimible. En binario el último byte (estatus 0 o 1) nunca es imprimible.
        val esTexto = bytes.all { it in 0x20..0x7E || it == '\n'.code.toByte() || it == '\r'.code.toByte() }
        return if (esTexto) leerTexto(String(bytes, Charsets.US_ASCII).trim()) else leerBinario(bytes)
    }

    private fun leerTexto(texto: String): Telemetria {
        if (texto.none { it in SEPARADORES }) {
            throw TelemetriaInvalidaException("Formato inválido: se esperaba PAQUETE;ESTATUS")
        }
        val partes = texto.split(*SEPARADORES).map { it.trim() }
        if (partes.size != 2) throw TelemetriaInvalidaException("Formato inválido: se esperaba PAQUETE;ESTATUS")
        val paquete = partes[0]
        if (!PATRON_PAQUETE.matches(paquete)) {
            throw TelemetriaInvalidaException("Identificador de paquete inválido")
        }
        val estatus = partes[1].toIntOrNull() ?: throw TelemetriaInvalidaException("El estatus no es un número")
        return Telemetria(paquete.uppercase(), validarEstatus(estatus))
    }

    private fun leerBinario(bytes: ByteArray): Telemetria {
        if (bytes.size != 5) throw TelemetriaInvalidaException("Formato binario inválido: se esperaban 5 bytes")
        val pedido = ByteBuffer.wrap(bytes, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
        if (pedido <= 0) throw TelemetriaInvalidaException("Identificador de paquete fuera de rango")
        return Telemetria("SEPPRO-$pedido", validarEstatus(bytes[4].toInt() and 0xFF))
    }

    private fun validarEstatus(estatus: Int): Int {
        if (estatus != ESTATUS_SIN_INCIDENTES && estatus != ESTATUS_GOLPE) {
            throw TelemetriaInvalidaException("Estatus fuera de rango ($estatus): debe ser 0 o 1")
        }
        return estatus
    }

    /** "SEPPRO-0042" / "seppro42" / "42" -> 42 (igual que normalizar_paquete en Supabase). */
    fun numeroDePaquete(paqueteId: String): Int? =
        paqueteId.trim().uppercase().removePrefix("SEPPRO").removePrefix("-").trimStart('0').toIntOrNull()

    /** ¿El Smart Tag leído pertenece a este pedido? (evita verificar la caja equivocada) */
    fun correspondeAPedido(t: Telemetria, pedidoId: Int): Boolean =
        pedidoId > 0 && numeroDePaquete(t.paqueteId) == pedidoId

    /** Codifica en formato texto (lo usa el simulador y las pruebas). */
    fun codificar(t: Telemetria): ByteArray = "${t.paqueteId};${t.estatus}".toByteArray(Charsets.UTF_8)
}
