package com.seppro.movil

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Práctica 12 — Fase 5: pruebas de resiliencia con fallos controlados (MockWebServer).
 * Cada prueba imprime la métrica medida con el prefijo "RES-xx" (se ve en el reporte de JUnit).
 */
class ResilienciaTest {

    private lateinit var servidor: MockWebServer

    @Before fun iniciar() {
        servidor = MockWebServer()
        servidor.start()
        Api.baseUrl = servidor.url("").toString().trimEnd('/')
        SupabaseClient.accessToken = "token-de-prueba"
    }

    @After fun detener() {
        servidor.shutdown()
        SupabaseClient.cerrarSesion()
        Api.baseUrl = SupabaseClient.URL
    }

    private fun mensajeJson(id: Long, texto: String) =
        """[{"id":$id,"reporte_id":5,"emisor_id":"c1","emisor_nombre":"Cliente Ana","emisor_rol":"usuario",
           "dispositivo":"Prueba","texto":"$texto","adjunto_path":null,"created_at":"2026-11-11T18:00:00+00:00"}]"""

    // RES-03: servidor sin respuesta -> timeout controlado (readTimeout = 20 s)
    @Test fun res03_servidorSinRespuesta_timeoutControlado() {
        servidor.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val t0 = System.currentTimeMillis()
        try {
            Api.get("/rest/v1/pedidos")
            fail("Debió agotar el tiempo de espera")
        } catch (e: IOException) {
            val ms = System.currentTimeMillis() - t0
            println("RES-03 timeout detectado en $ms ms (${e.javaClass.simpleName})")
            assertTrue("timeout entre 19 y 25 s", ms in 19_000..25_000)
        }
    }

    // RES-04: HTTP 500 -> error mostrado, sin reintento (no se repite una operación que el servidor rechazó)
    @Test fun res04_http500_noSeReintenta() {
        servidor.enqueue(MockResponse().setResponseCode(500).setBody("""{"message":"Internal Server Error"}"""))
        try {
            SoporteApi.enviarMensaje(5, "Hola")
            fail("Debió lanzar ApiException")
        } catch (e: ApiException) {
            assertEquals(500, e.codigo)
        }
        println("RES-04 HTTP 500 -> ApiException, solicitudes enviadas = ${servidor.requestCount}")
        assertEquals(1, servidor.requestCount)
    }

    // RES-05: 401 -> sesión expirada (volver al login); 403 -> error de permiso con el mensaje del servidor
    @Test fun res05_401y403_seDistinguen() {
        servidor.enqueue(MockResponse().setResponseCode(401).setBody("""{"message":"JWT expired"}"""))
        try { SoporteApi.enviarMensaje(5, "Hola"); fail() } catch (e: SesionExpiradaException) { /* esperado */ }
        servidor.enqueue(MockResponse().setResponseCode(403)
            .setBody("""{"code":"42501","message":"new row violates row-level security policy"}"""))
        try { SoporteApi.enviarMensaje(5, "Hola"); fail() } catch (e: ApiException) { assertEquals(403, e.codigo) }
        println("RES-05 401 -> SesionExpiradaException | 403 -> ApiException(403); solicitudes = ${servidor.requestCount}")
        assertEquals(2, servidor.requestCount)
    }

    /**
     * RES-07 / RES-08: el servidor GUARDA el mensaje pero la respuesta nunca llega (se corta la conexión).
     * Simulación fiel de la base: índice UNIQUE en id_cliente -> el reintento recibe 409.
     */
    private fun servidorConUnique(postsGuardados: AtomicInteger, cortarPrimeraRespuesta: Boolean) {
        val idsGuardados = mutableSetOf<String>()
        val primera = AtomicInteger(0)
        servidor.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "GET") return MockResponse().setBody(mensajeJson(77, "Hola"))
                val id = JSONObject(request.body.readUtf8()).optString("id_cliente")
                if (!idsGuardados.add(id)) {
                    return MockResponse().setResponseCode(409)
                        .setBody("""{"code":"23505","message":"duplicate key value violates unique constraint \"mensajes_id_cliente_uq\""}""")
                }
                postsGuardados.incrementAndGet()
                if (cortarPrimeraRespuesta && primera.getAndIncrement() == 0) {
                    return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) // guardado, sin respuesta
                }
                return MockResponse().setResponseCode(201).setBody(mensajeJson(77, "Hola"))
            }
        }
    }

    @Test fun res07_respuestaPerdida_reintentoNoDuplica() {
        val guardados = AtomicInteger(0)
        servidorConUnique(guardados, cortarPrimeraRespuesta = true)
        val t0 = System.currentTimeMillis()
        val m = SoporteApi.enviarMensaje(5, "Hola", idCliente = "11111111-2222-3333-4444-555555555555")
        val ms = System.currentTimeMillis() - t0
        println("RES-07 respuesta perdida -> ${servidor.requestCount} solicitudes, registros guardados = ${guardados.get()}, " +
            "mensaje recuperado id=${m.id}, tiempo total = $ms ms")
        assertEquals(1, guardados.get())   // sin duplicado
        assertEquals(77L, m.id)            // la app recupera el mensaje ya guardado
    }

    @Test fun res08_reintentoManualConMismoId_noDuplica() {
        val guardados = AtomicInteger(0)
        servidorConUnique(guardados, cortarPrimeraRespuesta = false)
        val id = "99999999-8888-7777-6666-555555555555"
        SoporteApi.enviarMensaje(5, "Hola", idCliente = id)
        SoporteApi.enviarMensaje(5, "Hola", idCliente = id) // el usuario toca Enviar otra vez
        println("RES-08 doble envío con el mismo id -> registros guardados = ${guardados.get()}")
        assertEquals(1, guardados.get())
    }

    // RES-01/06: sin red -> 3 intentos con espera progresiva y error controlado; luego se recupera
    @Test fun res01_sinRed_reintentaConBackoff_yLuegoSeRecupera() {
        servidor.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        servidor.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        servidor.enqueue(MockResponse().setResponseCode(201).setBody(mensajeJson(78, "Hola")))
        val t0 = System.currentTimeMillis()
        val m = SoporteApi.enviarMensaje(5, "Hola")
        val ms = System.currentTimeMillis() - t0
        println("RES-06 recuperado al intento ${servidor.requestCount} tras $ms ms de espera progresiva (id=${m.id})")
        assertEquals(3, servidor.requestCount)
        assertTrue("espera ≥ 500 + 1000 ms", ms >= 1_500)
    }

    @Test fun res01_sinRedPersistente_fallaTrasTresIntentos() {
        repeat(3) { servidor.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)) }
        val t0 = System.currentTimeMillis()
        try {
            SoporteApi.enviarMensaje(5, "Hola")
            fail("Debió fallar")
        } catch (e: IOException) {
            println("RES-02 sin red -> error tras ${servidor.requestCount} intentos y ${System.currentTimeMillis() - t0} ms; no se confirma el envío")
        }
        assertEquals(SoporteApi.MAX_INTENTOS, servidor.requestCount)
    }

    @Test fun esperaProgresiva_valores() {
        assertEquals(500L, SoporteApi.esperaReintentoMs(1, azar = 0))
        assertEquals(1_000L, SoporteApi.esperaReintentoMs(2, azar = 0))
        assertEquals(2_000L, SoporteApi.esperaReintentoMs(3, azar = 0))
        assertEquals(4_000L, SoporteApi.esperaReintentoMs(9, azar = 0)) // tope
    }
}
