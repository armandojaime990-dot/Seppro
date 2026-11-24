package com.seppro.movil

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prácticas 13 y 14 — AUT-01 (unitarias): lectura de la telemetría del Smart Tag.
 * Cubre entrada válida, vacía/nula, fuera de rango, resultado esperado y excepciones.
 */
class TelemetriaTagTest {

    // ---- Entrada válida / resultado esperado ----

    @Test fun textoValido_sinIncidentes() {
        val t = TelemetriaTag.leer("SEPPRO-42;0".toByteArray())
        assertEquals(Telemetria("SEPPRO-42", 0), t)
        assertFalse(t.golpe)
    }

    @Test fun textoValido_golpeFuerte_conOtrosSeparadoresYEspacios() {
        assertTrue(TelemetriaTag.leer(" seppro-42 , 1 \n".toByteArray()).golpe)
        assertEquals("SEPPRO-7", TelemetriaTag.leer("seppro-7|0".toByteArray()).paqueteId)
        assertEquals("15", TelemetriaTag.leer("15;1".toByteArray()).paqueteId)
    }

    @Test fun binarioValido_5bytesLittleEndian() {
        // pedido 59 = 0x3B (';' en ASCII): no debe confundirse con el formato de texto
        val t = TelemetriaTag.leer(byteArrayOf(0x3B, 0, 0, 0, 1))
        assertEquals(Telemetria("SEPPRO-59", 1), t)
        assertEquals("SEPPRO-300", TelemetriaTag.leer(byteArrayOf(0x2C, 0x01, 0, 0, 0)).paqueteId)
    }

    @Test fun codificarYLeer_esReversible() {
        val original = Telemetria("SEPPRO-3005", 1)
        assertEquals(original, TelemetriaTag.leer(TelemetriaTag.codificar(original)))
        assertArrayEquals("SEPPRO-1;0".toByteArray(), TelemetriaTag.codificar(Telemetria("SEPPRO-1", 0)))
    }

    // ---- Entrada vacía o nula ----

    @Test fun entradaNulaOVacia_lanzaExcepcion() {
        assertEquals("El Smart Tag no envió datos",
            assertThrows(TelemetriaInvalidaException::class.java) { TelemetriaTag.leer(null) }.message)
        assertThrows(TelemetriaInvalidaException::class.java) { TelemetriaTag.leer(ByteArray(0)) }
        assertThrows(TelemetriaInvalidaException::class.java) { TelemetriaTag.leer(";".toByteArray()) }
        assertThrows(TelemetriaInvalidaException::class.java) { TelemetriaTag.leer("SEPPRO-4;".toByteArray()) }
    }

    // ---- Valores fuera de rango ----

    @Test fun estatusFueraDeRango_lanzaExcepcion() {
        val e = assertThrows(TelemetriaInvalidaException::class.java) { TelemetriaTag.leer("SEPPRO-4;2".toByteArray()) }
        assertTrue(e.message!!.contains("fuera de rango"))
        assertThrows(TelemetriaInvalidaException::class.java) { TelemetriaTag.leer("SEPPRO-4;-1".toByteArray()) }
        assertThrows(TelemetriaInvalidaException::class.java) { TelemetriaTag.leer(byteArrayOf(5, 0, 0, 0, 7)) }
    }

    @Test fun paqueteFueraDeRango_lanzaExcepcion() {
        assertThrows(TelemetriaInvalidaException::class.java) { TelemetriaTag.leer(byteArrayOf(0, 0, 0, 0, 0)) } // pedido 0
        assertThrows(TelemetriaInvalidaException::class.java) { TelemetriaTag.leer(byteArrayOf(-1, -1, -1, -1, 0)) } // negativo
        assertThrows(TelemetriaInvalidaException::class.java) { TelemetriaTag.leer("SEPPRO-1234567890;0".toByteArray()) }
        assertThrows(TelemetriaInvalidaException::class.java) { TelemetriaTag.leer(ByteArray(65) { 'A'.code.toByte() }) }
    }

    // ---- Manejo de excepciones: formatos inválidos ----

    @Test fun formatosInvalidos_lanzanExcepcionConMensaje() {
        assertThrows(TelemetriaInvalidaException::class.java) { TelemetriaTag.leer("hola".toByteArray()) }
        assertThrows(TelemetriaInvalidaException::class.java) { TelemetriaTag.leer("SEPPRO-4;0;1".toByteArray()) }
        assertThrows(TelemetriaInvalidaException::class.java) { TelemetriaTag.leer("<script>;0".toByteArray()) }
        assertEquals("El estatus no es un número",
            assertThrows(TelemetriaInvalidaException::class.java) { TelemetriaTag.leer("SEPPRO-4;x".toByteArray()) }.message)
        assertThrows(TelemetriaInvalidaException::class.java) { TelemetriaTag.leer(byteArrayOf(1, 2, 3)) } // binario corto
    }

    // ---- El sensor debe ser el del pedido (mismo criterio que normalizar_paquete en Supabase) ----

    @Test fun correspondeAPedido() {
        assertTrue(TelemetriaTag.correspondeAPedido(Telemetria("SEPPRO-0042", 0), 42))
        assertTrue(TelemetriaTag.correspondeAPedido(Telemetria("42", 0), 42))
        assertFalse(TelemetriaTag.correspondeAPedido(Telemetria("SEPPRO-43", 0), 42))
        assertFalse(TelemetriaTag.correspondeAPedido(Telemetria("SEPPRO-42", 0), 0))
        assertNull(TelemetriaTag.numeroDePaquete("SEPPRO-"))
    }
}
