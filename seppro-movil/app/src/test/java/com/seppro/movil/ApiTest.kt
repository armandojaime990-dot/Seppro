package com.seppro.movil

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Práctica 11 — Método de COMUNICACIÓN con el servicio: Api.request (vía get/post/patch/rpc/subir)
 * y Api.mensajeDeError. Se usa MockWebServer (servidor HTTP local) en lugar de Supabase.
 *
 * Grafo de request():
 *  D1: accessToken == null            -> SesionExpiradaException (sin red)
 *  D2: resp.code == 401               -> SesionExpiradaException
 *  D3: !resp.isSuccessful             -> ApiException(code, mensajeDeError)
 *  else                               -> cuerpo de la respuesta
 *  (falla de red: IOException de OkHttp se propaga al llamador)
 *
 * Grafo de mensajeDeError():
 *  try JSON -> "message" o "error"; catch -> ""
 *  D4: msg contiene "jwt" && "expired" (C4a, C4b) -> SesionExpiradaException
 *  D5: msg en blanco -> "Error del servidor (código)"
 */
class ApiTest {

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

    // ---------- D1 ----------
    @Test fun sinToken_lanzaSesionExpirada_sinTocarLaRed() {
        SupabaseClient.accessToken = null
        try {
            Api.get("/rest/v1/pedidos")
            fail("Debió lanzar SesionExpiradaException")
        } catch (e: SesionExpiradaException) { /* esperado */ }
        assertEquals(0, servidor.requestCount)
    }

    // ---------- Camino exitoso (200) + cabeceras ----------
    @Test fun get200_devuelveCuerpo_yEnviaCabecerasDeAutenticacion() {
        servidor.enqueue(MockResponse().setResponseCode(200).setBody("""[{"id":1}]"""))
        assertEquals("""[{"id":1}]""", Api.get("/rest/v1/pedidos?select=id"))

        val req = servidor.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("GET", req.method)
        assertEquals("/rest/v1/pedidos?select=id", req.path)
        assertEquals("Bearer token-de-prueba", req.getHeader("Authorization"))
        assertEquals(SupabaseClient.ANON_KEY, req.getHeader("apikey"))
    }

    @Test fun post201_creado() {
        servidor.enqueue(MockResponse().setResponseCode(201).setBody("""[{"id":7}]"""))
        assertEquals("""[{"id":7}]""", Api.post("/rest/v1/mensajes", """{"texto":"hola"}"""))
        val req = servidor.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("""{"texto":"hola"}""", req.body.readUtf8())
        assertTrue(req.getHeader("Content-Type")!!.startsWith("application/json"))
    }

    @Test fun patch_usaMetodoPatch_yPreferRepresentation() {
        servidor.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
        Api.patch("/rest/v1/reportes?id=eq.5", """{"estado":"cerrado"}""")
        val req = servidor.takeRequest()
        assertEquals("PATCH", req.method)
        assertEquals("return=representation", req.getHeader("Prefer"))
    }

    @Test fun rpc_llamaALaRutaDeLaFuncion() {
        servidor.enqueue(MockResponse().setResponseCode(204))
        assertEquals("", Api.rpc("latido"))
        assertEquals("/rest/v1/rpc/latido", servidor.takeRequest().path)
    }

    @Test fun subir_enviaBytes_yNoSobrescribe() {
        servidor.enqueue(MockResponse().setResponseCode(200).setBody("""{"Key":"evidencias/5/a.jpg"}"""))
        Api.subir("/storage/v1/object/evidencias/5/a.jpg", ByteArray(1024) { 1 }, "image/jpeg")
        val req = servidor.takeRequest()
        assertEquals(1024L, req.bodySize)
        assertEquals("image/jpeg", req.getHeader("Content-Type"))
        assertEquals("false", req.getHeader("x-upsert"))
    }

    // ---------- D2: 401 ----------
    @Test(expected = SesionExpiradaException::class)
    fun respuesta401_esSesionExpirada() {
        servidor.enqueue(MockResponse().setResponseCode(401).setBody("""{"message":"JWT expired"}"""))
        Api.get("/rest/v1/pedidos")
    }

    // ---------- D3 + mensajeDeError ----------
    @Test fun respuesta403_conMensajeDelTrigger() {
        servidor.enqueue(MockResponse().setResponseCode(403)
            .setBody("""{"code":"42501","message":"Solo el administrador puede reasignar reportes"}"""))
        val e = esperarApiException { Api.rpc("reasignar_reporte") }
        assertEquals(403, e.codigo)
        assertEquals("Solo el administrador puede reasignar reportes", e.message)
    }

    @Test fun respuesta400_usaCampoError_siNoHayMessage() {
        servidor.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"Bucket not found"}"""))
        assertEquals("Bucket not found", esperarApiException { Api.get("/storage/v1/x") }.message)
    }

    @Test fun respuesta500_cuerpoNoJson_mensajeGenerico() {
        servidor.enqueue(MockResponse().setResponseCode(500).setBody("<html>Bad Gateway</html>"))
        val e = esperarApiException { Api.get("/rest/v1/pedidos") }
        assertEquals(500, e.codigo)
        assertEquals("Error del servidor (500)", e.message)
    }

    @Test fun respuesta404_jsonSinMensaje_mensajeGenerico() {
        servidor.enqueue(MockResponse().setResponseCode(404).setBody("{}"))
        assertEquals("Error del servidor (404)", esperarApiException { Api.get("/rest/v1/nada") }.message)
    }

    // D4 con C4a=V y C4b=V
    @Test(expected = SesionExpiradaException::class)
    fun storage400_jwtExpired_esSesionExpirada() {
        servidor.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"Unauthorized","message":"jwt expired"}"""))
        Api.subir("/storage/v1/object/evidencias/5/a.jpg", ByteArray(10), "image/jpeg")
    }

    // D4 con C4a=V y C4b=F
    @Test fun storage403_jwtMalformado_noEsSesionExpirada() {
        servidor.enqueue(MockResponse().setResponseCode(403).setBody("""{"message":"invalid jwt signature"}"""))
        assertEquals("invalid jwt signature", esperarApiException { Api.get("/storage/v1/x") }.message)
    }

    // D4 con C4a=F y C4b=V
    @Test fun respuesta409_textoConExpired_sinJwt() {
        servidor.enqueue(MockResponse().setResponseCode(409).setBody("""{"message":"token expired for coupon"}"""))
        assertEquals(409, esperarApiException { Api.post("/rest/v1/carrito", "{}") }.codigo)
    }

    // ---------- Excepción de red (sin conexión) ----------
    @Test fun servidorCaido_propagaIOException() {
        servidor.shutdown()
        try {
            Api.get("/rest/v1/pedidos")
            fail("Debió lanzar IOException")
        } catch (e: ApiException) {
            fail("Una falla de red no debe confundirse con un error del servidor")
        } catch (e: IOException) { /* esperado: ConnectException */ }
    }

    @Test fun conexionCortadaAMitadDeRespuesta_propagaIOException() {
        servidor.enqueue(MockResponse().setResponseCode(200)
            .setBody("[".repeat(10)).setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        try {
            Api.get("/rest/v1/pedidos")
            fail("Debió lanzar IOException")
        } catch (e: IOException) {
            assertTrue(e !is ApiException)
        }
    }

    private fun esperarApiException(bloque: () -> Unit): ApiException {
        try {
            bloque()
        } catch (e: ApiException) {
            return e
        }
        fail("Debió lanzar ApiException")
        throw IllegalStateException()
    }
}
