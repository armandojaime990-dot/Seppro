package com.seppro.movil

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Práctica 11 — Método de VALIDACIÓN: ValidacionLogin.validar(email, password)
 *
 * Decisiones (nodos del grafo de flujo):
 *  D1: email.isEmpty() || password.isEmpty()                         (C1a, C1b)
 *  D2: password.isBlank()                                            (C2)
 *  D3: email.length > 254 || password.length > 72                    (C3a, C3b)
 *  D4: email tiene espacios || !correoValido(email)                  (C4a, C4b)
 *  D5: password.length < 6                                           (C5)
 *  Salida final: null (válido)
 * Complejidad ciclomática: 5 decisiones + 1 = 6 (10 contando cada condición).
 */
class ValidacionLoginTest {

    private val ok = "cliente1@seppro.com"
    private val pass = "123456"

    // ---------- Camino P1: D1 verdadera ----------
    @Test fun p1a_correoVacio() = assertEquals("Llena todos los campos", ValidacionLogin.validar("", pass))
    @Test fun p1b_contrasenaVacia() = assertEquals("Llena todos los campos", ValidacionLogin.validar(ok, ""))
    @Test fun p1c_ambosVacios() = assertEquals("Llena todos los campos", ValidacionLogin.validar("", ""))

    // ---------- Camino P2: D2 verdadera (solo espacios) ----------
    @Test fun p2_contrasenaSoloEspacios() =
        assertEquals("Llena todos los campos", ValidacionLogin.validar(ok, "      "))

    // ---------- Camino P3: D3 verdadera (longitud máxima) ----------
    @Test fun p3a_correoDe255() {
        val email = "a".repeat(244) + "@seppro.com" // 255 caracteres
        assertEquals(255, email.length)
        assertEquals(ValidacionLogin.MENSAJE_GENERICO, ValidacionLogin.validar(email, pass))
    }

    @Test fun p3b_contrasenaDe73() =
        assertEquals(ValidacionLogin.MENSAJE_GENERICO, ValidacionLogin.validar(ok, "x".repeat(73)))

    // ---------- Camino P4: D4 verdadera (formato) ----------
    @Test fun p4a_correoConEspacio() =
        assertEquals("Escribe un correo válido", ValidacionLogin.validar("ana @seppro.com", pass))

    @Test fun p4b_correoSinArroba() =
        assertEquals("Escribe un correo válido", ValidacionLogin.validar("abc", pass))

    @Test fun p4c_correoSinDominio() =
        assertEquals("Escribe un correo válido", ValidacionLogin.validar("ana@seppro", pass))

    // ---------- Camino P5: D5 verdadera (mínimo) ----------
    @Test fun p5_contrasenaDe5() =
        assertEquals(ValidacionLogin.MENSAJE_GENERICO, ValidacionLogin.validar(ok, "12345"))

    // ---------- Camino P6: todas falsas -> válido ----------
    @Test fun p6_datosValidos() = assertNull(ValidacionLogin.validar(ok, pass))

    // ---------- Valores límite ----------
    @Test fun limite_contrasenaDe6_esValida() = assertNull(ValidacionLogin.validar(ok, "a".repeat(6)))
    @Test fun limite_contrasenaDe72_esValida() = assertNull(ValidacionLogin.validar(ok, "a".repeat(72)))

    @Test fun limite_correoDe254_esValido() {
        val email = "a".repeat(243) + "@seppro.com"
        assertEquals(254, email.length)
        assertNull(ValidacionLogin.validar(email, pass))
    }

    @Test fun limite_contrasenaConEspaciosYTexto_noEsBlank() =
        assertNull(ValidacionLogin.validar(ok, " 12345 "))

    // ---------- correoValido (patrón) ----------
    @Test fun patron_aceptaSubdominiosYSignos() {
        assertTrue(ValidacionLogin.correoValido("ana.perez+pruebas@mail.uteq.edu.mx"))
        assertTrue(ValidacionLogin.correoValido("2022348004@uteq.edu.mx"))
    }

    @Test fun patron_rechazaFormatosInvalidos() {
        assertFalse(ValidacionLogin.correoValido("@seppro.com"))
        assertFalse(ValidacionLogin.correoValido("ana@.com"))
        assertFalse(ValidacionLogin.correoValido("ana@@seppro.com"))
        assertFalse(ValidacionLogin.correoValido("ana@seppro.com' OR '1'='1"))
    }
}
