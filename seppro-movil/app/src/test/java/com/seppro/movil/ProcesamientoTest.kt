package com.seppro.movil

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

/**
 * Práctica 11 — Métodos de PROCESAMIENTO / CÁLCULO
 *
 * 1) SoporteApi.horaLocal(iso, conFecha)
 *    - Normaliza microsegundos a milisegundos (regex)
 *    - Ciclo: prueba 3 formatos (con 6, 3 o 0 decimales) dentro de try/catch
 *    - D1: parse devolvió null -> continue
 *    - D2: conFecha ? "dd/MM/yyyy HH:mm" : "HH:mm"
 *    - Si ningún formato sirve: respaldo iso.take(16) con 'T' -> ' '
 *
 * 2) RealtimeCanal.esperaReintento(intento): espera exponencial con tope
 *    - D1: intento < 1 -> 2000
 *    - min(2000 * 2^(min(intento-1, 4)), 30000)
 *
 * 3) Reporte.desdeJson / Mensaje.desdeJson: conversión del JSON de Supabase
 *    con campos opcionales (null) y valores por defecto.
 */
class ProcesamientoTest {

    private lateinit var zonaOriginal: TimeZone
    private lateinit var localeOriginal: Locale

    @Before fun fijarZona() {
        zonaOriginal = TimeZone.getDefault()
        localeOriginal = Locale.getDefault()
        // Querétaro (UTC-6, sin horario de verano) para resultados reproducibles
        TimeZone.setDefault(TimeZone.getTimeZone("America/Mexico_City"))
        Locale.setDefault(Locale.US)
    }

    @After fun restaurarZona() {
        TimeZone.setDefault(zonaOriginal)
        Locale.setDefault(localeOriginal)
    }

    // ===== horaLocal =====

    @Test fun hora_conMicrosegundos_formatoDeSupabase() =
        assertEquals("12:04", SoporteApi.horaLocal("2026-11-10T18:04:05.123456+00:00"))

    @Test fun hora_conMilisegundos() =
        assertEquals("12:04", SoporteApi.horaLocal("2026-11-10T18:04:05.123+00:00"))

    @Test fun hora_sinDecimales_recorreElCicloHastaElTercerFormato() =
        assertEquals("12:04", SoporteApi.horaLocal("2026-11-10T18:04:05+00:00"))

    @Test fun hora_conFecha_ramaVerdadera() =
        assertEquals("10/11/2026 12:04", SoporteApi.horaLocal("2026-11-10T18:04:05.5+00:00", conFecha = true))

    @Test fun hora_cambioDeDia_alConvertirZona() =
        assertEquals("09/11/2026 21:30", SoporteApi.horaLocal("2026-11-10T03:30:00+00:00", conFecha = true))

    @Test fun hora_textoInvalido_usaRespaldo() =
        assertEquals("no es fecha", SoporteApi.horaLocal("no es fecha"))

    @Test fun hora_sinZona_usaRespaldoConEspacio() =
        assertEquals("2026-11-10 18:04", SoporteApi.horaLocal("2026-11-10T18:04:05"))

    @Test fun hora_cadenaVacia_noLanzaExcepcion() =
        assertEquals("", SoporteApi.horaLocal(""))

    // ===== esperaReintento (valores límite) =====

    @Test fun espera_intentoCero_y_negativo() {
        assertEquals(2_000L, RealtimeCanal.esperaReintento(0))
        assertEquals(2_000L, RealtimeCanal.esperaReintento(-3))
    }

    @Test fun espera_creceExponencialmente() {
        assertEquals(2_000L, RealtimeCanal.esperaReintento(1))
        assertEquals(4_000L, RealtimeCanal.esperaReintento(2))
        assertEquals(8_000L, RealtimeCanal.esperaReintento(3))
        assertEquals(16_000L, RealtimeCanal.esperaReintento(4))
    }

    @Test fun espera_tope30s() {
        assertEquals(30_000L, RealtimeCanal.esperaReintento(5)) // 32 000 -> tope
        assertEquals(30_000L, RealtimeCanal.esperaReintento(6))
        assertEquals(30_000L, RealtimeCanal.esperaReintento(10_000)) // sin desbordar
    }

    // ===== Conversión de JSON (consistencia de datos servidor -> app) =====

    @Test fun reporte_completo_conRelacionesEmbebidas() {
        val j = JSONObject("""{"id":5,"folio":"REP005","tipo":"falla","pedido_id":12,
            "equipo":"Bomba Altamira KOR10","descripcion":"La caja llegó golpeada",
            "prioridad":"alta","estado":"en_proceso","cliente_id":"c1",
            "cliente":{"nombre":"Cliente Ana"},"agente_id":"a1","agente":{"nombre":"Agente Laura"},
            "para_admin":false,"created_at":"2026-11-10T18:04:05+00:00"}""")
        val r = Reporte.desdeJson(j)
        assertEquals("REP005", r.folio)
        assertEquals(12, r.pedidoId)
        assertEquals("Cliente Ana", r.clienteNombre)
        assertEquals("Agente Laura", r.agenteNombre)
        assertTrue(r.activo)
    }

    @Test fun reporte_camposNulos_noSeConviertenEnTextoNull() {
        val j = JSONObject("""{"id":6,"folio":"REP006","tipo":"acceso","pedido_id":null,
            "equipo":null,"descripcion":"No puedo entrar al reloj","estado":"cerrado",
            "cliente_id":"c2","agente_id":null,"agente":null,"para_admin":true,"created_at":""}""")
        val r = Reporte.desdeJson(j)
        assertNull(r.pedidoId)
        assertEquals("", r.equipo)            // no "null"
        assertNull(r.agenteId)
        assertNull(r.agenteNombre)
        assertEquals("media", r.prioridad)    // valor por defecto si falta
        assertTrue(r.paraAdmin)
        assertFalse(r.activo)
    }

    @Test fun mensaje_completo_comoLoEnviaRealtime() {
        val m = Mensaje.desdeJson(JSONObject("""{"id":42,"reporte_id":5,"emisor_id":"a1",
            "emisor_nombre":"Agente Laura","emisor_rol":"agente","dispositivo":"Google Pixel 8a",
            "texto":"Ya revisé la foto","adjunto_path":"5/abc.jpg","created_at":"2026-11-10T18:04:05.1+00:00"}"""))
        assertEquals(42L, m.id)
        assertEquals("Agente Laura", m.emisorNombre)
        assertEquals("agente", m.emisorRol)
        assertEquals("Google Pixel 8a", m.dispositivo)
        assertEquals("5/abc.jpg", m.adjunto)
    }

    @Test fun mensaje_valoresPorDefecto() {
        val m = Mensaje.desdeJson(JSONObject("""{"id":9,"emisor_id":"x","emisor_nombre":null,
            "emisor_rol":null,"dispositivo":null,"texto":"Hola","adjunto_path":null,"created_at":""}"""))
        assertEquals("Usuario", m.emisorNombre)
        assertEquals(Roles.USUARIO, m.emisorRol)
        assertEquals("Desconocido", m.dispositivo)
        assertNull(m.adjunto)
    }

    @Test(expected = org.json.JSONException::class)
    fun mensaje_sinId_lanzaExcepcion() {
        Mensaje.desdeJson(JSONObject("""{"texto":"sin id"}"""))
    }
}
